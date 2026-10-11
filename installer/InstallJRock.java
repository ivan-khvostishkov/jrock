// JRock's installer: one Swing wizard, one Java source file, no build step.
//
//     java InstallJRock.java            the wizard
//     java InstallJRock.java --check    what it would find, printed; changes nothing
//
// Run it from the folder the release artifact was unpacked into - the one holding
// jrock.jar and agentic-samples/ - which is the first thing it checks.
//
// What it does, in order, and nothing else:
//
//   1. finds jrock.jar and agentic-samples/ beside itself;
//   2. copies jrock.jar into a program folder of your choosing, with its SHA-256 and
//      MD5 beside it, as the release publishes them;
//   3. looks for Ghostscript and xpdf on PATH, and says where to get them;
//   4. takes a primary working folder - whose NAME is what Explorer's right-click
//      entries are labelled with, so it is worth choosing;
//   5. offers Start menu and Desktop shortcuts called "JRock Assistant";
//   6. installs the agents you pick, with the prompt files each one actually reads,
//      and a working folder per agent when you want them kept apart;
//   7. opens the new jrock-config.txt for review, then bedrock-key.txt for the key;
//   8. offers to copy those settings into each agent's own folder;
//   9. installs the Explorer right-click entries - "JRock here!" and one per agent -
//      with exactly the key names and command lines JRock itself writes, so JRock's
//      own Uninstall agent removes what this installed;
//  10. offers to start JRock and the agents, once nothing of JRock is still running.
//
// IT NEVER REMOVES ANYTHING. A jrock.jar already in the program folder is replaced,
// which is what installing a release means; a jrock-config.txt or a bedrock-key.txt that
// is already there is KEPT and said so, because those are yours. Uninstalling is a
// separate program, and not this one.
//
// A previous install is read rather than guessed at: the Explorer entries hold the
// program folder, the working folder, the prompts folder and which agents were installed,
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

    // The vendor folder under Program Files, and the name the shortcuts and the primary
    // working folder are called by. The working folder's NAME ends up in every Explorer
    // entry's label (see JRock's titleFolder), which is why it is a word and not a path.
    private static final String VENDOR = "NoSocial.Net";
    private static final String PRODUCT = "JRock";
    private static final String ASSISTANT = "JRockAssistant";
    private static final String AGENTS = "JRockAgents";
    private static final String SHORTCUT = "JRock Assistant";

    // What the artifact must hold, beside this installer.
    private static final String JAR = "jrock.jar";
    private static final String SAMPLES = "agentic-samples";

    // Where to send anybody missing the two outside tools.
    private static final String GS_URL = "https://ghostscript.com/releases/gsdnld.html";
    private static final String XPDF_URL = "https://www.xpdfreader.com/download.html";

    // JRock's own registry names, spelled the same way here on purpose: an entry this
    // installer writes is one JRock's "Uninstall agent" can remove, and one it reads back
    // the next time this installer runs (see previousInstall).
    private static final String CTX_KEY = "OpenJRockHere";
    private static final String CTX_LABEL = "JRock here!";
    private static final String TXT_KEY = "OpenAsJRockPrompt";
    private static final String TXT_LABEL = "Open as prompt with JRock";
    private static final String AGENT_KEY_PREFIX = "JRockAgent-";
    private static final String AGENT_TITLE = "JRock agent";
    private static final String[] CTX_SHELL_ROOTS = {
        "Software\\Classes\\Directory\\Background\\shell",
        "Software\\Classes\\Directory\\shell",
    };
    private static final String TXT_SHELL_ROOT = "Software\\Classes\\SystemFileAssociations\\.txt\\shell";
    private static final String AGENT_FILE_SHELL_ROOT = "Software\\Classes\\*\\shell";

    // One agent as the wizard knows it: its file, the prompts its own source names, and
    // where the user wants it to work.
    private static final class Agent {
        final Path file;
        final String stem;
        final List<Path> prompts = new ArrayList<>();
        boolean selected = true;
        Where where = Where.PRIMARY;
        Path own;                       // when where == OWN or CUSTOM

        Agent(Path file) {
            this.file = file;
            String name = file.getFileName().toString();
            this.stem = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
        }

        // The words of a camel-case name, as JRock labels them: "JRockPushToTalk" ->
        // "Push To Talk".
        String words() {
            String s = stem.startsWith("JRock") && stem.length() > 5 ? stem.substring(5) : stem;
            String words = s.replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ")
                    .trim();
            return words.isEmpty() ? stem : words;
        }

        // The folder this agent is to run in, which decides its settings and its label.
        Path work(Path primary, Path agentsRoot) {
            switch (where) {
                case OWN:    return agentsRoot.resolve(stem.replaceFirst("^JRock", ""));
                case CUSTOM: return own == null ? primary : own;
                default:     return primary;
            }
        }
    }

    private enum Where { PRIMARY, OWN, CUSTOM }

    // Everything the wizard has been told, in one place.
    private static Path source;          // where the artifact was unpacked
    private static Path programDir;
    private static Path primaryWork;
    private static Path agentsRoot;      // the prompts folder, and the root of per-agent ones
    private static boolean startMenu = true;
    private static boolean desktop = true;
    private static boolean withAgents = true;
    private static boolean customiseAgents;
    private static boolean copySettings = true;
    private static boolean copyKey = true;
    private static boolean contextMenus = true;
    private static final List<Agent> found = new ArrayList<>();
    private static final Set<String> previouslyInstalled = new LinkedHashSet<>();
    private static boolean toolsSkipped;

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color BRAND = new java.awt.Color(0x0F766E);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);

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
        findAgents();
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
        System.out.println("  " + SAMPLES + "/:   "
                + (Files.isDirectory(source.resolve(SAMPLES)) ? "found" : "MISSING"));
        System.out.println("Agents found:        " + found.size());
        for (Agent a : found) {
            System.out.println("  " + a.stem + "  (" + a.words() + ")  prompts: "
                    + a.prompts.size());
        }
        System.out.println("Ghostscript:         " + or(ghostscript(), "NOT on PATH"));
        System.out.println("xpdf pdftohtml:      " + or(onPath("pdftohtml"), "NOT on PATH"));
        System.out.println("xpdf pdftotext:      " + or(onPath("pdftotext"), "NOT on PATH"));
        System.out.println("javaw/java:          " + or(launcher(), "NOT found"));
        System.out.println("Previous install:    "
                + (previouslyInstalled.isEmpty() && programDir == null ? "none found"
                   : "program " + programDir + ", work " + primaryWork + ", prompts "
                     + agentsRoot + ", agents " + previouslyInstalled));
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
    // The agents in agentic-samples/, each with the prompt files its own source names.
    //
    // Read out of the source rather than listed here: an agent knows which prompts it
    // reads, every one of them is a string constant in it, and a table in this installer
    // would be a second place to keep that right.
    private static void findAgents() {
        found.clear();
        Path dir = source.resolve(SAMPLES);
        if (!Files.isDirectory(dir)) return;
        Pattern prompt = Pattern.compile("jrock-prompt-[a-z0-9-]+\\.txt");
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            List<Path> javas = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted().forEach(javas::add);
            for (Path java : javas) {
                Agent agent = new Agent(java);
                String text = new String(Files.readAllBytes(java), StandardCharsets.UTF_8);
                Matcher m = prompt.matcher(text);
                Set<String> names = new LinkedHashSet<>();
                while (m.find()) names.add(m.group());
                for (String name : names) {
                    Path file = dir.resolve(name);
                    if (Files.isRegularFile(file)) agent.prompts.add(file);
                }
                found.add(agent);
            }
        } catch (IOException ex) {
            // Nothing found: the first page says so and offers Retry.
        }
    }

    // Every prompt in agentic-samples/, for "install all": some belong to no agent at all
    // - a prompt to use by hand - and leaving them behind would be a quiet loss.
    private static List<Path> allPrompts() {
        List<Path> prompts = new ArrayList<>();
        Path dir = source.resolve(SAMPLES);
        if (!Files.isDirectory(dir)) return prompts;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted().forEach(prompts::add);
        } catch (IOException ex) {
            // Nothing to add.
        }
        return prompts;
    }

    // ---- A previous install ----------------------------------------------------
    // The Explorer entries JRock writes carry everything this installer would otherwise
    // have to ask twice: the jar's folder, the working folder, the prompts folder, and
    // which agents are installed. Read with reg.exe - no registry API in the JDK - and
    // only read.
    private static void previousInstall() {
        if (!isWindows()) return;
        String here = regQuery("HKCU\\" + CTX_SHELL_ROOTS[0] + "\\" + CTX_KEY + "\\command");
        if (here != null) {
            Path jar = jarIn(here);
            if (jar != null && jar.getParent() != null) programDir = jar.getParent();
            Path work = flagIn(here, "--working-dir");
            if (work != null) primaryWork = work;
            Path prompts = flagIn(here, "--prompts-dir");
            if (prompts != null) agentsRoot = prompts;
        }
        // The agent entries: their key names carry the agent's own file name.
        for (String key : regKeys("HKCU\\" + AGENT_FILE_SHELL_ROOT)) {
            if (!key.startsWith(AGENT_KEY_PREFIX)) continue;
            String command = regQuery("HKCU\\" + AGENT_FILE_SHELL_ROOT + "\\" + key + "\\command");
            if (command == null) continue;
            Matcher m = Pattern.compile("\"([^\"]*JRock[A-Za-z0-9]*\\.java)\"").matcher(command);
            if (m.find()) {
                previouslyInstalled.add(Paths.get(m.group(1)).getFileName().toString()
                        .replaceAll("\\.java$", ""));
            }
            if (agentsRoot == null) {
                Path prompts = flagIn(command, "--prompts-dir");
                if (prompts != null) agentsRoot = prompts;
            }
        }
    }

    // The value of a registry command key, or null when it is not there.
    private static String regQuery(String key) {
        List<String> out = run("reg.exe", "query", key, "/ve");
        for (String line : out) {
            int at = line.indexOf("REG_SZ");
            if (at >= 0) return line.substring(at + "REG_SZ".length()).trim();
        }
        return null;
    }

    // The subkey names under a key, which is how the installed agents are found.
    private static List<String> regKeys(String key) {
        List<String> keys = new ArrayList<>();
        for (String line : run("reg.exe", "query", key)) {
            String text = line.trim();
            int slash = text.lastIndexOf('\\');
            if (text.startsWith("HKEY_") && slash > 0) keys.add(text.substring(slash + 1));
        }
        return keys;
    }

    private static Path jarIn(String command) {
        Matcher m = Pattern.compile("-cp\\s+\"([^\"]+)\"").matcher(command);
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
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path documents = Files.isDirectory(home.resolve("Documents"))
                ? home.resolve("Documents") : home;
        if (programDir == null) programDir = defaultProgramDir();
        if (primaryWork == null) primaryWork = documents.resolve(ASSISTANT);
        if (agentsRoot == null) agentsRoot = documents.resolve(AGENTS);
        if (!previouslyInstalled.isEmpty()) {
            // Installing again: what was there is what is ticked, and the rest is there
            // to be added rather than quietly installed behind the user.
            for (Agent a : found) a.selected = previouslyInstalled.contains(a.stem);
            customiseAgents = true;
        }
    }

    private static Path defaultProgramDir() {
        if (isWindows()) {
            String files = System.getenv("ProgramFiles");
            Path root = files == null || files.isBlank() ? Paths.get("C:\\Program Files")
                    : Paths.get(files);
            return root.resolve(VENDOR).resolve(PRODUCT);
        }
        if (isMac()) {
            return Paths.get(System.getProperty("user.home"), "Applications", VENDOR, PRODUCT);
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share",
                VENDOR.toLowerCase(Locale.ROOT), PRODUCT.toLowerCase(Locale.ROOT));
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
            panel.add(found(jar, JAR + (jar ? " - " + fmt(size(source.resolve(JAR))) + " bytes" : "")));
            panel.add(found(samples, SAMPLES + "/" + (samples ? " - " + found.size()
                    + " agent(s), " + allPrompts().size() + " prompt file(s)" : "")));
            if (!jar || !samples) {
                panel.add(note("Start this installer from the folder the release zip was "
                        + "unpacked into - the one holding jrock.jar and agentic-samples/. "
                        + "Press Retry when it is."));
            } else if (!previouslyInstalled.isEmpty()) {
                panel.add(note("A previous install was found, and its folders and agents "
                        + "are the defaults below. Nothing of it will be removed."));
            }
        }, () -> {
            findAgents();
            if (!Files.isRegularFile(source.resolve(JAR))) return "jrock.jar is not here.";
            if (!Files.isDirectory(source.resolve(SAMPLES))) return SAMPLES + "/ is not here.";
            return null;
        }));
        steps.get(0).nextLabel = "Next >";

        // 2. The program folder, and the jar into it.
        steps.add(new Step("Where the program goes", panel -> {
            panel.add(text("jrock.jar is copied here, with its SHA-256 and MD5 beside it:"));
            panel.add(folderRow(() -> programDir, p -> programDir = p, "Program folder"));
            panel.add(note(isWindows()
                    ? "Program Files needs an elevated installer. If the copy is refused, "
                      + "either start this installer as administrator, or choose a folder "
                      + "of your own - the agents and the shortcuts work either way."
                    : "Anywhere you can write. The agents and the shortcuts work wherever "
                      + "it is."));
        }, () -> {
            try {
                Files.createDirectories(programDir);
                Path jar = programDir.resolve(JAR);
                boolean replacing = Files.isRegularFile(jar);
                Files.copy(source.resolve(JAR), jar, StandardCopyOption.REPLACE_EXISTING);
                say((replacing ? "Replaced " : "Copied ") + jar + " (" + fmt(size(jar)) + " bytes)");
                writeHash(jar, "SHA-256", "sha256");
                writeHash(jar, "MD5", "md5");
                return null;
            } catch (IOException ex) {
                return "Could not write into " + programDir + ": " + ex.getMessage();
            }
        }));

        // 3. The two outside tools.
        steps.add(new Step("Ghostscript and xpdf", panel -> {
            Path gs = ghostscript();
            Path html = onPath("pdftohtml");
            Path txt = onPath("pdftotext");
            panel.add(text("JRock converts PDFs with two tools it does not ship. Everything "
                    + "else works without them."));
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
                        + "Or press Continue without them - JRock says which tool is "
                        + "missing if you ever ask it for that conversion, and installing "
                        + "them later needs nothing from this installer."));
            }
        }, () -> {
            boolean all = ghostscript() != null && onPath("pdftohtml") != null
                    && onPath("pdftotext") != null;
            if (!all) {
                toolsSkipped = true;
                say("Ghostscript or xpdf not on PATH: the PDF conversions will say so when asked.");
            }
            return null;
        }));

        // 4. The primary working folder.
        steps.add(new Step("Where JRock keeps your work", panel -> {
            panel.add(text("Every prompt, the whole transcript, the settings and the key "
                    + "live in a JRock/ folder inside this one:"));
            panel.add(folderRow(() -> primaryWork, p -> primaryWork = p, "Working folder"));
            panel.add(note("The FOLDER NAME is what Explorer's right-click entries are "
                    + "labelled with - \"" + AGENT_TITLE + ": Push To Talk ("
                    + primaryWork.getFileName() + ")...\" - so it is worth a name you will "
                    + "recognise. One folder, one set of settings: a second folder is a "
                    + "second JRock with its own model, DPI and key."));
        }, () -> {
            try {
                Files.createDirectories(primaryWork);
                say("Working folder: " + primaryWork);
                return null;
            } catch (IOException ex) {
                return "Could not create " + primaryWork + ": " + ex.getMessage();
            }
        }));

        // 5. Shortcuts.
        steps.add(new Step("Shortcuts", panel -> {
            panel.add(text("Both open JRock in " + primaryWork + "."));
            panel.add(check("Start menu shortcut \"" + SHORTCUT + "\"", startMenu,
                    v -> startMenu = v));
            panel.add(check("Desktop shortcut \"" + SHORTCUT + "\"", desktop, v -> desktop = v));
            if (!isWindows()) {
                panel.add(note(isMac()
                        ? "On macOS these are small .command launchers in ~/Applications and "
                          + "on the Desktop."
                        : "On Linux these are .desktop launchers in the applications menu "
                          + "and on the Desktop."));
            }
        }, () -> {
            if (startMenu) shortcut(startMenuDir(), true);
            if (desktop) shortcut(desktopDir(), false);
            return null;
        }));

        // 6. Which agents, and where each one works.
        steps.add(new Step("Agents", panel -> {
            panel.add(check("Install agents", withAgents, v -> {
                withAgents = v;
                redraw();
            }));
            if (!withAgents) {
                panel.add(note("Nothing but JRock itself is installed. The agents are "
                        + "single .java files: copy them beside jrock.jar whenever you "
                        + "like and install their Explorer entries from JRock's own "
                        + "Window menu."));
                return;
            }
            panel.add(text("Prompt files go here, and this is also the root of the "
                    + "per-agent folders below:"));
            panel.add(folderRow(() -> agentsRoot, p -> agentsRoot = p, "Prompts folder"));
            panel.add(radio("Install all " + found.size() + " agents, each working in "
                    + primaryWork.getFileName(), !customiseAgents, v -> {
                        customiseAgents = !v;
                        redraw();
                    }));
            panel.add(radio("Choose the agents, and where each one works", customiseAgents,
                    v -> {
                        customiseAgents = v;
                        redraw();
                    }));
            if (customiseAgents) panel.add(agentTable());
        }, () -> {
            if (!withAgents) return null;
            if (!customiseAgents) {
                for (Agent a : found) {
                    a.selected = true;
                    a.where = Where.PRIMARY;
                }
            }
            if (selected().isEmpty()) return "No agent is ticked.";
            try {
                Files.createDirectories(agentsRoot);
            } catch (IOException ex) {
                return "Could not create " + agentsRoot + ": " + ex.getMessage();
            }
            return copyAgents();
        }));

        // 7. The settings, and the key.
        steps.add(new Step("Settings and the key", panel -> {
            panel.add(text("Two files in " + primaryWork.resolve(PRODUCT) + ":"));
            panel.add(value("jrock-config.txt - the endpoint, the model, the DPI, the "
                    + "checkboxes"));
            panel.add(value("bedrock-key.txt - the Bedrock API key, and nothing else"));
            panel.add(note("Next opens the config in whatever opens a .txt here. Save and "
                    + "close it, then the key file opens the same way. An existing file of "
                    + "either kind is kept as it is - this installer never overwrites "
                    + "your settings."));
        }, () -> {
            try {
                Path jrock = primaryWork.resolve(PRODUCT);
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

        // 8. The same settings for the agents that work elsewhere.
        steps.add(new Step("Settings for the agents", panel -> {
            List<Agent> apart = apart();
            if (apart.isEmpty()) {
                panel.add(text("Every agent works in " + primaryWork + ", so they all read "
                        + "the settings you just reviewed. Nothing to copy."));
                return;
            }
            panel.add(text(apart.size() + " agent(s) work in a folder of their own, and a "
                    + "folder of their own means settings of its own:"));
            for (Agent a : apart) {
                panel.add(value(a.words() + "  \u2192  " + a.work(primaryWork, agentsRoot)));
            }
            panel.add(check("Copy jrock-config.txt into each of them", copySettings,
                    v -> copySettings = v));
            panel.add(check("Copy bedrock-key.txt too", copyKey, v -> copyKey = v));
            panel.add(note("An agent cannot ask a model without a key in its own folder. "
                    + "JRock itself never copies a key into a folder it was not typed for, "
                    + "which is why this is a tick and not a given - and why an existing "
                    + "key in a folder is left alone."));
        }, () -> {
            for (Agent a : apart()) {
                Path jrock = a.work(primaryWork, agentsRoot).resolve(PRODUCT);
                try {
                    Files.createDirectories(jrock);
                    if (copySettings) copyIfAbsent(primaryWork.resolve(PRODUCT)
                            .resolve("jrock-config.txt"), jrock.resolve("jrock-config.txt"));
                    if (copyKey) copyIfAbsent(primaryWork.resolve(PRODUCT)
                            .resolve("bedrock-key.txt"), jrock.resolve("bedrock-key.txt"));
                } catch (IOException ex) {
                    return "Could not write into " + jrock + ": " + ex.getMessage();
                }
            }
            return null;
        }));

        // 9. Explorer entries.
        steps.add(new Step("Right-click entries", panel -> {
            if (!isWindows()) {
                panel.add(text("Explorer entries are a Windows thing; there is nothing to "
                        + "install here."));
                return;
            }
            panel.add(check("Install the Explorer right-click entries", contextMenus,
                    v -> contextMenus = v));
            panel.add(value("\"" + CTX_LABEL + "\" on a folder, and on its background"));
            panel.add(value("\"" + TXT_LABEL + "\" on a .txt file"));
            for (Agent a : selected()) {
                panel.add(value("\"" + AGENT_TITLE + ": " + a.words() + " ("
                        + a.work(primaryWork, agentsRoot).getFileName() + ")...\" on a "
                        + "folder and on any file"));
            }
            panel.add(note("Written under HKEY_CURRENT_USER - no administrator, and nothing "
                    + "outside your own account. The .reg files applied are kept in "
                    + primaryWork.resolve(PRODUCT) + " so you can read exactly what was "
                    + "done. You can skip this and install them later from JRock's Window "
                    + "menu."));
        }, () -> {
            if (!isWindows() || !contextMenus) return null;
            return installEntries();
        }));

        // 10. Done, and running it.
        steps.add(new Step("Installed", panel -> {
            panel.add(text("JRock is installed."));
            panel.add(value("Program:  " + programDir));
            panel.add(value("Work:     " + primaryWork));
            if (withAgents && !selected().isEmpty()) {
                panel.add(value("Agents:   " + selected().size() + " in " + programDir));
                panel.add(value("Prompts:  " + agentsRoot));
            }
            List<String> running = runningJRock();
            if (!running.isEmpty()) {
                panel.add(note("Something of JRock is still running: " + String.join(", ",
                        running) + ". Close it, then press Retry - a second JRock in the "
                        + "same folder writes the same log and the same prompt file, and "
                        + "the two overwrite each other."));
            } else {
                panel.add(note("Nothing of JRock is running. Next starts JRock in "
                        + primaryWork.getFileName()
                        + (withAgents && !selected().isEmpty()
                           ? ", and every agent in its own folder." : ".")));
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
    // The chosen agents into the program folder, their prompts into the prompts folder,
    // and - for an agent that works in a folder of its own - its prompts in there too, so
    // the agent finds them beside its own working folder as well.
    private static String copyAgents() {
        try {
            List<Agent> chosen = selected();
            for (Agent a : chosen) {
                Path to = programDir.resolve(a.file.getFileName());
                Files.copy(a.file, to, StandardCopyOption.REPLACE_EXISTING);
                say("Agent: " + to);
            }
            List<Path> prompts = customiseAgents ? promptsOf(chosen) : allPrompts();
            for (Path p : prompts) {
                copyIfNewer(p, agentsRoot.resolve(p.getFileName()));
            }
            say(prompts.size() + " prompt file(s) into " + agentsRoot);
            for (Agent a : apart()) {
                Path dir = a.work(primaryWork, agentsRoot);
                Files.createDirectories(dir);
                for (Path p : a.prompts) copyIfNewer(p, dir.resolve(p.getFileName()));
                say(a.prompts.size() + " prompt file(s) into " + dir);
            }
            return null;
        } catch (IOException ex) {
            return "Could not copy the agents: " + ex.getMessage();
        }
    }

    private static List<Path> promptsOf(List<Agent> agents) {
        Set<Path> prompts = new LinkedHashSet<>();
        for (Agent a : agents) prompts.addAll(a.prompts);
        return new ArrayList<>(prompts);
    }

    private static List<Agent> selected() {
        List<Agent> chosen = new ArrayList<>();
        if (!withAgents) return chosen;
        for (Agent a : found) if (a.selected) chosen.add(a);
        return chosen;
    }

    // The chosen agents that work somewhere other than the primary folder.
    private static List<Agent> apart() {
        List<Agent> apart = new ArrayList<>();
        for (Agent a : selected()) {
            if (!a.work(primaryWork, agentsRoot).equals(primaryWork)) apart.add(a);
        }
        return apart;
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

    // The hash files the release publishes beside the jar, written from the copy that
    // was just made - so what they describe is the file on this disk.
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
                "' This file was created by the installer and holds nothing yet: JRock writes",
                "' every setting it knows the first time it runs, and the Configure dialog",
                "' writes them again whenever you change one. Anything you put here now is",
                "' read at that first start.",
                "'",
                "' endpoint$",
                "' https://bedrock-mantle.us-east-1.api.aws/v1/chat/completions",
                "'",
                "' model$",
                "' xai.grok-4.3",
                "");
        Files.write(config, text.getBytes(StandardCharsets.UTF_8));
        say("Created " + config);
        return config;
    }

    private static Path starterKey(Path jrock) throws IOException {
        Path key = jrock.resolve("bedrock-key.txt");
        if (!Files.exists(key)) {
            Files.write(key, new byte[0]);
            say("Created " + key + " (empty)");
        }
        return key;
    }

    private static boolean isBlank(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim().isEmpty();
        } catch (IOException ex) {
            return true;
        }
    }

    // ---- Shortcuts -------------------------------------------------------------
    // One shortcut that starts JRock in the primary working folder. A .lnk is a COM
    // object, so PowerShell makes it - the one way to write a real shortcut without a
    // native library. Elsewhere it is a .desktop file or a small .command script, which
    // is what those desktops use.
    private static void shortcut(Path dir, boolean menu) {
        if (dir == null) {
            say("No " + (menu ? "Start menu" : "Desktop") + " folder found; no shortcut.");
            return;
        }
        try {
            Files.createDirectories(dir);
            Path jar = programDir.resolve(JAR);
            if (isWindows()) {
                Path lnk = dir.resolve(SHORTCUT + ".lnk");
                String ps = "$s = (New-Object -ComObject WScript.Shell).CreateShortcut('"
                        + lnk + "'); "
                        + "$s.TargetPath = '" + launcher() + "'; "
                        + "$s.Arguments = '-cp \"" + jar + "\" JRock --working-dir \""
                        + primaryWork + "\"'; "
                        + "$s.WorkingDirectory = '" + primaryWork + "'; "
                        + "$s.Description = 'JRock in " + primaryWork.getFileName() + "'; "
                        + "$s.Save()";
                List<String> out = run("powershell.exe", "-NoProfile", "-NonInteractive",
                        "-Command", ps);
                say(Files.isRegularFile(lnk) ? "Shortcut: " + lnk
                        : "Could not write " + lnk + " " + out);
                return;
            }
            if (isMac()) {
                Path command = dir.resolve(SHORTCUT + ".command");
                Files.write(command, ("#!/bin/sh\nexec \"" + launcher() + "\" -cp \"" + jar
                        + "\" JRock --working-dir \"" + primaryWork + "\"\n")
                        .getBytes(StandardCharsets.UTF_8));
                command.toFile().setExecutable(true);
                say("Launcher: " + command);
                return;
            }
            Path desktopFile = dir.resolve("jrock-assistant.desktop");
            Files.write(desktopFile, ("[Desktop Entry]\nType=Application\nName=" + SHORTCUT
                    + "\nComment=JRock in " + primaryWork.getFileName() + "\nExec=\""
                    + launcher() + "\" -cp \"" + jar + "\" JRock --working-dir \""
                    + primaryWork + "\"\nPath=" + primaryWork + "\nTerminal=false\n"
                    + "Categories=Development;Utility;\n").getBytes(StandardCharsets.UTF_8));
            desktopFile.toFile().setExecutable(true);
            say("Launcher: " + desktopFile);
        } catch (IOException ex) {
            say("Could not write the " + (menu ? "Start menu" : "Desktop") + " shortcut: "
                    + ex.getMessage());
        }
    }

    private static Path startMenuDir() {
        if (isWindows()) {
            String appData = System.getenv("APPDATA");
            return appData == null ? null : Paths.get(appData,
                    "Microsoft", "Windows", "Start Menu", "Programs", VENDOR);
        }
        if (isMac()) return Paths.get(System.getProperty("user.home"), "Applications");
        return Paths.get(System.getProperty("user.home"), ".local", "share", "applications");
    }

    private static Path desktopDir() {
        Path desktopFolder = Paths.get(System.getProperty("user.home"), "Desktop");
        return Files.isDirectory(desktopFolder) ? desktopFolder : null;
    }

    // ---- Explorer entries ------------------------------------------------------
    // Exactly what JRock writes from its own Window menu: the same key names, the same
    // labels, the same command lines. Which is the point - an entry installed here is one
    // JRock recognises, and one its own Uninstall agent takes away again.
    private static String installEntries() {
        String javaw = launcher();
        if (javaw == null) return "Could not find javaw.exe beside this JVM.";
        Path jar = programDir.resolve(JAR);
        StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");

        String here = "\"" + javaw + "\" -cp \"" + jar + "\" JRock --working-dir "
                + quoted(primaryWork);
        for (String root : CTX_SHELL_ROOTS) {
            verb(reg, root, CTX_KEY, CTX_LABEL, javaw, here);
        }
        verb(reg, TXT_SHELL_ROOT, TXT_KEY, TXT_LABEL, javaw, here + " \"%1\"");

        for (Agent a : selected()) {
            Path work = a.work(primaryWork, agentsRoot);
            String label = AGENT_TITLE + ": " + a.words() + " (" + work.getFileName() + ")...";
            String key = AGENT_KEY_PREFIX + keyPart(a.stem) + "-"
                    + keyPart(String.valueOf(work.getFileName())) + "-" + shortHash(work);
            String agentJava = programDir.resolve(a.file.getFileName()).toString();
            String launch = "\"" + javaw + "\" -cp \"" + jar + "\" \"" + agentJava + "\""
                    + " --working-dir " + quoted(work)
                    + " --prompts-dir " + quoted(agentsRoot);
            for (String root : CTX_SHELL_ROOTS) {
                verb(reg, root, key, label, javaw, launch);
            }
            verb(reg, AGENT_FILE_SHELL_ROOT, key, label, javaw, launch + " \"%1\"");
        }

        try {
            Path jrock = primaryWork.resolve(PRODUCT);
            Files.createDirectories(jrock);
            Path regFile = jrock.resolve("jrock-installer-context-menus.reg");
            byte[] text = reg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            byte[] withBom = new byte[text.length + 2];
            withBom[0] = (byte) 0xFF;
            withBom[1] = (byte) 0xFE;
            System.arraycopy(text, 0, withBom, 2, text.length);
            Files.write(regFile, withBom);
            List<String> out = run("reg.exe", "import", regFile.toString());
            say("Applied " + regFile + " " + (out.isEmpty() ? "" : out));
            say("Explorer entries: \"" + CTX_LABEL + "\", \"" + TXT_LABEL + "\" and "
                    + selected().size() + " agent entr(y/ies).");
            return null;
        } catch (IOException ex) {
            return "Could not write the entries: " + ex.getMessage();
        }
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

    // A trailing backslash - which only a drive root still has - would escape the closing
    // quote when Windows parses the command line, so it is doubled.
    private static String quoted(Path dir) {
        String s = dir.toString();
        if (s.endsWith("\\")) s = s + "\\";
        return "\"" + s + "\"";
    }

    private static String keyPart(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9_-]", "-");
        return clean.isEmpty() ? "x" : clean;
    }

    // The same short hash JRock puts in an agent's key, so two installs from two folders
    // are two entries rather than one overwritten.
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
            // A running JRock is a JVM with the jar or an agent on its command line.
            // Nothing else counts: a shell whose current folder happens to be named
            // after JRock, or a text editor with jrock-config.txt open, is not JRock -
            // and this answer is what the last page refuses to go past.
            String program = name.toLowerCase(Locale.ROOT);
            boolean jvm = program.startsWith("java") || program.equals("jrock");
            boolean ours = command.contains(JAR)
                    || command.matches("(?s).*JRock[A-Za-z0-9]*\\.java.*")
                    || command.matches("(?s).*\\bJRock\\b.*-" + "-working-dir.*");
            if (!jvm || !ours || command.contains("InstallJRock")) continue;
            Path work = flagIn(command, "--working-dir");
            running.add(name + " " + pid + (work == null || work.getFileName() == null ? ""
                    : " in " + work.getFileName()));
        }
        return running;
    }

    private static void launchAll() {
        Path jar = programDir.resolve(JAR);
        start(List.of(launcher(), "-cp", jar.toString(), "JRock",
                "--working-dir", primaryWork.toString()), primaryWork);
        say("Started JRock in " + primaryWork);
        for (Agent a : selected()) {
            Path work = a.work(primaryWork, agentsRoot);
            Path agentJava = programDir.resolve(a.file.getFileName());
            start(List.of(launcher(), "-cp", jar.toString(), agentJava.toString(),
                    "--working-dir", work.toString(),
                    "--prompts-dir", agentsRoot.toString()), work);
            say("Started " + a.words() + " in " + work);
        }
    }

    private static void start(List<String> command, Path in) {
        try {
            new ProcessBuilder(command).directory(in.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException ex) {
            say("Could not start " + command.get(command.size() - 3) + ": " + ex.getMessage());
        }
    }

    // ---- Finding things --------------------------------------------------------
    private static String launcher() {
        String home = System.getProperty("java.home");
        if (home == null) return null;
        for (String name : isWindows() ? new String[] { "javaw.exe", "java.exe" }
                                       : new String[] { "java" }) {
            Path at = Paths.get(home, "bin", name);
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
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);

        heading = new javax.swing.JLabel(" ");
        heading.setFont(base.deriveFont(java.awt.Font.BOLD, 16f));
        heading.setForeground(BRAND);
        heading.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 10, 0));

        content = new javax.swing.JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setOpaque(false);
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(content);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new java.awt.Dimension(660, 300));

        log = new javax.swing.JTextArea(8, 70);
        log.setEditable(false);
        log.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        javax.swing.JScrollPane logScroll = new javax.swing.JScrollPane(log);
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

        javax.swing.JPanel buttons = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(back);
        buttons.add(next);

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

    private static void say(String line) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            log.append(line + System.lineSeparator());
            log.setCaretPosition(log.getDocument().getLength());
        });
        System.out.println(line);
    }

    // ---- Small widgets ---------------------------------------------------------
    private static javax.swing.JComponent text(String words) {
        javax.swing.JLabel label = new javax.swing.JLabel(
                "<html><div style='width:620px'>" + escape(words) + "</div></html>");
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 8, 0));
        return label;
    }

    private static javax.swing.JComponent note(String words) {
        javax.swing.JLabel label = new javax.swing.JLabel(
                "<html><div style='width:620px'>" + escape(words) + "</div></html>");
        label.setForeground(MUTED);
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 8, 0));
        return label;
    }

    private static javax.swing.JComponent value(String words) {
        javax.swing.JLabel label = new javax.swing.JLabel(escape(words));
        label.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        label.setAlignmentX(0f);
        label.setBorder(javax.swing.BorderFactory.createEmptyBorder(1, 10, 1, 0));
        return label;
    }

    private static javax.swing.JComponent found(boolean yes, String what) {
        javax.swing.JLabel label = new javax.swing.JLabel((yes ? "\u2713  " : "\u2717  ")
                + escape(what));
        label.setForeground(yes ? BRAND : new java.awt.Color(0xB91C1C));
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

    // A folder, typed or browsed to.
    private static javax.swing.JComponent folderRow(java.util.function.Supplier<Path> get,
                                                    java.util.function.Consumer<Path> set,
                                                    String title) {
        javax.swing.JTextField field = new javax.swing.JTextField(get.get().toString(), 48);
        field.addActionListener(e -> set.accept(Paths.get(field.getText().trim())));
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

    // One row per agent: whether to install it, and which folder it works in. The three
    // choices are the three anybody actually wants - everything together, one folder each,
    // or a folder you name - and the last is the only one that needs typing.
    private static javax.swing.JComponent agentTable() {
        javax.swing.JPanel table = new javax.swing.JPanel();
        table.setLayout(new javax.swing.BoxLayout(table, javax.swing.BoxLayout.Y_AXIS));
        table.setOpaque(false);
        table.setAlignmentX(0f);
        for (Agent a : found) {
            javax.swing.JPanel row = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
            row.setOpaque(false);
            javax.swing.JCheckBox box = new javax.swing.JCheckBox(
                    a.words() + "  (" + a.prompts.size() + " prompt file(s))", a.selected);
            box.setOpaque(false);
            box.addActionListener(e -> a.selected = box.isSelected());
            box.setPreferredSize(new java.awt.Dimension(270, box.getPreferredSize().height));

            javax.swing.JComboBox<String> where = new javax.swing.JComboBox<>(new String[] {
                primaryWork.getFileName() + " (with JRock)",
                AGENTS + "/" + a.stem.replaceFirst("^JRock", "") + " (its own)",
                "Choose\u2026",
            });
            where.setSelectedIndex(a.where == Where.PRIMARY ? 0 : a.where == Where.OWN ? 1 : 2);
            javax.swing.JTextField custom = new javax.swing.JTextField(
                    a.own == null ? "" : a.own.toString(), 18);
            custom.setEnabled(a.where == Where.CUSTOM);
            custom.addActionListener(e -> a.own = Paths.get(custom.getText().trim()));
            custom.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    String typed = custom.getText().trim();
                    if (!typed.isEmpty()) a.own = Paths.get(typed);
                }
            });
            where.addActionListener(e -> {
                int picked = where.getSelectedIndex();
                a.where = picked == 0 ? Where.PRIMARY : picked == 1 ? Where.OWN : Where.CUSTOM;
                custom.setEnabled(a.where == Where.CUSTOM);
                if (a.where == Where.CUSTOM && a.own == null) {
                    a.own = agentsRoot.resolve(a.stem.replaceFirst("^JRock", ""));
                    custom.setText(a.own.toString());
                }
            });

            javax.swing.JPanel right = new javax.swing.JPanel(new java.awt.BorderLayout(6, 0));
            right.setOpaque(false);
            right.add(where, java.awt.BorderLayout.WEST);
            right.add(custom, java.awt.BorderLayout.CENTER);
            row.add(box, java.awt.BorderLayout.WEST);
            row.add(right, java.awt.BorderLayout.CENTER);
            table.add(row);
        }
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table);
        scroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Agents"));
        scroll.setPreferredSize(new java.awt.Dimension(640, 220));
        scroll.setAlignmentX(0f);
        return scroll;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
