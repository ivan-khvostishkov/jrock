import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

/**
 * Whether JRock appeared on a phone's screen, decided from two screenshots of it.
 *
 * <pre>
 *     java CanvasAppeared.java before.png after.png [percent]
 * </pre>
 *
 * <p>WHEN A PICTURE AND NOT THE PROTOCOL. The Android job prefers to ask Chrome itself,
 * over the DevTools protocol, whether the page has a canvas of a sensible size - the
 * same question the desktop browser is asked, and the one with a real answer in it. On
 * an emulator it sometimes cannot ask: a Play-store image is a {@code user} build with
 * {@code ro.debuggable=0} and a release Chrome whose {@code flags=0x0}, and on such a
 * device {@code localabstract:chrome_devtools_remote} has more than once not existed at
 * all, with Chrome running and its window in front. This is what the job falls back to
 * on those runs, and only on those: where the socket answers, its answer is the one that
 * counts.
 *
 * <p>The fallback is made out of what the job already has. Two screenshots: one taken
 * seconds after the address is opened, one taken after the wait. If CheerpJ started a JVM
 * and Swing painted a window, the middle of the screen is unrecognisable between them -
 * the page's dark panel is replaced by JRock. If the page never came up, the middle of
 * the screen is the same dark panel in both, and the only thing that moved is a line or
 * two of the page's own log at the bottom.
 *
 * <p>Which is why only the MIDDLE BAND is compared, from a fifth of the way down to four
 * fifths: that is where the window goes, and it leaves out the page's header and footer,
 * the status bar and the navigation bar, all of which change on their own and would
 * answer the question with yes whatever happened.
 *
 * <p>The two states are so far apart - a near-black panel against a light Swing window -
 * that the threshold is not a tuned number: a page that came up changes almost every
 * pixel in the band, and a page that did not changes almost none. The default asks for a
 * quarter of them.
 */
public final class CanvasAppeared {

    /** How much of the height to look at: the band where the window is drawn. */
    private static final double FROM = 0.20, TO = 0.80;

    /** How different one pixel has to be, summed over the three channels, to count. */
    private static final int DIFFERENT = 48;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("usage: CanvasAppeared before.png after.png [percent]");
            System.exit(2);
        }
        double wanted = args.length > 2 ? Double.parseDouble(args[2]) : 25.0;
        BufferedImage before = ImageIO.read(new File(args[0]));
        BufferedImage after = ImageIO.read(new File(args[1]));
        if (before == null || after == null) {
            System.out.println("one of these is not a picture this can read");
            System.exit(1);
        }
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            System.out.println("the two pictures are different sizes ("
                    + before.getWidth() + "x" + before.getHeight() + " and "
                    + after.getWidth() + "x" + after.getHeight()
                    + "), so the screen changed shape rather than contents");
            System.exit(1);
        }

        int top = (int) (before.getHeight() * FROM);
        int bottom = (int) (before.getHeight() * TO);
        long looked = 0, changed = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = 0; x < before.getWidth(); x++) {
                int one = before.getRGB(x, y), two = after.getRGB(x, y);
                int apart = Math.abs(((one >> 16) & 0xff) - ((two >> 16) & 0xff))
                        + Math.abs(((one >> 8) & 0xff) - ((two >> 8) & 0xff))
                        + Math.abs((one & 0xff) - (two & 0xff));
                if (apart >= DIFFERENT) changed++;
                looked++;
            }
        }
        double percent = looked == 0 ? 0 : 100.0 * changed / looked;
        System.out.printf("the middle of the screen changed by %.1f%% (%d of %d pixels,"
                + " rows %d to %d); %.1f%% is what this asks for%n",
                percent, changed, looked, top, bottom, wanted);
        if (percent >= wanted) {
            System.out.println("JRock came up on the phone");
            return;
        }
        System.out.println("::error::nothing came up on the phone: the screen where the "
                + "window goes is the same in both pictures");
        System.exit(1);
    }

    private CanvasAppeared() {
    }
}
