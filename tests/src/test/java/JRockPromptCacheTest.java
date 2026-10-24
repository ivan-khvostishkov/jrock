import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Local prompt cache and the History filter, through the real Send button (driven
 * by the automation API, which presses it).
 * <p>
 * Offline: no API key is set, so a send that does reach Bedrock fails at once - which is
 * how a test tells a cache hit, which never gets that far, from a miss.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockPromptCacheTest extends JRockGuiFixture {

    private static final long SEND_TIMEOUT_MS = 30_000;

    @Test
    @DisplayName("a prompt answered before, in any file of __JRock/messages/, is answered from it")
    void answersAPromptSentBeforeFromItsFile() throws Exception {
        awaitReadyCount(1);
        Path messages = Files.createDirectories(workingDirectory().resolve("__JRock").resolve("messages"));
        write(messages.resolve("20260101-100000-000-operator.txt"), "What is 2+2?");
        write(messages.resolve("20260101-100005-000-assistant.txt"), "4.");
        // Asked, never answered (the send failed), then asked something else.
        write(messages.resolve("20260102-100000-000-operator.txt"), "Unanswered?");
        write(messages.resolve("20260102-100100-000-operator.txt"), "Other?");
        write(messages.resolve("20260102-100105-000-assistant.txt"), "Other answer.");
        rebuildCache();

        assertThat(JRock.automationBegin("cache test")).isNull();
        try {
            assertThat(JRock.automationSetPrompt("What is 2+2?")).isNull();
            JRock.Sent sent = JRock.automationSend(SEND_TIMEOUT_MS);
            assertThat(sent.ok()).describedAs("the send, answered: " + sent.failure()).isTrue();
            assertThat(read(JRock.automationMessageFile("assistant", sent.assistant()))).isEqualTo("4.");
            assertThat(logPane().text())
                    .contains("Local prompt cache: this prompt was answered before - reusing "
                            + "__JRock/messages/20260101-100005-000-assistant.txt, with no call "
                            + "to Bedrock.")
                    .doesNotContain("Calling ");

            // The one that failed is not an answer to anything.
            assertThat(JRock.automationSetPrompt("Unanswered?")).isNull();
            assertThat(JRock.automationSend(SEND_TIMEOUT_MS).ok()).isFalse();
            assertThat(logPane().text()).contains("Calling ");
        } finally {
            JRock.automationEnd("done");
        }
    }

    @Test
    @DisplayName("an answer written in this session joins the cache without the folder being read again")
    void addsEachNewAnswerAsItIsWritten() throws Exception {
        awaitReadyCount(1);
        rebuildCache();   // an empty folder: nothing cached

        // A question and its answer, written the way a send writes them.
        Object log = log();
        Method human = log.getClass().getDeclaredMethod("human", String.class, boolean.class);
        Method assistant = log.getClass().getDeclaredMethod("assistant", String.class, boolean.class);
        human.setAccessible(true);
        assistant.setAccessible(true);
        human.invoke(log, "Capital of France?", false);
        assistant.invoke(log, "Paris.", false);
        awaitLogLine("Paris.", 10);

        assertThat(JRock.automationBegin("cache test")).isNull();
        try {
            assertThat(JRock.automationSetPrompt("Capital of France?")).isNull();
            JRock.Sent sent = JRock.automationSend(SEND_TIMEOUT_MS);
            assertThat(sent.ok()).describedAs("the send, answered: " + sent.failure()).isTrue();
            assertThat(read(JRock.automationMessageFile("assistant", sent.assistant()))).isEqualTo("Paris.");
        } finally {
            JRock.automationEnd("done");
        }
    }

    @Test
    @DisplayName("with the cache off, nothing is answered from it, and the table is gone")
    void sendsEverythingWithTheCacheOff() throws Exception {
        awaitReadyCount(1);
        Path messages = Files.createDirectories(workingDirectory().resolve("__JRock").resolve("messages"));
        write(messages.resolve("20260101-100000-000-operator.txt"), "What is 2+2?");
        write(messages.resolve("20260101-100005-000-assistant.txt"), "4.");
        field("promptCacheOn").set(null, false);
        rebuildCache();
        assertThat(field("promptCache").get(null)).describedAs("the table").isNull();

        assertThat(JRock.automationBegin("cache test")).isNull();
        try {
            assertThat(JRock.automationSetPrompt("What is 2+2?")).isNull();
            assertThat(JRock.automationSend(SEND_TIMEOUT_MS).ok()).isFalse();
            assertThat(logPane().text()).contains("Calling ")
                    .doesNotContain("this prompt was answered before");
        } finally {
            JRock.automationEnd("done");
        }
    }

    @Test
    @DisplayName("History sends only the messages that were sent with History ticked")
    @SuppressWarnings("unchecked")
    void historyLeavesOutTheSideQuestions() throws Exception {
        awaitReadyCount(1);
        Object log = log();
        Method human = log.getClass().getDeclaredMethod("human", String.class, boolean.class);
        Method assistant = log.getClass().getDeclaredMethod("assistant", String.class, boolean.class);
        human.setAccessible(true);
        assistant.setAccessible(true);
        human.invoke(log, "Let us plan a trip.", true);         // with History
        assistant.invoke(log, "Where to?", true);
        human.invoke(log, "Side question: what is 2+2?", false); // without
        assistant.invoke(log, "4.", false);
        human.invoke(log, "To Rome.", true);
        assistant.invoke(log, "Rome it is.", true);
        awaitLogLine("Rome it is.", 10);

        Method history = log.getClass().getDeclaredMethod("dialogHistory");
        history.setAccessible(true);
        List<String[]> turns = new java.util.ArrayList<>();
        org.assertj.swing.edt.GuiActionRunner.execute(new org.assertj.swing.edt.GuiTask() {
            @Override
            protected void executeInEDT() throws Exception {
                turns.addAll((List<String[]>) history.invoke(log));
            }
        });
        List<String> texts = new java.util.ArrayList<>();
        for (String[] turn : turns) texts.add(turn[1]);
        assertThat(texts).containsExactly("Let us plan a trip.", "Where to?", "To Rome.",
                "Rome it is.");
    }

    /** Builds the table afresh from the working folder, as turning the setting on does. */
    private void rebuildCache() throws Exception {
        field("promptCache").set(null, null);
        Method sync = JRock.class.getDeclaredMethod("syncPromptCache", log().getClass());
        sync.setAccessible(true);
        sync.invoke(null, log());
    }

    private static Object log() throws Exception {
        Object ui = field("ui").get(null);
        java.lang.reflect.Field logField = ui.getClass().getDeclaredField("log");
        logField.setAccessible(true);
        return logField.get(ui);
    }

    private static void write(Path file, String text) throws Exception {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws Exception {
        // Absolute by the API's own contract, which is worth saying once here: what comes
        // back is a path to read, not text to turn into one.
        assertThat(file).describedAs("the reply's message file").isNotNull();
        assertThat(file.isAbsolute()).describedAs("and an absolute path: " + file).isTrue();
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
