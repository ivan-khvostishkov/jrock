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
 * checks the order: the folder's own files first, in name order, then each folder in it,
 * in name order, the same way down - each file as its extension says, and what the
 * include dialog does not take skipped with a line in the log. With Markdown references
 * on, each names its file by the path from the working directory.
 * <p>
 * Offline, like the rest: no API key is set, and nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockIncludeDirectoryTest extends JRockGuiFixture {

    private static final long INCLUDE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("a folder's files come first in name order, then each folder in it, recursively")
    void includesFilesFirstThenEachFolderInNameOrder() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, true);
        field("includeProcessedCopies").set(null, false);

        Path dir = Files.createDirectories(workingDirectory().resolve("folder"));
        write(dir.resolve("b-notes.txt"), "Some notes.");
        assertThat(ImageIO.write(new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB),
                "png", dir.resolve("a-picture.png").toFile())).describedAs("the PNG").isTrue();
        Files.write(dir.resolve("c-archive.xyz"), new byte[] { 1, 2, 3 });
        // Listed after the files although "A" sorts before them: files first, then folders.
        write(Files.createDirectories(dir.resolve("A-sub")).resolve("x.txt"), "In A.");
        Path deep = Files.createDirectories(dir.resolve("d-inner").resolve("deeper"));
        write(dir.resolve("d-inner").resolve("z.txt"), "In d.");
        write(deep.resolve("y.txt"), "Deeper.");

        chooseInThePromptMenu("Include directory...");
        JFileChooserFixture chooser =
                JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
        approveWith(chooser, dir);
        awaitLogLine("file(s) included, ", INCLUDE_TIMEOUT_SECONDS);

        // 1. The order, read off the references: the folder's two files, then A-sub's,
        //    then d-inner's own file before the folder inside it.
        String prompt = promptArea().text();
        int picture = prompt.indexOf("](folder/a-picture.png)");
        int notes = prompt.indexOf("](folder/b-notes.txt)");
        int inA = prompt.indexOf("](folder/A-sub/x.txt)");
        int inD = prompt.indexOf("](folder/d-inner/z.txt)");
        int deeper = prompt.indexOf("](folder/d-inner/deeper/y.txt)");
        assertThat(picture).describedAs("the picture's reference in " + prompt).isNotNegative();
        assertThat(new int[] { picture, notes, inA, inD, deeper })
                .describedAs("the references in the order they went in").isSorted();
        assertThat(notes).isPositive();
        assertThat(prompt).describedAs("an image's reference, then its token")
                .startsWith("![Image: 40 x 30, ");

        // 2. What was not taken is named in the log, and counted.
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Skipped " + dir.resolve("c-archive.xyz") + ": not a type Include file takes.")
                .contains("Include directory " + dir + ": 5 file(s) included, 1 skipped.");
    }

    private static void write(Path file, String text) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
