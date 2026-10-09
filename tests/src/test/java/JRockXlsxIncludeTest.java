import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes an .xlsx as CSV through the real include dialog, and checks the CSV each
 * sheet became and the tokens the prompt got for them.
 * <p>
 * Nothing external, as for DOCX: java.util.zip and the JDK's XML parser. The CSV is
 * compared in full, because a cell in the wrong place is the failure that matters.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see FormattedXlsx for the workbook, and the CSV it has to become
 */
class JRockXlsxIncludeTest extends JRockGuiFixture {

    private static final String XLSX_FILTER = "XLSX files, as CSV text, one file per sheet (*.xlsx)";

    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("an XLSX is written to xlsx-csv/ as one CSV per sheet, each tagged @txt")
    void convertsEachSheetToItsOwnCsv() throws Exception {
        awaitReadyCount(1);
        Path xlsx = FormattedXlsx.write(workingDirectory().resolve("stock.xlsx"));

        includeThroughTheDialog(XLSX_FILTER, CONVERSION_TIMEOUT_SECONDS, xlsx);

        Path dir = workingDirectory().resolve("JRock").resolve("includes").resolve("xlsx-csv");
        Path sales = dir.resolve("stock.xlsx.Sales Q1.csv");
        Path notes = dir.resolve("stock.xlsx.Заметки.csv");
        assertThat(read(sales)).describedAs("the first sheet's CSV").isEqualTo(FormattedXlsx.SALES_CSV);
        assertThat(read(notes)).describedAs("the second sheet's CSV").isEqualTo(FormattedXlsx.NOTES_CSV);

        assertThat(logPane().text())
                .contains("Converting XLSX to CSV, one file per sheet: ")
                .contains("Inserted 2 new @txt token(s) for stock.xlsx.");
        // In tab order, which is not the order of the sheets' parts in the file.
        String log = logPane().text();
        assertThat(log.indexOf("Sheet \"Sales Q1\"")).isLessThan(log.indexOf("Sheet \"Заметки\""));
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(2);
    }

    @Test
    @DisplayName("a file that is not really an XLSX is reported, and nothing is included")
    void saysSoWhenTheFileIsNotAnXlsx() throws Exception {
        awaitReadyCount(1);
        Path notXlsx = workingDirectory().resolve("renamed.xlsx");
        Files.write(notXlsx, "a,b\n1,2\n".getBytes(StandardCharsets.UTF_8));

        chooseInTheIncludeDialog(XLSX_FILTER, notXlsx);
        awaitLogLine("Could not read XLSX renamed.xlsx", CONVERSION_TIMEOUT_SECONDS);

        assertThat(countOccurrences(promptArea().text(), "@txt ")).isZero();
    }

    private static String read(Path file) throws Exception {
        assertThat(file).describedAs(file.getFileName().toString()).exists();
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
