import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes a two-page A4 PDF as page images, at a resolution chosen in the
 * Configure dialog, and checks the pages that come back.
 * <p>
 * This is the one test that runs an external program: Ghostscript has to be on
 * PATH (the workflow installs it). That is the point - the DPI setting is only
 * meaningful if it reaches {@code gs -r}, and the only way to know it did is to
 * measure the pixels that came out.
 * <p>
 * Still offline: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see A4Pdf for the PDF, and why its page box is written by hand
 */
class JRockPdfIncludeTest extends JRockGuiFixture {

    /** The include chooser's filter that means "convert with Ghostscript to PNGs". */
    private static final String PDF_IMAGE_FILTER = "PDF as page images (*.pdf)";

    /**
     * The resolution to pick in Configure, as an {@code int} because that is what the
     * dropdown holds: its items are Integers, and choosing one means naming the item,
     * not the text it is rendered as.
     */
    private static final int DPI = 300;

    /** Ghostscript rasterising two A4 pages at 300 dpi takes a moment. */
    private static final long CONVERSION_TIMEOUT_SECONDS = 120;

    @Test
    @DisplayName("a two-page PDF included at 300 dpi gives two 2480x3508 page images")
    void convertsAPdfToPageImagesAtTheConfiguredResolution() throws Exception {
        awaitReadyCount(1);

        // Checked before anything is clicked: without Ghostscript, JRock answers the
        // include with a modal "not found" dialog, and this test would sit at its
        // timeout with that dialog open rather than saying what is actually wrong.
        assertThat(ghostscriptOnPath())
                .describedAs("Ghostscript on PATH (gs, or gswin64c on Windows), which "
                        + "this test needs in order to convert a real PDF")
                .isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));

        chooseDpiInConfigureDialog();
        assertThat(field("imagesDpi").get(null))
                .describedAs("the configured Images DPI").isEqualTo(DPI);

        includeThroughTheDialog(PDF_IMAGE_FILTER, CONVERSION_TIMEOUT_SECONDS, pdf);

        // 1. Ghostscript was asked for the resolution the dialog was given. Checked
        //    on the command line JRock logs, so a wrong flag name would show up here
        //    rather than only as a surprising pixel count.
        assertThat(lineStartingWith(logLines(), "Converting PDF with Ghostscript: "))
                .describedAs("the logged Ghostscript command line").isNotNegative();
        assertThat(logPane().text())
                .describedAs("the logged Ghostscript command line")
                .contains("-r" + DPI);

        // 2. The user was told what Ghostscript is doing, page by page - which is what
        //    keeps -q off that command line: with it, gs says nothing at all, and a
        //    long conversion looks like a hung application.
        //
        //    Where it is said depends on the build. Only the console one can be
        //    echoed; on Windows JRock prefers the windowed gswin64.exe, which reports
        //    into its own window and writes nothing to the pipe, so the log points
        //    there instead.
        if (windowedGhostscript()) {
            assertThat(logPane().text())
                    .describedAs("the log saying where Ghostscript's progress appears")
                    .contains("Ghostscript reports its progress in its own window");
        } else {
            assertThat(logPane().text())
                    .describedAs("Ghostscript's own output, echoed into the log")
                    .contains("gs: Page 1")
                    .contains("gs: Page 2");
        }

        // 3. Two pages came back, and JRock measured each at A4-at-300dpi.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Ghostscript produced 2 page file(s)")
                .contains("Inserted 2 new @img token(s)");
        String expected = "Image: " + A4Pdf.WIDTH_AT_300_DPI + " x " + A4Pdf.HEIGHT_AT_300_DPI;
        assertThat(countOccurrences(logPane().text(), expected))
                .describedAs("log lines reporting " + expected)
                .isEqualTo(2);

        // 4. The prompt references both pages: one @img token per page.
        assertThat(countOccurrences(promptArea().text(), "@img "))
                .describedAs("@img tokens in the prompt").isEqualTo(2);

        // 5. And the files on disk really are that size - measured from the PNGs
        //    themselves, not from what JRock said about them.
        List<Path> pages = producedPages();
        assertThat(pages).describedAs("PNG pages under JRock/gs-pdf/").hasSize(2);
        for (Path page : pages) {
            BufferedImage image = ImageIO.read(page.toFile());
            assertThat(image).describedAs("decoded " + page.getFileName()).isNotNull();
            assertThat(image.getWidth()).describedAs("width of " + page.getFileName())
                    .isEqualTo(A4Pdf.WIDTH_AT_300_DPI);
            assertThat(image.getHeight()).describedAs("height of " + page.getFileName())
                    .isEqualTo(A4Pdf.HEIGHT_AT_300_DPI);
        }
    }

    /** The Ghostscript command JRock itself would run, or null if it found none. */
    private static String ghostscriptOnPath() throws Exception {
        java.lang.reflect.Method find = JRock.class.getDeclaredMethod("findGhostscript");
        find.setAccessible(true);
        return (String) find.invoke(null);
    }

    /** Whether that Ghostscript is one of the windowed Windows builds. */
    private static boolean windowedGhostscript() throws Exception {
        java.lang.reflect.Method windowed =
                JRock.class.getDeclaredMethod("isWindowedGhostscript", String.class);
        windowed.setAccessible(true);
        return (Boolean) windowed.invoke(null, ghostscriptOnPath());
    }

    /** Opens Configure, picks the DPI from the dropdown, and applies it. */
    private void chooseDpiInConfigureDialog() {
        JOptionPaneFixture dialog = openConfigure();
        // The Configure dialog's one non-editable combo: the model combo is
        // editable, which separates them without depending on their order.
        select(dialog.comboBox(new GenericTypeMatcher<JComboBox>(JComboBox.class) {
            @Override
            protected boolean isMatching(JComboBox box) {
                return !box.isEditable();
            }
        }), DPI);
        press(dialog.okButton());
        // Applying re-runs the whole session report, ending in a second "Ready.".
        awaitReadyCount(2);
    }

    /** The PNGs Ghostscript wrote, in page order. */
    private List<Path> producedPages() throws Exception {
        Path dir = workingDirectory().resolve("JRock").resolve("gs-pdf");
        List<Path> pages = new ArrayList<>();
        if (!Files.isDirectory(dir)) return pages;
        try (java.util.stream.Stream<Path> found = Files.list(dir)) {
            found.filter(p -> p.getFileName().toString().endsWith(".png"))
                    .sorted()
                    .forEach(pages::add);
        }
        return pages;
    }
}
