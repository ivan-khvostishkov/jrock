import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.swing.finder.JOptionPaneFinder;
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
    private static final String DOCX_FILTER = "DOCX files, as Markdown text (*.docx)";

    /** Reading one small document in-process; only a slow CI runner needs the rest. */
    private static final long CONVERSION_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("a DOCX included as Markdown is converted, written beside the copies and tagged @txt")
    void convertsADocxToMarkdownAndIncludesTheMarkdown() throws Exception {
        awaitReadyCount(1);

        Path docx = FormattedDocx.write(workingDirectory().resolve(FormattedDocx.NAME));

        includeThroughTheDialog(DOCX_FILTER, CONVERSION_TIMEOUT_SECONDS, docx);

        // 1. The log says what it did, and to which file.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Converting DOCX to Markdown: ")
                .contains("Markdown written to ")
                .contains("Inserted 1 new @txt token(s) for quarterly.docx.");

        // 2. The Markdown landed where the README says it does, named after the document.
        Path markdown = includes().resolve("quarterly.docx.md");
        assertThat(markdown).describedAs("the Markdown under JRock/includes/").exists();

        // 3. And it is the Markdown the document means: headings from the styles, bold
        //    and italic as markup, the numbered list as a list, the table as a table,
        //    the pictures as references where the document draws them, the asterisks
        //    escaped, and the umlaut intact as UTF-8.
        String produced = new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8);
        assertThat(produced).describedAs("the converted Markdown").isEqualTo(FormattedDocx.MARKDOWN);

        // 4. The prompt references it once, as a text include: the model reads Markdown,
        //    not a ZIP - and once per picture, so the pictures the references name are
        //    sent with it and can be placed again by the DOCX export.
        assertThat(countOccurrences(promptArea().text(), "@txt "))
                .describedAs("@txt tokens in the prompt").isEqualTo(1);
        assertThat(countOccurrences(promptArea().text(), "@img "))
                .describedAs("@img tokens in the prompt")
                .isEqualTo(FormattedDocx.PICTURES.size());

        // 5. The includes are the files that were written - the Markdown and every
        //    picture out of the package - and not the .docx itself.
        java.util.List<Path> pictures = new java.util.ArrayList<>();
        for (String name : FormattedDocx.PICTURES) pictures.add(includes().resolve(name));
        for (Path picture : pictures) {
            assertThat(picture).describedAs("a picture out of the document").isRegularFile();
        }
        assertThat(includedPaths()).describedAs("the files registered as includes")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(java.util.stream.Stream.of(markdown),
                                pictures.stream()).collect(java.util.stream.Collectors.toList()));

        // 6. Each picture is the one the document held, byte for byte: the pixels of a
        //    document's graphics are its content, and a document is included to be kept.
        assertThat(Files.readAllBytes(pictures.get(0)))
                .describedAs("the first picture's bytes")
                .isEqualTo(partOf(docx, "word/media/photo.png"));
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Image: " + FormattedDocx.PHOTO_WIDTH + " x "
                        + FormattedDocx.PHOTO_HEIGHT + ",")
                .contains("Image: " + FormattedDocx.LOGO_WIDTH + " x "
                        + FormattedDocx.LOGO_HEIGHT + ",")
                .contains("Inserted 2 new @img token(s) for the 2 picture(s) in "
                        + "quarterly.docx.")
                // 7. And the EMF - what a pasted chart arrives as - is passed over and
                //    said to have been, rather than referred to and then unreadable.
                .contains("Not taken out of quarterly.docx: its emf picture(s).");
        assertThat(includes().resolve("quarterly.docx.chart.emf"))
                .describedAs("the EMF, which nothing should have written").doesNotExist();
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
        assertThat(workingDirectory().resolve("JRock").resolve("includes")
                .resolve("renamed.docx.md"))
                .describedAs("the Markdown, which nothing should have written").doesNotExist();
    }

    @Test
    @DisplayName("with processed copies off, a DOCX is refused and the setting to turn on is named")
    void refusesAConversionIntoJRockWithCopiesOff() throws Exception {
        awaitReadyCount(1);
        Path docx = FormattedDocx.write(workingDirectory().resolve("quarterly.docx"));

        // Converting means writing the Markdown under JRock/includes/, which is exactly
        // what "Include from processed copies in JRock/includes/" off rules out.
        chooseInTheIncludeDialog(DOCX_FILTER, false, docx);
        press(JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot)
                .okButton());
        awaitLogLine("Not included: " + docx, CONVERSION_TIMEOUT_SECONDS);

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("\"Include from processed copies in JRock/includes/\" is off. Turn it on "
                        + "in Configure to include it.");
        assertThat(promptArea().text()).describedAs("the prompt").doesNotContain("@txt ");
        assertThat(workingDirectory().resolve("JRock").resolve("includes")
                .resolve("quarterly.docx.md")).doesNotExist();
    }

    /** The paths JRock currently has registered as includes. */
    @SuppressWarnings("unchecked")
    private static java.util.Collection<Path> includedPaths() throws Exception {
        return ((java.util.Map<String, Path>) field("INCLUDES").get(null)).values();
    }

    /** The folder every include is made from. */
    private Path includes() {
        return workingDirectory().resolve("JRock").resolve("includes");
    }

    /** One named entry of the .docx, read back to compare a picture against. */
    private static byte[] partOf(Path docx, String name) throws Exception {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(
                Files.newInputStream(docx))) {
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (name.equals(entry.getName())) return zip.readAllBytes();
            }
        }
        throw new AssertionError("no " + name + " in " + docx);
    }
}
