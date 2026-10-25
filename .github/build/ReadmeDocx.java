import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.JFrame;

/**
 * The README as a DOCX, written by JRock.
 *
 * <p>Run after the JAR, with it on the class path, from the repository root:
 *
 * <pre>
 *     java -cp jrock.jar .github/build/ReadmeDocx.java README.md README.docx
 * </pre>
 *
 * <p>WHY JROCK WRITES IT. JRock already turns Markdown into a DOCX - it is how an answer
 * leaves the window on its way to being typeset, and the reason that export is DOCX and
 * not RTF is the one that applies here: it carries NAMED PARAGRAPH STYLES a layout
 * application maps onto its own. The document comes out with Word's own {@code Heading
 * 1..6}, {@code Normal}, {@code Code}, {@code Quote} and {@code Hyperlink}, in Calibri
 * and Consolas - two fonts every Windows 10 machine has, so InDesign finds them instead
 * of asking. Placing this in a primary text frame and mapping five style names is then
 * the whole of the import.
 *
 * <p>So there is no pandoc here, no LaTeX, nothing to install and no second Markdown
 * dialect to keep in step with the first. The program documents itself, and the step that
 * builds the document is a check on the exporter every bit as much as the screenshots are
 * a check on the window.
 *
 * <p>WHAT IS DROPPED. The table of contents between the README's {@code <!-- toc:start
 * -->} and {@code <!-- toc:end -->} markers: a layout application generates its own from
 * the heading styles, with its own page numbers, and an imported one would be a list of
 * dead anchors in front of it. Nothing else is touched - the Markdown that goes in is the
 * README as it stands.
 *
 * <p>HOW THE DIAGRAMS GET IN. The README's seven diagrams are {@code ```mermaid} blocks,
 * which GitHub renders itself and a word processor cannot: left alone they would arrive as
 * eighteen lines of {@code sequenceDiagram} in Consolas, which is source code where a
 * picture belongs. So each one is rendered to a PNG first, by
 * <a href="https://github.com/mermaid-js/mermaid-cli">mermaid-cli</a> through {@code npx} -
 * the one cross-platform renderer there is, being the reference implementation itself
 * driving a headless browser - and the block is replaced by a reference to the picture, so
 * the rest of this program attaches it exactly as it attaches a screenshot.
 *
 * <p>It is the only outside tool in this build, it is asked for by {@code npx} rather than
 * installed, and <b>nothing stops if it is missing</b>: the blocks stay as they are, the
 * document says so, and the DOCX is still written. The browser is the one already on the
 * machine where there is one - {@code JROCK_CHROMIUM}, or Edge or Chrome where they live -
 * so a second Chromium is not downloaded to draw seven pictures.
 *
 * <p>HOW THE PICTURES GET IN. The exporter places a {@code ![...](<target>)} when the
 * target names one of the session's includes, so every image the README refers to is
 * attached first, through the same {@code automationInclude} call a flow uses. The
 * working directory is the repository root, which is what makes {@code images/x.png}
 * resolve; processed copies are turned off for this session, so an include points at the
 * file in {@code images/} rather than at a copy of it.
 */
public final class ReadmeDocx {

    /** The TOC markers in the README, and everything between them, including them. */
    private static final Pattern TOC = Pattern.compile(
            "<!--\\s*toc:start\\s*-->.*?<!--\\s*toc:end\\s*-->\\s*", Pattern.DOTALL);

    /** A fenced mermaid diagram, and the diagram source inside it. */
    private static final Pattern MERMAID = Pattern.compile(
            "^```mermaid[ \\t]*\\r?\\n(.*?)\\r?\\n```[ \\t]*$",
            Pattern.DOTALL | Pattern.MULTILINE);

    /** Where a Chromium might be, for mermaid-cli to draw in. */
    private static final String[] CHROMIUM = {
        "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
        "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
        "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
        "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
        "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
    };

    /** An image reference, for finding what has to be attached before the export. */
    private static final Pattern IMAGE = Pattern.compile(
            "!\\[[^\\]\\n]*\\]\\((<[^>\\n]+>|[^)\\s]+)\\)");

