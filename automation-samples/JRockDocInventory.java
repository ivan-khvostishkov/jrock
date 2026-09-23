// A JRock automation: one scanned PDF in, a text twin of it and a file name out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockDocInventory.java [--working-dir <dir>]
//                                               [--prompts-dir <dir>] [document.pdf]
//
// Without the document it asks for the PDF in a file chooser, opened in the working
// directory. The two prompts it needs it finds beside itself, in this directory.
//
// The flags are JRock's and are passed straight through (see parseArgs): --working-dir
// names the folder whose settings it is to use - that folder's model, its images DPI,
// its Bedrock key - whatever directory the process was started in. JRock's window menu
// installs an Explorer right-click entry for exactly this ("Install agent"), which is
// what lets one agent work on a file anywhere on the disk with one folder's settings,
// and lets the same agent be installed from several folders with settings of their own.
//
// COPY jrock.jar HERE FIRST. It is not in the repository - the repository carries no
// jars - and nothing downloads it: take it from a "Reproducible build" artifact, or
// build it with .github/build/BuildJar.java, and put it next to this file. That copy is
// what enables automations, and copying it is deliberately a manual act: an automation
// directory is a jar, some prompts and the scripts that chain them, all files you put
// there yourself.
//
// -cp jrock.jar is not optional, and it is not only about run time. The source-file
// launcher (JEP 330) compiles this file in memory with exactly that class path, so every
// JRock call below is an ordinary typed static call the compiler has checked - and the
// jar is where JRock is loaded from when it runs. Forget the flag and javac says
// "cannot find symbol: class JRock" before anything starts, which is the whole
// diagnosis. An older jar, without the automation API in it, fails the same way and
// names the method it is missing.
//
// There is nothing to instantiate, either: JRock's automation API is static, and has to
// be. The window, the log, the JRock/ directory and the Bedrock session are one per
// process, so there is no object that could hold a second set of them. main() shows the
// window; the automation* methods drive the one that is there.
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
// by hand, which is the point of driving the real window rather than a headless copy of
// it. Copy this file and rewrite it for chains of your own - and read
// JRockTranslateToEnglish.java beside it, which is this same plumbing around a single
// pass, and takes a document of any type rather than a PDF.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JRockDocInventory {

    // The two prompts, expected beside this file.
    private static final String PROMPT_ASCII = "jrock-prompt-doc-to-ascii.txt";
    private static final String PROMPT_NAME = "jrock-prompt-doc-inventory.txt";

    // JRock's own flags, spelled out here because this automation both reads them and
    // passes them on (see parseArgs).
    private static final String WORKING_DIR_FLAG = "--working-dir";
    private static final String PROMPTS_DIR_FLAG = "--prompts-dir";

    // Starting a JVM's worth of Swing and fetching the model list, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // A document's worth of page images, read by a frontier model. Generous on purpose:
    // the failure this guards against is a request that never comes back, and a minute
    // either way is cheaper than giving up on an answer that was on its way.
    private static final long REPLY_TIMEOUT_MS = 20 * 60_000;

    // Why an automation stopped, said in one sentence and shown in one dialog.
    //
    // A RuntimeException so the steps below read as the sequence they are rather than as
    // a ladder of if-blocks: every automation API call returns null for "fine" and a
    // sentence for "not fine", and check() turns the second into one of these.
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

        // The prompt becomes editable again and Send is released whatever happened - an
        // automation that failed must not leave the window locked. A no-op if it never
        // got as far as taking it.
        JRock.automationEnd(stopped == null ? summary : "stopped - " + stopped);
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
        Args told = parseArgs(args);
        Path here = ownDirectory();
        System.out.println("Automation directory: " + here);
        System.out.println("Working directory:    " + told.workingDir);
        System.out.println("Driving JRock from:   " + jrockCame());
        Path ascii = mustExist(here.resolve(PROMPT_ASCII));
        Path inventory = mustExist(here.resolve(PROMPT_NAME));

        // The document. Asked for before JRock is started, so a cancelled chooser
        // leaves nothing behind at all.
        Path pdf = (told.document != null) ? told.document : choosePdf(told.workingDir);
        if (pdf == null) {
            System.out.println("No document chosen - nothing to do.");
            return null;
        }
        if (!Files.isRegularFile(pdf)) throw new Stop("not a file: " + pdf);
        System.out.println("Document: " + pdf);

        // ---- Start JRock and wait for it to be usable ----------------------
        // The real entry point, which shows the window on the event dispatch thread and
        // returns at once. Everything after this runs on THIS thread, which is what the
        // automation API asks for - every one of its methods blocks.
        JRock.main(told.forJRock.toArray(new String[0]));
        check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
        check(JRock.automationBegin("doc inventory of " + pdf.getFileName()));

        // ---- Pass one: the document as plain text --------------------------
        System.out.println("Pass 1: " + PROMPT_ASCII);
        check(JRock.automationLoadPrompt(ascii.toString()));
        // The bare "@img" / "@txt" lines the sample prompts end with are placeholders a
        // person reads and presses Ctrl+I on; a program has to be told.
        JRock.automationDropPlaceholders();
        check(JRock.automationInclude(pdf.toString(), "pdf"));
        String text = reply(JRock.automationSend(REPLY_TIMEOUT_MS));

        // Beside the PDF and named after it, which is what makes the pair findable
        // before they are renamed - and what the second pass is about to read back.
        Path twin = free(pdf.getParent(), stem(pdf), ".txt");
        Files.write(twin, text.getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote " + twin);

        // ---- Pass two: a name for it --------------------------------------
        System.out.println("Pass 2: " + PROMPT_NAME);
        check(JRock.automationLoadPrompt(inventory.toString()));
        JRock.automationDropPlaceholders();
        check(JRock.automationInclude(twin.toString(), "txt"));
        String answer = reply(JRock.automationSend(REPLY_TIMEOUT_MS));
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

    // ---- The command line --------------------------------------------------
    // What this automation was told, sorted into what JRock is to be started with and
    // what this automation keeps for itself.
    //
    // The division matters in one direction in particular: the flags are passed on, and
    // the bare argument is NOT. JRock reads a bare argument as a prompt file to load,
    // so handing it the document would load a PDF as the prompt and leave nothing to
    // work on. The working directory is kept as well as passed on, because the file
    // chooser opens in it.
    private static final class Args {
        private final java.util.List<String> forJRock = new java.util.ArrayList<>();
        private Path workingDir = Paths.get("").toAbsolutePath().normalize();
        private Path document = null;
    }

    // Both spellings of a flag - "--working-dir <dir>" and "--working-dir=<dir>" - as
    // JRock itself accepts both. A flag this automation does not know goes through
    // untouched: JRock may know it, and a launcher that grew a flag should not have to
    // wait for every agent to be edited.
    private static Args parseArgs(String[] args) {
        Args parsed = new Args();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null || arg.isBlank()) continue;
            String flag = flagOf(arg);
            if (flag.equals(WORKING_DIR_FLAG) || flag.equals(PROMPTS_DIR_FLAG)) {
                String value = valueOf(arg);
                if (value == null) {
                    if (i + 1 >= args.length) {
                        throw new Stop(flag + " needs a directory after it.");
                    }
                    value = args[++i];
                    parsed.forJRock.add(flag);
                    parsed.forJRock.add(value);
                } else {
                    parsed.forJRock.add(arg);   // one word, passed on as one word
                }
                if (flag.equals(WORKING_DIR_FLAG)) {
                    parsed.workingDir = Paths.get(value).toAbsolutePath().normalize();
                }
            } else if (arg.startsWith("-")) {
                parsed.forJRock.add(arg);
            } else if (parsed.document == null) {
                parsed.document = Paths.get(arg).toAbsolutePath().normalize();
            } else {
                throw new Stop("one document at a time, and two were given:\n\n"
                        + parsed.document + "\n" + Paths.get(arg).toAbsolutePath());
            }
        }
        return parsed;
    }

    // "--flag=value" in two halves: the flag, and the value or null when the argument
    // carried none (the value is then the next argument).
    private static String flagOf(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? arg : arg.substring(0, eq);
    }

    private static String valueOf(String arg) {
        int eq = arg.indexOf('=');
        return eq < 0 ? null : arg.substring(eq + 1);
    }

    // ---- Where the files are -----------------------------------------------
    // The directory this source file sits in, which is where the prompts are looked
    // for. An automation directory is self-contained by design, so "beside me" has to be
    // answerable however the file was launched - and it is not the working directory,
    // which is the folder whose documents are being filed.
    //
    // jdk.launcher.sourcefile is set by the launcher in source-file mode (`java
    // Foo.java`), which is how this is meant to be run, and it is absolute even when the
    // command line was not. The code source covers the other way - a compiled class, or
    // this file inside a jar - and the working directory is the last resort, which is
    // right often enough to be worth trying.
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

    // Which jar is actually being driven, for one line on the console. A stale jrock.jar
    // in one directory and a newer one in another is a real mix-up, and saying which one
    // -cp found costs nothing.
    private static String jrockCame() {
        try {
            java.security.CodeSource code = JRock.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) return code.getLocation().toString();
        } catch (RuntimeException ignore) {
            // Nothing to say, which is what the fallback says.
        }
        return "the class path";
    }

    // A file the automation cannot run without.
    private static Path mustExist(Path file) {
        if (!Files.isRegularFile(file)) throw new Stop("there is no " + file + ".");
        return file;
    }

    // ---- The API's answers -------------------------------------------------
    // Turns an automation API result into a stop, a null meaning there is nothing wrong.
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
        if (JRock.automationMessageFile("operator", sent[1]) == null) {
            throw new Stop("the request was not written to JRock/messages/.");
        }
        String file = JRock.automationMessageFile("assistant", sent[2]);
        if (file == null) throw new Stop("the reply was not written to JRock/messages/.");
        try {
            return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file + ": " + ex.getMessage());
        }
    }

    // ---- Files -------------------------------------------------------------
    // A line that is nothing but a file name: name characters and spaces, no comma, no
    // apostrophe, no colon - the punctuation of prose is what tells the two apart.
    private static final java.util.regex.Pattern NAME_LINE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{2,}");

    // A file name sitting inside a line of text ("File name: 2020-07-27-FTS-3NDFL-..."):
    // a run of name characters long enough not to be an ordinary word.
    private static final java.util.regex.Pattern NAME_IN_LINE =
            java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{6,}[A-Za-z0-9]");

    // How many spaces a line may hold and still be read as a name rather than as prose.
    private static final int NAME_MAX_SPACES = 4;

    // The file name, out of the second reply.
    //
    // The prompt asks for nothing but the name, and the name is normally the first line -
    // but "normally" is not a contract, and a model that adds a sentence about the
    // document used to win outright, because the LAST non-blank line was the one taken.
    // A sentence reduced to name-safe characters is not a name:
    //     2020-07-27-FTS-3NDFL-TaxReturn-2019
    //
    //     The document is Ivan Khvostishkov's personal Russian 3-NDFL income-tax ...
    // came back as "The-document-is-Ivan-Khvostishkov-s-personal-Russian-3-NDFL-income-".
    // So the reply is searched for the line that LOOKS like a file name, in two passes:
    // one for a line that is a name entire, then one for a name embedded in a line. The
    // first line that answers wins, the top of a reply being where the answer was asked
    // for. Then an extension the prompt said not to add comes off (otherwise
    // "...-1234567890.pdf.pdf"), and only then the characters a name cannot hold.
    //
    // Nothing that looks like a name -> "", and the caller stops and shows the reply
    // instead of inventing a name out of prose.
    private static String baseName(String answer) {
        String[] lines = answer.split("\n");
        String found = "";
        for (String line : lines) {
            String plain = undecorated(line);
            if (isNameLine(plain)) {
                found = plain;
                break;
            }
        }
        for (int i = 0; found.isEmpty() && i < lines.length; i++) {
            java.util.regex.Matcher m = NAME_IN_LINE.matcher(undecorated(lines[i]));
            while (m.find()) {
                // The longest run in the line, and only one carrying a separator: a name
                // is made of several parts, an English word is not.
                if (m.group().matches(".*[._-].*") && m.group().length() > found.length()) {
                    found = m.group();
                }
            }
        }
        String name = found.replaceAll("(?i)\\.(pdf|txt)$", "");
        name = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("-{2,}", "-");
        if (name.length() > 120) name = name.substring(0, 120);
        return name.replaceAll("^-+", "").replaceAll("-+$", "");
    }

    // Whether a whole (undecorated) line reads as a file name: the characters of one and
    // nothing else, few enough spaces to be a name rather than a sentence, and either
    // several parts or a decent length - which is what keeps a stray short word, the
    // "json" of a code fence among them, from being taken for an answer.
    private static boolean isNameLine(String plain) {
        return NAME_LINE.matcher(plain).matches()
                && spaces(plain) <= NAME_MAX_SPACES
                && (plain.matches(".*[ ._-].*") || plain.length() >= 8);
    }

    // One line with its decoration off: the bullet or heading marker in front of it, a
    // label like "Suggested file name:", the quotes, backticks or asterisks around the
    // name, and the full stop after it.
    private static String undecorated(String line) {
        return line.trim()
                .replaceAll("^[-*#>\\s]+", "")
                .replaceAll("(?i)^(suggested|proposed|new|final)?\\s*(file ?name|name)"
                        + "\\s*[:=]\\s*", "")
                .replaceAll("^[\"'`*_\\s]+", "")
                .replaceAll("[\"'`*_\\s.]+$", "");
    }

    // How many spaces a string holds.
    private static int spaces(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ' ') n++;
        }
        return n;
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
    // automation converts a PDF and has nothing to say about anything else. It opens in
    // the working directory - the folder being filed, not this one, and not the folder
    // Explorer happened to start the process in either (that is what --working-dir
    // overrides). Null when the chooser was cancelled.
    private static Path choosePdf(Path startIn) {
        Path[] chosen = { null };
        onEdt(() -> {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(startIn.toFile());
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

    // Both dialogs below belong to JRock's own window, so they cannot be lost behind it.
    // automationWindow() is null until that window exists, which JOptionPane reads as
    // "centre it on the screen" - right for the one dialog that can come that early.
    private static boolean ask(String message) {
        boolean[] yes = { false };
        onEdt(() -> yes[0] = javax.swing.JOptionPane.showConfirmDialog(
                JRock.automationWindow(), message, "Rename the files?",
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE)
                    == javax.swing.JOptionPane.YES_OPTION);
        return yes[0];
    }

    private static void tell(String message, String title, int kind) {
        onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                JRock.automationWindow(), message, title, kind));
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
