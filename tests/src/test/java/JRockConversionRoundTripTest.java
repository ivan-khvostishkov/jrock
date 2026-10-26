import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Markdown out to a document and back again, and the same document out of it a second
 * time: the invariant that makes typesetting with this stable.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The .docx is not an export in the sense of a dead end - it is the form of the book
 * a typesetter works in, and the Markdown is the source it came from. So the two have to
 * be the same book. If placing a regenerated .docx gives a document wearing different
 * styles from the last one, every hour spent setting those styles is spent again; and if
 * a .docx read back as Markdown comes back as flat prose, an edit made in the layout can
 * never return to the source.
 *
 * <p>What makes it work is that the paragraph is NAMED. A caption is italic because the
 * Caption style is italic, a header row is bold because Table head is, an item is at the
 * third level because its style is List 3 - and none of those leave a mark in the text
 * the way {@code **bold**} does. A converter reading the text alone loses every one of
 * them, which is what this pins down: the export writes the names, and the import reads
 * them.
 *
 * <h2>The one honest difference</h2>
 *
 * <p>A table's column alignment ({@code :---:}, {@code ---:}) is a layout decision and
 * is not read back, so the fixtures here do not use it. A picture's reference changes by
 * necessity - the import writes the picture out as a file and refers to it by that name -
 * so that is asserted by shape rather than by equality.
 *
 * <h2>RTF is not this, and is not meant to be</h2>
 *
 * <p>Only the .docx direction is symmetric, and that is the division of labour: the
 * .docx carries text, tables and pictures and is the form a layout application places;
 * RTF carries text and tables for anything that opens a word processor file, and carries
 * no pictures at all.
 *
 * <p>Its round trip is lossy, and the reason is the reader rather than the writer. The
 * RTF written here has real tables ({@code \trowd}, {@code \cell}) and a monospace font
 * for code in it; the import goes through Swing's {@code RTFEditorKit}, which has no
 * notion of a table - the cells arrive as one run of text - and none of a paragraph
 * style, so a heading can only be guessed at from its point size. Reading it exactly
 * would mean not using the editor kit at all, and a converter that GUESSES is not one to
 * build a book on, so the asymmetry is accepted rather than approximated. What survives
 * is asserted below; what does not is deliberately left unasserted, so that nothing here
 * has to be deleted if that ever changes.
 *
 * @see JRockMarkdownExportTest for the converters examined part by part
 */
class JRockConversionRoundTripTest {

    @TempDir
    Path dir;

    /** The hash an include is referred to by, as the session's map keys them. */
    private static final String HASH = "0123456789ab";

    /**
     * One of everything the converter knows, and no pictures: this one is asserted
     * character for character, both ways round.
     */
    private static final String EVERYTHING = String.join("\n",
            "# The Title",
            "",
            "## A Section",
            "",
            "Body text with **bold**, *italic* and `code` in it.",
            "",
            "- first item",
            "- second item",
            "  - nested item",
            "",
            "> a quoted paragraph",
            "",
            "```",
            "code line one",
            "    code line two, indented",
            "```",
            "",
            "| Region | Revenue |",
            "| --- | --- |",
            "| EMEA | 12 |",
            "",
            "Closing paragraph.",
            "");

    /** The styles that document is written with, in the order it writes them. */
    private static final List<String> EVERYTHING_STYLES = List.of(
            "Heading1", "Heading2", "(none)",
            "List1", "List1", "List2",
            "Quote",
            "Code",
            "TableHead", "TableHead", "TableText", "TableText", "TableSpace",
            "(none)");

    @Test
    @DisplayName("Markdown through a .docx and back is the same Markdown")
    void markdownSurvivesTheDocx() throws Exception {
        Path file = dir.resolve("everything.docx");
        Files.write(file, docx(export(EVERYTHING)));

        assertThat(docxAsMarkdown(file))
                .describedAs("the Markdown a .docx of it reads back as")
                .isEqualTo(EVERYTHING);
    }

