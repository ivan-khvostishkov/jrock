import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.JComboBox;
import javax.swing.filechooser.FileFilter;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.fixture.JComboBoxFixture;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.timing.Condition;
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

    private static final String DPI = "300";

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
        assertThat(field("pdfDpi").get(null))
                .describedAs("the configured PDF image DPI").isEqualTo(300);

        includeAsPageImages(pdf);

        // 1. Ghostscript was asked for the resolution the dialog was given. Checked
        //    on the command line JRock logs, so a wrong flag name would show up here
        //    rather than only as a surprising pixel count.
        assertThat(lineStartingWith(logLines(), "Converting PDF with Ghostscript: "))
                .describedAs("the logged Ghostscript command line").isNotNegative();
        assertThat(logPane().text())
                .describedAs("the logged Ghostscript command line")
                .contains("-r" + DPI);

        // 2. Two pages came back, and JRock measured each at A4-at-300dpi.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Ghostscript produced 2 page file(s)")
                .contains("Inserted 2 new @img token(s)");
        String expected = "Image: " + A4Pdf.WIDTH_AT_300_DPI + " x " + A4Pdf.HEIGHT_AT_300_DPI;
        assertThat(countOccurrences(logPane().text(), expected))
                .describedAs("log lines reporting " + expected)
                .isEqualTo(2);

        // 3. The prompt references both pages: one @img token per page.
        assertThat(countOccurrences(promptArea().text(), "@img "))
                .describedAs("@img tokens in the prompt").isEqualTo(2);

        // 4. And the files on disk really are that size - measured from the PNGs
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

    /** Opens Configure, picks the DPI from the dropdown, and applies it. */
    private void chooseDpiInConfigureDialog() {
        JOptionPaneFixture dialog = openConfigure();
        // The Configure dialog's one non-editable combo: the model combo is
        // editable, which separates them without depending on their order.
        dialog.comboBox(new GenericTypeMatcher<JComboBox>(JComboBox.class) {
            @Override
            protected boolean isMatching(JComboBox box) {
                return !box.isEditable();
            }
        }).selectItem(DPI);
        dialog.okButton().click();
        // Applying re-runs the whole session report, ending in a second "Ready.".
        awaitReadyCount(2);
    }

    /**
     * Presses Ctrl+I, picks the "PDF as page images" filter in the chooser, selects
     * the PDF and confirms - then waits for the conversion to finish.
     * <p>
     * Ctrl+I rather than the prompt's context menu: it is bound on the root pane as
     * WHEN_IN_FOCUSED_WINDOW and opens the very same chooser, without depending on
     * a popup being rendered and hit-tested on a virtual display.
     */
    private void includeAsPageImages(Path pdf) {
        promptArea().focus();
        // Ctrl held explicitly rather than passed as a modifier mask: AssertJ-Swing
        // translates masks through a table that knows only the legacy InputEvent
        // constants, so CTRL_DOWN_MASK silently presses nothing at all.
        robot.pressKeyWhileRunning(KeyEvent.VK_CONTROL,
                () -> robot.pressAndReleaseKey(KeyEvent.VK_I));

        JFileChooserFixture chooser =
                JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);

        // The filter decides everything that follows: same dialog, same file, but
        // this is what makes it a Ghostscript page-image include rather than an
        // attempt to attach the PDF itself.
        //
        // JFileChooserFixture exposes the file name box and the buttons, not the
        // "Files of Type" combo, so it is found in the chooser's own hierarchy - by
        // what its items are, rather than by a position in the dialog.
        JComboBox<?> box = robot.finder().find(chooser.target(),
                new GenericTypeMatcher<JComboBox>(JComboBox.class) {
                    @Override
                    protected boolean isMatching(JComboBox candidate) {
                        return candidate.getItemCount() > 0
                                && candidate.getItemAt(0) instanceof FileFilter;
                    }
                });
        int index = indexOfFilter(box, PDF_IMAGE_FILTER);
        assertThat(index).describedAs("the \"" + PDF_IMAGE_FILTER + "\" filter")
                .isNotNegative();
        new JComboBoxFixture(robot, box).selectItem(index);

        // selectFiles, not selectFile: the chooser is in multi-selection mode, and
        // JRock reads getSelectedFiles() - which setSelectedFile alone leaves empty.
        chooser.selectFiles(pdf.toFile());
        chooser.approve();

        // The conversion runs on the EDT: it starts Ghostscript and waits for it, so
        // there is no worker to join - only the last line it writes to look for.
        pause(new Condition("the PDF include to finish") {
            @Override
            public boolean test() {
                return logPane().text().contains("token(s) for " + pdf.getFileName());
            }
        }, timeout(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** Index of the chooser's filter with the given description, or -1. */
    private static int indexOfFilter(final JComboBox<?> box, String description) {
        return GuiActionRunner.execute(new GuiQuery<Integer>() {
            @Override
            protected Integer executeInEDT() {
                for (int i = 0; i < box.getItemCount(); i++) {
                    Object item = box.getItemAt(i);
                    if (item instanceof FileFilter
                            && description.equals(((FileFilter) item).getDescription())) {
                        return i;
                    }
                }
                return -1;
            }
        });
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

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
