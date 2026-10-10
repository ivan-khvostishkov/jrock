import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.swing.timing.Pause.pause;
import static org.assertj.swing.timing.Timeout.timeout;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.edt.GuiQuery;
import org.assertj.swing.edt.GuiTask;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.timing.Condition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks that the window says what it keeps and what its controls do.
 * <p>
 * Two promises, and the first is the one that rots. JRock saves everything into a
 * {@code JRock/} folder of the working directory - the prompt, the transcript, every
 * message, the API key, the settings, the conversions - and the Help window lists the
 * whole of it, so an operator never has to guess what can be in there. A file added to
 * that folder and left out of the list is a file nobody is told about, which is what the
 * first test here is for: it finds the folder's contents from the application itself and
 * holds the Help window to them.
 * <p>
 * The second is that every button and checkbox explains itself on a right-click, for the
 * phone that has no tooltip to hover over - and that asking does not also press it.
 *
 * @see JRockGuiFixture for how the application is started and stopped
 */
class JRockExplainsTest extends JRockGuiFixture {

    /** The first words of the folder list, which is how it is found on screen. */
    private static final String FOLDER_LIST_OPENING = "Everything JRock keeps";

    /** The two PDF subfolders, one per engine, which are named by their engine's tag. */
    private static final String[] PDF_SUBFOLDERS = { "gs-pdf", "pdfjs-pdf" };

    @Test
    @DisplayName("the Help window names every file and folder JRock/ can hold")
    void namesEveryFileTheJRockFolderCanHold() throws Exception {
        awaitReadyCount(1);

        JOptionPaneFixture configure = openConfigure();
        press(helpButton());
        JTextArea folderList = awaitShowing(FOLDER_LIST_OPENING);
        String help = allTextIn(windowOf(folderList));

        for (String name : everythingUnderTheJRockFolder()) {
            assertThat(help)
                    .describedAs("JRock/" + name + ", named in the Help window")
                    .contains(name);
        }

        dispose(windowOf(folderList));
        press(configure.cancelButton());
    }

    @Test
    @DisplayName("right-clicking Clock explains it, and does not tick it")
    void explainsClockWithoutTickingIt() throws Exception {
        awaitReadyCount(1);

        JCheckBox clock = checkBox("Clock");
        boolean before = selectionOf(clock);
        rightClick(clock);

        // The one thing about Clock worth a sentence of its own: the time it sends is
        // the model's, and the date the log prints is not.
        JTextArea explanation = awaitShowing("A model has no clock");
        assertThat(explanation.getText())
                .describedAs("what the Clock checkbox says about itself")
                .contains("time zone go to the model")
                .contains("never sent");
        dismissMenus();

        assertThat(selectionOf(clock))
                .describedAs("Clock, after being asked what it does")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the JRock/ label beside Configure opens the window's menu")
    void theStatusLabelOpensTheWindowMenu() throws Exception {
        awaitReadyCount(1);

        leftClick(statusLabel());
        JMenuItem item = robot.finder().find(new GenericTypeMatcher<JMenuItem>(JMenuItem.class) {
            @Override
            protected boolean isMatching(JMenuItem candidate) {
                return "What is in the JRock folder...".equals(candidate.getText())
                        && candidate.isShowing();
            }
        });
        assertThat(item).describedAs("the folder item in the label's menu").isNotNull();
        dismissMenus();
    }

    /**
     * Every name the application can put inside its {@code JRock/} folder, asked of the
     * application rather than listed here.
     * <p>
     * JRock resolves each of its files and subfolders in a no-argument method of its own
     * ({@code logsDir()}, {@code wavDir()}, and so on), so reflecting over those and
     * keeping the ones under {@code JRock/} is the folder's contents as the code itself
     * defines them. A file added later comes with such a method, and so comes with a
     * failing test until the Help window names it too.
     */
    private static Set<String> everythingUnderTheJRockFolder() throws Exception {
        Path folder = (Path) invoke("jrockDir");
        Set<String> names = new LinkedHashSet<>();
        for (Method m : JRock.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0 || m.getReturnType() != Path.class) continue;
            m.setAccessible(true);
            Path resolved = (Path) m.invoke(null);
            if (resolved == null || resolved.equals(folder) || !resolved.startsWith(folder)) {
                continue;
            }
            names.add(resolved.getFileName().toString());
        }
        // The PDF page images go in one subfolder per engine, which takes the engine as
        // an argument and so is not among the methods above.
        for (String subfolder : PDF_SUBFOLDERS) names.add(subfolder);
        assertThat(names).describedAs("what JRock/ can hold").isNotEmpty();
        return names;
    }

