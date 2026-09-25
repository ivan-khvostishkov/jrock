import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What <em>Mic always on</em> hears: speech, and not a clap, a phone ringing or a
 * doorbell - fed to {@code JRock.Ears} as synthetic 16 kHz audio, no microphone.
 * <p>
 * The voice is a vowel as a voice makes one: a 120 Hz buzz with its harmonics shaped by
 * three formants, rising and falling at a syllable rate. The rings are what rings are,
 * one pure tone or two. A clap - or a key hit - is a burst of noise gone in a few
 * milliseconds.
 *
 * @see JRockGuiFixture for the tests that drive the real window
 */
class JRockEarsTest {

    private static final int RATE = 16000;

    @Test
    @DisplayName("A voice after a quiet room is heard as speech")
    void hearsSpeech() {
        JRock.Ears ears = hear(room(1.0), voice(1.0, 0.1));
        assertThat(ears.heard).isEqualTo("speech");
    }

    @Test
    @DisplayName("A clap or a key hit is not heard")
    void ignoresAClap() {
        JRock.Ears ears = hear(room(1.0), clap(), room(0.5));
        assertThat(ears.heard).isNull();
    }

    @Test
    @DisplayName("A phone ringing (440 + 480 Hz) is not heard")
    void ignoresAPhoneRing() {
        JRock.Ears ears = hear(room(1.0), tones(2.0, 0.2, 440, 480), room(1.0));
        assertThat(ears.heard).isNull();
    }

    @Test
    @DisplayName("A doorbell (ding-dong, 660 then 550 Hz) is not heard")
    void ignoresADoorbell() {
        JRock.Ears ears = hear(room(1.0), tones(0.6, 0.3, 660, 660 * 2.76),
                tones(1.2, 0.3, 550, 550 * 2.76), room(1.0));
        assertThat(ears.heard).isNull();
    }

    @Test
    @DisplayName("A quiet room is not heard, and counts as quiet")
    void aQuietRoomIsQuiet() {
        JRock.Ears ears = hear(room(3.0));
        assertThat(ears.heard).isNull();
        assertThat(ears.quietMs).isGreaterThan(2500);
    }

    @Test
    @DisplayName("Quiet after a voice is counted from where the voice stopped")
    void quietIsCountedAfterTheVoice() {
        JRock.Ears ears = hear(room(1.0), voice(1.0, 0.1), room(2.0));
        assertThat(ears.quietMs).isBetween(1500, 2100);
    }

    // ---- The sounds, as samples in -1..1 ------------------------------------

    // A room: faint noise, -60 dBFS or so.
    private static double[] room(double seconds) {
        java.util.Random r = new java.util.Random(1);
        double[] s = new double[(int) (seconds * RATE)];
        for (int i = 0; i < s.length; i++) s[i] = r.nextGaussian() * 0.001;
        return s;
    }

    // A vowel: 120 Hz and its harmonics, each weighted by three formants (700, 1200,
    // 2600 Hz), the whole of it rising and falling four times a second.
    private static double[] voice(double seconds, double peak) {
        double[] s = room(seconds);
        double[] formants = { 700, 1200, 2600 }, widths = { 130, 150, 200 };
        for (int h = 1; h * 120 < 7000; h++) {
            double hz = h * 120, a = 0;
            for (int f = 0; f < 3; f++) {
                double d = (hz - formants[f]) / widths[f];
                a += 1.0 / (1 + d * d) / (f + 1);
            }
            for (int i = 0; i < s.length; i++) {
                double t = (double) i / RATE;
                double syllable = 0.55 + 0.45 * Math.sin(2 * Math.PI * 4 * t);
                s[i] += peak * 0.3 * a * syllable * Math.sin(2 * Math.PI * hz * t + h);
            }
        }
        return s;
    }

    // Pure tones together, each at the same level, fading out as a bell does.
    private static double[] tones(double seconds, double peak, double... hz) {
        double[] s = room(seconds);
        for (int i = 0; i < s.length; i++) {
            double t = (double) i / RATE, decay = Math.exp(-t * 1.5);
            for (double f : hz) s[i] += peak / hz.length * decay * Math.sin(2 * Math.PI * f * t);
        }
        return s;
    }

    // A clap: white noise, loud, gone within about ten milliseconds.
    private static double[] clap() {
        java.util.Random r = new java.util.Random(2);
        double[] s = room(0.1);
        for (int i = 0; i < s.length; i++) {
            s[i] += r.nextGaussian() * 0.5 * Math.exp(-(double) i / RATE / 0.004);
        }
        return s;
    }

    // Everything, one after another, as 16-bit mono PCM fed in 100 ms reads - the way
    // a recording reads it.
    private static JRock.Ears hear(double[]... parts) {
        int total = 0;
        for (double[] p : parts) total += p.length;
        byte[] pcm = new byte[total * 2];
        int at = 0;
        for (double[] p : parts) {
            for (double v : p) {
                int x = (int) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
                pcm[at++] = (byte) x;
                pcm[at++] = (byte) (x >> 8);
            }
        }
        JRock.Ears ears = new JRock.Ears(RATE);
        int read = RATE / 10 * 2;
        byte[] buf = new byte[read];
        for (int i = 0; i < pcm.length; i += read) {
            int n = Math.min(read, pcm.length - i);
            System.arraycopy(pcm, i, buf, 0, n);
            ears.feed(buf, n, 1);
        }
        return ears;
    }
}
