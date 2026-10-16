import static org.assertj.core.api.Assertions.assertThat;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.edt.GuiTask;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Model row in Configure: a free-text field over a list of every model the account
 * can reach, which narrows itself to what is being typed.
 * <p>
 * The mechanics under test are a sequence, so they are tested as one: a model selected
 * offers the whole list, typing narrows it and opens it, a whole name widens it again and
 * closes it, erasing the field widens it, and something no model holds leaves the list
 * alone rather than opening an empty dropdown over a half-typed id.
 * <p>
 * Typing is done the way the rest of this suite does it - the editor's text set, then the
 * key listeners told a key was released - because the suite injects no real keyboard
 * events. That the filter reads KEY RELEASES rather than the document is itself the point
 * of one of these steps: with the dropdown open, Up and Down write the highlighted item
 * into the editor, and a filter that could not tell that from typing would shut the
 * dropdown on the first arrow key.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockModelFilterTest extends JRockGuiFixture {

    /** A list with two vendors in it, two models sharing a prefix, and one "sonnet". */
    private static final List<String> MODELS = Arrays.asList(
            "xai.grok-4.3", "xai.grok-4.3-mini", "anthropic.claude-opus-5",
            "anthropic.claude-sonnet-5-5", "anthropic.claude-haiku-5-5", "openai.gpt-5");

    @Test
    @DisplayName("typing narrows the model list and opens it; a whole name widens it and closes it")
    void narrowsTheModelListToWhatIsBeingTyped() throws Exception {
        awaitReadyCount(1);

        // The list a successful /v1/models call leaves behind, without the call: what is
        // under test is the row, not the fetch (the fixture puts this static back).
        field("availableModels").set(null, new ArrayList<>(MODELS));
        JOptionPaneFixture dialog = openConfigure();
        try {
            JComboBox<String> box = modelBox();

            // A model is selected and the field holds its whole name, so the arrow has
            // the full list behind it and nothing is open.
            assertThat(items(box)).describedAs("the list a selected model leaves behind")
                    .isEqualTo(MODELS);
            assertThat(popupOpen(box)).describedAs("the dropdown before anybody types")
                    .isFalse();

            // Typing - any part of a name, which is how a model id is remembered.
            type(box, "sonnet");
            assertThat(items(box)).describedAs("the list after typing \"sonnet\"")
                    .containsExactly("anthropic.claude-sonnet-5-5");
            assertThat(popupOpen(box)).describedAs("the dropdown after typing")
                    .isTrue();
            assertThat(text(box)).describedAs("the typing, left as it was typed")
                    .isEqualTo("sonnet");

            // Two vendors' worth of match: still a filter, not the whole list.
            type(box, "grok");
            assertThat(items(box)).describedAs("the list after typing \"grok\"")
                    .containsExactly("xai.grok-4.3", "xai.grok-4.3-mini");

            // The whole name, typed out: there is nothing left to choose, so the list
            // goes back to all of them for the next click on the arrow.
            type(box, "anthropic.claude-haiku-5-5");
            assertThat(items(box)).describedAs("the list once the name is whole")
                    .isEqualTo(MODELS);
            assertThat(popupOpen(box)).describedAs("the dropdown once the name is whole")
                    .isFalse();

            // Erased: all of them again, and still nothing opened by itself.
            type(box, "");
            assertThat(items(box)).describedAs("the list with the field empty")
                    .isEqualTo(MODELS);

            // A model id this machine's list has never heard of, which is allowed - the
            // field is free text. The list is left alone and no empty dropdown is opened
            // over the name somebody is halfway through typing.
            type(box, "bedrock.something-of-my-own");
            assertThat(items(box)).describedAs("the list after typing what nothing holds")
                    .isEqualTo(MODELS);
            assertThat(popupOpen(box)).describedAs("the dropdown after typing what nothing holds")
                    .isFalse();
            assertThat(text(box)).describedAs("the free text, untouched")
                    .isEqualTo("bedrock.something-of-my-own");
        } finally {
            press(dialog.cancelButton());
        }
    }

    @Test
    @DisplayName("picking from the narrowed list fills the field and puts the whole list back")
    void pickingFromTheNarrowedListWidensItAgain() throws Exception {
        awaitReadyCount(1);
        field("availableModels").set(null, new ArrayList<>(MODELS));
        JOptionPaneFixture dialog = openConfigure();
        try {
            JComboBox<String> box = modelBox();
            type(box, "opus");
            assertThat(items(box)).describedAs("the narrowed list")
                    .containsExactly("anthropic.claude-opus-5");

            // What a click on the one row left does, which is to select it.
            pick(box, "anthropic.claude-opus-5");

            assertThat(text(box)).describedAs("the field after the pick")
                    .isEqualTo("anthropic.claude-opus-5");
            assertThat(items(box)).describedAs("the list after the pick")
                    .isEqualTo(MODELS);
            assertThat(popupOpen(box)).describedAs("the dropdown after the pick").isFalse();
        } finally {
            press(dialog.cancelButton());
        }
    }

    // ---- the row, driven and read on the EDT ----------------------------------
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private JComboBox<String> modelBox() {
        return robot.finder().find(new GenericTypeMatcher<JComboBox>(JComboBox.class) {
            @Override protected boolean isMatching(JComboBox candidate) {
                return "model".equals(candidate.getName());
            }
        });
    }

    /**
     * Types text into the row: the editor's text set, then a key release, which is what
     * the filter listens for. The character is immaterial - what the filter reads is the
     * text that is there by the time the key comes up - but the KEY CODE is not: an arrow
     * or an Enter is choosing rather than typing, and is skipped.
     */
    private void type(final JComboBox<String> box, final String typed) {
        GuiActionRunner.execute(new GuiTask() {
            @Override protected void executeInEDT() {
                JTextComponent editor = editorOf(box);
                editor.setText(typed);
                editor.setCaretPosition(typed.length());
                KeyEvent released = new KeyEvent(editor, KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED,
                        typed.isEmpty() ? '\b' : typed.charAt(typed.length() - 1));
                for (KeyListener listener : editor.getKeyListeners()) {
                    listener.keyReleased(released);
                }
            }
        });
        settle();
    }

    /** What a click on a row of the dropdown does. */
    private void pick(final JComboBox<String> box, final String item) {
        GuiActionRunner.execute(new GuiTask() {
            @Override protected void executeInEDT() {
                box.setSelectedItem(item);
            }
        });
        settle();
    }

    /**
     * Lets the queue drain: the filter does its work at the END of the event queue, a
     * document may not be changed inside the notification that it changed.
     */
    private void settle() {
        for (int i = 0; i < 3; i++) {
            GuiActionRunner.execute(new GuiTask() {
                @Override protected void executeInEDT() { }
            });
        }
    }

    private List<String> items(final JComboBox<String> box) {
        return GuiActionRunner.execute(new GuiQuery<List<String>>() {
            @Override protected List<String> executeInEDT() {
                List<String> found = new ArrayList<>();
                for (int i = 0; i < box.getItemCount(); i++) found.add(box.getItemAt(i));
                return found;
            }
        });
    }

    private String text(final JComboBox<String> box) {
        return GuiActionRunner.execute(new GuiQuery<String>() {
            @Override protected String executeInEDT() {
                return editorOf(box).getText();
            }
        });
    }

    private Boolean popupOpen(final JComboBox<String> box) {
        return GuiActionRunner.execute(new GuiQuery<Boolean>() {
            @Override protected Boolean executeInEDT() {
                return box.isPopupVisible();
            }
        });
    }

    private static JTextComponent editorOf(JComboBox<String> box) {
        return (JTextComponent) box.getEditor().getEditorComponent();
    }
}
