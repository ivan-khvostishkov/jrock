import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a small .docx, by hand, and states the Markdown it has to become.
 * <p>
 * Hand-rolled rather than exported by JRock itself, for the reason {@link FormattedRtf}
 * and {@link A4Pdf} are: a converter checked against its own output proves only that it
 * is self-consistent. Every element here is one a real Word document contains and this
 * conversion has an opinion about - a heading style spelled with a space, runs split
 * mid-word the way Word splits them, a toggle switched off with {@code w:val="false"},
 * list markup that carries no bullet character at all, a {@code w:tab} inside a run, a
 * hyperlink (whose URL lives in a part this conversion does not read), a table with a
 * bold header row, a non-ASCII character, and asterisks that are text and not markup.
 * <p>
 * {@link #MARKDOWN} is the whole expected output, not a fragment: what a conversion
 * leaves out or adds matters as much as what it gets right.
 * <p>
 * NOT named *Test, so Surefire doesn't try to run it.
 */
final class FormattedDocx {

    private static final String CONTENT_TYPES = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
            "<Default Extension=\"rels\" ContentType=\"application/",
            "vnd.openxmlformats-package.relationships+xml\"/>",
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/",
            "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>",
            "</Types>");

    private static final String RELS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/",
            "relationships\"><Relationship Id=\"rId1\" Type=\"http://",
            "schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\"",
            " Target=\"word/document.xml\"/></Relationships>");

    /** The document body. Indented for reading; whitespace between elements is ignored. */
    private static final String DOCUMENT = String.join("\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<w:document"
                    + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships\"><w:body>",
            // A heading, and a second one whose style is spelled the other way.
            "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>",
            "<w:r><w:t>Quarterly Report</w:t></w:r></w:p>",
            "<w:p><w:pPr><w:pStyle w:val=\"heading 2\"/></w:pPr>",
            "<w:r><w:t>Over</w:t></w:r><w:r><w:t>view</w:t></w:r></w:p>",
            // Body text: bold split across two runs, an umlaut, italic, and a run that
            // switches italic back off rather than simply not having it.
            "<w:p><w:r><w:t xml:space=\"preserve\">Prepared by the </w:t></w:r>",
            "<w:r><w:rPr><w:b/></w:rPr><w:t>fin</w:t></w:r>",
            "<w:r><w:rPr><w:b/><w:bCs/></w:rPr><w:t>ance</w:t></w:r>",
            "<w:r><w:t xml:space=\"preserve\"> team in München, </w:t></w:r>",
            "<w:r><w:rPr><w:i/></w:rPr><w:t>in March</w:t></w:r>",
            "<w:r><w:rPr><w:i w:val=\"false\"/></w:rPr><w:t>.</w:t></w:r></w:p>",
            // A list: the bullet is in the numbering, not in the text.
            "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr>",
            "</w:pPr><w:r><w:t>Revenue up</w:t></w:r></w:p>",
            "<w:p><w:pPr><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"2\"/></w:numPr>",
            "</w:pPr><w:r><w:t>Costs</w:t></w:r><w:r><w:tab/><w:t>flat</w:t></w:r></w:p>",
            "<w:p><w:pPr><w:pStyle w:val=\"Quote\"/></w:pPr>",
            "<w:r><w:t>A quotation.</w:t></w:r></w:p>",
            // A hyperlink: its text is in a run, its URL in the relationships part.
            "<w:p><w:hyperlink r:id=\"rId9\"><w:r><w:rPr><w:rStyle w:val=\"Hyperlink\"/>",
            "</w:rPr><w:t>the repository</w:t></w:r></w:hyperlink></w:p>",
            // A table, header row in bold, as Word writes one.
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>",
            "<w:tblGrid><w:gridCol w:w=\"4819\"/><w:gridCol w:w=\"4819\"/></w:tblGrid>",
            "<w:tr><w:trPr><w:tblHeader/></w:trPr>",
            "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Region</w:t></w:r></w:p></w:tc>",
            "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Revenue</w:t></w:r></w:p></w:tc></w:tr>",
            "<w:tr><w:tc><w:p><w:r><w:t>EMEA</w:t></w:r></w:p></w:tc>",
            "<w:tc><w:p><w:r><w:t>12</w:t></w:r></w:p></w:tc></w:tr></w:tbl>",
            "<w:p><w:r><w:t>An asterisk * and a 2*3 stay text.</w:t></w:r></w:p>",
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>",
            "</w:body></w:document>");

    /**
     * The Markdown {@link #DOCUMENT} converts to, byte for byte.
     * <p>
     * Note what is <em>not</em> here: no emphasis inside the headings (the style said
     * what they are, so saying it twice is noise), no URL for the hyperlink (it is in
     * the relationships part, and what the reader of the document sees is the text),
     * and no right alignment on the table (Markdown's separator row can carry it, but
     * the alignment is the document's layout rather than its meaning). The table header
     * keeps the bold it was written in, the asterisks of the last line are escaped -
     * they were characters someone typed - and the umlaut is a character, as UTF-8.
     */
    static final String MARKDOWN = String.join("\n",
            "# Quarterly Report",
            "",
            "## Overview",
            "",
            "Prepared by the **finance** team in München, *in March*.",
            "",
            "- Revenue up",
            "- Costs flat",
            "",
            "> A quotation.",
            "",
            "the repository",
            "",
            "| **Region** | **Revenue** |",
            "| --- | --- |",
            "| EMEA | 12 |",
            "",
            "An asterisk \\* and a 2\\*3 stay text.",
            "");

    private FormattedDocx() { }

    /** Writes the document to {@code file} and returns it. */
    static Path write(Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", RELS);
            entry(zip, "word/document.xml", DOCUMENT);
        }
        Files.write(file, bytes.toByteArray());
        return file;
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
