// JRock - minimal Swing app that calls Amazon Bedrock via the OpenAI-compatible
// Chat Completions API on the bedrock-mantle endpoint, authenticated with a
// lightweight Bedrock API key (no SigV4, no AWS SDK, no ~/.aws credentials).
//
// Run directly with: java JRock.java
//   Optionally: java JRock.java <initial-prompt-file>
//
// Prompt persistence (crash recovery):
//   The prompt text is autosaved to "jrock-prompt.txt" on every keystroke (full
//   rewrite via an atomic temp-file swap, so a crash can't corrupt it).
//   On startup the initial prompt is resolved as:
//     1. If a file path is passed as the first CLI arg, load it READ-ONLY and use
//        its contents; the original file is never modified. That text is then
//        written to the persistent file, which continues to receive autosaves.
//     2. Else if jrock-prompt.txt exists, recover the prompt from it.
//     3. Else use the built-in default prompt.
//
// Java version requirements:
//   Minimum: JDK 11  - single-file source launch (JEP 330) and java.net.http.HttpClient
//                       are both required. On JDK 8-10 this won't run as a single file.
//   Maximum: none    - only core JDK APIs (javax.swing, java.net.http). No external
//                       jars. Runs on the latest JDK.
//
// Endpoint / API:
//   bedrock-mantle is the newer endpoint surface (same underlying Mantle inference
//   engine as bedrock-runtime). It exposes the OpenAI-compatible Chat Completions
//   and Responses APIs plus the Anthropic Messages API, across a broad model
//   catalog (Claude, OpenAI-family, etc.). We use Chat Completions here
//   because it is the portable, multi-model surface: swap MODEL_ID to change model.
//     URL:  https://bedrock-mantle.{region}.api.aws/v1/chat/completions
//
// Authentication:
//   Bearer token = your Bedrock API key, read from the BEDROCK_API_KEY env var.
//   Generate one in the Bedrock console (Amazon Bedrock API keys).
//   Set it before launching, e.g. (PowerShell):
//     $env:BEDROCK_API_KEY = "..."
//     $env:AWS_REGION = "us-east-1"   # optional, defaults below

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.BadLocationException;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import javax.swing.undo.UndoManager;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

public class JRock {

    // ---- Configuration -----------------------------------------------------
    private static final String DEFAULT_REGION = "us-east-1";
    private static final boolean REGION_IS_DEFAULT =
            System.getenv("AWS_REGION") == null || System.getenv("AWS_REGION").isBlank();
    private static final String REGION = envOr("AWS_REGION", DEFAULT_REGION);
    private static final String MANTLE_HOST =
            "https://bedrock-mantle." + REGION + ".api.aws";
    private static final String OPENAI_BASE = MANTLE_HOST + "/openai/v1";
    private static final String ENDPOINT = OPENAI_BASE + "/chat/completions";
    private static final String MODELS_ENDPOINT = MANTLE_HOST + "/v1/models";
    private static final String MODEL_ID = "xai.grok-4.3";
    private static final String PROMPT = "Hello world";
    // Persistent prompt file (crash recovery). Name contains "jrock" and ends .txt.
    private static final Path PROMPT_FILE = Paths.get("jrock-prompt.txt");

    // ---- UI ----------------------------------------------------------------
    public static void main(String[] args) {
        // Optional first arg: a file to load the initial prompt from (read-only).
        String sourceArg = (args.length > 0 && !args[0].isBlank()) ? args[0].trim() : null;
        SwingUtilities.invokeLater(() -> createAndShowGui(sourceArg));
    }

