import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exporting a log selection as RTF and DOCX, and reading a DOCX back as Markdown.
 * <p>
 * No GUI: the converters are Strings in and bytes out, which is what makes them
 * testable on their own. Both directions are checked against something other than
 * themselves - the RTF is read back by the JDK's {@code RTFEditorKit}, and the DOCX is
 * unzipped and parsed as XML - because a document that only this code can read is not a
 * document anyone can use.
 * <p>
 * The DOCX also carries the session's pictures, so the includes are handed in here as the
 * map JRock keeps them in: what is checked then is the package (the image bytes, its
 * content type, the relationship the drawing points through) and the arithmetic that
 * decides how big the picture is printed, which nothing else in the pipeline decides.
 *
 * @see JRockRtfIncludeTest for the same conversion the other way round, through the GUI
 */
class JRockMarkdownExportTest {

    /** A selection of the kind a model actually answers with, formatting and all. */
    private static final String MARKDOWN = String.join("\n",
            "# Quarterly Report",
            "",
            "Prepared by the **finance** team in *March*, in München.",
            "",
            "- Revenue up",
            "- Costs flat",
            "",
            "| Region | Revenue |",
            "| --- | ---: |",
            "| EMEA | 12 |",
            "");

    /**
     * What {@link #MARKDOWN} comes back as after a trip through DOCX and out again.
     * <p>
     * Not identical to it, and the two differences are the honest ones: the header row
     * of a table is written bold (it is a header), so it returns as bold markup; and the
     * column's right alignment is a layout decision that the import does not read back.
     * Everything that carries meaning - the heading, the emphasis, the list, the table's
     * shape and every character of its text - survives both ways.
     */
    private static final String ROUND_TRIPPED = String.join("\n",
            "# Quarterly Report",
            "",
            "Prepared by the **finance** team in *March*, in München.",
            "",
            "- Revenue up",
            "- Costs flat",
            "",
            "| **Region** | **Revenue** |",
            "| --- | --- |",
            "| EMEA | 12 |",
            "");

    /** The five parts of the smallest package a word processor will open. */
    private static final String[] DOCX_PARTS = {
            "[Content_Types].xml", "_rels/.rels", "word/_rels/document.xml.rels",
            "word/styles.xml", "word/document.xml" };

    /** A hash of the shape JRock gives an include: twelve lowercase hex digits. */
    private static final String HASH = "0123456789ab";

    @TempDir
    Path dir;

    @Test
    @DisplayName("the RTF is ASCII, reads back in Swing's own RTF reader, and has the table")
    void writesRtfAnyReaderCanOpen() throws Exception {
        Object document = export(MARKDOWN);
        byte[] bytes = rtf(document);

        // Pure ASCII, by design: every character above it is written as a control word,
        // so no encoding anywhere can change what the file says.
        for (byte b : bytes) {
            assertThat(b).describedAs("every byte of the RTF is ASCII").isBetween((byte) 1, (byte) 126);
        }
        String rtf = new String(bytes, StandardCharsets.US_ASCII);
        assertThat(rtf).startsWith("{\\rtf1\\ansi").endsWith("}\n");

        // Read back by the JDK's RTF reader - the same one JRock uses for the import
        // direction, and a third party as far as this writer is concerned.
        DefaultStyledDocument read = new DefaultStyledDocument();
        new RTFEditorKit().read(new ByteArrayInputStream(bytes), read, 0);
        String text = read.getText(0, read.getLength());
        assertThat(text).describedAs("the text a reader gets out of the RTF")
                .contains("Quarterly Report")           // the heading
                .contains("finance")                    // the bold run
                .contains("München")               // the escaped umlaut, decoded
                .contains("•")                     // the list bullet
                .contains("EMEA");                      // a table cell
        // Markdown's own markup is gone: it became formatting, which was the point.
        assertThat(text).doesNotContain("**").doesNotContain("# ");

        // The table, which is the half of a document RTFEditorKit cannot read and this
        // writer therefore has to get right by itself: a row of bordered cells.
        assertThat(rtf).contains("\\trowd").contains("\\clbrdrt").contains("\\cellx4819")
                .contains("\\cell ").contains("\\row");
    }

