// A JRock agent (it loops, and is started with JRock): a timer and alarm clock, and the
// first agent that other agents talk to.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockTimer.java [--working-dir <dir>]
//
// The jar is wherever the installer put it - one folder up from samples/flows/,
// two up from an agent's own folder - and -cp names it. Take the flags as
// JRock's own and expect a typed static call rather than reflection: all of
// that is samples/flows/JRockDocInventory.java's opening comment, and it is
// true of every agent and every flow. Read that file first.
//
// It needs no Bedrock API key: nothing here calls a model, which makes it a demo that runs
// on any machine. What it takes from JRock is one setting, the speaker in Configure >
// Narrate on (JRock.automationNarrateDevice) - the sounds are its own, made here, and
// played on that device with Java Sound, never on the Windows default.
//
//   1. JRock is started and taken as an automation: its prompt read-only and Mic always
//      on muted, so this JRock never records what is said to another one.
//   2. A small window opens beside JRock's: a time, hh:mm:ss, and Set. Set, and one
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

    private static javax.swing.JFrame window;
    private static javax.swing.JTextField timeField;
    private static javax.swing.JLabel status;
    private static javax.swing.DefaultListModel<Alarm> alarms;   // EDT only
    private static javax.swing.JList<Alarm> list;
    private static volatile ServerSocket server;

    public static void main(String[] args) {
        JRock.main(args);
        // Ready, or ready but without a key - which this agent does not need. Only no
        // window at all is a reason not to start.
        String notReady = JRock.automationAwaitReady(READY_TIMEOUT_MS);
        String problem = JRock.automationWindow() == null ? notReady
                : JRock.automationBegin("timer agent");
        if (problem != null) {
            System.out.println("Stopped: " + problem);
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The timer agent could not start:\n\n" + problem,
                    "Timer", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        onEdt(JRockTimer::showWindow);
        String listening = listen();
        later(() -> say(listening));
        System.out.println("Timer is ready. " + listening);
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
            later(list::repaint);
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
    // round again from the fourth. Three notes each, around the fifth octave where the
    // single beep used to be, so a chord carries across a room as that beep did.
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

    // Plays pcm on JRock's Narrate on device, a slice at a time so stop() is looked at
    // every 50 ms. On the SOUND thread. Returns whether it could play - a device not set,
    // or not there, is said on the status line and is false.
    private static boolean play(byte[] pcm, java.util.function.BooleanSupplier stop) {
        String device = JRock.automationNarrateDevice();
        if (device == null || device.isEmpty()) {
            later(() -> say("No sound: no speaker is set. Press Narrate in JRock's log menu "
                    + "to list them, and pick one in Configure > Narrate on."));
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
                for (int i = 0; i < alarms.size(); i++) alarms.get(i).phase = Phase.REMOVED;
                ServerSocket s = server;
                if (s != null) {
                    try { s.close(); } catch (IOException ignored) { }
                }
                daemon(() -> JRock.automationEnd("timer agent closed"), "jrock-timer-end").start();
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

        status = new javax.swing.JLabel(" ");
        status.setFont(base.deriveFont(java.awt.Font.PLAIN, 12f));
        status.setForeground(MUTED);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 2, 10, 2));

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
        scroll.setPreferredSize(new java.awt.Dimension(320, 220));

        javax.swing.JButton remove = new javax.swing.JButton("Remove");
        remove.setToolTipText("Removes the selected alarm - and stops it, if it is ringing "
                + "(Delete or Escape)");
        remove.addActionListener(e -> removeSelected());
        javax.swing.JPanel bottom = new javax.swing.JPanel(new java.awt.FlowLayout(
                java.awt.FlowLayout.RIGHT, 0, 8));
        bottom.setOpaque(false);
        bottom.add(remove);

        javax.swing.JPanel north = new javax.swing.JPanel(new java.awt.BorderLayout());
        north.setOpaque(false);
        north.add(top, java.awt.BorderLayout.NORTH);
        north.add(status, java.awt.BorderLayout.CENTER);

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
        window.setMinimumSize(window.getSize());
        // Beside JRock's window rather than on top of it, so both can be watched.
        java.awt.Window jrock = JRock.automationWindow();
        if (jrock != null) {
            java.awt.Rectangle at = jrock.getBounds();
            window.setLocation(Math.max(0, at.x - window.getWidth()), at.y);
        } else {
            window.setLocationRelativeTo(null);
        }
        window.setVisible(true);
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
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        status.setText("<html><div style='width:300px'>" + safe + "</div></html>");
    }

    // ---- Plumbing --------------------------------------------------------------
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
