import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@code JRock.ImageHeader}, which reads image dimensions out of a file header.
 * <p>
 * No GUI, and deliberately no {@link ImageIO} in the code under test: in the browser
 * (CheerpJ) {@code ImageIO.read} on a JPEG fails with {@code UnsatisfiedLinkError: no
 * lcms in java.library.path}, because the JDK's colour management is native and a
 * browser JVM cannot load it. Reading the header is arithmetic, so it behaves the
 * same everywhere - and these tests are what says so.
 * <p>
 * ImageIO is used HERE, in the test, as the independent opinion: for the formats the
 * JDK can write, the parser is checked against what a real encoder produced.
 */
class JRockImageSizeTest {

    /** Deliberately not square, so a width/height swap cannot pass. */
    private static final int WIDTH = 37;
    private static final int HEIGHT = 19;

    @TempDir
    Path dir;

    @Test
    @DisplayName("PNG, GIF and JPEG sizes match what the encoder wrote")
    void readsTheFormatsTheJdkCanWrite() throws Exception {
        for (String format : new String[] { "png", "gif", "jpg" }) {
            Path file = write(format, WIDTH, HEIGHT);
            assertThat(imageSize(file)).describedAs(format).containsExactly(WIDTH, HEIGHT);

            // And the same answer a decoder gives, on the same bytes.
            BufferedImage decoded = ImageIO.read(file.toFile());
            assertThat(imageSize(file))
                    .describedAs(format + " agrees with ImageIO")
                    .containsExactly(decoded.getWidth(), decoded.getHeight());
        }
    }

    @Test
    @DisplayName("a JPEG's size is found behind a large metadata segment")
    void walksPastMetadataToTheFrameHeader() throws Exception {
        // The reason the JPEG path walks segments instead of reading a fixed offset:
        // a camera's EXIF block, or an embedded colour profile, sits between the
        // signature and the frame header, and can be tens of kilobytes.
        byte[] jpeg = Files.readAllBytes(write("jpg", WIDTH, HEIGHT));
        byte[] padded = withApp1Segment(jpeg, 60_000);
        Path file = dir.resolve("with-exif.jpg");
        Files.write(file, padded);

        assertThat(padded.length).isGreaterThan(jpeg.length + 60_000 - 1);
        assertThat(imageSize(file)).containsExactly(WIDTH, HEIGHT);
        // Still a readable JPEG, i.e. the segment was inserted legally.
        assertThat(ImageIO.read(file.toFile())).isNotNull();
    }

    @Test
    @DisplayName("all three WEBP encodings are read")
    void readsWebp() throws Exception {
        // Hand-built, because the JDK writes no WEBP: each encoding packs the canvas
        // size its own way, and the packing is the part worth testing.
        assertThat(imageSize(file("lossy.webp", lossyWebp(WIDTH, HEIGHT))))
                .describedAs("VP8 (lossy)").containsExactly(WIDTH, HEIGHT);
        assertThat(imageSize(file("lossless.webp", losslessWebp(WIDTH, HEIGHT))))
                .describedAs("VP8L (lossless)").containsExactly(WIDTH, HEIGHT);
        assertThat(imageSize(file("extended.webp", extendedWebp(WIDTH, HEIGHT))))
                .describedAs("VP8X (extended)").containsExactly(WIDTH, HEIGHT);

        // 14 bits is the format's limit, and the arithmetic has to hold at it.
        assertThat(imageSize(file("max.webp", losslessWebp(16384, 16384))))
                .describedAs("VP8L at the 14-bit maximum").containsExactly(16384, 16384);
    }

