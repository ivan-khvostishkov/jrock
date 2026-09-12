// JRock - minimal Swing app that calls Amazon Bedrock via the OpenAI-compatible
// Chat Completions API on the bedrock-mantle endpoint, authenticated with a
// lightweight Bedrock API key (no SigV4, no AWS SDK, no ~/.aws credentials).
//
// Run directly with: java JRock.java
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
import java.awt.BorderLayout;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    // ---- UI ----------------------------------------------------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(JRock::createAndShowGui);
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

    private static void createAndShowGui() {
        JFrame frame = new JFrame("JRock - Bedrock (mantle)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 460);
        frame.setLocationRelativeTo(null);

        JTextArea output = new JTextArea();
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setEditable(false);
        output.setMargin(new java.awt.Insets(8, 8, 8, 8));

        // Input area for the user's prompt, seeded with the default.
        JTextArea input = new JTextArea(PROMPT, 3, 20);
        input.setLineWrap(true);
        input.setWrapStyleWord(true);
        input.setMargin(new java.awt.Insets(8, 8, 8, 8));
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
                + "\"max_tokens\":512"
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
        return "HTTP 200\n\nModel reply:\n" + extractContent(resp.body())
                + "\n\n--- raw response ---\n" + resp.body();
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
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
