import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

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

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JTextPane;

import org.assertj.swing.core.BasicRobot;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.Robot;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiTask;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
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
            "workingDir", "apiKeyOverride", "REGION", "regionSource", "MODEL_ID",
            "availableModels", "pdfDpi");

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
