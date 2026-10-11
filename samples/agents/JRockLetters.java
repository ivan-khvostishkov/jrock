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
//     java -cp jrock.jar JRockLetters.java [--folder <where the scanner writes>]
//     java -cp jrock.jar JRockLetters.java --print recent
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
//     #inventory [name]   file it: the named scan, or the most recent one, through the
//                         doc inventory flow - which renames everything and says what to
//     #folder             where it is looking, and whether that folder is there
//
// TWO FILES IN FIVE MINUTES IS ONE LETTER. A one-sided scanner given a two-sided letter
// is run twice - the stack, then the stack turned over - and writes two PDFs a minute
// apart. So #recent counts DOCUMENTS, not files: two scans close together and adjacent in
// name are reported as one letter in two passes, which is what #inventory then merges.
// Three or more close together are reported as what they are, with their times, because
// at that point only the operator knows what happened.
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

public final class JRockLetters {

    // The next port after the browser's.
    private static final int PORT = 47475;

    // Where this agent keeps the one thing it is told: the folder the scanner writes to.
    private static final String SETTINGS = "jrock-letters.txt";

    // The flow it drives, and the flag that stops it asking.
    private static final String FLOW = "JRockDocInventory.java";
    private static final String AUTO_FLAG = "--auto";
    private static final String AUTO_RESULT = "AUTO-RESULT";

    // What a scanner writes, and what a filed letter is read from.
    private static final String SCAN_EXT = ".pdf";
    private static final String TEXT_EXT = ".txt";

    // Two scans this close together, with adjacent names, are one two-sided letter.
    private static final long DUPLEX_WINDOW_MS = 5 * 60_000;

    // How far back #recent looks when it is not told, and how many scans #scans lists.
    private static final int RECENT_MINUTES = 10;
    private static final int LIST_MAX = 20;

    // How much of a letter goes back in one answer. A letter is a page or two; a hundred
    // thousand characters is a scanner that was fed a book, and sending it through a
    // model is a bill nobody meant to run up.
    private static final int TEXT_MAX = 100_000;

    // How long the flow gets: a PDF through Ghostscript, xpdf and three model passes.
    private static final long FLOW_TIMEOUT_MS = 20 * 60_000;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("EEE d MMM HH:mm:ss", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH);

    private static volatile Path folder;
    private static volatile Process flowRunning;

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField folderField;
    private static javax.swing.JTextArea result;
    private static javax.swing.JLabel status;
    private static javax.swing.JLabel listening;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        String printing = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--folder") && i + 1 < args.length) {
                folder = Paths.get(args[++i]);
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

        onEdt(JRockLetters::showWindow);
        String said = listen();
        later(() -> listening.setText(said));
        System.out.println("Letters agent is ready. " + said);
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
            default:
                throw new Stop("\"" + line + "\" is not something this agent answers. Ask "
                        + "#recent [minutes], #scans [n], #filed [n], #file <name>, "
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

        Scan(Path file, Instant at) {
            this.file = file;
            this.at = at;
        }

        String name() {
            return file.getFileName().toString();
        }
    }

    // Every PDF in the folder, newest first. Not recursive: a scanner writes where it is
    // told to write, and a folder tree would turn "what just arrived" into a search.
    private static List<Scan> scansIn(Path at) {
        List<Scan> scans = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(at)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(SCAN_EXT)) continue;
                try {
                    scans.add(new Scan(file, Files.getLastModifiedTime(file).toInstant()));
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
        List<List<Scan>> documents = group(fresh);
        StringBuilder out = new StringBuilder("In the last " + minutes
                + (minutes == 1 ? " minute" : " minutes") + ": " + documents.size()
                + (documents.size() == 1 ? " document" : " documents") + ", "
                + fresh.size() + (fresh.size() == 1 ? " file" : " files") + ".");
        for (List<Scan> document : documents) {
            Scan first = document.get(document.size() - 1);
            out.append("\n- ").append(document.size() == 2
                    ? "one letter in two passes (two-sided): " : "")
                    .append(document.size() > 2 ? document.size() + " scans together: " : "");
            for (int i = document.size() - 1; i >= 0; i--) {
                Scan scan = document.get(i);
                out.append(i == document.size() - 1 ? "" : ", ").append(scan.name())
                        .append(" at ").append(ZonedDateTime.ofInstant(scan.at, zone)
                                .format(CLOCK));
            }
            if (Files.isRegularFile(textOf(first.file))) {
                out.append("  (filed already)");
            }
        }
        out.append("\nAsk to file it and the inventory flow names it and renames every "
                + "file of it.");
        return out.append('\n').append(footer(at)).toString();
    }

