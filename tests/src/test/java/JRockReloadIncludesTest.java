import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes files, throws away the hash -&gt; path map the way a restart does, and reloads
 * it from the log with the prompt menu's <em>Reload all includes</em>.
 * <p>
 * That map is the one thing JRock deliberately does not persist, while the prompt and the
 * whole transcript are recovered from disk - so after a restart the tokens are all still
 * there and nothing knows what they stand for any more. What makes the repair possible is
 * that the log wrote down every include it ever made, and clearing the map by reflection
 * is the same broken state a restart leaves, minus the restart.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message. Nothing is hashed by the reload either, which is why a
 * file that has changed is not part of what is checked here - that is
 * {@code verifyIncludes} on send.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see JRockIncludeCopyTest for keeping the files themselves within reach
 */
class JRockReloadIncludesTest extends JRockGuiFixture {

    private static final String IMAGE_FILTER = "Image files (png, jpg, jpeg, gif, webp)";
    private static final String TEXT_FILTER =
            "Text files as is (*.txt, *.csv, *.html, *.java, *.rtf)";
    private static final String RELOAD_ITEM = "Reload all includes";

    /** Reading a log and a handful of paths; a slow CI runner needs the rest. */
    private static final long RELOAD_TIMEOUT_SECONDS = 30;

    @Test
    @DisplayName("the includes a lost session made are reloaded from the log, both kinds")
    void reloadsEveryIncludeTheLogRecorded() throws Exception {
        awaitReadyCount(1);

        Path png = png("one", "IMG_4002.png", 120, 80);
        Path txt = text("notes.txt", "Two included files, one of each kind.");
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        include(TEXT_FILTER, txt, "Text: ");

        // What the session knew, and then the state a restart leaves: the tokens still
        // in the prompt, and nothing behind them.
        Map<String, Path> before = new LinkedHashMap<>(includes());
        assertThat(before).describedAs("the includes before the map is lost").hasSize(2);
        includes().clear();

        reload();

        // Reloaded, and each one named with the path the log recorded for it.
        assertThat(includes()).describedAs("the includes after reloading them")
                .isEqualTo(before);
        for (Map.Entry<String, Path> e : before.entrySet()) {
            String kind = e.getValue().equals(png) ? "img" : "txt";
            assertThat(logPane().text()).describedAs("the log pane's text")
                    .contains("Reloaded @" + kind + " " + e.getKey() + " from " + e.getValue());
        }
        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Reload all includes: 2 reloaded (of 2 referred to).");
    }

    @Test
    @DisplayName("a reference with no include line behind it is named, not silently dropped")
    void saysWhichReferencesTheLogHasNoRecordOf() throws Exception {
        awaitReadyCount(1);

        // A token for a file this session never included: what is left after the log has
        // been cleared, and what the model writes if it invents a hash. There is nothing
        // to reload it from, and the user is the only one who can fix it.
        enterText(promptArea(), "Look at @img 0123456789ab and tell me what you see.\n");

        reload();

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("No include recorded for @img 0123456789ab - attach the file "
                        + "again with Ctrl+I (the log may have been cleared since).")
                .contains("Reload all includes: 0 reloaded, 1 not recorded in the log "
                        + "(of 1 referred to).");
        assertThat(includes()).describedAs("the includes, which gained nothing").isEmpty();
    }

    @Test
    @DisplayName("an include whose file has since gone is reloaded, and said to be gone")
    void reportsAnIncludeWhoseFileIsNoLongerThere() throws Exception {
        awaitReadyCount(1);

        Path png = png("one", "IMG_4002.png", 120, 80);
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        String hash = includes().keySet().iterator().next();
        includes().clear();
        Files.delete(png);      // the browser's /uploads after a reload, on the desktop

        reload();

        assertThat(logPane().text()).describedAs("the log pane's text")
                .contains("Reloaded @img " + hash + " from " + png
                        + " - but that file is not there any more.")
                .contains("Reload all includes: 0 reloaded, 1 with a missing file "
                        + "(of 1 referred to).");
        // Registered all the same: the mapping is what the log recorded, and the send
        // will say the file is missing rather than that the hash is unknown.
        assertThat(includes()).describedAs("the includes after reloading them")
                .containsEntry(hash, png);
    }

    @Test
    @DisplayName("a file included twice is reloaded from where it was included last")
    void prefersTheLatestPathForAHash() throws Exception {
        awaitReadyCount(1);

        // The same bytes in two places, so both includes are the same hash: the second
        // include line has to win, being the later record of where that file is.
        Path first = png("one", "IMG_4002.png", 120, 80);
        Path second = Files.copy(first, Files.createDirectories(
                workingDirectory().resolve("two")).resolve("IMG_4002.png"));
        include(IMAGE_FILTER, first, "Image: 120 x 80");
        include(IMAGE_FILTER, second, "Already referenced (@img ");

        Map<String, Path> before = new LinkedHashMap<>(includes());
        assertThat(before).describedAs("one hash for the two copies").hasSize(1);
        includes().clear();

        reload();

        assertThat(includes().values()).describedAs("the includes after reloading them")
                .containsExactly(second);
    }

    /** Includes one file through the real dialog, and waits for it to be over. */
    private void include(String filter, Path file, String until) {
        chooseInTheIncludeDialog(filter, file);
        awaitLogLine(until, RELOAD_TIMEOUT_SECONDS);
    }

    /** Invokes Reload all includes from the prompt's context menu, and waits for it. */
    private void reload() {
        chooseInThePromptMenu(RELOAD_ITEM);
        awaitLogLine("Reload all includes: ", RELOAD_TIMEOUT_SECONDS);
    }

    /** The hash -&gt; path map JRock keeps its includes in. */
    @SuppressWarnings("unchecked")
    private static Map<String, Path> includes() throws Exception {
        return (Map<String, Path>) field("INCLUDES").get(null);
    }

    /** A real PNG in a subfolder of the working directory. */
    private Path png(String folder, String name, int width, int height) throws Exception {
        Path dir = Files.createDirectories(workingDirectory().resolve(folder));
        Path file = dir.resolve(name);
        assertThat(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB),
                "png", file.toFile())).describedAs("the JDK wrote the PNG").isTrue();
        return file;
    }

    /** A text file in the working directory. */
    private Path text(String name, String content) throws Exception {
        return Files.write(workingDirectory().resolve(name),
                content.getBytes(StandardCharsets.UTF_8));
    }
}