    public static void main(String[] args) throws Exception {
        Path readme = Paths.get(args.length > 0 ? args[0] : "README.md").toAbsolutePath();
        Path target = Paths.get(args.length > 1 ? args[1] : "README.docx").toAbsolutePath();
        Path repo = readme.getParent();

        String markdown = new String(Files.readAllBytes(readme), StandardCharsets.UTF_8);
        String withoutToc = TOC.matcher(markdown).replaceAll("");
        if (withoutToc.length() == markdown.length()) {
            System.out.println("no <!-- toc:start --> ... <!-- toc:end --> block found: "
                    + "exporting the README as it stands");
        }

        // THE FOLDER FIRST, because the diagrams are drawn into it before anything is
        // read out of the Markdown: a rendered diagram is a picture reference like any
        // other by the time the references are counted.
        Path root = Files.createTempDirectory("jrock-readme-docx");
        String text = diagramsAsPictures(withoutToc, root);

        // A FOLDER OF ITS OWN, and not the repository root - which is the obvious place
        // and the wrong one. JRock works in a folder and keeps its settings in it, so
        // running this at a desk would write a __JRock/ into the checkout and overwrite
        // the endpoint, the model and the settings of whoever works in that folder. So
        // the pictures the README refers to are copied into a throwaway folder under the
        // same relative names and JRock is pointed at that, which is also what makes
        // "images/x.png" resolve (see refTargetHash in JRock.java).
        List<String> references = imagesIn(text);
        int copied = 0;
        for (String reference : references) {
            Path from = repo.resolve(reference);
            if (!Files.isRegularFile(from)) continue;
            Path to = root.resolve(reference);
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
            copied++;
        }
        System.out.println("working in " + root + " with " + copied + " of "
                + references.size() + " referenced picture(s)");

        // The settings this one session wants, written before JRock reads them: no
        // processed copies, so an include is the picture itself and the README's own
        // reference to it resolves. No endpoint and no key, because nothing here sends
        // anything - JRock says so and skips the model list, so this step needs no
        // credential and reaches no network.
        Path settings = root.resolve("__JRock");
        Files.createDirectories(settings);
        Files.write(settings.resolve("jrock-config.txt"),
                ("' Written by .github/build/ReadmeDocx.java for one export.\n\n"
                 + "include-processed-copies$\nfalse\n\n")
                        .getBytes(StandardCharsets.UTF_8));

        JRock.main(new String[] { "--working-dir", root.toString() });
        String notReady = JRock.automationAwaitReady(120_000);
        JFrame window = JRock.automationWindow();
        if (window == null) {
            System.out.println("no window: " + notReady);
            System.exit(1);
        }
        String problem = JRock.automationBegin("readme-docx");
        if (problem != null) {
            System.out.println("could not drive JRock: " + problem);
            System.exit(1);
        }

        int attached = 0;
        for (String reference : references) {
            Path file = root.resolve(reference);
            if (!Files.isRegularFile(file)) {
                System.out.println("no file for " + reference + ": the reference stays in "
                        + "the text and no picture is placed");
                continue;
            }
            String refused = JRock.automationInclude(file);
            if (refused != null) System.out.println(reference + ": " + refused);
            else attached++;
        }
        System.out.println("attached " + attached + " picture(s)");

        // The whole README into the prompt, and then out as a document. The prompt is
        // JRock's own sheet of paper for exactly this: text put there is text the window
        // is showing, which is the rule the export follows (see automationExportPromptDocx).
        String rejected = JRock.automationSetPrompt(text);
        if (rejected != null) {
            System.out.println("could not set the prompt: " + rejected);
            System.exit(1);
        }
        String failure = JRock.automationExportPromptDocx(target.toString());
        if (failure != null) {
            System.out.println("could not export: " + failure);
            System.exit(1);
        }
        System.out.println("wrote " + target + "  "
                + Files.size(target) + " bytes, from " + text.length()
                + " characters of Markdown");

        if (args.length > 2) chapters(text, Paths.get(args[2]).toAbsolutePath());
        System.exit(0);
    }

    /**
     * Every {@code ```mermaid} block rendered to a PNG under the session folder, and the
     * Markdown with each block replaced by a reference to its picture. The blocks are left
     * exactly as they are when there is no renderer to be had - a document with seven
     * diagrams as source in it is worth more than no document.
     */
    private static String diagramsAsPictures(String markdown, Path root) throws Exception {
        List<String> sources = new ArrayList<>();
        Matcher blocks = MERMAID.matcher(markdown);
        while (blocks.find()) sources.add(blocks.group(1));
        if (sources.isEmpty()) return markdown;

        // ONE FILE OF NOTHING BUT DIAGRAMS, and not the README itself, although mermaid-cli
        // will happily take a Markdown file and hand back a rewritten one. What comes back
        // then is 400 kB of somebody else's idea of this document, and the substitution
        // here is three lines. So it gets the seven blocks, and this program keeps the text.
        Path folder = root.resolve("images").resolve("diagrams");
        Files.createDirectories(folder);
        StringBuilder together = new StringBuilder();
        for (String source : sources) {
            together.append("```mermaid\n").append(source).append("\n```\n\n");
        }
        Path input = folder.resolve("diagrams.md");
        Files.write(input, together.toString().getBytes(StandardCharsets.UTF_8));

        List<String> command = new ArrayList<>();
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) command.addAll(Arrays.asList("cmd.exe", "/c"));
        // The binary named rather than guessed at: npx will work out that this package's
        // one command is mmdc, and then not work it out on the machine where the cache is
        // cold or the package grows a second binary. --package says which, and the pinned
        // major says which mermaid - a diagram that renders today renders the same in a
        // year, which is the whole point of pinning a tool nobody here maintains.
        command.addAll(Arrays.asList("npx", "--yes",
                "--package=@mermaid-js/mermaid-cli@12", "mmdc",
                "--input", input.toString(),
                "--output", folder.resolve("diagram.md").toString(),
                "--outputFormat", "png",
                "--backgroundColor", "white",
                // Twice the size, which is what makes the type in them readable on paper:
                // a diagram placed at its physical size on A4 is small, and a 1x PNG of it
                // is the one picture in the document that looks worse than the screenshots.
                "--scale", "2"));

