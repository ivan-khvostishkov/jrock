import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
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

        // A FOLDER OF ITS OWN, and not the repository root - which is the obvious place
        // and the wrong one. JRock works in a folder and keeps its settings in it, so
        // running this at a desk would write a __JRock/ into the checkout and overwrite
        // the endpoint, the model and the settings of whoever works in that folder. So
        // the pictures the README refers to are copied into a throwaway folder under the
        // same relative names and JRock is pointed at that, which is also what makes
        // "images/x.png" resolve (see refTargetHash in JRock.java).
        Path root = Files.createTempDirectory("jrock-readme-docx");
        List<String> references = imagesIn(withoutToc);
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
        String rejected = JRock.automationSetPrompt(withoutToc);
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
                + Files.size(target) + " bytes, from " + withoutToc.length()
                + " characters of Markdown");
        System.exit(0);
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
