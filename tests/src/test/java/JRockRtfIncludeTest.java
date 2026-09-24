import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes an RTF as Markdown through the real include dialog, and checks both the
 * Markdown that was written and the token the prompt got for it.
 * <p>
 * The opposite of {@link JRockPdfIncludeTest} in one respect: nothing external is
 * involved. The reader is the JDK's own {@code RTFEditorKit}, so this runs anywhere
 * the tests run - and the formatting is the point, which is why the expected Markdown
 * is compared in full rather than searched for keywords.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see FormattedRtf for the document, and the Markdown it has to become
 */
class JRockRtfIncludeTest extends JRockGuiFixture {

    /** The include chooser's filter that means "convert this RTF to Markdown". */
    private static final String RTF_FILTER = "RTF as Markdown text (*.rtf)";

    /** The other offer for the same file: send the bytes on disk, markup and all. */
    private static final String AS_IS_FILTER =
            "Text files as is (*.txt, *.csv, *.json, *.html, *.java, *.rtf)";

    /** Reading one small document in-process; only a slow CI runner needs the rest. */
    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("an RTF included as Markdown is converted, written to rtf-md/ and tagged @txt")
    void convertsAnRtfToMarkdownAndIncludesTheMarkdown() throws Exception {
        awaitReadyCount(1);

        Path rtf = FormattedRtf.write(workingDirectory().resolve("quarterly.rtf"));

        includeThroughTheDialog(RTF_FILTER, CONVERSION_TIMEOUT_SECONDS, rtf);

        // 1. The log says what it did, and to which file.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Converting RTF to Markdown with Swing's RTF reader: ")
                .contains("Markdown written to ")
                .contains("Inserted 1 new @txt token(s) for quarterly.rtf.");

        // 2. The Markdown landed where the README says it does, named after the RTF.
        Path markdown = workingDirectory().resolve("JRock").resolve("rtf-md")
                .resolve("quarterly.rtf.md");
        assertThat(markdown).describedAs("the Markdown under JRock/rtf-md/").exists();

        // 3. And it is the Markdown the document means: headings from the font sizes,
        //    bold and italic as markup, the Word bullets as a list, the asterisks
        //    escaped, and the umlaut intact as UTF-8.
        String produced = new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8);
        assertThat(produced).describedAs("the converted Markdown").isEqualTo(FormattedRtf.MARKDOWN);

        // 4. The prompt references it once, as a text include - the whole point of
        //    converting to Markdown rather than to something JRock would have to send
        //    as a new kind of part.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);

        // 5. The include is registered against the file that was written, not against
        //    the RTF: what the model receives is the Markdown.
        assertThat(includedPaths()).describedAs("the files registered as includes")
                .containsExactly(markdown);
    }

    @Test
    @DisplayName("a file that is not really RTF is reported, and nothing is included")
    void saysSoWhenTheFileIsNotRtf() throws Exception {
        awaitReadyCount(1);

        // The realistic mistake: some other document renamed to .rtf. The reader does
        // not object to it - it simply finds no text - so JRock has to say so itself,
        // rather than inserting a token for an empty file.
        Path notRtf = workingDirectory().resolve("renamed.rtf");
        Files.write(notRtf, "This is plain text, not RTF at all.".getBytes(StandardCharsets.UTF_8));

        // No token line to wait for here - the refusal is the last thing said about
        // this file, so that is what the wait is on.
        chooseInTheIncludeDialog(RTF_FILTER, notRtf);
        awaitLogLine("No text found in renamed.rtf", CONVERSION_TIMEOUT_SECONDS);

        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("No text found in renamed.rtf; nothing included.");
        assertThat(promptArea().text()).describedAs("the prompt").doesNotContain("@txt ");
        assertThat(workingDirectory().resolve("JRock").resolve("rtf-md"))
                .describedAs("JRock/rtf-md/, which nothing should have created").doesNotExist();
    }

    @Test
    @DisplayName("an RTF included as is is the RTF itself, converted by nothing")
    void includesTheRtfItselfUnderTheAsIsFilter() throws Exception {
        awaitReadyCount(1);

        // The same document, under the other filter. An RTF file is text - its markup is
        // ASCII, which is how it carries everything else - so a model that knows the
        // format can be handed it as it stands, and answer in it. That is a different
        // thing to ask for than Markdown, and the only difference between the two is
        // which filter was chosen.
        Path rtf = FormattedRtf.write(workingDirectory().resolve("quarterly.rtf"));

        chooseInTheIncludeDialog(AS_IS_FILTER, rtf);
        // A plain include has nothing to convert and no worker to wait for; the line it
        // logs as it registers the file is the signal.
        awaitLogLine("Included @txt ", CONVERSION_TIMEOUT_SECONDS);

        // 1. The include is the RTF, not a file derived from it: nothing was converted
        //    and nothing was written.
        assertThat(includedPaths()).describedAs("the files registered as includes")
                .containsExactly(rtf);
        assertThat(workingDirectory().resolve("JRock").resolve("rtf-md"))
                .describedAs("JRock/rtf-md/, which converting nothing should not create")
                .doesNotExist();
        assertThat(logPane().text()).describedAs("the log pane's text")
                .doesNotContain("Converting RTF to Markdown");

        // 2. And the prompt references it once, as text - which is what "as is" means
        //    here: the file goes to the model the way @txt always sends one.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);
    }

    /** The paths JRock currently has registered as includes. */
    @SuppressWarnings("unchecked")
    private static java.util.Collection<Path> includedPaths() throws Exception {
        return ((java.util.Map<String, Path>) field("INCLUDES").get(null)).values();
    }
}
