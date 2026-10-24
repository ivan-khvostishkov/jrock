// A JRock flow (one run, on something you point it at): one PDF, image, DOCX, RTF or
// TXT in; a DOCX with the pictures, a plain-text twin and a file name out.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockDocInventory.java [--working-dir <dir>] [--prompts-dir <dir>]
//                                               [document.pdf|.png|.jpg|.docx|.rtf|.txt]
//
// Without the document it asks for one in a file chooser, opened in the folder this was
// started in - which, from the right-click menu, is the folder that was clicked. CANCEL
// THAT CHOOSER AND THE JROCK WINDOW OPENS ANYWAY, with nothing run and no automation on
// it: this entry is also the quickest way to a JRock in the folder you clicked, for a
// setting, the log, or a cleanup. The
// three prompts it needs it finds BESIDE ITSELF, in whichever folder this file is in,
// which is why the installer copies a flow and its prompts into one folder and never
// splits them.
//
// The flags are JRock's and are passed straight through (see parseArgs): --working-dir
// names the folder whose settings it is to use - that folder's model, its images DPI,
// its Bedrock key - whatever directory the process was started in. The installer writes
// an Explorer right-click entry that carries exactly those flags, which is what lets one
// flow work on a file anywhere on the disk with one folder's settings, and lets the same
// flow be installed from two folders with settings of their own.
//
// THE JAR IS NAMED ON -cp AND HAS TO BE THERE. It is not in the repository - the
// repository carries no jars - and nothing downloads it: the installer puts it in the
// folder it installs into, or take it from a "Reproducible build" artifact, or build it
// with .github/build/BuildJar.java. Installed, this file sits in the Flows\ folder and
// the jar one level up; in the repository, point -cp at wherever you built it.
//
// -cp jrock.jar is not optional, and it is not only about run time. The source-file
// launcher (JEP 330) compiles this file in memory with exactly that class path, so every
// JRock call below is an ordinary typed static call the compiler has checked - and the
// jar is where JRock is loaded from when it runs. Forget the flag and javac says
// "cannot find symbol: class JRock" before anything starts, which is the whole
// diagnosis. A jar without the automation API in it fails the same way and
// names the method it is missing.
//
// There is nothing to instantiate, either: JRock's automation API is static, and has to
// be. The window, the log, the __JRock/ directory and the Bedrock session are one per
// process, so there is no object that could hold a second set of them. main() shows the
// window; the automation* methods drive the one that is there.
//
// What this one does is the chain the README describes under "Prompt library and
// chaining", with nobody pressing the keys:
//
//   1. jrock-prompt-doc-to-markdown.txt + the document         -> the document as
//      Markdown with image references, exported as <document name>.docx beside it -
//      by the same writer as "Export selected Markdown with images as DOCX...", so
//      the pictures the answer refers to are in the DOCX. A PDF goes in as HTML, by
//      xpdf's pdftohtml into __JRock/includes/ - JRock's own folder, so nothing is
//      written beside the document and there is nothing to ask about or tidy up
//      afterwards; an image is a one-page document and goes in as itself.
//   2. jrock-prompt-doc-to-ascii.txt    + that DOCX, as Markdown -> the document as
//      plain text, saved as <document name>.txt beside it.
//   3. jrock-prompt-doc-inventory.txt   + that text file          -> one file name.
//   4. Every file of that document renamed to it, if you say so - and to whatever you
//      type over it in the question, the suggestion being a suggestion: the other formats
//      of the same name, the InDesign layout beside them, and the ones that carry a suffix
//      - "3703093896-en.docx", "3703093896-SA.txt", a translation or an appendix - which
//      keep their suffix.
//
// Each pass starts from what the one before it left, so a run can start part of the
// way down: a DOCX (or an RTF) starts at pass 2, a TXT at pass 3. And files of the same
// name beside the document are the same document, so a PDF whose .txt or .docx is
// already there - an earlier run's, or Acrobat's "Export to Word" - is offered the one
// furthest down the chain instead (see laterStep): every pass skipped is a send not
// paid for. Whichever way it goes, all of them are renamed at the end (see siblingsOf),
// the suffixed ones with the suffix that tells them apart still on them.
//
// The JRock window is the real one, and it is in front of you the whole way: the prompt
// fills with include tokens as the pages are converted, the log reports every step, and
// both answers stay in the transcript. The prompt is read-only until the automation is
// finished and editable again afterwards - the conversation is there to be carried on
// by hand, which is the point of driving the real window rather than a headless copy of
// it. Copy this file and rewrite it for chains of your own - and read
// JRockTranslateToEnglish.java beside it, which is this same plumbing around a single
// pass, and takes a document of any type rather than these.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class JRockDocInventory {

    // The three prompts, expected beside this file.
    private static final String PROMPT_MARKDOWN = "jrock-prompt-doc-to-markdown.txt";
    private static final String PROMPT_ASCII = "jrock-prompt-doc-to-ascii.txt";
    private static final String PROMPT_NAME = "jrock-prompt-doc-inventory.txt";

    // The kinds of file this automation files, and the only ones: a scan (a PDF, or a
    // picture of a single page), the word-processor documents one becomes, and plain
    // text. Constants because every step asks the extension the same questions - which
    // pass the file starts at, which files are its twins, and whether a path handed
    // over by the right-click menu is one this agent reads at all. "docx" and "rtf" are
    // also the include kinds JRock's automationInclude takes for them.
    private static final String EXT_PDF = "pdf";
    private static final String EXT_RTF = "rtf";
    private static final String EXT_DOCX = "docx";
    private static final String EXT_TXT = "txt";
    private static final java.util.List<String> EXT_IMAGES =
            java.util.List.of("png", "jpg", "jpeg", "gif", "webp");

    // The passes, by where a file of each kind starts: a scan at the Markdown pass, a
    // DOCX or an RTF at the plain-text one, a TXT at the naming one.
    private static final int PASS_MARKDOWN = 1, PASS_TEXT = 2, PASS_NAME = 3;

    // The copies of one document worth starting from instead, best first: the further
    // down the chain, the fewer sends are left to pay for.
    private static final java.util.List<String> LATER_FIRST =
            java.util.List.of(EXT_TXT, EXT_DOCX, EXT_RTF);

    // JRock's own flags, spelled out here because this automation both reads them and
    // passes them on (see parseArgs).
    // --auto: nobody is watching, so nothing is asked.
    //
    // The flow is the same flow; what changes is that every question answers itself the
    // way the person at the keyboard almost always answers it - yes, rename them - and
    // that the result is PRINTED in a line a program can read instead of shown in a
    // dialog a program cannot dismiss. It is how the paper docs agent files a scan the
    // moment it arrives, with the operator three rooms away.
    //
    // Two documents are allowed with it, and only with it: the two passes of a
    // two-sided batch, which are merged into one PDF first (see mergeDuplex) because
    // they are one document and filing them separately would file half of it twice.
    private static final String AUTO_FLAG = "--auto";
    private static final String AUTO_RESULT = "AUTO-RESULT";

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

        // Nothing to say: the chooser was cancelled, so nothing was run - and the
        // window that was opened instead (see run) is the operator's now, with no
        // automation to end and no dialog to show about it.
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

        // --auto ENDS THE PROCESS, and that is not a tidiness detail.
        //
        // main returning does not end a JVM with a window open: Swing's event thread is
        // not a daemon, so this process lived on with JRock's window on screen until
        // somebody closed it. Interactively that is exactly right - the window holds the
        // log and the conversation, and the operator closes it when they have read it.
        // Under --auto nobody is there to close anything, and whoever started this is
        // waiting for the PROCESS to end and not merely for its answer: the paper docs agent
        // reads this output until the stream ends, which it only does when the process
        // does. So it read the result line, printed "Flow: Finished", and then waited for
        // ever on a process that was never going to exit - with every later request to
        // that agent queued behind it, and push-to-talk reporting "the paper docs agent did
        // not answer" three minutes after the letter had in fact been filed.
        //
        // Read off the command line rather than off `asking`, so that an automation that
        // stopped before it got as far as reading its flags still ends here.
        if (java.util.Arrays.asList(args).contains(AUTO_FLAG)) {
            try {
                javax.swing.JFrame open = JRock.automationWindow();
                if (open != null) onEdt(open::dispose);
            } catch (RuntimeException closingAnyway) {
                // The window is going with the process in the next line regardless.
            }
            System.exit(stopped == null ? 0 : 1);
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
        Path markdownPrompt = mustExist(here.resolve(PROMPT_MARKDOWN));
        Path ascii = mustExist(here.resolve(PROMPT_ASCII));
        Path inventory = mustExist(here.resolve(PROMPT_NAME));

        asking = !told.auto;

        // The document. Asked for before JRock is started, so a cancelled chooser
        // leaves nothing behind at all.
        Path chosen = (told.document != null)
                ? told.document : chooseDocument(told.chooserStart());
        if (chosen == null) {
            // CANCELLED IS NOT "NOTHING TO DO". This flow's right-click entry is often
            // the quickest way to a JRock window in the folder that was clicked - to
            // change a setting there, to read the log, to clean the folder up with
            // "Cleanup JRock files..." - and closing the chooser used to end the process,
            // leaving the operator to go and find JRock itself.
            //
            // So the window opens with NO automation begun on it (not startJRock, which
            // takes it): the prompt is editable, Send works, and it is the operator's.
            // main() returns, and the process lives on with the window - Swing's event
            // thread is not a daemon.
            //
            // Not under --auto, where nobody is there to be given a window and whoever
            // started this is waiting for the process to END (see main): a window held
            // open by a flow with nothing to file would be a flow that never answers.
            System.out.println("No document chosen.");
            if (!asking) return null;
            System.out.println("Opening the JRock window instead.");
            JRock.main(told.forJRock.toArray(new String[0]));
            check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
            started = true;   // nothing else may start a second window in this process
            return null;
        }
        if (!Files.isRegularFile(chosen)) throw new Stop("not a file:\n\n    " + chosen);

        // Two passes of a two-sided batch: one document in two files, merged into one
        // PDF before anything is read, named after the front pass. JRock does the
        // interleave - its own duplex merge, through the automation API - because that
        // is where Ghostscript and the page pairing already live.
        //
        // The two passes are kept in hand: once the merged document has been read, named
        // and renamed, they are two files holding the same pages under names that mean
        // nothing, and the last step offers to delete them (see the end of this method).
        Path frontPass = null;
        Path backPass = null;
        if (told.second != null) {
            frontPass = chosen;
            backPass = told.second;
            chosen = mergeDuplex(chosen, told.second, told);
        }

        // Checked here and not left to the include: the chooser only offers these
        // extensions, but the right-click menu hands over whatever was clicked, and a
        // .jpg arriving that way would otherwise be sent and paid for before anything
        // noticed it is not a document this agent files.
        if (passOf(chosen) == 0) {
            throw new Stop("this files a PDF, an image, a DOCX, an RTF or a TXT, and that "
                    + "is none of them:\n\n    " + chosen.getFileName() + "\n\nA PDF goes "
                    + "in as HTML, an image as itself, a DOCX or an RTF as Markdown text, a "
                    + "TXT as it is; nothing else is read here.");
        }

        // The same document in the other formats, when they are there - and the offer to
        // start from the one furthest down the chain (see laterStep). Whichever is
        // worked on, all of them are renamed at the end: they are one document.
        Path document = chosen;
        Path later = laterStep(chosen);
        if (later != null && askStartFrom(chosen, later)) document = later;
        int pass = passOf(document);
        System.out.println("Document: " + document + " (from pass " + pass + ")");

        // ---- Start JRock and wait for it to be usable ----------------------
        startJRock(told, "doc inventory of " + document.getFileName());

        // Everything written here goes beside the document and is named after it, which
        // is what makes the set findable before it is renamed - and what each pass reads
        // back from the one before.
        Path dir = document.getParent();
        java.util.List<Path> written = new java.util.ArrayList<>();

        // ---- An image's A4 PDF, to print from -------------------------------
        // Before any send, being free: see writeA4Pdf. Not when a PDF of the same name
        // is there already - that is this document's PDF, and is renamed with the rest.
        if (EXT_IMAGES.contains(extension(document))
                && !Files.exists(dir.resolve(stem(document) + "." + EXT_PDF))) {
            Path printable = dir.resolve(stem(document) + "." + EXT_PDF);
            boolean made;
            try {
                made = writeA4Pdf(document, printable);
            } catch (IOException ex) {
                made = false;
                System.out.println("No PDF copy: " + ex.getMessage());
            }
            if (made) {
                written.add(printable);
                System.out.println("Wrote " + printable);
            } else {
                System.out.println("No PDF copy: " + document.getFileName()
                        + " is not an image ImageIO reads.");
            }
        }

        // ---- Pass one: the document as Markdown, then as a DOCX -------------
        // INCLUDED AS THE FOLDER SAYS, AND THE PROMPT FITTED TO WHAT ARRIVED.
        //
        // This used to force a reading - a PDF as xpdf HTML, an image with its Markdown
        // reference - which is the best input there is for this pass and is also this
        // automation overruling the operator in their own folder. "Include a PDF as" and
        // "Write a Markdown reference" are settings of the JRock that runs here, and a
        // flow that ignores them is a flow whose output cannot be explained by what the
        // dialog says.
        //
        // So the include is the plain one, and THE PROMPT COVERS WHAT MAY ARRIVE: page
        // images laid out, extracted text, either of them with or without the references
        // that let a picture be placed. It says what to do in each case - and, where no
        // references are attached, to write none and invent nothing - so an instruction
        // and an attachment cannot disagree whatever the dialog says. What did arrive is
        // read off the prompt and printed (see sayWhatArrived), because that is the first
        // thing to know when a document comes out with no pictures in it.
        Path wordFile = document;
        if (pass == PASS_MARKDOWN) {
            System.out.println("Pass 1: " + PROMPT_MARKDOWN);
            check(JRock.automationLoadPrompt(markdownPrompt));
            dropPlaceholders();
            check(JRock.automationInclude(document));
            sayWhatArrived();
            // The send is checked; its text is not wanted here at all.
            reply(JRock.automationSend(REPLY_TIMEOUT_MS));
            wordFile = free(dir, stem(document), "." + EXT_DOCX);
            // EXPORTED FROM THE LOG, where the answer already is - and selected by WHERE
            // IT IS rather than by its own text (automationSelectLastAnswer). Handing the
            // reply back to be found again was three lines and two ways to miss: a
            // trailing newline, or the ``` fence a model puts round an answer when it was
            // asked for Markdown, which had to be taken off here before the text would
            // match what the log showed. The export takes that fence off itself now.
            // Selected, written, and let go of: the log scrolls nowhere while a selection
            // stands.
            check(JRock.automationSelectLastAnswer());
            check(JRock.automationExportSelectionDocx(wordFile.toString()));
            JRock.automationClearLogSelection();
            written.add(wordFile);
            System.out.println("Wrote " + wordFile);
        } else if (pass == PASS_NAME && alone(document)) {
            // A TXT THAT IS NOBODY'S PLAIN-TEXT COPY IS A DOCUMENT OF ITS OWN, and gets
            // the DOCX the others get. No send: the text is already the text, and the
            // exporter reads Markdown - so this works for a note and for an answer the
            // paper docs agent was asked to keep, which arrives as markdown in a .txt.
            //
            // Only when the TXT stands alone. A .txt BESIDE a document is that
            // document's plain text, written by pass two, and exporting a DOCX from it
            // would be making a second document out of a copy.
            String markdown = readText(document);
            if (markdown.isBlank()) {
                throw new Stop(document.getFileName() + " is empty, so there is no "
                        + "document to make of it.");
            }
            System.out.println("A text document on its own: writing its DOCX, no send.");
            wordFile = free(dir, stem(document), "." + EXT_DOCX);
            // THROUGH THE PROMPT, because nothing was sent and so nothing of this is in
            // the log. The prompt is this automation's workspace between steps - it is
            // read-only to the operator while the window is held - so the text goes
            // there, is exported from there, and pass three loads its own prompt over it.
            check(JRock.automationSetPrompt(markdown));
            check(JRock.automationExportPromptDocx(wordFile.toString()));
            written.add(wordFile);
            System.out.println("Wrote " + wordFile);
        }

        // ---- Pass two: the DOCX as plain text -------------------------------
        // The DOCX goes in as Markdown - which is how a DOCX becomes plain text here: the
        // model lays that Markdown out for a fixed-width page.
        Path textFile = document;
        if (pass <= PASS_TEXT) {
            System.out.println("Pass 2: " + PROMPT_ASCII);
            check(JRock.automationLoadPrompt(ascii));
            dropPlaceholders();
            check(JRock.automationInclude(wordFile));
            String text = reply(JRock.automationSend(REPLY_TIMEOUT_MS));
            textFile = free(dir, stem(document), "." + EXT_TXT);
            Files.write(textFile, text.getBytes(StandardCharsets.UTF_8));
            written.add(textFile);
            System.out.println("Wrote " + textFile);
        }

        // ---- Pass three: a name for it --------------------------------------
        System.out.println("Pass 3: " + PROMPT_NAME);
        check(JRock.automationLoadPrompt(inventory));
        dropPlaceholders();
        check(JRock.automationInclude(textFile));
        String answer = reply(JRock.automationSend(REPLY_TIMEOUT_MS));
        String base = baseName(answer);
        if (base.isEmpty()) {
            throw new Stop("the second answer held no usable file name:\n\n"
                    + answer.trim());
        }
        System.out.println("Suggested name: " + base);

        // ---- The rename, which is the user's call --------------------------
        // Asked rather than assumed: this is the one step that changes files the user
        // owns, and the name is a model's suggestion, not a fact about the document -
        // which is also why the question holds it in a field to be typed over rather
        // than in a sentence to agree with (see askRename).
        //
        // Every file of the document goes at once, or none does - the document, what this
        // run wrote, every other copy of it on the disk, and the files named after it
        // that are not copies at all: the translations, the appendices, the InDesign
        // layout (see siblingsOf). A scan renamed without its export beside it leaves one
        // document under two names, which is the mess this automation exists to clear up
        // rather than to make - and a translation left behind is worse, because its own
        // name no longer says which document it is the translation OF.
        java.util.List<Path> files = new java.util.ArrayList<>();
        files.add(chosen);
        for (Path sibling : siblingsOf(chosen)) if (!files.contains(sibling)) files.add(sibling);
        for (Path file : written) if (!files.contains(file)) files.add(file);

        // The name they all go under until this line, which is what their suffixes are
        // measured against: the set is renamed, so this has to be read before any of it
        // moves.
        String filedAs = stem(chosen);
        java.util.List<String> suffixes = suffixesIn(files, filedAs);
        // THE SUFFIXES THEMSELVES, not the names they would make. The name in the dialog
        // is edited there (see askRename), so "2026-06-14-DHL-...-en" written out here
        // would be a promise about a name that may no longer be the name by the time the
        // button is pressed - and a dialog that contradicts what then happens is worse
        // than one that says less.
        //
        // Broken into short lines by hand, as every message here is: JOptionPane wraps a
        // String message nowhere at all, so a sentence written as one line is a dialog as
        // wide as that sentence.
        String suffixPart = suffixes.isEmpty() ? ""
                : "\n\nThe suffixed files keep their suffix - " + String.join(", ", suffixes)
                        + " -\nwhatever name you give the set: a suffix is what tells one "
                        + "file\nof a document from another, so the translation stays the "
                        + "translation.";

        String howMany = files.size() == 1 ? "the file"
                : files.size() == 2 ? "both files" : "all " + files.size() + " files";
        String name = askRename(base, howMany, files, dir, suffixPart);
        boolean rename = name != null;
        if (rename && !name.equals(base)) {
            System.out.println("Name typed in place of the suggestion: " + name);
        }

        // NOTHING OF PASS ONE IS LEFT TO TIDY UP. The HTML pages xpdf made of a PDF are
        // working material for the model, not copies for anybody to open, and JRock writes
        // them into its own __JRock/includes/ folder - so the document's folder holds the
        // document and the files named after it, and nothing else this flow has to ask
        // about, rename or delete.
        if (!rename) {
            return new Result("kept " + named(files) + "; the suggested name was " + base,
                    "Nothing was renamed:\n\n" + listed(files)
                            + "\nThe suggested name was\n\n    " + base + "\n");
        }
        java.util.List<Path> renamed = new java.util.ArrayList<>();
        for (Path file : files) renamed.add(renameTo(file, name, filedAs));
        System.out.println("Renamed to " + named(renamed));

        // ---- The two passes the document was merged from -------------------
        // Deleted last, and only once everything before it has gone right.
        //
        // A two-sided letter arrives as two scans; the merge puts every page of both into
        // one PDF, and refuses a pair whose page counts differ, so nothing of either can
        // be missing from it. That one PDF is what was read, named and renamed. What is
        // left over is two files holding the same pages under names that mean nothing -
        // and the scanner folder is where the operator looks to see what has NOT been
        // dealt with: two leftovers there are a letter that still looks unfiled, and
        // "file it" on them would file the same letter a second time from scratch.
        //
        // THE GATE IS EVERYTHING-WENT-RIGHT, NOT NEARLY. The pair was merged; the merge
        // reported no problem (or this method would have stopped); every file of the
        // document was renamed, which is this line above; and the renamed document is
        // there and is bigger than either pass on its own, which a truncated merge would
        // not be. Anything else and both scans stay, because whatever is interfering with
        // one file in this folder is a reason not to delete another one beside it.
        //
        // Interactively it is asked, as the rename is - and the question says that this
        // does not go through the Recycle Bin, because it does not. --auto deletes them,
        // which is what the operator asked --auto for.
        String scansNote = "";
        if (frontPass != null && !renamed.isEmpty()) {
            Path filed = renamed.get(0);              // the document itself: files.get(0)
            long whole = sizeOf(filed);
            long front = sizeOf(frontPass);
            long back = sizeOf(backPass);
            if (front <= 0 || back <= 0 || whole <= front || whole <= back) {
                // Either a pass has already gone, or the merged document is no bigger
                // than one of them: not the shape of a merge that holds both.
                scansNote = "the two scans kept - " + filed.getFileName()
                        + " is not bigger than both of them";
            } else if (askDeleteScans(frontPass, backPass, filed)) {
                java.util.List<String> gone = new java.util.ArrayList<>();
                java.util.List<String> stayed = new java.util.ArrayList<>();
                for (Path oneScan : java.util.Arrays.asList(frontPass, backPass)) {
                    try {
                        Files.delete(oneScan);
                        gone.add(oneScan.getFileName().toString());
                    } catch (IOException ex) {
                        stayed.add(oneScan.getFileName() + " (" + ex.getMessage() + ")");
                    }
                }
                scansNote = gone.isEmpty() ? "" : String.join(" and ", gone) + " deleted";
                if (!stayed.isEmpty()) {
                    scansNote += (scansNote.isEmpty() ? "" : "; ") + "could not delete "
                            + String.join(", ", stayed);
                }
            } else {
                scansNote = "the two scans kept";
            }
            System.out.println(scansNote);
        }
        String scansPart = scansNote.isEmpty() ? "" : "; " + scansNote;
        String scansLine = scansNote.isEmpty() ? "" : "\n" + capitalised(scansNote) + ".";

        if (!asking) {
            // The one line the paper docs agent reads out of all this output. The name is
            // the suggestion under --auto, nobody being there to type another.
            System.out.println(autoResult(name, renamed.isEmpty() ? document
                    : renamed.get(0), renamed.size()));
        }
        // What the model suggested, when that is not what the files are called: the one
        // fact the names themselves no longer hold, and the thing to read in the log a
        // week later when the folder looks nothing like the answer.
        String suggestionPart = name.equals(base) ? ""
                : "; the suggested name was " + base;
        return new Result("renamed to " + named(renamed) + suggestionPart + scansPart,
                "Renamed to\n\n" + listed(renamed) + "\nin " + dir + "." + scansLine);
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
        private Path second = null;      // the back pass, with --auto only
        private boolean auto = false;

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
            } else if (flag.equals(AUTO_FLAG)) {
                parsed.auto = true;
            } else if (arg.startsWith("-")) {
                parsed.forJRock.add(arg);
            } else if (parsed.document == null) {
                parsed.document = documentPath(arg);
            } else if (parsed.second == null) {
                parsed.second = documentPath(arg);
            } else {
                throw new Stop("two documents at the most - the two passes of one "
                        + "two-sided batch - and three were given:\n\n"
                        + parsed.document + "\n" + parsed.second + "\n" + arg);
            }
        }
        if (parsed.second != null && !parsed.auto) {
            throw new Stop("two documents are only taken with " + AUTO_FLAG + ", where "
                    + "they mean the two passes of one two-sided batch.");
        }
        return parsed;
    }

    // JRock, started once however many times this is called.
    //
    // Once, because the duplex merge needs the window before the rest of the flow does -
    // it is JRock's own merge, through the automation API - and a second JRock.main would
    // be a second window driving the same folder.
    //
    // The real entry point shows the window on the event dispatch thread and returns at
    // once. Everything after it runs on THIS thread, which is what the automation API
    // asks for: every one of its methods blocks.
    private static boolean started;

    private static void startJRock(Args told, String what) {
        if (started) return;
        started = true;
        JRock.main(told.forJRock.toArray(new String[0]));
        check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
        check(JRock.automationBegin(what));
    }

    // The two passes into one PDF, through JRock's own duplex merge.
    //
    // The front pass is the one whose name comes first: a scanner writes the first pass
    // before the second, so the earlier name holds the fronts and the later one holds
    // the backs in reverse. That is the same rule JRock's own merge uses, and the reason
    // it is a rule rather than a guess is that getting it wrong puts every back page
    // against the wrong front.
    private static Path mergeDuplex(Path one, Path two, Args told) {
        Path[] passes = frontThenBack(one, two);
        Path front = passes[0];
        Path back = passes[1];
        String name = front.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path merged = front.resolveSibling((dot > 0 ? name.substring(0, dot) : name)
                + ".Merged.pdf");
        System.out.println("Two passes: front " + front.getFileName() + ", back "
                + back.getFileName() + " (in reverse), into " + merged.getFileName());
        startJRock(told, "duplex merge of " + front.getFileName() + " and "
                + back.getFileName());
        String problem = JRock.automationMergeDuplex(front.toString(), back.toString(),
                merged.toString());
        if (problem != null) throw new Stop("the two passes would not merge: " + problem);
        return merged;
    }

    // The pair in the order their names put them, which is front first.
    private static Path[] frontThenBack(Path a, Path b) {
        String one = a.getFileName().toString();
        String two = b.getFileName().toString();
        int by = stemOf(one).compareToIgnoreCase(stemOf(two));
        if (by == 0) by = one.compareToIgnoreCase(two);
        if (by == 0) by = a.toString().compareTo(b.toString());
        return by <= 0 ? new Path[] { a, b } : new Path[] { b, a };
    }

    private static String stemOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // The one line a program reads out of all this: what the document was called, when
    // it was scanned, and how many files now carry that name.
    private static String autoResult(String base, Path document, int files) {
        String scanned = "";
        try {
            scanned = java.time.ZonedDateTime.ofInstant(
                    Files.getLastModifiedTime(document).toInstant(),
                    java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd HH:mm:ss"));
        } catch (IOException ex) {
            scanned = "unknown";
        }
        return AUTO_RESULT + " name=" + base + " scanned=" + scanned + " files=" + files;
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

    // Takes the bare "@img" / "@txt" lines out of the prompt just loaded. The sample
    // prompts end with them as placeholders: a person reads them as "the attachments go
    // here" and presses Ctrl+I on one. This automation includes the document itself, so
    // they go - and what is left ends with one newline, as each include token goes on a
    // line of its own at the end of it. They are no tokens (a token carries a
    // 12-hex-digit hash), so one left in would go out as the word it is.
    // WHAT THE INCLUDE ACTUALLY ATTACHED, read off the prompt it attached into - said,
    // and nothing else done about it.
    //
    // Three readings reach this pass, all of them legitimate settings of the folder, and
    // the PROMPT covers all three rather than this automation editing it: page images
    // show the document laid out, extracted text shows the words alone, and a reference
    // line above a picture is what lets the answer place that picture. Asked for pictures
    // it has no references for, the prompt tells the model to write none and to invent
    // nothing - so an instruction and an attachment cannot disagree, whatever the dialog
    // is set to.
    //
    // Printed because it is the first thing to know when a DOCX comes out without the
    // pictures in it, or with less structure than the page had: what arrived is what the
    // folder's "Include a PDF as" and "Write a Markdown reference" said to attach.
    private static void sayWhatArrived() {
        String prompt = JRock.automationPromptText();
        if (prompt == null) throw new Stop("the prompt could not be read.");
        boolean pictures = prompt.contains("@img ");
        boolean text = prompt.contains("@txt ");
        System.out.println("Attached as this folder is configured: "
                + (text && pictures ? "text and page images"
                   : text ? "text only" : pictures ? "page images only" : "nothing")
                + (prompt.contains("![") ? ", with Markdown references - the pictures can be "
                        + "placed in the document"
                   : ", with no references - the pictures are read, not placed"));
    }

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

    // The reply a send produced, read out of its own file under __JRock/messages/.
    //
    // The request's file is checked and not read, which is the point of checking it: if
    // the message JRock says it sent is not on disk, the stamps do not mean what they
    // look like they mean and the reply under the other one is not to be trusted.
    // One text file, read as UTF-8: the markdown a .md document arrives as.
    private static String readText(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read:\n\n    " + file + "\n\n" + ex.getMessage());
        }
    }

    private static String reply(JRock.Sent sent) {
        if (!sent.ok()) throw new Stop(sent.failure());
        if (JRock.automationMessageFile("operator", sent.operator()) == null) {
            throw new Stop("the request was not written to __JRock/messages/.");
        }
        java.nio.file.Path file = JRock.automationMessageFile("assistant", sent.assistant());
        if (file == null) throw new Stop("the reply was not written to __JRock/messages/.");
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read the reply:\n\n    " + file
                    + "\n\n" + ex.getMessage());
        }
    }

    // ---- Files -------------------------------------------------------------
    // The characters a name is made of, as the body of a regex class: letters, the marks
    // that belong to them, and digits - and not merely A-Za-z0-9.
    //
    // The prompt asks for the name in A-Z, a-z, digits and hyphens only, an umlaut
    // written out (u-umlaut -> ue, eszet -> ss): a name with an umlaut in it does not
    // travel well - Adobe InDesign, for one, will not place a .docx called that. This
    // set is wider on purpose, as the safety net for a reply that ignores the rule:
    // held to ASCII, a bank whose letterhead says Muenchner with an u-umlaut would be
    // filed as
    //     2026-09-22-M-nchnerBank-...
    // every letter outside the set becoming a dash, and a dash is the one character here
    // that means "the next part of the name starts". \p{L} rather than a list of accented
    // letters, because such a list is never complete - a Greek
    // or Cyrillic name is a legal file name too, and dashes in place of one help nobody.
    // \p{M} for a reply whose letters arrive decomposed (see the NFC in baseName), \p{N}
    // for digits that are not Arabic numerals.
    private static final String NAME_CHARS = "\\p{L}\\p{M}\\p{N}";

    // A line that is nothing but a file name: name characters and spaces, no comma, no
    // apostrophe, no colon - the punctuation of prose is what tells the two apart.
    private static final java.util.regex.Pattern NAME_LINE = java.util.regex.Pattern
            .compile("[" + NAME_CHARS + "][" + NAME_CHARS + " ._-]{2,}");

    // A file name sitting inside a line of text ("File name: 2020-07-27-FTS-IncomeTaxReturn-..."):
    // a run of name characters long enough not to be an ordinary word.
    private static final java.util.regex.Pattern NAME_IN_LINE = java.util.regex.Pattern
            .compile("[" + NAME_CHARS + "][" + NAME_CHARS + "._-]{6,}[" + NAME_CHARS + "]");

    // How many spaces a line may hold and still be read as a name rather than as prose.
    private static final int NAME_MAX_SPACES = 4;

    // The file name, out of the second reply.
    //
    // The prompt asks for nothing but the name, and the name is normally the first line -
    // but "normally" is not a contract, and a model may add a sentence about the
    // document after it. A sentence reduced to name-safe characters is not a name - a
    // reply of
    //     2020-07-27-FTS-IncomeTaxReturn-2019
    //
    //     The document is the operator's personal income-tax ...
    // must give the first line, not "The-document-is-Ivan-Khvostishkov-s-personal-...".
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
        return nameSafe(found);
    }

    // One line as a file name: the extension off, the characters a name cannot hold
    // turned into dashes, and the length capped. "" when nothing is left of it.
    //
    // THE SAME RULE FOR BOTH NAMES THERE ARE HERE - the one the model answered with (see
    // baseName) and the one typed over it in the dialog (see askRename). A name typed in
    // a hurry holds the things typing holds: a trailing space, a slash out of a path
    // pasted whole, "Rechnung Mai 2026" with the spaces still in it. Cleaning one and
    // not the other would mean the operator could produce a name this flow would never
    // have chosen - and on Windows a name with a slash in it is not a name at all.
    private static String nameSafe(String text) {
        String name = java.text.Normalizer
                .normalize(text.trim(), java.text.Normalizer.Form.NFC)
                .replaceAll("(?i)\\.(pdf|rtf|docx|txt|png|jpe?g|gif|webp)$", "");
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

    // The pass a file starts at (see PASS_MARKDOWN), or 0 for a kind this does not file.
    private static int passOf(Path file) {
        String ext = extension(file);
        if (ext.equals(EXT_PDF) || EXT_IMAGES.contains(ext)) return PASS_MARKDOWN;
        if (ext.equals(EXT_DOCX) || ext.equals(EXT_RTF)) return PASS_TEXT;
        if (ext.equals(EXT_TXT)) return PASS_NAME;
        return 0;
    }

    // Every other file of the same document, beside it in the same folder. Empty when
    // there is none.
    //
    // Two shapes count, and the folder is READ rather than asked about candidate names,
    // because the second shape has no list of names to try:
    //
    //   the same name, another extension  - "3703093896.docx" of "3703093896.pdf", the
    //       .pdf of a .txt, the .png scanned of the same page, the .indd the layout was
    //       made in. Such a set is what an earlier run, or Acrobat's "Export to Word",
    //       or the designer leaves behind: one document on the disk more than once.
    //   the same name with a SUFFIX       - "3703093896-en.docx", "3703093896_SA.txt":
    //       the translations and the appendices, which are not copies of the document
    //       but parts of it, and are no use filed under a name of their own.
    //
    // Any extension, because the list of things that belong to a document is not this
    // flow's to close: a layout, a spreadsheet appendix, a scanner's sidecar are all
    // named after the document they belong to, and leaving one behind splits the set -
    // which is the mess this automation exists to clear up rather than to make.
    //
    // A suffix starts with a hyphen or an underscore and nothing else (see SUFFIX_START):
    // a dot there would make "scan0167.Merged.pdf" a file of "scan0167.pdf", and the
    // merged document is the one whose own passes the last step offers to DELETE.
    private static java.util.List<Path> siblingsOf(Path file) {
        java.util.List<Path> found = new java.util.ArrayList<>();
        Path dir = file.getParent();
        String stem = stem(file);
        try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
            listing.filter(other -> !other.equals(file))
                    .filter(Files::isRegularFile)
                    .filter(other -> companionSuffix(stem, other) != null)
                    .sorted()
                    .forEach(found::add);
        } catch (IOException cannotRead) {
            // One document under one name is still better than none: the file that was
            // chosen is renamed, and the console says what was not looked at.
            System.out.println("Could not list " + dir + ", so only the document itself "
                    + "is renamed: " + cannotRead.getMessage());
        }
        return found;
    }

    // The characters a suffix may start with. See siblingsOf for why a dot is not one.
    private static final String SUFFIX_START = "-_";

    // What file is to a document named stem: "" for another format of the same name,
    // "-en" or "_SA" for a translation or an appendix - and null when it is no file of
    // that document at all, which is what siblingsOf filters on.
    //
    // Names are compared ignoring case, as the file system this runs on does: "Scan0167"
    // and "scan0167" are one name here, and a translation whose name was typed by hand
    // is as much a file of the document as one a tool wrote.
    private static String companionSuffix(String stem, Path file) {
        String other = stem(file);
        if (other.length() < stem.length()) return null;
        if (!other.regionMatches(true, 0, stem, 0, stem.length())) return null;
        String suffix = other.substring(stem.length());
        if (suffix.isEmpty()) return "";
        return SUFFIX_START.indexOf(suffix.charAt(0)) >= 0 ? suffix : null;
    }

    // The same, for a file already known to belong to the document: its suffix, or "" -
    // which is what a rename keeps (see renameTo).
    private static String suffixOf(String stem, Path file) {
        String suffix = companionSuffix(stem, file);
        return suffix == null ? "" : suffix;
    }

    // The suffixes among a document's files, each once and in the order they come: what
    // the rename question has to show, because those files do not all get one name.
    private static java.util.List<String> suffixesIn(java.util.List<Path> files, String stem) {
        java.util.List<String> suffixes = new java.util.ArrayList<>();
        for (Path file : files) {
            String suffix = suffixOf(stem, file);
            if (!suffix.isEmpty() && !suffixes.contains(suffix)) suffixes.add(suffix);
        }
        return suffixes;
    }

    // Is this file the only copy of its document? Which is what says whether a TXT is a
    // document in its own right - a note, or an answer somebody kept - or the plain text
    // of a document that is already here, written by pass two or by Acrobat.
    //
    // Only a twin of the same name counts, and only one of the kinds this flow reads: a
    // translation beside a note, or a layout, is another file of the set and not this
    // note's own text, so the note still gets the DOCX every document here gets.
    private static boolean alone(Path file) {
        String stem = stem(file);
        for (Path sibling : siblingsOf(file)) {
            if (suffixOf(stem, sibling).isEmpty() && passOf(sibling) != 0) return false;
        }
        return true;
    }

    // The sibling furthest down the chain from file, when one is further down than file
    // itself: a .txt before a .docx before an .rtf. Null when there is none - and for a
    // TXT, which starts at the last pass already.
    private static Path laterStep(Path file) {
        int from = passOf(file);
        for (String ext : LATER_FIRST) {
            Path beside = file.getParent().resolve(stem(file) + "." + ext);
            if (!beside.equals(file) && Files.isRegularFile(beside) && passOf(beside) > from) {
                return beside;
            }
        }
        return null;
    }

    // ---- An image as an A4 PDF ---------------------------------------------
    // A picture of a page is filed as a document, and a document is printed - which from
    // a PNG means a viewer's own idea of page size and margins. So an image gets a PDF
    // copy beside it: one A4 page, the image centred on it, ready to print as it is.
    //
    // The sizes, in PDF points (1/72 in): A4 is 595.28 x 841.89, and 30 mm of margin on
    // every side leaves a frame of 150 x 237 mm (1771 x 2799 pixels at 300 dpi). The
    // image is placed at 300 dpi - 0.24 pt a pixel - when it fits the frame at that, and
    // shrunk to the frame, aspect kept, when it does not; never enlarged. So 300 dpi is
    // the least it prints at, and a small image stays small, in the middle of the page.
    //
    // Written here rather than by Ghostscript, which reads JPEG (through its viewjpeg.ps)
    // and not PNG or GIF. A JPEG goes in as the bytes it is (DCTDecode), so nothing is
    // recompressed; anything else ImageIO reads goes in as its pixels, deflated. A WEBP,
    // which ImageIO does not read, gets no PDF - said on the console, nothing more.
    private static final double MM = 72 / 25.4;
    private static final double A4_WIDTH = 210 * MM, A4_HEIGHT = 297 * MM;
    private static final double MARGIN = 30 * MM;
    private static final double POINTS_PER_PIXEL_AT_300_DPI = 72.0 / 300;

    // The PDF copy of image, at pdf. Returns false when the image could not be read.
    private static boolean writeA4Pdf(Path image, Path pdf) throws IOException {
        byte[] bytes = Files.readAllBytes(image);
        java.awt.image.BufferedImage picture =
                javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (picture == null) return false;
        int w = picture.getWidth(), h = picture.getHeight();
        boolean gray = picture.getColorModel().getNumColorComponents() == 1;
        String ext = extension(image);
        boolean jpeg = ext.equals("jpg") || ext.equals("jpeg");

        // The pixels, for anything that is not a JPEG: over white, so a transparent PNG
        // prints as it looks on screen; one byte a pixel for gray, three for colour.
        byte[] stream;
        String filter;
        if (jpeg) {
            stream = bytes;
            filter = "/DCTDecode";
        } else {
            java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
            try (java.util.zip.DeflaterOutputStream z = new java.util.zip.DeflaterOutputStream(raw)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = picture.getRGB(x, y);
                        int a = argb >>> 24;
                        int r = over(argb >> 16 & 0xff, a), g = over(argb >> 8 & 0xff, a),
                            b = over(argb & 0xff, a);
                        if (gray) {
                            z.write(r);
                        } else {
                            z.write(r); z.write(g); z.write(b);
                        }
                    }
                }
            }
            stream = raw.toByteArray();
            filter = "/FlateDecode";
        }

        double scale = Math.min(1, Math.min(
                (A4_WIDTH - 2 * MARGIN) / (w * POINTS_PER_PIXEL_AT_300_DPI),
                (A4_HEIGHT - 2 * MARGIN) / (h * POINTS_PER_PIXEL_AT_300_DPI)));
        double dw = w * POINTS_PER_PIXEL_AT_300_DPI * scale;
        double dh = h * POINTS_PER_PIXEL_AT_300_DPI * scale;
        String place = String.format(java.util.Locale.ROOT, "q %.3f 0 0 %.3f %.3f %.3f cm /Im0 Do Q",
                dw, dh, (A4_WIDTH - dw) / 2, (A4_HEIGHT - dh) / 2);
        String box = String.format(java.util.Locale.ROOT, "[0 0 %.3f %.3f]", A4_WIDTH, A4_HEIGHT);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.util.List<Integer> offsets = new java.util.ArrayList<>();
        ascii(out, "%PDF-1.4\n%âãÏÓ\n");
        object(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>", null);
        object(out, offsets, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>", null);
        object(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox " + box
                + " /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>", null);
        object(out, offsets, "<< /Type /XObject /Subtype /Image /Width " + w + " /Height " + h
                + " /ColorSpace " + (gray ? "/DeviceGray" : "/DeviceRGB")
                + " /BitsPerComponent 8 /Filter " + filter + " /Length " + stream.length + " >>",
                stream);
        byte[] content = place.getBytes(StandardCharsets.US_ASCII);
        object(out, offsets, "<< /Length " + content.length + " >>", content);
        int xref = out.size();
        StringBuilder table = new StringBuilder("xref\n0 " + (offsets.size() + 1)
                + "\n0000000000 65535 f \n");
        for (int offset : offsets) table.append(String.format("%010d 00000 n \n", offset));
        table.append("trailer\n<< /Size ").append(offsets.size() + 1)
                .append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        ascii(out, table.toString());
        Files.write(pdf, out.toByteArray());
        return true;
    }

    // A colour channel over white, at alpha a (0-255).
    private static int over(int c, int a) {
        return (c * a + 255 * (255 - a)) / 255;
    }

    // One numbered PDF object: its dictionary, and its stream when it has one.
    private static void object(java.io.ByteArrayOutputStream out, java.util.List<Integer> offsets,
                               String dict, byte[] stream) throws IOException {
        offsets.add(out.size());
        ascii(out, offsets.size() + " 0 obj\n" + dict + "\n");
        if (stream != null) {
            ascii(out, "stream\n");
            out.write(stream);
            ascii(out, "\nendstream\n");
        }
        ascii(out, "endobj\n");
    }

    // PDF structure is bytes, one per character: ISO-8859-1 keeps the binary-marker
    // comment's four high bytes as they are.
    private static void ascii(java.io.ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    // A file's size, or -1 when there is no file there to measure. Used to check that a
    // merged document really is bigger than each pass it was made from before the passes
    // are deleted, so a truncated merge cannot take its own originals with it.
    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException notThere) {
            return -1;
        }
    }

    private static String capitalised(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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

    // Renames a file to base, keeping its extension AND its suffix, and returns where it
    // went. stem is the name the document went under until now - what the suffix is
    // measured against (see companionSuffix).
    //
    // The suffix is kept because it is the only thing that tells one file of the set from
    // another: "3703093896-en.docx" and "3703093896-SA.docx" both renamed to the bare
    // name would be one name, one extension and two files, so the second would land as
    // "...-2.docx" through free() and the translation would be a number nobody can read.
    private static Path renameTo(Path file, String base, String stem) throws IOException {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = free(file.getParent(), base + suffixOf(stem, file),
                dot > 0 ? name.substring(dot) : "");
        Files.move(file, target);
        return target;
    }

    // ---- Dialogs -----------------------------------------------------------
    // The document, chosen in a file chooser with one filter and no "All files": this
    // automation files a scan, a text export of one, or plain text, and has nothing to
    // say about anything else. It opens where Args.chooserStart says - the folder this process was
    // started in, which from the right-click menu is the folder that was clicked. Null
    // when the chooser was cancelled.
    private static Path chooseDocument(Path startIn) {
        Path[] chosen = { null };
        onEdt(() -> {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(startIn.toFile());
            chooser.setDialogTitle("Choose the PDF, image, DOCX, RTF or TXT to inventory");
            chooser.setAcceptAllFileFilterUsed(false);
            java.util.List<String> kinds = new java.util.ArrayList<>(
                    java.util.List.of(EXT_PDF, EXT_DOCX, EXT_RTF, EXT_TXT));
            kinds.addAll(EXT_IMAGES);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Documents (*.pdf, *.docx, *.rtf, *.txt, *.png, *.jpg, *.jpeg, *.gif, "
                            + "*.webp)", kinds.toArray(new String[0])));
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                chosen[0] = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
            }
        });
        return chosen[0];
    }

    // The offer a document with a copy further down the chain beside it gets: start from
    // that copy, and skip the passes that made it.
    //
    // Offered rather than taken, because which copy is the good one is not something this
    // automation knows - a .txt from a run that went wrong is worse than the scan it came
    // from. But the question is worth asking out loud: every pass skipped is a send not
    // paid for, and pass one is the dear one, pages of a document read by a model. All the
    // files are renamed at the end whichever way this goes.
    private static boolean askStartFrom(Path document, Path later) {
        String what = extension(later).toUpperCase(java.util.Locale.ROOT);
        String an = what.equals("RTF") ? "an " : "a ";
        int skipped = passOf(later) - passOf(document);
        String skips = later.getFileName() + " is "
                + (passOf(later) == PASS_NAME ? "the text the file name is made from"
                                              : "what the plain-text pass reads")
                + ", so " + (skipped == 1 ? "one pass" : skipped + " passes")
                + " - and " + (skipped == 1 ? "its send" : "their sends") + " - are skipped.";
        return ask("There is " + an + what + " beside this document, under the same name:\n\n"
                + "    " + document.getFileName() + "\n    " + later.getFileName()
                + "\n\nStart from the " + what + " instead? " + skips
                + "\n\nAll the files are renamed either way.",
                "Start from the " + what + "?");
    }

    // The name every file of the document is to get: the suggestion, with whatever the
    // operator types over it, or null for "leave them alone".
    //
    // EDITABLE, BECAUSE A SUGGESTION IS A SUGGESTION. The model read one document and
    // guessed a counterparty, a date and a subject out of it; the person at the keyboard
    // knows what the page does not say - that this Stadtsparkasse is the one everything
    // else in the folder is filed under as SSKM, that the letter is about the flat and
    // not about the account it names, that the number worth finding it by is the one
    // stamped on it. Before this the answer to a name that was nearly right was No, and
    // then four files renamed by hand in Explorer with their suffixes copied across by
    // eye, which is precisely the work this flow exists to do.
    //
    // ONE DIALOG, NOT TWO. The field opens with the suggestion already in it, so Enter
    // alone is still the whole of "yes, that name" - which is what the Yes button was -
    // and changing one word costs one word of typing. Cancel, and the window's X, leave
    // every file exactly as it is, as No did.
    //
    // What comes back goes through nameSafe like the model's own answer, so a trailing
    // space, a pasted slash or "Rechnung Mai 2026" with the spaces still in it cannot
    // reach the file system. A name that is nothing but punctuation is asked for again
    // rather than quietly turned into something else: it is the one case where what was
    // meant cannot be guessed at all, and renaming four files on a guess is not the
    // place to start.
    private static String askRename(String base, String howMany, java.util.List<Path> files,
                                    Path dir, String suffixPart) {
        if (!asking) {
            // --auto: nobody is there to read a name, let alone to type one.
            System.out.println("Rename " + howMany + " to " + base + "? yes (auto)");
            return base;
        }
        while (true) {
            String message = "The files of this document, in " + dir + ":\n\n"
                    + listed(files)
                    + "\nRename " + howMany + " to this name? Edit it if it is not right.\n"
                    + "Cancel leaves every one of them exactly as it is." + suffixPart;
            String[] typed = { null };
            onEdt(() -> {
                Object answer = javax.swing.JOptionPane.showInputDialog(
                        JRock.automationWindow(), message, "Rename the files?",
                        javax.swing.JOptionPane.QUESTION_MESSAGE, null, null, base);
                typed[0] = (answer == null) ? null : answer.toString();
            });
            if (typed[0] == null) return null;
            String name = nameSafe(typed[0]);
            if (!name.isEmpty()) return name;
            tell("A file name here is letters, digits, dots, hyphens and underscores, and "
                    + "there is none of that in\n\n    " + typed[0].trim()
                    + "\n\nNothing has been renamed. The name is asked for again.",
                    "That is not a file name", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Whether the two passes a duplex document was merged from may go. Every page of both
    // is in the document that has just been named, so what is being deleted is the
    // duplicate and not the letter - but it is still the only other copy on this disk,
    // which is why the question says what it says.
    private static boolean askDeleteScans(Path front, Path back, Path filed) {
        if (!asking) {
            System.out.println("Delete the two scans it was merged from? yes (auto: "
                    + front.getFileName() + " and " + back.getFileName()
                    + " - every page of both is in " + filed.getFileName() + ")");
            return true;
        }
        return ask("This document was merged from two scans:\n\n    " + front.getFileName()
                + "\n    " + back.getFileName() + "\n\nEvery page of both is in\n\n    "
                + filed.getFileName() + "\n\nDelete the two scans? They do NOT go to the "
                + "Recycle Bin.\n\nNo keeps them - and they will go on looking like a "
                + "letter nobody has filed.", "Delete the two scans?");
    }

    // Every dialog below belongs to JRock's own window, so none of them can be lost
    // behind it. automationWindow() is null until that window exists, which JOptionPane
    // reads as "centre it on the screen" - right for the dialogs that come that early,
    // the offer to read an export instead among them.
    // Whether anything may be asked at all. Set from the flags before the first
    // question, and false only with --auto: a dialog nobody can see is a flow that has
    // stopped without saying so.
    private static volatile boolean asking = true;

    private static boolean ask(String message, String title) {
        if (!asking) {
            // Said in the log instead, so the answer is on the record either way.
            System.out.println("Auto: yes - " + title + " ("
                    + message.replaceAll("\\s+", " ").trim() + ")");
            return true;
        }
        boolean[] yes = { false };
        onEdt(() -> yes[0] = javax.swing.JOptionPane.showConfirmDialog(
                JRock.automationWindow(), message, title,
                javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.QUESTION_MESSAGE)
                    == javax.swing.JOptionPane.YES_OPTION);
        return yes[0];
    }

    private static void tell(String message, String title, int kind) {
        if (!asking) {
            System.out.println(title + ": " + message.replaceAll("\\s+", " ").trim());
            return;
        }
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
