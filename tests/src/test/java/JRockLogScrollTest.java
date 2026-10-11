import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import javax.swing.JTextPane;

import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.edt.GuiTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a line arriving in the log does to the reader: nothing, when they are in the
 * middle of reading it.
 * <p>
 * A selection in the log is somebody about to copy a path, a hash or an error. Appending
 * used to move the caret to the end of the document, which both scrolled the pane and
 * collapsed the selection - so a reply, or an autobackup finishing, took the reader's
 * place and their text away at once. Now the end is followed only when nothing is
 * selected, and never for a line that arrived on a timer.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockLogScrollTest extends JRockGuiFixture {

    /** Long enough for a line queued with invokeLater to be rendered. */
    private static final long LINE_TIMEOUT_SECONDS = 10;

    @Test
    @DisplayName("a line arriving keeps the selection, and the view stays where it was")
    void keepsTheSelectionAndThePlace() throws Exception {
        awaitReadyCount(1);

        // The startup report is already in the pane: "Ready." is the last line of it,
        // and selecting it is what a reader about to copy something looks like.
        int at = theLog().indexOf("Ready.");
        assertThat(at).describedAs("\"Ready.\" in the log").isGreaterThanOrEqualTo(0);
        select(at, at + "Ready.".length());
        int caretBefore = caret();

        gray("a line that arrived while somebody was reading");
        awaitLogLine("a line that arrived while somebody was reading", LINE_TIMEOUT_SECONDS);

        assertThat(selected()).describedAs("the selection after the line arrived")
                .isEqualTo("Ready.");
        assertThat(caret()).describedAs("the caret, which is what the pane scrolls to")
                .isEqualTo(caretBefore);
    }

    @Test
    @DisplayName("with nothing selected, an asked-for line follows the end and a timed one does not")
    void followsOnlyWhatWasAskedFor() throws Exception {
        awaitReadyCount(1);

        // Nothing selected, and the reader somewhere up the transcript.
        select(0, 0);
        assertThat(selected()).describedAs("the selection").isNull();

        grayQuiet("an autobackup finishing, which nobody asked for");
        awaitLogLine("an autobackup finishing, which nobody asked for", LINE_TIMEOUT_SECONDS);
        assertThat(caret()).describedAs("the caret after a line that arrived on a timer")
                .isZero();

        gray("something that happened because a button was pressed");
        awaitLogLine("something that happened because a button was pressed", LINE_TIMEOUT_SECONDS);
        assertThat(caret()).describedAs("the caret after a line somebody asked for")
                .isEqualTo(length());
    }

    // ---- the log pane, read and written on the EDT ----------------------------
    private JTextPane pane() {
        return (JTextPane) logPane().target();
    }

    private String theLog() {
        return logPane().text().replace("\r\n", "\n");
    }

    private void select(final int from, final int to) {
        GuiActionRunner.execute(new GuiTask() {
            @Override protected void executeInEDT() {
                pane().select(from, to);
            }
        });
    }

    private String selected() {
        return GuiActionRunner.execute(new GuiQuery<String>() {
            @Override protected String executeInEDT() {
                return pane().getSelectedText();
            }
        });
    }

    private int caret() {
        return GuiActionRunner.execute(new GuiQuery<Integer>() {
            @Override protected Integer executeInEDT() {
                return pane().getCaretPosition();
            }
        });
    }

    private int length() {
        return GuiActionRunner.execute(new GuiQuery<Integer>() {
            @Override protected Integer executeInEDT() {
                return pane().getStyledDocument().getLength();
            }
        });
    }

    // ---- JRock's own log, as the application writes to it ---------------------
    private void gray(String line) throws Exception {
        logView("gray", line);
    }

    private void grayQuiet(String line) throws Exception {
        logView("grayQuiet", line);
    }

    /** Calls one of LogView's own methods on the log this window is using. */
    private void logView(String method, String line) throws Exception {
        Object ui = field("ui").get(null);
        assertThat(ui).describedAs("JRock's live window").isNotNull();
        Field logField = ui.getClass().getDeclaredField("log");
        logField.setAccessible(true);
        Object log = logField.get(ui);
        Method m = log.getClass().getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        m.invoke(log, line);
    }
}
