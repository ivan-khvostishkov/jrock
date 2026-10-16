import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a role header says: the time to the minute, and who said it.
 * <p>
 * {@code [HUMAN OPERATOR] · Fri, 16 Oct 2026, 14:22 · Ivan} and
 * {@code [OPERATOR'S ASSISTANT] · Fri, 16 Oct 2026, 14:22 · JRock} — a transcript read a
 * year later is a conversation between two parties neither of whom is otherwise named on
 * it, and seconds are a measurement nobody wanted in a line they only read.
 * <p>
 * The date itself is the locale's own, so what is asserted here is the SHAPE: the role,
 * the separators, the name at the end, and that no part of it is a clock with seconds in
 * it. And the one invariant underneath: a header suffix is what says a message was sent
 * with History on, so a side question has none at all and stays out of the conversation.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockHeaderTest extends JRockGuiFixture {

    /** Long enough for a line queued with invokeLater to be rendered. */
    private static final long LINE_TIMEOUT_SECONDS = 10;

    private static final String HUMAN = "[HUMAN OPERATOR]";
    private static final String ASSISTANT = "[OPERATOR'S ASSISTANT]";

    @Test
    @DisplayName("a header carries the time to the minute, and who said it")
    void saysTheTimeToTheMinuteAndWhoSaidIt() throws Exception {
        awaitReadyCount(1);
        field("operatorName").set(null, "Ivan");

        dialog("human", "a question nobody has to answer");
        awaitLogLine("a question nobody has to answer", LINE_TIMEOUT_SECONDS);
        dialog("assistant", "an answer nobody has to read");
        awaitLogLine("an answer nobody has to read", LINE_TIMEOUT_SECONDS);

        String asked = headerBefore("a question nobody has to answer");
        assertThat(asked).describedAs("the question's header")
                .startsWith(HUMAN + " \u00B7 ").endsWith(" \u00B7 Ivan");
        String answered = headerBefore("an answer nobody has to read");
        assertThat(answered).describedAs("the answer's header")
                .startsWith(ASSISTANT + " \u00B7 ").endsWith(" \u00B7 JRock");

        // NO SECONDS, whatever the locale writes the date like: a header is read, not
        // measured. The seconds are still in the stamp that names the message file.
        assertThat(asked).describedAs("the question's header, as a clock")
                .doesNotMatch(".*\\d:\\d\\d:\\d\\d.*");
        assertThat(answered).describedAs("the answer's header, as a clock")
                .doesNotMatch(".*\\d:\\d\\d:\\d\\d.*");
    }

    @Test
    @DisplayName("a side question has no suffix at all, and stays out of the conversation")
    void aSideQuestionCarriesNothing() throws Exception {
        awaitReadyCount(1);
        field("operatorName").set(null, "Ivan");

        // History off is what withTimestamp=false means, and the empty suffix is what
        // dialogHistory reads it by - so the name must not be written there either.
        dialogWithoutTime("human", "a side question");
        awaitLogLine("a side question", LINE_TIMEOUT_SECONDS);
        assertThat(headerBefore("a side question")).describedAs("a side question's header")
                .isEqualTo(HUMAN);

        dialog("human", "a question in the conversation");
        awaitLogLine("a question in the conversation", LINE_TIMEOUT_SECONDS);

        List<String[]> history = dialogHistory();
        assertThat(history).describedAs("what History would send").hasSize(1);
        assertThat(history.get(0)[1]).describedAs("and it is the one asked with History on")
                .isEqualTo("a question in the conversation");
    }

    // ---- the log, read and written through LogView's own methods ---------------
    /** The line above the first line of a message, which is its role header. */
    private String headerBefore(String body) {
        String[] lines = logPane().text().replace("\r\n", "\n").split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals(body) && i > 0) {
                // The body has a blank line under the header (see renderDialog).
                return lines[i - 1].isEmpty() && i > 1 ? lines[i - 2] : lines[i - 1];
            }
        }
        throw new AssertionError("\"" + body + "\" is not a line of the log");
    }

    private void dialog(String role, String text) throws Exception {
        message(role, text, true);
    }

    private void dialogWithoutTime(String role, String text) throws Exception {
        message(role, text, false);
    }

    /** {@code log.human(text, withTimestamp)} / {@code log.assistant(...)}. */
    private void message(String role, String text, boolean withTimestamp) throws Exception {
        Object log = logView();
        Method m = log.getClass().getDeclaredMethod(role, String.class, boolean.class);
        m.setAccessible(true);
        m.invoke(log, text, withTimestamp);
    }

    @SuppressWarnings("unchecked")
    private List<String[]> dialogHistory() throws Exception {
        Object log = logView();
        Method m = log.getClass().getDeclaredMethod("dialogHistory");
        m.setAccessible(true);
        return (List<String[]>) m.invoke(log);
    }

    private Object logView() throws Exception {
        Object ui = field("ui").get(null);
        assertThat(ui).describedAs("JRock's live window").isNotNull();
        Field logField = ui.getClass().getDeclaredField("log");
        logField.setAccessible(true);
        return logField.get(ui);
    }
}
