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

    // A styled log backed by a list of entries so it can be re-rendered on demand:
    //   - GRAY  : app/system status, echoes, raw request/response/stats.
    //   - MODEL : actual dialog content (human input, model reply) - black.
    //   - HEADER: [HUMAN OPERATOR] / [OPERATOR'S ASSISTANT] - branded teal.
    // "Dialog only" mode hides GRAY entries, leaving a clean, copy-pastable
    // transcript of just the headers and dialog.
    private static final class LogView {
        private enum Kind { GRAY, MODEL, HEADER }

        private static final class Entry {
            final Kind kind;
            final String text;
            Entry(Kind kind, String text) { this.kind = kind; this.text = text; }
        }

        private final JTextPane pane;
        private final java.util.List<Entry> entries = new ArrayList<>();
        private boolean dialogOnly = false;

        LogView(JTextPane pane) { this.pane = pane; }

        void gray(String line)   { add(Kind.GRAY, line); }
        void model(String line)  { add(Kind.MODEL, line); }
        void header(String line) { add(Kind.HEADER, line); }

        private void add(Kind kind, String line) {
            SwingUtilities.invokeLater(() -> {
                Entry e = new Entry(kind, line);
                entries.add(e);
                if (isVisible(e)) renderLine(e);
            });
        }

        void setDialogOnly(boolean on) {
            SwingUtilities.invokeLater(() -> {
                dialogOnly = on;
                rebuild();
            });
        }

        void clear() {
            SwingUtilities.invokeLater(() -> {
                entries.clear();
                pane.setText("");
            });
        }

        private boolean isVisible(Entry e) {
            return !dialogOnly || e.kind != Kind.GRAY;
        }

        private void rebuild() {
            pane.setText("");
            for (Entry e : entries) {
                if (isVisible(e)) renderLine(e);
            }
        }

        private void renderLine(Entry e) {
            java.awt.Color color;
            switch (e.kind) {
                case HEADER: color = BRAND; break;
                case MODEL:  color = java.awt.Color.BLACK; break;
                default:     color = java.awt.Color.GRAY;
            }
            SimpleAttributeSet attrs = new SimpleAttributeSet();
            StyleConstants.setForeground(attrs, color);
            try {
                pane.getStyledDocument().insertString(
                        pane.getStyledDocument().getLength(), e.text + "\n", attrs);
            } catch (BadLocationException ignored) {
                // Position is always valid (document end); ignore defensively.
            }
            pane.setCaretPosition(pane.getStyledDocument().getLength());
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

        JButton send = new JButton("Send (Ctrl-Enter)");
        send.addActionListener(e -> {
            String prompt = input.getText().trim();
            if (prompt.isEmpty()) {
                log.gray("");
                log.gray("(nothing to send - type a prompt first)");
                return;
            }
            send.setEnabled(false);
            log.gray("");
            log.header("[HUMAN OPERATOR]");
            log.model(prompt);
            log.model("");
            log.gray("Calling " + ENDPOINT + " ...");
            new SwingWorker<String[], Void>() {
                @Override
                protected String[] doInBackground() {
                    try {
                        return callModel(prompt);
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
                            // A real reply is dialog: branded header + black text.
                            log.gray("");
                            log.header("[OPERATOR'S ASSISTANT]");
                            log.model(result[1]);
                            log.model("");
                        } else {
                            // Failures are NOT dialog: log in gray so they don't
                            // pollute the transcript or "Dialog only" view.
                            log.gray("");
                            log.gray(result[1]);
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

        // Left-aligned button in a padded panel so it isn't full-width and doesn't
        // sit against the window edges (avoids accidental clicks while resizing).
        javax.swing.JPanel buttonBar = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        // Smaller top gap than bottom so the button visually groups with the text
        // area above it, while keeping clearance from the window's bottom edge.
        buttonBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 12, 14, 12));
        buttonBar.add(send);

        frame.add(topBar, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buttonBar, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    // ---- Bedrock call ------------------------------------------------------
    // Returns a 3-element array:
    //   [0] = "1" on success, "0" on failure.
    //   [1] = the model reply (success) or the error message (failure).
    //   [2] = raw request/response/stats detail block (gray), or null.
    // Only a successful reply is treated as dialog; failures are logged in gray.
    private static String[] callModel(String prompt) throws Exception {
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

        // OpenAI Chat Completions request shape.
        String body = "{"
                + "\"model\":\"" + jsonEscape(MODEL_ID) + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\"" + jsonEscape(prompt) + "\"}],"
                + "\"max_tokens\":2048"
                + "}";

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
                "--- raw request ---\nPOST " + ENDPOINT + "\n"
                    + maskFirst(body, jsonEscape(prompt), "<input masked>")
                    + "\n\n--- raw response ---\n" + resp.body()
            };
        }

        String reply = extractContent(resp.body());

        // Symbol (character) counts are computed locally.
        int inputSymbols = prompt.length();
        int outputSymbols = reply.length();

        // Token counts come from the API's "usage" object (best-effort parse).
        long inputTokens = extractLong(resp.body(), "prompt_tokens");
        long outputTokens = extractLong(resp.body(), "completion_tokens");

        // Mask the prompt/reply text inside the raw JSON so it isn't duplicated
        // (it's already shown above as "Model reply" and in the request line).
        // We replace the JSON-escaped form of each, since that's what's in the JSON.
        String maskedRequest = maskFirst(body, jsonEscape(prompt), "<input masked>");
        String maskedResponse = maskFirst(resp.body(), jsonEscape(reply), "<output masked>");

        String details = "--- raw request ---\n" + "POST " + ENDPOINT + "\n" + maskedRequest
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

    // Rewrites the whole persistent prompt file with the given text. Writes to a
    // temp file first, then atomically moves it into place, so a crash mid-write
    // cannot leave a half-written (corrupt) prompt file. Best-effort: swallows
    // I/O errors so typing is never interrupted.
    private static void savePromptQuietly(String text) {
        try {
            Path dir = PROMPT_FILE.toAbsolutePath().getParent();
            Path tmp = Files.createTempFile(dir, "jrock-prompt", ".tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, PROMPT_FILE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amnse) {
                // Fall back to a non-atomic replace if the filesystem can't do it.
                Files.move(tmp, PROMPT_FILE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            // Persistence is best-effort; do not disrupt the UI on failure.
        }
    }
}