        String browser = chromium();
        ProcessBuilder running = new ProcessBuilder(command);
        if (browser != null) {
            // The Chromium that is here already, named in a file of puppeteer's own, and
            // the download of another one turned off in the same breath.
            Path configuration = folder.resolve("puppeteer.json");
            // protocolTimeout, because puppeteer's own is three minutes per command and
            // a screenshot of a large diagram on a busy machine can take longer than
            // that - which failed one run of thirteen diagrams here and left the
            // document with its blocks as source. Ten minutes, the same as the cap on
            // the whole process below, so one hang cannot outlive the step.
            Files.write(configuration, ("{\"executablePath\":"
                    + quoted(browser) + ",\"protocolTimeout\":600000"
                    + ",\"args\":[\"--no-sandbox\"]}")
                            .getBytes(StandardCharsets.UTF_8));
            command.addAll(Arrays.asList("--puppeteerConfigFile", configuration.toString()));
            running.environment().put("PUPPETEER_SKIP_DOWNLOAD", "true");
            running.environment().put("PUPPETEER_SKIP_CHROMIUM_DOWNLOAD", "true");
            System.out.println("drawing " + sources.size() + " diagram(s) in " + browser);
        } else {
            System.out.println("drawing " + sources.size() + " diagram(s) in whichever "
                    + "browser npx brings: no Chromium found on this machine");
        }
        running.command(command);
        running.redirectErrorStream(true);
        Path report = folder.resolve("mermaid-cli.log");
        running.redirectOutput(report.toFile());

        Process rendering = running.start();
        // Ten minutes, because the first run on a machine fetches mermaid-cli itself.
        if (!rendering.waitFor(10, TimeUnit.MINUTES)) {
            rendering.destroy();
            return left(markdown, "mermaid-cli did not finish in ten minutes", report);
        }
        if (rendering.exitValue() != 0) {
            return left(markdown, "mermaid-cli exited with " + rendering.exitValue(), report);
        }

