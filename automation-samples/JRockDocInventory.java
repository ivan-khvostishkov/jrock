// A JRock automation: one scanned PDF in, a text twin of it and a file name out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java JRockDocInventory.java [document.pdf]
//
// Without the argument it asks for the PDF in a file chooser. Everything else it needs
// it finds beside itself, in this directory: jrock.jar and the two prompts.
//
// COPY jrock.jar HERE FIRST. It is not in the repository - the repository carries no
// jars - and nothing downloads it: take it from a "Reproducible build" artifact, or
// build it with .github/build/BuildJar.java, and put it next to this file. That copy is
// what enables automations, and copying it is deliberately a manual act: an automation
// directory is a jar, some prompts and the scripts that chain them, all of them files
// you put there yourself.
//
// What this one does is the chain the README describes under "Prompt library and
// chaining", with nobody pressing the keys:
//
//   1. jrock-prompt-doc-to-ascii.txt  + the PDF as page images  -> the document as
//      plain text, saved as <pdf name>.txt beside the PDF.
//   2. jrock-prompt-doc-inventory.txt + that text file          -> one file name.
//   3. Both files renamed to it, if you say so.
//
// The JRock window is the real one, and it is in front of you the whole way: the prompt
// fills with include tokens as the pages are converted, the log reports every step, and
// both answers stay in the transcript. The prompt is read-only until the automation is
// finished and editable again afterwards - the conversation is there to be carried on
// by hand, which is the point of driving the window rather than a headless copy of it.
//
// How it talks to JRock: `java JRockDocInventory.java` compiles this file with nothing
// on its classpath, so nothing here can name a JRock type. The jar is loaded at run
// time and the API called by reflection - see api() and the one-line wrappers under it.
// The API is the "Automation API (public)" section of JRock.java, and this file is meant
// to be copied and rewritten for chains of your own.

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JRockDocInventory {

    // Everything this automation expects to find beside itself.
    private static final String JAR = "jrock.jar";
    private static final String PROMPT_ASCII = "jrock-prompt-doc-to-ascii.txt";
    private static final String PROMPT_NAME = "jrock-prompt-doc-inventory.txt";

    // Starting a JVM's worth of Swing and fetching the model list, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // A document's worth of page images, read by a frontier model. Generous on purpose:
    // the failure this guards against is a request that never comes back, and a minute
    // either way is cheaper than giving up on an answer that was on its way.
    private static final long REPLY_TIMEOUT_MS = 20 * 60_000;

    // The JRock class, loaded out of jrock.jar. Null until load() has run, which is
    // what end() and parent() check before they try to use it.
    private static Class<?> jrock;

    // Why an automation stopped, said in one sentence and shown in one dialog.
    //
    // A RuntimeException so the steps below read as the sequence they are rather than as
    // a ladder of if-blocks: every API call returns null for "fine" and a sentence for
    // "not fine", and check() turns the second into one of these.
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    public static void main(String[] args) {
        String summary = null;
        String stopped = null;
        try {
            summary = run(args);
        } catch (Stop stop) {
            stopped = stop.getMessage();
        } catch (Exception ex) {
            stopped = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        // Nothing to say: the chooser was cancelled, and nothing has been started.
        if (summary == null && stopped == null) return;

        // The prompt becomes editable again and Send is released either way - an
        // automation that failed must not leave the window locked.
        end(stopped == null ? summary : "stopped - " + stopped);
        if (stopped == null) {
            System.out.println("Finished: " + summary);
            tell("Finished.\n\n" + summary, "Automation finished",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } else {
            System.out.println("Stopped: " + stopped);
            tell("The automation stopped:\n\n" + stopped
                    + "\n\nThe JRock log says where it got to.",
                    "Automation stopped", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    // The whole chain, start to finish. Returns what to report, or null when there was
    // nothing to do; throws Stop with the reason for anything else.
    private static String run(String[] args) throws IOException {
        Path here = ownDirectory();
        System.out.println("Automation directory: " + here);

        Path jar = here.resolve(JAR);
        if (!Files.isRegularFile(jar)) {
            throw new Stop("there is no " + JAR + " in " + here + ".\n\nCopy one there "
                    + "(from a \"Reproducible build\" artifact, or built with "
                    + ".github/build/BuildJar.java) and run this again.");
        }
        Path ascii = mustExist(here.resolve(PROMPT_ASCII));
        Path inventory = mustExist(here.resolve(PROMPT_NAME));

        // The document. Asked for before JRock is started, so a cancelled chooser
        // leaves nothing behind at all.
        Path pdf = (args.length > 0 && !args[0].isBlank())
                ? Paths.get(args[0]).toAbsolutePath().normalize()
                : choosePdf();
        if (pdf == null) {
            System.out.println("No document chosen - nothing to do.");
            return null;
        }
        if (!Files.isRegularFile(pdf)) throw new Stop("not a file: " + pdf);
        System.out.println("Document: " + pdf);

        // ---- Start JRock and wait for it to be usable ----------------------
        load(jar);
        // The real entry point, which shows the window on the event dispatch thread and
        // returns at once. Everything after this runs on THIS thread, which is exactly
        // what the automation API asks for.
        api("main", new Class<?>[] { String[].class }, (Object) new String[0]);
        check(awaitReady(READY_TIMEOUT_MS));
        check(begin("doc inventory of " + pdf.getFileName()));

        // ---- Pass one: the document as plain text --------------------------
        System.out.println("Pass 1: " + PROMPT_ASCII);
        check(loadPrompt(ascii));
        dropPlaceholders();
        check(include(pdf, "pdf"));
        String text = reply(send());

        // Beside the PDF and named after it, which is what makes the pair findable
        // before they are renamed - and what the second pass is about to read back.
        Path twin = free(pdf.getParent(), stem(pdf), ".txt");
        Files.write(twin, text.getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote " + twin);

        // ---- Pass two: a name for it --------------------------------------
        System.out.println("Pass 2: " + PROMPT_NAME);
        check(loadPrompt(inventory));
        dropPlaceholders();
        check(include(twin, "txt"));
        String answer = reply(send());
        String base = baseName(answer);
        if (base.isEmpty()) {
            throw new Stop("the second answer held no usable file name:\n\n"
                    + answer.trim());
        }
        System.out.println("Suggested name: " + base);

        // ---- The rename, which is the user's call --------------------------
        // Asked rather than assumed: this is the one step that changes files the user
        // owns, and the name is a model's suggestion, not a fact about the document.
        if (!ask("Rename both files to\n\n    " + base + "\n\nfrom\n\n    "
                + pdf.getFileName() + "\n    " + twin.getFileName() + "\n\nin "
                + pdf.getParent() + " ?")) {
            return "kept " + pdf.getFileName() + " and " + twin.getFileName()
                    + "; the suggested name was " + base;
        }
        Path pdfNow = renameTo(pdf, base);
        Path twinNow = renameTo(twin, base);
        System.out.println("Renamed to " + pdfNow + " and " + twinNow);
        return "renamed to " + pdfNow.getFileName() + " and " + twinNow.getFileName();
    }

    // ---- Where this file is ------------------------------------------------
    // The directory this source file sits in, which is where the jar and the prompts
    // are looked for. An automation directory is self-contained by design, so "beside
    // me" has to be answerable however the file was launched.
    //
    // jdk.launcher.sourcefile is set by the launcher in source-file mode (`java
    // Foo.java`), which is how this is meant to be run. The code source covers the
    // other way - a compiled class, or this file inside a jar - and the working
    // directory is the last resort, which is right often enough to be worth trying.
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockDocInventory.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                Path at = Paths.get(code.getLocation().toURI()).toAbsolutePath().normalize();
                Path parent = Files.isDirectory(at) ? at : at.getParent();
                if (parent != null) return parent;
            }
        } catch (RuntimeException | java.net.URISyntaxException ignore) {
            // Fall through to the working directory.
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    // A file the automation cannot run without.
    private static Path mustExist(Path file) {
        if (!Files.isRegularFile(file)) throw new Stop("there is no " + file + ".");
        return file;
    }

    // ---- Reaching JRock ----------------------------------------------------
    // Loads jrock.jar and finds the JRock class in it.
    //
    // A class loader of its own, with this one's as its parent: JRock needs the JDK,
    // which it gets from up the chain, and nothing here needs to be visible to JRock.
    // The loader is never closed - JRock's window goes on living in it after this
    // automation's own work is done, which is the whole idea.
    private static void load(Path jar) {
        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[] { jar.toUri().toURL() },
                    JRockDocInventory.class.getClassLoader());
            jrock = Class.forName("JRock", true, loader);
            System.out.println("Loaded JRock from " + jar);
        } catch (ClassNotFoundException ex) {
            throw new Stop(jar + " does not contain a JRock class.");
        } catch (java.net.MalformedURLException ex) {
            throw new Stop("could not read " + jar + ": " + ex.getMessage());
        }
    }

    // Calls one public static method of JRock and hands back whatever it returned.
    //
    // The single point of reflection in this file. Everything below it is a one-line
    // wrapper, so a step in run() reads as the step it is and the API's shape - which
    // types, which order - is written down once.
    private static Object api(String name, Class<?>[] types, Object... args) {
        if (jrock == null) throw new Stop("JRock has not been loaded yet.");
        try {
            return jrock.getMethod(name, types).invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new Stop("JRock's " + name + " threw " + ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new Stop("this " + JAR + " has no " + name + "(...) - it is older than "
                    + "this automation. Copy a newer jar in.");
        }
    }

    private static String awaitReady(long timeoutMillis) {
        return (String) api("automationAwaitReady", new Class<?>[] { long.class },
                timeoutMillis);
    }

    private static String begin(String what) {
        return (String) api("automationBegin", new Class<?>[] { String.class }, what);
    }

    private static String loadPrompt(Path prompt) {
        return (String) api("automationLoadPrompt", new Class<?>[] { String.class },
                prompt.toString());
    }

    private static int dropPlaceholders() {
        return (Integer) api("automationDropPlaceholders", new Class<?>[0]);
    }

    private static String include(Path file, String kind) {
        return (String) api("automationInclude",
                new Class<?>[] { String.class, String.class }, file.toString(), kind);
    }

    private static String[] send() {
        return (String[]) api("automationSend", new Class<?>[] { long.class },
                REPLY_TIMEOUT_MS);
    }

    private static String messageFile(String role, String stamp) {
        return (String) api("automationMessageFile",
                new Class<?>[] { String.class, String.class }, role, stamp);
    }

    // Best-effort, and called on the way out however that came about: an automation
    // that failed must not leave the prompt locked, and a jar that was never loaded has
    // no window to unlock.
    private static void end(String note) {
        if (jrock == null) return;
        try {
            api("automationEnd", new Class<?>[] { String.class }, note);
        } catch (Stop ignore) {
            // Beyond helping; the dialog about to be shown is the whole report.
        }
    }

    // JRock's window, so this automation's dialogs belong to it rather than float on
    // their own. Null before the jar is loaded, which JOptionPane reads as "centre it".
    private static java.awt.Component parent() {
        if (jrock == null) return null;
        return (java.awt.Component) api("automationWindow", new Class<?>[0]);
    }

    // Turns an API method's answer into a stop, a null meaning there is nothing wrong.
    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // The reply a send produced, read out of its own file under JRock/messages/.
    //
    // The request's file is checked and not read, which is the point of checking it: if
    // the message JRock says it sent is not on disk, the stamps do not mean what they
    // look like they mean and the reply under the other one is not to be trusted.
    private static String reply(String[] sent) {
        if (!"1".equals(sent[0])) throw new Stop(sent[3]);
        if (messageFile("operator", sent[1]) == null) {
            throw new Stop("the request was not written to JRock/messages/.");
        }
        String file = messageFile("assistant", sent[2]);
        if (file == null) throw new Stop("the reply was not written to JRock/messages/.");
        try {
            return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file + ": " + ex.getMessage());
        }
    }

    // ---- Files -------------------------------------------------------------
    // The answer's file name, out of the second reply.
    //
    // The prompt asks for nothing but the name and that is usually what comes back -
    // but "usually" is not a contract, so the last non-blank line is taken and then
    // reduced to something every file system here will accept. The order matters: the
    // quoting comes off first, then an extension the prompt said not to add (otherwise
    // "...-1234567890.pdf.pdf"), and only then the characters a name cannot hold.
    private static String baseName(String answer) {
        String line = "";
        for (String candidate : answer.split("\n")) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) line = trimmed;
        }
        line = line.replaceAll("^[\"'`*_\\s]+", "").replaceAll("[\"'`*_\\s.]+$", "");
        line = line.replaceAll("(?i)\\.(pdf|txt)$", "");
        line = line.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        if (line.length() > 120) line = line.substring(0, 120);
        return line.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    // A file's name without its extension.
    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // A name in dir that is not taken: "<stem><ext>", or the same with "-2", "-3", ...
    // before the extension. Nothing this automation writes overwrites anything.
    private static Path free(Path dir, String stem, String ext) {
        Path target = dir.resolve(stem + ext);
        for (int n = 2; Files.exists(target); n++) {
            target = dir.resolve(stem + "-" + n + ext);
        }
        return target;
    }

    // Renames a file to base, keeping its extension, and returns where it went.
    private static Path renameTo(Path file, String base) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = free(file.getParent(), base, dot > 0 ? name.substring(dot) : "");
        Files.move(file, target);
        return target;
    }

    // ---- Dialogs -----------------------------------------------------------
    // The PDF, chosen in a file chooser with one filter and no "All files": this
    // automation converts a PDF and has nothing to say about anything else. Null when
    // the chooser was cancelled.
    private static Path choosePdf() {
        Path[] chosen = { null };
        onEdt(() -> {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(Paths.get("").toAbsolutePath().toFile());
            chooser.setDialogTitle("Choose the PDF to inventory");
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "PDF files (*.pdf)", "pdf"));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chosen[0] = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
        });
        return chosen[0];
    }

    private static boolean ask(String message) {
        boolean[] yes = { false };
        onEdt(() -> yes[0] = javax.swing.JOptionPane.showConfirmDialog(parent(), message,
                "Rename the files?", javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE)
                    == javax.swing.JOptionPane.YES_OPTION);
        return yes[0];
    }

    private static void tell(String message, String title, int kind) {
        onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                parent(), message, title, kind));
    }

    // Runs body on the event dispatch thread and waits for it, every dialog above being
    // a widget and this automation running on a thread of its own.
    private static void onEdt(Runnable body) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted.");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Stop) throw (Stop) cause;
            throw new Stop("a dialog failed: " + cause);
        }
    }
}