    // Scans into documents: two within five minutes of each other, with adjacent names,
    // are the two passes of one two-sided letter.
    private static List<List<Scan>> group(List<Scan> scans) {
        List<List<Scan>> documents = new ArrayList<>();
        List<Scan> current = new ArrayList<>();
        for (Scan scan : scans) {     // newest first
            if (current.isEmpty()) {
                current.add(scan);
                continue;
            }
            Scan last = current.get(current.size() - 1);
            long apart = Math.abs(Duration.between(scan.at, last.at).toMillis());
            if (apart <= DUPLEX_WINDOW_MS && adjacent(scan.name(), last.name())) {
                current.add(scan);
                continue;
            }
            documents.add(current);
            current = new ArrayList<>();
            current.add(scan);
        }
        if (!current.isEmpty()) documents.add(current);
        return documents;
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
        List<Path> matches = new ArrayList<>();
        for (Path file : texts) {
            if (matches(file.getFileName().toString(), asked)) matches.add(file);
        }
        if (matches.isEmpty()) {
            StringBuilder out = new StringBuilder("No filed letter matches \"" + asked
                    + "\". Filed letters there:");
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
                StringBuilder out = new StringBuilder(matches.size() + " filed letters "
                        + "match \"" + asked + "\". Which one:");
                for (Path file : matches) out.append("\n- ").append(file.getFileName());
                return out.toString();
            }
        }
        Path file = matches.get(0);
        String body;
        try {
            body = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file.getFileName() + ": " + ex.getMessage());
        }
        boolean cut = body.length() > TEXT_MAX;
        if (cut) body = body.substring(0, TEXT_MAX);
        // CONTENT is the marker the asking agent splits on: everything after the first
        // line is the letter itself, to be written to a file and included rather than
        // pasted into a prompt.
        return "CONTENT " + file.getFileName() + "\n" + body
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

        List<Scan> document;
        if (asked.isBlank()) {
            // The most recent document, which may be two passes of one letter.
            document = group(scans).get(0);
        } else {
            Scan found = null;
            for (Scan scan : scans) {
                if (matches(scan.name(), asked)) {
                    found = scan;
                    break;
                }
            }
            if (found == null) throw new Stop("no scan in " + at + " matches \"" + asked + "\".");
            document = new ArrayList<>();
            document.add(found);
            // Its other pass, if it has one.
            for (Scan scan : scans) {
                if (scan != found && Math.abs(Duration.between(scan.at, found.at).toMillis())
                        <= DUPLEX_WINDOW_MS && adjacent(scan.name(), found.name())) {
                    document.add(scan);
                    break;
                }
            }
        }
        if (document.size() > 2) {
            throw new Stop(document.size() + " scans arrived together, and this files one "
                    + "letter at a time - one file, or two passes of one two-sided letter. "
                    + "Name the one to file.");
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
        StringBuilder out = new StringBuilder();
        String resultLine = null;
        try {
            Process flowProcess = new ProcessBuilder(command)
                    .directory(at.toFile())
                    .redirectErrorStream(true)
                    .start();
            flowRunning = flowProcess;
            flowProcess.getOutputStream().close();
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
            if (!flowProcess.waitFor(FLOW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                flowProcess.destroyForcibly();
                throw new Stop("the inventory flow took longer than "
                        + FLOW_TIMEOUT_MS / 60_000 + " minutes and was stopped.");
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
            throw new Stop("the inventory flow filed nothing"
                    + (why.isEmpty() ? "." : ": " + why));
        }
        String name = valueIn(resultLine, "name=");
        String scanned = valueIn(resultLine, "scanned=");
        String files = valueIn(resultLine, "files=");
        return "Filed: " + name + "\nScanned: " + scanned + "\nFiles renamed: " + files
                + "\nAsk what it says by any part of that name.";
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
        }, "jrock-letters-listener");
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
        window = new javax.swing.JFrame("JRock - Letters (the paper post)");
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

        status = new javax.swing.JLabel(" ");
        status.setFont(small);
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 8, 2));

        listening = new javax.swing.JLabel(" ");
        listening.setFont(small);
        listening.setForeground(MUTED);
        listening.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 2, 0, 2));

        javax.swing.JPanel north = new javax.swing.JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] {
                folderRow, asks, status }) {
            row.setAlignmentX(0f);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                    row.getPreferredSize().height));
            north.add(row);
        }

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
        }, "jrock-letters-ask").start();
    }

    private static void show(String text) {
        result.setText(text);
        result.setCaretPosition(0);
    }

    private static void say(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:720px'>" + safe + "</div></html>");
        System.out.println(text);
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
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read " + file + ": " + ex.getMessage());
        }
    }

    private static void writeSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        String out = "' JRock letters agent\n"
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
                    JRockLetters.class.getProtectionDomain().getCodeSource();
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

    private JRockLetters() {
    }
}
