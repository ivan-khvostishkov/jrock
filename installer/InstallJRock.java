// JRock's installer: one Swing wizard, one Java source file, no build step.
//
//     java InstallJRock.java            the wizard
//     java InstallJRock.java --check    what it would find, printed; changes nothing
//
// Run it from the folder the release artifact was unpacked into - the one holding
// jrock.jar and samples/ - which is the first thing it checks.
//
// ONE FOLDER holds the installation, and that is the only thing to decide:
//
//     Documents\JRockAssistant\             jrock.jar + its SHA-256 and MD5
//                                           JRock\ - the window's own files
//                                           jrock-prompt-*.txt - the prompt library
//         Agents\PushToTalk\                the agent, its prompts, its own JRock\
//         Agents\Timer\                     ...one folder each, because they all run
//         Agents\Weather\                      at once
//         Flows\                            every flow, their prompts, one JRock\
//
// An AGENT loops: it is started with JRock and stays up - a timer, a push-to-talk
// listener, a calendar reader answering a port - so it gets a folder to itself, because
// JRock writes its log, its prompt and its transcript into a JRock/ inside the folder it
// works in, and three agents sharing one folder would write over each other.
//
// A FLOW runs once, on something you point it at, and asks you for it: translate this
// document, inventory that scan. Nothing loops, nothing is started behind you, and one
// folder does for all of them.
//
// What it does, in order, and nothing else:
//
//   1. finds jrock.jar and samples/ beside itself;
//   2. copies jrock.jar into the folder you choose, with its SHA-256 and MD5 beside it,
//      as the release publishes them;
//   3. looks for Ghostscript and xpdf on PATH, and says where to get them;
//   4. offers Start menu and Desktop shortcuts called "JRock Assistant";
//   5. installs the agents and flows you tick, each with the prompt files its own source
//      names, and - if you want them - the prompts that belong to no sample at all;
//   6. opens the new jrock-config.txt for review, then bedrock-key.txt for the key;
//   7. offers to copy those settings into each agent's folder and into Flows\;
//   8. installs the Explorer right-click entries - "JRock here!", one per agent, one per
//      flow - after clearing away every JRock entry that was already there;
//   9. offers to start JRock and the AGENTS, once nothing of JRock is still running. Not
//      the flows: a flow is one run you ask for, and starting one behind you would open
//      a file chooser nobody asked for.
//
// IT REMOVES NO FOLDER, NO DOCUMENT AND NO SETTINGS FILE. Not one, ever. A jrock.jar in
// the folder is replaced, which is what installing a release means; a jrock-config.txt, a
// bedrock-key.txt or a prompt file already there is KEPT and said so, because those are
// yours. Nothing is deleted when you install somewhere else, and nothing is deleted when
// you install again.
//
// What it MAY remove is two kinds of pointer, and only when you say so: its own Explorer
// right-click entries (page 8 clears every JRock entry on the machine before writing the
// new ones, and clears them even when you install none) and its own shortcuts (page 4,
// where unticking one that is there takes it away). A pointer is not data: the entries
// are written down into a .reg that puts every one of them back, and a shortcut is one
// line of this installer away from being written again.
//
// A previous install is read rather than guessed at: the Explorer entries hold the folder
// everything went into, which agents and flows were installed and where each one works,
// and those become the defaults here - so installing again extends what is there instead
// of asking everything twice.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstallJRock {

    // The name of the folder everything goes into, and what the shortcuts are called. The
    // folder's NAME ends up in every Explorer entry's label, which is why it is a word
    // and not a path.
    private static final String ASSISTANT = "JRockAssistant";
    private static final String PRODUCT = "JRock";
    private static final String SHORTCUT = "JRock Assistant";

    // The two folders inside it: one per agent under Agents, all the flows in Flows.
    private static final String AGENTS_DIR = "Agents";
    private static final String FLOWS_DIR = "Flows";

    // What the artifact must hold, beside this installer.
    private static final String JAR = "jrock.jar";
    private static final String SAMPLES = "samples";
    private static final String PROMPTS_SUB = "prompts";
    private static final String AGENTS_SUB = "agents";
    private static final String FLOWS_SUB = "flows";

    // Where to send anybody missing the two outside tools.
    private static final String GS_URL = "https://ghostscript.com/releases/gsdnld.html";
    private static final String XPDF_URL = "https://www.xpdfreader.com/download.html";

    // The registry names. This installer is the only thing that writes them - JRock
    // itself knows nothing about the registry, which is what keeps every line of it
    // doing the same thing on Windows, macOS and Linux - and the only thing that reads
    // them back, both to clean up and to find a previous install.
    private static final String CTX_KEY = "OpenJRockHere";
    private static final String CTX_LABEL = "JRock here!";
    private static final String TXT_KEY = "OpenAsJRockPrompt";
    private static final String TXT_LABEL = "Open as prompt with JRock";
    private static final String AGENT_KEY_PREFIX = "JRockAgent-";
    private static final String FLOW_KEY_PREFIX = "JRockFlow-";
    private static final String AGENT_TITLE = "JRock agent";
    private static final String FLOW_TITLE = "JRock flow";
    private static final String[] CTX_SHELL_ROOTS = {
        "Software\\Classes\\Directory\\Background\\shell",
        "Software\\Classes\\Directory\\shell",
    };
    private static final String TXT_SHELL_ROOT =
            "Software\\Classes\\SystemFileAssociations\\.txt\\shell";
    private static final String FILE_SHELL_ROOT = "Software\\Classes\\*\\shell";
    // Where a JRock entry can be, including the places this installer never writes: a
    // cleanup that only looked where it writes would leave an older install's entries
    // behind, and the point of the cleanup is that there is nothing left behind.
    private static final String[] ALL_SHELL_ROOTS = {
        "HKCU\\Software\\Classes\\Directory\\Background\\shell",
        "HKCU\\Software\\Classes\\Directory\\shell",
        "HKCU\\Software\\Classes\\*\\shell",
        "HKCU\\Software\\Classes\\SystemFileAssociations\\.txt\\shell",
        "HKLM\\Software\\Classes\\Directory\\Background\\shell",
        "HKLM\\Software\\Classes\\Directory\\shell",
        "HKLM\\Software\\Classes\\*\\shell",
        "HKLM\\Software\\Classes\\SystemFileAssociations\\.txt\\shell",
    };

    private enum Kind { AGENT, FLOW }

    // One sample as the wizard knows it: its file, which kind it is, the prompts its own
    // source names, and where it is to live.
    private static final class Sample {
        final Path file;
        final String stem;
        final Kind kind;
        final List<Path> prompts = new ArrayList<>();
        boolean selected = true;
        Path own;                       // an agent's folder, when it is not the default

        Sample(Path file, Kind kind) {
            this.file = file;
            this.kind = kind;
            String name = file.getFileName().toString();
            this.stem = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
        }

        boolean loops() {
            return kind == Kind.AGENT;
        }

        // The words of a camel-case name, as the labels read them: "JRockPushToTalk" ->
        // "Push To Talk".
        String words() {
            String s = stem.startsWith("JRock") && stem.length() > 5 ? stem.substring(5) : stem;
            String words = s.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ")
                    .trim();
            return words.isEmpty() ? stem : words;
        }

        // What an agent's own folder is named after: "JRockPushToTalk" -> "PushToTalk".
        String shortStem() {
            return stem.replaceFirst("^JRock", "");
        }

        // The one folder this sample lives and works in: its .java file, the prompt files
        // it reads, and the JRock/ its own JRock writes.
        //
        // One folder for all three because an agent looks for its prompts BESIDE ITS OWN
        // FILE (ownDirectory() in any of them) and nowhere else - not in the working
        // folder, not through --prompts-dir. Split them and it stops on "there is no
        // jrock-prompt-....txt" the first time it is clicked.
        //
        // An agent gets a folder to itself because every agent is running at the same
        // time as every other one. The flows share theirs because a flow is one run you
        // started yourself, and because sharing is one config and one key instead of
        // seven.
        Path dir(Path home) {
            if (!loops()) return home.resolve(FLOWS_DIR);
            return own != null ? own : home.resolve(AGENTS_DIR).resolve(shortStem());
        }

        String title() {
            return loops() ? AGENT_TITLE : FLOW_TITLE;
        }

        String keyPrefix() {
            return loops() ? AGENT_KEY_PREFIX : FLOW_KEY_PREFIX;
        }
    }

    // Everything the wizard has been told, in one place.
    private static Path source;          // where the artifact was unpacked
    private static Path home;            // the one folder the installation lives in
    private static boolean startMenu = true;
    private static boolean desktop = true;
    private static boolean withSamples = true;
    private static boolean customise;
    private static boolean orphanPrompts = true;
    private static boolean copySettings = true;
    private static boolean copyKey = true;
    private static boolean contextMenus = true;
    private static final List<Sample> found = new ArrayList<>();
    // Sample name -> the folder a previous install gave it, read back from its entry.
    private static final Map<String, Path> previouslyInstalled = new LinkedHashMap<>();
    private static boolean toolsSkipped;

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color BRAND = new java.awt.Color(0x0F766E);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);
    private static final java.awt.Color ALARM = new java.awt.Color(0xB91C1C);
    // One width for every page, so the text, the folder rows and the tables all end on
    // the same line down the right - which is most of what makes a page look made rather
    // than assembled.
    private static final int TEXT_WIDTH = 700;

    private static javax.swing.JFrame window;
    private static javax.swing.JPanel content;
    private static javax.swing.JLabel heading;
    private static javax.swing.JTextArea log;
    private static javax.swing.JButton back, next, cancel;
    private static final List<Step> steps = new ArrayList<>();
    private static int at;

    // One page of the wizard: what it is called, what it shows, and what it does when
    // Next is pressed - returning the reason to stay on the page, or null to go on.
    private static final class Step {
        final String title;
        final java.util.function.Consumer<javax.swing.JPanel> build;
        final java.util.function.Supplier<String> onNext;
        String nextLabel = "Next >";

        Step(String title, java.util.function.Consumer<javax.swing.JPanel> build,
             java.util.function.Supplier<String> onNext) {
            this.title = title; this.build = build; this.onNext = onNext;
        }
    }

    public static void main(String[] args) {
        source = Paths.get("").toAbsolutePath().normalize();
        boolean check = args.length > 0 && args[0].equals("--check");
        findSamples();
        previousInstall();
        if (check) {
            printCheck();
            return;
        }
        defaults();
        javax.swing.SwingUtilities.invokeLater(() -> {
            buildSteps();
            showWindow();
        });
    }

    // What --check prints: every detection this installer makes, and nothing written.
    private static void printCheck() {
        System.out.println("Unpacked artifact:   " + source);
        System.out.println("  " + JAR + ":        " + (Files.isRegularFile(source.resolve(JAR))
                ? "found, " + size(source.resolve(JAR)) + " bytes" : "MISSING"));
        System.out.println("  " + SAMPLES + "/:          "
                + (Files.isDirectory(source.resolve(SAMPLES)) ? "found" : "MISSING"));
        System.out.println("Agents (they loop):  " + ofKind(Kind.AGENT).size());
        for (Sample s : ofKind(Kind.AGENT)) {
            System.out.println("  " + s.stem + "  (" + s.words() + ")  prompts: "
                    + s.prompts.size() + "  -> " + AGENTS_DIR + "/" + s.shortStem());
        }
        System.out.println("Flows (one run):     " + ofKind(Kind.FLOW).size());
        for (Sample s : ofKind(Kind.FLOW)) {
            System.out.println("  " + s.stem + "  (" + s.words() + ")  prompts: "
                    + s.prompts.size() + "  -> " + FLOWS_DIR);
        }
        System.out.println("Prompts of no sample: " + orphans().size());
        System.out.println("Ghostscript:         " + or(ghostscript(), "NOT on PATH"));
        System.out.println("xpdf pdftohtml:      " + or(onPath("pdftohtml"), "NOT on PATH"));
        System.out.println("xpdf pdftotext:      " + or(onPath("pdftotext"), "NOT on PATH"));
        System.out.println("javaw/java:          " + or(launcher(), "NOT found"));
        System.out.println("Previous install:    "
                + (previouslyInstalled.isEmpty() && home == null ? "none found"
                   : "folder " + home + ", installed " + previouslyInstalled.keySet()));
        if (isWindows()) {
            List<String[]> entries = existingEntries();
            System.out.println("JRock entries now:   " + entries.size()
                    + (entries.isEmpty() ? "" : " (page 8 would clear them first)"));
            for (String[] e : entries) System.out.println("  " + e[0]);
        }
        System.out.println("Running JRock:       " + or(String.join(", ", runningJRock()), "none"));
    }

    private static String or(Object value, String otherwise) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? otherwise : text;
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return -1;
        }
    }

    // ---- What is there to install ----------------------------------------------
    // samples/agents/ and samples/flows/, each sample with the prompt files its own
    // source names - which are shipped in the same folder it is, because that is where
    // it reads them from once installed as well.
    //
    // The kind comes from the folder it arrived in, and the prompts are read out of the
    // source rather than from the folder listing: a prompt can be read by two samples
    // (the push-to-talk and the weather agent share one), the flows all sit together, and
    // only the source says which of the files beside it are actually its own.
    private static void findSamples() {
        found.clear();
        collect(source.resolve(SAMPLES).resolve(AGENTS_SUB), Kind.AGENT);
        collect(source.resolve(SAMPLES).resolve(FLOWS_SUB), Kind.FLOW);
    }

    private static void collect(Path dir, Kind kind) {
        if (!Files.isDirectory(dir)) return;
        Pattern prompt = Pattern.compile("jrock-prompt-[a-z0-9-]+\\.txt");
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            List<Path> javas = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(javas::add);
            for (Path java : javas) {
                String text = new String(Files.readAllBytes(java), StandardCharsets.UTF_8);
                Sample sample = new Sample(java, kind);
                Matcher m = prompt.matcher(text);
                Set<String> names = new LinkedHashSet<>();
                while (m.find()) names.add(m.group());
                for (String name : names) {
                    Path beside = dir.resolve(name);
                    Path spare = source.resolve(SAMPLES).resolve(PROMPTS_SUB).resolve(name);
                    if (Files.isRegularFile(beside)) sample.prompts.add(beside);
                    else if (Files.isRegularFile(spare)) sample.prompts.add(spare);
                }
                found.add(sample);
            }
        } catch (IOException ex) {
            // Nothing found: the first page says so and offers Retry.
        }
    }

    // Every prompt file in the artifact, wherever it sits.
    private static List<Path> allPrompts() {
        List<Path> prompts = new ArrayList<>();
        for (String sub : new String[] { PROMPTS_SUB, AGENTS_SUB, FLOWS_SUB }) {
            Path dir = source.resolve(SAMPLES).resolve(sub);
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                        .sorted().forEach(prompts::add);
            } catch (IOException ex) {
                // Nothing to add.
            }
        }
        return prompts;
    }

    // The prompts in samples/prompts/, which is where a prompt that belongs to no agent
    // and no flow is shipped: a prompt to use by hand, from JRock's own Load prompt. They
    // have nowhere else to go, so they are offered the one folder every "JRock here!"
    // opens its prompt library in.
    private static List<Path> orphans() {
        List<Path> rest = new ArrayList<>();
        Path dir = source.resolve(SAMPLES).resolve(PROMPTS_SUB);
        if (!Files.isDirectory(dir)) return rest;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted().forEach(rest::add);
        } catch (IOException ex) {
            // Nothing to add.
        }
        return rest;
    }

    private static List<Sample> ofKind(Kind kind) {
        List<Sample> some = new ArrayList<>();
        for (Sample s : found) if (s.kind == kind) some.add(s);
        return some;
    }

    private static List<Sample> selected() {
        List<Sample> chosen = new ArrayList<>();
        if (!withSamples) return chosen;
        for (Sample s : found) if (s.selected) chosen.add(s);
        return chosen;
    }

    private static List<Sample> selectedOfKind(Kind kind) {
        List<Sample> chosen = new ArrayList<>();
        for (Sample s : selected()) if (s.kind == kind) chosen.add(s);
        return chosen;
    }

    // Every folder an installed sample works in, each once: the agents' own folders, and
    // Flows once however many flows there are.
    private static List<Path> sampleFolders() {
        Set<Path> dirs = new LinkedHashSet<>();
        for (Sample s : selected()) dirs.add(s.dir(home));
        return new ArrayList<>(dirs);
    }

    // ---- A previous install ----------------------------------------------------
    // The Explorer entries hold everything this installer would otherwise have to ask
    // twice: the folder the jar is in, which samples are installed, and where each one
    // works. Read with reg.exe - there is no registry API in the JDK.
    private static void previousInstall() {
        if (!isWindows()) return;
        String here = regDefault("HKCU\\" + CTX_SHELL_ROOTS[0] + "\\" + CTX_KEY + "\\command");
        if (here != null) {
            Path jar = jarIn(here);
            if (jar != null && jar.getParent() != null) home = jar.getParent();
        }
        for (String key : regKeys("HKCU\\" + FILE_SHELL_ROOT)) {
            if (!key.startsWith(AGENT_KEY_PREFIX) && !key.startsWith(FLOW_KEY_PREFIX)) continue;
            String command = regDefault("HKCU\\" + FILE_SHELL_ROOT + "\\" + key + "\\command");
            if (command == null) continue;
            Matcher m = Pattern.compile("\"([^\"]*JRock[A-Za-z0-9]*\\.java)\"").matcher(command);
            if (m.find()) {
                previouslyInstalled.put(Paths.get(m.group(1)).getFileName().toString()
                        .replaceAll("\\.java$", ""), flagIn(command, "--working-dir"));
            }
            if (home == null) {
                Path jar = jarIn(command);
                if (jar != null && jar.getParent() != null) home = jar.getParent();
            }
        }
    }

    // The default value of a key, or null when it is not there.
    private static String regDefault(String key) {
        return regValue(key, null);
    }

    private static String regValue(String key, String name) {
        List<String> out = name == null ? run("reg.exe", "query", key, "/ve")
                                        : run("reg.exe", "query", key, "/v", name);
        for (String line : out) {
            int at = line.indexOf("REG_SZ");
            if (at >= 0) return line.substring(at + "REG_SZ".length()).trim();
        }
        return null;
    }

    // The subkey names under a key, which is how installed samples are found.
    private static List<String> regKeys(String key) {
        List<String> keys = new ArrayList<>();
        for (String line : run("reg.exe", "query", key)) {
            String text = line.trim();
            int slash = text.lastIndexOf('\\');
            if (text.startsWith("HKEY_") && slash > 0) keys.add(text.substring(slash + 1));
        }
        return keys;
    }

    // Both shapes of installed command: JRock itself runs as -jar, a sample as -cp with
    // its own .java file after it.
    private static Path jarIn(String command) {
        Matcher m = Pattern.compile("-(?:cp|jar)\\s+\"([^\"]+)\"").matcher(command);
        return m.find() ? Paths.get(m.group(1)) : null;
    }

    private static Path flagIn(String command, String flag) {
        Matcher m = Pattern.compile(Pattern.quote(flag) + "[=\\s]+\"([^\"]+)\"").matcher(command);
        if (!m.find()) return null;
        String value = m.group(1).replaceAll("\\\\+$", "\\\\");
        return Paths.get(value);
    }

    // What was not read from a previous install: the defaults for this machine.
    private static void defaults() {
        Path userHome = Paths.get(System.getProperty("user.home", "."));
        Path documents = Files.isDirectory(userHome.resolve("Documents"))
                ? userHome.resolve("Documents") : userHome;
        if (home == null) home = documents.resolve(ASSISTANT);
        if (!previouslyInstalled.isEmpty()) {
            // Installing again: what was there is what is ticked, and the rest is there
            // to be added rather than quietly installed behind the user. An agent's
            // folder is offered back to it as it was.
            for (Sample s : found) {
                s.selected = previouslyInstalled.containsKey(s.stem);
                Path was = previouslyInstalled.get(s.stem);
                if (s.loops() && was != null) s.own = was;
            }
            customise = true;
        }
    }

    // ---- The steps -------------------------------------------------------------
    private static void buildSteps() {
        steps.clear();

        // 1. What was unpacked.
        steps.add(new Step("What was unpacked", panel -> {
            boolean jar = Files.isRegularFile(source.resolve(JAR));
            boolean samples = Files.isDirectory(source.resolve(SAMPLES));
            panel.add(text("This installer works on the folder it was started in:"));
            panel.add(value(source.toString()));
            panel.add(found(jar, JAR + (jar ? " - " + fmt(size(source.resolve(JAR)))
                    + " bytes" : "")));
            panel.add(found(samples, SAMPLES + "/" + (samples
                    ? " - " + ofKind(Kind.AGENT).size() + " agent(s) that loop, "
                      + ofKind(Kind.FLOW).size() + " flow(s) that run once, "
                      + allPrompts().size() + " prompt file(s)" : "")));
            if (!jar || !samples) {
                panel.add(note("Start this installer from the folder the release zip was "
                        + "unpacked into - the one holding jrock.jar and samples/. Press "
                        + "Retry when it is."));
            } else if (!previouslyInstalled.isEmpty()) {
                panel.add(note("A previous install was found, and its folder and its "
                        + "agents and flows are the defaults below. Nothing of your work "
                        + "will be removed."));
            }
        }, () -> {
            findSamples();
            if (!Files.isRegularFile(source.resolve(JAR))) return "jrock.jar is not here.";
            if (!Files.isDirectory(source.resolve(SAMPLES))) return SAMPLES + "/ is not here.";
            if (found.isEmpty()) return "No agents or flows in " + SAMPLES + "/.";
            return null;
        }));
        steps.get(0).nextLabel = "Next >";

        // 2. The one folder, and the jar into it.
        steps.add(new Step("Where everything goes", panel -> {
            panel.add(text("One folder holds the lot - the program, your work, the agents "
                    + "and the flows:"));
            panel.add(folderRow(() -> home, p -> home = p, "Install folder"));
            panel.add(tree());
            panel.add(note("The FOLDER NAME is what Explorer's right-click entries are "
                    + "labelled with - \"" + AGENT_TITLE + ": Timer (" + home.getFileName()
                    + ")...\" - so it is worth a name you will recognise. A second folder "
                    + "is a second JRock with its own model, DPI and key, installed the "
                    + "same way."));
        }, () -> {
            try {
                Files.createDirectories(home);
                Path jar = home.resolve(JAR);
                boolean replacing = Files.isRegularFile(jar);
                Files.copy(source.resolve(JAR), jar, StandardCopyOption.REPLACE_EXISTING);
                say((replacing ? "Replaced " : "Copied ") + jar + " (" + fmt(size(jar))
                        + " bytes)");
                writeHash(jar, "SHA-256", "sha256");
                writeHash(jar, "MD5", "md5");
                return null;
            } catch (IOException ex) {
                return "Could not write into " + home + ": " + ex.getMessage();
            }
        }));

        // 3. The two outside tools.
        steps.add(new Step("Ghostscript and xpdf", panel -> {
            Path gs = ghostscript();
            Path html = onPath("pdftohtml");
            Path txt = onPath("pdftotext");
            panel.add(text("JRock converts PDFs with two tools it does not ship. "
                    + "Everything else works without them."));
            panel.add(found(gs != null, "Ghostscript" + (gs == null ? "" : " - " + gs)
                    + " - PDF pages as images, and merging duplex scans"));
            panel.add(found(html != null && txt != null, "xpdf"
                    + (html == null || txt == null ? "" : " - " + html.getParent())
                    + " - PDF as HTML or as text"));
            if (gs == null) panel.add(link("Ghostscript downloads", GS_URL));
            if (html == null || txt == null) {
                panel.add(link("xpdf command line tools", XPDF_URL));
            }
            if (gs == null || html == null || txt == null) {
                panel.add(note("Install what is missing, add it to PATH, then press Retry. "
                        + "Or press Next without them - JRock says which tool is missing "
                        + "if you ever ask it for that conversion, and installing them "
                        + "later needs nothing from this installer."));
            }
        }, () -> {
            boolean all = ghostscript() != null && onPath("pdftohtml") != null
                    && onPath("pdftotext") != null;
            if (!all) {
                toolsSkipped = true;
                say("Ghostscript or xpdf not on PATH: the PDF conversions will say so "
                        + "when asked.");
            }
            return null;
        }));

        // 4. Shortcuts - installed when ticked, and taken away when not.
        steps.add(new Step("Shortcuts", panel -> {
            Path menuAt = shortcutFile(startMenuDir());
            Path deskAt = shortcutFile(desktopDir());
            panel.add(text("Both open JRock in " + home + "."));
            panel.add(check("Start menu shortcut \"" + SHORTCUT + "\"", startMenu,
                    v -> {
                        startMenu = v;
                        redraw();
                    }));
            panel.add(check("Desktop shortcut \"" + SHORTCUT + "\"", desktop, v -> {
                desktop = v;
                redraw();
            }));
            List<Path> going = new ArrayList<>();
            if (!startMenu && menuAt != null && Files.exists(menuAt)) going.add(menuAt);
            if (!desktop && deskAt != null && Files.exists(deskAt)) going.add(deskAt);
            for (Path p : going) panel.add(alarm("Will be removed: " + p));
            panel.add(note(going.isEmpty()
                    ? "Unticking one that is already there removes it - this is where a "
                      + "shortcut is taken away as well as put."
                    : "Unticked and already there, so it goes. Nothing else of yours is "
                      + "touched: tick it again to keep it."));
            if (!isWindows()) {
                panel.add(note(isMac()
                        ? "On macOS these are small .command launchers in ~/Applications "
                          + "and on the Desktop."
                        : "On Linux these are .desktop launchers in the applications menu "
                          + "and on the Desktop."));
            }
        }, () -> {
            if (startMenu) shortcut(startMenuDir(), true);
            else removeShortcut(startMenuDir(), true);
            if (desktop) shortcut(desktopDir(), false);
            else removeShortcut(desktopDir(), false);
            return null;
        }));

        // 5. Which agents and flows, and where each agent works.
        steps.add(new Step("Agents and flows", panel -> {
            panel.add(check("Install the agents and flows", withSamples, v -> {
                withSamples = v;
                redraw();
            }));
            if (!withSamples) {
                panel.add(note("Nothing but JRock itself is installed. Every agent and "
                        + "every flow is a single .java file in the samples/ folder of "
                        + "this artifact: copy one in beside jrock.jar whenever you like "
                        + "and run this installer again to give it a right-click entry."));
                return;
            }
            panel.add(radio("Install all " + found.size() + " of them", !customise, v -> {
                customise = !v;
                redraw();
            }));
            panel.add(radio("Choose which, and where each agent works", customise, v -> {
                customise = v;
                redraw();
            }));
            if (customise) panel.add(sampleTable());
            int spare = orphans().size();
            if (spare > 0) {
                panel.add(check("Also copy the " + spare + " prompt file(s) that no agent "
                        + "and no flow reads into " + home.getFileName(), orphanPrompts,
                        v -> orphanPrompts = v));
            }
            panel.add(note("An AGENT loops - it is started with JRock and stays up - so it "
                    + "gets a folder of its own under " + AGENTS_DIR + "\\, because JRock "
                    + "writes its log, its prompt and its transcript into a JRock/ inside "
                    + "the folder it works in. A FLOW runs once on something you point it "
                    + "at, so all of them share " + FLOWS_DIR + "\\. Each one is installed "
                    + "with the prompt files its own source names, beside its .java, which "
                    + "is where it looks for them."));
        }, () -> {
            if (!withSamples) return null;
            if (!customise) {
                for (Sample s : found) s.selected = true;
            }
            if (selected().isEmpty()) return "Nothing is ticked.";
            return copySamples();
        }));

        // 6. The settings, and the key.
        steps.add(new Step("Settings and the key", panel -> {
            panel.add(text("Two files in " + home.resolve(PRODUCT) + ":"));
            panel.add(value("jrock-config.txt - the endpoint, the model, the DPI, the "
                    + "checkboxes"));
            panel.add(value("bedrock-key.txt - the Bedrock API key, and nothing else"));
            panel.add(note("Next opens the config in whatever opens a .txt here. Save and "
                    + "close it, then the key file opens the same way. An existing file of "
                    + "either kind is kept as it is - this installer never overwrites your "
                    + "settings."));
        }, () -> {
            try {
                Path jrock = home.resolve(PRODUCT);
                Files.createDirectories(jrock);
                Path config = starterConfig(jrock);
                Path key = starterKey(jrock);
                open(config);
                int done = javax.swing.JOptionPane.showConfirmDialog(window,
                        "Reviewing " + config + "\n\nSave and close it, then press OK to "
                        + "open the key file.", "JRock settings",
                        javax.swing.JOptionPane.OK_CANCEL_OPTION,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                if (done != javax.swing.JOptionPane.OK_OPTION) return "Waiting for the config.";
                open(key);
                javax.swing.JOptionPane.showMessageDialog(window,
                        "Paste your Bedrock API key into\n" + key + "\n\nOne line, nothing "
                        + "else. Save and close it, then press OK.", "Bedrock API key",
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
                if (isBlank(key)) {
                    say("No key in " + key + " yet.");
                    javax.swing.JOptionPane.showMessageDialog(window,
                            "The key file is still empty.\n\nPut the key in there whenever "
                            + "you have one - JRock reads it at startup, and the Configure "
                            + "dialog writes it there too.\n\nThe window opens and works "
                            + "without it: merging duplex scans, converting and including "
                            + "documents, fetching a URL, exporting to DOCX or RTF and "
                            + "printing all happen on this machine. Only asking a model "
                            + "needs the key.",
                            "No key yet", javax.swing.JOptionPane.WARNING_MESSAGE);
                } else {
                    say("A key is in place in " + key + ".");
                }
                return null;
            } catch (IOException ex) {
                return "Could not write the settings: " + ex.getMessage();
            }
        }));

        // 7. The same settings for the folders the samples work in.
        steps.add(new Step("Settings for the agents and flows", panel -> {
            List<Path> dirs = sampleFolders();
            if (dirs.isEmpty()) {
                panel.add(text("No agent and no flow is installed, so there is nothing to "
                        + "copy."));
                return;
            }
            panel.add(text("Every folder below runs a JRock of its own, and a JRock of its "
                    + "own reads the settings in its own folder:"));
            for (Path dir : dirs) panel.add(value(dir.toString()));
            panel.add(check("Copy jrock-config.txt into each of them", copySettings,
                    v -> copySettings = v));
            panel.add(check("Copy bedrock-key.txt too", copyKey, v -> copyKey = v));
            panel.add(note("An agent cannot ask a model without a key in its own folder. "
                    + "JRock itself never copies a key into a folder it was not typed for, "
                    + "which is why this is a tick and not a given - and why an existing "
                    + "key in one of these folders is left alone."));
        }, () -> {
            for (Path dir : sampleFolders()) {
                Path jrock = dir.resolve(PRODUCT);
                try {
                    Files.createDirectories(jrock);
                    if (copySettings) copyIfAbsent(home.resolve(PRODUCT)
                            .resolve("jrock-config.txt"), jrock.resolve("jrock-config.txt"));
                    if (copyKey) copyIfAbsent(home.resolve(PRODUCT)
                            .resolve("bedrock-key.txt"), jrock.resolve("bedrock-key.txt"));
                } catch (IOException ex) {
                    return "Could not write into " + jrock + ": " + ex.getMessage();
                }
            }
            return null;
        }));

        // 8. Explorer entries: cleared out first, written second.
        steps.add(new Step("Right-click entries", panel -> {
            if (!isWindows()) {
                panel.add(text("Explorer entries are a Windows thing; there is nothing to "
                        + "install here."));
                return;
            }
            List<String[]> already = existingEntries();
            panel.add(check("Install the Explorer right-click entries", contextMenus,
                    v -> {
                        contextMenus = v;
                        redraw();
                    }));
            if (contextMenus) {
                panel.add(value("\"" + CTX_LABEL + "\" on a folder, and on its background "
                        + "- JRock works in the folder you clicked"));
                panel.add(value("\"" + TXT_LABEL + "\" on a .txt file"));
                for (Sample s : selected()) {
                    panel.add(value("\"" + s.title() + ": " + s.words() + " ("
                            + home.getFileName() + ")...\" on a folder and on any file"));
                }
            }
            if (!already.isEmpty()) {
                panel.add(alarm(already.size() + " JRock entr" + (already.size() == 1
                        ? "y is" : "ies are") + " in the registry now, and "
                        + (contextMenus ? "all of them are removed before the ones above "
                                          + "are written"
                                        : "all of them are removed")));
                for (String[] e : already) panel.add(value("  " + e[2]));
            }
            panel.add(note(already.isEmpty()
                    ? "Written under HKEY_CURRENT_USER - no administrator, and nothing "
                      + "outside your own account."
                    : "Every one of them goes first, including any left by an older "
                      + "install in a folder you no longer use: a stale entry runs a jar "
                      + "that may not be there any more. What was removed is kept as a "
                      + ".reg that puts it all back. One written under "
                      + "HKEY_LOCAL_MACHINE by somebody else needs an administrator to "
                      + "remove - it is named in the log and left alone, and nothing "
                      + "else waits for it."));
            if (!contextMenus) {
                panel.add(note("Nothing ticked, so this page only CLEANS UP: the JRock "
                        + "entries above are removed and none are written. Run this "
                        + "installer again to put them back."));
            }
            panel.add(note("Both .reg files - what was applied, and what was taken away - "
                    + "are kept in " + home.resolve(PRODUCT) + " so you can read exactly "
                    + "what was done. The command line of every entry goes into the log "
                    + "below: which jar runs from where, with which arguments."));
        }, () -> {
            if (!isWindows()) return null;
            String problem = removeEntries(existingEntries());
            if (problem != null) return problem;
            if (!contextMenus) {
                say("No Explorer entries installed - this run only cleaned them up.");
                return null;
            }
            return installEntries();
        }));

        // 9. Done, and running it.
        steps.add(new Step("Installed", panel -> {
            panel.add(text("JRock is installed."));
            panel.add(value("Folder:   " + home));
            if (!selected().isEmpty()) {
                panel.add(value("Agents:   " + selectedOfKind(Kind.AGENT).size()
                        + " in " + AGENTS_DIR + "\\, one folder each, started with JRock"));
                panel.add(value("Flows:    " + selectedOfKind(Kind.FLOW).size()
                        + " in " + FLOWS_DIR + "\\, each run by its right-click entry"));
            }
            List<String> running = runningJRock();
            if (!running.isEmpty()) {
                panel.add(alarm("Something of JRock is still running: "
                        + String.join(", ", running)));
                panel.add(note("Close it, then press Retry - a second JRock in the same "
                        + "folder writes the same log and the same prompt file, and the "
                        + "two overwrite each other."));
            } else {
                panel.add(note("Nothing of JRock is running. Next starts JRock in "
                        + home.getFileName() + (selectedOfKind(Kind.AGENT).isEmpty() ? "."
                        : ", and each agent in its own folder. The flows are not started: "
                          + "a flow is one run you ask for, from its right-click entry.")));
            }
        }, () -> {
            List<String> running = runningJRock();
            if (!running.isEmpty()) return "Still running: " + String.join(", ", running);
            launchAll();
            javax.swing.JOptionPane.showMessageDialog(window,
                    "Started. This installer is done.", "JRock",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            window.dispose();
            return null;
        }));
        steps.get(steps.size() - 1).nextLabel = "Run JRock >";
    }

    // ---- Copying ---------------------------------------------------------------
    // Each chosen sample into the folder it works in: its .java file and the prompt files
    // its own source names, together, because beside its own file is the only place it
    // looks for them. Agents one folder each, flows all in one.
    private static String copySamples() {
        try {
            for (Sample s : selected()) {
                Path dir = s.dir(home);
                Files.createDirectories(dir);
                Path to = dir.resolve(s.file.getFileName());
                Files.copy(s.file, to, StandardCopyOption.REPLACE_EXISTING);
                for (Path p : s.prompts) copyIfNewer(p, dir.resolve(p.getFileName()));
                say((s.loops() ? "Agent: " : "Flow:  ") + to + (s.prompts.isEmpty() ? ""
                        : ", with " + s.prompts.size() + " prompt file(s) beside it"));
            }
            if (orphanPrompts) {
                List<Path> spare = orphans();
                for (Path p : spare) copyIfNewer(p, home.resolve(p.getFileName()));
                if (!spare.isEmpty()) {
                    say(spare.size() + " prompt file(s) of their own into " + home);
                }
            }
            return null;
        } catch (IOException ex) {
            return "Could not copy the agents and flows: " + ex.getMessage();
        }
    }

    private static void copyIfNewer(Path from, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    // Yours stays yours: a settings file already in a folder is never written over.
    private static void copyIfAbsent(Path from, Path to) throws IOException {
        if (!Files.isRegularFile(from)) return;
        if (Files.exists(to)) {
            say("Kept " + to + " as it is.");
            return;
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
        say("Copied " + to);
    }

    // The hash files the release publishes beside the jar, written from the copy that was
    // just made - so what they describe is the file on this disk.
    private static void writeHash(Path jar, String algorithm, String extension)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] sum = digest.digest(Files.readAllBytes(jar));
            StringBuilder hex = new StringBuilder();
            for (byte b : sum) hex.append(String.format("%02x", b));
            Path file = jar.resolveSibling(jar.getFileName() + "." + extension);
            Files.write(file, (hex + "  " + jar.getFileName() + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8));
            say(algorithm + ": " + hex);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("this JVM has no " + algorithm, ex);
        }
    }

    // A starter config, and only when there is none: JRock writes every setting it knows
    // on its first run, so what belongs here is the header that says what the file is.
    private static Path starterConfig(Path jrock) throws IOException {
        Path config = jrock.resolve("jrock-config.txt");
        if (Files.exists(config)) {
            say("Kept the settings already in " + config);
            return config;
        }
        String text = String.join(System.lineSeparator(),
                "' JRock settings - https://github.com/ivan-khvostishkov/jrock",
                "'",
                "' A setting is a name ending in $ on one line, its value on the next.",
                "' Leading and trailing spaces are dropped. Blank lines, and lines starting",
                "' with ' (a single quote), are ignored.",
                "'",
                "' JRock rewrites this whole file when you press OK in Configure, so what",
                "' it holds then is every setting it knows, with the values in effect.",
                "'",
                "endpoint$",
                "https://bedrock-mantle.us-east-1.amazonaws.com/v1/chat/completions",
                "model$",
                "openai.gpt-oss-120b-1:0",
                "images-dpi$",
                "203",
                "");
        Files.write(config, text.getBytes(StandardCharsets.UTF_8));
        say("Wrote a starter " + config);
        return config;
    }

    private static Path starterKey(Path jrock) throws IOException {
        Path key = jrock.resolve("bedrock-key.txt");
        if (Files.exists(key)) {
            say("A " + key + " is already there and was left alone.");
            return key;
        }
        Files.write(key, new byte[0]);
        say("Created an empty " + key);
        return key;
    }

    private static boolean isBlank(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).isBlank();
        } catch (IOException ex) {
            return true;
        }
    }

    // ---- Shortcuts -------------------------------------------------------------
    // One shortcut that starts JRock in the install folder. A .lnk is a COM object, so
    // PowerShell makes it - the one way to write a real shortcut without a native
    // library. Elsewhere it is a .desktop file or a small .command script, which is what
    // those desktops use.
    private static Path shortcutFile(Path dir) {
        if (dir == null) return null;
        if (isWindows()) return dir.resolve(SHORTCUT + ".lnk");
        if (isMac()) return dir.resolve(SHORTCUT + ".command");
        return dir.resolve("jrock-assistant.desktop");
    }

    private static void shortcut(Path dir, boolean menu) {
        if (dir == null) {
            say("No " + (menu ? "Start menu" : "Desktop") + " folder found; no shortcut.");
            return;
        }
        try {
            Files.createDirectories(dir);
            Path jar = home.resolve(JAR);
            Path file = shortcutFile(dir);
            if (isWindows()) {
                String ps = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
                        + file + "'); "
                        + "$s.TargetPath = '" + launcher() + "'; "
                        + "$s.Arguments = '-jar \"" + jar + "\"'; "
                        + "$s.WorkingDirectory = '" + home + "'; "
                        + "$s.Description = 'JRock in " + home.getFileName() + "'; "
                        + "$s.Save()";
                List<String> out = run("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", ps);
                say(Files.isRegularFile(file) ? "Shortcut: " + file
                        : "Could not write " + file + " " + out);
                return;
            }
            if (isMac()) {
                Files.write(file, ("#!/bin/sh\ncd \"" + home + "\"\nexec \"" + launcher()
                        + "\" -jar \"" + jar + "\"\n").getBytes(StandardCharsets.UTF_8));
                file.toFile().setExecutable(true);
                say("Launcher: " + file);
                return;
            }
            Files.write(file, ("[Desktop Entry]\nType=Application\nName=" + SHORTCUT
                    + "\nComment=JRock in " + home.getFileName() + "\nExec=\"" + launcher()
                    + "\" -jar \"" + jar + "\"\nPath=" + home + "\nTerminal=false\n"
                    + "Categories=Development;Utility;\n").getBytes(StandardCharsets.UTF_8));
            file.toFile().setExecutable(true);
            say("Launcher: " + file);
        } catch (IOException ex) {
            say("Could not write the " + (menu ? "Start menu" : "Desktop") + " shortcut: "
                    + ex.getMessage());
        }
    }

    // Unticked means gone: a shortcut this installer would have written, taken away when
    // it is already there. Only that one file, by its exact name - nothing else in the
    // Start menu or on the Desktop is any of this installer's business.
    private static void removeShortcut(Path dir, boolean menu) {
        Path file = shortcutFile(dir);
        if (file == null || !Files.exists(file)) return;
        try {
            Files.delete(file);
            say("Removed the " + (menu ? "Start menu" : "Desktop") + " shortcut " + file);
        } catch (IOException ex) {
            say("Could not remove " + file + ": " + ex.getMessage());
        }
    }

    private static Path startMenuDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Paths.get(appData,
                    "Microsoft", "Windows", "Start Menu", "Programs", "NoSocial.Net");
        }
        if (isMac()) return Paths.get(System.getProperty("user.home"), "Applications");
        return Paths.get(System.getProperty("user.home"), ".local", "share", "applications");
    }

    private static Path desktopDir() {
        Path desktopFolder = Paths.get(System.getProperty("user.home"), "Desktop");
        return Files.isDirectory(desktopFolder) ? desktopFolder : null;
    }

    // ---- Explorer entries ------------------------------------------------------
    // Every JRock right-click entry on this machine, wherever it is and whoever wrote it:
    // {full key, key under its root with the hive short name, its label}.
    private static List<String[]> existingEntries() {
        List<String[]> entries = new ArrayList<>();
        if (!isWindows()) return entries;
        for (String root : ALL_SHELL_ROOTS) {
            for (String key : regKeys(root)) {
                String name = key.toLowerCase(Locale.ROOT);
                if (!name.startsWith(CTX_KEY.toLowerCase(Locale.ROOT))
                        && !name.startsWith(TXT_KEY.toLowerCase(Locale.ROOT))
                        && !name.startsWith(AGENT_KEY_PREFIX.toLowerCase(Locale.ROOT))
                        && !name.startsWith(FLOW_KEY_PREFIX.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String full = root + "\\" + key;
                String label = regDefault(full);
                entries.add(new String[] { full, full, label == null ? key : label });
            }
        }
        return entries;
    }

    // Away with all of them - but written down first. A .reg holding exactly what was
    // there goes into the install folder, and importing it puts every one of these
    // entries back as it was, which is what makes this safe to do without asking twice.
    private static String removeEntries(List<String[]> entries) {
        if (entries.isEmpty()) return null;
        StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
        for (String[] e : entries) {
            String key = fullKey(e[0]);
            String label = regDefault(e[0]);
            String icon = regValue(e[0], "Icon");
            String command = regDefault(e[0] + "\\command");
            reg.append('[').append(key).append("]\r\n");
            if (label != null) reg.append("@=").append(regString(label)).append("\r\n");
            if (icon != null) reg.append("\"Icon\"=").append(regString(icon)).append("\r\n");
            reg.append("\r\n");
            if (command != null) {
                reg.append('[').append(key).append("\\command]\r\n");
                reg.append("@=").append(regString(command)).append("\r\n\r\n");
            }
        }
        try {
            Path backup = writeReg("jrock-context-menus-removed.reg", reg.toString());
            say("What was in the registry is kept at " + backup);
            say("  put it all back with:  reg import \"" + backup + "\"");
        } catch (IOException ex) {
            return "Could not write the backup of the entries: " + ex.getMessage();
        }
        int gone = 0;
        List<String> stuck = new ArrayList<>();
        for (String[] e : entries) {
            List<String> out = run("reg.exe", "delete", e[0], "/f");
            if (regDefault(e[0]) == null && regDefault(e[0] + "\\command") == null) {
                gone++;
                say("Removed " + e[0]);
            } else {
                stuck.add(e[0] + " " + String.join(" ", out));
            }
        }
        say("Removed " + gone + " JRock Explorer entr" + (gone == 1 ? "y" : "ies") + ".");
        if (!stuck.isEmpty()) {
            // An entry under HKEY_LOCAL_MACHINE needs an administrator, and this
            // installer deliberately is not one. Saying which one and carrying on is the
            // useful answer: the install is not held up by a row in the registry that
            // somebody else wrote, and the one place it can be removed from is named.
            say("Left in place, because removing it needs an administrator - start this "
                    + "installer as one, or use regedit:");
            for (String one : stuck) say("  " + one);
        }
        return null;
    }

    // "HKCU\Software\..." as reg.exe takes it -> "HKEY_CURRENT_USER\Software\..." as a
    // .reg file needs it.
    private static String fullKey(String key) {
        if (key.startsWith("HKCU\\")) return "HKEY_CURRENT_USER\\" + key.substring(5);
        if (key.startsWith("HKLM\\")) return "HKEY_LOCAL_MACHINE\\" + key.substring(5);
        return key;
    }

    // The entries this install wants, written in one import.
    private static String installEntries() {
        String javaw = launcher();
        if (javaw == null) return "Could not find javaw.exe beside this JVM.";
        Path jar = home.resolve(JAR);
        StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
        // Every command written, label and all, for the log: this is the one place that
        // says which jar runs from where, with which arguments, under which menu item.
        List<String> wrote = new ArrayList<>();

        // "JRock here!" names NO working folder, and that is the whole of what it means:
        // Explorer starts a right-click command in the folder that was clicked, JRock
        // roots its files in the folder it was started in, and so the folder you clicked
        // is the folder it works in. A --working-dir here would send every click on every
        // folder to one fixed place instead.
        //
        // The prompt library IS named, because nothing else would name it: without the
        // flag, Ctrl+O would open in whichever folder was clicked rather than where the
        // prompts are.
        String here = "\"" + javaw + "\" -jar " + quoted(jar)
                + " --prompts-dir " + quoted(home);
        for (String root : CTX_SHELL_ROOTS) {
            verb(reg, root, CTX_KEY, CTX_LABEL, javaw, here);
        }
        wrote.add("\"" + CTX_LABEL + "\" - on a folder, and on a folder's background");
        wrote.add("    " + here);
        verb(reg, TXT_SHELL_ROOT, TXT_KEY, TXT_LABEL, javaw, here + " \"%1\"");
        wrote.add("\"" + TXT_LABEL + "\" - on a .txt file");
        wrote.add("    " + here + " \"%1\"");

        for (Sample s : selected()) {
            Path dir = s.dir(home);
            // The folder in the label is the INSTALL folder, not the sample's own: it is
            // which installation this entry belongs to that tells two of them apart, and
            // "Timer (Timer)" would say nothing twice.
            String label = s.title() + ": " + s.words() + " (" + home.getFileName() + ")...";
            String key = s.keyPrefix() + keyPart(s.stem) + "-"
                    + keyPart(String.valueOf(home.getFileName())) + "-" + shortHash(home);
            String launch = "\"" + javaw + "\" -cp " + quoted(jar) + " "
                    + quoted(dir.resolve(s.file.getFileName()))
                    + " --working-dir " + quoted(dir)
                    + " --prompts-dir " + quoted(dir);
            for (String root : CTX_SHELL_ROOTS) {
                verb(reg, root, key, label, javaw, launch);
            }
            verb(reg, FILE_SHELL_ROOT, key, label, javaw, launch + " \"%1\"");
            wrote.add("\"" + label + "\" - on a folder, and on any file (with \"%1\")");
            wrote.add("    " + launch);
        }

        try {
            Path regFile = writeReg("jrock-context-menus.reg", reg.toString());
            List<String> out = run("reg.exe", "import", regFile.toString());
            say("Applied " + regFile + " " + (out.isEmpty() ? "" : out));
            say("Explorer entries written, each with the command it runs:");
            for (String line : wrote) say("  " + line);
            return null;
        } catch (IOException ex) {
            return "Could not write the entries: " + ex.getMessage();
        }
    }

    // A .reg file in the install folder's JRock/, in the encoding reg.exe insists on:
    // UTF-16LE with a byte order mark, or it reads the file as one line of nonsense.
    private static Path writeReg(String name, String content) throws IOException {
        Path jrock = home.resolve(PRODUCT);
        Files.createDirectories(jrock);
        Path file = jrock.resolve(name);
        byte[] text = content.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[text.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(text, 0, withBom, 2, text.length);
        Files.write(file, withBom);
        return file;
    }

    private static void verb(StringBuilder reg, String root, String key, String label,
                             String icon, String command) {
        String base = "HKEY_CURRENT_USER\\" + root + "\\" + key;
        reg.append('[').append(base).append("]\r\n");
        reg.append("@=").append(regString(label)).append("\r\n");
        reg.append("\"Icon\"=").append(regString(icon)).append("\r\n\r\n");
        reg.append('[').append(base).append("\\command]\r\n");
        reg.append("@=").append(regString(command)).append("\r\n\r\n");
    }

    private static String regString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // A path as one quoted argument of an installed command line. A trailing backslash -
    // which only a drive root still has - would escape the closing quote when Windows
    // parses the command line, so it is doubled.
    private static String quoted(Path path) {
        String s = path.toString();
        if (s.endsWith("\\")) s = s + "\\";
        return "\"" + s + "\"";
    }

    private static String keyPart(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9_-]", "-");
        return clean.isEmpty() ? "x" : clean;
    }

    // What makes a key unique: two installs in two folders are two sets of entries
    // rather than one set overwritten.
    private static String shortHash(Path dir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] sum = digest.digest(dir.toString().toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format("%02x", sum[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return "x";
        }
    }

    // ---- Running it ------------------------------------------------------------
    // Anything of JRock still running, by the command line that started it. A second
    // JRock in one folder writes the same log and the same prompt file, and the two
    // overwrite each other - so this is asked before anything is started.
    private static List<String> runningJRock() {
        List<String> lines = new ArrayList<>();
        if (isWindows()) {
            // Single quotes only, and a concatenation rather than interpolation: Windows
            // strips the inner double quotes of an argument on the way to a process, and
            // PowerShell then reads what is left as nothing it knows.
            lines.addAll(run("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_Process | ForEach-Object { [string]$_.ProcessId "
                    + "+ '|' + $_.Name + '|' + $_.CommandLine }"));
        } else {
            lines.addAll(run("/bin/sh", "-c", "ps -ax -o pid=,comm=,command= "
                    + "| sed 's/  */|/; s/  */|/'"));
        }
        List<String> running = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            String pid = parts[0].trim();
            String name = parts[1].trim();
            String command = parts[2];
            // A running JRock is a JVM with the jar or a sample on its command line.
            // Nothing else counts: a shell whose current folder happens to be named after
            // JRock, or a text editor with jrock-config.txt open, is not JRock - and this
            // answer is what the last page refuses to go past.
            String program = name.toLowerCase(Locale.ROOT);
            boolean jvm = program.startsWith("java") || program.equals("jrock");
            boolean ours = command.contains(JAR)
                    || command.matches("(?s).*JRock[A-Za-z0-9]*\\.java.*");
            if (!jvm || !ours || command.contains("InstallJRock")) continue;
            Path work = flagIn(command, "--working-dir");
            running.add(name + " " + pid + (work == null || work.getFileName() == null ? ""
                    : " in " + work.getFileName()));
        }
        return running;
    }

    // JRock, and the AGENTS - the ones that loop. A flow is a single run that asks for a
    // document, so starting one here would open a file chooser nobody asked for; its
    // right-click entry is how it is meant to begin.
    private static void launchAll() {
        Path jar = home.resolve(JAR);
        start(List.of(launcher(), "-jar", jar.toString()), home, "JRock");
        say("Started JRock in " + home);
        for (Sample s : selectedOfKind(Kind.AGENT)) {
            Path dir = s.dir(home);
            start(List.of(launcher(), "-cp", jar.toString(),
                    dir.resolve(s.file.getFileName()).toString(),
                    "--working-dir", dir.toString(),
                    "--prompts-dir", dir.toString()), dir, s.words());
            say("Started " + s.words() + " in " + dir);
        }
        List<Sample> flows = selectedOfKind(Kind.FLOW);
        if (!flows.isEmpty()) {
            say(flows.size() + " flow(s) installed and not started: each one runs when you "
                    + "pick it from a right-click menu.");
        }
    }

    private static void start(List<String> command, Path in, String what) {
        try {
            new ProcessBuilder(command).directory(in.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            say("Could not start " + what + ": " + ex.getMessage());
        }
    }

    // ---- Finding things --------------------------------------------------------
    private static String launcher() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null) return null;
        for (String name : isWindows() ? new String[] { "javaw.exe", "java.exe" }
                                       : new String[] { "java" }) {
            Path at = Paths.get(javaHome, "bin", name);
            if (Files.isRegularFile(at)) return at.toAbsolutePath().toString();
        }
        return null;
    }

    // Ghostscript goes by three names, depending on the platform and the build.
    private static Path ghostscript() {
        for (String name : new String[] { "gs", "gswin64c", "gswin64", "gswin32c" }) {
            Path at = onPath(name);
            if (at != null) return at;
        }
        return null;
    }

    private static Path onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        String[] names = isWindows() ? new String[] { name + ".exe", name + ".cmd", name }
                                     : new String[] { name };
        for (String dir : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) continue;
            for (String candidate : names) {
                Path at = Paths.get(dir).resolve(candidate);
                if (Files.isRegularFile(at)) return at;
            }
        }
        return null;
    }

    private static List<String> run(String... command) {
        List<String> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) out.add(line);
            }
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (IOException ex) {
            // The tool is not there: an empty answer says so.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static void open(Path file) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(file.toFile());
                return;
            }
        } catch (IOException | RuntimeException ex) {
            // Fall through to the platform's own opener.
        }
        if (isWindows()) run("cmd", "/c", "start", "", file.toString());
        else if (isMac()) run("open", file.toString());
        else run("xdg-open", file.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String fmt(long n) {
        return java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(n);
    }

    // ---- The wizard's chrome ---------------------------------------------------
    private static void showWindow() {
        window = new javax.swing.JFrame("JRock installer");
        window.setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) {
            base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);
        }

        heading = new javax.swing.JLabel(" ");
        heading.setFont(base.deriveFont(java.awt.Font.BOLD, 16f));
        heading.setForeground(BRAND);
        heading.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 10, 0));

        content = new javax.swing.JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setOpaque(false);
        // Vertical only. A page that scrolls sideways as well has to be steered in two
        // directions to be read, and every line on these pages is written to fit.
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(content,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new java.awt.Dimension(TEXT_WIDTH + 40, 360));

        // Wrapped, so a long path reads on the next line instead of behind a horizontal
        // scrollbar - and every path in this log is long.
        log = new javax.swing.JTextArea(7, 70);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        javax.swing.JScrollPane logScroll = new javax.swing.JScrollPane(log,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        logScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("What was done"));

        back = new javax.swing.JButton("< Back");
        back.addActionListener(e -> {
            if (at > 0) {
                at--;
                redraw();
            }
        });
        next = new javax.swing.JButton("Next >");
        next.addActionListener(e -> {
            Step step = steps.get(at);
            next.setEnabled(false);
            String problem = null;
            try {
                problem = step.onNext.get();
            } catch (RuntimeException ex) {
                problem = String.valueOf(ex.getMessage());
            } finally {
                next.setEnabled(true);
            }
            if (problem != null) {
                say("Stayed on \"" + step.title + "\": " + problem);
                javax.swing.JOptionPane.showMessageDialog(window, problem, step.title,
                        javax.swing.JOptionPane.WARNING_MESSAGE);
                redraw();
                return;
            }
            if (at < steps.size() - 1) {
                at++;
                redraw();
            }
        });
        cancel = new javax.swing.JButton("Close");
        cancel.addActionListener(e -> window.dispose());

        // Close on the left, away from the pair that moves the wizard: the two buttons
        // you press forty times should not be beside the one that ends it.
        javax.swing.JPanel forward = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        forward.setOpaque(false);
        forward.add(back);
        forward.add(next);
        javax.swing.JPanel buttons = new javax.swing.JPanel(new java.awt.BorderLayout());
        buttons.setOpaque(false);
        buttons.add(cancel, java.awt.BorderLayout.WEST);
        buttons.add(forward, java.awt.BorderLayout.EAST);

        javax.swing.JPanel all = new javax.swing.JPanel(new java.awt.BorderLayout());
        all.setBackground(BACKGROUND);
        all.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 14, 18));
        all.add(heading, java.awt.BorderLayout.NORTH);
        all.add(scroll, java.awt.BorderLayout.CENTER);

        javax.swing.JPanel south = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
        south.setOpaque(false);
        south.add(logScroll, java.awt.BorderLayout.CENTER);
        south.add(buttons, java.awt.BorderLayout.SOUTH);
        all.add(south, java.awt.BorderLayout.SOUTH);

        window.setContentPane(all);
        window.pack();
        window.setMinimumSize(window.getSize());
        window.setLocationRelativeTo(null);
        redraw();
        window.setVisible(true);
    }

    // The page as it stands now - rebuilt rather than patched, because a tick on one of
    // them changes what the rest of the page says.
    private static void redraw() {
        Step step = steps.get(at);
        heading.setText((at + 1) + " of " + steps.size() + "   \u00b7   " + step.title);
        content.removeAll();
        step.build.accept(content);
        content.add(javax.swing.Box.createVerticalGlue());
        content.revalidate();
        content.repaint();
        back.setEnabled(at > 0);
        next.setText(step.nextLabel);
    }

    // Said twice: into the window's log, and onto standard output - which is what
    // --check and a run from a terminal read, and what is left when there is no window
    // at all (the log is null until one is built).
    private static void say(String line) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (log == null) return;
            log.append(line + System.lineSeparator());
            log.setCaretPosition(log.getDocument().getLength());
        });
        System.out.println(line);
    }

    // ---- Small widgets ---------------------------------------------------------
    private static javax.swing.JComponent text(String words) {
        return paragraph(words, null, 0, 8);
    }

    private static javax.swing.JComponent note(String words) {
        return paragraph(words, MUTED, 8, 8);
    }

    private static javax.swing.JComponent alarm(String words) {
        return paragraph(words, ALARM, 6, 2);
    }

    private static javax.swing.JComponent paragraph(String words, java.awt.Color colour,
                                                    int above, int below) {
        javax.swing.JLabel label = new javax.swing.JLabel("<html><div style='width:"
                + TEXT_WIDTH + "px'>" + escape(words) + "</div></html>");
        if (colour != null) label.setForeground(colour);
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(above, 0, below, 0));
        return label;
    }

    // A path, a label, a command: monospaced, and broken onto further lines when it is
    // longer than the page. A path is one long word, so nothing else would break it - and
    // a path with its end cut off is no use to anybody.
    private static javax.swing.JComponent value(String words) {
        javax.swing.JLabel label = new javax.swing.JLabel(broken(words, 94));
        label.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 10, 1, 0));
        return label;
    }

    // Breaks at a separator where there is one near the end of the line, and anywhere
    // when there is not, which is what keeps a folder name whole.
    private static String broken(String text, int width) {
        StringBuilder html = new StringBuilder("<html>");
        int from = 0;
        while (text.length() - from > width) {
            int cut = -1;
            for (int i = from + width; i > from + width / 2; i--) {
                char c = text.charAt(i);
                if (c == '\\' || c == '/' || c == ' ') {
                    cut = i;
                    break;
                }
            }
            if (cut < 0) cut = from + width;
            html.append(escape(text.substring(from, cut + 1))).append("<br>&nbsp;&nbsp;");
            from = cut + 1;
        }
        return html.append(escape(text.substring(from))).append("</html>").toString();
    }

    private static javax.swing.JComponent found(boolean yes, String what) {
        javax.swing.JLabel label = new javax.swing.JLabel((yes ? "\u2713  " : "\u2717  ")
                + escape(what));
        label.setForeground(yes ? BRAND : ALARM);
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 0));
        return label;
    }

    private static javax.swing.JComponent check(String label, boolean on,
                                                java.util.function.Consumer<Boolean> set) {
        javax.swing.JCheckBox box = new javax.swing.JCheckBox(label, on);
        box.setOpaque(false);
        box.setAlignmentX(0f);
        box.addActionListener(e -> set.accept(box.isSelected()));
        return box;
    }

    private static javax.swing.JComponent radio(String label, boolean on,
                                                java.util.function.Consumer<Boolean> set) {
        javax.swing.JRadioButton button = new javax.swing.JRadioButton(label, on);
        button.setOpaque(false);
        button.setAlignmentX(0f);
        button.addActionListener(e -> set.accept(true));
        return button;
    }

    private static javax.swing.JComponent link(String label, String url) {
        javax.swing.JButton button = new javax.swing.JButton(label + " - " + url);
        button.setAlignmentX(0f);
        button.addActionListener(e -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
                    return;
                }
            } catch (IOException | RuntimeException ex) {
                // Fall through.
            }
            if (isWindows()) run("rundll32", "url.dll,FileProtocolHandler", url);
            else if (isMac()) run("open", url);
            else run("xdg-open", url);
        });
        return button;
    }

    // What the install folder will hold, as a folder tree - the one picture that answers
    // "where does all this go" without a paragraph about it.
    private static javax.swing.JComponent tree() {
        String name = String.valueOf(home.getFileName());
        javax.swing.JTextArea area = new javax.swing.JTextArea(String.join("\n",
                name + "\\                       " + JAR + ", its SHA-256 and MD5",
                "    " + PRODUCT + "\\                 the window's own files",
                "    jrock-prompt-*.txt      the prompt library",
                "    " + AGENTS_DIR + "\\<name>\\         one agent, its prompts, its own "
                        + PRODUCT + "\\",
                "    " + FLOWS_DIR + "\\                 every flow, their prompts, one "
                        + PRODUCT + "\\"));
        area.setEditable(false);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        area.setForeground(MUTED);
        area.setOpaque(false);
        area.setAlignmentX(0f);
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 8, 0));
        return area;
    }

    // A folder, typed or browsed to.
    private static javax.swing.JComponent folderRow(java.util.function.Supplier<Path> get,
                                                    java.util.function.Consumer<Path> set,
                                                    String title) {
        javax.swing.JTextField field = new javax.swing.JTextField(get.get().toString(), 48);
        field.addActionListener(e -> {
            set.accept(Paths.get(field.getText().trim()));
            redraw();
        });
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                String typed = field.getText().trim();
                if (!typed.isEmpty()) set.accept(Paths.get(typed));
            }
        });
        javax.swing.JButton browse = new javax.swing.JButton("Browse\u2026");
        browse.addActionListener(e -> {
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(get.get().toFile());
            chooser.setDialogTitle(title);
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) {
                Path picked = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                set.accept(picked);
                field.setText(picked.toString());
                redraw();
            }
        });
        javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(0f);
        row.add(field, java.awt.BorderLayout.CENTER);
        row.add(browse, java.awt.BorderLayout.EAST);
        row.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 8, 0));
        return row;
    }

    // One row per sample, in four columns that line up: install it or not, which kind it
    // is, how many prompt files it brings, and the folder it works in.
    //
    // The folder can be typed in only where typing in it would mean something. An agent
    // must have a folder to itself, so it gets one to edit, filled in with
    // Agents\<its name> - which is the answer almost everybody wants. A flow shares
    // Flows\ with the others, so its cell says so and nothing more. No dropdown offering
    // a choice that is not a choice.
    private static javax.swing.JComponent sampleTable() {
        javax.swing.JPanel table = new javax.swing.JPanel(new java.awt.GridBagLayout());
        table.setOpaque(false);
        table.setAlignmentX(0f);
        java.awt.GridBagConstraints at = new java.awt.GridBagConstraints();
        at.anchor = java.awt.GridBagConstraints.WEST;
        at.insets = new java.awt.Insets(1, 2, 1, 6);
        int row = 0;
        for (Sample s : found) {
            javax.swing.JCheckBox box = new javax.swing.JCheckBox(s.words(), s.selected);
            box.setOpaque(false);
            box.addActionListener(e -> s.selected = box.isSelected());
            at.gridx = 0;
            at.gridy = row;
            at.weightx = 0;
            at.fill = java.awt.GridBagConstraints.NONE;
            table.add(box, at);

            javax.swing.JLabel kind = new javax.swing.JLabel(s.loops()
                    ? "agent \u00b7 loops" : "flow \u00b7 one run");
            kind.setForeground(s.loops() ? BRAND : MUTED);
            at.gridx = 1;
            table.add(kind, at);

            javax.swing.JLabel prompts = new javax.swing.JLabel(s.prompts.isEmpty()
                    ? "\u2013" : s.prompts.size() + " prompt"
                    + (s.prompts.size() == 1 ? "" : "s"));
            prompts.setForeground(MUTED);
            at.gridx = 2;
            table.add(prompts, at);

            at.gridx = 3;
            at.weightx = 1;
            at.fill = java.awt.GridBagConstraints.HORIZONTAL;
            table.add(s.loops() ? ownFolderCell(s) : sharedFolderCell(), at);
            row++;
        }
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Agents and flows"));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // As tall as it needs, up to nine rows, and never taller: a fixed height that
        // showed four and a half rows was most of what made this page a maze.
        int height = Math.min(found.size(), 9) * 30 + 18;
        scroll.setPreferredSize(new java.awt.Dimension(TEXT_WIDTH, height));
        scroll.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, height));
        scroll.setAlignmentX(0f);
        return scroll;
    }

    // An agent's own folder: editable, defaulted, and browsable.
    private static javax.swing.JComponent ownFolderCell(Sample s) {
        javax.swing.JTextField field = new javax.swing.JTextField(s.dir(home).toString());
        field.setToolTipText(s.words() + " loops, and runs a JRock of its own, so it needs "
                + "a folder of its own");
        java.util.function.Consumer<String> take = typed -> {
            if (!typed.isBlank()) s.own = Paths.get(typed.trim());
        };
        field.addActionListener(e -> take.accept(field.getText()));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                take.accept(field.getText());
            }
        });
        javax.swing.JButton browse = new javax.swing.JButton("\u2026");
        browse.setMargin(new java.awt.Insets(1, 6, 1, 6));
        browse.addActionListener(e -> {
            Path start = s.dir(home).getParent();
            javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(
                    start == null ? home.toFile() : start.toFile());
            chooser.setDialogTitle("Folder for " + s.words());
            chooser.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(window) == javax.swing.JFileChooser.APPROVE_OPTION) {
                s.own = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
                field.setText(s.own.toString());
            }
        });
        javax.swing.JPanel cell = new javax.swing.JPanel(new java.awt.BorderLayout(4, 0));
        cell.setOpaque(false);
        cell.add(field, java.awt.BorderLayout.CENTER);
        cell.add(browse, java.awt.BorderLayout.EAST);
        return cell;
    }

    // A flow's folder: the one every flow shares, and nothing to decide.
    private static javax.swing.JComponent sharedFolderCell() {
        javax.swing.JLabel label = new javax.swing.JLabel(FLOWS_DIR + "\\  (shared)");
        label.setForeground(MUTED);
        label.setToolTipText("A flow is one run you start yourself, so the flows share a "
                + "folder");
        return label;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
