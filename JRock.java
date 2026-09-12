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

    // Appends a line to the output area, ensuring the update runs on the EDT.
    private static void log(JTextArea area, String line) {
        SwingUtilities.invokeLater(() -> {
            area.append(line + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    // Calls GET /v1/models on the mantle endpoint and returns a compact,
    // comma-separated list of model ids. Never throws - returns a status
    // string on failure so startup logging is best-effort.
    private static String listAvailableModels() {
        String apiKey = System.getenv("BEDROCK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "(skipped - BEDROCK_API_KEY not set)";
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

        JTextArea output = new JTextArea();
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setEditable(false);
        output.setMargin(new java.awt.Insets(8, 8, 8, 8));

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
            log(output, "AWS region: " + REGION + " (DEFAULT applied - AWS_REGION env var not set)");
        } else {
            log(output, "AWS region: " + REGION + " (from AWS_REGION env var)");
        }
        log(output, "Configured model: " + MODEL_ID);
        log(output, "Prompt source: " + promptSource);
        log(output, "Autosaving prompt to: " + PROMPT_FILE);
        log(output, "Available models (mantle): loading...");

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
                log(output, "Available models (mantle): " + models);
                log(output, "");
                log(output, "Ready.");
            }
        }.execute();

        JButton send = new JButton("Send");
        send.addActionListener(e -> {
            String prompt = input.getText().trim();
            if (prompt.isEmpty()) {
                log(output, "");
                log(output, "(nothing to send - type a prompt first)");
                return;
            }
            send.setEnabled(false);
            log(output, "");
            log(output, "> " + prompt);
            log(output, "Calling " + ENDPOINT + " ...");
            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() {
                    try {
                        return callModel(prompt);
                    } catch (Exception ex) {
                        return "ERROR: " + ex.getClass().getSimpleName() + ": " + ex.getMessage();
                    }
                }

                @Override
                protected void done() {
                    try {
                        log(output, get());
                    } catch (Exception ex) {
                        log(output, "ERROR: " + ex.getMessage());
                    }
                    send.setEnabled(true);
                }
            }.execute();
        });

        // Bottom panel: prompt input above, Send button below.
        javax.swing.JPanel bottom = new javax.swing.JPanel(new BorderLayout());
        bottom.add(inputScroll, BorderLayout.CENTER);
        bottom.add(send, BorderLayout.SOUTH);

        JScrollPane outputScroll = new JScrollPane(output);
        // Same framed look as the input area.
        outputScroll.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6),
                javax.swing.BorderFactory.createLineBorder(java.awt.Color.GRAY)));

        frame.add(outputScroll, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);
        frame.setVisible(true);
    }

    // ---- Bedrock call ------------------------------------------------------
    private static String callModel(String prompt) throws Exception {
        String apiKey = System.getenv("BEDROCK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            return "No BEDROCK_API_KEY found. Generate a Bedrock API key in the "
                    + "console and set it, e.g.:\n\n"
                    + "  $env:BEDROCK_API_KEY = \"<your key>\"\n\n"
                    + "then relaunch: java JRock.java";
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
            return "HTTP " + resp.statusCode() + "\n\n" + resp.body();
        }

        String reply = extractContent(resp.body());

        // Symbol (character) counts are computed locally.
        int inputSymbols = prompt.length();
        int outputSymbols = reply.length();

        // Token counts come from the API's "usage" object (best-effort parse).
        long inputTokens = extractLong(resp.body(), "prompt_tokens");
        long outputTokens = extractLong(resp.body(), "completion_tokens");

        return "HTTP 200\n\nModel reply:\n" + reply
                + "\n\n--- raw response ---\n" + resp.body()
                + "\n\n--- stats ---"
                + "\nInput symbols:  " + inputSymbols
                + "\nOutput symbols: " + outputSymbols
                + "\nInput tokens:   " + tokenStr(inputTokens)
                + "\nOutput tokens:  " + tokenStr(outputTokens);
    }

    private static String tokenStr(long v) {
        return v < 0 ? "(not reported)" : Long.toString(v);
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
