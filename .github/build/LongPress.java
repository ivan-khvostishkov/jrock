import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;

/**
 * One press and hold, at a fraction of the screen, for as long as asked.
 *
 * <pre>
 *     java .github/build/LongPress.java 0.5 0.55 900
 * </pre>
 *
 * <p>WHAT IT IS FOR. JRock's menus open on a LONG PRESS where there is no right button to
 * click - a phone, or a tablet - and that behaviour has a picture of its own in the
 * README. On a real phone a finger does it; in the iOS Simulator the device screen is a
 * macOS window and the finger is the host's mouse, pressed and held over it. The device's
 * own screen is then read back with {@code simctl io screenshot}, so what comes out is
 * the phone's screen with the menu on it and none of the Simulator's chrome.
 *
 * <p>FRACTIONS AND NOT PIXELS, because the one thing this cannot know is where the
 * Simulator put its window. The middle of the screen is the middle of the device screen
 * closely enough to land inside JRock's log, which is what the menu is wanted on; a
 * fraction keeps that true whatever the runner's resolution is.
 *
 * <p>Android needs none of this: {@code adb shell input swipe x y x y 900} is a long
 * press, delivered to the device rather than to a window on somebody's desktop.
 */
public final class LongPress {

    public static void main(String[] args) throws Exception {
        double acrossFraction = args.length > 0 ? Double.parseDouble(args[0]) : 0.5;
        double downFraction = args.length > 1 ? Double.parseDouble(args[1]) : 0.5;
        long held = args.length > 2 ? Long.parseLong(args[2]) : 900;

        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int x = (int) Math.round(screen.width * acrossFraction);
        int y = (int) Math.round(screen.height * downFraction);

        Robot robot = new Robot();
        robot.setAutoDelay(40);
        robot.mouseMove(x, y);
        Thread.sleep(300);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(held);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        System.out.println("pressed and held " + held + " ms at " + x + "," + y
                + " of " + screen.width + "x" + screen.height);
        System.exit(0);
    }

    private LongPress() {
    }
}
