import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes files with the include dialog's <em>Save include copies</em> checkbox on and
 * off, and checks where each include ended up pointing.
 * <p>
 * The option exists because the file JRock remembers is not always a file that stays:
 * in the browser an uploaded one lives in CheerpJ's {@code /uploads} and is gone after a
 * reload, which leaves {@link JRockReloadIncludesTest} with a path it cannot open. A copy
 * under {@code JRock/includes/} is in the folder the user owns - so what is checked here
 * is that the include is registered against the copy, not against the original.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockIncludeCopyTest extends JRockGuiFixture {

    private static final String IMAGE_FILTER = "Image files (png, jpg, jpeg, gif, webp)";
    private static final String SAVE_COPIES = "Save include copies";

    /** Hashing and copying one small file; a slow CI runner needs the rest. */
    private static final long INCLUDE_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("with the checkbox ticked, the copy under JRock/includes/ is what is included")
    void copiesTheFileIntoJRockAndIncludesTheCopy() throws Exception {
        awaitReadyCount(1);

        Path original = png("elsewhere", "IMG_4002.png", 120, 80);
        includeWithCopies(true, original, "Image: 120 x 80");

        // 1. The copy is reported, and before the include it was made for: the order is
        //    the point - the file is copied first and included from there.
        List<String> lines = logLines();
        int copied = lineStartingWith(lines, "Copied for the include: ");
        int included = lineStartingWith(lines, "Included @img ");
        assertThat(copied).describedAs("the \"Copied for the include\" line").isGreaterThan(-1);
        assertThat(copied).describedAs("the copy line's position, before the include's")
                .isLessThan(included);

        // 2. The copy is where the README says it is, under the name it had, and it is
        //    the same file.
        Path copy = workingDirectory().resolve("JRock").resolve("includes")
                .resolve("IMG_4002.png");
        assertThat(copy).describedAs("the copy under JRock/includes/").exists();
        assertThat(Files.readAllBytes(copy)).describedAs("the copied bytes")
                .isEqualTo(Files.readAllBytes(original));

        // 3. And the include points at the copy - which is the whole object of the
        //    exercise, the original being the file that may not be there tomorrow.
        assertThat(includes().values()).describedAs("the files registered as includes")
                .containsExactly(copy);
        assertThat(promptArea().text()).describedAs("the prompt").startsWith("@img ");
    }

    @Test
    @DisplayName("without it, the file is included where it lies and nothing is copied")
    void leavesTheFileWhereItIsWhenNotAsked() throws Exception {
        awaitReadyCount(1);

        Path original = png("elsewhere", "IMG_4002.png", 120, 80);
        includeWithCopies(false, original, "Image: 120 x 80");

        assertThat(logPane().text()).describedAs("the log pane's text")
                .doesNotContain("Copied for the include: ");
        assertThat(workingDirectory().resolve("JRock").resolve("includes"))
                .describedAs("JRock/includes/, which nothing should have created").doesNotExist();
        assertThat(includes().values()).describedAs("the files registered as includes")
                .containsExactly(original);
    }

    @Test
    @DisplayName("two different files of the same name are both kept, the second as -2")
    void doesNotLetOneCopyOverwriteAnother() throws Exception {
        awaitReadyCount(1);

        // The same name from two folders, which is what a camera's "IMG_4002.png" is:
        // one copy may not silently replace the other, or an include would end up
        // pointing at a picture that is not the one that was chosen.
        Path first = png("one", "photo.png", 120, 80);
        Path second = png("two", "photo.png", 60, 40);
        includeWithCopies(true, first, "Image: 120 x 80");
        includeWithCopies(true, second, "Image: 60 x 40");

        Path copies = workingDirectory().resolve("JRock").resolve("includes");
        assertThat(Files.readAllBytes(copies.resolve("photo.png")))
                .describedAs("the first copy").isEqualTo(Files.readAllBytes(first));
        assertThat(Files.readAllBytes(copies.resolve("photo-2.png")))
                .describedAs("the second copy, renamed").isEqualTo(Files.readAllBytes(second));
        assertThat(includes().values()).describedAs("the files registered as includes")
                .containsExactlyInAnyOrder(copies.resolve("photo.png"), copies.resolve("photo-2.png"));

        // And the same file again is not copied a third time: the bytes are already there
        // under that name, so that copy is the one to include.
        includeWithCopies(true, first, "Include copy already saved: ");
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Include copy already saved: " + copies.resolve("photo.png"));
        try (java.util.stream.Stream<Path> saved = Files.list(copies)) {
            assertThat(saved).describedAs("what is under JRock/includes/").hasSize(2);
        }
    }

    /**
     * Includes one file through the real dialog, with the checkbox in the given state,
     * and waits for the line that says this include is over.
     * <p>
     * Which line that is, is the caller's business: the include's own lines are the same
     * words every time, so a second include can only be waited for on something the
     * first one did not already write.
     */
    private void includeWithCopies(final boolean copies, Path file, String until) {
        chooseInTheIncludeDialog(IMAGE_FILTER,
                chooser -> tick(checkBoxIn(chooser, SAVE_COPIES), copies),
                file);
        awaitLogLine(until, INCLUDE_TIMEOUT_SECONDS);
    }

    /** The hash -&gt; path map JRock keeps its includes in. */
    @SuppressWarnings("unchecked")
    private static Map<String, Path> includes() throws Exception {
        return (Map<String, Path>) field("INCLUDES").get(null);
    }

    /** A real PNG in a subfolder of the working directory, outside JRock/. */
    private Path png(String folder, String name, int width, int height) throws Exception {
        Path dir = Files.createDirectories(workingDirectory().resolve(folder));
        Path file = dir.resolve(name);
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                "png", file.toFile())).describedAs("the JDK wrote the PNG").isTrue();
        return file;
    }
}
