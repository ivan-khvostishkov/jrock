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
     * A region that does not exist, so JRock's "bedrock-mantle.&lt;region&gt;.api.aws"
     * host does not resolve.
     * <p>
     * Applying the dialog re-runs the session report, and with a key now set the
     * model-list fetch is no longer skipped - so without this the test would send a
     * bogus credential to the real AWS endpoint. Instead it fails at DNS, and the
     * suite stays offline. There is no wildcard record under api.aws, so an
     * unknown region really is NXDOMAIN rather than something that resolves.
     */
    private static final String UNROUTABLE_REGION = "moon-9";

    @Test
    @DisplayName("the Configure dialog accepts an API key and never shows it back")
    void neverShowsBackTheApiKeyItWasGiven() throws Exception {
        awaitReadyCount(1);

        // Set directly rather than typed into the dialog's region field: several of
        // the dialog's fields are JTextFields, and picking one out by type alone would
        // be ambiguous. What is under test here is the key field, not the region field.
        field("REGION").set(null, UNROUTABLE_REGION);

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
