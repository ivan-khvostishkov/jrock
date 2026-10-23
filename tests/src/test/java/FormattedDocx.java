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
 * hyperlink whose URL lives in the relationships part, the same link written as a field
 * both ways Word writes one, a table with a bold header row, a non-ASCII character, and
 * asterisks that are text and not markup.
 * <p>
 * And the pictures, in both shapes a document holds one: a {@code w:drawing} whose
 * {@code a:blip} names the picture's part (placed twice, once with alternative text and
 * once inline in a sentence with none) and the older {@code w:pict} whose
 * {@code v:imagedata} names it. {@link #PICTURES} is what they have to be written as.
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
            "<Default Extension=\"png\" ContentType=\"image/png\"/>",
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/",
            "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>",
            "</Types>");

    private static final String RELS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/",
            "relationships\"><Relationship Id=\"rId1\" Type=\"http://",
            "schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\"",
            " Target=\"word/document.xml\"/></Relationships>");

    /**
     * The document's own relationships: where its w:hyperlink's address is kept, and
     * which part each of its pictures is stored in. The last one is a picture the
     * document only LINKS to, which has no bytes in the package and is therefore no
     * picture to write out or to refer to.
     */
    private static final String DOCUMENT_RELS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/",
            "relationships\"><Relationship Id=\"rId9\" Type=\"http://",
            "schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\"",
            " Target=\"https://github.com/ivan-khvostishkov/jrock\" TargetMode=\"External\"/>",
            "<Relationship Id=\"rId10\" Type=\"http://schemas.openxmlformats.org/",
            "officeDocument/2006/relationships/image\" Target=\"media/photo.png\"/>",
            "<Relationship Id=\"rId11\" Type=\"http://schemas.openxmlformats.org/",
            "officeDocument/2006/relationships/image\" Target=\"media/logo.png\"/>",
            "<Relationship Id=\"rId12\" Type=\"http://schemas.openxmlformats.org/",
            "officeDocument/2006/relationships/image\"",
            " Target=\"https://example.com/elsewhere.png\" TargetMode=\"External\"/>",
            "<Relationship Id=\"rId13\" Type=\"http://schemas.openxmlformats.org/",
            "officeDocument/2006/relationships/image\" Target=\"media/chart.emf\"/>",
            "</Relationships>");

    /** The document body. Indented for reading; whitespace between elements is ignored. */
    private static final String DOCUMENT = String.join("\n",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<w:document"
                    + " xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                    + "relationships\""
                    + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/"
                    + "wordprocessingDrawing\""
                    + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                    + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
                    + " xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>",
            // A heading, and a second one whose style is spelled the other way.
            "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr>",
            "<w:r><w:t>Quarterly Report</w:t></w:r></w:p>",
            "<w:p><w:pPr><w:pStyle w:val=\"heading 2\"/></w:pPr>",
            "<w:r><w:t>Over</w:t></w:r><w:r><w:t>view</w:t></w:r></w:p>",
            // A picture in a paragraph of its own, with the alternative text Word's Alt
            // Text box writes into wp:docPr's descr.
            "<w:p>" + drawing("rId10", " descr=\"The team in March\"") + "</w:p>",
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
            // The same thing as a field, both ways Word writes one: spelled out over
            // runs (begin, instruction, separate, the text, end), and as a w:fldSimple -
            // whose text and address need brackets and parentheses escaped.
            "<w:p><w:r><w:t xml:space=\"preserve\">Read </w:t></w:r>",
            "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>",
            "<w:r><w:instrText xml:space=\"preserve\"> HYPERLINK </w:instrText></w:r>",
            "<w:r><w:instrText>\"https://example.com/notes\"</w:instrText></w:r>",
            "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>",
            "<w:r><w:rPr><w:i/></w:rPr><w:t>the notes</w:t></w:r>",
            "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>",
            "<w:r><w:t xml:space=\"preserve\"> and </w:t></w:r>",
            "<w:fldSimple w:instr=\" HYPERLINK &quot;https://example.com/draft(1)&quot; \">",
            "<w:r><w:t>the [draft]</w:t></w:r></w:fldSimple><w:r><w:t>.</w:t></w:r></w:p>",
            // A table, header row in bold, as Word writes one.
            "<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/></w:tblPr>",
            "<w:tblGrid><w:gridCol w:w=\"4819\"/><w:gridCol w:w=\"4819\"/></w:tblGrid>",
            "<w:tr><w:trPr><w:tblHeader/></w:trPr>",
            "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Region</w:t></w:r></w:p></w:tc>",
            "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Revenue</w:t></w:r></w:p></w:tc></w:tr>",
            "<w:tr><w:tc><w:p><w:r><w:t>EMEA</w:t></w:r></w:p></w:tc>",
            "<w:tc><w:p><w:r><w:t>12</w:t></w:r></w:p></w:tc></w:tr></w:tbl>",
            "<w:p><w:r><w:t>An asterisk * and a 2*3 stay text.</w:t></w:r></w:p>",
            // The same picture again, inside a sentence and with no alternative text -
            // and the older VML picture after it, which is how a document written by
            // something other than a recent Word holds one.
            "<w:p><w:r><w:t xml:space=\"preserve\">The photo </w:t></w:r>",
            drawing("rId10", ""),
            "<w:r><w:t xml:space=\"preserve\"> again, and the logo </w:t></w:r>",
            "<w:r><w:pict><v:shape style=\"width:60pt;height:20pt\">",
            "<v:imagedata r:id=\"rId11\"/></v:shape></w:pict></w:r>",
            "<w:r><w:t>.</w:t></w:r></w:p>",
            // A picture the document only links to on the web: nothing of it is in the
            // package, so there is nothing to write out and nothing to refer to. Nor is
            // the EMF beside it, which is the format a pasted chart arrives in and is a
            // format no request carries.
            "<w:p>" + drawing("rId12", " descr=\"Somewhere else\"")
                    + drawing("rId13", " descr=\"A pasted chart\"") + "</w:p>",
            "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>",
            "</w:body></w:document>");

    // One picture in the text flow, as DrawingML writes it: the frame it is given, the
    // alternative text, and the a:blip naming the part its bytes are in.
    private static String drawing(String relationship, String descr) {
        return String.join("",
                "<w:r><w:drawing><wp:inline>",
                "<wp:extent cx=\"914400\" cy=\"609600\"/>",
                "<wp:docPr id=\"1\" name=\"Picture 1\"", descr, "/>",
                "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/",
                "drawingml/2006/picture\"><pic:pic><pic:nvPicPr>",
                "<pic:cNvPr id=\"1\" name=\"photo.png\"/><pic:cNvPicPr/></pic:nvPicPr>",
                "<pic:blipFill><a:blip r:embed=\"", relationship, "\"/>",
                "<a:stretch><a:fillRect/></a:stretch></pic:blipFill>",
                "</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>");
    }

    /**
     * The name the document has to be written under for {@link #MARKDOWN} to be its
     * Markdown: a picture is named after the whole of the document's name, so the two
     * cannot be read apart.
     */
    static final String NAME = "quarterly.docx";

    /**
     * The Markdown {@link #DOCUMENT} converts to, byte for byte, when it is written as
     * {@link #NAME}.
     * <p>
     * Note what is <em>not</em> here: no emphasis inside the headings (the style said
     * what they are, so saying it twice is noise), and no right alignment on the table
     * (Markdown's separator row can carry it, but the alignment is the document's layout
     * rather than its meaning). Every link keeps its address, as Markdown's
     * {@code [text](address)}, with its text formatted as its runs were. The table header
     * keeps the bold it was written in, the asterisks of the last line are escaped -
     * they were characters someone typed - and the umlaut is a character, as UTF-8.
     * <p>
     * Each picture is a reference to the file it was written to, where the document draws
     * it: the alternative text the document gives it in the square brackets, or the file's
     * own name when it gives it none. The picture the document only links to on the web is
     * in none of them, nor is the EMF - no request carries one - and the paragraph that
     * held nothing but those two is not a paragraph of the Markdown at all.
     */
    static final String MARKDOWN = String.join("\n",
            "# Quarterly Report",
            "",
            "## Overview",
            "",
            "![The team in March](quarterly.docx.photo.png)",
            "",
            "Prepared by the **finance** team in München, *in March*.",
            "",
            "- Revenue up",
            "- Costs flat",
            "",
            "> A quotation.",
            "",
            "[the repository](https://github.com/ivan-khvostishkov/jrock)",
            "",
            "Read [*the notes*](https://example.com/notes) and "
                    + "[the \\[draft\\]](https://example.com/draft%281%29).",
            "",
            "| **Region** | **Revenue** |",
            "| --- | --- |",
            "| EMEA | 12 |",
            "",
            "An asterisk \\* and a 2\\*3 stay text.",
            "",
            "The photo ![quarterly.docx.photo.png](quarterly.docx.photo.png) again, and "
                    + "the logo ![quarterly.docx.logo.png](quarterly.docx.logo.png).",
            "");

    /**
     * The files the pictures of {@link #DOCUMENT} are written as, in the order the
     * document places them - the same two names {@link #MARKDOWN} refers to, the one
     * placed twice written once.
     */
    static final java.util.List<String> PICTURES =
            java.util.List.of("quarterly.docx.photo.png", "quarterly.docx.logo.png");

    /**
     * The pixel size of each picture, which the include logs and the DOCX export reads.
     * Two sizes, so the two pictures are two files and not one: a picture is included
     * under the hash of its bytes, and two identical files are one include under two
     * names.
     */
    static final int PHOTO_WIDTH = 40, PHOTO_HEIGHT = 24;
    static final int LOGO_WIDTH = 60, LOGO_HEIGHT = 20;

    private FormattedDocx() { }

    /** Writes the document to {@code file} and returns it. */
    static Path write(Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", RELS);
            entry(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS);
            entry(zip, "word/document.xml", DOCUMENT);
            // Real PNGs, because what comes out of the package is included as a picture:
            // its header is read for the size and its first bytes for the kind.
            entry(zip, "word/media/photo.png", png(PHOTO_WIDTH, PHOTO_HEIGHT));
            entry(zip, "word/media/logo.png", png(LOGO_WIDTH, LOGO_HEIGHT));
            entry(zip, "word/media/chart.emf", "not a format a request carries");
        }
        Files.write(file, bytes.toByteArray());
        return file;
    }

    /** One small PNG, as the JDK writes it. */
    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(
                width, height, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        entry(zip, name, xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }
}
