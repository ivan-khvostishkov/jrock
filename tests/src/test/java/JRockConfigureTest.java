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

    @Test
    @DisplayName("request-extra$ goes into every request body, and survives a save that follows")
    void putsTheExtraRequestFieldsIntoTheBodyAndKeepsThem() throws Exception {
        awaitReadyCount(1);

        // A field only the endpoint knows the meaning of, which is the whole point of the
        // setting: JRock has no row for it, reads nothing out of it, and puts it in as it
        // stands. Two of them, one nested, because that is the shape these arrive in.
        String extra = "{\"modalities\":[\"text\",\"image\"],"
                + "\"extra_body\":{\"vendor\":{\"some_config\":{\"flag\":false}}}}";
        field("requestExtra").set(null, extra);

        // 1. In the body, beside model and messages - and the body is built by the one
        //    method the real request and the log's copy both use.
        java.lang.reflect.Method chatRequestBody =
                JRock.class.getDeclaredMethod("chatRequestBody", CharSequence.class);
        chatRequestBody.setAccessible(true);
        assertThat((String) chatRequestBody.invoke(null, "[{\"role\":\"user\"}]"))
                .describedAs("the request body")
                .contains("\"modalities\":[\"text\",\"image\"]")
                .contains("\"extra_body\":{\"vendor\":{\"some_config\":{\"flag\":false}}}")
                .contains("\"messages\":[{\"role\":\"user\"}]");

        // 2. AND IT SURVIVES THE NEXT SAVE. No dialog sets this one, so nothing else
        //    would stop a line JRock knows nothing about from being dropped the next time
        //    any other setting is written - which happens on its own, when the window is
        //    moved (see rememberBoundsAsTheyChange).
        java.lang.reflect.Method save = JRock.class.getDeclaredMethod("saveConfigQuietly");
        save.setAccessible(true);
        save.invoke(null);
        Path config = workingDirectory().resolve("JRock").resolve("jrock-config.txt");
        assertThat(new String(Files.readAllBytes(config), StandardCharsets.UTF_8))
                .describedAs("JRock/jrock-config.txt after a save")
                .contains("request-extra$\n" + extra);

        // 3. A value that is not one balanced JSON object - a paste that lost its last
        //    brace - is reported and ignored, rather than failing every send on a request
        //    nobody can read.
        java.lang.reflect.Method problem =
                JRock.class.getDeclaredMethod("requestExtraProblem");
        problem.setAccessible(true);
        field("requestExtra").set(null, "{\"modalities\":[\"text\"");
        assertThat((String) problem.invoke(null))
                .describedAs("what is wrong with a truncated value")
                .contains("not one balanced JSON object");
        assertThat((String) chatRequestBody.invoke(null, "[]"))
                .describedAs("the body built from a broken value")
                .isEqualTo("{\"model\":\"" + field("MODEL_ID").get(null) + "\",\"messages\":[]}");

        // And a brace inside a string is text, not structure: this one is balanced.
        field("requestExtra").set(null, "{\"note\":\"}\"}");
        assertThat((String) problem.invoke(null))
                .describedAs("a brace inside a string").isNull();

        field("requestExtra").set(null, "");   // left as it was found
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