    @Test
    @DisplayName("and the .docx written from THAT Markdown wears the same styles")
    void theStylesAreStableAcrossARoundTrip() throws Exception {
        Path first = dir.resolve("first.docx");
        Files.write(first, docx(export(EVERYTHING)));
        byte[] second = docx(export(docxAsMarkdown(first)));

        // The whole point: place this one, set the styles, regenerate, place it again -
        // and the paragraphs are wearing what they wore before.
        assertThat(styles(second))
                .describedAs("the styles of the .docx written after a round trip")
                .isEqualTo(styles(docx(export(EVERYTHING))))
                .isEqualTo(EVERYTHING_STYLES);
    }

    @Test
    @DisplayName("a picture and its caption come back as a picture and its caption")
    void aFigureAndItsCaptionSurvive() throws Exception {
        Path png = png("shot.png", 600, 400);
        String markdown = "![a screenshot](" + HASH + ")\n\n"
                + "*The caption under it, with `code` inside.*\n";
        Path file = dir.resolve("figure.docx");
        Files.write(file, docx(export(markdown, Collections.singletonMap(HASH, png))));

        // Named in the document, which is what a layout application maps.
        assertThat(styles(Files.readAllBytes(file)))
                .describedAs("the figure and the caption are styled as such")
                .containsSequence("Figure", "Caption");

        String back = docxAsMarkdown(file);
        // The picture is written out beside the Markdown, so its reference names that
        // file rather than the hash it went in as - and its alt text is the file's name.
        assertThat(back).describedAs("the Markdown read back out")
                .containsPattern("!\\[shot\\.png\\]\\([^)]+\\.png\\)")
                // The caption's italics are the style's, so the import has to put the
                // markup back from the NAME - there is nothing in the text to read.
                .contains("*The caption under it, with `code` inside.*");

        // And the picture is really there, as a file the Markdown can be read with.
        Matcher m = Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)").matcher(back);
        assertThat(m.find()).describedAs("a picture reference in " + back).isTrue();
        assertThat(dir.resolve(m.group(1))).describedAs("the picture it refers to")
                .exists();
    }

    @Test
    @DisplayName("a code block keeps its lines, as line breaks in one paragraph")
    void aCodeBlockIsOneParagraphOfLines() throws Exception {
        byte[] bytes = docx(export(EVERYTHING));
        String body = part(bytes, "word/document.xml");

        // One Code paragraph for the block, not one per line: the space above and below
        // a block belongs to the block, and a paragraph per line has nowhere to put it.
        assertThat(count(body, "<w:pStyle w:val=\"Code\"/>"))
                .describedAs("Code paragraphs in " + body).isEqualTo(1);
        assertThat(count(body, "<w:br/>"))
                .describedAs("line breaks inside it").isEqualTo(1);

        // And the lines come back as lines, indentation and all.
        Path file = dir.resolve("code.docx");
        Files.write(file, bytes);
        assertThat(docxAsMarkdown(file))
                .contains("```\ncode line one\n    code line two, indented\n```");
    }

    @Test
    @DisplayName("the .docx says where its content may legally say it")
    void theMarkupIsWhereTheSchemaPutsIt() throws Exception {
        String body = part(docx(export(EVERYTHING)), "word/document.xml");

        // Asserted because a layout application tolerates both of these while a word
        // processor offers to repair the document - which is the worst way for markup to
        // be wrong, since nothing says so until somebody else opens it. w:br is run
        // content; w:rStyle belongs inside w:rPr.
        assertThat(body).describedAs("a w:br outside a run")
                .doesNotContain("</w:pPr><w:br/>").doesNotContain("<w:p><w:br/>");
        assertThat(body).describedAs("a w:rStyle outside w:rPr")
                .doesNotContain("<w:r><w:rStyle");
        // One rPr per run at most, which is what the schema allows.
        for (String run : body.split("<w:r>")) {
            assertThat(count(run.split("</w:r>")[0], "<w:rPr>"))
                    .describedAs("run properties in a single run: " + run)
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    @DisplayName("the style names are the agreed list, and adding one is deliberate")
    void theNamedStylesAreTheOnesDocumented() throws Exception {
        String styles = part(docx(export(EVERYTHING)), "word/styles.xml");
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("<w:name w:val=\"([^\"]+)\"/>").matcher(styles);
        while (m.find()) names.add(m.group(1));

        // The README's own table of these (see "The style names a layout application
        // maps") is what a reader sets up their template from, so a name added or
        // renamed here is a change to the documented interface rather than an
        // implementation detail.
        assertThat(names).describedAs("the styles " + styles + " declares")
                .containsExactly("Normal",
                        "heading 1", "heading 2", "heading 3",
                        "heading 4", "heading 5", "heading 6",
                        "Code", "List 1", "List 2", "List 3", "List 4", "List 5",
                        "Quote", "Figure", "Caption",
                        "Table text", "Table head", "Table space", "Book table",
                        "Hyperlink", "Code inline");
    }

    @Test
    @DisplayName("RTF keeps the prose, the headings and the emphasis")
    void whatTheRtfRoundTripKeeps() throws Exception {
        Path file = dir.resolve("everything.rtf");
        Files.write(file, rtf(export(EVERYTHING)));
        String back = rtfAsMarkdown(file);

        // Asserted because it holds. The rest of what this fixture carries - the table,
        // the fence, the quote, the nesting, the inline code - does not survive the
        // editor kit the import reads with, and is left to the reader that replaces it
        // (see this class's own notes).
        assertThat(back).describedAs("the Markdown an RTF of it reads back as")
                .contains("# The Title")
                .contains("## A Section")
                .contains("**bold**")
                .contains("*italic*")
                .contains("- first item")
                .contains("Closing paragraph.");
    }

    // ---- the converters, which are private to JRock ----

    private static Object export(String markdown) throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        return of.invoke(null, markdown);
    }

    private static Object export(String markdown, Map<String, Path> includes)
            throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class, Map.class);
        of.setAccessible(true);
        return of.invoke(null, markdown, includes);
    }

    private static byte[] docx(Object document) throws Exception {
        return (byte[]) call(document, "docx");
    }

    private static byte[] rtf(Object document) throws Exception {
        return (byte[]) call(document, "rtf");
    }

    private static Object call(Object document, String name) throws Exception {
        Method method = document.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(document);
    }

    /** {@code JRock.DocxMarkdown.of(file, dir).markdown}. */
    private String docxAsMarkdown(Path file) throws Exception {
        Method of = Class.forName("JRock$DocxMarkdown")
                .getDeclaredMethod("of", Path.class, Path.class);
        of.setAccessible(true);
        Object converted = of.invoke(null, file, dir);
        Field field = converted.getClass().getDeclaredField("markdown");
        field.setAccessible(true);
        return (String) field.get(converted);
    }

    /** {@code JRock.RtfMarkdown.of(file)}. */
    private static String rtfAsMarkdown(Path file) throws Exception {
        Method of = Class.forName("JRock$RtfMarkdown").getDeclaredMethod("of", Path.class);
        of.setAccessible(true);
        return (String) of.invoke(null, file);
    }

    // ---- reading the package the way anything else opening it would ----

    /** The paragraph styles a .docx wears, in the order its body writes them. */
    private static List<String> styles(byte[] docx) throws Exception {
        String body = part(docx, "word/document.xml");
        List<String> found = new ArrayList<>();
        // Every paragraph, with the name it carries or "(none)" for the ones that take
        // the document's default - both matter to the sequence.
        Matcher m = Pattern.compile("<w:p(?:/>|>(?:<w:pPr>(?:<w:pStyle w:val=\"([^\"]+)\"/>)?)?)")
                .matcher(body);
        while (m.find()) {
            // The empty paragraph the body ends on is structure, not content.
            if (m.group(0).equals("<w:p/>")) continue;
            found.add(m.group(1) == null ? "(none)" : m.group(1));
        }
        return found;
    }

    private static int count(String text, String needle) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) {
            n++;
            at += needle.length();
        }
        return n;
    }

    private static String part(byte[] zip, String name) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                in.transferTo(out);
                parts.put(entry.getName(), out.toByteArray());
            }
        }
        assertThat(parts).describedAs("the parts of the package").containsKey(name);
        return new String(parts.get(name), StandardCharsets.UTF_8);
    }

    /** A real PNG, which is what the placement arithmetic is read from. */
    private Path png(String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        assertThat(ImageIO.write(new BufferedImage(width, height,
                BufferedImage.TYPE_INT_RGB), "png", file.toFile()))
                .describedAs("the JDK wrote the PNG").isTrue();
        return file;
    }
}
