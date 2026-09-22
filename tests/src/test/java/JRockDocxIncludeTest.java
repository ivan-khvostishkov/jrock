import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes a .docx as Markdown through the real include dialog, and checks both the
 * Markdown that was written and the token the prompt got for it.
 * <p>
 * The symmetric twin of {@link JRockRtfIncludeTest}, and nothing external is involved
 * here either: a .docx is a ZIP of XML, so the reader is {@code java.util.zip} and the
 * JDK's XML parser, and this runs anywhere the tests run. The formatting is the point,
 * which is why the expected Markdown is compared in full rather than searched.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see FormattedDocx for the document, and the Markdown it has to become
 * @see JRockMarkdownExportTest for the export direction, and a round trip through both
 */
class JRockDocxIncludeTest extends JRockGuiFixture {

    /** The include chooser's filter that means "convert this DOCX to Markdown". */
    private static final String DOCX_FILTER = "DOCX as Markdown text (*.docx)";

    /** Reading one small document in-process; only a slow CI runner needs the rest. */
    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("a DOCX included as Markdown is converted, written to docx-md/ and tagged @txt")
    void convertsADocxToMarkdownAndIncludesTheMarkdown() throws Exception {
        awaitReadyCount(1);

        Path docx = FormattedDocx.write(workingDirectory().resolve("quarterly.docx"));

        includeThroughTheDialog(DOCX_FILTER, CONVERSION_TIMEOUT_SECONDS, docx);

        // 1. The log says what it did, and to which file.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Converting DOCX to Markdown: ")
                .contains("Markdown written to ")
                .contains("Inserted 1 new @txt token(s) for quarterly.docx.");

        // 2. The Markdown landed where the README says it does, named after the document.
        Path markdown = workingDirectory().resolve("JRock").resolve("docx-md")
                .resolve("quarterly.docx.md");
        assertThat(markdown).describedAs("the Markdown under JRock/docx-md/").exists();

        // 3. And it is the Markdown the document means: headings from the styles, bold
        //    and italic as markup, the numbered list as a list, the table as a table,
        //    the asterisks escaped, and the umlaut intact as UTF-8.
        String produced = new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8);
        assertThat(produced).describedAs("the converted Markdown").isEqualTo(FormattedDocx.MARKDOWN);

        // 4. The prompt references it once, as a text include: the model reads Markdown,
        //    not a ZIP.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);

        // 5. The include is registered against the file that was written, not against
        //    the .docx itself.
        assertThat(includedPaths()).describedAs("the files registered as includes")
                .containsExactly(markdown);
    }

    @Test
    @DisplayName("a file that is not really a DOCX is reported, and nothing is included")
    void saysSoWhenTheFileIsNotADocx() throws Exception {
        awaitReadyCount(1);

        // The realistic mistake: something else renamed to .docx - an older .doc, a PDF,
        // or as here plain text. None of them is a ZIP with a Word document inside, and
        // JRock has to say so rather than insert a token for nothing.
        Path notDocx = workingDirectory().resolve("renamed.docx");
        Files.write(notDocx, "This is plain text, not a Word document.".getBytes(StandardCharsets.UTF_8));

        // No token line to wait for: the refusal is the last thing said about this file.
        chooseInTheIncludeDialog(DOCX_FILTER, notDocx);
        awaitLogLine("Could not read DOCX renamed.docx", CONVERSION_TIMEOUT_SECONDS);

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Could not read DOCX renamed.docx: no word/document.xml inside it");
        assertThat(promptArea().text()).describedAs("the prompt").doesNotContain("@txt ");
        assertThat(workingDirectory().resolve("JRock").resolve("docx-md"))
                .describedAs("JRock/docx-md/, which nothing should have created").doesNotExist();
    }

    /** The paths JRock currently has registered as includes. */
    @SuppressWarnings("unchecked")
    private static java.util.Collection<Path> includedPaths() throws Exception {
        return ((java.util.Map<String, Path>) field("INCLUDES").get(null)).values();
    }
}
