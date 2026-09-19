import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The prompts directory: set in the Configure dialog, and where Ctrl+O and Ctrl+S open.
 * <p>
 * The property worth a test is that those two dialogs do <em>not</em> remember where
 * they were last. A chooser that remembers is the Swing default and the easy thing to
 * write, so this is the kind of behaviour that comes back by accident.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockPromptsDirTest extends JRockGuiFixture {

    @Test
    @DisplayName("Ctrl+O and Ctrl+S always open in the prompts directory, whatever was browsed last")
    void alwaysOpensInThePromptsDirectory() throws Exception {
        awaitReadyCount(1);

        Path library = Files.createDirectories(workingDirectory().resolve("library"));
        Path elsewhere = Files.createDirectories(workingDirectory().resolve("elsewhere"));
        Path strayPrompt = write(elsewhere.resolve("not-in-the-library.txt"), "a prompt from afar");

        // Configured through the dialog, which is the only way a user has.
        setPromptsDirectoryTo(library);
        assertThat(field("promptsDir").get(null))
                .describedAs("the configured prompts directory").isEqualTo(library);

        // 1. Ctrl+O opens in the library.
        JFileChooserFixture chooser = pressCtrlAnd(KeyEvent.VK_O);
        assertThat(currentDirectoryOf(chooser))
                .describedAs("where Load prompt opened").isEqualTo(real(library));

        // 2. Browse out of it and load a file from somewhere else entirely - which is
        //    allowed, and is exactly what would teach a remembering chooser the wrong
        //    directory.
        browseTo(chooser, elsewhere);
        chooser.selectFile(strayPrompt.toFile());
        chooser.approve();
        assertThat(promptArea().text())
                .describedAs("the prompt, loaded from outside the library")
                .isEqualTo("a prompt from afar");

        // 3. Ctrl+O again: back in the library, not where we just were.
        JFileChooserFixture reopened = pressCtrlAnd(KeyEvent.VK_O);
        assertThat(currentDirectoryOf(reopened))
                .describedAs("where Load prompt opened the second time")
                .isEqualTo(real(library));
        reopened.cancel();

        // 4. And Ctrl+S opens in the same place, so a prompt is saved back where
        //    prompts are loaded from.
        JFileChooserFixture save = pressCtrlAnd(KeyEvent.VK_S);
        assertThat(currentDirectoryOf(save))
                .describedAs("where Save prompt copy opened").isEqualTo(real(library));
        save.cancel();
    }

    @Test
    @DisplayName("prompts follow the working directory until a different one is set")
    void followTheWorkingDirectoryWhenUnset() throws Exception {
        // Unset is the startup state, and it means "wherever the working directory is"
        // rather than a path frozen at launch.
        assertThat(field("promptsDir").get(null))
                .describedAs("the prompts directory on a plain launch").isNull();
        assertThat(effectivePromptsDir())
                .describedAs("where prompts open when unset").isEqualTo(workingDirectory());

        // Setting it to the working directory is the same statement, so it is stored
        // the same way - as unset. Otherwise prompts would stop following a later
        // change to the working directory, and an installed Explorer entry would carry
        // a --prompts-dir naming the folder it happened to be launched from.
        applyPromptsDir(workingDirectory().resolve("library").toString());
        assertThat(field("promptsDir").get(null)).isNotNull();
        applyPromptsDir(workingDirectory().toString());
        assertThat(field("promptsDir").get(null))
                .describedAs("prompts set to the working directory").isNull();

        // Emptied means the same again.
        applyPromptsDir(workingDirectory().resolve("library").toString());
        applyPromptsDir("");
        assertThat(field("promptsDir").get(null))
                .describedAs("prompts cleared").isNull();

        // A directory that doesn't exist yet is created, not refused: it was typed or
        // browsed to deliberately, exactly like the working directory above it.
        Path fresh = workingDirectory().resolve("brand-new-library");
        assertThat(Files.exists(fresh)).isFalse();
        applyPromptsDir(fresh.toString());
        assertThat(Files.isDirectory(fresh))
                .describedAs("the prompts directory was created").isTrue();
        assertThat(effectivePromptsDir()).isEqualTo(fresh);
    }

    @Test
    @DisplayName("an installed Explorer entry carries --prompts-dir only when it differs")
    void installedCommandsCarryThePromptsDirectoryOnlyWhenItDiffers() throws Exception {
        // What gets baked into the registry. Nothing to say while prompts follow the
        // working directory: Explorer supplies that itself, and pinning it would make
        // every future "JRock here!" open Ctrl+O in today's folder.
        assertThat(ctxPromptsDirArg())
                .describedAs("the flag baked in when prompts follow the working directory")
                .isEmpty();

        Path library = Files.createDirectories(workingDirectory().resolve("library"));
        applyPromptsDir(library.toString());
        assertThat(ctxPromptsDirArg())
                .describedAs("the flag baked in once a prompts directory is set")
                .isEqualTo(" --prompts-dir \"" + library + "\"");

        // Same rule on the command line, so the two ways in agree: --prompts-dir
        // naming the working directory is accepted, reported, and then not stored.
        field("promptsDir").set(null, null);
        field("promptsDirNote").set(null, null);
        setPromptsDirFromCommandLine(workingDirectory().toString());
        assertThat(field("promptsDir").get(null))
                .describedAs("--prompts-dir naming the working directory").isNull();
        assertThat((String) field("promptsDirNote").get(null))
                .describedAs("what the session report says about it")
                .contains("same as the working directory");
        assertThat(ctxPromptsDirArg()).isEmpty();
    }

    /** Opens Configure, types the directory into the prompts row, and applies it. */
    private void setPromptsDirectoryTo(Path dir) {
        JOptionPaneFixture dialog = openConfigure();
        // By name: the working-directory row is the same kind of widget, and on a
        // plain launch it holds the same text, so type alone would not separate them.
        dialog.textBox("promptsDir").setText(dir.toString());
        dialog.okButton().click();
        // Applying re-runs the whole session report, ending in a second "Ready.".
        awaitReadyCount(2);
    }

    /**
     * Fires the Ctrl+&lt;key&gt; shortcut on the main window and returns the chooser it
     * opens. See {@link JRockGuiFixture#pressCtrl} for why the shortcut is triggered
     * through its binding rather than typed on the keyboard.
     */
    private JFileChooserFixture pressCtrlAnd(final int key) {
        pressCtrl(key);
        return JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
    }

    /** Navigates an open chooser to another directory, as a user browsing would. */
    private void browseTo(final JFileChooserFixture chooser, final Path dir) {
        GuiActionRunner.execute(() -> chooser.target().setCurrentDirectory(dir.toFile()));
        robot.waitForIdle();
    }

    /** The directory an open chooser is showing. */
    private File currentDirectoryOf(final JFileChooserFixture chooser) throws Exception {
        File current = GuiActionRunner.execute(new GuiQuery<File>() {
            @Override
            protected File executeInEDT() {
                return chooser.target().getCurrentDirectory();
            }
        });
        return current == null ? null : real(current.toPath());
    }

    /**
     * The path as the filesystem finally spells it.
     * <p>
     * A chooser hands its directory back through FileSystemView, and on Windows the
     * temporary directory these tests run in can come back with a different (8.3) form
     * of the same path. Both sides are resolved so the comparison is about the
     * directory, not about its spelling.
     */
    private static File real(Path path) throws Exception {
        return path.toRealPath().toFile();
    }

    private static Path write(Path file, String text) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /** {@code JRock.promptsDir()}: the directory in effect, configured or inherited. */
    private static Path effectivePromptsDir() throws Exception {
        Method m = JRock.class.getDeclaredMethod("promptsDir");
        m.setAccessible(true);
        return (Path) m.invoke(null);
    }

    /** What the Configure dialog does with the prompts row on OK. */
    private static void applyPromptsDir(String value) throws Exception {
        Method m = JRock.class.getDeclaredMethod(
                "applyPromptsDir", javax.swing.JFrame.class, String.class);
        m.setAccessible(true);
        // No frame needed: it is only used for the "could not create" warning, and
        // every path used here succeeds.
        m.invoke(null, null, value);
    }

    /** What {@code --prompts-dir} on the command line does. */
    private static void setPromptsDirFromCommandLine(String value) throws Exception {
        Method m = JRock.class.getDeclaredMethod("setPromptsDir", String.class);
        m.setAccessible(true);
        m.invoke(null, value);
    }

    /** The {@code --prompts-dir} fragment baked into an installed launch command. */
    private static String ctxPromptsDirArg() throws Exception {
        Method m = JRock.class.getDeclaredMethod("ctxPromptsDirArg");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }
}
