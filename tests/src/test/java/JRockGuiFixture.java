import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.text.JTextComponent;

import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.edt.GuiTask;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JButtonFixture;
import org.assertj.swing.fixture.JComboBoxFixture;
import org.assertj.swing.fixture.JFileChooserFixture;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.fixture.JTextComponentFixture;
import org.assertj.swing.timing.Condition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Starts the real application before each test, takes it down afterwards, and hands
 * the test its window.
 * <p>
 * Deliberately in the DEFAULT package: JRock is itself in the default package, and a
 * class there cannot be imported from a named one.
 * <p>
 * NOT named *Test, so Surefire doesn't try to run the base class itself.
 * <p>
 * Nothing is stubbed - this is the real application, doing what it really does. Only
 * two things are arranged: no Bedrock API key (set blank in the POM), and a throwaway
 * working directory, so the app cannot restore a log written by an earlier run.
 */
abstract class JRockGuiFixture {

    /** Generous: the app is starting a JVM's worth of Swing on a cold CI runner. */
    private static final long WINDOW_TIMEOUT_MS = 30_000;

    /**
     * Longer than JRock's own 30s model-list timeout, so a test still passes on a
     * machine where that fetch really is attempted rather than skipped.
     */
    private static final long READY_TIMEOUT_SECONDS = 90;

    /** How long to wait for a dialog JRock was just asked to open, or to close. */
    protected static final long DIALOG_TIMEOUT_MS = 30_000;

    /** How much of the log to quote when a wait for one of its lines times out. */
    private static final int LOG_TAIL_LINES = 30;

    /**
     * The mutable statics the application keeps its configuration in.
     * <p>
     * These matter because Surefire reuses one JVM for every test class, so a test
     * that changes the region or sets an API key override would otherwise hand that
     * on to whatever runs next - and the next test would start making network calls.
     * Saved before each test and put back after it.
     */
    private static final List<String> CONFIG_FIELDS = Arrays.asList(
            "workingDir", "promptsDir", "promptsDirNote", "apiKeyOverride", "REGION",
            "regionSource", "MODEL_ID", "availableModels", "pdfDpi", "saveIncludeCopies");

    private final Map<String, Object> savedConfig = new LinkedHashMap<>();

    protected Robot robot;
    protected FrameFixture window;
    private Path throwawayHome;

    @BeforeEach
    void startTheApplication() throws Exception {
        for (String name : CONFIG_FIELDS) {
            savedConfig.put(name, field(name).get(null));
        }

        // Give the app an empty directory of its own to keep its JRock/ folder in.
        //
        // This is about isolation, not tidiness: startup RESTORES a previous log from
        // disk, so a run that reused a directory would begin with the last run's
        // "Ready." already in the pane - and a test would then pass without the app
        // ever having written one. A fresh directory each time means the lines under
        // test can only have come from this run.
        throwawayHome = Files.createTempDirectory("jrock-test-home");
        field("workingDir").set(null, throwawayHome);

        robot = BasicRobot.robotWithCurrentAwtHierarchy();

        // The real entry point. main() shows the window on the EDT itself, so there
        // is nothing to arrange here - we just wait for the window to turn up.
        JRock.main(new String[0]);

        window = WindowFinder.findFrame(new GenericTypeMatcher<JFrame>(JFrame.class) {
            @Override
            protected boolean isMatching(JFrame frame) {
                // The title is "<working directory> - JRock", so match on the suffix
                // rather than on a folder name that depends on where tests run.
                return frame.getTitle() != null
                        && frame.getTitle().endsWith("JRock")
                        && frame.isShowing();
            }
        }).withTimeout(WINDOW_TIMEOUT_MS).using(robot);
    }

    @AfterEach
    void stopTheApplication() throws Exception {
        try {
            disposeWindow();
        } finally {
            // Last, and after the window is gone: a straggling EDT task that still
            // persists the log must write into the throwaway directory, not into the
            // real working directory this puts back.
            restoreConfiguration();
            forgetIncludes();
            deleteThrowawayHome();
        }
    }

