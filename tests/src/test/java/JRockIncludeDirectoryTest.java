import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes a whole folder through <em>Include directory...</em> in the prompt's menu, and
 * checks that each file went in as its extension says, in name order, and that what the
 * include dialog does not take - a file of another type, a folder inside - was skipped
 * with a line in the log.
 * <p>
 * Offline, like the rest: no API key is set, and nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockIncludeDirectoryTest extends JRockGuiFixture {

    private static final long INCLUDE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("every file of a folder is included by its extension, in name order; the rest is skipped")
    void includesEachFileByItsExtensionAndSkipsTheRest() throws Exception {
        awaitReadyCount(1);

        Path dir = Files.createDirectories(workingDirectory().resolve("folder"));
        Files.write(dir.resolve("b-notes.txt"), "Some notes.".getBytes(StandardCharsets.UTF_8));
        assertThat(ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                "png", dir.resolve("a-picture.png").toFile())).describedAs("the PNG").isTrue();
        Files.write(dir.resolve("c-archive.xyz"), new byte[] { 1, 2, 3 });
        Files.createDirectories(dir.resolve("d-inner"));

        chooseInThePromptMenu("Include directory...");
        JFileChooserFixture chooser =
                JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
        approveWith(chooser, dir);
        awaitLogLine("file(s) included, ", INCLUDE_TIMEOUT_SECONDS);

        // 1. The picture first and the text after it - name order, not the order the
        //    folder happens to list them in - each as its own kind of token, ahead of
        //    the text the prompt already had.
        assertThat(promptArea().text()).describedAs("the prompt")
                .matches("(?s)@img [0-9a-f]+\\n@txt [0-9a-f]+\\n.*");

        // 2. What was not taken is named in the log, each for its own reason.
        String log = logPane().text();
        assertThat(log).describedAs("the log pane's text")
                .contains("Skipped " + dir.resolve("c-archive.xyz") + ": not a type Include file takes.")
                .contains("Skipped " + dir.resolve("d-inner") + ": a folder, which is not searched.")
                .contains("Include directory " + dir + ": 2 file(s) included, 2 skipped.");
    }
}
