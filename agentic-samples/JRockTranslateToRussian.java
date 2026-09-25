// A JRock automation: one document in, its translation into Russian out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockTranslateToRussian.java [--working-dir <dir>]
//                                                     [--prompts-dir <dir>] [document]
//
// Without the document it asks for one in a file chooser, opened in the folder this was
// started in - which, from the right-click menu, is the folder that was clicked. The
// prompt it needs it finds beside itself, in this directory.
//
// COPY jrock.jar HERE FIRST, take the flags as JRock's own, and expect a typed static
// call rather than reflection: all of that is JRockDocInventory.java's opening comment,
// and it is true of every automation in this directory. Read that file first.
//
// What this one does is one pass, not two:
//
//   1. jrock-prompt-translate-to-russian.txt + the document -> the whole document in
//      Russian, saved as <name>-ru.txt beside the original.
//
// Which is the shorter half of why both samples are here. An automation is a prompt, an
// include and a send; the inventory chains two of those and renames files afterwards,
// this one does a single pass and writes one new file, and the plumbing around them is
// the same file twice. Copy whichever is closer to the chain you want.
//
// JRockTranslateToEnglish.java is this same file, and so are the ones for the other
// official languages of the United Nations: JRockTranslateToArabic, -Chinese, -French and
// -Spanish. They differ in nothing but the class name, the language, its code and the
// prompt they load - one file each, because an agent is one file and its right-click
// entry is named after it. Change one, change them all.
//
// The document can be any kind JRock can include, and the kind is chosen from the
// extension (see includeKind) - which is what makes this one worth installing as an
// agent: "Install agent" writes a right-click entry that hands the clicked file's path
// to the agent whatever type it is, so a PDF, a DOCX, an RTF, a text file or a
// photograph of a page all translate from the same menu item. A PDF goes as page images,
// because a scan has no text layer to read and a translation wants the layout anyway.
//
// Nothing is asked and nothing existing is touched: the translation is a new file next to
// the original, and an existing <name>-ru.txt becomes <name>-ru-2.txt. So unlike the
// inventory, which stops to ask before renaming your documents, this one runs through.
//
// The saved file is a copy for the archive, not the only place the answer is: the
// translation is in the transcript in front of you, and the window is yours again as soon
// as the automation ends. Select the translation there and the log's right-click menu
// will "Export selected Markdown with images as DOCX..." it into a real document, in
// either A4 orientation - which is usually what a translation is wanted for.
//
// One limit worth knowing: a reply is one reply. A very long document can run into the
// model's own output limit, and what comes back is then a translation that stops in the
// middle rather than an error - JRock asks for no cap of its own, so the ceiling is
// whatever the chosen model applies. Split such a document, or pick a model with more
// room; either way the answer to check is the end of the file, not the beginning.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JRockTranslateToRussian {

    // The prompt, expected beside this file.
    private static final String PROMPT_TRANSLATE = "jrock-prompt-translate-to-russian.txt";

    // What the translation is called: <name>-ru.txt beside the original. A suffix and not
    // a directory, so the pair sits together in one listing - and .txt whatever went in,
    // because what comes back is text even when the original was a scan.
    private static final String TARGET_SUFFIX = "-ru";
    private static final String TARGET_EXTENSION = ".txt";

    // JRock's own flags, spelled out here because this automation both reads them and
    // passes them on (see parseArgs).
    private static final String WORKING_DIR_FLAG = "--working-dir";
    private static final String PROMPTS_DIR_FLAG = "--prompts-dir";

    // The folder this process was started in, which is where the file chooser opens.
    //
    // Not the working directory, and that is the whole point: --working-dir names the
    // folder whose model, DPI and key this run uses, one folder set up once and usually
    // nowhere near the document, so a chooser opening there opens in the one folder the
    // answer is certainly not in. Explorer runs a right-click command IN the folder it
    // was clicked in, so this is that folder - no flag needed, and nothing to pass on.
    //
    // Read once, here, and held: JRock's own --working-dir handling sets the user.dir
    // property, and Paths.get("").toAbsolutePath() reads that property every time it is
    // called, so asking later would answer with the settings folder instead.
    private static final Path STARTED_IN = Paths.get("").toAbsolutePath().normalize();

    // Starting a JVM's worth of Swing and fetching the model list, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // A whole document translated word for word is a long answer, and a long answer takes
    // its time. Generous on purpose: the failure this guards against is a request that
    // never comes back, and a minute either way is cheaper than giving up on an answer
    // that was on its way.
    private static final long REPLY_TIMEOUT_MS = 20 * 60_000;

    // The extensions this automation knows how to hand to JRock, by the include kind they
    // are handed under. Text is deliberately wider than JRock's own "Text files as is"
    // filter (.md and .log are here): the filter is a convenience in a dialog, while the
    // include itself reads any text file, and a translation is exactly what a Markdown
    // note or a log of foreign-language messages might want.
    private static final String[] TEXT_EXTENSIONS =
            { "txt", "md", "csv", "json", "html", "htm", "log", "java" };
    private static final String[] IMAGE_EXTENSIONS =
            { "png", "jpg", "jpeg", "gif", "webp" };

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
            tell("Finished.\n\n" + summary
                    + "\n\nThe translation is in the transcript too - select it there to"
                    + " export it as RTF or DOCX.",
                    "Translation finished", javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } else {
            System.out.println("Stopped: " + stopped);
            tell("The automation stopped:\n\n" + stopped
                    + "\n\nThe JRock log says where it got to.",
                    "Automation stopped", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    // The one pass, start to finish. Returns what to report, or null when there was
    // nothing to do; throws Stop with the reason for anything else.
    private static String run(String[] args) throws IOException {
        Args told = parseArgs(args);
        Path here = ownDirectory();
        System.out.println("Automation directory: " + here);
        System.out.println("Working directory:    " + told.workingDir);
        System.out.println("Chooser opens in:     " + told.chooserStart());
        System.out.println("Driving JRock from:   " + jrockCame());
        Path prompt = mustExist(here.resolve(PROMPT_TRANSLATE));

        // The document. Asked for before JRock is started, so a cancelled chooser leaves
        // nothing behind at all.
        Path document = (told.document != null)
                ? told.document : chooseDocument(told.chooserStart());
        if (document == null) {
            System.out.println("No document chosen - nothing to do.");
            return null;
        }
        if (!Files.isRegularFile(document)) throw new Stop("not a file: " + document);

        // Before the window, not after: an extension nobody can include is a stop worth
        // reaching in a second, and it says which ones would have worked.
        String kind = includeKind(document);
        System.out.println("Document: " + document + " (as \"" + kind + "\")");

        // ---- Start JRock and wait for it to be usable ----------------------
        // The real entry point, which shows the window on the event dispatch thread and
        // returns at once. Everything after this runs on THIS thread, which is what the
        // automation API asks for - every one of its methods blocks.
        JRock.main(told.forJRock.toArray(new String[0]));
        check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
        check(JRock.automationBegin("translation of " + document.getFileName()));

        // ---- The pass: the document in Russian ------------------------------
        System.out.println("Translating with " + PROMPT_TRANSLATE);
        check(JRock.automationLoadPrompt(prompt.toString()));
        dropPlaceholders();
        check(JRock.automationInclude(document.toString(), kind));
        String translation = reply(JRock.automationSend(REPLY_TIMEOUT_MS));
        if (translation.isBlank()) throw new Stop("the reply was empty.");

        // Beside the original and named after it, which is what keeps the pair together
        // in a folder sorted by name.
        Path target = free(document.getParent(), stem(document) + TARGET_SUFFIX,
                TARGET_EXTENSION);
        Files.write(target, translation.getBytes(StandardCharsets.UTF_8));
        System.out.println("Wrote " + target);

        String from = sourceLanguage(translation);
        return "wrote " + target.getFileName()
                + (from == null ? "" : ", translated from " + from);
    }

    // ---- The command line --------------------------------------------------
    // What this automation was told, sorted into what JRock is to be started with and
    // what this automation keeps for itself.
    //
    // The division matters in one direction in particular: the flags are passed on, and
    // the bare argument is NOT. JRock reads a bare argument as a prompt file to load, so
    // handing it the document would load the document as the prompt and leave nothing to
    // work on. The working directory is kept as well as passed on, because the file
    // chooser opens in it.
    private static final class Args {
        private final java.util.List<String> forJRock = new java.util.ArrayList<>();
        private Path workingDir = STARTED_IN;
        private Path document = null;

        // Where the file chooser opens: the folder this process was started in (see
        // STARTED_IN), falling back to the working directory if that folder has since
        // gone - a chooser must open somewhere.
        private Path chooserStart() {
            return Files.isDirectory(STARTED_IN) ? STARTED_IN : workingDir;
        }
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
                    Path dir = dirOrNull(value);
                    if (dir != null) parsed.workingDir = dir;
                }
            } else if (arg.startsWith("-")) {
                parsed.forJRock.add(arg);
            } else if (parsed.document == null) {
                parsed.document = documentPath(arg);
            } else {
                throw new Stop("one document at a time, and two were given:\n\n"
                        + parsed.document + "\n" + arg);
            }
        }
        return parsed;
    }

    // The document argument as a path, or a stop saying why that text is not one.
    //
    // Worth its own sentence rather than an InvalidPathException out of a stack trace,
    // because the reason is nothing to do with this automation: on Windows the arguments
    // reaching main are decoded with the system ANSI code page, so a path holding a
    // character that code page has no room for - a Cyrillic name on a Western install -
    // arrives with a literal '?' in place of it, and '?' is illegal in a path. Nobody
    // can put those characters back; the chooser is the way round it, and the message
    // says so.
    private static Path documentPath(String value) {
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            throw new Stop("Windows could not put this file name on the command line - a "
                    + "character in it has no place in the system code page, so it "
                    + "arrived as '?':\n\n    " + value + "\n\nTo fix it for good: Region "
                    + "settings > Administrative > Change system locale... > tick \"Beta: "
                    + "Use Unicode UTF-8 for worldwide language support\" and restart "
                    + "Windows.\n\nOr right-click the FOLDER instead and pick the file in "
                    + "the chooser, which never goes through a command line.");
        }
    }

    // A directory argument, or null when that text is no path at all (same reason as
    // above). Null rather than a stop: a flag whose folder cannot be read is a worse
    // chooser, not a reason to give up on the document (and JRock reports its own flags
    // for itself).
    private static Path dirOrNull(String value) {
        try {
            return Paths.get(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            System.out.println("Ignored, not a readable path: " + value);
            return null;
        }
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

    // ---- What to include it as ---------------------------------------------
    // The include kind for a document, from its extension - "pdf", "docx", "rtf", "txt"
    // or "img" (see JRock.automationInclude).
    //
    // The extension and not the bytes, because the choice is not only about what the file
    // is: an RTF is a text file JRock can include as it stands, and it is included as
    // "rtf" instead because that path converts it to Markdown, and headings and tables
    // that survive as structure are worth more to a translation than the same document
    // with its markup spelled out in the middle of it. A PDF goes as page images for the
    // reason the include dialog gives: Ghostscript's text extraction hands back something
    // a model has to guess at, and a scan has no text layer at all.
    //
    // An extension nobody knows is a stop that lists the ones that work, rather than a
    // guess at "txt" that would send a model a page of binary.
    private static String includeKind(Path file) {
        String ext = extension(file);
        if (ext.equals("pdf")) return "pdf";
        if (ext.equals("docx")) return "docx";
        if (ext.equals("rtf")) return "rtf";
        if (holds(TEXT_EXTENSIONS, ext)) return "txt";
        if (holds(IMAGE_EXTENSIONS, ext)) return "img";
        throw new Stop("there is no way to include " + file.getFileName() + ".\n\n"
                + "Translatable: pdf, docx, rtf, " + String.join(", ", TEXT_EXTENSIONS)
                + ", " + String.join(", ", IMAGE_EXTENSIONS) + ".");
    }

    // A file's extension, lower-cased and without the dot ("" when it has none).
    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean holds(String[] extensions, String ext) {
        for (String one : extensions) {
            if (one.equals(ext)) return true;
        }
        return false;
    }

    // Every extension above, as one list for the file chooser's single filter.
    private static String[] translatable() {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.add("pdf");
        all.add("docx");
        all.add("rtf");
        all.addAll(java.util.Arrays.asList(TEXT_EXTENSIONS));
        all.addAll(java.util.Arrays.asList(IMAGE_EXTENSIONS));
        return all.toArray(new String[0]);
    }

    // ---- Where the files are -----------------------------------------------
    // The directory this source file sits in, which is where the prompt is looked for. An
    // automation directory is self-contained by design, so "beside me" has to be
    // answerable however the file was launched - and it is not the working directory,
    // which is the folder whose documents are being translated.
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
                    JRockTranslateToRussian.class.getProtectionDomain().getCodeSource();
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

    // Takes the bare "@img" / "@txt" lines out of the prompt just loaded. The sample
    // prompts end with them as placeholders: a person reads them as "the attachments go
    // here" and presses Ctrl+I on one. This automation includes the document itself, so
    // they go - and what is left ends with one newline, as each include token goes on a
    // line of its own at the end of it. They are no tokens (a token carries a
    // 12-hex-digit hash), so one left in would go out as the word it is.
    private static void dropPlaceholders() {
        String prompt = JRock.automationPromptText();
        if (prompt == null) throw new Stop("the prompt could not be read.");
        StringBuilder kept = new StringBuilder();
        for (String line : prompt.split("\n", -1)) {
            String bare = line.trim();
            if (bare.equals("@img") || bare.equals("@txt") || bare.equals("@audio")) continue;
            kept.append(line).append('\n');
        }
        check(JRock.automationSetPrompt(kept.toString().replaceAll("\n+$", "\n")));
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
    // The "Translated from: German" line the prompt asks for, read back for one word in
    // the summary. The line stays in the saved file - it is what the model answered, and
    // an automation does not edit an answer it is only storing - so this is a read and
    // not a strip.
    //
    // Null when the first line is not that line, which is a model not following one
    // instruction and nothing more: the translation under it is still a translation, so
    // the summary simply says less.
    private static final java.util.regex.Pattern TRANSLATED_FROM =
            java.util.regex.Pattern.compile(
                    "(?i)^\\s*translated\\s+from\\s*:\\s*(.{1,60}?)\\s*$");

    private static String sourceLanguage(String translation) {
        int end = translation.indexOf('\n');
        String first = (end < 0) ? translation : translation.substring(0, end);
        java.util.regex.Matcher m = TRANSLATED_FROM.matcher(first);
        return m.matches() ? m.group(1) : null;
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

    // ---- Dialogs -----------------------------------------------------------
    // The document, chosen in a file chooser with one filter and no "All files": one
    // filter because the kind is decided by the extension anyway, and no "All files"
    // because a file with no readable extension would only reach includeKind's stop. It
    // opens where Args.chooserStart says - the folder this process was started in, which
    // from the right-click menu is the folder that was clicked. Null when the chooser was
    // cancelled.
    private static Path chooseDocument(Path startIn) {
        Path[] chosen = { null };
        onEdt(() -> {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(startIn.toFile());
            chooser.setDialogTitle("Choose the document to translate into Russian");
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Documents, text and images", translatable()));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chosen[0] = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
        });
        return chosen[0];
    }

    // The dialog below belongs to JRock's own window, so it cannot be lost behind it.
    // automationWindow() is null until that window exists, which JOptionPane reads as
    // "centre it on the screen" - right for a stop that came before the window.
    private static void tell(String message, String title, int kind) {
        onEdt(() -> javax.swing.JOptionPane.showMessageDialog(
                JRock.automationWindow(), message, title, kind));
    }

    // Runs body on the event dispatch thread and waits for it, every dialog above being a
    // widget and this automation running on a thread of its own.
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
