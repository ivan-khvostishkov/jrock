import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a two-page A4 PDF, by hand, with no dependencies.
 * <p>
 * Hand-rolled rather than produced by a library or by Ghostscript itself, because
 * the whole point of the test using it is the EXACT page box: A4 is 210x297 mm, so
 * 595.276 x 841.89 pt, and at 300 dpi that is 2480 x 3508 px. Ghostscript's own
 * {@code -sPAPERSIZE=a4} is the rounded 595 x 842 pt, which rasterises to
 * 2479 x 3508 - one pixel narrower, and a confusing way to fail. Writing the
 * MediaBox here means the expected pixel size is derived from a number this file
 * controls.
 * <p>
 * Each page carries a line of Helvetica text, so a produced page image is
 * recognisable if anyone ever opens one while diagnosing a failure.
 * <p>
 * NOT named *Test, so Surefire doesn't try to run it.
 */
final class A4Pdf {

    /** A4 in PostScript points: 210 mm and 297 mm at 72 pt per inch. */
    static final String MEDIA_BOX = "[0 0 595.276 841.89]";

    /** Raster size of one A4 page at 300 dpi: 210/25.4*300, 297/25.4*300, rounded. */
    static final int WIDTH_AT_300_DPI  = 2480;
    static final int HEIGHT_AT_300_DPI = 3508;

    private A4Pdf() { }

    /** Writes a two-page A4 PDF to {@code target} and returns it. */
    static Path writeTwoPages(Path target) throws IOException {
        List<String> objects = new ArrayList<>();
        objects.add("<</Type /Catalog /Pages 2 0 R>>");
        objects.add("<</Type /Pages /Kids [3 0 R 4 0 R] /Count 2>>");
        objects.add(page(6));                       // page 1 -> content stream obj 6
        objects.add(page(7));                       // page 2 -> content stream obj 7
        objects.add("<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>");
        objects.add(textStream("JRock PDF test - page 1"));
        objects.add(textStream("JRock PDF test - page 2"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "%PDF-1.4\n");

        // Byte offset of each object, for the cross-reference table below. A reader
        // that has to reconstruct a broken table still works, but complains on
        // stderr - and that noise would land in JRock's log, which the test reads.
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            write(out, (i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n");
        }

        int xref = out.size();
        write(out, "xref\n0 " + (objects.size() + 1) + "\n");
        write(out, "0000000000 65535 f \n");
        for (int offset : offsets) {
            // Each entry is exactly 20 bytes, which the format is chosen to give.
            write(out, String.format("%010d", offset) + " 00000 n \n");
        }
        write(out, "trailer\n<</Size " + (objects.size() + 1) + " /Root 1 0 R>>\n"
                + "startxref\n" + xref + "\n%%EOF\n");

        Files.write(target, out.toByteArray());
        return target;
    }

    private static String page(int contentsObj) {
        return "<</Type /Page /Parent 2 0 R /MediaBox " + MEDIA_BOX
                + " /Resources <</Font <</F1 5 0 R>>>> /Contents " + contentsObj + " 0 R>>";
    }

    private static String textStream(String text) {
        String content = "BT /F1 36 Tf 72 700 Td (" + text + ") Tj ET";
        return "<</Length " + content.length() + ">>\nstream\n" + content + "\nendstream";
    }

    // PDF structure is bytes, and every offset in the xref table counts them, so
    // the encoding has to be fixed rather than the platform default. Everything
    // written here is ASCII.
    private static void write(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
