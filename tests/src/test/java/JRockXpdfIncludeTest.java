import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.swing.finder.JOptionPaneFinder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes a two-page PDF as text and as an HTML folder, with xpdf's pdftotext and
 * pdftohtml, and checks what was written under {@code JRock/includes/} and what the
 * prompt got.
 * <p>
 * The folder the PDF is in is not written into and nothing is asked: the pages are
 * working material for the model, so they go where every other conversion's output goes
 * and are cleaned up with it.
 * <p>
 * Like {@link JRockPdfIncludeTest}, this runs external programs: both xpdf tools
 * have to be on PATH (the workflow installs them).
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see A4Pdf for the PDF and the text on its pages
 */
class JRockXpdfIncludeTest extends JRockGuiFixture {

    private static final String PDF_TEXT_FILTER = "PDF files, as text, with xpdf (*.pdf)";
    private static final String PDF_HTML_FILTER = "PDF files, as an HTML folder, with xpdf (*.pdf)";

    private static final long CONVERSION_TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("a PDF included as text is written to JRock/includes/ as <name>.txt and tagged @txt")
    void convertsAPdfToTextUnderJRockAndIncludesIt() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftotext")).describedAs("pdftotext on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        field("pdfIncludeMode").set(null, "text");
        chooseInTheIncludeDialog(PDF_TEXT_FILTER, pdf);
        awaitLogLine("token(s) for " + pdf.getFileName(), CONVERSION_TIMEOUT_SECONDS);

        Path txt = includes().resolve("two-page-a4.pdf.txt");
        assertThat(new String(Files.readAllBytes(txt), StandardCharsets.UTF_8))
                .describedAs("the text pdftotext wrote")
                .contains("JRock PDF test - page 1")
                .contains("JRock PDF test - page 2");
        // Nothing beside the PDF: the document's own folder is left exactly as it was.
        assertThat(workingDirectory().resolve("two-page-a4.pdf.txt"))
                .describedAs("a conversion beside the PDF, which there should not be")
                .doesNotExist();
        assertThat(logPane().text())
                .contains("Converting PDF with xpdf: ")
                .contains("-enc UTF-8")
                .contains("Inserted 1 new @txt token(s) for two-page-a4.pdf.");
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);
    }

    @Test
    @DisplayName("a PDF included as HTML is written to a folder under JRock/includes/, then included as one")
    void convertsAPdfToAnHtmlFolderUnderJRockAndIncludesTheFolder() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftohtml")).describedAs("pdftohtml on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        field("pdfIncludeMode").set(null, "html");
        chooseInTheIncludeDialog(PDF_HTML_FILTER, pdf);
        awaitLogLine("Include directory ", CONVERSION_TIMEOUT_SECONDS);

        Path dir = includes().resolve("two-page-a4.pdf.html");
        assertThat(dir.resolve("page1.html")).isRegularFile();
        assertThat(dir.resolve("page2.html")).isRegularFile();
        assertThat(new String(Files.readAllBytes(dir.resolve("page2.html")),
                StandardCharsets.UTF_8)).contains("page 2");
        assertThat(workingDirectory().resolve("two-page-a4.pdf.html"))
                .describedAs("a folder beside the PDF, which there should not be")
                .doesNotExist();
        assertThat(logPane().text())
                .contains("-nofonts")
                .contains("Include directory " + dir);
        // index.html and one page<N>.html per page, at the least.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("an earlier conversion of the same PDF is replaced, so the pages say what the PDF says")
    void replacesAnEarlierConversion() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftotext")).describedAs("pdftotext on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        Path txt = includes().resolve("two-page-a4.pdf.txt");
        Files.createDirectories(includes());
        Files.write(txt, "an earlier conversion".getBytes(StandardCharsets.UTF_8));
        field("pdfIncludeMode").set(null, "text");
        chooseInTheIncludeDialog(PDF_TEXT_FILTER, pdf);
        awaitLogLine("token(s) for " + pdf.getFileName(), CONVERSION_TIMEOUT_SECONDS);

        assertThat(new String(Files.readAllBytes(txt), StandardCharsets.UTF_8))
                .describedAs("the text under JRock/includes/")
                .doesNotContain("an earlier conversion")
                .contains("JRock PDF test - page 1");
        assertThat(logPane().text())
                .contains("Replacing the earlier conversion at " + txt)
                .contains("Converting PDF with xpdf: ");
    }

    @Test
    @DisplayName("with processed copies off, an xpdf reading is refused and the setting to turn on is named")
    void refusesAConversionIntoJRockWithCopiesOff() throws Exception {
        awaitReadyCount(1);

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        field("pdfIncludeMode").set(null, "text");
        // The conversion writes under JRock/includes/, which is exactly what "Include
        // from processed copies in JRock/includes/" off rules out.
        chooseInTheIncludeDialog(PDF_TEXT_FILTER, false, pdf);
        press(JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot)
                .okButton());
        awaitLogLine("Not included: " + pdf, CONVERSION_TIMEOUT_SECONDS);

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("\"Include from processed copies in JRock/includes/\" is off. Turn it on "
                        + "in Configure to include it.");
        assertThat(promptArea().text()).describedAs("the prompt").doesNotContain("@txt ");
        assertThat(includes().resolve("two-page-a4.pdf.txt")).doesNotExist();
    }

    /** The folder every include is made from. */
    private Path includes() {
        return workingDirectory().resolve("JRock").resolve("includes");
    }

    /** The tool JRock itself would run, or null if it found none. */
    private static String onPath(String tool) throws Exception {
        java.lang.reflect.Method find =
                JRock.class.getDeclaredMethod("findOnPath", String[].class);
        find.setAccessible(true);
        return (String) find.invoke(null, (Object) new String[] { tool });
    }
}