    @Test
    @DisplayName("the DOCX is a valid package: five parts, all well-formed, styles named")
    void writesDocxWithNamedStyles() throws Exception {
        Map<String, byte[]> parts = unzip(docx(export(MARKDOWN)));
        assertThat(parts.keySet()).describedAs("the parts of the package")
                .containsExactlyInAnyOrder(DOCX_PARTS);
        for (Map.Entry<String, byte[]> part : parts.entrySet()) {
            assertThatCode(() -> parse(part.getValue()))
                    .describedAs(part.getKey() + " parses as XML").doesNotThrowAnyException();
        }

        // The styles are why DOCX is the format to export for typesetting: a layout
        // application imports a document by mapping style NAMES onto its own.
        String styles = text(parts.get("word/styles.xml"));
        assertThat(styles).contains("w:styleId=\"Heading1\"").contains("w:val=\"heading 1\"")
                .contains("w:styleId=\"Code\"").contains("w:styleId=\"Quote\"");

        String body = text(parts.get("word/document.xml"));
        assertThat(body).describedAs("word/document.xml")
                .contains("<w:pStyle w:val=\"Heading1\"/>")   // the heading, as a style
                .contains("<w:b/>")                           // the bold run
                .contains("<w:i/>")                           // the italic one
                .contains("<w:tbl>").contains("<w:tblHeader/>")
                .contains("München");                    // UTF-8, as the part declares
    }