    private static Object invoke(String method) throws Exception {
        Method m = JRock.class.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(null);
    }

    /** The Help button on the Configure dialog's first line. */
    private JButton helpButton() {
        return robot.finder().find(new GenericTypeMatcher<JButton>(JButton.class) {
            @Override
            protected boolean isMatching(JButton button) {
                return "Help".equals(button.getText()) && button.isShowing();
            }
        });
    }

    /** The status label beside Configure, which the window's menu hangs from. */
    private JLabel statusLabel() {
        return robot.finder().find(window.target(),
                new GenericTypeMatcher<JLabel>(JLabel.class) {
                    @Override
                    protected boolean isMatching(JLabel label) {
                        return "JRock/".equals(label.getText());
                    }
                });
    }

    /** Waits for a showing text area whose text starts with the given words. */
    private JTextArea awaitShowing(final String opening) {
        final JTextArea[] found = new JTextArea[1];
        pause(new Condition("a showing text saying \"" + opening + "...\"") {
            @Override
            public boolean test() {
                found[0] = GuiActionRunner.execute(new GuiQuery<JTextArea>() {
                    @Override
                    protected JTextArea executeInEDT() {
                        for (Window w : Window.getWindows()) {
                            if (!w.isShowing()) continue;
                            JTextArea hit = textStartingWith(w, opening);
                            if (hit != null) return hit;
                        }
                        return null;
                    }
                });
                return found[0] != null;
            }
        }, timeout(DIALOG_TIMEOUT_MS));
        return found[0];
    }

    private static JTextArea textStartingWith(Container root, String opening) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextArea && ((JTextArea) c).getText().startsWith(opening)) {
                return (JTextArea) c;
            }
            if (c instanceof Container) {
                JTextArea hit = textStartingWith((Container) c, opening);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Everything a window has to say, labels and prose alike, in one string. */
    private static String allTextIn(final Window window) {
        return GuiActionRunner.execute(new GuiQuery<String>() {
            @Override
            protected String executeInEDT() {
                StringBuilder text = new StringBuilder();
                collect(window, text);
                return text.toString();
            }
        });
    }

    private static void collect(Container root, StringBuilder into) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTextComponent) into.append(((JTextComponent) c).getText());
            if (c instanceof JLabel) into.append(((JLabel) c).getText());
            if (c instanceof Container) collect((Container) c, into);
            into.append('\n');
        }
    }

    private static Window windowOf(final Component comp) {
        return GuiActionRunner.execute(new GuiQuery<Window>() {
            @Override
            protected Window executeInEDT() {
                return SwingUtilities.getWindowAncestor(comp);
            }
        });
    }

    /**
     * Closes a window the test opened, without hunting for its button: the Help window is
     * a message dialog whose OK button looks exactly like the Configure dialog's, and both
     * are showing at once.
     */
    private static void dispose(final Window window) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                window.dispose();
            }
        });
    }

    /** A plain press, which is what a tap is - and what the status label opens on. */
    private static void leftClick(final Component comp) {
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                comp.dispatchEvent(new java.awt.event.MouseEvent(comp,
                        java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        0, 4, 4, 1, false));      // popupTrigger = false
            }
        });
    }

    private static void dismissMenus() {
        GuiActionRunner.execute(new GuiTask() {
            @Override
            protected void executeInEDT() {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            }
        });
    }

    private static boolean selectionOf(final JCheckBox box) {
        return GuiActionRunner.execute(new GuiQuery<Boolean>() {
            @Override
            protected Boolean executeInEDT() {
                return box.isSelected();
            }
        });
    }
}
