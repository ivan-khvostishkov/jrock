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

import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;

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

    /** How long to wait for a dialog JRock was just asked to open. */
    protected static final long DIALOG_TIMEOUT_MS = 30_000;

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
            "regionSource", "MODEL_ID", "availableModels", "pdfDpi");

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

    /** Clicks Configure and returns the dialog it opens. */
    protected JOptionPaneFixture openConfigure() {
        window.button(new GenericTypeMatcher<JButton>(JButton.class) {
            @Override
            protected boolean isMatching(JButton button) {
                return "Configure".equals(button.getText());
            }
        }).click();
        return JOptionPaneFinder.findOptionPane().withTimeout(DIALOG_TIMEOUT_MS).using(robot);
    }

    /**
     * Triggers one of JRock's Ctrl+&lt;key&gt; shortcuts on the main window.
     * <p>
     * Looked up in the root pane's WHEN_IN_FOCUSED_WINDOW input map and run, rather
     * than typed on the keyboard with the Robot. A real keystroke has to be delivered
     * by the display server to whichever window it thinks is focused, and the headless
     * runner is an X server with <em>no window manager</em> - so nothing gives the frame
     * its focus back after a modal dialog closes. The keystroke then simply vanishes:
     * no chooser opens, and not even a stray character arrives in the prompt. Mouse
     * clicks need no focus, which is why every other interaction here works.
     * <p>
     * The binding is still what is under test. This is the same KeyStroke the toolkit
     * would build from that key press, looked up in the same map, so a shortcut that
     * was renamed, unbound or registered at the wrong scope fails here - loudly, and
     * naming the shortcut - instead of quietly doing nothing. What is no longer covered
     * is the trip through X, which this environment cannot do for a frame anyway.
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
        pressCtrl(KeyEvent.VK_I);

        JFileChooserFixture chooser =
                JFileChooserFinder.findFileChooser().withTimeout(DIALOG_TIMEOUT_MS).using(robot);

        // JFileChooserFixture exposes the file name box and the buttons, not the "Files
        // of Type" combo, so it is found in the chooser's own hierarchy - by what its
        // items are, rather than by a position in the dialog.
        JComboBox<?> filters = robot.finder().find(chooser.target(),
                new GenericTypeMatcher<JComboBox>(JComboBox.class) {
                    @Override
                    protected boolean isMatching(JComboBox candidate) {
                        return candidate.getItemCount() > 0
                                && candidate.getItemAt(0) instanceof FileFilter;
                    }
                });
        int index = indexOfFilter(filters, filterDescription);
        assertThat(index).describedAs("the \"" + filterDescription + "\" filter")
                .isNotNegative();
        new JComboBoxFixture(robot, filters).selectItem(index);

        // selectFiles, not selectFile: the chooser is in multi-selection mode, and JRock
        // reads getSelectedFiles() - which setSelectedFile alone leaves empty.
        java.io.File[] chosen = new java.io.File[files.length];
        for (int i = 0; i < files.length; i++) chosen[i] = files[i].toFile();
        chooser.selectFiles(chosen);
        chooser.approve();
    }

    /** Waits until the log pane holds {@code needle} anywhere in its text. */
    protected void awaitLogLine(final String needle, long timeoutSeconds) {
        pause(new Condition("\"" + needle + "\" in the log") {
            @Override
            public boolean test() {
                return logPane().text().contains(needle);
            }
        }, timeout(timeoutSeconds, TimeUnit.SECONDS));
    }

    /** Index of the chooser's filter with the given description, or -1. */
    private static int indexOfFilter(final JComboBox<?> filters, final String description) {
        return GuiActionRunner.execute(new GuiQuery<Integer>() {
            @Override
            protected Integer executeInEDT() {
                for (int i = 0; i < filters.getItemCount(); i++) {
                    Object item = filters.getItemAt(i);
                    if (item instanceof FileFilter
                            && description.equals(((FileFilter) item).getDescription())) {
                        return i;
                    }
                }
                return -1;
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
