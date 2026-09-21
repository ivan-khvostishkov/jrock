import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opening another working directory, which is a whole switch of everything JRock keeps
 * on disk - and therefore the one moment where its own files can be destroyed.
 * <p>
 * The prompt is autosaved over on every keystroke, so a switch that leaves the previous
 * directory's prompt in the box has armed the next keystroke to overwrite the prompt
 * stored in the directory just opened. That is unrecoverable, and it is the kind of bug
 * that is invisible in use until the work is already gone - so it is tested from both
 * sides: what the newly opened directory keeps, and what the one left behind keeps.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockWorkingDirTest extends JRockGuiFixture {

    private static final String LEFT_BEHIND = "the prompt left in the other folder";
    private static final String ON_SCREEN = "work in progress, unsaved anywhere else";

    @Test
    @DisplayName("opening a folder that has a prompt takes it over, and overwrites nothing")
    void takesOverThePromptOfTheDirectoryItOpens() throws Exception {
        awaitReadyCount(1);

        // A folder somebody has already worked in: it has a prompt of its own.
        Path other = workingDirectory().resolve("other");
        Path itsPrompt = promptFileIn(other);
        write(itsPrompt, LEFT_BEHIND);

        // And a prompt on screen here, which the autosave has put in this folder.
        putInThePrompt(ON_SCREEN);
        assertThat(read(promptFileIn(workingDirectory())))
                .describedAs("the prompt autosaved in the folder about to be left")
                .isEqualTo(ON_SCREEN);

        switchWorkingDirectoryTo(other);

        // 1. The other folder's prompt is what is now on screen - and still what is in
        //    its file, which is the part that used to be lost.
        assertThat(promptArea().text())
                .describedAs("the prompt after opening the other folder").isEqualTo(LEFT_BEHIND);
        assertThat(read(itsPrompt))
                .describedAs("the prompt file in the folder that was opened").isEqualTo(LEFT_BEHIND);

        // 2. Nor was anything lost at the other end: the folder left behind keeps what
        //    was on screen when it was left.
        assertThat(read(promptFileIn(workingDirectory())))
                .describedAs("the prompt file in the folder left behind").isEqualTo(ON_SCREEN);

        // 3. And the session report says where the prompt came from, as it does at
        //    startup: a prompt that changes by itself needs explaining.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Prompt source: recovered persistent file: JRock/jrock-prompt.txt");
    }

    @Test
    @DisplayName("a folder with no prompt of its own keeps the one on screen, and stores it")
    void carriesThePromptIntoAFolderThatHasNone() throws Exception {
        awaitReadyCount(1);

        // The other half: nothing can be overwritten in an empty folder, so the prompt
        // comes along - which is what makes opening a new folder to work in it useful.
        Path fresh = workingDirectory().resolve("fresh");
        putInThePrompt(ON_SCREEN);

        switchWorkingDirectoryTo(fresh);

        assertThat(promptArea().text())
                .describedAs("the prompt after opening an empty folder").isEqualTo(ON_SCREEN);
        assertThat(read(promptFileIn(fresh)))
                .describedAs("the prompt autosaved into the folder that had none")
                .isEqualTo(ON_SCREEN);
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .contains("Prompt source: carried over from the previous working directory");
    }

    /**
     * Types the working directory into the Configure dialog and applies it - the only
     * way a user has of switching.
     */
    private void switchWorkingDirectoryTo(Path dir) {
        JOptionPaneFixture dialog = openConfigure();
        // By name: the prompts row is the same kind of widget, holding the same text.
        enterText(dialog.textBox("workingDir"), dir.toString());
        press(dialog.okButton());
        // Applying re-runs the whole session report, this time for the directory just
        // opened - and the prompt is taken over before that report is written, so this
        // is also the wait for the switch itself.
        awaitSessionReportFor(dir);
    }

    /**
     * Puts text in the prompt, which is what arms the autosave: the file is rewritten on
     * every change to this document. See {@link JRockGuiFixture#enterText}.
     */
    private void putInThePrompt(String text) {
        enterText(promptArea(), text);
    }

    /** Where JRock autosaves the prompt of a given working directory. */
    private static Path promptFileIn(Path workingDir) {
        return workingDir.resolve("JRock").resolve("jrock-prompt.txt");
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        assertThat(file).describedAs("the prompt file").exists();
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
