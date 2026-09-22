import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes an image through the real include dialog under each of the two filters that
 * offer images, and checks what each one writes into the prompt.
 * <p>
 * The whole difference between them is one line: <em>Image with a Markdown reference</em>
 * puts a {@code ![](<hash>)} above the {@code @img} token, which is what makes the picture
 * part of the text for the model - and what the DOCX export then places (see
 * {@link JRockMarkdownExportTest}). Everything else about the two includes is identical,
 * which is why both are driven here rather than one being assumed from the other.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockImageRefIncludeTest extends JRockGuiFixture {

    private static final String IMAGE_FILTER = "Image files (png, jpg, jpeg, gif, webp)";
    private static final String IMAGE_REF_FILTER =
            "Image with a Markdown reference (png, jpg, jpeg, gif, webp)";

    /** Hashing one small file and reading its header; a slow CI runner needs the rest. */
    private static final long INCLUDE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("an image included with a Markdown reference writes two lines, not one")
    void writesAMarkdownReferenceAboveTheToken() throws Exception {
        awaitReadyCount(1);

        Path png = png("IMG_4002.png");
        chooseInTheIncludeDialog(IMAGE_REF_FILTER, png);
        awaitLogLine("With a Markdown reference above it: ", INCLUDE_TIMEOUT_SECONDS);

        // The log says both things it did, and the size it read out of the header.
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Included @img ")
                .contains("With a Markdown reference above it: ![](")
                .contains("Image: 120 x 80");

        // The prompt holds the reference on the line above the token, in that order: the
        // picture belongs where the text refers to it, and the token says which file.
        // At the caret, which is where the starter prompt below it stays.
        String hash = hashOf(promptArea().text());
        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("![](" + hash + ")\n@img " + hash + "\n");
    }

    @Test
    @DisplayName("the plain image filter still writes the token alone")
    void writesOnlyTheTokenWithoutTheReference() throws Exception {
        awaitReadyCount(1);

        chooseInTheIncludeDialog(IMAGE_FILTER, png("plain.png"));
        awaitLogLine("Included @img ", INCLUDE_TIMEOUT_SECONDS);

        assertThat(promptArea().text()).describedAs("the prompt")
                .startsWith("@img ").doesNotContain("![](");
        assertThat(logPane().text()).describedAs("the log pane's text")
                .doesNotContain("With a Markdown reference");
    }

    /** The hash JRock gave the include, read back out of the token it inserted. */
    private static String hashOf(String prompt) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("@img ([0-9a-f]{12})").matcher(prompt);
        assertThat(m.find()).describedAs("an @img token in " + prompt).isTrue();
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
