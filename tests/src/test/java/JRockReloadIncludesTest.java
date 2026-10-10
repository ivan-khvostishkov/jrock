import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Includes files, throws away the hash -&gt; path map the way a restart does, and has it
 * reloaded from the log the way every session start reloads it - here, the session
 * report that applying the Configure dialog runs again.
 * <p>
 * That map is the one thing JRock deliberately does not persist, while the prompt and the
 * whole transcript are recovered from disk - so after a restart the tokens are all still
 * there and nothing knows what they stand for any more. What makes the repair possible is
 * that the log wrote down every include it ever made, and clearing the map by reflection
 * is the same broken state a restart leaves, minus the restart.
 * <p>
 * Offline, like the rest: no API key is set, so JRock skips its model-list fetch, and
 * nothing here sends a message. What a send does with a token whose file is missing is
 * checked on {@code verifyIncludes} and {@code buildParts} directly.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 * @see JRockIncludeCopyTest for keeping the files themselves within reach
 */
class JRockReloadIncludesTest extends JRockGuiFixture {

    private static final String IMAGE_FILTER = "Image files (png, jpg, jpeg, gif, webp)";
    private static final String TEXT_FILTER =
            "Text files as is (*.txt, *.md, *.csv, *.json, *.xml, *.html, *.svg, *.java)";

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

        assertThat(includes()).describedAs("the includes after reloading them")
                .isEqualTo(before);
        assertThat(logLines()).describedAs("the log's lines")
                .contains("Includes reloaded from the log: 2 of 2 referred to");
    }

    @Test
    @DisplayName("a real restart reloads the includes from the log it loads from disk")
    void reloadsTheIncludesAtStartup() throws Exception {
        awaitReadyCount(1);

        Path png = png("one", "IMG_4002.png", 120, 80);
        Path txt = text("notes.txt", "Two included files, one of each kind.");
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        include(TEXT_FILTER, txt, "Text: ");
        Map<String, Path> before = new LinkedHashMap<>(includes());
        assertThat(before).describedAs("the includes before the restart").hasSize(2);

        // At startup the log pane is empty until the log read from disk is put into
        // it, later, on the EDT: the reload has to read what was loaded, not the pane.
        includes().clear();
        restartTheApplication();
        awaitReadyCount(2);

        assertThat(includes()).describedAs("the includes after the restart")
                .isEqualTo(before);
        assertThat(logLines()).describedAs("the log's lines")
                .contains("Includes reloaded from the log: 2 of 2 referred to");
    }

    @Test
    @DisplayName("a reference that names its file finds it again after a restart")
    void reloadsWhatAReferenceNamed() throws Exception {
        awaitReadyCount(1);
        field("includeMarkdownRefs").set(null, true);

        Path png = png("one", "IMG_4002.png", 120, 80);
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        String hash = includes().keySet().iterator().next();
        assertThat(refTargets()).describedAs("what the reference names")
                .containsEntry("IMG_4002.png", hash);

        // A restart: both maps gone, the prompt's "![...](IMG_4002.png)" still there.
        includes().clear();
        refTargets().clear();

        reload();

        // The log's reference line says what the name stands for, so the DOCX export
        // can place the picture an answer refers to by that name again.
        assertThat(refTargets()).describedAs("what the reference names, reloaded")
                .containsEntry("IMG_4002.png", hash);
        assertThat(includes()).containsEntry(hash, png);
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
                .contains("Includes reloaded from the log: 0 of 1 referred to, 1 not "
                        + "recorded in the log - a send stops until each one is included again");
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
                .contains("Includes reloaded from the log: 0 of 1 referred to, 1 with a "
                        + "missing file - a send stops until each one is included again");
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

    @Test
    @DisplayName("by default a send stops on a missing file, naming it and the setting")
    void stopsTheSendOnAMissingFile() throws Exception {
        awaitReadyCount(1);
        assertThat(field("missingIncludeStopsSend").get(null))
                .describedAs("the setting, on by default").isEqualTo(true);

        Path png = png("one", "IMG_4002.png", 120, 80);
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        String hash = includes().keySet().iterator().next();
        Files.delete(png);

        // Refused before anything goes out, so no key or network is needed.
        press(window.button(new org.assertj.swing.core.GenericTypeMatcher<javax.swing.JButton>(
                javax.swing.JButton.class) {
            @Override
            protected boolean isMatching(javax.swing.JButton button) {
                return button.getText() != null && button.getText().startsWith("Send");
            }
        }));
        awaitLogLine("Not sent: ", RELOAD_TIMEOUT_SECONDS);

        assertThat(logLines()).describedAs("the log's lines")
                .contains("Missing include: included file is missing: " + png
                        + " (@img " + hash + ").")
                .contains("Not sent: 1 included file is missing. Include them again with "
                        + "Ctrl+I, or turn off \"Stop a send on a missing include\" in "
                        + "Configure to send their tokens as text.");
        assertThat(logPane().text()).doesNotContain("Calling ");
    }

    @Test
    @DisplayName("what a send is told about a missing file, which buildParts sends as text")
    void sendsTheTokenOfAMissingFileAsText() throws Exception {
        awaitReadyCount(1);

        Path png = png("one", "IMG_4002.png", 120, 80);
        include(IMAGE_FILTER, png, "Image: 120 x 80");
        String hash = includes().keySet().iterator().next();
        Files.delete(png);
        String prompt = "Before @img " + hash + " and @txt 0123456789ab after.";

        Set<String> warnings = new LinkedHashSet<>();
        Method verify = method("verifyIncludes", String.class, Set.class);
        assertThat(verify.invoke(null, prompt, warnings))
                .describedAs("the error that would stop the send").isNull();
        assertThat(warnings).describedAs("what is missing").containsExactly(
                "included file is missing: " + png + " (@img " + hash + ")",
                "@txt 0123456789ab is not known - no include of it is recorded in the log");

        // Neither token is expanded: the prompt goes as the one text it is.
        List<?> parts = (List<?>) method("buildParts", String.class).invoke(null, prompt);
        assertThat(parts).describedAs("the content parts").hasSize(1);
        Field text = parts.get(0).getClass().getDeclaredField("text");
        text.setAccessible(true);
        assertThat(text.get(parts.get(0))).isEqualTo(prompt);
    }

    private static Method method(String name, Class<?>... types) throws Exception {
        Method m = JRock.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m;
    }

    /** Includes one file through the real dialog, and waits for it to be over. */
    private void include(String filter, Path file, String until) {
        // Where it lies, not from a processed copy: where each file was is the point.
        chooseInTheIncludeDialog(filter, false, file);
        awaitLogLine(until, RELOAD_TIMEOUT_SECONDS);
    }

    /**
     * Applies the Configure dialog unchanged, which runs the session report again - and
     * the reload of the includes with it, as a start does - and waits for it to finish.
     */
    private void reload() {
        int ready = readyCount();
        press(openConfigure().okButton());
        awaitReadyCount(ready + 1);
    }

    /** The hash -&gt; path map JRock keeps its includes in. */
    @SuppressWarnings("unchecked")
    private static Map<String, Path> includes() throws Exception {
        return (Map<String, Path>) field("INCLUDES").get(null);
    }

    /** The reference name -&gt; hash map the DOCX export finds pictures by. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> refTargets() throws Exception {
        return (Map<String, String>) field("REF_TARGETS").get(null);
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
