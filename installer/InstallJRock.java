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
//      names, and - if you want them - the prompts that belong to no sample at all. With
//      something installed already it offers THAT and nothing else: a second run is
//      normally a new jar, not a new argument about which agents;
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
// yours - with one exception that is not a loss: an EMPTY jrock-config.txt or
// bedrock-key.txt in an agent's folder is written over, a blank file being nobody's
// setting and exactly what an install done before the key was typed leaves behind.
// Nothing is deleted when you install somewhere else, and nothing is deleted when
// you install again.
//
// What it MAY remove is two kinds of pointer, and only when you say so: its own Explorer
// right-click entries (page 8 clears every JRock entry on the machine before writing the
// new ones, and clears them even when you install none) and its own shortcuts (page 3,
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

    // THE START SCRIPT, written beside the jar: one thing that starts the whole
    // installation - JRock and every agent installed with it, each in its own folder.
    //
    // Why a script rather than this installer starting nine processes itself. The day
    // after the install, "start all of it" is still something the operator wants, and
    // starting it twice is the one thing they must not do: two JRocks in one folder write
    // the same log and the same prompt file over each other. A script can look first,
    // every time, for ever; an installer can only look the once.
    //
    // Two files on Windows, and the pair is deliberate: the .ps1 holds the thinking,
    // because asking Windows what is running is a WMI query and that is PowerShell's job,
    // and the .cmd is the thing a person can double-click in Explorer.
    private static final String START_PS1 = "JRockStart.ps1";
    private static final String START_CMD = "JRockStart.cmd";
    private static final String START_SH = "jrock-start.sh";

    // What the artifact must hold, beside this installer.
    private static final String JAR = "jrock.jar";
    private static final String SAMPLES = "samples";
    private static final String PROMPTS_SUB = "prompts";
    private static final String AGENTS_SUB = "agents";
    private static final String FLOWS_SUB = "flows";

    // Where to send anybody missing the two outside tools.
    private static final String GS_URL = "https://ghostscript.com/releases/gsdnld.html";
    private static final String XPDF_URL = "https://www.xpdfreader.com/download.html";
    private static final String SQLITE_URL = "https://sqlite.org/download.html";

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

    // Samples that have been renamed. An install of the old name IS an install of the new
    // one, so a rename does not read as one agent gone and another appeared - which on a
    // page that offers only what is installed would hide the new one altogether. The
    // folder the old name was working in is kept, so its settings and its log carry over.
    private static final Map<String, String> RENAMED = Map.of(
            "JRockCalendarEvents", "JRockThunderbird",
            "JRockLetters", "JRockPapers");

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
    // Run everything at login. Unlike the other two this does NOT default to yes: adding
    // an autostart entry is a decision about somebody's machine, and the honest default
    // is whatever they chose last time (see main, where it is read off the disk).
    private static boolean startup;
    private static boolean withSamples = true;
    private static boolean customise;
    // Something is installed already, so this run is an UPDATE of it: the page offers
    // what is there and not the whole catalogue. Turned off by the operator asking for
    // the full list, which is the only way to add something that is not installed yet.
    private static boolean updating;
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
    private static Page content;
    private static javax.swing.JLabel heading;
    private static javax.swing.JTextArea log;
    private static javax.swing.JButton back, next, cancel;
    private static final List<Step> steps = new ArrayList<>();
    private static int at;

    // THE PAGE ITSELF, and the two things about it that have to be true.
    //
    // It tracks the viewport's WIDTH instead of keeping a preferred width of its own. A
    // plain JPanel in a scroll pane keeps its own preferred width, and when that is wider
    // than the window the right-hand side is simply cut off - with no horizontal
    // scrollbar, which this wizard deliberately has none of, there is no way to reach it.
    // That is where the Browse... button went: off the right edge of a row that was wider
    // than the window it was in.
    //
    // It does NOT track the height: a long page is meant to scroll.
    private static final class Page extends javax.swing.JPanel
            implements javax.swing.Scrollable {
        Page() {
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setOpaque(false);
        }

        @Override public java.awt.Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(java.awt.Rectangle visible,
                                                        int orientation, int direction) {
            return 16;
        }

        @Override public int getScrollableBlockIncrement(java.awt.Rectangle visible,
                                                         int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? visible.height : visible.width;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    // One row of a page, at its natural height and aligned to the left margin.
    //
    // BoxLayout hands leftover vertical space to whatever will take it, and every Swing
    // component will: a one-line text field stretched to four times its height, with the
    // glue at the end of the page getting a fraction of what was going spare, is what
    // that looks like. Capping the maximum height at the preferred one sends all of the
    // slack to the glue, which is what it is for.
    private static <T extends javax.swing.JComponent> T row(T component) {
        component.setAlignmentX(0f);
        component.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                component.getPreferredSize().height));
        return component;
    }

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
        System.out.println("sqlite3:             " + or(onPath("sqlite3"), "NOT on PATH")
                + "   (the Thunderbird agent's)");
        System.out.println("javaw/java:          " + or(launcher(), "NOT found"));
        System.out.println("Previous install:    "
                + (previouslyInstalled.isEmpty() && home == null ? "none found"
                   : "folder " + home));
        if (!previouslyInstalled.isEmpty()) {
            System.out.println("  installed already: " + previouslyInstalled.size()
                    + " (page 4 would offer these, and only these)");
            for (Map.Entry<String, Path> one : previouslyInstalled.entrySet()) {
                System.out.println("    " + one.getKey()
                        + (one.getValue() == null ? "" : "  working in " + one.getValue()));
            }
            for (String stem : missingFromArtifact()) {
                System.out.println("    " + stem + "  NOT in this artifact");
            }
        }
        if (isWindows()) {
            List<String[]> entries = existingEntries();
            System.out.println("JRock entries now:   " + entryTitles(entries).size()
                    + " in " + entries.size() + " registry key(s)"
                    + (entries.isEmpty() ? "" : " (page 8 would clear them first)"));
            for (String[] e : entries) System.out.println("  " + e[0]);
        }
        System.out.println("Running JRock:       " + or(runningList(runningJRock()), "none"));
        Path script = home == null ? null : home.resolve(isWindows() ? START_CMD : START_SH);
        System.out.println("Start script:        " + (script == null ? "(no folder yet)"
                : script + (Files.isRegularFile(script) ? "" : "  - not written yet")));
        Path login = shortcutFile(Place.STARTUP);
        System.out.println("At login:            " + (login == null ? "not offered here"
                : Files.exists(login) ? login : "no (" + login.getParent() + " has none)"));
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

    // The one sample this installer asks about by name: the tools it needs are not the
    // tools anything else here needs.
    private static final String THUNDERBIRD = "JRockThunderbird";

    private static boolean installing(String stem) {
        for (Sample s : selected()) if (s.stem.equals(stem)) return true;
        return false;
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

    // What the Explorer entries say is installed, in the order the samples come in.
    private static List<Sample> installed() {
        List<Sample> some = new ArrayList<>();
        for (Sample s : found) if (previouslyInstalled.containsKey(s.stem)) some.add(s);
        return some;
    }

    // Installed, and not in this artifact at all: an agent from an older release, or one
    // that was renamed. Named on the page rather than passed over, because page 8 clears
    // every JRock entry and this one has nothing to write back - its files are left
    // exactly where they are, as everything of yours is.
    private static List<String> missingFromArtifact() {
        Set<String> here = new LinkedHashSet<>();
        for (Sample s : found) here.add(s.stem);
        List<String> gone = new ArrayList<>();
        for (String stem : previouslyInstalled.keySet()) {
            if (!here.contains(stem)) gone.add(stem);
        }
        return gone;
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
    //
    // EVERY shell root is read, not the one this installer writes an agent's file verb
    // to. An entry can be in any of them - an older release wrote the same agent to
    // three, JRock's own window menu wrote them before that, and a hand-made one can be
    // anywhere - and an installed agent that was looked for in one place and missed would
    // come back as "not installed", which is how you end up offering somebody a fresh
    // install of what they already have.
    private static void previousInstall() {
        if (!isWindows()) return;
        String here = regDefault("HKCU\\" + CTX_SHELL_ROOTS[0] + "\\" + CTX_KEY + "\\command");
        if (here != null) {
            Path jar = jarIn(here);
            if (jar != null && jar.getParent() != null) home = jar.getParent();
        }
        Pattern java = Pattern.compile("\"([^\"]*JRock[A-Za-z0-9]*\\.java)\"");
        for (String root : ALL_SHELL_ROOTS) {
            for (String key : regKeys(root)) {
                if (!key.startsWith(AGENT_KEY_PREFIX) && !key.startsWith(FLOW_KEY_PREFIX)) {
                    continue;
                }
                String command = regDefault(root + "\\" + key + "\\command");
                if (command == null) continue;
                Matcher m = java.matcher(command);
                if (m.find()) {
                    String stem = Paths.get(m.group(1)).getFileName().toString()
                            .replaceAll("\\.java$", "");
                    stem = RENAMED.getOrDefault(stem, stem);
                    // First entry found wins the folder: the same agent in two roots is
                    // one install written twice, with the same command in both.
                    previouslyInstalled.putIfAbsent(stem, flagIn(command, "--working-dir"));
                }
                if (home == null) {
                    Path jar = jarIn(command);
                    if (jar != null && jar.getParent() != null) home = jar.getParent();
                }
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
        // The login entry: on if it is there, off if it is not. Never on by default - an
        // installer that adds an autostart entry nobody asked for is an installer people
        // uninstall - and never off by surprise either, which is why it is read rather
        // than assumed.
        Path login = shortcutFile(Place.STARTUP);
        startup = login != null && Files.exists(login);
        Path userHome = Paths.get(System.getProperty("user.home", "."));
        Path documents = Files.isDirectory(userHome.resolve("Documents"))
                ? userHome.resolve("Documents") : userHome;
        if (home == null) home = documents.resolve(ASSISTANT);
        if (!previouslyInstalled.isEmpty()) {
            // Installing again is UPDATING: what is installed is what is ticked, each
            // agent in the folder it was given, and the rest of the catalogue is not
            // offered at all. Somebody who has chosen their agents once has chosen; the
            // second run is for the new jar, not for a fresh argument about which
            // agents. The full list is one radio button away when it is wanted.
            for (Sample s : found) {
                s.selected = previouslyInstalled.containsKey(s.stem);
                Path was = previouslyInstalled.get(s.stem);
                if (s.loops() && was != null) s.own = was;
            }
            updating = true;
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
                panel.add(found(true, previouslyInstalled.size() + " agent(s) and flow(s) "
                        + "already installed: " + String.join(", ",
                        previouslyInstalled.keySet())));
                List<Running> running = runningJRock();
                if (!running.isEmpty()) {
                    panel.add(found(false, running.size() + " of them "
                            + (running.size() == 1 ? "is" : "are") + " running right now: "
                            + runningList(running)));
                    panel.add(note("They have to go before the program they run can be "
                            + "replaced: Windows does not allow a file to be overwritten "
                            + "while a process has it open, and jrock.jar is open in every "
                            + "one of them. Nothing of a session is lost by stopping them - "
                            + "JRock writes its log as it goes and autosaves the prompt - "
                            + "and the installer offers this again at the copy itself if "
                            + "you would rather close their windows yourself."));
                    panel.add(action("Stop them all now", () -> {
                        stopJRocks();
                        redraw();
                    }));
                }
                panel.add(note("So this run is an UPDATE of that install: its folder is "
                        + "the folder below, and page 4 offers what is installed rather "
                        + "than the whole catalogue - the full list is one radio button "
                        + "away if you want to change the set. Nothing of your work will "
                        + "be removed."));
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
            // The one check that has to happen before anything is written, because this
            // is the write that a running JRock makes impossible.
            String running = stopFirstIfRunning();
            if (running != null) return running;
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

        // 3. Shortcuts - installed when ticked, and taken away when not.
        steps.add(new Step("Shortcuts", panel -> {
            Path menuAt = shortcutFile(Place.MENU);
            Path deskAt = shortcutFile(Place.DESKTOP);
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
            if (startMenu) shortcut(Place.MENU);
            else removeShortcut(Place.MENU);
            if (desktop) shortcut(Place.DESKTOP);
            else removeShortcut(Place.DESKTOP);
            return null;
        }));

        // 4. Which agents and flows, and where each agent works.
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
            // Already installed: this is an update of exactly that, and the rest of the
            // catalogue is not on the page. Offering it would be offering to install
            // what somebody has already decided about - and the usual reason for running
            // this installer twice is a new release of the same jar, not a new agent.
            if (updating) {
                List<Sample> installed = installed();
                panel.add(text(installed.size() + " of them " + (installed.size() == 1
                        ? "is" : "are") + " installed already, which the Explorer entries "
                        + "say rather than this installer guessing. This updates those, "
                        + "and only those:"));
                panel.add(sampleTable(installed));
                for (String stem : missingFromArtifact()) {
                    panel.add(alarm("Installed, but not in this artifact: " + stem
                            + " - its right-click entry goes, and its files stay where "
                            + "they are."));
                }
                panel.add(radio("Update these " + installed.size(), true, v -> { }));
                panel.add(radio("Show all " + found.size() + " and let me change the set",
                        false, v -> {
                            updating = false;
                            redraw();
                        }));
            } else {
                panel.add(radio("Install all " + found.size() + " of them", !customise,
                        v -> {
                            customise = !v;
                            redraw();
                        }));
                panel.add(radio("Choose which, and where each agent works", customise,
                        v -> {
                            customise = v;
                            redraw();
                        }));
                if (customise) panel.add(sampleTable(found));
            }
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
            if (!updating && !customise) {
                for (Sample s : found) s.selected = true;
            }
            if (selected().isEmpty()) return "Nothing is ticked.";
            String problem = copySamples();
            if (problem != null) return problem;
            // Written here rather than at the end, because it is written FROM the set
            // that has just been copied - and because an installer closed before its
            // last page should still leave behind the thing that starts what it put in.
            return writeStartScript();
        }));

        // 5. The outside tools - after the agents, because which ones are wanted
        // depends on which agents are going in.
        steps.add(new Step("Ghostscript, xpdf and sqlite3", panel -> {
            Path gs = ghostscript();
            Path html = onPath("pdftohtml");
            Path txt = onPath("pdftotext");
            boolean mail = installing(THUNDERBIRD);
            Path sqlite = mail ? onPath("sqlite3") : null;
            panel.add(text("JRock converts PDFs with two tools it does not ship, and the "
                    + "Thunderbird agent reads your calendar and mail index with a third. "
                    + "Everything else works without them."));
            panel.add(found(gs != null, "Ghostscript" + (gs == null ? "" : " - " + gs)
                    + " - PDF pages as images, and merging duplex scans"));
            panel.add(found(html != null && txt != null, "xpdf"
                    + (html == null || txt == null ? "" : " - " + html.getParent())
                    + " - PDF as HTML or as text"));
            if (mail) {
                panel.add(found(sqlite != null, "sqlite3"
                        + (sqlite == null ? "" : " - " + sqlite)
                        + " - the Thunderbird agent's calendar and mail index"));
            } else {
                panel.add(note("sqlite3 is not looked for: it belongs to the Thunderbird "
                        + "agent, which is not in the set on the page before this."));
            }
            if (gs == null) panel.add(link("Ghostscript downloads", GS_URL));
            if (html == null || txt == null) {
                panel.add(link("xpdf command line tools", XPDF_URL));
            }
            if (mail && sqlite == null) {
                panel.add(link("sqlite3 command line tools", SQLITE_URL));
            }
            if (gs == null || html == null || txt == null || (mail && sqlite == null)) {
                panel.add(note("Install what is missing, add it to PATH, then press Retry. "
                        + "Or press Next without them: JRock and the agents say which tool "
                        + "is missing when something is asked of them that needs one, and "
                        + "installing it later needs nothing from this installer."));
            }
        }, () -> {
            boolean pdf = ghostscript() != null && onPath("pdftohtml") != null
                    && onPath("pdftotext") != null;
            if (!pdf) {
                toolsSkipped = true;
                say("Ghostscript or xpdf not on PATH: the PDF conversions will say so "
                        + "when asked.");
            }
            if (installing(THUNDERBIRD) && onPath("sqlite3") == null) {
                toolsSkipped = true;
                say("sqlite3 not on PATH: the Thunderbird agent will say so when asked "
                        + "for the calendar or the mail.");
            }
            return null;
        }));

        // 6. The settings, and the key.
        steps.add(new Step("Settings and the key", panel -> {
            panel.add(text("Two files in " + home.resolve(PRODUCT) + ":"));
            panel.add(value("jrock-config.txt - the endpoint, the model, the DPI, the "
                    + "checkboxes"));
            panel.add(value("bedrock-key.txt - the Bedrock API key, and nothing else"));
            panel.add(note("Next opens the config in whatever opens a .txt here. Save and "
                    + "close it, then the key file opens the same way."));
            panel.add(strong("An existing file of either kind is kept as it is - this "
                    + "installer never overwrites your settings."));
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
                if (isBlank(key, true)) {
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
                    + "own reads the settings in its own folder."));
            panel.add(text("NOTHING HERE IS OVERWRITTEN. A file that is already in one of "
                    + "these folders is kept exactly as it is - only a missing or an empty "
                    + "one is filled in. What that means folder by folder, as it stands "
                    + "right now:"));
            panel.add(check("Fill in a missing jrock-config.txt (never replaces one)",
                    copySettings, v -> {
                        copySettings = v;
                        redraw();
                    }));
            panel.add(check("Fill in a missing bedrock-key.txt as well (never replaces one)",
                    copyKey, v -> {
                        copyKey = v;
                        redraw();
                    }));
            Path from = home.resolve(PRODUCT);
            for (Path dir : dirs) {
                panel.add(value(dir.toString()));
                panel.add(fate(dir, from, "jrock-config.txt", copySettings));
                panel.add(fate(dir, from, "bedrock-key.txt", copyKey));
            }
            panel.add(note("An agent cannot ask a model without a key in its own folder. "
                    + "JRock itself never copies a key into a folder it was not typed for, "
                    + "which is why this is a tick and not a given. An EMPTY file is the "
                    + "one exception to \"kept as it is\": a blank jrock-config.txt or "
                    + "bedrock-key.txt is nobody's setting, and is what an install done "
                    + "before the key was typed leaves behind."));
        }, () -> {
            for (Path dir : sampleFolders()) {
                Path jrock = dir.resolve(PRODUCT);
                try {
                    Files.createDirectories(jrock);
                    if (copySettings) copyIfUnset(home.resolve(PRODUCT)
                            .resolve("jrock-config.txt"), jrock.resolve("jrock-config.txt"));
                    if (copyKey) copyIfUnset(home.resolve(PRODUCT)
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
                List<String> titles = entryTitles(already);
                panel.add(alarm(titles.size() + " JRock entr" + (titles.size() == 1
                        ? "y is" : "ies are") + " in the registry now, and "
                        + (contextMenus ? "all of them are removed before the ones above "
                                          + "are written"
                                        : "all of them are removed")));
                for (String title : titles) panel.add(value("  " + title));
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
            panel.add(value("Starts:   " + startScriptName()
                    + ", beside the jar - which starts all of it, or none of it"));
            Path loginAt = shortcutFile(Place.STARTUP);
            if (loginAt != null) {
                panel.add(check("Run JRock and its agents at login", startup, v -> {
                    startup = v;
                    redraw();
                }));
                if (!startup && Files.exists(loginAt)) {
                    panel.add(alarm("Will be removed: " + loginAt));
                }
            }
            List<Running> running = runningJRock();
            if (!running.isEmpty()) {
                panel.add(note("Some of JRock is running: " + runningList(running)
                        + ". The script will name what it finds and ask whether to stop "
                        + "all of it and start again - a no leaves it exactly as it is."));
            } else {
                panel.add(note("Nothing of JRock is running, so the script will start it "
                        + "without a word: JRock in " + home.getFileName()
                        + (selectedOfKind(Kind.AGENT).isEmpty() ? "."
                        : ", and each agent in its own folder. The flows are not started: "
                          + "a flow is one run you ask for, from its right-click entry.")));
            }
        }, () -> {
            if (startup) shortcut(Place.STARTUP);
            else removeShortcut(Place.STARTUP);
            String problem = runStartScript();
            if (problem != null) return problem;
            javax.swing.JOptionPane.showMessageDialog(window,
                    startScriptName() + " is running. This installer is done.\n\n"
                    + "Run it again whenever you want everything started: it looks for "
                    + "what is already there first.", "JRock",
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

    // Yours stays yours: a settings file that already says something is never written
    // over. An EMPTY one says nothing, and is replaced.
    //
    // That is not a detail. The home folder's own bedrock-key.txt starts empty (see
    // starterKey), so an install done before the key was typed leaves an empty key in
    // every agent folder - and keeping those for ever would mean seven agents that
    // cannot ask a model, a tick that looked as if it had worked, and a key that is
    // now sitting in the home folder being copied nowhere. The same goes for a
    // jrock-config.txt that was never written to.
    //
    // Nothing is copied FROM a blank file either: handing an empty key on achieves
    // nothing, and would write over a key that somebody had typed into that folder
    // themselves. A file that cannot be READ counts as one with something in it, because
    // the only thing worse than leaving an empty config in place is replacing a real one.
    // What is about to happen to one file in one folder, said before it happens.
    //
    // Because "Copy jrock-config.txt into each of them" is a sentence somebody can read
    // as "replace the settings of every agent I have set up", and the answer to "will it?"
    // should be on the page rather than in the log afterwards. The same three questions
    // copyIfUnset asks, asked here for the answer instead of for the action.
    private static javax.swing.JComponent fate(Path dir, Path from, String name,
                                               boolean ticked) {
        Path source = from.resolve(name);
        Path target = dir.resolve(PRODUCT).resolve(name);
        String what = "   " + name + " - ";
        if (!ticked) {
            return found(true, what + "not touched (the tick is off)");
        }
        if (!Files.isRegularFile(source) || isBlank(source, true)) {
            return found(false, what + "nothing to copy: " + source + " is "
                    + (Files.exists(source) ? "empty" : "not there"));
        }
        if (Files.exists(target) && !isBlank(target, false)) {
            return found(true, what + "KEPT as it is - this folder has its own");
        }
        return found(true, what + (Files.exists(target)
                ? "filled in, because the file there is empty"
                : "copied, because there is none yet"));
    }

    private static void copyIfUnset(Path from, Path to) throws IOException {
        if (!Files.isRegularFile(from) || isBlank(from, true)) return;
        boolean there = Files.exists(to);
        if (there && !isBlank(to, false)) {
            say("Kept " + to + " as it is.");
            return;
        }
        Files.createDirectories(to.getParent());
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING);
        say(there ? "Copied " + to + ", where the file was empty." : "Copied " + to);
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

    // Whether a file holds nothing but whitespace. whenUnreadable is the answer for a
    // file that cannot be read at all, because the two callers want opposite ones: a key
    // file nobody can read is no key to go on with, while a settings file nobody can read
    // is one to leave exactly where it is.
    private static boolean isBlank(Path file, boolean whenUnreadable) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).isBlank();
        } catch (IOException cannotRead) {
            return whenUnreadable;
        }
    }

    // ---- The start script ------------------------------------------------------
    // Written with the command lines this installer would have used itself, so that what
    // starts the installation tomorrow is what started it today.
    //
    // NOTHING, OR ALL OF IT. Finding some of JRock already running, it names what it
    // found and asks whether to stop all of it and start again: yes is a restart, no
    // leaves the machine exactly as it was. Finding nothing, it starts everything and
    // says nothing at all - which is what it does at login, where there is nobody to
    // tell and nothing to ask.
    //
    // The agents only. A flow is a single run that asks for a document, so starting one
    // would open a file chooser nobody asked for; its right-click entry is how it begins.
    private static String writeStartScript() {
        Path jar = home.resolve(JAR);
        List<String[]> toStart = new ArrayList<>();
        toStart.add(new String[] { PRODUCT, home.toString(), "-jar", jar.toString() });
        for (Sample s : selectedOfKind(Kind.AGENT)) {
            Path dir = s.dir(home);
            toStart.add(new String[] { s.words(), dir.toString(), "-cp", jar.toString(),
                    dir.resolve(s.file.getFileName()).toString(),
                    "--working-dir", dir.toString(), "--prompts-dir", dir.toString() });
        }
        try {
            if (isWindows()) {
                Files.write(home.resolve(START_PS1),
                        windowsStartScript(jar, toStart).getBytes(StandardCharsets.UTF_8));
                Files.write(home.resolve(START_CMD), ("@echo off\r\n"
                        + "rem Starts JRock and its agents - or asks first, if some are "
                        + "already running.\r\n"
                        + "powershell.exe -NoProfile -ExecutionPolicy Bypass -File "
                        + "\"%~dp0" + START_PS1 + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                say("Start script: " + home.resolve(START_CMD) + " (and " + START_PS1 + ")");
            } else {
                Path sh = home.resolve(START_SH);
                Files.write(sh, otherStartScript(jar, toStart).getBytes(StandardCharsets.UTF_8));
                sh.toFile().setExecutable(true);
                say("Start script: " + sh);
            }
            return null;
        } catch (IOException ex) {
            return "Could not write the start script: " + ex.getMessage();
        }
    }

    private static String windowsStartScript(Path jar, List<String[]> toStart) {
        StringBuilder ps = new StringBuilder();
        ps.append("# JRock Assistant: start all of it, or none of it.\r\n")
          .append("# Written by the JRock installer for ").append(home).append("\r\n")
          .append("# Run it again whenever you like - it looks before it starts anything.\r\n\r\n")
          .append("$ErrorActionPreference = 'Stop'\r\n")
          .append("$jar = ").append(psText(jar.toString())).append("\r\n")
          .append("$javaw = ").append(psText(launcher())).append("\r\n\r\n")
          .append("# What to start, in order, each in the folder whose settings it reads.\r\n")
          .append("$toStart = @(\r\n");
        for (String[] one : toStart) {
            ps.append("    @{ What = ").append(psText(one[0]))
              .append("; Dir = ").append(psText(one[1])).append("; Args = @(");
            for (int i = 2; i < one.length; i++) {
                if (i > 2) ps.append(", ");
                ps.append(psArg(one[i]));
            }
            ps.append(") }\r\n");
        }
        ps.append(")\r\n\r\n")
          // WHAT THIS SCRIPT STARTS, and nothing else: a running FLOW names the same jar
          // and must not be caught in a restart. A flow is one run somebody asked for -
          // a document being converted, three model passes deep - and this script would
          // kill it without being able to start it again, because flows are not started
          // here. So the ones that are ours are these, by name, and anything else of
          // JRock's is left exactly as it is.
          .append("$mine = @(").append(startedNames(toStart)).append(")\r\n\r\n")
          // Contains and not -like: a path can hold [ or ], which -like reads as a
          // wildcard, and the question here is whether this exact jar is named.
          .append("$ours = @(Get-CimInstance Win32_Process | Where-Object {\r\n")
          .append("    $_.CommandLine -and $_.CommandLine.Contains($jar) } | Where-Object {\r\n")
          .append("    $name = if ($_.CommandLine -match '([A-Za-z0-9]+)\\.java') "
                  + "{ $matches[1] } else { 'JRock' }\r\n")
          .append("    $mine -contains $name })\r\n")
          .append("$running = @($ours)\r\n")
          .append("if ($running.Count -gt 0) {\r\n")
          .append("    $names = @($running | ForEach-Object {\r\n")
          .append("        if ($_.CommandLine -match '([A-Za-z0-9]+)\\.java') "
                  + "{ $matches[1] } else { 'JRock' }\r\n")
          .append("    } | Sort-Object -Unique) -join ', '\r\n")
          .append("    Add-Type -AssemblyName System.Windows.Forms | Out-Null\r\n")
          .append("    $answer = [System.Windows.Forms.MessageBox]::Show(\r\n")
          .append("        \"This is already running:`r`n`r`n$names`r`n`r`n"
                  + "Stop all of it and start it again?\",\r\n")
          .append("        'JRock Assistant',\r\n")
          .append("        [System.Windows.Forms.MessageBoxButtons]::YesNo,\r\n")
          .append("        [System.Windows.Forms.MessageBoxIcon]::Question)\r\n")
          .append("    if ($answer -ne [System.Windows.Forms.DialogResult]::Yes) { exit 0 }\r\n")
          .append("    $running | ForEach-Object {\r\n")
          .append("        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }\r\n")
          // Long enough for the ports to be given back: an agent that is starting while
          // the one it replaces still holds 47470 cannot listen on it.
          .append("    Start-Sleep -Milliseconds 900\r\n")
          .append("}\r\n\r\n")
          .append("foreach ($one in $toStart) {\r\n")
          .append("    Start-Process -FilePath $javaw -ArgumentList $one.Args "
                  + "-WorkingDirectory $one.Dir | Out-Null\r\n")
          .append("}\r\n")
          .append("exit 0\r\n");
        return ps.toString();
    }

    // The same for a desktop that is not Windows, without the question: there is no one
    // dialog box every Linux has, and a script that kills things after asking in a
    // terminal nobody is looking at would be worse than one that says what it found.
    private static String otherStartScript(Path jar, List<String[]> toStart) {
        StringBuilder sh = new StringBuilder("#!/bin/sh\n")
                .append("# JRock Assistant: start all of it, or none of it.\n")
                .append("# Written by the JRock installer for ").append(home).append("\n\n")
                .append("JAR=\"").append(jar).append("\"\n")
                .append("JAVA=\"").append(launcher()).append("\"\n")
                .append("RUNNING=$(pgrep -f \"$JAR\" | tr '\\n' ' ')\n")
                .append("if [ -n \"$RUNNING\" ]; then\n")
                .append("  echo \"JRock is already running (pids: $RUNNING).\"\n")
                .append("  echo \"Stop it and run this again to restart everything.\"\n")
                .append("  exit 1\n")
                .append("fi\n");
        for (String[] one : toStart) {
            sh.append("( cd \"").append(one[1]).append("\" && \"$JAVA\"");
            for (int i = 2; i < one.length; i++) sh.append(" \"").append(one[i]).append("\"");
            sh.append(" >/dev/null 2>&1 & )   # ").append(one[0]).append("\n");
        }
        return sh.append("exit 0\n").toString();
    }

    // The names this script starts, as PowerShell strings: 'JRock' for the window itself
    // and the class name of each agent, which is what its command line shows.
    private static String startedNames(List<String[]> toStart) {
        StringBuilder names = new StringBuilder(psText(PRODUCT));
        for (String[] one : toStart) {
            for (int i = 2; i < one.length; i++) {
                if (!one[i].endsWith(".java")) continue;
                String file = Paths.get(one[i]).getFileName().toString();
                names.append(", ").append(psText(file.substring(0, file.length() - 5)));
            }
        }
        return names.toString();
    }

    // A PowerShell single-quoted string: the one quote it has to escape is its own.
    private static String psText(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    // One argument for Start-Process -ArgumentList, which joins the list with spaces and
    // quotes nothing - so a path with a space in it has to arrive already quoted.
    private static String psArg(String value) {
        return "'\"" + value.replace("'", "''") + "\"'";
    }

    // ---- Shortcuts -------------------------------------------------------------
    // One shortcut that starts JRock in the install folder. A .lnk is a COM object, so
    // PowerShell makes it - the one way to write a real shortcut without a native
    // library. Elsewhere it is a .desktop file or a small .command script, which is what
    // those desktops use.
    // Three places one can go, and the third points somewhere else: the Start menu and
    // the Desktop start JRock, while the startup entry runs the start SCRIPT - the whole
    // installation, agents included, because "run it at login" means all of it or there
    // is no point to it.
    private enum Place {
        MENU("Start menu"), DESKTOP("Desktop"), STARTUP("startup at login");

        final String words;

        Place(String words) {
            this.words = words;
        }

        /** The folder, or null where this desktop has nowhere to put one. */
        Path dir() {
            if (this == MENU) return startMenuDir();
            if (this == DESKTOP) return desktopDir();
            return startupDir();
        }
    }

    private static Path shortcutFile(Place place) {
        Path dir = place.dir();
        if (dir == null) return null;
        if (isWindows()) return dir.resolve(SHORTCUT + ".lnk");
        if (isMac()) return dir.resolve(SHORTCUT + ".command");
        return dir.resolve("jrock-assistant.desktop");
    }

    private static void shortcut(Place place) {
        Path dir = place.dir();
        if (dir == null) {
            say("No " + place.words + " folder on this desktop; nothing written.");
            return;
        }
        boolean all = place == Place.STARTUP;
        try {
            Files.createDirectories(dir);
            Path jar = home.resolve(JAR);
            Path file = shortcutFile(place);
            String about = (all ? "JRock and its agents in " : "JRock in ")
                    + home.getFileName();
            if (isWindows()) {
                // WindowStyle 7 is minimised, which together with PowerShell's own
                // -WindowStyle Hidden is the difference between a console window
                // appearing at every login and not.
                String ps = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
                        + file + "'); "
                        + "$s.TargetPath = '" + (all ? powershellExe() : launcher()) + "'; "
                        + "$s.Arguments = '" + (all
                            ? "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "
                              + "\"" + home.resolve(START_PS1) + "\""
                            : "-jar \"" + jar + "\"") + "'; "
                        + "$s.WorkingDirectory = '" + home + "'; "
                        + (all ? "$s.WindowStyle = 7; " : "")
                        + "$s.Description = '" + about + "'; "
                        + "$s.Save()";
                List<String> out = run("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", ps);
                say(Files.isRegularFile(file) ? place.words + ": " + file
                        : "Could not write " + file + " " + out);
                return;
            }
            String command = all ? "\"" + home.resolve(START_SH) + "\""
                                 : "\"" + launcher() + "\" -jar \"" + jar + "\"";
            if (isMac()) {
                Files.write(file, ("#!/bin/sh\ncd \"" + home + "\"\nexec " + command + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                file.toFile().setExecutable(true);
                say(place.words + ": " + file);
                return;
            }
            Files.write(file, ("[Desktop Entry]\nType=Application\nName=" + SHORTCUT
                    + "\nComment=" + about + "\nExec=" + command + "\nPath=" + home
                    + "\nTerminal=false\nCategories=Development;Utility;\n")
                    .getBytes(StandardCharsets.UTF_8));
            file.toFile().setExecutable(true);
            say(place.words + ": " + file);
        } catch (IOException ex) {
            say("Could not write the " + place.words + " entry: " + ex.getMessage());
        }
    }

    // Unticked means gone: an entry this installer would have written, taken away when it
    // is already there. Only that one file, by its exact name - nothing else in the Start
    // menu, on the Desktop or in Startup is any of this installer's business.
    private static void removeShortcut(Place place) {
        Path file = shortcutFile(place);
        if (file == null || !Files.exists(file)) return;
        try {
            Files.delete(file);
            say("Removed the " + place.words + " entry " + file);
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

    // Where a desktop starts things at login. Windows reads the Startup folder, and the
    // freedesktop desktops read ~/.config/autostart; a Mac wants a launch agent, which is
    // a plist and a different story, so there the tick is not offered at all.
    private static Path startupDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Paths.get(appData,
                    "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        }
        if (isMac()) return null;
        return Paths.get(System.getProperty("user.home"), ".config", "autostart");
    }

    // PowerShell by its full path, for a shortcut that has to find it with no PATH.
    private static String powershellExe() {
        String root = System.getenv("SystemRoot");
        return root == null ? "powershell.exe"
                : root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
    }

    // ---- Explorer entries ------------------------------------------------------
    // Every JRock right-click entry on this machine, wherever it is and whoever wrote it:
    // {full key, key under its root with the hive short name, its label}.
    // The entries as Explorer shows them: one per label, sorted. The same entry is
    // written under several keys - a folder, a folder's background, any file - and the
    // keys matter to the removal and to --check, not to somebody reading a page.
    private static List<String> entryTitles(List<String[]> entries) {
        java.util.TreeSet<String> titles = new java.util.TreeSet<>();
        for (String[] e : entries) titles.add(e[2]);
        return new ArrayList<>(titles);
    }

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
    // One of them: the pid to stop it by, what the process is called, which sample it is,
    // and the folder it was started in.
    private static final class Running {
        final long pid;
        final String program, sample;
        final Path work;

        Running(long pid, String program, String sample, Path work) {
            this.pid = pid; this.program = program; this.sample = sample; this.work = work;
        }

        String label() {
            return sample + " (" + program + " " + pid
                    + (work == null || work.getFileName() == null ? ""
                       : ", in " + work.getFileName()) + ")";
        }
    }

    private static List<Running> runningJRock() {
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
        List<Running> running = new ArrayList<>();
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
            long number;
            try {
                number = Long.parseLong(pid);
            } catch (NumberFormatException ex) {
                continue;        // not a process this can do anything about
            }
            running.add(new Running(number, name, which(command), work));
        }
        return running;
    }

    // Which JRock a command line is: the sample's own name where it has one, and the
    // program itself where it does not.
    //
    // The source file first, then a class name standing on its own - and NOT a name
    // inside a path, which is the trap here: every one of these command lines has
    // "...\JRockAssistant\..." in it somewhere, so a plain search for JRock-something
    // reported eight processes all called "Assistant". A sample's name is either
    // JRockPapers.java or the bare class JRockPapers at the end of the line; a folder's
    // name has a separator on one side of it.
    private static String which(String command) {
        Matcher file = Pattern.compile("JRock([A-Za-z0-9]+)\\.java").matcher(command);
        if (file.find()) return file.group(1);
        Matcher clazz = Pattern.compile("(?<![\\\\/\\w])JRock([A-Za-z0-9]+)(?![\\\\/\\w.])")
                .matcher(command);
        if (clazz.find()) return clazz.group(1);
        return "JRock";
    }

    private static String runningList(List<Running> running) {
        List<String> labels = new ArrayList<>();
        for (Running one : running) labels.add(one.label());
        return String.join(", ", labels);
    }

    // Stops them, and says how many went.
    //
    // By pid and not by command line, which is the one thing Java can do about another
    // process on every platform: ProcessHandle.of(pid) finds it, destroy() asks it to go
    // and destroyForcibly() stops asking. (The LOOKING is done by a shell above, because
    // on Windows ProcessHandle tells you nothing about a process it did not start -
    // measured here: 294 processes, 0 command lines, including this program's own.)
    //
    // Asked politely first and then not: half a second is long enough to find out whether
    // a Swing program with a modal dialog open is going to answer. Nothing of a session
    // rides on it - JRock writes its log as it goes and autosaves the prompt.
    private static int stopJRocks() {
        List<Running> running = runningJRock();
        if (running.isEmpty()) return 0;
        List<ProcessHandle> handles = new ArrayList<>();
        for (Running one : running) {
            ProcessHandle.of(one.pid).ifPresent(handles::add);
        }
        for (ProcessHandle handle : handles) handle.destroy();
        sleep(500);
        for (ProcessHandle handle : handles) {
            if (handle.isAlive()) handle.destroyForcibly();
        }
        sleep(300);
        int stopped = 0;
        for (ProcessHandle handle : handles) {
            if (!handle.isAlive()) stopped++;
        }
        say("Stopped " + stopped + " of " + running.size() + " running: "
                + runningList(running));
        return stopped;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // The offer, made where it matters: at the copy that cannot be done while they run.
    // Returns the reason to stay on the page, or null to carry on.
    private static String stopFirstIfRunning() {
        List<Running> running = runningJRock();
        if (running.isEmpty()) return null;
        int answer = javax.swing.JOptionPane.showConfirmDialog(window,
                running.size() + " JRock "
                + (running.size() == 1 ? "process is" : "processes are") + " still running:"
                + "\n\n" + runningList(running)
                + "\n\nWindows will not let jrock.jar be replaced while they have it open, "
                + "so this install cannot go on while they do.\n\nStop them now and carry "
                + "on? Nothing of a session is lost - JRock writes its log as it goes and "
                + "autosaves the prompt. They are started again by the start script on the "
                + "last page.",
                "JRock is running", javax.swing.JOptionPane.YES_NO_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (answer != javax.swing.JOptionPane.YES_OPTION) {
            return "Close the running JRocks yourself, then press Next again.";
        }
        stopJRocks();
        List<Running> left = runningJRock();
        if (!left.isEmpty()) {
            return left.size() + " would not stop (" + runningList(left) + "). Close "
                    + "them by hand, then press Next again.";
        }
        return null;
    }

    // JRock, and the AGENTS - the ones that loop. A flow is a single run that asks for a
    // document, so starting one here would open a file chooser nobody asked for; its
    // right-click entry is how it is meant to begin.
    private static String runStartScript() {
        Path script = home.resolve(isWindows() ? START_PS1 : START_SH);
        if (!Files.isRegularFile(script)) {
            String problem = writeStartScript();   // closed the installer and came back
            if (problem != null) return problem;
        }
        List<String> command = isWindows()
                ? List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString())
                : List.of("/bin/sh", script.toString());
        start(command, home, startScriptName());
        say("Ran " + script);
        List<Sample> flows = selectedOfKind(Kind.FLOW);
        if (!flows.isEmpty()) {
            say(flows.size() + " flow(s) installed and not started: each one runs when you "
                    + "pick it from a right-click menu.");
        }
        return null;
    }

    private static String startScriptName() {
        return isWindows() ? START_CMD : START_SH;
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
        List<Path> places = new ArrayList<>();
        for (String dir : path.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) continue;
            try {
                // Quotes are legal in a PATH entry and illegal in a path: that entry is
                // dropped rather than allowed to end the search.
                places.add(Paths.get(dir.replace("\"", "")));
            } catch (java.nio.file.InvalidPathException notAPath) {
                continue;
            }
        }
        // And the two folders a user keeps their own tools in, which PATH may name in a
        // session this program did not inherit.
        Path home = Paths.get(System.getProperty("user.home", "."));
        places.add(home.resolve(".local").resolve("bin"));
        places.add(home.resolve("bin"));
        for (Path dir : places) {
            for (String candidate : names) {
                Path at = dir.resolve(candidate);
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

        content = new Page();
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

    private static javax.swing.JComponent strong(String words) {
        javax.swing.JTextArea area = wrapping(words);
        area.setFont(area.getFont().deriveFont(java.awt.Font.BOLD));
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 8, 0));
        return growing(area);
    }

    private static javax.swing.JComponent alarm(String words) {
        return paragraph(words, ALARM, 6, 2);
    }

    // A PARAGRAPH WRAPS TO THE WIDTH IT HAS, which is why this is a text area and not an
    // HTML label.
    //
    // A label wraps only where its HTML names a width, and a width in HTML is in CSS
    // pixels, which Swing scales by the display: "width:700px" asks for 910 device pixels
    // on a 130% screen. The window is then narrower than the text in it and there is no
    // horizontal scrollbar to reach the rest - page 1 read "...already installed:
    // JRockBrowser, JRockPapers, JRockLocation, JRockPushToTalk, JRockThunderbird, J" and
    // stopped at the window's edge, mid-name.
    //
    // A text area wraps to the width it is actually given, whatever the display is doing,
    // and breaks inside a word when the word is wider than that - which a path, or the
    // tail of a comma-separated list of class names, always is.
    private static javax.swing.JComponent paragraph(String words, java.awt.Color colour,
                                                    int above, int below) {
        javax.swing.JTextArea area = wrapping(words);
        if (colour != null) area.setForeground(colour);
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(above, 0, below, 0));
        return growing(area);
    }

    // The text area every wrapping line on a page is: plain, transparent, out of the Tab
    // order, and carrying the look of a label rather than of a form field.
    //
    // Its maximum height is ANSWERED WHEN ASKED rather than set while the page is built.
    // BoxLayout hands leftover vertical space to whatever will take it, so a maximum is
    // needed - but the height these prefer is a function of the width they are given, and
    // they have none until the page is laid out: a maximum stored at build time freezes
    // the paragraph at the one line it preferred while nobody had told it anything. Asked
    // at layout time, it is the height the words actually need.
    private static javax.swing.JTextArea wrapping(String words) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(words) {
            // THE HEIGHT IS A FUNCTION OF THE WIDTH, and the width is not known until the
            // page is laid out. So both answers are worked out when they are asked for:
            // the area is sized to the width it has been given, which makes the text
            // re-wrap, and the height that wrapping needs is what it then reports.
            //
            // Measured before this: the paragraph reported the height of the single line
            // it preferred while its width was still zero, BoxLayout gave it exactly
            // that, and the second and third lines were drawn below the bottom of it -
            // which is a page whose text is cut off horizontally AND vertically.
            @Override public java.awt.Dimension getPreferredSize() {
                int width = getWidth();
                if (width <= 0 && getParent() != null) width = getParent().getWidth();
                if (width <= 0) return super.getPreferredSize();
                setSize(width, Short.MAX_VALUE);
                java.awt.Dimension wrapped = super.getPreferredSize();
                return new java.awt.Dimension(width, wrapped.height);
            }

            // And the maximum is that same height, so BoxLayout hands the slack at the
            // bottom of the page to the glue instead of stretching a paragraph.
            @Override public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);      // but selectable: a path can still be copied out
        area.setFocusable(false);
        area.setOpaque(false);
        java.awt.Font label = javax.swing.UIManager.getFont("Label.font");
        if (label != null) area.setFont(label);
        return area;
    }

    // A row aligned to the left margin and left to cap its own height, for the components
    // above that answer that question themselves (see wrapping). row() is for everything
    // else: a field or a button knows its height before it is laid out.
    private static <T extends javax.swing.JComponent> T growing(T component) {
        component.setAlignmentX(0f);
        return component;
    }

    // A path, a label, a command: monospaced, and broken onto further lines when it is
    // longer than the page. A path is one long word, so nothing else would break it - and
    // a path with its end cut off is no use to anybody.
    private static javax.swing.JComponent value(String words) {
        javax.swing.JLabel label = new javax.swing.JLabel(broken(words, 94));
        label.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 10, 1, 0));
        return row(label);
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

    // A tick or a cross and what it is about - wrapping, because one of these lines is
    // the names of everything already installed, which is as long as the install is. It
    // was a plain label, which wraps nowhere at all, and on a ten-agent install it ran
    // off the right of the window.
    private static javax.swing.JComponent found(boolean yes, String what) {
        javax.swing.JTextArea area = wrapping((yes ? "\u2713  " : "\u2717  ") + what);
        area.setForeground(yes ? BRAND : ALARM);
        area.setFont(area.getFont().deriveFont(java.awt.Font.BOLD));
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 4, 2, 0));
        return growing(area);
    }

    private static javax.swing.JComponent check(String label, boolean on,
                                                java.util.function.Consumer<Boolean> set) {
        javax.swing.JCheckBox box = new javax.swing.JCheckBox(label, on);
        box.setOpaque(false);
        box.addActionListener(e -> set.accept(box.isSelected()));
        return row(box);
    }

    private static javax.swing.JComponent radio(String label, boolean on,
                                                java.util.function.Consumer<Boolean> set) {
        javax.swing.JRadioButton button = new javax.swing.JRadioButton(label, on);
        button.setOpaque(false);
        button.addActionListener(e -> set.accept(true));
        return row(button);
    }

    // A button that does something on this page rather than opening a link or going on.
    private static javax.swing.JComponent action(String label, Runnable what) {
        javax.swing.JButton button = new javax.swing.JButton(label);
        button.addActionListener(e -> what.run());
        javax.swing.JPanel holder = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.LEFT, 0, 2));
        holder.setOpaque(false);
        holder.add(button);
        return row(holder);
    }

    private static javax.swing.JComponent link(String label, String url) {
        javax.swing.JButton button = new javax.swing.JButton(label + " - " + url);
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
        return row(button);
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
        area.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 10, 8, 0));
        return row(area);
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
        javax.swing.JPanel line = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        line.setOpaque(false);
        line.add(field, java.awt.BorderLayout.CENTER);
        line.add(browse, java.awt.BorderLayout.EAST);
        line.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 0, 8, 0));
        return row(line);
    }

    // One row per sample, in four columns that line up: install it or not, which kind it
    // is, how many prompt files it brings, and the folder it works in.
    //
    // The folder can be typed in only where typing in it would mean something. An agent
    // must have a folder to itself, so it gets one to edit, filled in with
    // Agents\<its name> - which is the answer almost everybody wants. A flow shares
    // Flows\ with the others, so its cell says so and nothing more. No dropdown offering
    // a choice that is not a choice.
    private static javax.swing.JComponent sampleTable(List<Sample> shown) {
        javax.swing.JPanel table = new javax.swing.JPanel(new java.awt.GridBagLayout());
        table.setOpaque(false);
        table.setAlignmentX(0f);
        java.awt.GridBagConstraints at = new java.awt.GridBagConstraints();
        at.anchor = java.awt.GridBagConstraints.WEST;
        at.insets = new java.awt.Insets(1, 2, 1, 6);
        int row = 0;
        for (Sample s : shown) {
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
        int height = Math.min(Math.max(shown.size(), 1), 9) * 30 + 18;
        scroll.setPreferredSize(new java.awt.Dimension(TEXT_WIDTH, height));
        return row(scroll);
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
