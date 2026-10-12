// A JRock agent (it loops, and is started with JRock): the PAPER post. What the scanner
// has put in a folder, what arrived when, and - once a letter has been filed - what it
// says.
//
// LETTERS, and that is the name. The Thunderbird agent reads e-mail; this one reads the
// post that comes through the door, which is a different thing with a different rhythm
// and a different pile. "Mail" would have meant both and so neither.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java -cp jrock.jar JRockPapers.java [--folder <where the scanner writes>]
//     java -cp jrock.jar JRockPapers.java --print recent
//
// A NEW SHAPE: AN AGENT WITH NO JROCK OF ITS OWN, THAT DRIVES A FLOW.
//
// Every other agent here starts a JRock and holds it for as long as it runs. This one
// starts none: looking at a folder needs no model, and a window that did nothing but sit
// there would be a window to close. What it does instead, when a letter has to be filed,
// is start the DOC INVENTORY FLOW - samples/flows/JRockDocInventory.java, with --auto -
// as a process of its own. That flow starts a JRock, merges the two passes if there are
// two, reads the letter, names it, renames every file of it, prints one line saying what
// it did, and exits. Its window closes with it.
//
// So the long-running thing has no model and no key, and the thing with the model lives
// for one letter. An agent for what is always true, a flow for what happens once - which
// is the distinction the two words have carried all along, now in one chain.
//
// What it answers, over TCP on 127.0.0.1:PORT - one line in, read until closed:
//
//     #recent [minutes]   what the scanner has put there lately (10 minutes by default)
//     #scans [n]          the last n scans, newest first, with their times
//     #file <name>        the TEXT of a filed letter, by any part of its name
//     #filed [n]          the letters that have been filed and named, newest first
//     #inventory          file the newest scan, by itself
//     #inventory <name>   file that one, by itself
//     #inventory duplex   file the newest TWO as one two-sided letter, merged
//     #inventory <a> <b>  file those two as one two-sided letter, merged
//     #folder             where it is looking, and whether that folder is there
//
// WHETHER TWO SCANS ARE ONE TWO-SIDED LETTER IS NOT SOMETHING THIS CAN KNOW, and the
// first version of this agent pretended otherwise. It grouped two scans with adjacent
// names a few minutes apart into one document, which sounds reasonable until you look at
// a real scanner folder:
//
//     scan0164.pdf  21:55:24   2 pages
//     scan0165.pdf  21:58:38   2 pages
//     scan0166.pdf  21:58:59   2 pages
//     scan0167.pdf  21:59:22   2 pages
//     scan0168.pdf  21:59:42   2 pages
//
// Five letters put through a flatbed one after the other look exactly like two-and-a-half
// two-sided letters. Twenty seconds apart is both the time it takes to turn a stack over
// AND the time it takes to put the next letter in; the page counts are equal either way;
// and the names are always adjacent, because a scanner counts. There is no signal here,
// and a wrong guess merges two unrelated letters into one PDF and files them under one
// name - which is the worst thing this agent could do.
//
// So it guesses nothing at all. Every scan is reported as itself, with its time and its
// size, and WHICH FILES MAKE UP ONE LETTER IS SAID BY THE OPERATOR - one scan by itself,
// or two named scans merged as the two passes of one. They know whether they turned the
// stack over; this does not, and does not pretend to.
//
// ONLY .txt FOR CONTENT. Asked what a letter says, this agent reads the .txt file beside
// it and nothing else. It does not convert anything: turning a PDF into text is the doc
// inventory flow's job, with Ghostscript and xpdf and a model, and doing it a second way
// here would be a second answer to the same question. A letter that has not been filed
// yet has no .txt, and the answer says so.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class JRockPapers {

    // The next port after the browser's.
    private static final int PORT = 47475;

    // Where this agent keeps the one thing it is told: the folder the scanner writes to.
    private static final String SETTINGS = "jrock-papers.txt";

    // The flow it drives, and the flag that stops it asking.
    private static final String FLOW = "JRockDocInventory.java";
    private static final String AUTO_FLAG = "--auto";
    private static final String AUTO_RESULT = "AUTO-RESULT";

    // What a scanner writes, and what a filed letter is read from.
    private static final String SCAN_EXT = ".pdf";
    private static final String TEXT_EXT = ".txt";

    // THE OTHER FOLDER, and the symmetry is the point. The scanner's folder is where
    // paper ARRIVES; this is where documents arrive by every other route - a DOCX this
    // agent was asked to keep, something downloaded, something copied off a phone - and
    // where this agent writes. One folder in, one folder out, both of them read.
    //
    // Default: Documents\Incoming. Not inside this agent's own folder, and that is
    // deliberate: a folder somebody synchronises has to be somewhere they would look for
    // it, and the inside of an agent's working directory is not that place.
    private static final String INCOMING_DEFAULT = "Incoming";

    // The kinds of file in there this agent can read out. Anything JRock can include as
    // text or as a document, which is what makes "always filed by design" true of that
    // folder: a file in it can be ASKED about whatever it arrived as.
    private static final String[] READABLE = {
        ".txt", ".md", ".docx", ".rtf", ".pdf", ".xlsx", ".png", ".jpg", ".jpeg", ".gif",
    };

    // How long its own JRock gets to come up, and how long one refining pass may take.
    private static final long READY_TIMEOUT_MS = 120_000;
    private static final long REPLY_TIMEOUT_MS = 10 * 60_000;

    // The prompt that turns a document into clean Markdown, beside this file.
    private static final String PROMPT_TO_MARKDOWN = "jrock-prompt-papers-to-markdown.txt";

    // Nothing here measures how alike two scans are, on purpose: see the note at the top
    // of this file. Which files make up one letter is said by the operator, not inferred.

    // How far back #recent looks when it is not told, and how many scans #scans lists.
    private static final int RECENT_MINUTES = 10;
    private static final int LIST_MAX = 20;

    // How much of a letter goes back in one answer. A letter is a page or two; a hundred
    // thousand characters is a scanner that was fed a book, and sending it through a
    // model is a bill nobody meant to run up.
    private static final int TEXT_MAX = 100_000;

    // How long the flow gets: a PDF through Ghostscript, xpdf and three model passes.
    private static final long FLOW_TIMEOUT_MS = 20 * 60_000;

    // And how long it gets to EXIT once its output has ended - a JVM closing a window.
    // Short on purpose: by then the letter is filed and the answer is in hand, and the
    // only thing left to do with a process that will not go is to end it.
    private static final long FLOW_EXIT_MS = 20_000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);

    private static volatile Path folder;
    private static volatile Path incoming;
    private static volatile Process flowRunning;
    private static volatile boolean started;   // its JRock, started once and kept

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField folderField;
    private static javax.swing.JTextArea result;
    private static javax.swing.JTextArea status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String printing = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--folder") && i + 1 < args.length) {
                folder = Paths.get(args[++i]);
            } else if (args[i].equals("--incoming") && i + 1 < args.length) {
                incoming = Paths.get(args[++i]);
            } else if (args[i].equals("--print") && i + 1 < args.length) {
                printing = args[++i];
            }
        }
        if (folder == null) readSettings();

        // --print: one answer on standard output and nothing else. No window, no socket,
        // and nothing started - the way to see what an agent would be told.
        if (printing != null) {
            try {
                System.out.println(answer(printing.startsWith("#") ? printing
                        : "#" + printing));
            } catch (Stop stop) {
                System.out.println("Stopped: " + stop.getMessage());
            }
            return;
        }

        onEdt(JRockPapers::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Papers agent is ready. " + said);
    }

    // ---- Answering -------------------------------------------------------------
    private static String answer(String request) {
        String line = request == null ? "" : request.trim();
        if (line.isEmpty()) line = "#recent";
        if (!line.startsWith("#")) line = "#" + line;
        String[] parts = line.split("\\s+", 2);
        String what = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (what) {
            case "#folder":
                return where();
            case "#recent":
                return recent(minutes(rest, RECENT_MINUTES));
            case "#scans":
                return scans(count(rest, LIST_MAX));
            case "#filed":
                return filed(count(rest, LIST_MAX));
            case "#file":
                if (rest.isEmpty()) {
                    throw new Stop("#file wants part of a letter's name, as #file Shipping.");
                }
                return text(rest);
            case "#inventory":
                return fileIt(rest);
            case "#incoming":
                return incomingList(count(rest, LIST_MAX));
            case "#save":
                return save(rest);
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#recent [minutes], #scans [n], #filed [n], #incoming [n], "
                        + "#save <markdown>, #file <name>, "
                        + "#inventory [name] or #folder.");
        }
    }

    private static int minutes(String text, int whenMissing) {
        if (text.isBlank()) return whenMissing;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,5})")
                .matcher(text);
        return m.find() ? Math.max(1, Math.min(10_000, Integer.parseInt(m.group(1))))
                : whenMissing;
    }

    private static int count(String text, int whenMissing) {
        return minutes(text, whenMissing);
    }

    private static Path scanFolder() {
        Path at = folder;
        if (at == null) {
            throw new Stop("no scan folder is set. Set it in the agent's window - it is "
                    + "the folder the scanner writes its PDFs into.");
        }
        if (!Files.isDirectory(at)) {
            throw new Stop(at + " is not a folder. The scanner's folder is what this "
                    + "agent watches, and that is not one.");
        }
        return at;
    }

    private static String where() {
        Path at = folder;
        if (at == null) return "No scan folder is set yet.";
        if (!Files.isDirectory(at)) return "Set to " + at + ", which is not there.";
        List<Scan> scans = scansIn(at);
        return "Watching " + at + ": " + scans.size()
                + (scans.size() == 1 ? " PDF" : " PDFs") + ", "
                + filedIn(at).size() + " of them filed and named.";
    }

    // ---- What is in the folder -------------------------------------------------
    // One file the scanner wrote.
    private static final class Scan {
        final Path file;
        final Instant at;
        final long size;

        Scan(Path file, Instant at, long size) {
            this.file = file;
            this.at = at;
            this.size = size;
        }

        String name() {
            return file.getFileName().toString();
        }

        String megabytes() {
            return String.format(Locale.ENGLISH, "%.1f MB", size / 1_048_576.0);
        }
    }

    // Every PDF in the folder, newest first. Not recursive: a scanner writes where it is
    // told to write, and a folder tree would turn "what just arrived" into a search.
    private static List<Scan> scansIn(Path at) {
        return filesIn(at, SCAN_EXT);
    }

    // The same for any one extension, or for ALL files when ext is null - which is what
    // the incoming folder wants: a document that arrived there is a document, whatever
    // it arrived as.
    private static List<Scan> filesIn(Path at, String ext) {
        List<Scan> scans = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(at)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (ext != null && !name.endsWith(ext)) continue;
                try {
                    scans.add(new Scan(file, Files.getLastModifiedTime(file).toInstant(),
                            Files.size(file)));
                } catch (IOException ex) {
                    // A file being written this second: it will be there next time.
                }
            }
        } catch (IOException ex) {
            throw new Stop("could not read " + at + ": " + ex.getMessage());
        }
        scans.sort(Comparator.comparing((Scan s) -> s.at).reversed());
        return scans;
    }

    // The ones that have been filed: a PDF with a .txt of the same name beside it, which
    // is what the inventory flow leaves behind.
    private static List<Scan> filedIn(Path at) {
        List<Scan> filed = new ArrayList<>();
        for (Scan scan : scansIn(at)) {
            if (Files.isRegularFile(textOf(scan.file))) filed.add(scan);
        }
        return filed;
    }

    private static Path textOf(Path pdf) {
        String name = pdf.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return pdf.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + TEXT_EXT);
    }

    // What arrived lately, counted as DOCUMENTS rather than files.
    private static String recent(int minutes) {
        Path at = scanFolder();
        Instant since = Instant.now().minus(Duration.ofMinutes(minutes));
        List<Scan> fresh = new ArrayList<>();
        for (Scan scan : scansIn(at)) {
            if (scan.at.isAfter(since)) fresh.add(scan);
        }
        ZoneId zone = ZoneId.systemDefault();
        if (fresh.isEmpty()) {
            return "Nothing has been scanned in the last " + minutes
                    + (minutes == 1 ? " minute." : " minutes.") + "\n" + footer(at);
        }
        StringBuilder out = new StringBuilder("In the last " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ": " + fresh.size()
                + (fresh.size() == 1 ? " scan" : " scans") + ".");
        for (Scan scan : fresh) {       // newest first
            out.append("\n- ").append(scan.name()).append("  ")
                    .append(ZonedDateTime.ofInstant(scan.at, zone).format(CLOCK))
                    .append("  ").append(scan.megabytes())
                    .append(Files.isRegularFile(textOf(scan.file)) ? "  (filed already)" : "");
        }
        // NO GUESS about which of these belong together. The folder is the only thing
        // this knows and the only thing it reports; which files make up one letter is
        // the operator's to say, in words, naming them.
        out.append("\nSay which one to file, or which TWO to file as one two-sided "
                + "letter, and it is done that way.");
        return out.append('\n').append(footer(at)).toString();
    }

    // Two names a scanner wrote one after the other: the same but for a trailing number,
    // one apart. "scan0166.pdf" and "scan0167.pdf", or "letter.pdf" and "letter-2.pdf".
    private static boolean adjacent(String one, String two) {
        java.util.regex.Pattern tail = java.util.regex.Pattern.compile(
                "^(.*?)(\\d+)(\\.[A-Za-z0-9]+)$");
        java.util.regex.Matcher a = tail.matcher(one);
        java.util.regex.Matcher b = tail.matcher(two);
        if (!a.matches() || !b.matches()) {
            // No numbers to compare: the same stem with "-2" on one of them still counts.
            String stemA = one.replaceAll("(?i)[-_ ]?2?\\.pdf$", "");
            String stemB = two.replaceAll("(?i)[-_ ]?2?\\.pdf$", "");
            return stemA.equalsIgnoreCase(stemB);
        }
        if (!a.group(1).equalsIgnoreCase(b.group(1))) return false;
        try {
            return Math.abs(Long.parseLong(a.group(2)) - Long.parseLong(b.group(2))) == 1;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static String scans(int many) {
        Path at = scanFolder();
        List<Scan> scans = scansIn(at);
        if (scans.isEmpty()) return "No PDFs in " + at + ".\n" + footer(at);
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder out = new StringBuilder("Scans in " + at.getFileName() + ", newest "
                + "first (" + Math.min(many, scans.size()) + " of " + scans.size() + "):");
        int shown = 0;
        for (Scan scan : scans) {
            if (shown++ >= many) break;
            out.append("\n- ").append(scan.name()).append("  ")
                    .append(ZonedDateTime.ofInstant(scan.at, zone).format(STAMP))
                    .append(Files.isRegularFile(textOf(scan.file)) ? "  (filed)" : "");
        }
        return out.append('\n').append(footer(at)).toString();
    }

    private static String filed(int many) {
        Path at = scanFolder();
        List<Scan> filed = filedIn(at);
        if (filed.isEmpty()) {
            return "Nothing in " + at + " has been filed yet - no letter there has a .txt "
                    + "beside it.\n" + footer(at);
        }
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder out = new StringBuilder("Letters filed in " + at.getFileName()
                + ", newest first (" + Math.min(many, filed.size()) + " of " + filed.size()
                + "):");
        int shown = 0;
        for (Scan scan : filed) {
            if (shown++ >= many) break;
            out.append("\n- ").append(scan.name()).append("  ")
                    .append(ZonedDateTime.ofInstant(scan.at, zone).format(STAMP));
        }
        out.append("\nAsk for any of them by any part of its name to be told what it says.");
        return out.append('\n').append(footer(at)).toString();
    }

    // ---- What a letter says ----------------------------------------------------
    // The .txt beside a filed letter, by any part of its name. Only the .txt: converting
    // a PDF is the inventory flow's job, and a second way of doing it here would be a
    // second answer to the same question.
    private static String text(String asked) {
        Path at = scanFolder();
        // BOTH FOLDERS, and a different rule in each. In the scanner's folder a document
        // is readable once it has been FILED, which is what the .txt beside it means: the
        // flow put it there, and reading a scan that has not been through it would be
        // reading a picture. In the incoming folder everything readable is readable now -
        // nothing arrived there as paper, so there is nothing to convert first. Which is
        // what makes that folder "always filed by design".
        List<Path> texts = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(at)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(TEXT_EXT)) {
                    texts.add(file);
                }
            }
        } catch (IOException ex) {
            throw new Stop("could not read " + at + ": " + ex.getMessage());
        }
        Path other = incomingFolder();
        if (Files.isDirectory(other)) {
            for (Scan file : filesIn(other, null)) {
                if (readable(file.file)) texts.add(file.file);
            }
        }
        List<Path> matches = new ArrayList<>();
        for (Path file : texts) {
            if (matches(file.getFileName().toString(), asked)) matches.add(file);
        }
        if (matches.isEmpty()) {
            StringBuilder out = new StringBuilder("Nothing matches \"" + asked
                    + "\" in either folder. What there is:");
            int shown = 0;
            for (Path file : texts) {
                if (shown++ >= 20) break;
                out.append("\n- ").append(file.getFileName());
            }
            if (texts.isEmpty()) out.append("\n- none yet.");
            return out.toString();
        }
        if (matches.size() > 1) {
            matches.sort(Comparator.comparing(f -> f.getFileName().toString().length()));
            Path best = matches.get(0);
            // One clearly shortest name is the one that was meant; several of a length
            // is a question, not an answer.
            if (matches.get(1).getFileName().toString().length()
                    == best.getFileName().toString().length()) {
                StringBuilder out = new StringBuilder(matches.size() + " documents "
                        + "match \"" + asked + "\". Which one:");
                for (Path file : matches) out.append("\n- ").append(file.getFileName());
                return out.toString();
            }
        }
        Path file = matches.get(0);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        // Not text? Then it goes through this agent's own JRock first: included as the
        // kind it is and asked for as Markdown, which is the same bargain the Thunderbird
        // agent strikes with an attachment. Nothing raw goes back to whoever asked - a
        // DOCX is a zip, a PDF is a picture, and neither is something to read aloud.
        if (!name.endsWith(TEXT_EXT) && !name.endsWith(".md")) {
            return "CONTENT " + kindOfPlace(file) + " " + file.getFileName() + "\n"
                    + refined(file);
        }
        String body;
        try {
            body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file.getFileName() + ": " + ex.getMessage());
        }
        boolean cut = body.length() > TEXT_MAX;
        if (cut) body = body.substring(0, TEXT_MAX);
        // CONTENT, then the kind, then a file name: the marker the asking agent splits
        // on. Everything after the first line is the letter itself, to be written to a
        // file and included rather than pasted into a prompt - and the kind is what tells
        // it whether it is holding a letter or an e-mail.
        // The KIND comes from the folder it was in, because that is what it means: a
        // thing off the scanner is a letter somebody posted, and a thing in the incoming
        // folder is a document that arrived some other way. Whoever asked uses the kind
        // to decide what to say about it.
        return "CONTENT " + kindOfPlace(file) + " " + file.getFileName() + "\n" + body
                + (cut ? "\n\n(Cut off after " + TEXT_MAX + " characters.)" : "");
    }

    // Any part of the name, in any case, with spaces and punctuation ignored on both
    // sides: a name said out loud arrives as "Shipping Confirmation" and the file
    // is called "2026-10-02-DHL-ShippingConfirmation-1234.txt".
    private static boolean matches(String name, String asked) {
        String plain = plain(name);
        String want = plain(asked);
        if (want.isEmpty()) return false;
        if (plain.contains(want)) return true;
        // Every word of the question somewhere in the name, which is how a spoken name
        // arrives with its words in a different order.
        Set<String> words = new LinkedHashSet<>();
        for (String word : asked.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() > 2) words.add(word);
        }
        if (words.isEmpty()) return false;
        for (String word : words) {
            if (!plain.contains(word)) return false;
        }
        return true;
    }

    private static String plain(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    // ---- Filing one, through the flow ------------------------------------------
    // The doc inventory flow, as a process of its own, with --auto.
    //
    // A process rather than a call: the flow starts a JRock of its own and ends it, which
    // is not something to do inside a long-running agent - and its one line of result
    // comes back on standard output, which is why it is started with java rather than
    // javaw. Its window appears while it works, which is the point: the operator can see
    // the letter being read.
    private static String fileIt(String asked) {
        Path at = scanFolder();
        Process already = flowRunning;
        if (already != null && already.isAlive()) {
            throw new Stop("a letter is being filed already - one at a time, because they "
                    + "share a folder and a JRock.");
        }
        List<Scan> scans = scansIn(at);
        if (scans.isEmpty()) throw new Stop("there are no PDFs in " + at + ".");

        // WHAT TO FILE, said rather than guessed:
        //
        //   (nothing)        the newest scan, by itself
        //   duplex           the newest two, as one two-sided letter
        //   <name>           that one, by itself
        //   <name> <name>    those two, as one two-sided letter
        //
        // One by itself is the default because it is the one that cannot go wrong: a
        // two-sided letter filed as two single-sided ones is two files to tidy up, while
        // two letters merged into one are a document that is half somebody else's.
        List<Scan> document = new ArrayList<>();
        String[] named = asked.isBlank() ? new String[0] : asked.trim().split("\\s+");
        boolean duplex = named.length > 0 && named[0].equalsIgnoreCase("duplex");
        if (duplex) {
            named = java.util.Arrays.copyOfRange(named, 1, named.length);
        }
        if (named.length == 0) {
            if (scans.size() < (duplex ? 2 : 1)) {
                throw new Stop("there are not two scans in " + at + " to merge.");
            }
            document.add(scans.get(0));
            if (duplex) document.add(scans.get(1));
        } else {
            for (String part : named) {
                Scan found = null;
                for (Scan scan : scans) {
                    if (matches(scan.name(), part)) {
                        found = scan;
                        break;
                    }
                }
                if (found == null) {
                    throw new Stop("no scan in " + at + " matches \"" + part + "\".");
                }
                if (!document.contains(found)) document.add(found);
            }
        }
        if (document.size() > 2) {
            throw new Stop("one letter at a time: one scan, or two scans of one two-sided "
                    + "letter. " + document.size() + " were named.");
        }
        if (document.size() == 2 && !adjacent(document.get(0).name(),
                document.get(1).name())) {
            // Said rather than refused: the operator may know something the names do not.
            later(() -> say("Those two scans are not next to each other in name - merging "
                    + "them anyway, as asked."));
        }
        for (Scan scan : document) {
            if (Files.isRegularFile(textOf(scan.file))) {
                throw new Stop(scan.name() + " has been filed already - there is a .txt "
                        + "beside it. Ask what it says instead.");
            }
        }

        Path jar = jar();
        Path flow = flow();
        List<String> command = new ArrayList<>(List.of(java(), "-cp", jar.toString(),
                flow.toString(), AUTO_FLAG));
        // Oldest first: the earlier name is the front pass, which the flow relies on.
        for (int i = document.size() - 1; i >= 0; i--) {
            command.add(document.get(i).file.toString());
        }
        later(() -> say("Filing " + document.size()
                + (document.size() == 1 ? " scan" : " scans (two passes)")
                + " through the doc inventory flow - its own JRock window will open."));
        String resultLine = runFlow(command, at);
        String name = valueIn(resultLine, "name=");
        String scanned = valueIn(resultLine, "scanned=");
        String files = valueIn(resultLine, "files=");
        return "Filed: " + name + "\nScanned: " + scanned + "\nFiles renamed: " + files
                + "\nAsk what it says by any part of that name.";
    }

    // THE FLOW, RUN AND READ: the command, started in dir, with its one result line
    // returned. Throws Stop with the reason when there is no result line to return.
    //
    // Shared by the two things here that end in a named document - a letter off the
    // scanner, and a document this agent was asked to keep - because naming is the
    // same job either way and the flow is the only thing that does it.
    private static String runFlow(List<String> command, Path dir) {
        StringBuilder out = new StringBuilder();
        String resultLine = null;
        try {
            Process flowProcess = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .start();
            flowRunning = flowProcess;
            flowProcess.getOutputStream().close();

            // THE CLOCK RUNS BESIDE THE READING, not after it.
            //
            // Reading the flow's output stops at the end of the stream, and a stream ends
            // when the process does - so a flow that will not exit is not slow here, it
            // is permanent: this thread never reaches the line that was supposed to time
            // it out, and every later request to this agent waits behind it. That is
            // precisely what a flow with a dialog up, or one leaving its window open, used
            // to do - the letter was filed, the result line was read and printed, and the
            // agent was wedged from then on.
            //
            // So the limit is enforced by a thread of its own that kills the process,
            // which ends the stream, which ends the read. Killing a flow is safe at any
            // point: it renames files one at a time and the model is asked before anything
            // is renamed, so what is lost is a conversion, never a half-renamed document.
            boolean[] overdue = { false };
            Thread clock = new Thread(() -> {
                try {
                    if (!flowProcess.waitFor(FLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        overdue[0] = true;
                        flowProcess.destroyForcibly();
                    }
                } catch (InterruptedException readingFinished) {
                    Thread.currentThread().interrupt();
                }
            }, "letters-flow-clock");
            clock.setDaemon(true);
            clock.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(flowProcess.getInputStream(),
                            StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    out.append(line).append('\n');
                    System.out.println("flow: " + line);
                    if (line.startsWith(AUTO_RESULT)) resultLine = line;
                    String shown = line;
                    if (line.startsWith("Pass ") || line.startsWith("Renamed")
                            || line.startsWith("Finished") || line.startsWith("Stopped")) {
                        later(() -> say("Flow: " + shown));
                    }
                }
            }
            clock.interrupt();
            if (overdue[0]) {
                throw new Stop("the inventory flow took longer than "
                        + FLOW_TIMEOUT_MS / 60_000 + " minutes and was stopped.");
            }
            // The stream has ended, so the process is on its way out; this is the last
            // moment it could hold anything. A flow that printed its answer and then hung
            // on to the JVM is still answered for - the letter is filed, and saying so
            // matters more than the process - but it does not get to stay.
            if (!flowProcess.waitFor(FLOW_EXIT_MS, TimeUnit.MILLISECONDS)) {
                flowProcess.destroyForcibly();
                System.out.println("flow: it would not exit after its output ended - stopped.");
            }
        } catch (IOException ex) {
            throw new Stop("the inventory flow would not start: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Stop("interrupted while the letter was being filed.");
        } finally {
            flowRunning = null;
        }

        if (resultLine == null) {
            String why = lastLine(out.toString());
            throw new Stop("the inventory flow did nothing"
                    + (why.isEmpty() ? "." : ": " + why));
        }
        return resultLine;
    }

    private static String valueIn(String line, String key) {
        int at = line.indexOf(key);
        if (at < 0) return "";
        int from = at + key.length();
        int to = line.indexOf(" ", from);
        String value = to < 0 ? line.substring(from) : line.substring(from, to);
        // "scanned=2026-10-11 22:31:02" has a space in the middle of its value.
        if (key.equals("scanned=") && to > 0 && to + 1 < line.length()) {
            int second = line.indexOf(" ", to + 1);
            value = second < 0 ? line.substring(from) : line.substring(from, second);
        }
        return value.trim();
    }

    private static String lastLine(String text) {
        String[] lines = text.trim().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].trim();
    }

    // The jar this agent is running with, off its own class path - which is how it was
    // started, and so the one jar that certainly works.
    private static Path jar() {
        for (String part : System.getProperty("java.class.path", "")
                .split(java.io.File.pathSeparator)) {
            if (part.toLowerCase(Locale.ROOT).endsWith(".jar")
                    && Files.isRegularFile(Paths.get(part))) {
                return Paths.get(part).toAbsolutePath().normalize();
            }
        }
        throw new Stop("this agent is not running with a jrock.jar on its class path, and "
                + "the inventory flow needs one.");
    }

    // The flow's own file: in Flows/ beside the jar, which is where the installer puts
    // it, or beside this agent for anybody who keeps them together.
    private static Path flow() {
        Path jar = jar();
        Path home = jar.getParent();
        List<Path> tries = new ArrayList<>();
        if (home != null) {
            tries.add(home.resolve("Flows").resolve(FLOW));
            tries.add(home.resolve(FLOW));
        }
        tries.add(ownDirectory().resolve(FLOW));
        tries.add(ownDirectory().resolveSibling("flows").resolve(FLOW));
        for (Path at : tries) {
            if (Files.isRegularFile(at)) return at;
        }
        throw new Stop("could not find " + FLOW + " - looked in "
                + (home == null ? "" : home.resolve("Flows") + ", ") + ownDirectory()
                + ". That flow is what files a letter.");
    }

    private static String java() {
        String home = System.getProperty("java.home");
        for (String name : new String[] { "java.exe", "java" }) {
            Path at = Paths.get(home, "bin", name);
            if (Files.isRegularFile(at)) return at.toString();
        }
        return "java";
    }

    private static String footer(Path at) {
        return "Read from " + at + " - the folder the scanner writes to, read-only except "
                + "when a letter is filed.";
    }

    // ---- Listening -------------------------------------------------------------
    private static String listen() {
        try {
            server = new ServerSocket(PORT, 8, InetAddress.getLoopbackAddress());
        } catch (IOException ex) {
            return "Not listening on " + PORT + ": " + ex.getMessage()
                    + " - the window still works.";
        }
        Thread t = daemon(() -> {
            while (true) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(30_000);
                    serve(client);
                } catch (IOException ex) {
                    if (server == null || server.isClosed()) return;
                }
            }
        }, "jrock-papers-listener");
        t.start();
        return "Listening on 127.0.0.1:" + PORT + " for #recent, #scans, #file and "
                + "#inventory.";
    }

    private static void serve(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) >= 0 && c != '\n'; ) {
            if (c != '\r') line.append((char) c);
            if (line.length() > 2000) break;
        }
        String request = line.toString().trim();
        later(() -> say("Asked: " + request));
        String out;
        try {
            out = answer(request);
        } catch (Stop stop) {
            out = "Stopped: " + stop.getMessage();
        } catch (RuntimeException ex) {
            out = "Stopped: " + ex;
        }
        OutputStream os = client.getOutputStream();
        os.write(out.getBytes(StandardCharsets.UTF_8));
        os.flush();
        String shown = out;
        later(() -> show(shown));
    }

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Papers");
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                ServerSocket s = server;
                server = null;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        java.awt.Font small = base.deriveFont(java.awt.Font.PLAIN, 12f);
        java.awt.Font fixed = new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12);

        folderField = new javax.swing.JTextField(folder == null ? "" : folder.toString(), 40);
        folderField.setFont(small);
        folderField.setToolTipText("The folder the scanner writes its PDFs into");
        javax.swing.JButton browse = new javax.swing.JButton("Browse…");
        browse.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
            chooser.setDialogTitle("The folder the scanner writes to");
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (folder != null && Files.isDirectory(folder)) {
                chooser.setCurrentDirectory(folder.toFile());
            }
            if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) {
                folderField.setText(chooser.getSelectedFile().getAbsolutePath());
                useFolder();
            }
        });
        javax.swing.JButton use = new javax.swing.JButton("Use");
        use.addActionListener(e -> useFolder());
        folderField.addActionListener(e -> useFolder());

        javax.swing.JPanel folderButtons = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        folderButtons.setOpaque(false);
        folderButtons.add(browse);
        folderButtons.add(use);

        javax.swing.JPanel folderRow = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        folderRow.setOpaque(false);
        folderRow.add(new javax.swing.JLabel("Scanner folder:"), java.awt.BorderLayout.WEST);
        folderRow.add(folderField, java.awt.BorderLayout.CENTER);
        folderRow.add(folderButtons, java.awt.BorderLayout.EAST);

        javax.swing.JPanel asks = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 6, 0));
        asks.setOpaque(false);
        for (String[] ask : new String[][] { { "Just arrived", "#recent" },
                                             { "Last hour", "#recent 60" },
                                             { "All scans", "#scans" },
                                             { "Filed", "#filed" },
                                             { "File the newest", "#inventory" } }) {
            javax.swing.JButton b = new javax.swing.JButton(ask[0]);
            String request = ask[1];
            b.addActionListener(e -> ask(request));
            asks.add(b);
        }
        javax.swing.JTextField nameField = new javax.swing.JTextField(16);
        nameField.setToolTipText("Part of a filed letter's name");
        javax.swing.JButton read = new javax.swing.JButton("What does it say…");
        java.awt.event.ActionListener readIt = e -> {
            String typed = nameField.getText().trim();
            if (typed.isEmpty()) {
                say("Type part of a letter's name first.");
                return;
            }
            ask("#file " + typed);
        };
        read.addActionListener(readIt);
        nameField.addActionListener(readIt);
        asks.add(read);
        asks.add(nameField);

        result = new javax.swing.JTextArea(18, 76);
        result.setFont(fixed);
        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(result);
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder(
                "What an agent is told"));

        // A WRAPPING TEXT AREA AND NOT A LABEL, for two reasons that both ended up on
        // the operator's screen. A label wraps only if the HTML gives it a width in CSS
        // pixels, and Swing scales those by the display: "width:720px" asked for 939
        // device pixels on a 130% screen where the window had 818 to give, so the text
        // was cut off on the right. And a file name here is seventy characters with no
        // space in it, which Swing's HTML cannot break at all. A text area wraps to the
        // width it actually HAS, and breaks inside a word when a word is wider than that.
        status = new javax.swing.JTextArea(1, 1);
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 8, 2));
        status.setEditable(false);
        status.setOpaque(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 2, 0, 2));

        javax.swing.JPanel north = new javax.swing.JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                folderRow, asks }) {
            row.setAlignmentX(0f);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                    row.getPreferredSize().height));
            north.add(row);
        }
        // The status line is NOT capped at the height it happens to have while the window
        // is being built, which is the height of the single space it holds then. That cap
        // is the other half of what the operator photographed: a sentence needing three
        // lines was given the room for one, so the last line was cut off at the bottom as
        // well as the right. It grows as it needs to, and the list below gives up the room.
        status.setAlignmentX(0f);
        north.add(status);

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(16, 16, 12, 16));
        content.add(north, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        content.add(listening, java.awt.BorderLayout.SOUTH);
        window.setContentPane(content);

        window.pack();
        window.setMinimumSize(window.getSize());
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        // NOTHING THAT CAN BE CHANGED HAS THE FOCUS WHEN THIS OPENS. Swing gives it to
        // the first focusable component, which in these windows is a field, a checkbox or
        // a button - a setting one stray keypress away from being something else, with a
        // caret blinking in it as an invitation. The root pane takes it instead: it has no
        // key bindings of its own, so a keypress does nothing at all, and Tab still steps
        // into the window the usual way. Done in a later() so it happens AFTER Swing has
        // handed the focus out on activation.
        window.getRootPane().setFocusable(true);
        later(() -> window.getRootPane().requestFocusInWindow());
        if (folder == null) {
            say("Set the folder your scanner writes into, then press Use.");
        } else {
            ask("#recent 60");
        }
    }

    private static void useFolder() {
        String typed = folderField.getText().trim();
        if (typed.isEmpty()) {
            say("No folder set.");
            return;
        }
        folder = Paths.get(typed).toAbsolutePath().normalize();
        folderField.setText(folder.toString());
        writeSettings();
        ask("#recent 60");
    }

    private static void ask(String request) {
        say("Asked: " + request);
        daemon(() -> {
            String out;
            try {
                out = answer(request);
            } catch (Stop stop) {
                out = "Stopped: " + stop.getMessage();
            } catch (RuntimeException ex) {
                out = "Stopped: " + ex;
            }
            String shown = out;
            later(() -> show(shown));
        }, "jrock-papers-ask").start();
    }

    private static void show(String text) {
        result.setText(text);
        result.setCaretPosition(0);
    }

    private static void say(String text) {
        status.setText(text);
        // How tall a wrapped line is depends on how wide it is allowed to be, so the area
        // is pinned to the width it HAS before the layout is asked again - otherwise the
        // row keeps the height it had for the sentence before this one. Width 0 means the
        // window has not been laid out yet, and then there is nothing to re-measure.
        int wide = status.getWidth();
        if (wide > 0) status.setSize(wide, Short.MAX_VALUE);
        status.revalidate();
        System.out.println(text);
    }

    // ---- The other folder: what arrives by every route but paper ---------------
    // Where documents go and come from. Settable, remembered, and made when it is first
    // needed - an agent that writes into a folder somebody synchronises should not be the
    // reason the folder does not exist yet.
    private static Path incomingFolder() {
        Path at = incoming;
        if (at == null) {
            Path home = Paths.get(System.getProperty("user.home", "."));
            Path documents = Files.isDirectory(home.resolve("Documents"))
                    ? home.resolve("Documents") : home;
            at = documents.resolve(INCOMING_DEFAULT);
            incoming = at;
        }
        return at;
    }

    // What is in it, newest first - whatever it is. Not only this agent's own DOCX files:
    // anything dropped, downloaded or copied in there is listed, because from the
    // operator's side that folder is simply "the documents that came in".
    private static String incomingList(int many) {
        Path at = incomingFolder();
        if (!Files.isDirectory(at)) {
            return "Nothing has arrived yet: " + at + " does not exist. It is made the "
                    + "first time something is saved there.";
        }
        List<Scan> files = filesIn(at, null);
        if (files.isEmpty()) return "Nothing in " + at + " yet.";
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder out = new StringBuilder("In " + at.getFileName() + ", newest first ("
                + files.size() + (files.size() > many ? ", showing " + many : "") + "):");
        int shown = 0;
        for (Scan file : files) {
            if (shown++ >= many) break;
            out.append("\n- ").append(file.name()).append("  ")
                    .append(ZonedDateTime.ofInstant(file.at, zone).format(STAMP))
                    .append("  ").append(file.megabytes())
                    .append(readable(file.file) ? "" : "  (not a kind that can be read out)");
        }
        return out.append("\nAsk for any of them by name to have it read out.").toString();
    }

    private static boolean readable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : READABLE) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    // ---- Keeping a document ------------------------------------------------------
    // Markdown in, a named document in the incoming folder out.
    //
    // WHY THIS AGENT AND NOT PUSH-TO-TALK. Push-to-talk used to do this itself: select
    // the last answer in its own JRock and export it. That is this agent's business -
    // documents are what it is for - and doing it here has three consequences worth the
    // move. The document lands in the folder the operator synchronises rather than in a
    // subfolder of a voice agent. It is named by the doc inventory flow, like everything
    // else here, instead of carrying a timestamp. And the markdown arrives as a MESSAGE
    // between two agents, which is the same way every other document already travels.
    //
    // The route is JRock's prompt: set it, export it as a DOCX with its pictures placed,
    // which is exactly what a person does by hand with Ctrl+A and the export item. The
    // prompt is a sheet of paper here - nothing is sent and no model is asked.
    private static String save(String markdown) {
        String words = markdown == null ? "" : markdown.trim();
        if (words.isEmpty()) {
            throw new Stop("#save wants the document itself, as markdown, after the "
                    + "command.");
        }
        Path at = incomingFolder();
        try {
            Files.createDirectories(at);
        } catch (IOException ex) {
            throw new Stop("could not make " + at + ": " + ex.getMessage());
        }
        startJRock("saving a document");
        String stamp = java.time.LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"));
        Path file = free(at, "saved-" + stamp, ".docx");
        check(JRock.automationClearPrompt());
        check(JRock.automationSetPrompt(words));
        String problem = JRock.automationExportPromptDocx(file.toString());
        if (problem != null) throw new Stop("the document was not written: " + problem);
        later(() -> say("Saved " + file.getFileName() + " - naming it now."));

        // And then named properly, by the one thing here that names documents. A failure
        // to rename is not a failure to save: the document is on the disk either way, and
        // the answer says what it is called.
        String named = file.getFileName().toString();
        try {
            List<String> command = new ArrayList<>(List.of(java(), "-cp",
                    jar().toString(), flow().toString(), AUTO_FLAG, file.toString()));
            String renamed = valueIn(runFlow(command, at), "name=");
            if (!renamed.isEmpty()) named = renamed;
        } catch (Stop stop) {
            return "Saved in " + at.getFileName() + " as " + named + ", but it could not "
                    + "be named: " + stop.getMessage();
        }
        return "Saved in " + at.getFileName() + " as " + named + ".";
    }

    // A name in dir that is not taken: "<stem><ext>", or the same with -2, -3...
    private static Path free(Path dir, String stem, String ext) {
        Path file = dir.resolve(stem + ext);
        for (int n = 2; Files.exists(file) && n < 100; n++) {
            file = dir.resolve(stem + "-" + n + ext);
        }
        return file;
    }

    // ---- A document, read out --------------------------------------------------
    // Any file the incoming folder holds, as Markdown: included as the kind it is and
    // put through this agent's own model once.
    //
    // Which is the same thing the Thunderbird agent does with an attachment, and for the
    // same two reasons. A DOCX is a zip of XML and a PDF is a set of instructions for
    // drawing: neither is text, and whoever asked wants to be told what the document
    // says, not what it is made of. And a model that has just read it can lay it out as
    // prose, which is what gets read aloud at the other end.
    private static String refined(Path file) {
        Path prompt = ownDirectory().resolve(PROMPT_TO_MARKDOWN);
        String instructions = Files.isRegularFile(prompt) ? read(prompt) : null;
        if (instructions == null || instructions.isBlank()) {
            instructions = "Below is a document. Write out what it says as clean Markdown: "
                    + "its headings, its paragraphs, its lists and its tables, in the "
                    + "document's own language. Keep every number, date, name and "
                    + "reference exactly as written. Do not summarise and do not add "
                    + "anything that is not in it.";
        }
        startJRock("reading a document");
        check(JRock.automationClearPrompt());
        check(JRock.automationSetPrompt(instructions.replaceAll("\\s+$", "") + "\n\n"));
        String kind = kindOf(file);
        String problem = JRock.automationInclude(file.toString(), kind);
        if (problem != null) {
            throw new Stop("could not include " + file.getFileName() + " (" + kind + "): "
                    + problem);
        }
        String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
        if (!"1".equals(sent[0])) throw new Stop("the model did not answer: " + sent[3]);
        String markdown = read(Paths.get(JRock.automationMessageFile("assistant", sent[2])));
        if (markdown == null || markdown.isBlank()) {
            throw new Stop("the model's answer was empty.");
        }
        return markdown;
    }

    // "letter" for the scanner's folder, "document" for the incoming one: the kind says
    // where a thing came from, which is what tells the asking agent how to talk about it.
    private static String kindOfPlace(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        Path in = incomingFolder().toAbsolutePath();
        return parent != null && parent.equals(in) ? "document" : "letter";
    }

    // How JRock should be asked to include this file: the include kinds, by extension.
    private static String kindOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return "pdfhtml";
        if (name.endsWith(".docx")) return "docx";
        if (name.endsWith(".rtf")) return "rtf";
        if (name.endsWith(".xlsx")) return "xlsx";
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif")) {
            return "imgref";
        }
        return "txt";
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    // ---- Its own JRock -----------------------------------------------------------
    // Started once, when something first needs it, and not before: listing a folder
    // needs no model, and a window that did nothing but sit there would be a window to
    // close. Saving a document needs one (its prompt is the paper), and so does reading
    // out a file that is not already text.
    private static void startJRock(String what) {
        if (started) return;
        started = true;
        JRock.main(new String[0]);
        check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
        check(JRock.automationBegin("papers agent: " + what));
    }

    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // ---- Settings --------------------------------------------------------------
    private static void readSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("folder=")) {
                    String at = text.substring("folder=".length()).trim();
                    if (!at.isEmpty()) folder = Paths.get(at);
                } else if (text.startsWith("incoming=")) {
                    String at = text.substring("incoming=".length()).trim();
                    if (!at.isEmpty()) incoming = Paths.get(at);
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read " + file + ": " + ex.getMessage());
        }
    }

    private static void writeSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        String out = "' JRock papers agent\n"
                + (folder == null ? "" : "folder=" + folder + "\n");
        try {
            Files.write(file, out.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            say("Could not remember the folder in " + file + ": " + ex.getMessage());
        }
    }

    // ---- Plumbing --------------------------------------------------------------
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        try {
            java.security.CodeSource code =
                    JRockPapers.class.getProtectionDomain().getCodeSource();
            if (code != null && code.getLocation() != null) {
                Path at = Paths.get(code.getLocation().toURI()).toAbsolutePath().normalize();
                Path parent = Files.isDirectory(at) ? at : at.getParent();
                if (parent != null) return parent;
            }
        } catch (RuntimeException | java.net.URISyntaxException ignore) {
            // The working directory it is, then.
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static Thread daemon(Runnable what, String name) {
        Thread t = new Thread(what, name);
        t.setDaemon(true);
        return t;
    }

    private static void onEdt(Runnable what) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(what);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            System.out.println("Failed on the event thread: " + ex.getCause());
        }
    }

    private static void later(Runnable what) {
        javax.swing.SwingUtilities.invokeLater(what);
    }

    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop(String why) {
            super(why);
        }
    }

    private JRockPapers() {
    }
}
