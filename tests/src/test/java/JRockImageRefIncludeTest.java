import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes files through the real include dialog with Configure's <em>Markdown reference
 * above each include</em> on and off, and checks what each writes into the prompt.
 * <p>
 * On, every include gets one line more above its token: {@code ![<about>](<name>)} for
 * a picture, {@code [<about>](<name>)} for anything else, where {@code <about>} is the
 * line the log writes about the file - for an image its size in pixels, its size on
 * paper at Images DPI, its pixel count and its bytes. That line is what makes the file
 * part of the text for the model, and what the DOCX export places a picture by (see
 * {@link JRockMarkdownExportTest}).
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockImageRefIncludeTest extends JRockGuiFixture {

    private static final String ALL_FILTER =
            "All supported files (images, text, audio, PDF, RTF, DOCX, XLSX)";

    /** Hashing one small file and reading its header; a slow CI runner needs the rest. */
    private static final long INCLUDE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("an image's reference names the file, with its size in pixels and in cm at the DPI")
    void writesAMarkdownReferenceAboveTheToken() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, true);
        field("imagesDpi").set(null, 150);

        Path png = png("IMG_4002.png");
        chooseInTheIncludeDialog(ALL_FILTER, false, png);
        awaitLogLine("With a Markdown reference above it: ", INCLUDE_TIMEOUT_SECONDS);

        // 120 x 80 pixels at 150 dpi: 2.032 cm x 1.355 cm, to the millimetre.
        String about = "Image: 120 x 80, 2.0 cm x 1.4 cm @ 150 DPI, ";
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Included @img ")
                .contains("With a Markdown reference above it: ![" + about)
                .contains(about);

        // The prompt holds the reference on the line above the token, in that order: the
        // picture belongs where the text refers to it, and the token says which file.
        String hash = hashOf(promptArea().text(), "img");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("![" + about)
                .contains(" bytes](IMG_4002.png)\n@img " + hash + "\n");
    }

    @Test
    @DisplayName("any other file's reference is a link naming it, with what the log says of it")
    void writesALinkAboveATextToken() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, true);

        Path txt = workingDirectory().resolve("my notes.txt");
        Files.write(txt, "hello".getBytes(StandardCharsets.UTF_8));
        chooseInTheIncludeDialog(ALL_FILTER, false, txt);
        awaitLogLine("With a Markdown reference above it: ", INCLUDE_TIMEOUT_SECONDS);

        // A name with a space in it goes in angle brackets, or the link would end there.
        String hash = hashOf(promptArea().text(), "txt");
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("[Text: 5 symbols, 5 bytes](<my notes.txt>)\n@txt " + hash + "\n");
    }

    @Test
    @DisplayName("with references off, the token goes in alone")
    void writesOnlyTheTokenWithoutTheReference() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, false);

        chooseInTheIncludeDialog(ALL_FILTER, false, png("plain.png"));
        awaitLogLine("Included @img ", INCLUDE_TIMEOUT_SECONDS);

        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("@img ").doesNotContain("![");
        assertThat(logPane().text()).describedAs("the log pane's text")
                .doesNotContain("With a Markdown reference");
    }

    /** The hash JRock gave the include, read back out of the token it inserted. */
    private static String hashOf(String prompt, String kind) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@" + kind + " ([0-9a-f]{12})").matcher(prompt);
        assertThat(m.find()).describedAs("an @" + kind + " token in " + prompt).isTrue();
        return m.group(1);
    }

    /** A real PNG in the working directory, deliberately not square. */
    private Path png(String name) throws Exception {
        Path file = workingDirectory().resolve(name);
        assertThat(ImageIO.write(new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB),
                "png", file.toFile())).describedAs("the JDK wrote the PNG").isTrue();
        return file;
    }
}
