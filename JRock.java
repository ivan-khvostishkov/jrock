// JRock
// Run directly with: java JRock.java
//
// Java version requirements:
//   Minimum: JDK 11  - single-file source launch (JEP 330) is required for
//                       `java JRock.java`. On JDK 8-10, compile first with javac.
//   Maximum: none    - no upper bound. Uses only core Swing/AWT APIs (JDK 1.2+),
//                       none deprecated or scheduled for removal. Runs on the
//                       latest JDK (as of September, 2026).

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

public class JRock {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(JRock::createAndShowGui);
    }

    private static void createAndShowGui() {
        JFrame frame = new JFrame("JRock");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 200);
        frame.setLocationRelativeTo(null); // center on screen

        JLabel label = new JLabel("Hello, World!", SwingConstants.CENTER);
        frame.add(label);

        frame.setVisible(true);
    }
}