        // Named by mermaid-cli after the output file, one per block, in the order they
        // were written. A missing one is a diagram that stays as it is; the rest go in.
        StringBuilder done = new StringBuilder();
        Matcher each = MERMAID.matcher(markdown);
        int number = 0, drawn = 0;
        while (each.find()) {
            number++;
            String name = "diagram-" + number + ".png";
            String replacement;
            if (Files.isRegularFile(folder.resolve(name))) {
                replacement = "![" + caption(markdown, each.start(), number)
                        + "](images/diagrams/" + name + ")";
                drawn++;
            } else {
                replacement = each.group();                 // the block, as it was
            }
            each.appendReplacement(done, Matcher.quoteReplacement(replacement));
        }
        each.appendTail(done);
        System.out.println("drew " + drawn + " of " + number + " diagram(s); mermaid-cli "
                + "said what it said in " + report);
        return done.toString();
    }

    /** What a diagram is called in the document: its number, and the section it is in. */
    private static String caption(String markdown, int at, int number) {
        String before = markdown.substring(0, at);
        Matcher headings = Pattern.compile("(?m)^#{1,4} (.+)$").matcher(before);
        String last = null;
        while (headings.find()) last = headings.group(1).trim();
        String name = "Diagram " + number;
        return last == null ? name : name + ": " + last.replace("]", "").replace("[", "");
    }

    /** The message, and the Markdown with its diagrams still in it as source. */
    private static String left(String markdown, String why, Path report) {
        System.out.println(why + ": the diagrams stay in the document as mermaid source. "
                + "What it said is in " + report);
        return markdown;
    }

    /** The first Chromium this machine has, or null to let npx bring its own. */
    private static String chromium() {
        String told = System.getenv("JROCK_CHROMIUM");
        if (told != null && !told.isBlank() && Files.isExecutable(Paths.get(told))) {
            return told;
        }
        for (String candidate : CHROMIUM) {
            if (Files.isExecutable(Paths.get(candidate))) return candidate;
        }
        return null;
    }

    /** One JSON string, which on Windows means the backslashes doubled. */
    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * The same document again, one DOCX per chapter, for a book of linked documents -
     * which is how a long manual is actually laid out: InDesign's Book panel takes one
     * document per chapter, numbers them itself, and builds one table of contents and
     * one index across the lot. A single 300-page DOCX is one story thread nobody can
     * work on in parallel; thirty-seven are thirty-seven jobs, and InCopy can have an
     * editor in one while the rest are being typeset.
     *
     * <p>A chapter is a level-two heading and everything under it, and the file is named
     * for the section mark the text uses: {@code 14-settings-files.docx} is what
     * <b>§14</b> in a sentence points at, in the README on the web and on paper alike.
     * What comes before the first chapter - the title, the sentence under it, the first
     * picture - is {@code 00-}, the front matter.
     *
     * <p>Every heading moves up one level on the way in, because a chapter of its own is
     * a document of its own: the chapter title becomes {@code Heading 1} and the sections
     * in it {@code Heading 2}, so each file reads from its own top rather than starting
     * at the second level of a document that is not there.
     */
    private static void chapters(String markdown, Path folder) throws Exception {
        List<String> lines = Arrays.asList(markdown.split("\n", -1));
        List<int[]> spans = new ArrayList<>();      // first line, last line
        List<String> titles = new ArrayList<>();
        boolean fenced = false;
        int from = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("```")) fenced = !fenced;
            if (fenced || !line.startsWith("## ") || line.startsWith("## Contents")) continue;
            spans.add(new int[] { from, i - 1 });
            from = i;
        }
        spans.add(new int[] { from, lines.size() - 1 });

        // The heading that opens each span names its file, and "" is the front matter,
        // which opens with the document's own H1 instead.
        for (int[] span : spans) {
            String first = lines.get(span[0]);
            titles.add(first.startsWith("## ") ? first.substring(3).trim() : "");
        }

        Files.createDirectories(folder);
        int written = 0;
        for (int c = 0; c < spans.size(); c++) {
            int[] span = spans.get(c);
            // The number is the chapter's place in the document and not how many have
            // been written: a chapter that fails must not renumber the ones after it,
            // because the section marks in the text are counted the same way.
            int number = c;
            StringBuilder chapter = new StringBuilder();
            boolean inside = false;
            for (int i = span[0]; i <= span[1]; i++) {
                String line = lines.get(i);
                if (line.startsWith("```")) inside = !inside;
                // Up one level, outside code - where a line beginning with # is a
                // comment in a shell script and nothing to do with a heading.
                if (!inside && line.startsWith("##")) line = line.substring(1);
                chapter.append(line).append('\n');
            }
            String name = String.format("%02d-%s.docx", number,
                    titles.get(c).isEmpty() ? "front-matter" : anchorOf(titles.get(c)));
            Path file = folder.resolve(name);
            String rejected = JRock.automationSetPrompt(chapter.toString());
            if (rejected != null) {
                System.out.println(name + ": " + rejected);
                continue;
            }
            String failure = JRock.automationExportPromptDocx(file.toString());
            if (failure != null) {
                System.out.println(name + ": " + failure);
                continue;
            }
            written++;
            System.out.println(String.format("  %-52s %7d bytes", name, Files.size(file)));
        }
        // The front matter is not a chapter and is not counted as one: the numbers
        // here are the section marks the README's own text uses, and §01 is the first
        // chapter rather than the title page.
        System.out.println("wrote " + (written - 1) + " chapter(s) and the front matter "
                + "into " + folder);
    }

    /** A heading's anchor, the way GitHub makes one - and the README's links expect. */
    private static String anchorOf(String heading) {
        StringBuilder out = new StringBuilder(heading.length());
        for (int i = 0; i < heading.length(); i++) {
            char c = Character.toLowerCase(heading.charAt(i));
            if (c == ' ') out.append('-');
            else if (Character.isLetterOrDigit(c) || c == '-' || c == '_') out.append(c);
        }
        return out.toString();
    }

    /** Every image reference in the text, in order, each one only the first time. */
    private static List<String> imagesIn(String markdown) throws IOException {
        List<String> found = new ArrayList<>();
        Matcher refs = IMAGE.matcher(markdown);
        while (refs.find()) {
            String target = refs.group(1).trim();
            if (target.startsWith("<") && target.endsWith(">")) {
                target = target.substring(1, target.length() - 1).trim();
            }
            if (target.contains("://")) continue;          // somebody else's picture
            if (!found.contains(target)) found.add(target);
        }
        return found;
    }

    private ReadmeDocx() {
    }
}