    @Test
    @DisplayName("a DOCX exported and then included comes back as the same Markdown")
    void readsItsOwnDocxBackAsMarkdown() throws Exception {
        Path file = Files.createTempFile("jrock-export", ".docx");
        try {
            Files.write(file, docx(export(MARKDOWN)));
            assertThat(docxAsMarkdown(file)).describedAs("the Markdown read back out")
                    .isEqualTo(ROUND_TRIPPED);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("markup that is wrong, or not markup at all, still exports")
    void convertsAnythingIntoSomething() throws Exception {
        // Every line here is something the rules do NOT cover, which is the point: the
        // parser's answer to anything it does not recognise is "a paragraph of text",
        // and an export that refuses a selection is worse than one that under-formats
        // it. A NUL is in there because a model's output is not guaranteed clean and
        // one control character would otherwise make the whole document.xml unreadable.
        String messy = String.join("\n",
                "a snake_case_name, 2*3, and a lone * asterisk",
                "| ragged | pipes",
                "#no space after the hash",
                "[unclosed link](http://example.com",
                "> quoted",
                "---",
                "text with a \u0000 control character",
                "```",
                "an unclosed fence");

        Object document = export(messy);
        assertThat(simplified(document))
                .describedAs("the parser coped, so nothing had to be given up on").isFalse();
        assertThat(blocks(document)).describedAs("blocks parsed").isGreaterThan(0);

        // The RTF still reads.
        DefaultStyledDocument read = new DefaultStyledDocument();
        new RTFEditorKit().read(new ByteArrayInputStream(rtf(document)), read, 0);
        assertThat(read.getText(0, read.getLength()))
                .contains("snake_case_name").contains("an unclosed fence");

        // And the DOCX is still a package, with the NUL dropped rather than written.
        Path file = Files.createTempFile("jrock-messy", ".docx");
        try {
            Files.write(file, docx(document));
            String markdown = docxAsMarkdown(file);
            assertThat(markdown)
                    // The underscores were part of a word, so they were never markup
                    // and are not escaped as if they had been.
                    .contains("snake_case_name")
                    // Whereas these asterisks were characters someone typed - no closing
                    // one, so no emphasis - and come back escaped, as characters.
                    .contains("2\\*3, and a lone \\* asterisk")
                    .contains("unclosed link")
                    .contains("> quoted")
                    .contains("an unclosed fence");
            assertThat(markdown).describedAs("the control character").doesNotContain("\u0000");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    @DisplayName("an included image is packed into the DOCX, pointed at, and named in the text")
    void placesTheImagesTheSelectionRefersTo() throws Exception {
        // What the new include filter writes into the prompt, and what the model hands
        // back in its answer: the picture, then the token that says which file it was.
        Path png = png("IMG_4002.png", 1000, 500);
        String markdown = String.join("\n",
                "Here is the photograph:",
                "",
                "![](" + HASH + ")",
                "@img " + HASH,
                "");

        Object document = export(markdown, Collections.singletonMap(HASH, png));
        assertThat(warnings(document)).describedAs("nothing to complain about").isEmpty();
        assertThat(images(document)).describedAs("pictures placed").isEqualTo(1);
        Map<String, byte[]> parts = unzip(docx(document));

        // 1. The bytes are in the package, untouched, under a part named for the package
        //    rather than for the file - and the package says what kind of part it is.
        assertThat(parts.keySet()).contains("word/media/image1.png");
        assertThat(parts.get("word/media/image1.png")).isEqualTo(Files.readAllBytes(png));
        assertThat(text(parts.get("[Content_Types].xml")))
                .contains("<Default Extension=\"png\" ContentType=\"image/png\"/>");

        // 2. The drawing points at it through a relationship, which is the only way a
        //    .docx refers to anything. rId1 is styles.xml, so a picture starts at rId2.
        assertThat(text(parts.get("word/_rels/document.xml.rels")))
                .contains("Id=\"rId2\"").contains("Target=\"media/image1.png\"")
                .contains("/relationships/image");
        String body = text(parts.get("word/document.xml"));
        assertThat(body).describedAs("word/document.xml")
                .contains("<w:drawing>").contains("r:embed=\"rId2\"")
                // Declared where it is used, and only when there is a picture to declare
                // it for.
                .contains("xmlns:pic=");

        // 3. 1000 px across a 6.69 in text frame would be 149 dpi, so the picture is
        //    placed at 300 dpi instead and comes out narrower than the frame: 1000/300 in
        //    = 3048 EMU per pixel, and the height follows the same factor.
        assertThat(body).describedAs("the placed size")
                .contains("<wp:extent cx=\"3048000\" cy=\"1524000\"/>")
                .contains("<a:ext cx=\"3048000\" cy=\"1524000\"/>");

        // 4. And the token below it became the name of the file, because a hash is
        //    JRock's handle on an attachment and says nothing to a reader.
        assertThat(body).contains("IMG_4002.png").doesNotContain(HASH);
        assertThatCode(() -> parse(parts.get("word/document.xml")))
                .describedAs("a document.xml with a drawing in it is still well-formed XML")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the page decides the size: text frame, or 300 dpi, whichever is smaller")
    void sizesEveryImageForTheA4PageAtNoLessThan300Dpi() throws Exception {
        // The text frame of A4 with 2 cm margins: 9638 x 14570 twips, which is 635 EMU
        // each. A picture gets as much of it as it can have without being stretched below
        // 300 dpi - so one of three limits binds, and which one depends on the shape.
        //
        // Wide and large: the frame's width binds (6120130 EMU / 4000 px = 1530 each).
        assertThat(extentOf(4000, 2000)).isEqualTo("cx=\"6120000\" cy=\"3060000\"");
        // Tall and large: the frame's height binds (9251950 / 4000 = 2312 each), which is
        // what keeps a portrait photograph on the page it was placed on.
        assertThat(extentOf(500, 4000)).isEqualTo("cx=\"1156000\" cy=\"9248000\"");
        // Small: 300 dpi binds (3048 EMU per pixel), and the picture sits well inside the
        // frame rather than being blown up into a blur.
        assertThat(extentOf(300, 200)).isEqualTo("cx=\"914400\" cy=\"609600\"");
    }

    @Test
    @DisplayName("landscape turns the page AND everything measured against it")
    void laysTheDocumentOutForWhicheverA4WasAskedFor() throws Exception {
        // The page itself, stated both ways a reader might read it: the two dimensions
        // swapped, and w:orient - one reader looks at the numbers, another at the word.
        String portrait = text(unzip(docx(export(MARKDOWN))).get("word/document.xml"));
        assertThat(portrait).describedAs("the portrait page")
                .contains("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .doesNotContain("w:orient");
        String landscape =
                text(unzip(docx(export(MARKDOWN, null, true))).get("word/document.xml"));
        assertThat(landscape).describedAs("the landscape page")
                .contains("<w:pgSz w:w=\"16838\" w:h=\"11906\" w:orient=\"landscape\"/>");

        // And the text frame with it, which is the half of this that a hardcoded portrait
        // layout would get wrong: a table is shared out of 14570 twips on a landscape
        // page, not out of the 9638 of a page it is not on.
        assertThat(portrait).describedAs("the portrait table").contains("<w:tblW w:w=\"9638\"");
        assertThat(landscape).describedAs("the landscape table")
                .contains("<w:tblW w:w=\"14570\"");

        // A picture too, and this is the reason to export landscape at all: a wide one is
        // placed against the wide side of the frame (9251950 EMU / 4000 px = 2312 each),
        // so it comes out half again as large as the same picture on a portrait page.
        assertThat(extentOf(4000, 2000, true)).isEqualTo("cx=\"9248000\" cy=\"4624000\"");
        assertThat(extentOf(4000, 2000, false)).isEqualTo("cx=\"6120000\" cy=\"3060000\"");
        // A tall one on a landscape page is held to the frame's height, which is now the
        // page's short side (6120130 / 4000 = 1530) - one page still, not two.
        assertThat(extentOf(500, 4000, true)).isEqualTo("cx=\"765000\" cy=\"6120000\"");
    }

    @Test
    @DisplayName("a reference to something not included is a warning, not a failed export")
    void leavesUnplaceableReferencesAsTheTextTheyAre() throws Exception {
        // Three ways a reference can fail to be a picture: a hash from another session
        // (the includes do not survive a restart), an include that is not an image at
        // all, and an @img token for either of them.
        Path notAnImage = dir.resolve("notes.txt");
        Files.write(notAnImage, "plain text".getBytes(StandardCharsets.UTF_8));
        String other = "ffffffffffff";
        String markdown = String.join("\n",
                "![](" + HASH + ")",
                "@img " + HASH,
                "",
                "![](" + other + ")",
                "",
                "![alt](http://example.com/cat.png)",
                "");

        Object document = export(markdown, Collections.singletonMap(other, notAnImage));
        assertThat(images(document)).describedAs("nothing placeable, so nothing placed")
                .isEqualTo(0);
        assertThat(warnings(document)).describedAs("what the log is told")
                .hasSize(2)
                .anySatisfy(line -> assertThat(line)
                        .contains("No image is included under the hash " + HASH)
                        .endsWith("its reference is left in the text as it stands."))
                .anySatisfy(line -> assertThat(line).contains("notes.txt")
                        .contains("not a PNG, JPEG, GIF or WEBP"));

        Map<String, byte[]> parts = unzip(docx(document));
        assertThat(parts.keySet()).describedAs("no media part for a picture there is none of")
                .containsExactlyInAnyOrder(DOCX_PARTS);
        String body = text(parts.get("word/document.xml"));
        assertThat(body).describedAs("word/document.xml")
                .doesNotContain("<w:drawing>")
                // Left exactly as it stands, so the reader sees that something was meant
                // to be here - and the @img token too, since there is no file to name.
                .contains("![](" + HASH + ")")
                .contains("@img " + HASH)
                .contains("![](" + other + ")")
                // An ordinary Markdown image is not a JRock reference at all, and goes on
                // being written the way a link is: its text, then its address.
                .contains("alt (http://example.com/cat.png)");
    }

    // ---- the classes under test, which are private to JRock ----

    /** {@code JRock.MarkdownExport.of(markdown)}: the parsed document. */
    private static Object export(String markdown) throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        return of.invoke(null, markdown);
    }

    /** The same, with the session's includes, which is what the DOCX export passes. */
    private static Object export(String markdown, Map<String, Path> includes) throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class, Map.class);
        of.setAccessible(true);
        return of.invoke(null, markdown, includes);
    }

    /** And with the page the save dialog asked for: landscape A4 instead of portrait. */
    private static Object export(String markdown, Map<String, Path> includes,
                                 boolean landscape) throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class, Map.class, boolean.class);
        of.setAccessible(true);
        return of.invoke(null, markdown, includes, landscape);
    }

    private static byte[] rtf(Object document) throws Exception {
        return (byte[]) call(document, "rtf");
    }

    private static byte[] docx(Object document) throws Exception {
        return (byte[]) call(document, "docx");
    }

    private static int blocks(Object document) throws Exception {
        return (Integer) call(document, "blocks");
    }

    private static boolean simplified(Object document) throws Exception {
        return (Boolean) call(document, "simplified");
    }

    private static int images(Object document) throws Exception {
        return (Integer) call(document, "images");
    }

    @SuppressWarnings("unchecked")
    private static List<String> warnings(Object document) throws Exception {
        return (List<String>) call(document, "warnings");
    }

    /** The wp:extent of the one picture in a document that places an image of this size. */
    private String extentOf(int width, int height) throws Exception {
        return extentOf(width, height, false);
    }

    /** The same, on whichever A4 the export was asked for. */
    private String extentOf(int width, int height, boolean landscape) throws Exception {
        Path file = png(width + "x" + height + ".png", width, height);
        Object document = export("![](" + HASH + ")",
                Collections.singletonMap(HASH, file), landscape);
        String body = text(unzip(docx(document)).get("word/document.xml"));
        int at = body.indexOf("<wp:extent ");
        assertThat(at).describedAs("a wp:extent in " + body).isNotNegative();
        return body.substring(at + "<wp:extent ".length(), body.indexOf("/>", at));
    }

    /** A real PNG of exactly this pixel size, which is what the placement is read from. */
    private Path png(String name, int width, int height) throws Exception {
        Path file = dir.resolve(name);
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                "png", file.toFile())).describedAs("the JDK wrote the PNG").isTrue();
        return file;
    }

    private static Object call(Object document, String name) throws Exception {
        Method method = document.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(document);
    }

    /** {@code JRock.DocxMarkdown.of(file)}: the import side, on one .docx. */
    private static String docxAsMarkdown(Path file) throws Exception {
        Method of = Class.forName("JRock$DocxMarkdown").getDeclaredMethod("of", Path.class);
        of.setAccessible(true);
        return (String) of.invoke(null, file);
    }

    // ---- reading a package, the way anything else opening it would ----

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                parts.put(entry.getName(), out.toByteArray());
            }
        }
        return parts;
    }

    private static void parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String text(byte[] part) {
        return new String(part, StandardCharsets.UTF_8);
    }
}
