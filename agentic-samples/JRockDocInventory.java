// A JRock automation: one scanned PDF or RTF in, a text twin of it and a file name out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockDocInventory.java [--working-dir <dir>]
//                                               [--prompts-dir <dir>] [document.pdf|.rtf]
//
// Without the document it asks for a PDF or an RTF in a file chooser, opened in the
// folder this was started in - which, from the right-click menu, is the folder that was
// clicked. The two prompts it needs it finds beside itself, in this directory.
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
//   1. jrock-prompt-doc-to-ascii.txt  + the document           -> the document as plain
//      text, saved as <document name>.txt beside it. A PDF goes in as one page image
//      per page; an RTF goes in as Markdown, which is text.
//   2. jrock-prompt-doc-inventory.txt + that text file          -> one file name.
//   3. Every file of that document renamed to it, if you say so.
//
// A PDF and an RTF of the same name are one document filed twice - which is what
// Acrobat's "Export to RTF" leaves behind - so both are offered and both are renamed:
// pick either and the other is found beside it (see companionOf), and the rename at the
// end moves two files or three. Handed a PDF with an RTF next to it, this automation
// offers to read the RTF instead, because a page of Markdown costs a fraction of the
// same page photographed and says more exactly what is on it.
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

    // The two kinds of file this automation files, and the only two: a scanned document
    // and the text of one. Constants because every step asks the extension the same
    // questions - which include kind the file goes in under, which file is its twin, and
    // whether a path handed over by the right-click menu is one this agent reads at all.
    private static final String EXT_PDF = "pdf";
    private static final String EXT_RTF = "rtf";

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

    // What a finished run has to say, in the two shapes it has to be said in.
    //
    // note is one line, for the log and the console, which are lines already. message is
    // the same thing for a dialog, with every file name on a line of its own: a name here
    // is a date, a counterparty, a subject and a document number joined by dashes, sixty
    // characters is ordinary, and three of those wrapped into a paragraph is a dialog as
    // wide as the desktop with nothing the eye can compare down a column.
    private static final class Result {
        final String note;
        final String message;
        Result(String note, String message) { this.note = note; this.message = message; }
    }

    public static void main(String[] args) {
        Result done = null;
        String stopped = null;
        try {
            done = run(args);
        } catch (Stop stop) {
            stopped = stop.getMessage();
        } catch (Exception ex) {
            stopped = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        // Nothing to say: the chooser was cancelled, and nothing has been started.
        if (done == null && stopped == null) return;

        // The prompt becomes editable again and Send is released whatever happened - an
        // automation that failed must not leave the window locked. A no-op if it never
        // got as far as taking it.
        JRock.automationEnd(stopped == null ? done.note : "stopped - " + stopped);
        if (stopped == null) {
            System.out.println("Finished: " + done.note);
            tell("Finished.\n\n" + done.message, "Automation finished",
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
    private static Result run(String[] args) throws IOException {
        Args told = parseArgs(args);
        Path here = ownDirectory();
        System.out.println("Automation directory: " + here);
        System.out.println("Working directory:    " + told.workingDir);
        System.out.println("Chooser opens in:     " + told.chooserStart());
        System.out.println("Driving JRock from:   " + jrockCame());
        Path ascii = mustExist(here.resolve(PROMPT_ASCII));
        Path inventory = mustExist(here.resolve(PROMPT_NAME));

        // The document. Asked for before JRock is started, so a cancelled chooser
        // leaves nothing behind at all.
        Path chosen = (told.document != null)
                ? told.document : chooseDocument(told.chooserStart());
        if (chosen == null) {
            System.out.println("No document chosen - nothing to do.");
            return null;
        }
        if (!Files.isRegularFile(chosen)) throw new Stop("not a file:\n\n    " + chosen);

        // Checked here and not left to the include: the chooser only offers the two
        // extensions, but the right-click menu hands over whatever was clicked, and a
        // .jpg or a .docx arriving that way would otherwise be converted, sent and paid
        // for before anything noticed it is not a document this agent files.
        String ext = extension(chosen);
        if (!ext.equals(EXT_PDF) && !ext.equals(EXT_RTF)) {
            throw new Stop("this files a PDF or an RTF, and that is neither:\n\n    "
                    + chosen.getFileName() + "\n\nA scan goes in as page images, an RTF "
                    + "as Markdown text; nothing else is read here.");
        }

        // The same document in the other format, when it is there - and the offer to
        // read the cheaper copy of it (see companionOf and askRtfInstead). Whichever is
        // worked on, both are renamed at the end: they are one document.
        Path document = chosen;
        Path companion = companionOf(chosen);
        if (ext.equals(EXT_PDF) && companion != null && askRtfInstead(chosen, companion)) {
            document = companion;
            companion = chosen;
        }
        String kind = extension(document).equals(EXT_RTF) ? "rtf" : "pdf";
        System.out.println("Document: " + document + " (as " + kind + ")");
        if (companion != null) System.out.println("Companion: " + companion);

        // ---- Start JRock and wait for it to be usable ----------------------
        // The real entry point, which shows the window on the event dispatch thread and
        // returns at once. Everything after this runs on THIS thread, which is what the
        // automation API asks for - every one of its methods blocks.
        JRock.main(told.forJRock.toArray(new String[0]));
        check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
        check(JRock.automationBegin("doc inventory of " + document.getFileName()));

        // ---- Pass one: the document as plain text --------------------------
        System.out.println("Pass 1: " + PROMPT_ASCII);
        check(JRock.automationLoadPrompt(ascii.toString()));
        // The bare "@img" / "@txt" lines the sample prompts end with are placeholders a
        // person reads and presses Ctrl+I on; a program has to be told.
        JRock.automationDropPlaceholders();
        check(JRock.automationInclude(document.toString(), kind));
        String text = reply(JRock.automationSend(REPLY_TIMEOUT_MS));

        // Beside the document and named after it, which is what makes the set findable
        // before they are renamed - and what the second pass is about to read back.
        Path twin = free(document.getParent(), stem(document), ".txt");
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
        //
        // Every file of the document goes at once, or none does - two of them, or three
        // when the PDF and the RTF are both on the disk. A scan renamed without its
        // export beside it leaves one document under two names, which is the mess this
        // automation exists to clear up rather than to make.
        java.util.List<Path> files = new java.util.ArrayList<>();
        files.add(document);
        if (companion != null) files.add(companion);
        files.add(twin);
        String howMany = (files.size() == 2) ? "both files" : "all three files";
        if (!ask("Rename " + howMany + " to\n\n    " + base + "\n\nfrom\n\n"
                + listed(files) + "\nin " + document.getParent() + " ?",
                "Rename the files?")) {
            return new Result("kept " + named(files) + "; the suggested name was " + base,
                    "Nothing was renamed:\n\n" + listed(files)
                            + "\nThe suggested name was\n\n    " + base);
        }
        java.util.List<Path> renamed = new java.util.ArrayList<>();
        for (Path file : files) renamed.add(renameTo(file, base));
        System.out.println("Renamed to " + named(renamed));
        return new Result("renamed to " + named(renamed),
                "Renamed to\n\n" + listed(renamed) + "\nin " + document.getParent() + ".");
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
        if (!Files.isRegularFile(file)) {
            throw new Stop("there is no such file:\n\n    " + file);
        }
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
            throw new Stop("could not read the reply:\n\n    " + file
                    + "\n\n" + ex.getMessage());
        }
    }

    // ---- Files -------------------------------------------------------------
    // The characters a name is made of, as the body of a regex class: letters, the marks
    // that belong to them, and digits - and not merely A-Za-z0-9.
    //
    // The prompt asks for the title and the counterparty "in Latin1", and Latin-1 has
    // umlauts and an eszet in it. Held to ASCII, this pass answered
    //     2026-09-22-M-nchnerBank-...
    // for a bank whose letterhead says Muenchner with an u-umlaut: every letter the
    // sanitiser did not know became a dash, and a dash is the one character here that
    // means "the next part of the name starts". \p{L} rather than a list of the accented
    // letters of the languages met so far, because such a list only ever grows - a Greek
    // or Cyrillic name is a legal file name too, and dashes in place of one help nobody.
    // \p{M} for a reply whose letters arrive decomposed (see the NFC in baseName), \p{N}
    // for digits that are not Arabic numerals.
    private static final String NAME_CHARS = "\\p{L}\\p{M}\\p{N}";

    // A line that is nothing but a file name: name characters and spaces, no comma, no
    // apostrophe, no colon - the punctuation of prose is what tells the two apart.
    private static final java.util.regex.Pattern NAME_LINE = java.util.regex.Pattern
            .compile("[" + NAME_CHARS + "][" + NAME_CHARS + " ._-]{2,}");

    // A file name sitting inside a line of text ("File name: 2020-07-27-FTS-3NDFL-..."):
    // a run of name characters long enough not to be an ordinary word.
    private static final java.util.regex.Pattern NAME_IN_LINE = java.util.regex.Pattern
            .compile("[" + NAME_CHARS + "][" + NAME_CHARS + "._-]{6,}[" + NAME_CHARS + "]");

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
    //
    // The reply is put into NFC first, so an u-umlaut is one character and not a u with a
    // combining mark after it: both spellings look the same on screen, only one of them is
    // what a file system and the person searching the folder later will agree on.
    private static String baseName(String answer) {
        String[] lines = java.text.Normalizer
                .normalize(answer, java.text.Normalizer.Form.NFC).split("\n");
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
        String name = found.replaceAll("(?i)\\.(pdf|rtf|txt)$", "");
        name = name.replaceAll("[^" + NAME_CHARS + "._-]+", "-")
                .replaceAll("-{2,}", "-");
        if (name.length() > 120) {
            // 120 chars, and a char is not always a whole character: a cut between the two
            // halves of a surrogate pair would end the name in half a letter.
            int cut = Character.isHighSurrogate(name.charAt(119)) ? 119 : 120;
            name = name.substring(0, cut);
        }
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

    // A file's extension, lower-cased and without the dot ("" when it has none).
    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    // The same document in the other of the two formats, beside it and under the same
    // name: the .rtf of a .pdf, or the .pdf of a .rtf. Null when there is none.
    //
    // Worth looking for, because that pair is what Acrobat's "Export to RTF" leaves
    // behind: one document on the disk twice, one of the copies carrying text somebody
    // has already paid to extract. Both copies are the same document, so both get the
    // name the inventory settles on - and the text one is the cheaper one to read.
    private static Path companionOf(Path file) {
        String other = extension(file).equals(EXT_PDF) ? EXT_RTF : EXT_PDF;
        Path beside = file.getParent().resolve(stem(file) + "." + other);
        return Files.isRegularFile(beside) ? beside : null;
    }

    // File names for a dialog: one per line, indented, nothing else on the line. See
    // Result for why a name here never shares a line with anything.
    private static String listed(java.util.List<Path> files) {
        StringBuilder text = new StringBuilder();
        for (Path file : files) {
            text.append("    ").append(file.getFileName()).append('\n');
        }
        return text.toString();
    }

    // The same names for one line of log or console, where a line is what is wanted.
    private static String named(java.util.List<Path> files) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Path file : files) names.add(file.getFileName().toString());
        return String.join(", ", names);
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
    // The document, chosen in a file chooser with one filter and no "All files": this
    // automation files a scan or the text export of one, and has nothing to say about
    // anything else. It opens where Args.chooserStart says - the folder this process was
    // started in, which from the right-click menu is the folder that was clicked. Null
    // when the chooser was cancelled.
    private static Path chooseDocument(Path startIn) {
        Path[] chosen = { null };
        onEdt(() -> {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(startIn.toFile());
            chooser.setDialogTitle("Choose the PDF or RTF to inventory");
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "PDF and RTF files (*.pdf, *.rtf)", EXT_PDF, EXT_RTF));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chosen[0] = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
        });
        return chosen[0];
    }

    // The offer a PDF with an RTF beside it gets: read the text instead of the pictures.
    //
    // Offered rather than taken, because which copy is the good one is not something this
    // automation knows - a badly converted RTF is worse than the scan it came from. But
    // the question is worth asking out loud: twenty scanned pages are twenty images to
    // rasterise, upload and be charged for, and the RTF of the same document is a few
    // kilobytes of Markdown saying what is on them - cheaper, and read rather than
    // deciphered. Both files are renamed at the end whichever way this goes.
    private static boolean askRtfInstead(Path pdf, Path rtf) {
        return ask("There is an RTF beside this PDF, under the same name:\n\n"
                + "    " + pdf.getFileName() + "\n    " + rtf.getFileName()
                + "\n\nWork on the RTF instead? Its text goes to the model as Markdown, "
                + "which is cheaper and more exact than the PDF's pages as images.\n\n"
                + "Both files are renamed either way.", "Use the RTF instead?");
    }

    // Every dialog below belongs to JRock's own window, so none of them can be lost
    // behind it. automationWindow() is null until that window exists, which JOptionPane
    // reads as "centre it on the screen" - right for the dialogs that come that early,
    // the RTF offer among them.
    private static boolean ask(String message, String title) {
        boolean[] yes = { false };
        onEdt(() -> yes[0] = javax.swing.JOptionPane.showConfirmDialog(
                JRock.automationWindow(), message, title,
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