    private void disposeWindow() {
        if (window != null) {
            final java.awt.Frame frame = window.target();
            // dispose(), NOT a window-closing event: the app sets EXIT_ON_CLOSE, and a
            // close event would call System.exit and take the test JVM with it. Skipping
            // this altogether is not an option either - a live window keeps AWT's
            // non-daemon threads up and the forked JVM would never exit.
            GuiActionRunner.execute(new GuiTask() {
                @Override
                protected void executeInEDT() {
                    frame.dispose();
                }
            });
            window = null;
        }
        if (robot != null) {
            // Releases the screen lock without disposing windows, which we just did
            // ourselves for the reason above.
            robot.cleanUpWithoutDisposingWindows();
            robot = null;
        }
    }

    private void restoreConfiguration() throws Exception {
        for (Map.Entry<String, Object> saved : savedConfig.entrySet()) {
            field(saved.getKey()).set(null, saved.getValue());
        }
        savedConfig.clear();
    }

    /**
     * Empties the hash -&gt; path map of included files.
     * <p>
     * Same reason as {@link #CONFIG_FIELDS}, but the map is final so it is cleared
     * rather than put back: entries left behind point into a throwaway directory
     * that is about to be deleted, and a later test asking "is this hash a known
     * include?" should not get yes from a previous test's answer.
     */
    @SuppressWarnings("unchecked")
    private void forgetIncludes() throws Exception {
        ((Map<String, Path>) field("INCLUDES").get(null)).clear();
    }

