import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javax.swing.JTextPane;

import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.timing.Condition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ctrl+Shift+A: the last answer selected whole, and nothing of the transcript around it.
 * <p>
 * The step before an export - ask for a letter to be drafted, select the answer, press
 * Ctrl+Shift+D - and the one step of that which used to need the mouse. A draft is longer
 * than the window, so by hand it is click at the top, scroll, shift-click at the bottom,
 * with the teal "[OPERATOR'S ASSISTANT]" line one row above the first word and the next
 * gray line one row below the last: a selection that is nearly right most times it is
 * made, and a heading nobody wrote in the document that comes out of it.
 * <p>
 * So what is asserted here is what the key takes: the body of the LAST assistant message,
 * byte for byte, with no header, no gray line and nothing of the question that prompted it.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockSelectAnswerTest extends JRockGuiFixture {

    /** Long enough for a line queued with invokeLater to be rendered. */
    private static final long LINE_TIMEOUT_SECONDS = 10;

    /** The key's action is queued the same way, so the selection arrives the same way. */
    private static final long SELECTION_TIMEOUT_SECONDS = 10;

    /** An earlier answer, to prove the LAST one is the one taken. */
    private static final String EARLIER = "Theodor-Heuss-Ring 12 is the address on the lease.";

    /**
     * The answer under test, with what a draft really arrives with: a heading, blank
     * lines, a list and a sign-off. The blank lines matter - they are what a selection
     * dragged by hand is most likely to stop one short of.
     */
    private static final String DRAFT = "# Heating in flat 3\n"
            + "\n"
            + "Dear Mrs Baumgartner,\n"
            + "\n"
            + "the heating in flat 3 has been off since Monday. I am asking for:\n"
            + "\n"
            + "- an engineer this week,\n"
            + "- the radiators bled on the second floor.\n"
            + "\n"
            + "Yours sincerely";

    @Test
    @DisplayName("Ctrl+Shift+A selects the last answer whole, without its header or the lines around it")
    void selectsTheLastAnswerWhole() throws Exception {
        awaitReadyCount(1);

        // A conversation with two answers in it, a question between them, and a gray
        // line underneath - which is the shape of every log this key is pressed in.
        answer(EARLIER);
        awaitLogLine(EARLIER, LINE_TIMEOUT_SECONDS);
        ask("draft a letter about the heating");
        answer(DRAFT);
        awaitLogLine("Yours sincerely", LINE_TIMEOUT_SECONDS);
        gray("a line JRock wrote after the answer");
        awaitLogLine("a line JRock wrote after the answer", LINE_TIMEOUT_SECONDS);

        pressCtrlShift(KeyEvent.VK_A);
        awaitSelection();

        assertThat(selected()).describedAs("what Ctrl+Shift+A selected").isEqualTo(DRAFT);
    }

    @Test
    @DisplayName("with no answer in the log, it says so instead of doing nothing")
    void saysSoWhenNothingHasBeenAnswered() throws Exception {
        awaitReadyCount(1);

        // A key cannot be greyed out the way the menu item is, so it reports instead:
        // a key that does nothing at all is one somebody presses twice and stops trusting.
        pressCtrlShift(KeyEvent.VK_A);
        awaitLogLine("Nothing to select", LINE_TIMEOUT_SECONDS);

        assertThat(selected()).describedAs("the selection in a log with no answer in it")
                .isNull();
    }

    // ---- the log pane, read on the EDT ----------------------------------------
    private JTextPane pane() {
        return (JTextPane) logPane().target();
    }

    /** The selection as the document holds it, with the line endings the document uses. */
    private String selected() {
        String text = GuiActionRunner.execute(new GuiQuery<String>() {
            @Override protected String executeInEDT() {
                return pane().getSelectedText();
            }
        });
        return text == null ? null : text.replace("\r\n", "\n");
    }

    private void awaitSelection() {
        pause(new Condition("the log to have a selection") {
            @Override public boolean test() {
                return selected() != null;
            }
        }, timeout(SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    // ---- JRock's own log, as the application writes to it ---------------------
    private void answer(String text) throws Exception {
        message("assistant", text);
    }

    private void ask(String text) throws Exception {
        message("human", text);
    }

    private void gray(String line) throws Exception {
        Method m = logView().getClass().getDeclaredMethod("gray", String.class);
        m.setAccessible(true);
        m.invoke(logView(), line);
    }

    /** Logs a dialog message the way a send does, without a send. */
    private void message(String role, String text) throws Exception {
        Object log = logView();
        Method m = log.getClass().getDeclaredMethod(role, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(log, text, false);
    }

    /** The LogView the live window is using. */
    private Object logView() throws Exception {
        Object ui = field("ui").get(null);
        assertThat(ui).describedAs("JRock's live window").isNotNull();
        Field logField = ui.getClass().getDeclaredField("log");
        logField.setAccessible(true);
        return logField.get(ui);
    }
}
