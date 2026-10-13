// A JRock agent (it loops): a timer and alarm clock, and the first agent that other
// agents talk to.
//
// NO JROCK AT ALL, and that is the point of it. It needs no model, no Bedrock key and no
// window of JRock's: an alarm clock that made a text-generation program start up in order
// to ring is a strange alarm clock. The speaker it rings on is chosen in this window and
// remembered beside it (see SETTINGS) - a setting belongs to the program whose setting it
// is.
//
// Run it the way JRock itself runs - no build step, no Maven, one file:
//
//     java -cp jrock.jar JRockTimer.java [--working-dir <dir>]
//
// The jar on the class path is the installer's own habit rather than a need - nothing in
// this file calls into it any more - and the flags are accepted and ignored, so the
// right-click entry and the start script can treat this agent like all the others. All of
// that is samples/flows/JRockDocInventory.java's opening comment; read that file first.
//
//   1. A small window opens: a time, hh:mm:ss, and Set. Set, and one
//      beep in the alarm's own chord says it is on. The list under it counts down to
//      every alarm set since the window opened - nothing is kept across runs.
//   3. At the time, the alarm rings once and the list shows it rang. Remove (or Delete,
//      or Escape on it in the list) takes an alarm off the list.
//   4. Every alarm rings its own sound, so two of them due minutes apart can be told
//      apart from across the room: the first alarm set rings a C chord three times, the
//      second a G four times, the third an E five times, and the fourth starts the
//      chords again on C with six beeps. The window names the sound when the alarm is
//      set and again when it rings, and every row in the list carries it ("C x3").
//
// The time is the time of day the alarm rings, 24-hour: 07:30:00 is half past seven, today
// if that is still to come and tomorrow if it has gone. "+hh:mm:ss" is a timer instead:
// that long from now.
//
// Other agents set alarms over TCP, on the loopback interface only: connect to
// 127.0.0.1:PORT, send the time as above and a newline, read one line back - "1" when the
// alarm is set, "0" when it is not - and close. The push-to-talk agent does, when the
// model's answer says "Now is hh:mm:ss, I'm setting an alarm for hh:mm:ss".
//
// JRock's agents take their ports from JRock's own small range, AGENT_PORTS_FIRST to
// AGENT_PORTS_LAST (47470-47479): under Windows' dynamic range (49152 and up, where the
// system hands out ports and Hyper-V reserves blocks of them), and with no well-known
// service of its own. The timer is the first of them.
//
// Run it in a folder of its own - JRock keeps one log per folder and writes it whole, so
// two JRocks in one folder overwrite each other's. A folder with no key is fine; set its
// Narrate on once, as below.
//
// The speaker is JRock's to choose. Not set, or not connected, is a line in this window,
// and the alarm is still shown: press Narrate in JRock's log menu once to list the
// devices into Configure > Narrate on, pick one, and carry on.

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JRockTimer {

    // JRock's agents' ports, and the timer's. Other agents connect to PORT.
    public static final int AGENT_PORTS_FIRST = 47470;
    public static final int AGENT_PORTS_LAST = 47479;
    public static final int PORT = AGENT_PORTS_FIRST;

    // Starting a JVM's worth of Swing, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // How long a client has to send its line, and the longest line taken - a time is
    // nine characters, and anything much longer is not one.
    private static final int CLIENT_TIMEOUT_MS = 5000;
    private static final int LINE_MAX = 64;

    // What Set takes: hh:mm:ss, the hour in one digit or two; with a + in front, a
    // duration instead of a time of day.
    private static final Pattern TIME = Pattern.compile("(\\+)?(\\d{1,2}):(\\d{2}):(\\d{2})");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    // One alarm. due is when it rings; state changes on the alarm's thread and the EDT.
    private enum Phase { WAITING, RINGING, RANG, REMOVED }
    private static final class Alarm {
        final String label;      // what was set, as it is shown: "07:30:00" or "+00:05:00"
        final Instant due;
        // Which alarm this is, counting from 1 since the window opened - and so which
        // chord it rings on and how many times (see CHORDS and beeps). Counted on
        // setting rather than taken from the list, so an alarm removed from the middle
        // does not hand its sound to the next one set: two alarms waiting together are
        // never the same sound, which is the whole point of having more than one.
        final int number;
        volatile Phase phase = Phase.WAITING;
        Alarm(String label, Instant due, int number) {
            this.label = label; this.due = due; this.number = number;
        }
    }

    // How many alarms have been set since the window opened. Set from the window (EDT)
    // and from another agent's connection (its own thread), so it is counted atomically.
    private static final java.util.concurrent.atomic.AtomicInteger SET_COUNT =
            new java.util.concurrent.atomic.AtomicInteger();

    // Every sound goes through this one thread, so two never play over each other: a
    // confirmation set during a ring waits for the ring's pattern to finish.
    private static final java.util.concurrent.ExecutorService SOUND =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> daemon(r, "jrock-timer-sound"));

    // THE SPEAKER, this agent's own setting, remembered beside this file. Not JRock's,
    // and not the system default either: an alarm that rings somewhere other than where
    // its owner is listening has not rung. Empty until one is picked, which is the one
    // thing the window says out loud when it starts (see the chord in main).
    private static final String SETTINGS = "jrock-timer.txt";
    private static volatile String speaker = "";

    // THE SMALLEST THIS WINDOW CAN BE, as a whole number of cells on JRock's 32x27
    // grid (JRock.CELL_W, JRock.CELL_H - one grid for every window in this project):
    // 12 x 12 cells = 384 x 324, room for the clock, the two fields, three alarms and
    // the Remove button - the list taking whatever the window has beyond that.
    //
    // A NUMBER IN THIS FILE, and not whatever the layout worked out. This used to be
    // "pack(); setMinimumSize(getSize())", which makes the floor a figure nobody wrote
    // and nobody can read off the source - one that moves with the platform's fonts, the
    // display scaling, and even the text that happened to be in a label while the window
    // was being built. pack() still asks for the size of what is in this window, and it
    // opens at whichever of the two is larger.
    private static final int WINDOW_MIN_CELLS_W = 12;
    private static final int WINDOW_MIN_CELLS_H = 12;
    private static final int WINDOW_MIN_W = WINDOW_MIN_CELLS_W * JRock.CELL_W;
    private static final int WINDOW_MIN_H = WINDOW_MIN_CELLS_H * JRock.CELL_H;

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField timeField;
    private static javax.swing.JComboBox<String> speakerBox;
    private static javax.swing.JTextArea status;
    private static javax.swing.DefaultListModel<Alarm> alarms;   // EDT only
    private static javax.swing.JList<Alarm> list;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        // The flags are JRock's and this agent has no JRock: they are read for the log and
        // then left alone, so that one right-click entry and one start script can treat
        // every agent the same way.
        for (String arg : args) System.out.println("Ignoring JRock's flag: " + arg);
        readSettings();
        onEdt(JRockTimer::showWindow);
        // The alarms that were still waiting when this agent last stopped, back in the
        // list and counting down again (see restoreAlarms). After the window, because
        // that is what they go into; before the server, so an agent asking for the list
        // the moment this starts gets all of it.
        onEdt(JRockTimer::restoreAlarms);
        String listening = listen();
        later(() -> say(listening));
        System.out.println("Timer is ready. " + listening);

        // A chord on starting, before any alarm exists.
        //
        // It says the agent is up, which is worth something on its own when it was
        // started by a right-click or by the installer and its window may be behind
        // another. What it says that matters more: THE SOUND WORKS. Everything else here
        // is silent until an alarm comes due, so a speaker that is not set, or set to
        // something that is no longer plugged in, is a thing you would otherwise find out
        // when an alarm failed to wake you - and play() says why on the status line, now,
        // while there is time to fix it.
        //
        // The first alarm's own chord, half the volume of a confirmation and shorter:
        // recognisably this agent's voice rather than a system noise, and recognisably
        // not an alarm going off.
        SOUND.submit(() -> play(chord(CHORDS[0], 140, 0.5), () -> false));
    }

    // ---- Setting an alarm ------------------------------------------------------
    // Sets an alarm from text in the format above, from the window or from another
    // agent. Returns null, or why not. Any thread.
    static String set(String text, String from) {
        Matcher m = TIME.matcher(text == null ? "" : text.trim());
        if (!m.matches()) return "\"" + text + "\" is not hh:mm:ss.";
        int h = Integer.parseInt(m.group(2));
        int min = Integer.parseInt(m.group(3));
        int s = Integer.parseInt(m.group(4));
        if (min > 59 || s > 59) return "\"" + text.trim() + "\" has more than 59 minutes or seconds.";
        boolean timer = m.group(1) != null;
        if (!timer && h > 23) return "\"" + text.trim() + "\" is not a time of day.";
        Instant due;
        String label;
        if (timer) {
            Duration d = Duration.ofHours(h).plusMinutes(min).plusSeconds(s);
            if (d.isZero()) return "a timer needs a time longer than nothing.";
            due = Instant.now().plus(d);
            label = String.format("+%02d:%02d:%02d", h, min, s);
        } else {
            ZoneId zone = ZoneId.systemDefault();
            LocalTime at = LocalTime.of(h, min, s);
            ZonedDateTime when = ZonedDateTime.of(LocalDate.now(zone), at, zone);
            if (!when.isAfter(ZonedDateTime.now(zone))) when = when.plusDays(1);
            due = when.toInstant();
            label = at.format(HMS);
        }
        Alarm alarm = new Alarm(label, due, SET_COUNT.incrementAndGet());
        later(() -> {
            // In the order they ring.
            int i = 0;
            while (i < alarms.size() && !alarms.get(i).due.isAfter(due)) i++;
            alarms.add(i, alarm);
            say("Alarm set for " + ringsAt(alarm) + ", " + sounds(alarm)
                    + (from == null ? "." : ", by " + from + "."));
            // WRITTEN DOWN AS IT IS SET, and not only when the window closes: a machine
            // that is restarted for an update, or an agent that is killed rather than
            // closed, never reaches the closing handler - and an alarm is exactly the
            // thing that has to survive both.
            writeSettings();
        });
        SOUND.submit(() -> play(confirmation(alarm), () -> false));
        Thread wait = new Thread(() -> await(alarm), "jrock-timer-" + label);
        wait.setDaemon(true);
        wait.start();
        return null;
    }

    // The alarm's own thread: sleeps until it is due - in short naps against the wall
    // clock, so a laptop that slept through it rings on waking, not an hour late - and
    // then rings.
    private static void await(Alarm alarm) {
        try {
            long left;
            while ((left = Duration.between(Instant.now(), alarm.due).toMillis()) > 0) {
                if (alarm.phase == Phase.REMOVED) return;
                Thread.sleep(Math.min(left, 500));
            }
        } catch (InterruptedException ex) {
            return;
        }
        if (alarm.phase == Phase.REMOVED) return;
        alarm.phase = Phase.RINGING;
        later(() -> {
            say("Ringing: " + ringsAt(alarm) + ", " + sounds(alarm) + ".");
            list.repaint();
        });
        SOUND.submit(() -> {
            if (alarm.phase == Phase.RINGING) {
                play(ring(alarm), () -> alarm.phase != Phase.RINGING);
            }
            if (alarm.phase == Phase.RINGING) alarm.phase = Phase.RANG;
            later(() -> {
                list.repaint();
                writeSettings();    // it has rung: there is nothing left to pick up
            });
        });
    }

    // ---- The server ------------------------------------------------------------
    // Opens PORT on the loopback interface and takes connections on a thread of its own.
    // Returns what the status line should say about it.
    private static String listen() {
        try {
            ServerSocket socket = new ServerSocket(PORT, 16, InetAddress.getLoopbackAddress());
            server = socket;
            daemon(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket client = socket.accept();
                        daemon(() -> serve(client), "jrock-timer-client").start();
                    } catch (IOException closed) {
                        // Closed with the window, or failed: either way, no more clients.
                        return;
                    }
                }
            }, "jrock-timer-server").start();
            return "Other agents set alarms on 127.0.0.1:" + PORT + ".";
        } catch (IOException ex) {
            return "Not listening on port " + PORT + " (" + ex.getMessage() + ") - is "
                    + "another timer running? Alarms can still be set here.";
        }
    }

    // One client: a line in, "1" or "0" out, closed.
    private static void serve(Socket client) {
        try (Socket c = client) {
            c.setSoTimeout(CLIENT_TIMEOUT_MS);
            String line = readLine(c.getInputStream());
            String problem = line == null ? "no line came." : set(line, "another agent");
            OutputStream out = c.getOutputStream();
            out.write((problem == null ? "1\n" : "0\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (problem != null) {
                String said = problem;
                later(() -> say("Refused an alarm from another agent: " + said));
            }
        } catch (IOException ex) {
            // A client that went away, or never wrote: nothing was set, and nobody to tell.
        }
    }

    // The text up to a newline (a \r before it dropped), or null for none within
    // LINE_MAX bytes.
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (b == '\n') return bytes.toString(StandardCharsets.UTF_8.name()).replaceAll("\r$", "");
            if (bytes.size() >= LINE_MAX) return null;
            bytes.write(b);
        }
        return null;
    }

    // ---- The sounds ------------------------------------------------------------
    // Made here rather than read from files: a few sine tones need no files to ship,
    // and no licence to check. 44.1 kHz, 16-bit signed little-endian mono.
    private static final float RATE = 44100f;
    private static final javax.sound.sampled.AudioFormat FORMAT =
            new javax.sound.sampled.AudioFormat(RATE, 16, 1, true, false);

    // Set: the alarm's own chord, once.
    //
    // The same chord it will ring on, so setting an alarm already says which alarm it
    // is - and setting a third one, with two still waiting, is heard as a third sound
    // rather than as the same chirp a third time. Once against the ring's three times or
    // more, and a little quieter, is what tells "set" from "ringing" at the same chord:
    // the count is the difference, which is the same thing that tells two alarms apart.
    private static byte[] confirmation(Alarm alarm) {
        return chord(CHORDS[(alarm.number - 1) % CHORDS.length], 170, 0.7);
    }

    // One chord per alarm, in the order they were set: C major, G major, E major, and
    // round again from the fourth. Three notes each, around the fifth octave, which
    // carries across a room.
    private static final double[][] CHORDS = {
        { 523.25, 659.26, 783.99 },     // C major: C5, E5, G5
        { 783.99, 987.77, 1174.66 },    // G major: G5, B5, D6
        { 659.26, 830.61, 987.77 },     // E major: E5, G#5, B5
    };
    private static final String[] CHORD_NAMES = { "C", "G", "E" };

    // Beeps for the first alarm; every one after it gets one more.
    private static final int BEEPS_FIRST = 3;

    // Ringing: the alarm's own chord, beeped its own number of times, played once.
    //
    // Two alarms can come due minutes apart, or together, and from across the room the
    // only thing that says WHICH has gone off is the sound. So the chord and the count
    // both move with the alarm's number: the first rings C three times, the second G
    // four times, the third E five times, and the fourth starts the chords again on C
    // with six. The chords cycle because three are enough to tell neighbours apart; the
    // count keeps rising because it is what tells two alarms sharing a chord apart.
    private static byte[] ring(Alarm alarm) {
        double[] notes = CHORDS[(alarm.number - 1) % CHORDS.length];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < beeps(alarm); i++) {
            // Louder than the single beep was (0.6), because a chord is quieter at the
            // same figure: three notes divided by three peak at well under one note's
            // amplitude, their phases never lining up. 0.95 puts the loudness back where
            // it was and still leaves a third of the scale unused.
            out.writeBytes(chord(notes, 110, 0.95));
            out.writeBytes(silence(90));
        }
        return out.toByteArray();
    }

    private static int beeps(Alarm alarm) {
        return BEEPS_FIRST + alarm.number - 1;
    }

    // What an alarm will sound like, in words, for the status line: the window says it
    // when the alarm is set and again when it rings, so a sound coming out of the
    // speaker can be matched to the alarm that made it without counting beeps twice.
    private static String sounds(Alarm alarm) {
        return CHORD_NAMES[(alarm.number - 1) % CHORD_NAMES.length] + " chord, "
                + beeps(alarm) + " beeps";
    }

    // The same, short enough for a row in the list: "C x3".
    private static String soundTag(Alarm alarm) {
        return CHORD_NAMES[(alarm.number - 1) % CHORD_NAMES.length] + " x" + beeps(alarm);
    }

    // A chord: its notes summed, each of them a sine with its third harmonic for a
    // brighter beep that carries, faded in and out over 5 ms so it does not click.
    //
    // Divided by the number of notes, so three of them come out no louder than one and
    // nothing clips: a sum of sines peaks at the sum of their amplitudes, which is where
    // a chord played like a single tone turns into a crackle.
    private static byte[] chord(double[] notes, int ms, double volume) {
        int n = (int) (RATE * ms / 1000);
        int fade = (int) (RATE * 0.005);
        byte[] pcm = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double v = 0;
            for (double hz : notes) {
                v += Math.sin(2 * Math.PI * hz * t) * 0.8
                        + Math.sin(2 * Math.PI * hz * 3 * t) * 0.2;
            }
            v /= notes.length;
            double edge = Math.min(1.0, Math.min(i, n - 1 - i) / (double) fade);
            int sample = (int) (v * edge * volume * Short.MAX_VALUE);
            pcm[2 * i] = (byte) sample;
            pcm[2 * i + 1] = (byte) (sample >> 8);
        }
        return pcm;
    }

    private static byte[] silence(int ms) {
        return new byte[(int) (RATE * ms / 1000) * 2];
    }

    // THE WAY OUT, SAID OUT LOUD: two notes falling, where starting up is a chord rising.
    //
    // Closing this window ends the agent, and with it every alarm that was waiting - and
    // this window has one button, a list and a close box, which is not many ways to click
    // the wrong thing. The sound is for the time it is the wrong thing: a falling pair is
    // the one noise nobody has to be taught, and hearing it means the alarms have gone.
    //
    // Low and quiet: the agent has no more business making noise than that, and the ring
    // it must never be mistaken for is high, bright and three times over.
    private static byte[] closing() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        write(out, chord(new double[] { 261.63, 329.63 }, 130, 0.45));   // C4 + E4
        write(out, chord(new double[] { 174.61, 220.00 }, 260, 0.45));   // F3 + A3
        return out.toByteArray();
    }

    private static void write(java.io.ByteArrayOutputStream out, byte[] pcm) {
        out.write(pcm, 0, pcm.length);
    }

    // Plays pcm on the speaker chosen in this window, a slice at a time so stop() is
    // looked at every 50 ms. On the SOUND thread. Returns whether it could play - a device
    // not set, or not there, is said on the status line and is false.
    private static boolean play(byte[] pcm, java.util.function.BooleanSupplier stop) {
        String device = speaker;
        if (device == null || device.isEmpty()) {
            later(() -> say("No sound: no speaker is set. Pick one in Speaker, above - "
                    + "the list fills itself when you open it."));
            return false;
        }
        javax.sound.sampled.Mixer mixer = outputMixer(device);
        if (mixer == null) {
            later(() -> say("No sound: the speaker \"" + device + "\" is not connected."));
            return false;
        }
        javax.sound.sampled.SourceDataLine line = null;
        try {
            line = (javax.sound.sampled.SourceDataLine) mixer.getLine(
                    new javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine.class, FORMAT));
            line.open(FORMAT);
            line.start();
            int slice = (int) (RATE * 0.05) * 2;
            for (int at = 0; at < pcm.length; at += slice) {
                if (stop.getAsBoolean()) {
                    line.flush();
                    return true;
                }
                line.write(pcm, at, Math.min(slice, pcm.length - at));
            }
            line.drain();
            return true;
        } catch (javax.sound.sampled.LineUnavailableException | RuntimeException ex) {
            later(() -> say("No sound: could not play on " + device + " (" + ex.getMessage() + ")."));
            return false;
        } finally {
            if (line != null) line.close();
        }
    }

    // The output mixer of exactly that name, as JRock looks it up for Narrate: no near
    // matches, and never the "Primary Sound Driver" alias for the Windows default.
    private static javax.sound.sampled.Mixer outputMixer(String name) {
        javax.sound.sampled.Line.Info playback =
                new javax.sound.sampled.Line.Info(javax.sound.sampled.SourceDataLine.class);
        for (javax.sound.sampled.Mixer.Info info : javax.sound.sampled.AudioSystem.getMixerInfo()) {
            if (!info.getName().equals(name) || name.equals("Primary Sound Driver")) continue;
            javax.sound.sampled.Mixer mixer = javax.sound.sampled.AudioSystem.getMixer(info);
            if (mixer.isLineSupported(playback)) return mixer;
        }
        return null;
    }

    // ---- The window ------------------------------------------------------------
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color INK = new java.awt.Color(0x1F2328);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);
    private static final java.awt.Color RINGING = new java.awt.Color(0xE53935);

    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Timer");
        // Closing this window ends the agent, not JRock: no more alarms, no more clients.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                // The alarms that are still waiting are written down BEFORE they are
                // called off, so a restart picks them up where this left them (see
                // readSettings): closing the window is not the same as cancelling an
                // alarm, and an alarm set for eight tomorrow morning should not be undone
                // by tidying the desktop tonight.
                writeSettings();
                for (int i = 0; i < alarms.size(); i++) alarms.get(i).phase = Phase.REMOVED;
                ServerSocket s = server;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                // PLAYED HERE AND NOT HANDED TO THE SOUND THREAD. That thread is a daemon,
                // and this is the last thing this JVM does: a sound submitted now would be
                // dropped when the window's dispose lets the process end. Half a second on
                // the event thread, at the one moment there is nothing left for it to do.
                play(closing(), () -> false);
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);

        timeField = new javax.swing.JTextField(
                LocalTime.now().plusMinutes(5).withNano(0).format(HMS), 9);
        timeField.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.BOLD, 22));
        timeField.setHorizontalAlignment(javax.swing.JTextField.CENTER);
        timeField.setToolTipText("The time the alarm rings, 24-hour hh:mm:ss - or +hh:mm:ss "
                + "for that long from now");
        javax.swing.JButton setButton = new javax.swing.JButton("Set");
        setButton.setFont(base.deriveFont(java.awt.Font.BOLD, 15f));
        Runnable setIt = () -> {
            String problem = set(timeField.getText(), null);
            if (problem != null) say("Not set: " + problem);
        };
        setButton.addActionListener(e -> setIt.run());
        timeField.addActionListener(e -> setIt.run());

        javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(timeField, java.awt.BorderLayout.CENTER);
        top.add(setButton, java.awt.BorderLayout.EAST);

        // The speaker, chosen here rather than taken from another program's settings.
        // Picking one plays its chord at once: "which speaker is this?" is a question
        // only the ears can answer, and the answer should not have to wait for an alarm.
        speakerBox = new javax.swing.JComboBox<>();
        speakerBox.setFont(base.deriveFont(java.awt.Font.PLAIN, 12f));
        speakerBox.setToolTipText("Where the alarms ring. Open the list and it asks this "
                + "machine what it has, every time - so something plugged in a minute ago "
                + "is in it.");
        speakerBox.addItem("");
        if (!speaker.isEmpty()) speakerBox.addItem(speaker);
        speakerBox.setSelectedItem(speaker);
        speakerBox.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                speakerList();
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                // Nothing: the list is made on the way in, not on the way out.
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                // Nothing.
            }
        });
        speakerBox.addActionListener(e -> {
            if (filling) return;   // the list being refilled is nobody choosing anything
            Object picked = speakerBox.getSelectedItem();
            String now = picked == null ? "" : picked.toString();
            if (now.equals(speaker)) return;
            speaker = now;
            writeSettings();
            if (speaker.isEmpty()) {
                say("No speaker set: nothing will be heard when an alarm rings.");
                return;
            }
            say("Alarms will ring on " + speaker + ".");
            SOUND.submit(() -> play(chord(CHORDS[0], 140, 0.5), () -> false));
        });

        javax.swing.JPanel speakerRow = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        speakerRow.setOpaque(false);
        speakerRow.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 0, 0, 0));
        javax.swing.JLabel speakerLabel = new javax.swing.JLabel("Speaker:");
        speakerLabel.setFont(base.deriveFont(java.awt.Font.PLAIN, 12f));
        speakerLabel.setForeground(MUTED);
        speakerRow.add(speakerLabel, java.awt.BorderLayout.WEST);
        speakerRow.add(speakerBox, java.awt.BorderLayout.CENTER);

        // A WRAPPING TEXT AREA AND NOT A LABEL. A label wraps only where its HTML names a
        // width in CSS pixels, and Swing scales those by the display: "width:300px" asked
        // for 394 device pixels on a 130% screen, in a window with 320 to give, so the line
        // was cut off on the right. A text area wraps to the width it HAS, and breaks
        // inside a word when the word is wider than that - which a file name or an
        // address, being one long token with no space in it, always is.
        status = new javax.swing.JTextArea(1, 1);
        status.setFont(base.deriveFont(java.awt.Font.PLAIN, 12f));
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 10, 2));
        status.setEditable(false);
        status.setOpaque(false);
        status.setFocusable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);

        alarms = new javax.swing.DefaultListModel<>();
        list = new javax.swing.JList<>(alarms);
        java.awt.Font rowFont = base.deriveFont(java.awt.Font.PLAIN, 14f);
        list.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;   // never leaves this JVM
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> l, Object value, int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(l, value, index, selected, focus);
                Alarm a = (Alarm) value;
                setFont(rowFont);
                setBorder(javax.swing.BorderFactory.createEmptyBorder(5, 10, 5, 10));
                setText(ringsAt(a) + "  " + soundTag(a) + "    " + countdown(a));
                if (!selected) setForeground(a.phase == Phase.RINGING ? RINGING
                        : a.phase == Phase.RANG ? MUTED : INK);
                return this;
            }
        });
        list.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(java.awt.event.KeyEvent e) {
                if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DELETE
                        || e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE) removeSelected();
            }
        });
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(list);
        // THE LIST IS WHAT GIVES WAY, and that is what makes this window short. It is
        // the only part in the centre of the layout, so it takes every pixel the
        // window has over what the rows above and the button below need - and asking
        // for 220 of them made the smallest window two hundred pixels taller than
        // anything in it required. Three rows is enough to see that an alarm is set
        // and what it will sound like; a fourth is one drag of the window's edge away,
        // and the scrollbar is there either way.
        scroll.setPreferredSize(new java.awt.Dimension(320, 110));

        javax.swing.JButton remove = new javax.swing.JButton("Remove");
        remove.setToolTipText("Removes the selected alarm - and stops it, if it is ringing "
                + "(Delete or Escape)");
        remove.addActionListener(e -> removeSelected());
        javax.swing.JPanel bottom = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 0, 8));
        bottom.setOpaque(false);
        bottom.add(remove);

        javax.swing.JPanel north = new javax.swing.JPanel();
        north.setLayout(new javax.swing.BoxLayout(north, javax.swing.BoxLayout.Y_AXIS));
        north.setOpaque(false);
        for (javax.swing.JComponent row : new javax.swing.JComponent[] { top, speakerRow }) {
            row.setAlignmentX(0f);
            row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE,
                    row.getPreferredSize().height));
            north.add(row);
        }
        // Not capped: it wraps, and a wrapped line's height depends on its width.
        status.setAlignmentX(0f);
        north.add(status);

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(18, 18, 12, 18));
        content.add(north, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        content.add(bottom, java.awt.BorderLayout.SOUTH);
        window.setContentPane(content);
        window.getRootPane().setDefaultButton(setButton);

        // The countdowns, redrawn four times a second so a second never shows twice long.
        new javax.swing.Timer(250, e -> list.repaint()).start();

        window.pack();
        window.setMinimumSize(new java.awt.Dimension(WINDOW_MIN_W, WINDOW_MIN_H));
        window.setLocationRelativeTo(null);
        window.setVisible(true);
        // NOTHING THAT CAN BE CHANGED HAS THE FOCUS WHEN THIS OPENS. Swing gives it to
        // the first focusable component, which in these windows is a field, a checkbox or
        // a button - a setting one stray keypress away from being something else, with a
        // caret blinking in it as an invitation. The root pane takes it instead: it has no
        // key bindings of its own, so a keypress does nothing at all, and Tab still steps
        // into the window the usual way. Done in a later() so it happens AFTER Swing has
        // handed the focus out on activation.
        window.getRootPane().setFocusable(true);
        later(() -> window.getRootPane().requestFocusInWindow());
        timeField.requestFocusInWindow();
        timeField.selectAll();
    }

    // Removes the selected alarm, which also stops it ringing. On the EDT.
    private static void removeSelected() {
        int i = list.getSelectedIndex();
        if (i < 0) return;
        Alarm a = alarms.remove(i);
        boolean wasRinging = a.phase == Phase.RINGING;
        a.phase = Phase.REMOVED;
        say((wasRinging ? "Stopped and removed: " : "Removed: ") + ringsAt(a) + ".");
        if (!alarms.isEmpty()) list.setSelectedIndex(Math.min(i, alarms.size() - 1));
        writeSettings();        // one fewer to pick up next time
    }

    // When an alarm rings, as a time of day - with the date too, when that is not today.
    private static String ringsAt(Alarm a) {
        ZonedDateTime when = a.due.atZone(ZoneId.systemDefault());
        String time = when.format(HMS);
        return when.toLocalDate().equals(LocalDate.now()) ? time
                : time + " (" + when.format(DateTimeFormatter.ofPattern("EEE d MMM",
                        java.util.Locale.ENGLISH)) + ")";
    }

    // What an alarm's row says after the time: how long to go, or what became of it.
    private static String countdown(Alarm a) {
        switch (a.phase) {
            case RINGING: return "ringing";
            case RANG:    return "rang";
            case REMOVED: return "removed";
            default:
                long s = Math.max(0, (Duration.between(Instant.now(), a.due).toMillis() + 999) / 1000);
                return String.format("in %d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        }
    }

    // The status line, wrapped rather than widening the window for a long message.
    private static void say(String text) {
        status.setText(text);
        // A wrapped line's height depends on the width it is given, so the area is pinned
        // to the width it HAS before the layout runs again - otherwise the row keeps the
        // height it had for the sentence before. Width 0 is a window not laid out yet.
        int wide = status.getWidth();
        if (wide > 0) status.setSize(wide, Short.MAX_VALUE);
        status.revalidate();
    }

    // ---- Plumbing --------------------------------------------------------------
    // ---- The speaker, chosen here -----------------------------------------------
    // THE LIST IS MADE EVERY TIME IT IS OPENED, and deliberately not once like JRock's
    // Configure dropdown. That one lives in a dialog somebody opens to change a setting
    // and closes again, so listing once per dialog is a fair bargain for half a second.
    // This window is open for days: a headset plugged in this afternoon has to be in the
    // list this afternoon, and nobody is going to restart an alarm clock to see it. The
    // cost is the same half-second only on the first open of a session - Java Sound
    // caches the enumeration, and every later one measured about 20 ms.
    private static void speakerList() {
        Object chosen = speakerBox.getSelectedItem();
        String keep = chosen == null ? "" : chosen.toString();
        java.util.List<String> found = outputDeviceNames();
        boolean gone = !keep.isEmpty() && !found.contains(keep);
        filling = true;
        try {
            speakerBox.removeAllItems();
            speakerBox.addItem("");
            for (String name : found) speakerBox.addItem(name);
            // THE ONE CHOSEN BEFORE STAYS IN THE LIST even when the machine has not got
            // it at the moment: a headset switched off for the night is still the
            // operator's choice. A dropdown cannot hold a selection that is not one of
            // its items - it quietly takes the first one instead - so without this line
            // opening the list would re-point the alarms at nothing, and the operator
            // would have no way of knowing until one failed to ring.
            if (gone) speakerBox.addItem(keep);
            speakerBox.setSelectedItem(keep);
        } finally {
            filling = false;
        }
        if (gone) {
            say("\"" + keep + "\" is not connected now - it is still the one chosen, so "
                    + "alarms will be heard again when it is back. Pick another to move "
                    + "them.");
        }
    }

    // True while the list is being refilled: Swing fires the same event for that as for a
    // person choosing, and refilling must neither change the speaker nor beep about it.
    private static boolean filling;

    // The speakers this machine has, by the name Java Sound gives them - the same names
    // JRock's own Configure shows, because both ask the same question of the same system.
    private static java.util.List<String> outputDeviceNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        javax.sound.sampled.Line.Info playback =
                new javax.sound.sampled.Line.Info(javax.sound.sampled.SourceDataLine.class);
        for (javax.sound.sampled.Mixer.Info info
                : javax.sound.sampled.AudioSystem.getMixerInfo()) {
            // Never the "Primary Sound Driver" alias: it is whatever Windows feels like
            // at the time, and an alarm has to ring where it was told to ring.
            if (info.getName().equals("Primary Sound Driver")) continue;
            try {
                if (javax.sound.sampled.AudioSystem.getMixer(info).isLineSupported(playback)
                        && !names.contains(info.getName())) {
                    names.add(info.getName());
                }
            } catch (RuntimeException goneAlready) {
                // A device unplugged while it was being asked about: not in the list.
            }
        }
        return names;
    }

    // ---- Settings ---------------------------------------------------------------
    // One line beside this agent's own file, so the speaker chosen once is the speaker
    // tomorrow. Nothing of JRock's is read or written: this is this agent's setting.
    // ---- What survives a restart -------------------------------------------------
    // THE ALARMS THAT ARE STILL WAITING, beside the speaker in this agent's own settings
    // file: "alarm=<label>|<when, in epoch milliseconds>|<its number>".
    //
    // Why they are kept at all: an alarm is for a moment in the future, and the machine
    // between now and then is promised to nobody - Windows restarts for an update, the
    // laptop is shut, the window is closed by mistake. An alarm set for eight tomorrow
    // morning that a reboot at midnight quietly cancels is worse than no alarm at all,
    // because it was relied on.
    //
    // AN ALARM THAT CAME DUE WHILE NOTHING WAS RUNNING IS DROPPED WITHOUT A WORD. Ringing
    // it late is the one thing it must not do - "it is 07:30" at nine in the morning is
    // not information, it is a machine talking about yesterday - and there is nothing to
    // be done about a moment that has passed, so nothing is said about it either. A window
    // that opens complaining about three of them is a window that teaches somebody to
    // close it.
    //
    // THE ABSOLUTE MOMENT IS WHAT IS WRITTEN, not the time of day: "+00:05:00" set at
    // 23:58 is due at 00:03, and a timer read back as five minutes from whenever the agent
    // next starts would be an alarm for a different moment. The label is written too, so
    // the list still says what was asked for.
    private static final String ALARM_PREFIX = "alarm=";

    // Read here, put in the list from the window: the list and the sounds belong to the
    // event thread.
    private static final java.util.List<Alarm> restored = new java.util.ArrayList<>();

    private static void readSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String text = line.trim();
                if (text.startsWith("speaker=")) {
                    speaker = text.substring("speaker=".length()).trim();
                } else if (text.startsWith(ALARM_PREFIX)) {
                    Alarm alarm = alarmOf(text.substring(ALARM_PREFIX.length()).trim());
                    if (alarm != null) restored.add(alarm);
                }
            }
        } catch (IOException ex) {
            System.out.println("Could not read " + file + ": " + ex.getMessage());
        }
    }

    // One written line back into an alarm, or null - and a line this cannot read and one
    // whose moment has gone are the same thing here: nothing to ring.
    private static Alarm alarmOf(String written) {
        String[] parts = written.split(Pattern.quote("|"));
        if (parts.length < 3) return null;
        try {
            Instant due = Instant.ofEpochMilli(Long.parseLong(parts[1].trim()));
            if (!due.isAfter(Instant.now())) return null;    // came due while we were off
            return new Alarm(parts[0].trim(), due, Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    // Every alarm that has not rung yet, as the lines readSettings reads back. Called on
    // the event thread, which is where the list lives.
    private static String alarmLines() {
        StringBuilder out = new StringBuilder();
        if (alarms == null) return "";
        for (int i = 0; i < alarms.size(); i++) {
            Alarm alarm = alarms.get(i);
            if (alarm.phase != Phase.WAITING) continue;
            out.append(ALARM_PREFIX).append(alarm.label).append('|')
               .append(alarm.due.toEpochMilli()).append('|').append(alarm.number)
               .append(System.lineSeparator());
        }
        return out.toString();
    }

    // The alarms read back at startup, put in the list and given their waiting threads.
    // On the event thread, once the window exists. Said once, because an alarm somebody
    // set yesterday being still set is worth knowing - and nothing at all when there were
    // none, which is the ordinary case.
    private static void restoreAlarms() {
        if (restored.isEmpty()) return;
        int highest = 0;
        for (Alarm alarm : restored) {
            int i = 0;
            while (i < alarms.size() && !alarms.get(i).due.isAfter(alarm.due)) i++;
            alarms.add(i, alarm);
            highest = Math.max(highest, alarm.number);
            Thread wait = new Thread(() -> await(alarm), "jrock-timer-" + alarm.label);
            wait.setDaemon(true);
            wait.start();
        }
        // The next alarm set carries the numbering on, so its chord and its count are not
        // ones an alarm already waiting has (see CHORDS).
        SET_COUNT.set(Math.max(SET_COUNT.get(), highest));
        say(restored.size() == 1
                ? "One alarm was still set: " + ringsAt(restored.get(0)) + ", "
                  + sounds(restored.get(0)) + "."
                : restored.size() + " alarms were still set, the first at "
                  + ringsAt(restored.get(0)) + ".");
        restored.clear();
        // The file again, now that the ones whose moment had passed have been dropped:
        // what is written is what is waiting, from the first moment of the run.
        writeSettings();
    }

    private static void writeSettings() {
        Path file = ownDirectory().resolve(SETTINGS);
        String text = "' JRock timer agent\nspeaker=" + speaker + "\n";
        try {
            // The speaker, and every alarm still waiting (see alarmLines).
            Files.write(file, (text + alarmLines()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            later(() -> say("Could not remember the alarms in " + file + ": "
                    + ex.getMessage()));
        }
    }

    // The folder this agent's own file is in - where its settings go. Not the working
    // directory: a right-click entry runs an agent IN the folder that was clicked.
    private static Path ownDirectory() {
        String source = System.getProperty("jdk.launcher.sourcefile");
        if (source != null && !source.isBlank()) {
            Path parent = Paths.get(source).toAbsolutePath().normalize().getParent();
            if (parent != null) return parent;
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    private static Thread daemon(Runnable body, String name) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    private static void later(Runnable body) {
        javax.swing.SwingUtilities.invokeLater(body);
    }

    // Runs body on the event dispatch thread and waits for it.
    private static void onEdt(Runnable body) {
        try {
            javax.swing.SwingUtilities.invokeAndWait(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            throw new RuntimeException(ex.getCause());
        }
    }
}