    /** Removes only the directory this fixture created itself, contents and all. */
    private void deleteThrowawayHome() throws IOException {
        if (throwawayHome == null || !Files.exists(throwawayHome)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(throwawayHome)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover file in the OS temp directory is not worth failing a
                    // passing test over.
                }
            });
        }
        throwawayHome = null;
    }

    /** Reads or writes one of JRock's private configuration statics. */
    protected static Field field(String name) throws Exception {
        Field f = JRock.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * The log pane is the only non-editable text component in the window; the prompt
     * below it is an editable JTextArea, so the type alone already separates them.
     * Scoped to the frame, so the Configure dialog's own text areas can't match.
     */
    protected JTextComponentFixture logPane() {
        return window.textBox(new GenericTypeMatcher<JTextPane>(JTextPane.class) {
            @Override
            protected boolean isMatching(JTextPane pane) {
                return !pane.isEditable();
            }
        });
    }

    /** The prompt: the window's one editable text area. */
    protected JTextComponentFixture promptArea() {
        return window.textBox(new GenericTypeMatcher<JTextArea>(JTextArea.class) {
            @Override
            protected boolean isMatching(JTextArea area) {
                return area.isEditable();
            }
        });
    }

    /** The throwaway directory the application is keeping its JRock/ folder in. */
    protected Path workingDirectory() {
        return throwawayHome;
    }

    /** Presses Configure and returns the dialog it opens. */
    protected JOptionPaneFixture openConfigure() {
        press(window.button(new GenericTypeMatcher<JButton>(JButton.class) {
            @Override
            protected boolean isMatching(JButton button) {
                return "Configure".equals(button.getText());
            }
        }));
        return JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
    }

    /**
     * Triggers one of JRock's Ctrl+&lt;key&gt; shortcuts on the main window.
     * <p>
     * The binding is what is under test: this is the same KeyStroke the toolkit builds
     * from that key press, looked up in the root pane's WHEN_IN_FOCUSED_WINDOW map, so a
     * shortcut that is renamed, unbound or registered at the wrong scope fails here -
     * loudly, and naming the shortcut. A keystroke typed with the Robot would need the
     * display server to consider the frame focused, which nothing arranges on a runner
     * with no window manager; see {@link #press}.
     * <p>
     * invokeLater rather than GuiActionRunner.execute, because these actions open modal
     * dialogs: the EDT would not come back until the dialog was dismissed, and
     * dismissing it is what the caller does next.
     */
    protected void pressCtrl(final int keyCode) {
        final JFrame frame = (JFrame) window.target();
        final KeyStroke shortcut = KeyStroke.getKeyStroke(keyCode, InputEvent.CTRL_DOWN_MASK);
        final Action action = GuiActionRunner.execute(new GuiQuery<Action>() {
            @Override
            protected Action executeInEDT() {
                JRootPane root = frame.getRootPane();
                Object name = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(shortcut);
                return (name == null) ? null : root.getActionMap().get(name);
            }
        });
        assertThat(action)
                .describedAs("the window-level action bound to " + shortcut)
                .isNotNull();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                action.actionPerformed(
                        new ActionEvent(frame, ActionEvent.ACTION_PERFORMED, null));
            }
        });
    }

    /**
     * Presses a button: what a click on it does, without the click.
     * <p>
     * Nothing in this suite uses the mouse or the keyboard. Both are delivered not by
     * Swing but by the operating system, to whichever window it believes is focused or
     * under the pointer: the headless runner has no window manager to make that JRock's,
     * a desktop has whatever its owner is working in, and injected input can be discarded
     * outright - which is indistinguishable from a button that does nothing.
     * {@code doClick} runs the button's own listeners, which is all a click is once it has
     * arrived; what it gives up is that the button is where a mouse could reach it.
     * <p>
     * invokeLater rather than GuiActionRunner.execute, for the reason given in
     * {@link #pressCtrl}: a button may close one modal dialog and open another, and an
     * invokeAndWait would not return until it was dealt with. Later EDT work still queues
     * behind the press, so a query about what it changed sees the change.
     */
    protected static void press(JButtonFixture button) {
        press(button.target());
    }

    /** As {@link #press(JButtonFixture)}, for a button the test found for itself. */
    protected static void press(final AbstractButton button) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                button.doClick(0);
            }
        });
    }

    /**
     * Puts text in a field, which is what a dialog reads when it is applied.
     * <p>
     * Set on the field's document rather than typed, for the reason given in
     * {@link #press}. Every listener on the field runs, this being the same change typing
     * makes.
     */
    protected static void enterText(JTextComponentFixture field, final String text) {
        final JTextComponent target = field.target();
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                target.setText(text);
            }
        });
    }

    /**
     * Chooses an item in a dropdown without opening it.
     * <p>
     * {@code setSelectedItem} is what a click on the item reaches, and what tells
     * everything listening that the choice changed; the popup it skips would have to be
     * rendered and hit-tested, which is what {@link #press} avoids for buttons. The item
     * is named as the model holds it rather than as it is rendered, so a dropdown of
     * Integers is chosen from with an Integer.
     */
    protected static void select(JComboBoxFixture combo, Object item) {
        select(combo.target(), item);
    }

    /** As {@link #select(JComboBoxFixture, Object)}, for a combo the test found itself. */
    protected static void select(final JComboBox<?> combo, final Object item) {
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                combo.setSelectedItem(item);
            }
        });
    }

    /**
     * Includes files through the real Ctrl+I dialog, under the chooser filter with the
     * given description, and waits until JRock reports it has finished with each one.
     * <p>
     * The filter decides everything that follows: same dialog, same files, but it is
     * what turns an include into a Ghostscript page-image conversion, or an RTF read as
     * Markdown, rather than an attempt to attach the file as it stands - so it is named,
     * per test, by the description the user reads in the dropdown.
     * <p>
     * Ctrl+I rather than the prompt's context menu: it is bound on the root pane as
     * WHEN_IN_FOCUSED_WINDOW and opens the very same chooser, without depending on a
     * popup being rendered and hit-tested on a virtual display. See {@link #pressCtrl}
     * for why the shortcut is fired through its binding rather than typed.
     * <p>
     * {@code timeoutSeconds} is per file, because how long a conversion takes is the
     * caller's business: rasterising A4 pages is not reading an RTF.
     */
    protected void includeThroughTheDialog(String filterDescription, long timeoutSeconds,
                                           Path... files) {
        chooseInTheIncludeDialog(filterDescription, files);
        // A conversion runs on a background worker, so approving the dialog returns
        // long before it has finished. There is no worker to join from here - the last
        // line it writes is the signal, and it is only written once the tokens are in
        // the prompt.
        for (Path file : files) {
            awaitLogLine("token(s) for " + file.getFileName(), timeoutSeconds);
        }
    }

    /**
     * The same, but without waiting for the include to report success: for a file the
     * conversion is expected to refuse, where the line to wait for is the refusal.
     */
    protected void chooseInTheIncludeDialog(String filterDescription, Path... files) {
        chooseInTheIncludeDialog(filterDescription, chooser -> { }, files);
    }

    /**
     * The same, with a look at the open dialog before it is approved: for the options
     * that live in the chooser itself rather than in Configure, which have to be set
     * while it is up and are read once it is dismissed.
     */
    protected void chooseInTheIncludeDialog(String filterDescription,
                                            java.util.function.Consumer<JFileChooserFixture> whileOpen,
                                            Path... files) {
        pressCtrl(KeyEvent.VK_I);

        JFileChooserFixture chooser =
                JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);

        // JFileChooserFixture exposes the file name box and the buttons, not the "Files
        // of Type" combo, so it is found in the chooser's own hierarchy - by what its
        // items are, rather than by a position in the dialog.
        final JComboBox<?> filters = robot.finder().find(chooser.target(),
                new GenericTypeMatcher<JComboBox>(JComboBox.class) {
                    @Override
                    protected boolean isMatching(JComboBox candidate) {
                        return candidate.getItemCount() > 0
                                && candidate.getItemAt(0) instanceof FileFilter;
                    }
                });
        final FileFilter filter = filterNamed(filters, filterDescription);
        assertThat(filter).describedAs("the \"" + filterDescription + "\" filter in the "
                + "\"Files of Type\" dropdown").isNotNull();

        // Choosing the filter is the whole difference between one kind of include and
        // another, so it is checked rather than assumed: the chooser has to be going by
        // the filter that was asked for before anything is approved under it.
        select(filters, filter);
        assertThat(currentFilterOf(chooser)).describedAs("the chooser's filter")
                .isSameAs(filter);

        whileOpen.accept(chooser);
        approveWith(chooser, files);
    }

    /**
     * The include dialog's own checkbox, found by the label the user reads.
     * <p>
     * It is JRock's accessory panel rather than anything the chooser provides, so it is
     * looked for in the dialog's hierarchy - and only there, so the window's own
     * checkboxes (Extend conversation) cannot match it.
     */
    protected JCheckBox checkBoxIn(JFileChooserFixture chooser, final String label) {
        return robot.finder().find(chooser.target(),
                new GenericTypeMatcher<JCheckBox>(JCheckBox.class) {
                    @Override
                    protected boolean isMatching(JCheckBox candidate) {
                        return label.equals(candidate.getText());
                    }
                });
    }

    /**
     * Puts a checkbox in the given state, leaving it alone if it is in it already.
     * <p>
     * Read and clicked in the one EDT task, because the answer to "is it ticked?"
     * decides whether to click it: a checkbox that remembers a session-wide setting
     * (see {@link #checkBoxIn}) comes up ticked the second time round, and a click
     * then would turn it off. Unlike {@link #press}, this one opens no dialog, so
     * there is nothing for an invokeAndWait to deadlock behind.
     */
    protected static void tick(final AbstractButton box, final boolean on) {
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                if (box.isSelected() != on) box.doClick(0);
            }
        });
    }

    /**
     * Invokes an item of the prompt's context menu by its label.
     * <p>
     * A popup menu belongs to no window until it is shown, and what shows it is the
     * prompt's own mouse listener - so the way to it is a popup-trigger event delivered
     * to the prompt, which is what a right-click is once the operating system has
     * decided where it went. Dispatched to the component rather than injected, and the
     * item pressed rather than clicked, for the reasons given in {@link #press}.
     * <p>
     * The menu is dismissed afterwards, so the next lookup can only find a menu this
     * opened.
     */
    protected void chooseInThePromptMenu(final String label) {
        rightClick(promptArea().target());
        pressMenuItem(label);
    }

    /**
     * Invokes an item of the window's own context menu - the one on the top bar, beside
     * the Configure button - by its label.
     * <p>
     * The same popup trigger as {@link #chooseInThePromptMenu}, aimed at the other
     * component that has a menu: the top bar, found as what the content pane lays out to
     * the north rather than by counting children.
     */
    protected void chooseInTheWindowMenu(final String label) {
        final JFrame frame = (JFrame) window.target();
        java.awt.Component topBar = GuiActionRunner.execute(new GuiQuery<java.awt.Component>() {
            @Override
            protected java.awt.Component executeInEDT() {
                java.awt.Container content = frame.getContentPane();
                return ((java.awt.BorderLayout) content.getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.NORTH);
            }
        });
        assertThat(topBar).describedAs("the window's top bar").isNotNull();
        rightClick(topBar);
        pressMenuItem(label);
    }

    /**
     * Delivers a popup-trigger press to a component, which is what a right-click is once
     * the operating system has decided where it went. Dispatched to the component rather
     * than injected, for the reasons given in {@link #press}.
     */
    private static void rightClick(final java.awt.Component comp) {
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                comp.dispatchEvent(new java.awt.event.MouseEvent(comp,
                        java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        0, 4, 4, 1, true));      // popupTrigger = true
            }
        });
    }

    /**
     * Presses the showing menu item with the given label, then dismisses the menu so the
     * next lookup can only find one that was opened after it.
     */
    private void pressMenuItem(final String label) {
        JMenuItem item = robot.finder().find(new GenericTypeMatcher<JMenuItem>(JMenuItem.class) {
            @Override
            protected boolean isMatching(JMenuItem candidate) {
                return label.equals(candidate.getText()) && candidate.isShowing();
            }
        });
        press(item);
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            }
        });
    }

    /**
     * The window's checkbox with the given label, which is how the ones in the bars are
     * told apart - there being nothing else to tell them apart by.
     */
    protected JCheckBox checkBox(final String label) {
        return robot.finder().find(window.target(),
                new GenericTypeMatcher<JCheckBox>(JCheckBox.class) {
                    @Override
                    protected boolean isMatching(JCheckBox candidate) {
                        return label.equals(candidate.getText());
                    }
                });
    }

    /**
     * Names the files in the chooser's file-name box and approves it, then waits until
     * the dialog has gone.
     * <p>
     * The file-name box, not {@code chooser.selectFiles(...)}: a JFileChooser reads its
     * directory on a thread of its own and applies the result on the EDT, and choosing a
     * filter starts a fresh read. When one lands it rebuilds the file list and clears the
     * list's selection, which Swing turns straight into {@code setSelectedFiles(null)} on
     * the chooser - so a selection set programmatically can be dropped, at a moment that
     * depends on how busy the machine is, leaving Approve with nothing to approve.
     * <p>
     * The box survives that, a chooser only ever writing a non-empty selection into it,
     * and it is what the Approve action reads: it resolves the names itself, makes them the
     * selection and approves, which is the same path as a user typing a file name.
     * Absolute paths, quoted and space-separated when there are several, which is the form
     * that action parses in multi-selection mode.
     * <p>
     * The name goes in on the EDT and Approve is pressed rather than clicked, for the
     * reasons given in {@link #enterText} and {@link #press}.
     */
    protected void approveWith(final JFileChooserFixture chooser, final Path... files) {
        enterText(chooser.fileNameTextBox(), fileNameBoxText(files));
        press(chooser.approveButton());
        // An Approve that was ignored leaves the dialog up, and saying so here names the
        // dialog - rather than leaving the caller to time out on whatever the include was
        // supposed to start.
        awaitChooserGone(chooser, "approved");
    }

    /** Cancels a chooser and waits until it has gone, so the next one can't find it. */
    protected void dismiss(final JFileChooserFixture chooser) {
        press(chooser.cancelButton());
        awaitChooserGone(chooser, "cancelled");
    }

    /**
     * What to put in the file-name box: one absolute path, or several quoted ones.
     * <p>
     * The quoted form is only understood in multi-selection mode, so a single file is
     * left bare - a lone chooser would take the quotes for part of the name.
     */
    private static String fileNameBoxText(Path... files) {
        if (files.length == 1) return files[0].toString();
        StringBuilder sb = new StringBuilder();
        for (Path file : files) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('"').append(file).append('"');
        }
        return sb.toString();
    }

    /** Waits until a chooser is off the screen, whichever button was pressed. */
    private void awaitChooserGone(final JFileChooserFixture chooser, final String what) {
        final javax.swing.JFileChooser target = chooser.target();
        String title = GuiActionRunner.execute(new GuiQuery<String>() {
            @Override
            protected String executeInEDT() {
                return target.getDialogTitle();
            }
        });
        pause(new Condition("the \"" + title + "\" chooser to close after being " + what) {
            @Override
            public boolean test() {
                return !GuiActionRunner.execute(new GuiQuery<Boolean>() {
                    @Override
                    protected Boolean executeInEDT() {
                        return target.isShowing();
                    }
                });
            }
        }, timeout(DIALOG_TIMEOUT_MS));
    }

    /** Waits until the log pane holds {@code needle} anywhere in its text. */
    protected void awaitLogLine(final String needle, long timeoutSeconds) {
        pause(new Condition("\"" + needle + "\" in the log") {
            @Override
            public boolean test() {
                return logPane().text().contains(needle);
            }

            /**
             * The log itself, in the timeout message: whatever happened instead, JRock
             * said so there - "Could not read RTF ...", "Ghostscript not found" - while
             * the line that never arrived is the one thing already known.
             */
            @Override
            protected String descriptionAddendum() {
                return ", which said:\n" + tailOfTheLog();
            }
        }, timeout(timeoutSeconds, TimeUnit.SECONDS));
    }

    /** The last few lines of the log, for a failure message. */
    private String tailOfTheLog() {
        List<String> lines = logLines();
        int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
        return String.join("\n", lines.subList(from, lines.size()));
    }

    /** The dropdown's filter with the given description, or null if it offers none. */
    private static FileFilter filterNamed(final JComboBox<?> filters, final String description) {
        return GuiActionRunner.execute(new GuiQuery<FileFilter>() {
            @Override
            protected FileFilter executeInEDT() {
                for (int i = 0; i < filters.getItemCount(); i++) {
                    Object item = filters.getItemAt(i);
                    if (item instanceof FileFilter
                            && description.equals(((FileFilter) item).getDescription())) {
                        return (FileFilter) item;
                    }
                }
                return null;
            }
        });
    }

    /** The filter the chooser is actually going by, which is what JRock reads. */
    private static FileFilter currentFilterOf(final JFileChooserFixture chooser) {
        return GuiActionRunner.execute(new GuiQuery<FileFilter>() {
            @Override
            protected FileFilter executeInEDT() {
                return chooser.target().getFileFilter();
            }
        });
    }

    /** How many times {@code needle} occurs in {@code text}. */
    protected static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * The log pane's text as lines. Split on \R, every line terminator, not on "\n":
     * what a Swing text component hands back is not guaranteed to use the separator
     * that went in.
     */
    protected List<String> logLines() {
        return Arrays.asList(logPane().text().split("\\R", -1));
    }

    /**
     * Waits until the log holds at least {@code expected} "Ready." lines.
     * <p>
     * "Ready." is logged from the done() of the model-list SwingWorker, so it arrives
     * some time after the window does - and again after every reconfigure, since that
     * re-runs the whole session report. Counting rather than merely looking for it is
     * what makes the second one evidence of the second report.
     */
    protected void awaitReadyCount(final int expected) {
        pause(new Condition(expected + "x \"Ready.\" in the log") {
            @Override
            public boolean test() {
                return readyCount() >= expected;
            }
        }, timeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /**
     * Waits until the session report for a newly opened working directory has finished.
     * <p>
     * Not {@link #awaitReadyCount}: opening another directory reloads the log from
     * <em>that</em> directory, which replaces everything in the pane, so there is no
     * earlier "Ready." left to count. The order within the report is fixed, though - the
     * pane is rebuilt first, its first line names the directory and its last is "Ready." -
     * so a "Ready." after that line belongs to this report.
     */
    protected void awaitSessionReportFor(final Path dir) {
        final String firstLine = "Working directory: " + dir;
        pause(new Condition("the session report for " + dir) {
            @Override
            public boolean test() {
                String log = logPane().text();
                int reportStart = log.indexOf(firstLine);
                return reportStart >= 0 && log.indexOf("Ready.", reportStart) >= 0;
            }

            @Override
            protected String descriptionAddendum() {
                return ", which said:\n" + tailOfTheLog();
            }
        }, timeout(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    /** How many lines of the log are exactly "Ready.". */
    protected int readyCount() {
        int found = 0;
        for (String line : logLines()) {
            if (line.equals("Ready.")) found++;
        }
        return found;
    }

    /** Index of the first line with the given prefix, or -1. */
    protected static int lineStartingWith(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }
}