    // Wires undo/redo into a text component: Ctrl+Z undo, Ctrl+Y (and Ctrl+Shift+Z)
    // redo. JTextArea has no built-in undo, so we attach an UndoManager to its
    // document and bind the keystrokes.
    private static void enableUndo(javax.swing.text.JTextComponent comp) {
        UndoManager undo = new UndoManager();
        comp.getDocument().addUndoableEditListener(e -> undo.addEdit(e.getEdit()));

        comp.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        comp.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        comp.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_Z,
                        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redo");

        comp.getActionMap().put("undo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (undo.canUndo()) undo.undo();
            }
        });
        comp.getActionMap().put("redo", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (undo.canRedo()) undo.redo();
            }
        });
    }

    // JRock-branded color for the role headers ([HUMAN OPERATOR] / assistant).
    private static final java.awt.Color BRAND = new java.awt.Color(0x0F, 0x8B, 0x8D); // teal

    // Roles for dialog messages. The label is what shows as the teal header AND
    // is used to build the per-message filename in logs/.
    private static final String ROLE_HUMAN = "HUMAN OPERATOR";
    private static final String ROLE_ASSISTANT = "OPERATOR'S ASSISTANT";

    // Filesystem layout for persistence.
    private static final Path LOG_FILE = Paths.get("jrock-log.txt");     // main, rewritten atomically
    private static final Path LOGS_DIR = Paths.get("logs");              // per-message files (append-only)
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    // A styled, persisted log backed by an ordered list of entries so it can be
    // re-rendered on demand and rewritten to disk after every change.
    //
    //   GRAY   entry -> app/system text (gray). Stored INLINE in the main log.
    //   DIALOG entry -> a human or assistant message. Rendered as a teal header
    //                   + black body + blank line. The body is stored in its OWN
    //                   file under logs/ (append-only, never modified); the main
    //                   log stores only a reference: the header line + "@<stamp>".
    //
    // "Dialog only" mode hides GRAY entries, leaving a clean transcript of just
    // the headers + dialog, which matches `cat logs/*.txt`.
    private static final class LogView {
        private static final class Entry {
            final boolean dialog;      // true = dialog message, false = gray inline
            final String role;         // dialog: ROLE_* ; gray: null
            final String stamp;        // dialog: yyyyMMdd-HHmmss-SSS ; gray: null
            final String text;         // body text (dialog) or gray text
            Entry(boolean dialog, String role, String stamp, String text) {
                this.dialog = dialog; this.role = role; this.stamp = stamp; this.text = text;
            }
        }

        private final JTextPane pane;
        private final java.util.List<Entry> entries = new ArrayList<>();
        private boolean dialogOnly = false;

        LogView(JTextPane pane) { this.pane = pane; }

        // ---- Public logging API --------------------------------------------
        void gray(String line) {
            SwingUtilities.invokeLater(() -> {
                Entry e = new Entry(false, null, null, line);
                entries.add(e);
                if (isVisible(e)) renderGray(e.text);
                persistMainLog();
            });
        }

        void human(String text)     { dialog(ROLE_HUMAN, text); }
        void assistant(String text) { dialog(ROLE_ASSISTANT, text); }

        private void dialog(String role, String text) {
            SwingUtilities.invokeLater(() -> {
                String stamp = LocalDateTime.now().format(STAMP_FMT);
                Entry e = new Entry(true, role, stamp, text);
                entries.add(e);
                // Write the message body to its own append-only file in logs/.
                writeMessageFile(role, stamp, text);
                renderDialog(e);        // dialog is always visible
                persistMainLog();
            });
        }

        void setDialogOnly(boolean on) {
            SwingUtilities.invokeLater(() -> { dialogOnly = on; rebuild(); });
        }

        // Clears the on-screen log AND the main log file, but never touches logs/.
        void clear() {
            SwingUtilities.invokeLater(() -> {
                entries.clear();
                pane.setText("");
                atomicWriteQuietly(LOG_FILE, "");  // overwrite main log with empty
            });
        }

        // Returns the dialog turns so far, in order, as {role, text} pairs
        // (role is ROLE_HUMAN or ROLE_ASSISTANT). Used to build a multi-turn
        // request in "append" mode. Gray entries are excluded.
        java.util.List<String[]> dialogHistory() {
            java.util.List<String[]> out = new ArrayList<>();
            for (Entry e : entries) {
                if (e.dialog) out.add(new String[] { e.role, e.text });
            }
            return out;
        }

        // Loads and renders entries parsed from the main log file. Does NOT
        // rewrite the file (loading shouldn't trigger a save). Returns the number
        // of entries loaded.
        int loadFromDisk() {
            java.util.List<Entry> loaded = parseMainLog();
            SwingUtilities.invokeLater(() -> {
                entries.clear();
                entries.addAll(loaded);
                rebuild();
            });
            return loaded.size();
        }

        // Parses the main log. The file is a bit-perfect copy of the pane text,
        // with exactly ONE transformation: a role header line ("[HUMAN OPERATOR]"
        // / "[OPERATOR'S ASSISTANT]") is followed by an "@<stamp>" line that stands
        // in for the message body. On load we expand that single @<stamp> line back
        // to the message file's verbatim contents (like #include). Every other line
        // - including empty lines - is a plain gray line, reproduced as-is.
        private java.util.List<Entry> parseMainLog() {
            java.util.List<Entry> out = new ArrayList<>();
            String content = readFileQuietly(LOG_FILE);
            if (content == null) return out;
            String[] lines = content.split("\n", -1);
            // A non-empty file is written as a sequence of "<line>\n"; split(-1)
            // therefore ends with one artifact "" for that final newline. Reverse
            // it by dropping exactly that trailing element (nothing else).
            int n = lines.length;
            if (n > 0 && lines[n - 1].isEmpty()) n--;

            int i = 0;
            while (i < n) {
                String line = lines[i];
                String role = headerRole(line);
                if (role != null && i + 1 < n && lines[i + 1].startsWith("@")) {
                    String stamp = lines[i + 1].substring(1).trim();
                    String body = resolveReference(role, stamp);
                    if (body != null) {
                        // Header + expanded body. The blank line that follows in the
                        // file is a normal gray "" entry and is handled by the loop -
                        // we do NOT consume or synthesize any empty line here.
                        out.add(new Entry(true, role, stamp, body));
                        i += 2;
                        continue;
                    }
                    // Unresolvable reference: fall through and keep the header as a
                    // plain gray line so nothing is silently dropped.
                }
                out.add(new Entry(false, null, null, line));
                i++;
            }
            return out;
        }

        // Returns the role if the line is exactly a role header, else null.
        private String headerRole(String line) {
            if (line.equals("[" + ROLE_HUMAN + "]")) return ROLE_HUMAN;
            if (line.equals("[" + ROLE_ASSISTANT + "]")) return ROLE_ASSISTANT;
            return null;
        }

        // ---- Rendering -----------------------------------------------------
        private boolean isVisible(Entry e) { return !dialogOnly || e.dialog; }

        private void rebuild() {
            pane.setText("");
            for (Entry e : entries) {
                if (!isVisible(e)) continue;
                if (e.dialog) renderDialog(e); else renderGray(e.text);
            }
        }

        // Renders exactly two lines: the branded header and the (possibly
        // multi-line) body. The blank line after a message is NOT baked in here -
        // it is emitted separately as an explicit gray "" entry so it round-trips
        // like any other line. Nothing here adds or removes empty lines.
        private void renderDialog(Entry e) {
            appendStyled("[" + e.role + "]", BRAND);
            appendStyled(e.text, java.awt.Color.BLACK);
        }

        private void renderGray(String text) { appendStyled(text, java.awt.Color.GRAY); }

        private void appendStyled(String line, java.awt.Color color) {
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            try {
                pane.getStyledDocument().insertString(
                        pane.getStyledDocument().getLength(), line + "\n", attrs);
            } catch (BadLocationException ignored) {
                // Position is always valid (document end); ignore defensively.
            }
            pane.setCaretPosition(pane.getStyledDocument().getLength());
        }

        // ---- Persistence ---------------------------------------------------
        // Writes the main log as a bit-perfect copy of the pane, with exactly one
        // transformation: a dialog entry's body is collapsed to a single "@<stamp>"
        // line under its role header. Every entry (gray or dialog) contributes its
        // own "<line>\n"; no extra blank lines are added. The blank line seen after
        // a message is a separate gray "" entry, written like any other line.
        //   gray entry   -> "<text>\n"
        //   dialog entry -> "[<ROLE>]\n" + "@<stamp>\n"
        private void persistMainLog() {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                if (e.dialog) {
                    sb.append('[').append(e.role).append(']').append('\n');
                    sb.append('@').append(e.stamp).append('\n');
                } else {
                    sb.append(e.text).append('\n');
                }
            }
            atomicWriteQuietly(LOG_FILE, sb.toString());
        }
    }

    // Calls GET /v1/models on the mantle endpoint and returns a compact,
    // comma-separated list of model ids. Never throws - returns a status
    // string on failure so startup logging is best-effort.
    private static String listAvailableModels() {
        String apiKey = System.getenv("BEDROCK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "(skipped - BEDROCK_API_KEY env var not set)";
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODELS_ENDPOINT))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .GET()
                    .build();
            HttpResponse<String> resp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return "(HTTP " + resp.statusCode() + " from " + MODELS_ENDPOINT + ")";
            }
            List<String> ids = extractModelIds(resp.body());
            return ids.isEmpty() ? "(none parsed; raw: " + resp.body() + ")"
                    : String.join(", ", ids);
        } catch (Exception ex) {
            return "(error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")";
        }
    }

    // Pull every "id":"..." value from the OpenAI-shaped {"data":[{"id":...}]} list.
    private static List<String> extractModelIds(String json) {
        List<String> ids = new ArrayList<>();
        int i = 0;
        while (true) {
            int k = json.indexOf("\"id\"", i);
            if (k < 0) break;
            int colon = json.indexOf(':', k);
            int q1 = json.indexOf('"', colon + 1);
            int q2 = q1 < 0 ? -1 : json.indexOf('"', q1 + 1);
            if (q1 < 0 || q2 < 0) break;
            ids.add(json.substring(q1 + 1, q2));
            i = q2 + 1;
        }
        return ids;
    }

    private static void createAndShowGui(String sourceArg) {
        JFrame frame = new JFrame("JRock - Bedrock (mantle)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 460);
        frame.setLocationRelativeTo(null);

        JTextPane output = new JTextPane();
        output.setEditable(false);
        output.setMargin(new java.awt.Insets(8, 8, 8, 8));
        LogView log = new LogView(output);

        // Top bar: [Dialog only] checkbox on the left of a right-aligned [Clear log].
        javax.swing.JCheckBox dialogOnly = new javax.swing.JCheckBox("Dialog only");
        dialogOnly.setToolTipText("Show only the headers and dialog (hide gray system text)");
        dialogOnly.addActionListener(e -> log.setDialogOnly(dialogOnly.isSelected()));
        JButton clear = new JButton("Clear log");
        clear.addActionListener(e -> log.clear());

        javax.swing.JPanel topBar = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        topBar.add(dialogOnly);
        topBar.add(clear);

        // Resolve the initial prompt text:
        //   1. If a source file was given on the command line, load it read-only.
        //   2. Else if the persistent prompt file exists, load that.
        //   3. Else fall back to the built-in default.
        String initialPrompt;
        String promptSource;
        if (sourceArg != null) {
            String fromArg = readFileQuietly(Paths.get(sourceArg));
            if (fromArg != null) {
                initialPrompt = fromArg;
                promptSource = "command-line file (read-only): " + sourceArg;
            } else {
                initialPrompt = PROMPT;
                promptSource = "default (could not read " + sourceArg + ")";
            }
        } else {
            String fromPersist = readFileQuietly(PROMPT_FILE);
            if (fromPersist != null) {
                initialPrompt = fromPersist;
                promptSource = "recovered persistent file: " + PROMPT_FILE.getFileName();
            } else {
                initialPrompt = PROMPT;
                promptSource = "default";
            }
        }

        // Input area for the user's prompt.
        JTextArea input = new JTextArea(initialPrompt, 3, 20);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setMargin(new java.awt.Insets(8, 8, 8, 8));
        enableUndo(input);

        // Persist the initial text immediately (this also seeds/rewrites the
        // persistent file when loading from a command-line source), then autosave
        // on every document change so no keystroke can be lost to a crash.
        savePromptQuietly(input.getText());
        input.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { persist(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { persist(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { persist(); }
            private void persist() { savePromptQuietly(input.getText()); }
        });
        JScrollPane inputScroll = new JScrollPane(input);
        inputScroll.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6),
                javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY)));

        // Recover any previous log from disk BEFORE emitting startup messages, so
        // the restored history appears first, then the new session's messages.
        boolean hadLog = Files.exists(LOG_FILE);
        int restored = log.loadFromDisk();
        if (hadLog) {
            // Blank line to separate the restored history from this new session.
            if (restored > 0) log.gray("");
            log.gray("Loaded previous log from " + LOG_FILE);
        } else {
            log.gray("New log file created: " + LOG_FILE);
        }

        // Startup info goes to the text area (visible regardless of how the app
        // is launched), not the console.
        if (REGION_IS_DEFAULT) {
            log.gray("AWS region: " + REGION + " (DEFAULT applied - AWS_REGION env var not set)");
        } else {
            log.gray("AWS region: " + REGION + " (from AWS_REGION env var)");
        }
        log.gray("Configured model: " + MODEL_ID);
        log.gray("Prompt source: " + promptSource);
        log.gray("Autosaving prompt to: " + PROMPT_FILE);
        log.gray("Available models (mantle): loading...");

        // Fetch the model list off the EDT so the window stays responsive.
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                return listAvailableModels();
            }

            @Override
            protected void done() {
                String models;
                try {
                    models = get();
                } catch (Exception ex) {
                    models = "(error: " + ex.getMessage() + ")";
                }
                log.gray("Available models (mantle): " + models);
                log.gray("");
                log.gray("Ready.");
            }
        }.execute();

        // "Append" mode: when on, each send includes the full prior dialog so the
        // model sees a continuous conversation instead of a single message.
        javax.swing.JCheckBox appendMode = new javax.swing.JCheckBox("Append conversation");
        appendMode.setToolTipText("Send the whole prior dialog with each message (continuous chat)");

        JButton send = new JButton("Send (Ctrl-Enter)");
        send.addActionListener(e -> {
            String prompt = input.getText().trim();
            if (prompt.isEmpty()) {
                log.gray("");
                log.gray("Nothing to send - type a prompt first...");
                return;
            }
            send.setEnabled(false);
            // In append mode, capture the prior dialog turns BEFORE adding the new
            // prompt, so the request is [history...] + [new prompt].
            boolean append = appendMode.isSelected();
            java.util.List<String[]> history = append
                    ? log.dialogHistory() : java.util.Collections.emptyList();
            log.gray("");                        // blank line BEFORE the input message
            log.human(prompt);
            log.gray("");                        // blank line AFTER the input message
            log.gray(append
                    ? "Calling " + ENDPOINT + " (append: " + history.size() + " prior turns) ..."
                    : "Calling " + ENDPOINT + " ...");
            new SwingWorker<String[], Void>() {
                @Override
                protected String[] doInBackground() {
                    try {
                        return callModel(prompt, history);
                    } catch (Exception ex) {
                        return new String[] {
                            "0", // failure
                            "ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                            null
                        };
                    }
                }

                @Override
                protected void done() {
                    try {
                        String[] result = get();
                        // result[0] = "1" success / "0" failure.
                        // result[1] = model reply (success) or error text (failure).
                        // result[2] = raw request/response/stats -> always gray, or null.
                        boolean ok = "1".equals(result[0]);
                        if (ok) {
                            // A real reply is dialog: branded header + black text,
                            // and it's persisted to its own file in logs/. The blank
                            // lines around the message are separate gray "" entries.
                            log.gray("");        // blank line BEFORE the output message
                            log.assistant(result[1]);
                            log.gray("");        // blank line AFTER the output message
                        } else {
                            // Failures are NOT dialog: log in gray so they don't
                            // pollute the transcript or "Dialog only" view.
                            log.gray(result[1]);
                            log.gray("");
                        }
                        if (result[2] != null) {
                            log.gray(result[2]);
                        }
                    } catch (Exception ex) {
                        log.gray("ERROR: " + ex.getMessage());
                    }
                    send.setEnabled(true);
                }
            }.execute();
        });

        // Ctrl+Enter in the prompt area triggers Send.
        input.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "jrock-send");
        input.getActionMap().put("jrock-send", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (send.isEnabled()) send.doClick();
            }
        });

        JScrollPane outputScroll = new JScrollPane(output);
        // Same framed look as the input area.
        outputScroll.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6),
                javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY)));

        // Draggable divider between the output (top) and input (bottom) areas.
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(
                javax.swing.JSplitPane.VERTICAL_SPLIT, outputScroll, inputScroll);
        split.setResizeWeight(0.75);   // give extra space to the output on resize
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);

        // Bottom bar: Send on the left, Append checkbox on the right, same row.
        javax.swing.JPanel sendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        sendSide.add(send);
        javax.swing.JPanel appendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        appendSide.add(appendMode);

        javax.swing.JPanel buttonBar = new javax.swing.JPanel(new BorderLayout());
        // Smaller top gap than bottom so the row visually groups with the text
        // area above it, while keeping clearance from the window's bottom edge.
        buttonBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 12, 14, 12));
        buttonBar.add(sendSide, BorderLayout.WEST);
        buttonBar.add(appendSide, BorderLayout.EAST);

        frame.add(topBar, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buttonBar, BorderLayout.SOUTH);

        // Hidden feature: Ctrl+R opens a Move & Resize dialog. Bound at the window
        // level so it fires regardless of which component has focus.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "jrock-move-resize");
        frame.getRootPane().getActionMap().put("jrock-move-resize", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showMoveResizeDialog(frame);
            }
        });

        // Ctrl+S: save a COPY of the current prompt to a file the user chooses.
        // The persistent jrock-prompt.txt autosave is unaffected.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "jrock-save-prompt");
        frame.getRootPane().getActionMap().put("jrock-save-prompt", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                savePromptAs(frame, input.getText());
            }
        });

        // Ctrl+O: load a prompt from a file (read-only) into the input area, like
        // passing a prompt file as a startup argument. Autosave then continues
        // writing the loaded text to jrock-prompt.txt.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "jrock-load-prompt");
        frame.getRootPane().getActionMap().put("jrock-load-prompt", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                loadPromptInto(frame, input, log);
            }
        });

        frame.setVisible(true);
    }

    // ---- Save / load prompt (Ctrl+S / Ctrl+O) ------------------------------
    // Saves a copy of the given text to a user-chosen file. Does NOT touch the
    // persistent jrock-prompt.txt; this is an extra export.
    private static void savePromptAs(JFrame frame, String text) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Save prompt as");
        chooser.setSelectedFile(new java.io.File("prompt.txt"));
        if (chooser.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;

        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.write(target, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not save to " + target + ":\n" + ex.getMessage(),
                    "Save failed", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Loads a prompt from a user-chosen file (read-only) into the input area.
    // The document listener then autosaves the loaded text to jrock-prompt.txt.
    private static void loadPromptInto(JFrame frame, JTextArea input, LogView log) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Load prompt");
        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;

        Path source = chooser.getSelectedFile().toPath();
        String loaded = readFileQuietly(source);
        if (loaded == null) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not read " + source,
                    "Load failed", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        input.setText(loaded);            // triggers autosave to jrock-prompt.txt
        input.setCaretPosition(0);
        if (log != null) log.gray("Loaded prompt from (read-only): " + source);
    }

    // ---- Move & Resize dialog (Ctrl+R) -------------------------------------
    // Lets the user set the window size and on-screen position numerically, and
    // shows which screen the window is on plus that screen's bounds. Useful for
    // precise placement and moving the window across monitors without a mouse.
    private static void showMoveResizeDialog(JFrame frame) {
        java.awt.Rectangle win = frame.getBounds();
        boolean maximized =
                (frame.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) == java.awt.Frame.MAXIMIZED_BOTH;

        // When maximized, Windows places the frame slightly off-screen (e.g. -7,-7
        // with oversized bounds) so its invisible borders sit outside the monitor.
        // Showing those raw values is confusing, so prefill the fields with the
        // clean visible bounds of the screen the window is on instead.
        java.awt.Rectangle prefill = win;
        if (maximized) {
            java.awt.Rectangle screen = screenBoundsFor(win);
            if (screen != null) prefill = screen;
        }

        javax.swing.JTextField widthF  = new javax.swing.JTextField(String.valueOf(prefill.width), 6);
        javax.swing.JTextField heightF = new javax.swing.JTextField(String.valueOf(prefill.height), 6);
        javax.swing.JTextField xF      = new javax.swing.JTextField(String.valueOf(prefill.x), 6);
        javax.swing.JTextField yF      = new javax.swing.JTextField(String.valueOf(prefill.y), 6);

        // Screen info: find the device whose bounds contain the window's center.
        String header = maximized
                ? "NOTE: window is MAXIMIZED (Windows reports it at " + win.x + "," + win.y
                  + " size " + win.width + "x" + win.height + ").\nApplying will restore it "
                  + "to normal and use the values above.\n\n"
                : "";
        javax.swing.JTextArea info = new javax.swing.JTextArea(header + describeScreens(win));
        info.setEditable(false);
        info.setOpaque(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        info.setFont(javax.swing.UIManager.getFont("Label.font"));

        javax.swing.JPanel fields = new javax.swing.JPanel(new java.awt.GridLayout(4, 2, 6, 6));
        fields.add(new javax.swing.JLabel("Width:"));      fields.add(widthF);
        fields.add(new javax.swing.JLabel("Height:"));     fields.add(heightF);
        fields.add(new javax.swing.JLabel("Position X:")); fields.add(xF);
        fields.add(new javax.swing.JLabel("Position Y:")); fields.add(yF);

        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 8));
        panel.add(fields, BorderLayout.NORTH);
        javax.swing.JScrollPane infoScroll = new javax.swing.JScrollPane(info);
        infoScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Screens"));
        infoScroll.setPreferredSize(new java.awt.Dimension(480, 280));
        // Wrapping handles width, so no horizontal scrollbar is ever needed.
        infoScroll.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(infoScroll, BorderLayout.CENTER);

        int result = javax.swing.JOptionPane.showConfirmDialog(
                frame, panel, "Move & Resize (Ctrl+R)",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (result != javax.swing.JOptionPane.OK_OPTION) return;

        try {
            int w = Integer.parseInt(widthF.getText().trim());
            int h = Integer.parseInt(heightF.getText().trim());
            int x = Integer.parseInt(xF.getText().trim());
            int y = Integer.parseInt(yF.getText().trim());
            // Guard against degenerate sizes.
            w = Math.max(200, w);
            h = Math.max(150, h);
            // If maximized, restore to normal first so setBounds actually applies.
            if ((frame.getExtendedState() & java.awt.Frame.MAXIMIZED_BOTH) != 0) {
                frame.setExtendedState(java.awt.Frame.NORMAL);
            }
            frame.setBounds(x, y, w, h);
            // Moving to another monitor sometimes needs a revalidate to repaint.
            frame.revalidate();
            frame.repaint();
        } catch (NumberFormatException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Please enter whole numbers for width, height, X and Y.",
                    "Invalid input", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Returns the bounds of the screen whose area contains the window's center,
    // or the primary screen's bounds as a fallback, or null if none found.
    private static java.awt.Rectangle screenBoundsFor(java.awt.Rectangle win) {
        int cx = win.x + win.width / 2;
        int cy = win.y + win.height / 2;
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (java.awt.GraphicsDevice dev : ge.getScreenDevices()) {
            java.awt.Rectangle b = dev.getDefaultConfiguration().getBounds();
            if (b.contains(cx, cy)) return b;
        }
        java.awt.GraphicsDevice primary = ge.getDefaultScreenDevice();
        return primary == null ? null : primary.getDefaultConfiguration().getBounds();
    }

    // Builds a human-readable description of all screens and which one currently
    // holds the window, with the window's position relative to that screen.
    private static String describeScreens(java.awt.Rectangle win) {
        StringBuilder sb = new StringBuilder();
        int centerX = win.x + win.width / 2;
        int centerY = win.y + win.height / 2;

        java.awt.GraphicsDevice[] devices = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();
        java.awt.GraphicsDevice primary = java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice();

        for (int i = 0; i < devices.length; i++) {
            java.awt.GraphicsDevice dev = devices[i];
            java.awt.Rectangle b = dev.getDefaultConfiguration().getBounds();
            boolean isPrimary = dev.equals(primary);
            boolean hasWindow = b.contains(centerX, centerY);
            sb.append(hasWindow ? "> " : "  ")
              .append("Screen ").append(i + 1)
              .append(isPrimary ? " (primary)" : "")
              .append(": ").append(b.width).append("x").append(b.height)
              .append(" at (").append(b.x).append(", ").append(b.y).append(")");
            if (hasWindow) {
                sb.append("\n    window here; position on this screen: (")
                  .append(win.x - b.x).append(", ").append(win.y - b.y).append(")");
            }
            sb.append("\n");
        }
        sb.append("\nCoordinates X/Y are in the virtual desktop space\n")
          .append("(primary screen's top-left is 0,0; screens to the\n")
          .append("left/above have negative coordinates).");
        return sb.toString();
    }

    // ---- Bedrock call ------------------------------------------------------
    // Returns a 3-element array:
    //   [0] = "1" on success, "0" on failure.
    //   [1] = the model reply (success) or the error message (failure).
    //   [2] = raw request/response/stats detail block (gray), or null.
    // Only a successful reply is treated as dialog; failures are logged in gray.
    private static String[] callModel(String prompt, java.util.List<String[]> history)
            throws Exception {
        String apiKey = System.getenv("BEDROCK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return new String[] {
                "0",
                "No BEDROCK_API_KEY found. Generate a Bedrock API key in the "
                    + "console and set it, e.g.:\n\n"
                    + "  $env:BEDROCK_API_KEY = \"<your key>\"\n\n"
                    + "then relaunch: java JRock.java",
                null
            };
        }

        // Build the messages array. In single-message mode `history` is empty, so
        // this is just the new user turn. In append mode it is the full prior
        // dialog (mapped to OpenAI roles) followed by the new user turn.
        StringBuilder messages = new StringBuilder("[");
        for (String[] turn : history) {
            String openaiRole = ROLE_HUMAN.equals(turn[0]) ? "user" : "assistant";
            messages.append("{\"role\":\"").append(openaiRole).append("\",\"content\":\"")
                    .append(jsonEscape(turn[1])).append("\"},");
        }
        messages.append("{\"role\":\"user\",\"content\":\"").append(jsonEscape(prompt)).append("\"}]");

        // OpenAI Chat Completions request shape.
        String body = "{"
                + "\"model\":\"" + jsonEscape(MODEL_ID) + "\","
                + "\"messages\":" + messages
                + ",\"max_tokens\":2048"
                + "}";

        // Masked copy of the request for display: every message content (all
        // history turns AND the new prompt) is replaced with <input masked> so
        // the raw request isn't a noisy duplicate of the dialog.
        String maskedRequestBody = body;
        for (String[] turn : history) {
            maskedRequestBody = maskFirst(maskedRequestBody, jsonEscape(turn[1]), "<input masked>");
        }
        maskedRequestBody = maskFirst(maskedRequestBody, jsonEscape(prompt), "<input masked>");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey.trim())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            return new String[] {
                "0",
                "HTTP " + resp.statusCode(),
                "--- raw request ---\nPOST " + ENDPOINT + "\n" + maskedRequestBody
                    + "\n\n--- raw response ---\n" + resp.body()
            };
        }

        String reply = extractContent(resp.body());

        // Symbol (character) counts are computed locally. Input counts the ENTIRE
        // input sent to the model: all prior turns (append mode) plus the new
        // prompt - matching what prompt_tokens measures.
        int inputSymbols = prompt.length();
        for (String[] turn : history) {
            inputSymbols += turn[1].length();
        }
        int outputSymbols = reply.length();

        // Token counts come from the API's "usage" object (best-effort parse).
        long inputTokens = extractLong(resp.body(), "prompt_tokens");
        long outputTokens = extractLong(resp.body(), "completion_tokens");

        // Mask the reply text inside the raw response so it isn't duplicated
        // (it's already shown above). The request is already masked (all turns).
        String maskedResponse = maskFirst(resp.body(), jsonEscape(reply), "<output masked>");

        String details = "--- raw request ---\n" + "POST " + ENDPOINT + "\n" + maskedRequestBody
                + "\n\n--- raw response ---\n" + maskedResponse
                + "\n\n--- stats ---"
                + "\nInput symbols:  " + inputSymbols
                + "\nOutput symbols: " + outputSymbols
                + "\nInput tokens:   " + tokenStr(inputTokens)
                + "\nOutput tokens:  " + tokenStr(outputTokens);

        return new String[] { "1", reply, details };
    }

    private static String tokenStr(long v) {
        return v < 0 ? "(not reported)" : Long.toString(v);
    }

    // Replaces the first occurrence of `needle` in `haystack` with `placeholder`.
    // Returns the haystack unchanged if the needle is empty or not found.
    private static String maskFirst(String haystack, String needle, String placeholder) {
        if (needle == null || needle.isEmpty()) return haystack;
        int at = haystack.indexOf(needle);
        if (at < 0) return haystack;
        return haystack.substring(0, at) + placeholder + haystack.substring(at + needle.length());
    }

    // Best-effort read of a numeric JSON field like "prompt_tokens": 12.
    // Returns -1 if the field isn't present.
    private static long extractLong(String json, String key) {
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return -1;
        int colon = json.indexOf(':', k + key.length() + 2);
        if (colon < 0) return -1;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (i == start) return -1;
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    // Best-effort extraction of choices[0].message.content from an OpenAI Chat
    // Completions reply. Good enough for a demo; use a JSON library for production.
    private static String extractContent(String json) {
        int msg = json.indexOf("\"message\"");
        int from = msg >= 0 ? msg : 0;
        int c = json.indexOf("\"content\"", from);
        if (c < 0) return "(could not locate content; see raw response)";
        int start = json.indexOf(':', c);
        int firstQuote = json.indexOf('"', start + 1);
        if (firstQuote < 0) return "(unparsed)";
        StringBuilder sb = new StringBuilder();
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(n);
                }
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ---- Helpers -----------------------------------------------------------
    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    // JSON requires ALL control characters (U+0000..U+001F) to be
                    // escaped. Anything below 0x20 not handled above becomes \\u00XX.
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // ---- Prompt persistence (crash recovery) -------------------------------
    // Reads a file as UTF-8, returning null if it doesn't exist or can't be read.
    // Never modifies the file (used for the read-only command-line source too).
    private static String readFileQuietly(Path path) {
        try {
            if (path == null || !Files.exists(path) || !Files.isReadable(path)) return null;
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    // Rewrites the persistent prompt file (crash recovery for the input box).
    private static void savePromptQuietly(String text) {
        atomicWriteQuietly(PROMPT_FILE, text);
    }

    // Atomically rewrites `target` with `text`: write to a temp file, then move it
    // into place, so a crash mid-write can't leave a half-written (corrupt) file.
    // Best-effort: swallows I/O errors so the UI is never disrupted. Used for both
    // the prompt file and the main log.
    //
    // All writes are serialized through WRITE_LOCK (all callers are on the EDT
    // today; the lock is a safeguard should that ever change).
    //
    // Diagnosis showed the previous ATOMIC_MOVE approach failing with
    // AccessDeniedException on Windows: the target (e.g. jrock-log.txt) is briefly
    // opened by an external process - Windows Defender scanning the just-written
    // file, the Search Indexer, cloud sync (OneDrive), or an editor/IDE that has
    // the file open - and the rename is refused while that handle exists. The lock
    // is typically held only a few milliseconds, so we RETRY the move a few times
    // with a short backoff, which resolves it without losing data or leaving junk.
    //
    // On total failure we DO NOT delete the temp file (leave it for the user to
    // inspect/clean up) and only log the cause to STDOUT.
    private static final Object WRITE_LOCK = new Object();
    private static final int WRITE_ATTEMPTS = 5;
    private static final long WRITE_RETRY_MS = 40;

    private static void atomicWriteQuietly(Path target, String text) {
        synchronized (WRITE_LOCK) {
            Path tmp = null;
            try {
                Path dir = target.toAbsolutePath().getParent();
                Files.createDirectories(dir);
                tmp = Files.createTempFile(dir, "jrock", ".tmp");
                Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));

                IOException last = null;
                for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
                    try {
                        Files.move(tmp, target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        tmp = null;          // atomic move succeeded
                        return;
                    } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                        // Filesystem can't do atomic moves - this won't change on
                        // retry, so do a single plain (non-atomic) replace instead.
                        Files.move(tmp, target,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        tmp = null;
                        return;
                    } catch (IOException moveEx) {
                        // Transient failure (e.g. AccessDenied while the target is
                        // momentarily locked by an external process). The FS DOES
                        // support atomic moves, so keep retrying the atomic move -
                        // do not drop to a non-atomic one. Wait briefly, then retry.
                        last = moveEx;
                        if (attempt < WRITE_ATTEMPTS) {
                            try { Thread.sleep(WRITE_RETRY_MS); }
                            catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                // All atomic attempts failed transiently. Target keeps its previous
                // contents; the next autosave will retry. Leave the temp file for
                // the user to inspect/clean up.
                System.out.println("[JRock] write failed for " + target
                        + " after " + WRITE_ATTEMPTS + " attempts, tmp=" + tmp
                        + " on thread " + Thread.currentThread().getName()
                        + " : " + (last == null ? "?" : last.getClass().getName() + ": " + last.getMessage()));
            } catch (IOException ex) {
                // Failure creating/writing the temp file itself.
                System.out.println("[JRock] temp write failed for " + target
                        + " tmp=" + tmp + " on thread " + Thread.currentThread().getName()
                        + " : " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        }
    }

    // ---- Message-file persistence (append-only logs/ directory) ------------
    // Maps a role to a filename-safe slug. Kept explicit (no user text in the
    // name) so filenames are always predictable and injection-free.
    private static String roleSlug(String role) {
        return role.equals(ROLE_HUMAN) ? "operator" : "assistant";
    }

    // Builds the per-message file path from a validated stamp + role. The stamp
    // is ALWAYS a value we produced/validated (yyyyMMdd-HHmmss-SSS), never raw
    // user text, so the path can't escape logs/.
    private static Path messageFile(String role, String stamp) {
        return LOGS_DIR.resolve(stamp + "-" + roleSlug(role) + ".txt");
    }

    // Writes a dialog message body to its own file in logs/. Written once and
    // never modified afterwards. Best-effort.
    private static void writeMessageFile(String role, String stamp, String text) {
        try {
            Files.createDirectories(LOGS_DIR);
            Files.write(messageFile(role, stamp), text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // Best-effort; the on-screen log still shows the message.
        }
    }

    // Validates a reference stamp is a REAL datetime in our format, then rebuilds
    // the file path from the PARSED value (round-tripped back to the canonical
    // string). This is the injection guard: we never open a path derived from raw
    // reference text; only from a parsed, re-formatted LocalDateTime. Returns the
    // message body, or null if the stamp is invalid or the file is missing.
    private static String resolveReference(String role, String rawStamp) {
        try {
            LocalDateTime dt = LocalDateTime.parse(rawStamp, STAMP_FMT);
            String canonical = dt.format(STAMP_FMT);   // round-trip -> safe stamp
            Path file = messageFile(role, canonical);
            // Ensure the resolved path is actually inside logs/ (defense in depth).
            Path base = LOGS_DIR.toAbsolutePath().normalize();
            Path resolved = file.toAbsolutePath().normalize();
            if (!resolved.startsWith(base)) return null;
            return readFileQuietly(file);
        } catch (DateTimeParseException ex) {
            return null;   // not a valid datetime -> ignore, no file access
        }
    }
}