    @Test
    @DisplayName("nothing is invented for a file that is not an image")
    void returnsNullRatherThanGuessing() throws Exception {
        assertThat(imageSize(file("empty.png", new byte[0]))).isNull();
        assertThat(imageSize(file("text.png", "this is not a PNG at all".getBytes("US-ASCII"))))
                .isNull();

        // A real PNG signature, then nothing: a truncated download, which must not
        // produce a size read from whatever bytes happen to follow.
        byte[] png = Files.readAllBytes(write("png", WIDTH, HEIGHT));
        assertThat(imageSize(file("truncated.png", java.util.Arrays.copyOf(png, 12)))).isNull();

        // A JPEG that never reaches a frame header.
        assertThat(imageSize(file("headless.jpg", new byte[] { (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xDA, 0, 2 }))).isNull();

        // A WEBP announcing VP8 whose start code isn't VP8's: the bytes where the size
        // would be mean nothing, so they must not be read as a size.
        byte[] webp = lossyWebp(WIDTH, HEIGHT);
        webp[23] = 0x00;                            // was 0x9D
        assertThat(imageSize(file("bad-start-code.webp", webp))).isNull();

        // A RIFF/WEBP container whose first chunk is none of the three encodings —
        // malformed, or some revision this doesn't know. Either way, no size.
        assertThat(imageSize(file("unknown-chunk.webp", webp("ICCP", new byte[24])))).isNull();

        // A header that parses but claims nothing: "0 x 0 pixels" would be a worse
        // answer than admitting the dimensions are unavailable.
        assertThat(imageSize(file("zero.gif", "GIF89a\0\0\0\0".getBytes("US-ASCII")))).isNull();

        assertThat(imageSize(dir.resolve("does-not-exist.png"))).isNull();
    }

    /**
     * JRock's own header reader: {@code JRock.ImageHeader.size(Path)}.
     * <p>
     * Found by name rather than called directly, because the class is private - it is
     * an implementation detail of an include, not an API.
     */
    private static int[] imageSize(Path file) throws Exception {
        Class<?> imageHeader = Class.forName("JRock$ImageHeader");
        Method m = imageHeader.getDeclaredMethod("size", Path.class);
        m.setAccessible(true);
        return (int[]) m.invoke(null, file);
    }

    private Path write(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Path file = dir.resolve("image." + format);
        assertThat(ImageIO.write(image, format, file.toFile()))
                .describedAs("the JDK can write " + format).isTrue();
        return file;
    }

    private Path file(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    /** The JPEG with an APP1 segment of {@code payload} bytes inserted after the SOI. */
    private static byte[] withApp1Segment(byte[] jpeg, int payload) throws IOException {
        int length = payload + 2;              // the length field counts itself
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                 // SOI
        out.write(0xFF);
        out.write(0xE1);                       // APP1, where EXIF lives
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(new byte[payload]);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** A RIFF/WEBP container around one chunk. */
    private static byte[] webp(String fourCC, byte[] payload) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes("US-ASCII"));
        writeLe32(out, 4 + 8 + payload.length);
        out.write("WEBP".getBytes("US-ASCII"));
        out.write(fourCC.getBytes("US-ASCII"));
        writeLe32(out, payload.length);
        out.write(payload);
        return out.toByteArray();
    }

    /** VP8: a frame tag, the 9D 01 2A start code, then 14-bit width and height. */
    private static byte[] lossyWebp(int width, int height) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(new byte[] { 0x30, 0x01, 0x00 });          // frame tag (key frame)
        p.write(new byte[] { (byte) 0x9D, 0x01, 0x2A });   // start code
        writeLe16(p, width);                               // scaling bits left at 0
        writeLe16(p, height);
        p.write(new byte[8]);                              // partition data, unread
        return webp("VP8 ", p.toByteArray());
    }

    /** VP8L: a 0x2F signature, then width-1 and height-1 in 14 bits each. */
    private static byte[] losslessWebp(int width, int height) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x2F);
        int bits = ((width - 1) & 0x3FFF) | (((height - 1) & 0x3FFF) << 14);
        writeLe32(p, bits);
        p.write(new byte[8]);
        return webp("VP8L", p.toByteArray());
    }

    /** VP8X: flags, three reserved bytes, then canvas width-1 and height-1 as 24-bit. */
    private static byte[] extendedWebp(int width, int height) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        p.write(0x10);                     // alpha flag, arbitrary here
        p.write(new byte[3]);              // reserved
        writeLe24(p, width - 1);
        writeLe24(p, height - 1);
        return webp("VP8X", p.toByteArray());
    }

    private static void writeLe16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLe24(ByteArrayOutputStream out, int value) {
        writeLe16(out, value);
        out.write((value >> 16) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        writeLe24(out, value);
        out.write((value >>> 24) & 0xFF);
    }
}
