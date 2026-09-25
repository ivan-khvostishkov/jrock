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
//   4. As soon as the prompt has it, the agent presses Send - which empties the answer
//      area in its own window - waits for the reply, and shows it there. Only the last
//      answer: the conversation so far is in JRock's window already.
//   5. And JRock reads the whole answer aloud, on its Configure > Narrate on device,
//      while the button is already ready for the next turn. Pressing it stops the
//      voice, so the microphone does not hear the last answer as the next question.
//
// Hands-free, for a conversation with both hands off the keyboard and the mouse: the
// lever under the button (or Ctrl+Shift) hands the listening to JRock's own Mic always
// on. JRock mutes that for as long as an automation runs - nobody types into the prompt
// then, so nobody speaks into it either - and the lever lifts the mute
// (JRock.automationListen). From then on JRock hears when someone starts talking and
// puts what was said in the prompt once it is quiet again; the agent waits for that
// (JRock.automationAwaitHeard), sends it, shows and reads out the answer, and listens
// again - a spoken conversation, turn after turn. Mic always on has to be ticked in
// JRock for it; the agent says so if it is not. The lever off, the button, or Ctrl on
// its own ends it and mutes the microphone again, and what was being said is not sent.
//
// While a request is on its way the microphone is muted, and while the answer is read
// aloud JRock does not listen either, so on speakers it does not hear its own voice. The
// next turn starts when the reading is over - or at once, when the button stops it.
//
// Each turn is a question of its own: the prompt is cleared before every recording, and
// an automation runs with History off, so the model hears one recording at a time and
// not the conversation so far. The whole conversation is in JRock's transcript all the
// same, and closing this window hands JRock back to you with it.
//
// The recording goes as the whole prompt, with no text around it - which is what
// Voxtral wants (see the README): it takes a recording as the question and answers it.
//
// The microphone and the speaker are JRock's to choose, as they are for Ctrl+Space and
// Narrate in JRock's own window. Either one not set is a message here, not a dialog:
// press Ctrl+Space (or Narrate) in JRock once to list the devices into Configure, pick
// one, and carry on. A narration that cannot play is not a failed turn - the answer is
// on the screen all the same.

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

    // What the status line says between turns, and while listening, lever off and on.
    // No middle dots as separators: next to "Ctrl" one reads as a full stop.
    private static final String READY =
            "Hold to talk, or hold Ctrl. Ctrl+Shift for hands-free.";
    private static final String LISTENING_HELD = "Listening\u2026  let go to send";
    private static final String LISTENING_HANDS_FREE =
            "Hands-free: just talk, it sends when you stop. Ctrl or the button ends it.";

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

    // How long one wait for speech lasts before the conversation loop looks at the lever
    // again. Not a limit on anyone talking: a recording under way is waited out.
    private static final long LISTEN_POLL_MS = 250;

    // The lever: on, JRock listens and the agent converses (see converse). Written on
    // the EDT, read by the worker's loop too. And, EDT only: whether that loop is
    // running, and whether Ctrl and Shift are down, so their auto-repeat is not taken
    // for another press.
    private static volatile boolean handsFree;
    private static boolean conversing;
    private static boolean ctrlHeld;
    private static boolean shiftHeld;

    private static javax.swing.JFrame window;
    private static TalkButton button;
    private static HandsFreeLever lever;
    private static javax.swing.JLabel status;
    private static javax.swing.JTextPane answerArea;

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
        System.out.println("Push-to-talk is ready: hold the button, or Ctrl, and speak "
                + "(Ctrl+Shift for hands-free).");
    }

    // ---- The turn ----------------------------------------------------------
    // Button (or Ctrl) down. On the EDT.
    private static void press() {
        if (state != State.IDLE) return;
        setState(State.RECORDING, LISTENING_HELD);
        WORKER.submit(() -> {
            recordingStarted = false;
            try {
                JRock.automationStopNarration();   // the voice, not the question
                check(JRock.automationClearPrompt());
                check(JRock.automationStartRecording());
                recordingStarted = true;
            } catch (Stop stop) {
                // Still held, or already let go of: either way this turn is over.
                later(() -> setState(State.IDLE, "Not recording: " + stop.getMessage()));
            }
        });
    }

    // The button or Ctrl let go of. Sends what was recorded. On the EDT.
    private static void release() {
        if (state != State.RECORDING || conversing) return;
        setState(State.ANSWERING, "Sending\u2026");
        WORKER.submit(() -> {
            // Queued behind the press's task, so this knows how that one went.
            if (!recordingStarted) return;   // the press's task has said why
            String outcome;
            try {
                check(JRock.automationStopRecording(RECORDING_TIMEOUT_MS));
                recordingStarted = false;
                outcome = answer();
            } catch (Stop stop) {
                outcome = "That turn failed: " + stop.getMessage();
            }
            String said = outcome;
            later(() -> {
                setState(State.IDLE, said == null ? READY : said);
                if (handsFree) converseNow();   // the lever went on during the answer
            });
        });
    }

    // Sends the prompt as it stands, shows the reply in place of the last one and reads
    // it aloud, while the button is free again. On the worker. Returns null, or what to
    // say about a narration that could not play - the turn has succeeded all the same.
    private static String answer() {
        later(() -> {
            answerArea.setText("");
            setState(State.ANSWERING, "Waiting for the reply\u2026");
        });
        String[] sent = JRock.automationSend(REPLY_TIMEOUT_MS);
        if (!"1".equals(sent[0])) throw new Stop(sent[3]);
        String answer = read(JRock.automationMessageFile("assistant", sent[2]));
        if (answer == null) throw new Stop("the reply was not written to JRock/messages/.");
        later(() -> showAnswer(answer));
        String silent = JRock.automationNarrate(answer);
        return silent == null ? null : "Not read aloud: " + silent;
    }

    // The button or Ctrl let go of, or this window left: the end of a turn only when it
    // is being held - hands-free, nothing is.
    private static void releaseHeld() {
        if (!handsFree) release();
    }

    // The lever moved - clicked, or Ctrl+Shift, or Ctrl alone. On, the conversation
    // starts - at once, or after the answer on its way. Off, it ends: the microphone is
    // muted from a thread of its own, since the worker is inside the loop and the EDT
    // must not call JRock, and that is what brings the loop's wait back.
    private static void setHandsFree(boolean on) {
        if (on == handsFree) return;
        handsFree = on;
        lever.repaint();
        if (on) {
            if (state != State.ANSWERING) converseNow();
        } else if (conversing) {
            Thread mute = new Thread(() -> JRock.automationListen(false), "jrock-ptt-mute");
            mute.setDaemon(true);
            mute.start();
        }
    }

    // Ctrl+Shift, in either order: the lever on.
    private static void lockOn() {
        setHandsFree(true);
    }

    // Starts the conversation loop, unless it is running already. On the EDT.
    private static void converseNow() {
        if (conversing) return;
        conversing = true;
        setState(State.RECORDING, LISTENING_HANDS_FREE);
        WORKER.submit(JRockPushToTalk::converse);
    }

    // Hands-free: JRock listens, the agent sends what it heard, and round again - until
    // the lever goes off, or a turn fails. On the worker.
    private static void converse() {
        String said = null;
        try {
            // Ctrl, then Shift: the Ctrl started a held turn, which the lever replaces.
            if (recordingStarted) {
                recordingStarted = false;
                JRock.automationStopRecording(RECORDING_TIMEOUT_MS);
            }
            String note = null;
            while (handsFree) {
                check(JRock.automationClearPrompt());
                check(JRock.automationListen(true));
                String listening = note == null ? LISTENING_HANDS_FREE
                        : note + " " + LISTENING_HANDS_FREE;
                later(() -> setState(State.RECORDING, listening));
                String[] heard;
                do {
                    heard = JRock.automationAwaitHeard(LISTEN_POLL_MS);
                } while (handsFree && !"1".equals(heard[0]) && heard[1] == null);
                // Muted while the answer is on its way, so the question is not asked twice.
                JRock.automationListen(false);
                if (!handsFree) break;   // ended: what was being said is not sent
                if (!"1".equals(heard[0])) throw new Stop(heard[1]);
                note = answer();
            }
        } catch (Stop stop) {
            JRock.automationListen(false);
            said = "Hands-free ended: " + stop.getMessage();
        }
        String reason = said;
        later(() -> {
            conversing = false;
            if (reason != null) {
                handsFree = false;
                lever.repaint();
            }
            setState(State.IDLE, reason == null ? READY : reason);
            if (handsFree) converseNow();   // turned off and on again in the meantime
        });
    }

    private static void setState(State next, String message) {
        state = next;
        button.stateChanged();
        status.setText(wrap(message));
    }

    // The answer to the last question, and nothing else: the whole conversation is in
    // JRock's own window. From the top, where reading starts.
    private static void showAnswer(String answer) {
        javax.swing.text.StyledDocument doc = answerArea.getStyledDocument();
        answerArea.setText("");
        try {
            doc.insertString(0, answer.trim(), answerArea.getStyle(ANSWER));
        } catch (javax.swing.text.BadLocationException impossible) {
            // Offset 0 of an empty document.
        }
        answerArea.setCaretPosition(0);
    }

    // ---- The look ----------------------------------------------------------
    // One quiet palette: a pale background, white paper for the answer, slate for the
    // button at rest (and the lever on), red while it listens, amber while the answer
    // is on its way.
    private static final java.awt.Color BACKGROUND = new java.awt.Color(0xF4F5F7);
    private static final java.awt.Color PAPER = java.awt.Color.WHITE;
    private static final java.awt.Color HAIRLINE = new java.awt.Color(0xDCDFE4);
    private static final java.awt.Color INK = new java.awt.Color(0x1F2328);
    private static final java.awt.Color MUTED = new java.awt.Color(0x6B7280);
    private static final java.awt.Color IDLE = new java.awt.Color(0x37474F);
    private static final java.awt.Color IDLE_HOVER = new java.awt.Color(0x455A64);
    private static final java.awt.Color LISTENING = new java.awt.Color(0xE53935);
    private static final java.awt.Color ANSWERING = new java.awt.Color(0xF59E0B);
    private static final java.awt.Color LEVER_OFF = new java.awt.Color(0xC4C9D1);

    private static final String ANSWER = "answer";

    // The status line wraps rather than widening the window for a long reason.
    private static String wrap(String text) {
        String safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='text-align:center;width:300px'>" + safe + "</div></html>";
    }

    // ---- The window --------------------------------------------------------
    private static void showWindow() {
        window = new javax.swing.JFrame("JRock - Push to talk");
        // Closing this window ends the agent, not JRock: the window is handed back with
        // everything that was said in its transcript.
        window.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        window.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) {
                setHandsFree(false);
                WORKER.submit(() -> {
                    JRock.automationStopNarration();
                    JRock.automationEnd("push-to-talk agent closed");
                });
            }
        });
        // Leaving the window with Ctrl held is a release this window never sees. With the
        // lever on nothing is being held, so the conversation goes on wherever the
        // focus goes.
        window.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) {
                ctrlHeld = false;
                shiftHeld = false;
                releaseHeld();
            }
        });

        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 13);

        button = new TalkButton();
        button.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        status = new javax.swing.JLabel(wrap(READY), javax.swing.SwingConstants.CENTER);
        status.setFont(base.deriveFont(java.awt.Font.PLAIN, 12.5f));
        status.setForeground(MUTED);
        status.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        status.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 0, 18, 0));

        lever = new HandsFreeLever(base.deriveFont(java.awt.Font.PLAIN, 12.5f));
        lever.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        javax.swing.JPanel top = new javax.swing.JPanel();
        top.setOpaque(false);
        top.setLayout(new javax.swing.BoxLayout(top, javax.swing.BoxLayout.Y_AXIS));
        top.add(button);
        top.add(javax.swing.Box.createVerticalStrut(8));
        top.add(lever);
        top.add(status);

        answerArea = new javax.swing.JTextPane();
        answerArea.setEditable(false);
        answerArea.setFocusable(false);   // Ctrl stays the window's, never a text field's
        answerArea.setBackground(PAPER);
        answerArea.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        javax.swing.text.Style answer = answerArea.addStyle(ANSWER, null);
        javax.swing.text.StyleConstants.setFontFamily(answer, base.getFamily());
        javax.swing.text.StyleConstants.setFontSize(answer, 13);
        javax.swing.text.StyleConstants.setForeground(answer, INK);
        javax.swing.text.StyleConstants.setLineSpacing(answer, 0.15f);

        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(answerArea,
                javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(new RoundedBorder(HAIRLINE, 10));
        scroll.getViewport().setBackground(PAPER);
        scroll.setBackground(PAPER);
        scroll.setPreferredSize(new java.awt.Dimension(380, 280));

        javax.swing.JPanel content = new javax.swing.JPanel(new java.awt.BorderLayout());
        content.setBackground(BACKGROUND);
        content.setBorder(javax.swing.BorderFactory.createEmptyBorder(24, 20, 20, 20));
        content.add(top, java.awt.BorderLayout.NORTH);
        content.add(scroll, java.awt.BorderLayout.CENTER);
        window.setContentPane(content);

        // Ctrl, held, as the keyboard's button - in this window only, so Ctrl+C in
        // JRock's is still a copy. Shift with it, in either order, is the lever on; Ctrl
        // alone with the lever on turns it off, and nothing is sent. The auto-repeat
        // of a held key comes as more presses, and only the first one counts.
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(e -> {
                    int key = e.getKeyCode();
                    if ((key != java.awt.event.KeyEvent.VK_CONTROL
                            && key != java.awt.event.KeyEvent.VK_SHIFT)
                            || e.getComponent() == null) return false;
                    java.awt.Window from = (e.getComponent() instanceof java.awt.Window)
                            ? (java.awt.Window) e.getComponent()
                            : javax.swing.SwingUtilities.getWindowAncestor(e.getComponent());
                    if (from != window) return false;
                    if (e.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                        if (key == java.awt.event.KeyEvent.VK_SHIFT) {
                            if (!shiftHeld && ctrlHeld) lockOn();   // Ctrl, then Shift
                            shiftHeld = true;
                        } else if (!ctrlHeld) {
                            ctrlHeld = true;
                            if (e.isShiftDown()) lockOn();     // Shift, then Ctrl
                            else if (handsFree) setHandsFree(false);
                            else press();
                        }
                    } else if (e.getID() == java.awt.event.KeyEvent.KEY_RELEASED) {
                        if (key == java.awt.event.KeyEvent.VK_SHIFT) {
                            shiftHeld = false;
                        } else {
                            ctrlHeld = false;
                            releaseHeld();
                        }
                    }
                    return false;
                });

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
        window.toFront();
        window.requestFocus();
    }

    // A one-pixel hairline with rounded corners, the paper inside it clipped to match.
    private static final class RoundedBorder extends javax.swing.border.AbstractBorder {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private final java.awt.Color color;
        private final int radius;

        RoundedBorder(java.awt.Color color, int radius) {
            this.color = color;
            this.radius = radius;
        }

        @Override public void paintBorder(java.awt.Component c, java.awt.Graphics g,
                                          int x, int y, int w, int h) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            // The corners outside the curve are the window's background, not paper.
            java.awt.geom.Area corners = new java.awt.geom.Area(
                    new java.awt.Rectangle(x, y, w, h));
            corners.subtract(new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Float(
                    x, y, w - 1, h - 1, radius * 2, radius * 2)));
            g2.setColor(BACKGROUND);
            g2.fill(corners);
            g2.setColor(color);
            g2.draw(new java.awt.geom.RoundRectangle2D.Float(
                    x, y, w - 1, h - 1, radius * 2, radius * 2));
            g2.dispose();
        }

        @Override public java.awt.Insets getBorderInsets(java.awt.Component c) {
            int inset = radius / 2 + 1;
            return new java.awt.Insets(inset, inset, inset, inset);
        }
    }

    // The push-to-talk button: a flat disc with a microphone drawn on it. Slate at rest
    // (a shade lighter under the mouse), red while listening with a soft ring pulsing
    // out from it, amber while the answer is on its way. Held with the mouse; let go of
    // anywhere - dragging off it before letting go still sends, as a walkie-talkie's
    // button would. With the lever on, JRock listens by itself, and a click ends that.
    private static final class TalkButton extends javax.swing.JComponent {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private static final int SIZE = 112;
        private static final int HALO = 22;

        private boolean hover;
        private float pulse;   // 0..1, the ring's way out while listening
        private final javax.swing.Timer ticker = new javax.swing.Timer(33, e -> {
            pulse = (pulse + 0.03f) % 1f;
            repaint();
        });

        TalkButton() {
            setPreferredSize(new java.awt.Dimension(SIZE + 2 * HALO, SIZE + 2 * HALO));
            setMaximumSize(getPreferredSize());
            setFocusable(false);   // Ctrl goes to the window, not a button
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (!javax.swing.SwingUtilities.isLeftMouseButton(e) || !inside(e)) return;
                    if (handsFree) setHandsFree(false);
                    else press();
                }
                @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) releaseHeld();
                }
                @Override public void mouseExited(java.awt.event.MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
                @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                    boolean now = inside(e);
                    if (now != hover) { hover = now; repaint(); }
                }
            });
        }

        void stateChanged() {
            if (state == State.RECORDING) {
                pulse = 0f;
                ticker.start();
            } else {
                ticker.stop();
            }
            repaint();
        }

        private boolean inside(java.awt.event.MouseEvent e) {
            double dx = e.getX() - getWidth() / 2.0, dy = e.getY() - getHeight() / 2.0;
            return dx * dx + dy * dy <= (SIZE / 2.0) * (SIZE / 2.0);
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
                    java.awt.RenderingHints.VALUE_STROKE_PURE);
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            java.awt.Color fill = state == State.RECORDING ? LISTENING
                    : state == State.ANSWERING ? ANSWERING
                    : hover ? IDLE_HOVER : IDLE;

            // The ring, only while listening: out from the disc, fading as it goes.
            if (state == State.RECORDING) {
                double r = SIZE / 2.0 + HALO * pulse;
                int alpha = (int) (110 * (1f - pulse));
                g2.setColor(new java.awt.Color(fill.getRed(), fill.getGreen(), fill.getBlue(),
                        alpha));
                g2.fill(new java.awt.geom.Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            }

            // A faint shadow under the disc, then the disc.
            g2.setColor(new java.awt.Color(0, 0, 0, 28));
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx - SIZE / 2.0, cy - SIZE / 2.0 + 3,
                    SIZE, SIZE));
            g2.setColor(fill);
            g2.fill(new java.awt.geom.Ellipse2D.Double(cx - SIZE / 2.0, cy - SIZE / 2.0,
                    SIZE, SIZE));

            // The microphone: a capsule, the cradle under it, and its stand.
            g2.setColor(java.awt.Color.WHITE);
            double w = SIZE * 0.16, h = SIZE * 0.30;
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(cx - w / 2, cy - h * 0.72,
                    w, h, w, w));
            g2.setStroke(new java.awt.BasicStroke((float) (SIZE * 0.035),
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            double arc = SIZE * 0.27;
            g2.draw(new java.awt.geom.Arc2D.Double(cx - arc / 2, cy - h * 0.72 + h - arc / 2 - 2,
                    arc, arc * 0.9, 180, 180, java.awt.geom.Arc2D.OPEN));
            double base = cy - h * 0.72 + h - arc / 2 - 2 + arc * 0.9;
            g2.draw(new java.awt.geom.Line2D.Double(cx, base, cx, base + SIZE * 0.08));
            g2.draw(new java.awt.geom.Line2D.Double(cx - SIZE * 0.08, base + SIZE * 0.08,
                    cx + SIZE * 0.08, base + SIZE * 0.08));
            g2.dispose();
        }
    }

    // The hands-free lever: a switch - a pill with a knob, grey and to the left when off,
    // slate and to the right when on - with its name beside it. Anywhere on it toggles
    // it. The lock is drawn on the knob when it is on, since that is what it does to the
    // button.
    private static final class HandsFreeLever extends javax.swing.JComponent {
        private static final long serialVersionUID = 1L;   // never leaves this JVM
        private static final int TRACK_W = 38, TRACK_H = 22, GAP = 9;
        private static final String LABEL = "Hands-free (Ctrl+Shift)";

        HandsFreeLever(java.awt.Font font) {
            setFont(font);
            java.awt.FontMetrics fm = getFontMetrics(font);
            java.awt.Dimension size = new java.awt.Dimension(
                    TRACK_W + GAP + fm.stringWidth(LABEL), Math.max(TRACK_H, fm.getHeight()) + 2);
            setPreferredSize(size);
            setMaximumSize(size);
            setFocusable(false);   // Ctrl goes to the window, not the lever
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            setToolTipText("On: just talk - JRock hears you, and the agent sends what you "
                    + "said once you stop. Needs Mic always on ticked in JRock.");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e)) setHandsFree(!handsFree);
                }
            });
        }

        @Override protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double y = (getHeight() - TRACK_H) / 2.0;
            g2.setColor(handsFree ? IDLE : LEVER_OFF);
            g2.fill(new java.awt.geom.RoundRectangle2D.Double(0, y, TRACK_W, TRACK_H,
                    TRACK_H, TRACK_H));
            double knob = TRACK_H - 6;
            double kx = handsFree ? TRACK_W - 3 - knob : 3;
            g2.setColor(java.awt.Color.WHITE);
            g2.fill(new java.awt.geom.Ellipse2D.Double(kx, y + 3, knob, knob));
            if (handsFree) {
                // A padlock, small: the shackle, then the body.
                double cx = kx + knob / 2, cy = y + 3 + knob / 2;
                g2.setColor(IDLE);
                g2.setStroke(new java.awt.BasicStroke(1.4f));
                g2.draw(new java.awt.geom.Arc2D.Double(cx - 2.6, cy - 5, 5.2, 6, 0, 180,
                        java.awt.geom.Arc2D.OPEN));
                g2.fill(new java.awt.geom.RoundRectangle2D.Double(cx - 3.8, cy - 1.5, 7.6, 5.5,
                        1.5, 1.5));
            }
            g2.setFont(getFont());
            g2.setColor(handsFree ? INK : MUTED);
            java.awt.FontMetrics fm = g2.getFontMetrics();
            g2.drawString(LABEL, TRACK_W + GAP,
                    (float) ((getHeight() - fm.getHeight()) / 2.0 + fm.getAscent()));
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
