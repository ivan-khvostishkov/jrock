import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.rtf.RTFEditorKit;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exporting a log selection as RTF and DOCX, and reading a DOCX back as Markdown.
 * <p>
 * No GUI: the converters are Strings in and bytes out, which is what makes them
 * testable on their own. Both directions are checked against something other than
 * themselves - the RTF is read back by the JDK's {@code RTFEditorKit}, and the DOCX is
 * unzipped and parsed as XML - because a document that only this code can read is not a
 * document anyone can use.
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

    // ---- the classes under test, which are private to JRock ----

    /** {@code JRock.MarkdownExport.of(markdown)}: the parsed document. */
    private static Object export(String markdown) throws Exception {
        Method of = Class.forName("JRock$MarkdownExport")
                .getDeclaredMethod("of", String.class);
        of.setAccessible(true);
        return of.invoke(null, markdown);
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
