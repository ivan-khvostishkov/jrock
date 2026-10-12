import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JPasswordField;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.fixture.JTextComponentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the Configure dialog: gives it an API key, applies it, and reopens the
 * dialog to check the key is not shown back - and that the key reached
 * {@code JRock/bedrock-key.txt}, which is where the next start reads it from.
 * <p>
 * The API key field is write-only by design - blank every time the dialog opens,
 * whatever key is in effect. That is a property worth a test, because the failure
 * mode is a credential quietly appearing on screen (and in any screenshot of it).
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockConfigureTest extends JRockGuiFixture {

    private static final String FAKE_API_KEY = "fake-bedrock-key-not-a-real-credential";

    /**
     * An endpoint whose host does not resolve, naming a region that does not exist.
     * <p>
     * Applying the dialog re-runs the session report, and with a key set the
     * model-list fetch runs - so without this the test would send a
     * bogus credential to the real AWS endpoint. Instead it fails at DNS, and the
     * suite stays offline. There is no wildcard record under api.aws, so an
     * unknown region really is NXDOMAIN rather than something that resolves.
     */
    private static final String UNROUTABLE_ENDPOINT =
            "https://bedrock-mantle.moon-9.api.aws/v1/chat/completions";

    @Test
    @DisplayName("the audio devices are listed when the list is opened, once per dialog")
    void listsTheAudioDevicesWhenTheListIsOpenedAndNotBefore() throws Exception {
        awaitReadyCount(1);

        // Nothing has asked the sound system anything yet, and opening Configure does not
        // either: enumerating opens every mixer the machine has (488 ms cold here), and a
        // list nobody has asked to see is not worth that.
        assertThat((java.util.List<?>) field("outputDevices").get(null))
                .describedAs("the speakers listed before anybody asked").isEmpty();
        JOptionPaneFixture dialog = openConfigure();
        assertThat((java.util.List<?>) field("outputDevices").get(null))
                .describedAs("the speakers listed by opening Configure").isEmpty();
        press(dialog.cancelButton());

        // And the rule itself, with a stub where the sound system would be: what is under
        // test is WHEN it is asked and how often, not what it answers - a build machine
        // has whatever audio devices it has, and on Linux it has no such dropdown at all.
        int[] asked = { 0 };
        java.util.function.Supplier<java.util.List<String>> sound = () -> {
            asked[0]++;
            return java.util.List.of("Speaker one", "Speaker two");
        };
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
        java.lang.reflect.Method listOnOpen = JRock.class.getDeclaredMethod(
                "listOnOpen", javax.swing.JComboBox.class, java.util.function.Supplier.class);
        listOnOpen.setAccessible(true);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            box.setEditable(true);
            box.setSelectedItem("A name somebody typed");
            try {
                listOnOpen.invoke(null, box, sound);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        });
        assertThat(asked[0]).describedAs("asked before the list was opened").isZero();

        // Opening the popup is what the listener hears, so that is what is fired here.
        openTheList(box);
        assertThat(asked[0]).describedAs("asked once the list was opened").isEqualTo(1);
        assertThat(box.getItemCount()).isEqualTo(2);
        assertThat(box.getItemAt(0)).isEqualTo("Speaker one");
        assertThat(box.getSelectedItem())
                .describedAs("what was in the field before the list was made")
                .isEqualTo("A name somebody typed");

        // Opened again, and again: still one enumeration. The list stands until the
        // dialog is closed, which is what makes this cheap enough to do at all.
        openTheList(box);
        openTheList(box);
        assertThat(asked[0]).describedAs("asked after opening the list three times")
                .isEqualTo(1);
    }

    /** Fires what opening a combo box's popup fires, on the event thread. */
    private static void openTheList(javax.swing.JComboBox<String> box) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            for (javax.swing.event.PopupMenuListener listener : box.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(
                        new javax.swing.event.PopupMenuEvent(box));
            }
        });
    }

    @Test
    @DisplayName("the Configure dialog accepts an API key and never shows it back")
    void neverShowsBackTheApiKeyItWasGiven() throws Exception {
        awaitReadyCount(1);

        // Set directly rather than typed into the dialog's endpoint field: several of
        // the dialog's fields are JTextFields, and picking one out by type alone would
        // be ambiguous. What is under test here is the key field, not the endpoint.
        field("ENDPOINT").set(null, UNROUTABLE_ENDPOINT);

        JOptionPaneFixture dialog = openConfigure();
        // Blank on a first open too, not only after a key has been set.
        apiKeyField(dialog).requireEmpty();
        enterText(apiKeyField(dialog), FAKE_API_KEY);
        press(dialog.okButton());

        // Applying settings re-runs the whole session report, which ends in its own
        // "Ready." - so a second one is the evidence the app came back up. Counting is
        // what makes this meaningful: the first "Ready." is still in the pane.
        awaitReadyCount(2);

        JOptionPaneFixture reopened = openConfigure();
        apiKeyField(reopened).requireEmpty();
        press(reopened.cancelButton());

        // The key was taken, though - blank-on-reopen is write-only, not ignored.
        assertThat(field("apiKey").get(null))
                .describedAs("the API key in effect")
                .isEqualTo(FAKE_API_KEY);

        // And it was written where the next start reads it from, which is what makes
        // the dialog worth using at all: one line, the key, in the working folder.
        Path keyFile = workingDirectory().resolve("JRock").resolve("bedrock-key.txt");
        assertThat(keyFile).describedAs("JRock/bedrock-key.txt").exists();
        assertThat(new String(Files.readAllBytes(keyFile), StandardCharsets.UTF_8).trim())
                .describedAs("the key file's contents")
                .isEqualTo(FAKE_API_KEY);

        // And it never reached the transcript. The log is written to disk, so a key in
        // the pane would be a key in JRock/jrock-log.txt.
        assertThat(logPane().text())
                .describedAs("the log pane's text")
                .doesNotContain(FAKE_API_KEY);
    }

    /** The dialog's one JPasswordField: the Bedrock API key row. */
    private JTextComponentFixture apiKeyField(JOptionPaneFixture dialog) {
        return dialog.textBox(new GenericTypeMatcher<JPasswordField>(JPasswordField.class) {
            @Override
            protected boolean isMatching(JPasswordField field) {
                return true;
            }
        });
    }
}
