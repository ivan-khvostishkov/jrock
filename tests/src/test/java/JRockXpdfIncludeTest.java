import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes a two-page PDF as text and as an HTML folder, with xpdf's pdftotext and
 * pdftohtml, and checks what was written beside the PDF and what the prompt got.
 * <p>
 * Like {@link JRockPdfIncludeTest}, this runs external programs: both xpdf tools
 * have to be on PATH (the workflow installs them).
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see A4Pdf for the PDF and the text on its pages
 */
class JRockXpdfIncludeTest extends JRockGuiFixture {

    private static final String PDF_TEXT_FILTER = "PDF as text, with xpdf (*.pdf)";
    private static final String PDF_HTML_FILTER = "PDF as HTML folder, with xpdf (*.pdf)";

    private static final long CONVERSION_TIMEOUT_SECONDS = 60;

    @Test
    @DisplayName("a PDF included as text is written beside it as <name>.txt and tagged @txt")
    void convertsAPdfToTextBesideItAndIncludesIt() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftotext")).describedAs("pdftotext on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        chooseInTheIncludeDialog(PDF_TEXT_FILTER, pdf);
        answerYes();
        awaitLogLine("token(s) for " + pdf.getFileName(), CONVERSION_TIMEOUT_SECONDS);

        Path txt = workingDirectory().resolve("two-page-a4.txt");
        assertThat(new String(Files.readAllBytes(txt), StandardCharsets.UTF_8))
                .describedAs("the text pdftotext wrote")
                .contains("JRock PDF test - page 1")
                .contains("JRock PDF test - page 2");
        assertThat(logPane().text())
                .contains("Converting PDF with xpdf: ")
                .contains("-enc UTF-8")
                .contains("Inserted 1 new @txt token(s) for two-page-a4.pdf.");
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);
    }

    @Test
    @DisplayName("a PDF included as HTML is written to a folder beside it, then included as one")
    void convertsAPdfToAnHtmlFolderBesideItAndIncludesTheFolder() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftohtml")).describedAs("pdftohtml on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        chooseInTheIncludeDialog(PDF_HTML_FILTER, pdf);
        answerYes();
        awaitLogLine("Include directory ", CONVERSION_TIMEOUT_SECONDS);

        Path dir = workingDirectory().resolve("two-page-a4");
        assertThat(dir.resolve("page1.html")).isRegularFile();
        assertThat(dir.resolve("page2.html")).isRegularFile();
        assertThat(new String(Files.readAllBytes(dir.resolve("page2.html")),
                StandardCharsets.UTF_8)).contains("page 2");
        assertThat(logPane().text())
                .contains("-nofonts")
                .contains("Include directory " + dir);
        // index.html and one page<N>.html per page, at the least.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("an existing <name>.txt beside the PDF is included as it is, not converted again")
    void includesAnExistingTextFileAsItIs() throws Exception {
        awaitReadyCount(1);
        assertThat(onPath("pdftotext")).describedAs("pdftotext on PATH").isNotNull();

        Path pdf = A4Pdf.writeTwoPages(workingDirectory().resolve("two-page-a4.pdf"));
        Path txt = workingDirectory().resolve("two-page-a4.txt");
        Files.write(txt, "edited by hand".getBytes(StandardCharsets.UTF_8));
        chooseInTheIncludeDialog(PDF_TEXT_FILTER, pdf);
        answerYes();
        awaitLogLine("token(s) for " + pdf.getFileName(), CONVERSION_TIMEOUT_SECONDS);

        assertThat(new String(Files.readAllBytes(txt), StandardCharsets.UTF_8))
                .describedAs("the file the user already had").isEqualTo("edited by hand");
        assertThat(logPane().text())
                .doesNotContain("Converting PDF with xpdf: ")
                .contains("Including the existing file " + txt);
    }

    /** Says Yes to the create (or include existing) question. */
    private void answerYes() {
        JOptionPaneFixture question =
                JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
        press(question.yesButton());
    }

    /** The tool JRock itself would run, or null if it found none. */
    private static String onPath(String tool) throws Exception {
        java.lang.reflect.Method find =
                JRock.class.getDeclaredMethod("findOnPath", String[].class);
        find.setAccessible(true);
        return (String) find.invoke(null, (Object) new String[] { tool });
    }
}
