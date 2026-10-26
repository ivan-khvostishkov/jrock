import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * The page's icon, drawn rather than drawn by hand.
 *
 * <pre>
 *     java .github/build/Favicon.java [jrock-web]
 * </pre>
 *
 * <p>THE SAME MARK THE WINDOW HAS. JRock's taskbar icon is a rounded teal tile with
 * "JR" on it, painted at five sizes by {@code makeIcon} in JRock.java; a browser tab
 * showing the same program should show the same thing, so this draws it the same way
 * from the same two values - {@code #0F8B8D} and the bold sans face the platform has.
 * There is no image file to keep in step with the code, which is the point: the icon is
 * a consequence of the brand colour rather than a copy of it.
 *
 * <p>AND IT STOPS A 404. A page with no icon declared is a page every browser asks
 * {@code /favicon.ico} for anyway, and a server with no such file answers 404 - which
 * lands in the console, and from there in the CI log of anything that reads the console
 * while driving this page (the Android check prints it as "Failed to load resource").
 * One file and one link element in the head remove both the request and the noise.
 *
 * <p>WHY .ICO AND NOT A PNG. One file carrying 16, 32 and 48 pixel versions lets the
 * browser pick, where a single PNG makes it scale - and the 16 pixel tile is drawn, not
 * shrunk, so its two letters stay legible. The payloads inside are PNGs, which is what
 * an .ico has been allowed to hold since Windows Vista and what every browser in use
 * reads; the alternative, a bottom-up DIB with an AND mask, is more code for an older
 * audience than this page has.
 */
public final class Favicon {

    /** JRock's brand teal: BRAND in JRock.java, --brand in index.html. */
    private static final Color BRAND = new Color(0x0F, 0x8B, 0x8D);

    /** What the tile says, as the window's own icon says it with no folder to name. */
    private static final String LABEL = "JR";

    /** The sizes the .ico carries, each drawn at its own size rather than scaled. */
    private static final int[] SIZES = { 16, 32, 48 };

    /** And one for a phone's home screen, which wants a plain PNG of its own. */
    private static final int TOUCH = 180;

    public static void main(String[] args) throws IOException {
        Path dir = Paths.get(args.length > 0 ? args[0] : "jrock-web");
        if (!Files.isDirectory(dir)) {
            System.out.println("no such folder: " + dir.toAbsolutePath());
            System.exit(1);
        }

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) pngs.add(png(tile(size)));

        Path ico = dir.resolve("favicon.ico");
        try (OutputStream out = Files.newOutputStream(ico)) {
            writeIco(out, pngs);
        }
        System.out.println("wrote " + ico + "  " + Files.size(ico) + " bytes, "
                + SIZES.length + " sizes (" + join(SIZES) + ")");

        Path touch = dir.resolve("apple-touch-icon.png");
        Files.write(touch, png(tile(TOUCH)));
        System.out.println("wrote " + touch + "  " + Files.size(touch) + " bytes, "
                + TOUCH + "x" + TOUCH);
    }

    // The tile itself, which is makeIcon from JRock.java: a rounded brand-coloured
    // square with the label shrunk until it fits 82% of the width.
    private static BufferedImage tile(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int arc = Math.max(3, size / 4);
        g.setColor(BRAND);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        int fontSize = size;
        Font font;
        FontMetrics fm;
        int textW;
        do {
            font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            fm = g.getFontMetrics(font);
            textW = fm.stringWidth(LABEL);
            fontSize--;
        } while (textW > size * 0.82 && fontSize > 5);
        g.setFont(font);
        g.drawString(LABEL, (size - textW) / 2,
                (size - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bytes);
        return bytes.toByteArray();
    }

    // ICONDIR, then one 16-byte ICONDIRENTRY per image, then the images. Everything in
    // the header is little-endian, and an offset counts from the start of the file - so
    // the first payload begins after all of the entries, whatever their number.
    private static void writeIco(OutputStream out, List<byte[]> images) throws IOException {
        int count = images.size();
        out.write(new byte[] { 0, 0, 1, 0 });        // reserved, then type 1 (an icon)
        writeShort(out, count);
        int offset = 6 + 16 * count;
        for (int i = 0; i < count; i++) {
            int size = SIZES[i];
            out.write(size >= 256 ? 0 : size);       // 0 means 256 in one byte
            out.write(size >= 256 ? 0 : size);
            out.write(0);                            // colours in the palette: not indexed
            out.write(0);                            // reserved
            writeShort(out, 1);                      // one colour plane
            writeShort(out, 32);                     // bits per pixel, with the alpha
            writeInt(out, images.get(i).length);
            writeInt(out, offset);
            offset += images.get(i).length;
        }
        for (byte[] image : images) out.write(image);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }

    private static String join(int[] sizes) {
        StringBuilder text = new StringBuilder();
        for (int size : sizes) {
            if (text.length() > 0) text.append(", ");
            text.append(size);
        }
        return text.toString();
    }

    private Favicon() {
    }
}
