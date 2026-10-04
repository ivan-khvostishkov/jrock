import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A two-sheet .xlsx written by hand, part by part, and the CSV each sheet has to
 * become.
 * <p>
 * By hand for the reason {@link FormattedDocx} is: every cell type the converter
 * maps is here once, spelled the way Excel writes it - shared and inline strings, a
 * plain number, dates in a built-in and a custom format, a boolean, an error, a
 * formula's cached value - plus a skipped column and a skipped row, which have to
 * keep their places in the CSV.
 */
final class FormattedXlsx {

    private FormattedXlsx() { }

    private static final String CONTENT_TYPES = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">",
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>",
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>",
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>",
            "</Types>");

    private static final String RELS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>",
            "</Relationships>");

    /** The sheets in tab order - which is not the order of their part names. */
    private static final String WORKBOOK = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"",
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">",
            "<sheets>",
            "<sheet name=\"Sales Q1\" sheetId=\"1\" r:id=\"rId2\"/>",
            "<sheet name=\"Заметки\" sheetId=\"2\" r:id=\"rId1\"/>",
            "</sheets></workbook>");

    private static final String WORKBOOK_RELS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">",
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>",
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"/xl/worksheets/sheet2.xml\"/>",
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>",
            "</Relationships>");

    /** Index 2 carries a phonetic guide (rPh), which is not part of the text. */
    private static final String SHARED_STRINGS = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"5\" uniqueCount=\"5\">",
            "<si><t>Item</t></si>",
            "<si><t>Date</t></si>",
            "<si><r><t>Pr</t></r><r><rPr><b/></rPr><t>ice</t></r><rPh sb=\"0\" eb=\"1\"><t>XX</t></rPh></si>",
            "<si><t>Scissors, \"kitchen\"</t></si>",
            "<si><t xml:space=\"preserve\">Две строки\nтекста</t></si>",
            "</sst>");

    /**
     * cellXfs: 0 General, 1 built-in 14 (a short date), 2 custom 164 (a date and a
     * time), 3 custom 165 (a number with "d" only inside quotes - not a date), 4 the
     * built-in time format 20.
     */
    private static final String STYLES = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">",
            "<numFmts count=\"2\">",
            "<numFmt numFmtId=\"164\" formatCode=\"dd.mm.yyyy\\ hh:mm\"/>",
            "<numFmt numFmtId=\"165\" formatCode=\"[Red]0.00&quot; pcs, dozen&quot;\"/>",
            "</numFmts>",
            "<cellXfs count=\"5\">",
            "<xf numFmtId=\"0\"/><xf numFmtId=\"14\"/><xf numFmtId=\"164\"/>",
            "<xf numFmtId=\"165\"/><xf numFmtId=\"20\"/>",
            "</cellXfs></styleSheet>");

    /** Sales Q1: column C left empty in row 1, row 4 missing altogether. */
    private static final String SALES = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>",
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c>",
            "<c r=\"D1\" t=\"s\"><v>2</v></c></row>",
            "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c><c r=\"B2\" s=\"1\"><v>46299</v></c>",
            "<c r=\"C2\" t=\"b\"><v>1</v></c><c r=\"D2\" s=\"3\"><v>12.5</v></c></row>",
            "<row r=\"3\"><c r=\"A3\" t=\"inlineStr\"><is><t>Inline</t></is></c>",
            "<c r=\"B3\" s=\"2\"><v>45351.75</v></c><c r=\"C3\" t=\"e\"><v>#DIV/0!</v></c>",
            "<c r=\"D3\" t=\"str\"><f>A3&amp;\"!\"</f><v>Inline!</v></c></row>",
            "<row r=\"5\"><c r=\"A5\" s=\"4\"><v>0.5</v></c><c r=\"D5\"><f>D2*2</f><v>25</v></c></row>",
            "</sheetData></worksheet>");

    private static final String NOTES = String.join("",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>",
            "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>4</v></c><c r=\"B1\"><v>0.1</v></c></row>",
            "</sheetData></worksheet>");

    /** What "Sales Q1" has to become. */
    static final String SALES_CSV = String.join("\n",
            "Item,Date,,Price",
            "\"Scissors, \"\"kitchen\"\"\",2026-10-04,TRUE,12.5",
            "Inline,2024-02-29 18:00:00,#DIV/0!,Inline!",
            ",,,",
            "12:00:00,,,25",
            "");

    /** And "Заметки": a line break inside a field is quoted, not a new row. */
    static final String NOTES_CSV = "\"Две строки\nтекста\",0.1\n";

    /** Writes the workbook to {@code file} and returns it. */
    static Path write(Path file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", CONTENT_TYPES);
            entry(zip, "_rels/.rels", RELS);
            entry(zip, "xl/workbook.xml", WORKBOOK);
            entry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            entry(zip, "xl/sharedStrings.xml", SHARED_STRINGS);
            entry(zip, "xl/styles.xml", STYLES);
            entry(zip, "xl/worksheets/sheet1.xml", NOTES);
            entry(zip, "xl/worksheets/sheet2.xml", SALES);
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
