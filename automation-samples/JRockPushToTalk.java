// A JRock automation: a push-to-talk voice agent.
//
// Run it the way JRock itself runs - no build step, no Maven, one file - with the jar on
// the class path:
//
//     java -cp jrock.jar JRockPushToTalk.java [--working-dir <dir>] [--prompts-dir <dir>]
//
// COPY jrock.jar HERE FIRST, take the flags as JRock's own, and expect a typed static
// call rather than reflection: all of that is JRockDocInventory.java's opening comment,
// and it is true of every automation in this directory. Read that file first.
//
// What this one does is a loop, not a pass:
//
//   1. JRock is started, and the prompt cleared.
//   2. A small window of its own opens beside JRock's, with one round button. Hold it -
//      or hold Ctrl while this window has the focus - and JRock records from the
//      microphone set in its Configure > Record from.
//   3. Let go, and JRock stops the recording and puts it in the prompt: as an @audio
//      token, or with "Transcribe recordings into the prompt" ticked, as the text
//      Windows heard. That is JRock's setting to decide, not this agent's.
//   4. As soon as the prompt has it, the agent presses Send, waits for the reply, and
//      prints the exchange in its own window. The button is ready for the next turn.
//
// Each turn is a question of its own: the prompt is cleared before every recording, and
// an automation runs with History off, so the model hears one recording at a time and
// not the conversation so far. The whole conversation is in JRock's transcript all the
// same, and closing this window hands JRock back to you with it.
//
// The recording goes as the whole prompt, with no text around it - which is what
// Voxtral wants (see the README): it takes a recording as the question and answers it.
//
// The microphone is JRock's to choose, as it is for Ctrl+Space in JRock's own window.
// None set is a message here, not a dialog: press Ctrl+Space in JRock once to list the
// microphones into Configure, pick one, and hold the button again.

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class JRockPushToTalk {

    // Starting a JVM's worth of Swing and fetching the model list, on a cold machine.
    private static final long READY_TIMEOUT_MS = 120_000;

    // Letting go to the recording being in the prompt: the tail and a file write for an
    // @audio token, and for a transcription as long as Windows takes to hear it, which
    // for a minute of speech is several seconds.
    private static final long RECORDING_TIMEOUT_MS = 120_000;

    // A spoken question gets a spoken-length answer, but a reasoning model can think for
    // a while first. The failure this guards against is a reply that never comes.
    private static final long REPLY_TIMEOUT_MS = 10 * 60_000;

    // The include tokens as they stand in a sent message ("@audio 1f3a9c0b7e42"), shown
    // in this window as what they were rather than as twelve hex digits.
    private static final java.util.regex.Pattern TOKEN =
            java.util.regex.Pattern.compile("@(audio|img|txt)\\s+[0-9a-f]{12}");

    // Why a step failed, in one sentence (see check).
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        Stop(String why) { super(why); }
    }

    // Every automation API call blocks and must not be made on the event dispatch
    // thread, and they must not overlap: one thread, taking the button's presses and
    // releases in the order they came. A daemon, so closing JRock's window ends the
    // process as it always does.
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jrock-ptt");
                t.setDaemon(true);
                return t;
            });

    // Where a turn is. Read and written on the EDT only, which is where presses and
    // releases arrive: a press is taken only when IDLE, a release only when RECORDING.
    private enum State { IDLE, RECORDING, ANSWERING }
    private static State state = State.IDLE;

    // Whether the recording this turn asked for actually started. Written and read on
    // the worker only, so the release's task can tell a turn that never began.
    private static boolean recordingStarted;

    private static javax.swing.JFrame window;
    private static TalkButton button;
    private static javax.swing.JLabel status;
    private static javax.swing.JTextArea transcript;

    public static void main(String[] args) {
        try {
            // JRock's own flags go straight through. A bare argument would be a prompt
            // file JRock loads - harmless, since the prompt is cleared a line later.
            JRock.main(args);
            check(JRock.automationAwaitReady(READY_TIMEOUT_MS));
            check(JRock.automationBegin("push-to-talk agent"));
            check(JRock.automationClearPrompt());
        } catch (Stop stop) {
            JRock.automationEnd("stopped - " + stop.getMessage());
            System.out.println("Stopped: " + stop.getMessage());
            onEdt(() -> javax.swing.JOptionPane.showMessageDialog(JRock.automationWindow(),
                    "The push-to-talk agent could not start:\n\n" + stop.getMessage(),
                    "Push-to-talk", javax.swing.JOptionPane.ERROR_MESSAGE));
            return;
        }
        onEdt(JRockPushToTalk::showWindow);
        System.out.println("Push-to-talk is ready: hold the button, or Ctrl, and speak.");
    }

    // ---- The turn ----------------------------------------------------------
    // Button (or Ctrl) down. On the EDT.
    private static void press() {
        if (state != State.IDLE) return;
        state = State.RECORDING;
        button.repaint();
        status.setText("Listening... let go to send.");
        WORKER.submit(() -> {
            recordingStarted = false;
            try {
                check(JRock.automationClearPrompt());
                check(JRock.automationStartRecording());
                recordingStarted = true;
            } catch (Stop stop) {
                later(() -> {
                    // Still held, or already let go of: either way this turn is over.
                    state = State.IDLE;
                    button.repaint();
                    status.setText("Not recording: " + stop.getMessage());
                });
            }
        });
    }

    // Button (or Ctrl) up, or this window left while it was held. On the EDT.
    private static void release() {
        if (state != State.RECORDING) return;
        state = State.ANSWERING;
        button.repaint();
        status.setText("Waiting for the recording...");
        WORKER.submit(() -> {
            // Queued behind the press's task, so this knows how that one went.
            if (!recordingStarted) return;   // the press's task has said why
            String outcome;
            try {
                check(JRock.automationStopRecording(RECORDING_TIMEOUT_MS));
                later(() -> status.setText("Sent - waiting for the reply..."));
                String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
                if (!"1".equals(sent[0])) throw new Stop(sent[3]);
                String asked = read(JRock.automationMessageFile("operator", sent[1]));
                String answer = read(JRock.automationMessageFile("assistant", sent[2]));
                if (answer == null) throw new Stop("the reply was not written to JRock/messages/.");
                later(() -> print(asked, answer));
                outcome = "Ready - hold the button, or Ctrl, to talk.";
            } catch (Stop stop) {
                outcome = "That turn failed: " + stop.getMessage();
            }
            String said = outcome;
            later(() -> {
                state = State.IDLE;
                button.repaint();
                status.setText(said);
            });
        });
    }

    // One exchange, at the end of this window's transcript.
    private static void print(String asked, String answer) {
        StringBuilder turn = new StringBuilder();
        if (transcript.getDocument().getLength() > 0) turn.append("\n\n");
        if (asked != null) {
            String shown = TOKEN.matcher(asked.trim()).replaceAll("(your $1)");
            turn.append("You: ").append(shown).append("\n\n");
        }
        turn.append("JRock: ").append(answer.trim());
        transcript.append(turn.toString());
        transcript.setCaretPosition(transcript.getDocument().getLength());
    }

    // ---- The window --------------------------------------------------------
    private static void showWindow() {
        window = new javax.swing.JFrame("JRock push-to-talk");
        // Closing this window ends the agent, not JRock: the window is handed back with
        // everything that was said in its transcript.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                WORKER.submit(() -> JRock.automationEnd("push-to-talk agent closed"));
            }
        });
        // Leaving the window with Ctrl held is a release this window never sees.
        window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                release();
            }
        });

        button = new TalkButton();
        status = new javax.swing.JLabel("Ready - hold the button, or Ctrl, to talk.");
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 8, 4, 8));
        transcript = new javax.swing.JTextArea(14, 40);
        transcript.setEditable(false);
        transcript.setLineWrap(true);
        transcript.setWrapStyleWord(true);
        transcript.setFocusable(false);   // Ctrl stays the window's, never a text field's

        javax.swing.JPanel top = new javax.swing.JPanel(new java.awt.BorderLayout());
        top.add(button, java.awt.BorderLayout.CENTER);
        top.add(status, java.awt.BorderLayout.SOUTH);
        window.getContentPane().add(top, java.awt.BorderLayout.NORTH);
        window.getContentPane().add(new javax.swing.JScrollPane(transcript),
                java.awt.BorderLayout.CENTER);

        // Ctrl, held, as the keyboard's button - in this window only, so Ctrl+C in
        // JRock's is still a copy. The auto-repeat of a held key comes as more presses,
        // which press() ignores for as long as a turn is under way.
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    if (e.getKeyCode() != java.awt.event.KeyEvent.VK_CONTROL
                            || e.getComponent() == null) return false;
                    java.awt.Window from = (e.getComponent() instanceof java.awt.Window)
                            ? (java.awt.Window) e.getComponent()
                            : javax.swing.SwingUtilities.getWindowAncestor(e.getComponent());
                    if (from != window) return false;
                    if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) press();
                    if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) release();
                    return false;
                });

        window.pack();
        // Beside JRock's window rather than on top of it, so both can be watched.
        java.awt.Window jrock = JRock.automationWindow();
        if (jrock != null) {
            java.awt.Rectangle at = jrock.getBounds();
            window.setLocation(Math.max(0, at.x - window.getWidth()), at.y);
        } else {
            window.setLocationRelativeTo(null);
        }
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
    }

    // The push-to-talk button: a circle, grey when idle, red while recording, amber
    // while the answer is on its way. Held with the mouse; let go of anywhere - dragging
    // off it before letting go still sends, as a walkie-talkie's button would.
    private static final class TalkButton extends javax.swing.JComponent {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private static final int SIZE = 140;

        TalkButton() {
            setPreferredSize(new java.awt.Dimension(SIZE + 40, SIZE + 40));
            setFocusable(false);   // Ctrl goes to the window, not a button
            setToolTipText("Hold to talk (or hold Ctrl)");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e) && inside(e)) press();
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) release();
                }
            });
        }

        private boolean inside(java.awt.event.MouseEvent e) {
            double dx = e.getX() - getWidth() / 2.0, dy = e.getY() - getHeight() / 2.0;
            return dx * dx + dy * dy <= (SIZE / 2.0) * (SIZE / 2.0);
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int x = (getWidth() - SIZE) / 2, y = (getHeight() - SIZE) / 2;
            java.awt.Color fill = state == State.RECORDING ? new java.awt.Color(0xD32F2F)
                    : state == State.ANSWERING ? new java.awt.Color(0xF9A825)
                    : new java.awt.Color(0x607D8B);
            g2.setColor(fill);
            g2.fillOval(x, y, SIZE, SIZE);
            g2.setColor(fill.darker());
            g2.setStroke(new java.awt.BasicStroke(4f));
            g2.drawOval(x + 2, y + 2, SIZE - 4, SIZE - 4);
            String label = state == State.RECORDING ? "Listening"
                    : state == State.ANSWERING ? "Thinking" : "Hold to talk";
            g2.setColor(java.awt.Color.WHITE);
            g2.setFont(getFont().deriveFont(java.awt.Font.BOLD, 16f));
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(label, (getWidth() - fm.stringWidth(label)) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    // ---- Plumbing ----------------------------------------------------------
    // Turns an automation API result into a stop, a null meaning there is nothing wrong.
    private static void check(String problem) {
        if (problem != null) throw new Stop(problem);
    }

    // A message file's text, or null when there is none to read.
    private static String read(String file) {
        if (file == null) return null;
        try {
            return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new Stop("could not read " + file + ": " + ex.getMessage());
        }
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
