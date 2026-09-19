// JRock - minimal Swing app that calls Amazon Bedrock via the OpenAI-compatible
// Chat Completions API on the bedrock-mantle endpoint, authenticated with a
// lightweight Bedrock API key (no SigV4, no AWS SDK, no ~/.aws credentials).
//
// Run directly with: java JRock.java
//   Optionally: java JRock.java <initial-prompt-file>
//
// Persistence (crash recovery + full local history):
//   Everything lives under a "JRock" subfolder of the working directory.
//   - Prompt: autosaved to JRock/jrock-prompt.txt on every keystroke (full rewrite
//     via an atomic temp-file swap, so a crash can't corrupt it).
//   - Conversation log + dialog: the on-screen transcript is persisted to
//     JRock/jrock-log.txt, and each human/assistant message is also written to its
//     own append-only file in JRock/messages/. On startup the log is restored from
//     disk so the whole session survives restarts.
//   On startup the initial prompt is resolved as:
//     1. If a file path is passed as the first CLI arg, load it READ-ONLY and use
//        its contents; the original file is never modified. That text is then
//        written to the persistent file, which continues to receive autosaves.
//     2. Else if JRock/jrock-prompt.txt exists, recover the prompt from it.
//     3. Else use the built-in default prompt.
//
// Java version requirements:
//   Minimum: JDK 11  - single-file source launch (JEP 330) and java.net.http.HttpClient
//                       are both required. On JDK 8-10 this won't run as a single file.
//   Maximum: none    - only core JDK APIs (javax.swing, java.net.http). No external
//                       jars. Runs on the latest JDK.
//
// HTTP transport:
//   On any normal JVM, requests go out over java.net.http.HttpClient. In the
//   browser (CheerpJ, which runs this bytecode as WebAssembly) there is no native
//   socket layer, so HttpClient cannot work at all; JRock then routes its two
//   Bedrock calls through the hosting page's JavaScript HTTP client instead. See
//   the "HTTP transport" section below and jrock-web/index.html.
//
// Endpoint / API:
//   bedrock-mantle is the newer endpoint surface (same underlying Mantle inference
//   engine as bedrock-runtime). It exposes the OpenAI-compatible Chat Completions
//   and Responses APIs plus the Anthropic Messages API, across a broad model
//   catalog (Grok, Claude, OpenAI-GPT, GLM, Kimi, etc.). We use Chat Completions
//   because it is the portable, multi-model surface: swap the model to change it.
//     URL:  https://bedrock-mantle.{region}.api.aws/v1/chat/completions
//
//   Why bedrock-mantle: AWS recommends bedrock-runtime for new apps, and more
//   families are available there (Amazon Nova, Meta Llama, etc.). But as of Sep
//   2026 several frontier models still run on bedrock-mantle ONLY, so JRock
//   prefers mantle - sacrificing those runtime-only families for the simplicity of
//   a single API surface.
//
//   Why Chat Completions and NOT the Responses API: Responses is stateful - the
//   backend retains conversation state (stored responses, previous_response_id,
//   etc.) server-side. We deliberately avoid that. For security and transparency
//   JRock stores NOTHING on the backend; all history lives ONLY locally, as plain
//   visible files in the JRock/ folder that the user fully owns and controls. This
//   is the opposite of a browser client, where session data can be squirreled away
//   non-transparently in cookies, sessionStorage, IndexedDB, etc. Chat Completions
//   is stateless: each request carries its own context (see "Extend" mode), so
//   nothing needs to be, or is, kept on the server between calls.
//
// Authentication:
//   Bearer token = your Bedrock API key, read from the BEDROCK_API_KEY env var.
//   A short-term (recommended) Bedrock API key can be generated from the AWS
//   console at: https://console.aws.amazon.com/bedrock-mantle/api-keys
//   Set it before launching, e.g. (PowerShell):
//     $env:BEDROCK_API_KEY = "..."
//     $env:AWS_REGION = "us-east-1"   # optional, defaults below
//   Alternatively, configure the API key (and region/model) in the app itself via
//   the Configure dialog (top-left button); no env var needed.
//   In the browser the key never reaches the JVM at all: the page's JavaScript
//   HTTP client holds it and signs each request itself (see "HTTP transport").

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

    // Application version.
    private static final String VERSION = "1.4.1-dev";

    // Project home page (linked from the About line in the Configure dialog).
    private static final String GITHUB_URL = "https://github.com/ivan-khvostishkov/jrock";

    // ---- Configuration (mutable: changed via the Configure dialog) ----------
    private static final String DEFAULT_REGION = "us-east-1";
    // Region/model start from env/defaults and can be overridden at runtime.
    private static String REGION = envOr("AWS_REGION", DEFAULT_REGION);
    // Where REGION came from, phrased for the startup log; null for the built-in
    // default and once the user sets it in the Configure dialog.
    private static String regionSource =
            (env("AWS_REGION") == null) ? null : "from the AWS_REGION env var";
    private static String MODEL_ID = "xai.grok-4.3";
    private static final String PROMPT = "Hello, assistant.";

    // Request timeouts (seconds). The model list is a quick metadata call; a
    // completion can legitimately take much longer.
    private static final int MODELS_TIMEOUT_SECONDS  = 30;
    private static final int CHAT_TIMEOUT_SECONDS    = 60;
    private static final int CONNECT_TIMEOUT_SECONDS = 30;

    // Shown after an UnsatisfiedLinkError from the networking stack. That can only
    // really happen in one situation: JRock is running on CheerpJ in the browser
    // (no native socket layer, so HttpClient's sun.nio.ch.EPoll is unavailable)
    // and the hosting page never installed the browser HTTP bridge, so there was
    // no working transport to fall back to.
    private static final String NO_TRANSPORT_HINT =
            "This JVM has no working socket layer - it is almost certainly CheerpJ "
          + "in the browser - and the hosting page did not install JRock's browser "
          + "HTTP bridge, so there is no transport to send the request over. The "
          + "page must pass natives: { Java_JRock_browserHttpInfo, "
          + "Java_JRock_browserHttpSend } to cheerpjInit(); see jrock-web/index.html.";

    // Most recently fetched list of available model ids (from the /v1/models call).
    // Empty until the first successful fetch; used to populate the Configure dropdown.
    private static java.util.List<String> availableModels = new ArrayList<>();

    // In-memory Bedrock API key override. Null means "use the BEDROCK_API_KEY env
    // var". We never read/prefill the env value into the UI; the override is only
    // set when the user explicitly types a new key in the Configure dialog.
    private static String apiKeyOverride = null;

    // Working directory = the process current directory. When JRock is launched
    // from the "JRock here!" context menu, Explorer starts it in the clicked
    // folder, so the CWD is already correct with no extra flags. The window
    // title/icon reflect this folder so multiple instances in different folders
    // are distinguishable in Alt-Tab / the taskbar.
    private static Path workingDir = Paths.get("").toAbsolutePath();

    // Remembers where a file chooser last browsed, so the next dialog of the same
    // kind opens there. One instance PER PURPOSE, because these files live in
    // different places in practice: prompts in a prompt folder, included documents
    // and images wherever the source material is, exported logs somewhere else
    // again. A single shared memory meant that including a file dropped the user in
    // the prompt folder (and vice versa) - a directory away from what they wanted.
    private static final class ChooserDir {
        private Path dir = workingDir;

        // Directory the next chooser of this kind should open in.
        java.io.File start() { return dir.toFile(); }

        // Same directory, with a default file name pre-filled (for Save dialogs).
        java.io.File startFile(String name) { return new java.io.File(dir.toFile(), name); }

        // Records where the user ended up, once they've confirmed the dialog.
        void remember(javax.swing.JFileChooser chooser) {
            java.io.File current = chooser.getCurrentDirectory();
            if (current != null) dir = current.toPath();
        }

        // Back to the (possibly new) working directory; see initSession().
        void reset() { dir = workingDir; }
    }
    private static final ChooserDir promptChooserDir  = new ChooserDir();  // Save/Load prompt
    private static final ChooserDir includeChooserDir = new ChooserDir();  // Include file
    private static final ChooserDir logChooserDir     = new ChooserDir();  // Save log copy

    // Derived endpoints/paths (recomputed from the mutable config above).
    private static String mantleHost()     { return "https://bedrock-mantle." + REGION + ".api.aws"; }
    private static String modelsEndpoint() { return mantleHost() + "/v1/models"; }

    // Chat Completions URL for the currently configured model, or NULL if the
    // model has no Chat Completions path on mantle (e.g. Anthropic/Messages-only).
    //   - Known card with a chat path -> that URL (mantle default /v1, or /openai/v1).
    //   - Known card with a null chat path -> null (NOT a faked default).
    //   - No card (unknown/free-text)  -> the mantle default /v1/chat/completions.
    private static String endpoint() {
        BedrockModelCard card = cardFor(MODEL_ID);
        if (card == null) {
            return mantleHost() + "/v1/chat/completions";   // unknown model: default
        }
        String path = card.mantleChatCompletionsPath();
        return (path == null) ? null : mantleHost() + path;
    }

    // The path part of a mantle URL. Request lines log the path alone: the host is
    // reported once during session init, which keeps those lines narrow enough to
    // read on a phone without scrolling sideways.
    private static String pathOf(String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme < 0 ? 0 : scheme + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }

    // ---- Bedrock model cards -----------------------------------------------
    // Recorded from the AWS model-card pages: input/output modalities and the
    // per-endpoint API support (the cards list APIs SEPARATELY for bedrock-runtime
    // and bedrock-mantle, and they differ - so we record each table exactly).
    //
    // Note on endpoint choice: AWS recommends bedrock-runtime for new apps, where
    // additional families are available (Amazon Nova, Meta Llama, etc.). But as of
    // Sep 2026 several frontier models still run on bedrock-mantle ONLY, so JRock
    // gives preference to mantle - sacrificing those runtime-only families for the
    // simplicity of a single API surface (Chat Completions on mantle).
    //
    // We currently implement ONLY Chat Completions on bedrock-mantle. A model is
    // usable in JRock only if its mantle API set includes "Chat Completions"; the
    // mantle URL path differs per model (mantleChatCompletionsPath()).
    private abstract static class BedrockModelCard {
        abstract String modelId();
        abstract String displayName();
        abstract String cardUrl();          // AWS model-card documentation page
        abstract String[] inputModalities();
        abstract String[] outputModalities();
        abstract String[] endpointsSupported();
        abstract String[] apisOnRuntime();  // APIs supported on the bedrock-runtime endpoint
        abstract String[] apisOnMantle();   // APIs supported on the bedrock-mantle endpoint

        // Path (appended to the mantle host) for Chat Completions, or null if the
        // model isn't served via Chat Completions on mantle. Defaults to the mantle
        // default /v1; models on the OpenAI-compatible base override to /openai/v1.
        String mantleChatCompletionsPath() { return "/v1/chat/completions"; }

        // Path for the Anthropic Messages API on mantle, or null if not applicable.
        // Only Anthropic models set this; JRock does not implement Messages yet
        // (planned for the future), so this is metadata for now.
        String mantleMessagesPath() { return null; }

        // Vendor id prefix (e.g. "anthropic.", "openai."). Empty for cards that
        // aren't tied to a vendor family. Used for partial ("starts with") matching
        // when an exact model-id match isn't found.
        String vendorPrefix() { return ""; }

        // Whether this model exposes Chat Completions on mantle (i.e. usable by
        // JRock's current single-API implementation).
        boolean supportsMantleChatCompletions() {
            for (String api : apisOnMantle()) {
                if (api.equals("Chat Completions")) return true;
            }
            return false;
        }
    }

    // xAI Grok 4.3.
    // Note from the model card: "On bedrock-mantle, this model is served at
    // /openai/v1/responses, not the default /v1/responses." Its OpenAI-compatible
    // base is /openai/v1, so Chat Completions lives at /openai/v1/chat/completions.
    private static final class Grok43Card extends BedrockModelCard {
        String modelId()              { return "xai.grok-4.3"; }
        String displayName()          { return "Grok 4.3"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-xai-grok-4-3.html"; }
        String[] inputModalities()    { return new String[] { "Image", "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] {}; }  // runtime not supported
        String[] apisOnMantle()       { return new String[] { "Chat Completions", "Responses" }; }
        // Card: served under the OpenAI-compatible base /openai/v1 on mantle.
        @Override String mantleChatCompletionsPath() { return "/openai/v1/chat/completions"; }
    }

    // Moonshot AI Kimi K2.5.
    // Mantle base per the model card is /v1, so Chat Completions is /v1/chat/completions.
    private static final class KimiK25Card extends BedrockModelCard {
        String modelId()              { return "moonshotai.kimi-k2.5"; }
        String displayName()          { return "Kimi K2.5"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-moonshot-ai-kimi-k2-5.html"; }
        String[] inputModalities()    { return new String[] { "Image", "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-runtime", "bedrock-mantle" }; }
        // Card lists Chat Completions, Invoke, Converse (Responses NOT supported).
        String[] apisOnRuntime()      { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        String[] apisOnMantle()       { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        // Uses the mantle default /v1/chat/completions (inherited).
    }

    // Shared traits of OpenAI GPT models on Bedrock. Per their model cards, on
    // bedrock-mantle "both APIs use the /openai/v1 base path, not /v1", so Chat
    // Completions lives at /openai/v1/chat/completions. Text/Image-in, Text-out.
    //
    // CONCRETE (not abstract): it doubles as a generic "any OpenAI model" card used
    // for partial matches (model id starting with "openai."). Named subclasses
    // override the specifics (id, display name, url, per-endpoint APIs).
    private static class OpenAiModelCard extends BedrockModelCard {
        String vendorPrefix()         { return "openai."; }
        String modelId()              { return "openai."; }
        String displayName()          { return "OpenAI (generic)"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html"; }
        String[] inputModalities()    { return new String[] { "Image", "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] {}; }
        String[] apisOnMantle()       { return new String[] { "Responses", "Chat Completions" }; }
        @Override String mantleChatCompletionsPath() { return "/openai/v1/chat/completions"; }
    }

    // OpenAI GPT-5.4. Card: bedrock-mantle only; mantle APIs = Responses + Chat Completions.
    private static final class Gpt54Card extends OpenAiModelCard {
        String modelId()     { return "openai.gpt-5.4"; }
        String displayName() { return "GPT-5.4"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-54.html"; }
        String[] endpointsSupported() { return new String[] { "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] {}; }  // runtime not supported
        String[] apisOnMantle()       { return new String[] { "Responses", "Chat Completions" }; }
    }

    // OpenAI GPT-6 Astra. Card: both endpoints. Runtime = Responses/Chat Completions/
    // Converse; mantle = Responses/Chat Completions.
    private static final class Gpt6AstraCard extends OpenAiModelCard {
        String modelId()     { return "openai.gpt-6-astra"; }
        String displayName() { return "GPT-6 Astra"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-openai-gpt-6-astra.html"; }
        String[] endpointsSupported() { return new String[] { "bedrock-runtime", "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] { "Responses", "Chat Completions", "Converse" }; }
        String[] apisOnMantle()       { return new String[] { "Responses", "Chat Completions" }; }
    }

    // Shared traits of Anthropic Claude models on Bedrock. IMPORTANT (corrected
    // from the model cards): Claude does NOT support Chat Completions on either
    // endpoint. On bedrock-runtime it supports Messages, Converse, Invoke; on
    // bedrock-mantle it supports Messages ONLY, served at /anthropic/v1/messages.
    // JRock only implements Chat Completions on mantle, so Claude models are NOT
    // usable here (supportsMantleChatCompletions() returns false). These cards are
    // included for their metadata.
    //
    // CONCRETE (not abstract): it doubles as a generic "any Anthropic model" card
    // used for partial matches (model id starting with "anthropic."). Named
    // subclasses override only the identity fields.
    private static class AnthropicModelCard extends BedrockModelCard {
        String vendorPrefix()         { return "anthropic."; }
        String modelId()              { return "anthropic."; }
        String displayName()          { return "Anthropic Claude (generic)"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html"; }
        String[] inputModalities()    { return new String[] { "Image", "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-runtime", "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] { "Messages", "Converse", "Invoke" }; }
        String[] apisOnMantle()       { return new String[] { "Messages" }; }
        // Claude is NOT served via Chat Completions on mantle - only the Anthropic
        // Messages API at /anthropic/v1/messages (which JRock doesn't implement yet).
        @Override String mantleChatCompletionsPath() { return null; }
        @Override String mantleMessagesPath()        { return "/anthropic/v1/messages"; }
    }

    // Anthropic Claude Opus 5.
    private static final class ClaudeOpus5Card extends AnthropicModelCard {
        String modelId()     { return "anthropic.claude-opus-5"; }
        String displayName() { return "Claude Opus 5"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-opus-5.html"; }
    }

    // Anthropic Claude Fable 5.1.
    private static final class ClaudeFable51Card extends AnthropicModelCard {
        String modelId()     { return "anthropic.claude-fable-5-1"; }
        String displayName() { return "Claude Fable 5.1"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-fable-5-1.html"; }
    }

    // DeepSeek-V3.1.
    // Mantle base per the model card is /v1, so Chat Completions is /v1/chat/completions.
    private static final class DeepSeekV31Card extends BedrockModelCard {
        String modelId()              { return "deepseek.v3.1"; }
        String displayName()          { return "DeepSeek-V3.1"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-deepseek-deepseek-v3-1.html"; }
        String[] inputModalities()    { return new String[] { "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-runtime", "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        String[] apisOnMantle()       { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        // Uses the mantle default /v1/chat/completions (inherited).
    }

    // Qwen3 32B.
    // Mantle base per the model card is /v1, so Chat Completions is /v1/chat/completions.
    private static final class Qwen332bCard extends BedrockModelCard {
        String modelId()              { return "qwen.qwen3-32b"; }
        String displayName()          { return "Qwen3 32B"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-qwen-qwen3-32b.html"; }
        String[] inputModalities()    { return new String[] { "Text" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-runtime", "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        String[] apisOnMantle()       { return new String[] { "Chat Completions", "Invoke", "Converse" }; }
        // Uses the mantle default /v1/chat/completions (inherited).
    }

    // Registry of known model cards, and a lookup by model id.
    private static final BedrockModelCard[] MODEL_CARDS = {
        new Grok43Card(), new KimiK25Card(), new DeepSeekV31Card(), new Qwen332bCard(),
        new Gpt54Card(), new Gpt6AstraCard(),
        new ClaudeOpus5Card(), new ClaudeFable51Card(),
    };

    // Generic per-vendor cards, used as a fallback when no exact model-id match is
    // found but the id starts with a known vendor prefix (e.g. any "anthropic.*"
    // model routes to the Anthropic/Messages behavior).
    private static final BedrockModelCard[] VENDOR_CARDS = {
        new AnthropicModelCard(), new OpenAiModelCard(),
    };

    // Exact-match lookup by model id (pure; used to build endpoint URLs).
    private static BedrockModelCard cardFor(String modelId) {
        BedrockModelCard exact = exactCard(modelId);
        if (exact != null) return exact;
        return vendorCard(modelId);   // may be null
    }

    private static BedrockModelCard exactCard(String modelId) {
        for (BedrockModelCard c : MODEL_CARDS) {
            if (c.modelId().equals(modelId)) return c;
        }
        return null;
    }

    private static BedrockModelCard vendorCard(String modelId) {
        if (modelId == null) return null;
        for (BedrockModelCard v : VENDOR_CARDS) {
            String prefix = v.vendorPrefix();
            if (!prefix.isEmpty() && modelId.startsWith(prefix)) return v;
        }
        return null;
    }

    // Resolves the card for a model id and logs how it was matched:
    //   - exact model-id match       -> "Model card: found matching model: <id>"
    //   - partial (vendor prefix)     -> "Model card: found a partial match: <prefix>"
    //   - no match                    -> "Model card: none - using default config"
    // Returns the resolved card (or null when no match).
    private static BedrockModelCard resolveAndLogCard(String modelId, LogView log) {
        BedrockModelCard exact = exactCard(modelId);
        if (exact != null) {
            log.gray("Model card: found matching model: " + modelId);
            return exact;
        }
        BedrockModelCard vendor = vendorCard(modelId);
        if (vendor != null) {
            log.gray("Model card: found a partial match: " + vendor.vendorPrefix());
            return vendor;
        }
        log.gray("Model card: none - using default config (Chat Completions on /v1)");
        return null;
    }
    // All JRock files live under a "JRock" subfolder of the working directory.
    private static Path jrockDir()         { return workingDir.resolve("JRock"); }
    private static Path promptFile()       { return jrockDir().resolve("jrock-prompt.txt"); }
    private static Path logFile()          { return jrockDir().resolve("jrock-log.txt"); }
    private static Path logsDir()          { return jrockDir().resolve("messages"); }
    private static Path gsPdfDir()         { return jrockDir().resolve("gs-pdf"); }

    // Resolves the effective API key: the in-memory override from the Configure
    // dialog first, then the BEDROCK_API_KEY env var. Returns null when neither is
    // set - which is normal in the browser, where the key stays in the page and
    // the JVM never sees it (see HttpTransport.hostHoldsCredentials()).
    private static String resolveApiKey() {
        if (apiKeyOverride != null && !apiKeyOverride.isBlank()) return apiKeyOverride;
        return env("BEDROCK_API_KEY");
    }

    // ---- UI ----------------------------------------------------------------
    public static void main(String[] args) {
        // Settle the HTTP transport before anything is shown: in the browser this
        // also adopts the region configured by the hosting page, which the startup
        // log and the Configure dialog then report.
        http();
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

    // Filesystem layout for persistence (resolved against workingDir via
    // logFile() / logsDir(), so they follow the configured working directory).
    private static final DateTimeFormatter STAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    // Human-readable local date/time in the user's locale (e.g.
    // "Monday, 14 September 2026, 10:01:34"). Used for the startup line and, in
    // extend mode, the dialog header timestamps.
    private static String humanNow() {
        return java.time.format.DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.FULL, java.time.format.FormatStyle.MEDIUM)
                .withLocale(java.util.Locale.getDefault())
                .format(java.time.ZonedDateTime.now());
    }

    // ---- Multimodal includes (Ctrl+I) --------------------------------------
    // Non-persistent map of file hash -> path. Cleared on restart (users must
    // re-include files to reuse them). Text and images share this map; the token
    // kind (@img/@txt) in the prompt disambiguates how each is sent.
    private static final java.util.Map<String, Path> INCLUDES = new java.util.HashMap<>();

    // Hex digits kept from a file's SHA-256. Enough to identify a handful of
    // attachments per session without the token dominating the prompt and the log.
    private static final int HASH_LEN = 12;

    // Prompt token that stands in for an included file: "@img <hash>" or "@txt <hash>".
    // Hash is a shortened hex SHA-256. Matched anywhere in the prompt. The trailing
    // lookahead requires the hash to end there, so a longer hex run isn't read as a
    // token plus leftover text.
    private static final java.util.regex.Pattern INCLUDE_TOKEN =
            java.util.regex.Pattern.compile("@(img|txt) ([0-9a-f]{" + HASH_LEN + "})(?![0-9a-f])");

    // The first HASH_LEN hex digits of a file's SHA-256. Null on read failure.
    private static String hashFile(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(HASH_LEN);
            for (byte b : digest) {
                if (sb.length() >= HASH_LEN) break;
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, HASH_LEN);
        } catch (Exception ex) {
            return null;
        }
    }

    // Formats an integer with the current locale's grouping (e.g. 123,123,123).
    private static String fmtNum(long n) {
        return java.text.NumberFormat.getIntegerInstance(java.util.Locale.getDefault()).format(n);
    }

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
    // the headers + dialog, which matches `cat JRock/messages/*.txt`.
    private static final class LogView {
        private static final class Entry {
            final boolean dialog;      // true = dialog message, false = gray inline
            final String role;         // dialog: ROLE_* ; gray: null
            final String stamp;        // dialog: yyyyMMdd-HHmmss-SSS ; gray: null
            final String text;         // body text (dialog) or gray text
            final String headerSuffix; // dialog: text after "[role]" on the header
                                       // line (e.g. " Monday, 14 Sep 2026, ..."), or ""
            Entry(boolean dialog, String role, String stamp, String text, String headerSuffix) {
                this.dialog = dialog; this.role = role; this.stamp = stamp;
                this.text = text; this.headerSuffix = headerSuffix;
            }
        }

        private final JTextPane pane;
        private final java.util.List<Entry> entries = new ArrayList<>();
        private boolean dialogOnly = false;

        // Tracks whether the log has been exported (Ctrl+L) since it last changed.
        // An empty/just-cleared log counts as "saved" (nothing worth keeping). Set
        // false on any new entry; set true after a successful Save log copy.
        private boolean logCopySaved = true;

        LogView(JTextPane pane) { this.pane = pane; }

        // ---- Public logging API --------------------------------------------
        void gray(String line) {
            SwingUtilities.invokeLater(() -> {
                Entry e = new Entry(false, null, null, line, "");
                entries.add(e);
                logCopySaved = false;
                if (isVisible(e)) renderGray(e.text);
                persistMainLog();
            });
        }

        // withTimestamp: in "extend" (stateful-looking) mode we stamp the header
        // with a human-readable local time so the time order is visible.
        void human(String text, boolean withTimestamp)     { dialog(ROLE_HUMAN, text, withTimestamp); }
        void assistant(String text, boolean withTimestamp) { dialog(ROLE_ASSISTANT, text, withTimestamp); }

        private void dialog(String role, String text, boolean withTimestamp) {
            SwingUtilities.invokeLater(() -> {
                String stamp = LocalDateTime.now().format(STAMP_FMT);
                String suffix = withTimestamp ? " \u00B7 " + humanNow() : "";
                Entry e = new Entry(true, role, stamp, text, suffix);
                entries.add(e);
                logCopySaved = false;
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
                logCopySaved = true;   // nothing left to save
                atomicWriteQuietly(logFile(), "");  // overwrite main log with empty
            });
        }

        // Whether the log has been exported since it last changed (see the field).
        boolean isLogCopySaved() { return logCopySaved; }

        // Marks the log as exported; call after a successful Save log copy.
        void markLogCopySaved() { logCopySaved = true; }

        // Whether there is any content at all (an empty log needs no save prompt).
        boolean isEmpty() { return entries.isEmpty(); }

        // The text the user has highlighted in the log pane, or null when nothing
        // is selected. Save log copy and Print narrow themselves to this when it is
        // present, so an excerpt can be exported without the whole transcript. It
        // is the text AS SHOWN (so in "Dialog only" mode the hidden gray lines are
        // absent), which is what the user selected and therefore expects to get.
        String selectedText() {
            String sel = pane.getSelectedText();
            return (sel == null || sel.isEmpty()) ? null : sel;
        }

        // Serializes the FULL log (all entries, regardless of "Dialog only" view)
        // to plain text for export: each dialog entry as its "[role]<suffix>"
        // header line followed by its body, gray entries as their line. This is
        // the on-screen transcript, not the on-disk @<stamp> reference form.
        String fullText() {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                if (e.dialog) {
                    sb.append('[').append(e.role).append(']').append(e.headerSuffix).append('\n');
                    sb.append(e.text).append('\n');
                } else {
                    sb.append(e.text).append('\n');
                }
            }
            return sb.toString();
        }

        // Returns the dialog turns so far, in order, as {role, text} pairs
        // (role is ROLE_HUMAN or ROLE_ASSISTANT). Used to build a multi-turn
        // request in "extend" mode. Gray entries are excluded.
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
                logCopySaved = true;   // just loaded from disk; no unsaved changes
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
            String content = readFileQuietly(logFile());
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
                        // Everything after "[role]" on the header line (e.g. a
                        // localized timestamp) is preserved so it round-trips.
                        String suffix = line.substring(("[" + role + "]").length());
                        out.add(new Entry(true, role, stamp, body, suffix));
                        i += 2;
                        continue;
                    }
                    // Unresolvable reference: fall through and keep the header as a
                    // plain gray line so nothing is silently dropped.
                }
                out.add(new Entry(false, null, null, line, ""));
                i++;
            }
            return out;
        }

        // Returns the role if the line STARTS WITH that role's header (ignoring any
        // trailing text such as a timestamp), else null.
        private String headerRole(String line) {
            if (line.startsWith("[" + ROLE_HUMAN + "]")) return ROLE_HUMAN;
            if (line.startsWith("[" + ROLE_ASSISTANT + "]")) return ROLE_ASSISTANT;
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

        // Renders the branded header and the (possibly multi-line) body. In the
        // normal view the blank line after a message comes from a separate gray ""
        // entry. In "Dialog only" mode those gray separators are hidden, so we add
        // one blank line here to keep messages visually separated. This affects
        // only the on-screen view, never the stored entries or files.
        private void renderDialog(Entry e) {
            appendStyled("[" + e.role + "]" + e.headerSuffix, BRAND);
            appendStyled(e.text, java.awt.Color.BLACK);
            if (dialogOnly) appendStyled("", java.awt.Color.BLACK);
        }

        private void renderGray(String text) { appendStyled(addWrapPoints(text), java.awt.Color.GRAY); }

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
        //   dialog entry -> "[<ROLE>]<headerSuffix>\n" + "@<stamp>\n"
        //     (headerSuffix is "" normally, or " <localized time>" in extend mode)
        private void persistMainLog() {
            StringBuilder sb = new StringBuilder();
            for (Entry e : entries) {
                if (e.dialog) {
                    sb.append('[').append(e.role).append(']').append(e.headerSuffix).append('\n');
                    sb.append('@').append(e.stamp).append('\n');
                } else {
                    sb.append(e.text).append('\n');
                }
            }
            atomicWriteQuietly(logFile(), sb.toString());
        }
    }

    // Longest run of non-whitespace left with nowhere to wrap.
    private static final int GRAY_RUN_MAX = 40;

    // The pane can only wrap at whitespace, and the raw request/response are
    // single-line JSON with almost none. So once a run has gone this long without
    // whitespace, insert a space after its next "," or ":" and let Swing wrap: such a
    // space is insignificant in JSON, and the line stays one line.
    //
    // This is a view-only transformation, applied on the way into the pane. The stored
    // entries, the on-disk log and the exports keep the original text.
    private static String addWrapPoints(String text) {
        if (text == null || text.length() <= GRAY_RUN_MAX) return text;
        StringBuilder out = new StringBuilder(text.length() + 64);
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (Character.isWhitespace(c)) { run = 0; continue; }
            run++;
            if (run > GRAY_RUN_MAX && (c == ',' || c == ':')
                    && i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1))) {
                out.append(' ');
                run = 0;
            }
        }
        return out.toString();
    }

    // Emits the session report into the log. Used at startup AND after the
    // Configure dialog. The FIRST line is always the current working directory.
    // promptSourceNote is logged only when non-null (startup); on reconfigure the
    // prompt is untouched so it's omitted.
    private static void initSession(LogView log, String promptSourceNote) {
        // Every file chooser starts fresh in the (possibly new) working directory.
        promptChooserDir.reset();
        includeChooserDir.reset();
        logChooserDir.reset();

        // Recover any previous log from disk FIRST. loadFromDisk() replaces the
        // entry list (rebuild -> setText), so it must run before we log anything
        // for this session, otherwise those lines would be wiped.
        boolean hadLog = Files.exists(logFile());
        int restored = log.loadFromDisk();

        // The session report begins here; the working directory is its first line.
        // A restored log already ends with its own trailing blank line.
        log.gray("Working directory: " + workingDir);
        // App identity + local date/time in the user's locale/format.
        log.gray("JRock version " + VERSION + " - " + humanNow());
        if (hadLog) {
            log.gray("Loaded previous log from JRock/jrock-log.txt");
        } else {
            log.gray("New log file created: JRock/jrock-log.txt");
        }
        log.gray("Messages also stored in JRock/messages/ directory.");

        log.gray("HTTP transport: " + http().describe()
                + (isCheerpJ() ? " (CheerpJ browser runtime)" : ""));
        if (http().hostHoldsCredentials()) {
            log.gray("Bedrock API key: held by the hosting page, not by JRock");
        }
        if (regionSource != null) {
            log.gray("AWS region: " + REGION + " (" + regionSource + ")");
        } else {
            log.gray("AWS region: " + REGION);
        }
        // The one place the host is spelled out; later request lines log paths only.
        log.gray("Bedrock endpoint: " + mantleHost());
        log.gray("Configured model: " + MODEL_ID);
        resolveAndLogCard(MODEL_ID, log);
        if (promptSourceNote != null) {
            log.gray("Prompt source: " + promptSourceNote);
        }
        log.gray("Autosaving prompt to JRock/jrock-prompt.txt");
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
                // The one blank line that precedes its text, so "Ready." stands out
                // at the end of the session report rather than trailing the last line.
                log.gray("");
                log.gray("Ready.");
                log.gray("");
            }
        }.execute();
    }

    // Calls GET /v1/models on the mantle endpoint and returns a compact,
    // comma-separated list of model ids. Never throws - returns a status
    // string on failure so startup logging is best-effort.
    private static String listAvailableModels() {
        HttpTransport http = http();
        String apiKey = resolveApiKey();
        boolean haveKey = apiKey != null && !apiKey.isBlank();
        if (!haveKey && !http.hostHoldsCredentials()) {
            return "(skipped - no Bedrock API key set)";
        }
        try {
            List<String[]> headers = new ArrayList<>();
            if (haveKey) {
                headers.add(new String[] { "Authorization", "Bearer " + apiKey.trim() });
            }
            HttpReply resp = http.send("GET", modelsEndpoint(), headers,
                    null, MODELS_TIMEOUT_SECONDS);
            if (resp.status != 200) {
                return "(HTTP " + resp.status + " from " + pathOf(modelsEndpoint()) + ")";
            }
            List<String> ids = extractModelIds(resp.body);
            if (!ids.isEmpty()) {
                java.util.Collections.sort(ids);         // alphabetical order
                availableModels = ids;                   // cache for the Configure dropdown
            }
            return ids.isEmpty() ? "(none parsed; raw: " + resp.body + ")"
                    : String.join(", ", ids);
        } catch (Throwable ex) {
            // Throwable, not Exception: a JVM without a socket layer fails with an
            // Error (UnsatisfiedLinkError) rather than an exception.
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
        // Put the working-directory folder name FIRST in the title so it stays
        // visible even when Alt-Tab truncates ("myproj - JRock" rather than
        // "JRock - Bedro..."), so instances in different folders are
        // distinguishable. Falls back to a plain title at a filesystem root.
        //
        // Not in the browser: there is only ever one instance, and the directory is
        // CheerpJ's own virtual mount (so the title read "file - JRock" and the icon
        // said "FIL") - a folder name the user never chose and cannot act on. The
        // plain title and the plain "JR" icon are the right answer there.
        Path folderPath = isCheerpJ() ? null : workingDir.getFileName();
        String folder = folderPath != null ? folderPath.toString() : null;
        String title = (folder != null && !folder.isBlank())
                ? folder + " - JRock"
                : "JRock - Bedrock (mantle)";
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // App icon; when launched into a folder, overlay a short abbreviation so
        // each instance is visually distinct in the taskbar / Alt-Tab.
        frame.setIconImages(makeAppIcons(folder));

        // Preferred window size; if the screen can't fit it in either dimension,
        // start maximized, otherwise center it on screen.
        final int WIN_W = 1050, WIN_H = 800;
        frame.setSize(WIN_W, WIN_H);
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        if (screen.width < WIN_W || screen.height < WIN_H) {
            frame.setExtendedState(frame.getExtendedState() | JFrame.MAXIMIZED_BOTH);
        } else {
            frame.setLocationRelativeTo(null);   // center on screen
        }

        JTextPane output = new JTextPane();
        output.setEditable(false);
        output.setMargin(new java.awt.Insets(8, 8, 8, 8));
        LogView log = new LogView(output);

        // Top bar: [Configure] on the left; [Dialog only] + [Clear log] on the right.
        JButton configure = new JButton("Configure");
        configure.setToolTipText("Working directory, API key, region, model");
        // (Listener wired below, once `input` exists.)

        javax.swing.JCheckBox dialogOnly = new javax.swing.JCheckBox("Dialog only");
        dialogOnly.setToolTipText("Show only the headers and dialog (hide gray system text)");
        dialogOnly.addActionListener(e -> log.setDialogOnly(dialogOnly.isSelected()));
        JButton clear = new JButton("Clear log");
        clear.addActionListener(e -> clearLogConfirmed(frame, log));

        javax.swing.JPanel topRight = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 4));
        topRight.add(dialogOnly);
        topRight.add(clear);
        javax.swing.JPanel topLeft = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 4));
        topLeft.add(configure);

        javax.swing.JPanel topBar = new javax.swing.JPanel(new BorderLayout());
        topBar.add(topLeft, BorderLayout.WEST);
        topBar.add(topRight, BorderLayout.EAST);

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
            String fromPersist = readFileQuietly(promptFile());
            if (fromPersist != null) {
                initialPrompt = fromPersist;
                promptSource = "recovered persistent file: JRock/jrock-prompt.txt";
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

        // Emit the startup session report (CWD, log recovery, region/model, model
        // list, Ready). Reused verbatim after reconfiguration.
        initSession(log, promptSource);

        // "Extend conversation" mode: when on, each send includes the full prior
        // dialog so the model sees a continuous conversation, not a single message.
        javax.swing.JCheckBox extendMode = new javax.swing.JCheckBox("Extend conversation");
        extendMode.setToolTipText("Send the whole prior dialog with each message (continuous chat)");

        JButton send = new JButton("Send (Ctrl-Enter)");
        send.addActionListener(e -> {
            String prompt = input.getText().trim();
            if (prompt.isEmpty()) {
                log.gray("Nothing to send - type a prompt first...");
                log.gray("");
                return;
            }
            send.setEnabled(false);
            // In "extend" mode, capture the prior dialog turns BEFORE adding the new
            // prompt, so the request is [history...] + [new prompt].
            boolean extend = extendMode.isSelected();
            java.util.List<String[]> history = extend
                    ? log.dialogHistory() : java.util.Collections.emptyList();
            log.human(prompt, extend);
            log.gray("");                        // closes the input message block

            // Verify all @img/@txt includes are known and unchanged - in the new
            // prompt AND in prior human turns (extend mode re-sends those, expanding
            // their tokens too). On any problem, report it right after the
            // [HUMAN OPERATOR] message and do not send.
            String includeError = verifyIncludes(prompt);
            if (includeError == null) {
                for (String[] turn : history) {
                    if (ROLE_HUMAN.equals(turn[0])) {
                        includeError = verifyIncludes(turn[1]);
                        if (includeError != null) break;
                    }
                }
            }
            if (includeError != null) {
                log.gray(includeError);
                log.gray("");
                send.setEnabled(true);
                return;
            }

            // If the model isn't served via Chat Completions on mantle, don't even
            // log "Calling ..." - report why and stop, without any HTTP request.
            BedrockModelCard card = cardFor(MODEL_ID);
            if (card != null && !card.supportsMantleChatCompletions()) {
                String via = card.mantleMessagesPath() != null
                        ? "the Anthropic Messages API (" + card.mantleMessagesPath() + ")"
                        : "an API JRock does not implement";
                log.gray("Model \"" + MODEL_ID + "\" is not served via Chat Completions on "
                        + "bedrock-mantle; it uses " + via + ", which JRock does not implement "
                        + "yet. Choose a Chat-Completions-capable model.");
                log.gray("");
                send.setEnabled(true);
                return;
            }

            log.gray(extend
                    ? "Calling " + pathOf(endpoint()) + " (extend: " + history.size() + " prior turns) ..."
                    : "Calling " + pathOf(endpoint()) + " ...");
            new SwingWorker<String[], Void>() {
                @Override
                protected String[] doInBackground() {
                    try {
                        return callModel(prompt, history);
                    } catch (Throwable ex) {
                        // Catch Throwable, not just Exception: a JVM without a real
                        // socket layer (CheerpJ in the browser, with no page-side
                        // HTTP bridge installed) fails inside HttpClient's native
                        // networking code (Java_sun_nio_ch_EPoll_*) with an
                        // UnsatisfiedLinkError, which is an Error. Print the
                        // failure as-is, then add a hint for that case.
                        String msg = "ERROR: " + ex.getClass().getSimpleName()
                                + ": " + ex.getMessage();
                        if (ex instanceof UnsatisfiedLinkError) {
                            msg += "\n" + NO_TRANSPORT_HINT;
                        }
                        return new String[] { "0", msg, null };
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
                            // The reply is its own block, so the blank line here
                            // closes the "Calling ..." one. A real reply is dialog:
                            // branded header + black text, persisted to its own file.
                            log.gray("");        // closes the "Calling ..." block
                            log.assistant(result[1], extend);
                            log.gray("");        // closes the reply block
                        } else {
                            // Failures are NOT dialog: log in gray so they don't
                            // pollute the transcript or "Dialog only" view. The reason
                            // is the outcome of the "Calling ..." block, so it goes in
                            // that block and the blank line closes them together.
                            log.gray(result[1]);
                            log.gray("");
                        }
                        if (result[2] != null) {
                            log.gray(result[2]);
                            log.gray("");        // closes the raw request/response/stats block
                        }
                    } catch (Exception ex) {
                        log.gray("ERROR: " + ex.getMessage());
                        log.gray("");
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

        // Bottom bar: Send on the left, Extend checkbox on the right, same row.
        javax.swing.JPanel sendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        sendSide.add(send);
        javax.swing.JPanel extendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        extendSide.add(extendMode);

        javax.swing.JPanel buttonBar = new javax.swing.JPanel(new BorderLayout());
        // Smaller top gap than bottom so the row visually groups with the text
        // area above it, while keeping clearance from the window's bottom edge.
        buttonBar.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 12, 14, 12));
        buttonBar.add(sendSide, BorderLayout.WEST);
        buttonBar.add(extendSide, BorderLayout.EAST);

        frame.add(topBar, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(buttonBar, BorderLayout.SOUTH);

        // Hidden feature: Ctrl+M opens a Move & resize dialog. Bound at the window
        // level so it fires regardless of which component has focus. (Ctrl+M is
        // used instead of Ctrl+R, which reloads the page in the browser build.)
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK), "jrock-move-resize");
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

        // Ctrl+L: save a COPY of the current log to a file the user chooses
        // (symmetric to Ctrl+S for the prompt). Marks the log as exported so
        // Clear log won't warn about unsaved changes.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "jrock-save-log");
        frame.getRootPane().getActionMap().put("jrock-save-log", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                saveLogAs(frame, log);
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

        // Ctrl+D toggles the "Dialog only" checkbox (doClick keeps the checkbox,
        // its action listener and the view in sync).
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "jrock-toggle-dialog-only");
        frame.getRootPane().getActionMap().put("jrock-toggle-dialog-only", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { dialogOnly.doClick(); }
        });

        // Ctrl+E toggles "Extend conversation" (E = extend; avoids Ctrl+A/C).
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_E, InputEvent.CTRL_DOWN_MASK), "jrock-toggle-extend");
        frame.getRootPane().getActionMap().put("jrock-toggle-extend", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { extendMode.doClick(); }
        });

        // Ctrl+P opens the system print dialog for the log pane. JTextComponent.print()
        // handles pagination and shows the native dialog, where the user can pick a
        // printer (including "Microsoft Print to PDF" on Windows) or save to PDF.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK), "jrock-print");
        frame.getRootPane().getActionMap().put("jrock-print", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { printLog(frame, output); }
        });

        // Ctrl+I includes a text or image file: hashes it, remembers hash -> path,
        // and inserts an "@img <hash>" / "@txt <hash>" token at the prompt cursor.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "jrock-include");
        frame.getRootPane().getActionMap().put("jrock-include", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showIncludeDialog(frame, input, log, extendMode.isSelected());
            }
        });

        // Configure button: opens the settings dialog, then re-runs the session
        // report (CWD first, models loaded, ... Ready) exactly like startup.
        // initSession reloads the log from the (possibly new) working directory,
        // fully replacing the window contents - so switching directories shows
        // only the new directory's log, with nothing carried over from the old one.
        configure.addActionListener(e -> {
            if (showConfigureDialog(frame)) {
                initSession(log, null);   // no prompt-source line on reconfigure
            }
        });

        // ---- Right-click context menus -------------------------------------
        // setComponentPopupMenu wires the platform-appropriate popup trigger.
        // Each item calls the same handler as its keyboard shortcut / button.

        // Log pane: Save log copy as... / Print... - both of which act on the
        // SELECTION when text is selected, so the labels are rewritten each time the
        // menu opens to name what will actually be saved or printed.
        javax.swing.JPopupMenu logMenu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem saveLogItem =
                addMenuItem(logMenu, "Save log copy as...", () -> saveLogAs(frame, log));
        javax.swing.JMenuItem printLogItem =
                addMenuItem(logMenu, "Print...",            () -> printLog(frame, output));
        logMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean selected = log.selectedText() != null;
                saveLogItem.setText(selected ? "Save selected text as..." : "Save log copy as...");
                printLogItem.setText(selected ? "Print selected text..."  : "Print...");
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        attachPopup(output, logMenu);

        // Prompt area: Include... / Load prompt... / Save prompt copy...
        javax.swing.JPopupMenu promptMenu = new javax.swing.JPopupMenu();
        addMenuItem(promptMenu, "Include text or image file...",
                () -> showIncludeDialog(frame, input, log, extendMode.isSelected()));
        addMenuItem(promptMenu, "Load prompt from file...",
                () -> loadPromptInto(frame, input, log));
        addMenuItem(promptMenu, "Save prompt copy as...",
                () -> savePromptAs(frame, input.getText()));
        attachPopup(input, promptMenu);

        // Window chrome (empty area of the top bar, e.g. right of Configure):
        // Move & resize window...; on Windows, also install/uninstall the
        // "Open JRock here" folder context-menu entry.
        javax.swing.JPopupMenu windowMenu = new javax.swing.JPopupMenu();
        addMenuItem(windowMenu, "Move & resize window...", () -> showMoveResizeDialog(frame));
        if (isWindows()) {
            windowMenu.addSeparator();
            addMenuItem(windowMenu, "Install \"JRock here!\" (Explorer menu)...",
                    () -> installContextMenu(frame, log));
            addMenuItem(windowMenu, "Uninstall \"JRock here!\" (Explorer menu)...",
                    () -> uninstallContextMenu(frame, log));
        }
        attachPopup(topBar, windowMenu);

        frame.setVisible(true);
    }

    // Adds a JMenuItem running the given action to a popup menu, and returns it for
    // callers that relabel it later (see the log pane's selection-aware items).
    private static javax.swing.JMenuItem addMenuItem(javax.swing.JPopupMenu menu,
                                                     String label, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
        return item;
    }

    // Shows a popup menu on a native right-click (desktop) OR a long-press
    // (touch). We can't use setComponentPopupMenu alone because CheerpJ on mobile
    // delivers a touch as a plain left-click, never a Swing popup-trigger event,
    // so a press-and-hold timer synthesizes the popup for touchscreens. The timer
    // is cancelled if the pointer is released early or dragged away, so it won't
    // fire during scrolling or text selection.
    private static final int LONG_PRESS_MS = 500;   // press-and-hold threshold
    private static final int LONG_PRESS_SLOP = 12;   // px of movement that cancels it
    private static void attachPopup(javax.swing.JComponent comp, javax.swing.JPopupMenu menu) {
        java.awt.event.MouseAdapter h = new java.awt.event.MouseAdapter() {
            private javax.swing.Timer timer;
            private java.awt.Point origin;

            private void showAt(int x, int y) { menu.show(comp, x, y); }

            private void cancel() {
                if (timer != null) { timer.stop(); timer = null; }
                origin = null;
            }

            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) { showAt(e.getX(), e.getY()); return; }   // desktop right-click
                // Otherwise arm a long-press timer for touch/left-press.
                cancel();
                origin = e.getPoint();
                final int x = e.getX(), y = e.getY();
                timer = new javax.swing.Timer(LONG_PRESS_MS, ev -> { cancel(); showAt(x, y); });
                timer.setRepeats(false);
                timer.start();
            }

            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) { cancel(); showAt(e.getX(), e.getY()); return; }
                cancel();   // released before the threshold: normal click, no menu
            }

            @Override public void mouseDragged(java.awt.event.MouseEvent e) {
                // Moving too far (scroll/select) cancels the pending long-press.
                if (origin != null && origin.distance(e.getPoint()) > LONG_PRESS_SLOP) cancel();
            }

            @Override public void mouseExited(java.awt.event.MouseEvent e) { cancel(); }
        };
        comp.addMouseListener(h);
        comp.addMouseMotionListener(h);
    }

    // ---- Configure dialog --------------------------------------------------
    // Shows working directory, API key (write-only override), region and model.
    // Returns true if the user applied changes (so the caller re-inits the session).
    private static boolean showConfigureDialog(JFrame frame) {
        javax.swing.JTextField cwdF    = new javax.swing.JTextField(workingDir.toString(), 30);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(ev -> {
            javax.swing.JFileChooser dc = new javax.swing.JFileChooser(cwdF.getText().trim());
            dc.setDialogTitle("Choose working directory");
            dc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (dc.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION
                    && dc.getSelectedFile() != null) {
                cwdF.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });
        javax.swing.JPanel cwdRow = new javax.swing.JPanel(new BorderLayout(4, 0));
        cwdRow.add(cwdF, BorderLayout.CENTER);
        cwdRow.add(browse, BorderLayout.EAST);
        javax.swing.JPasswordField keyF = new javax.swing.JPasswordField(24); // never prefilled
        javax.swing.JTextField regionF = new javax.swing.JTextField(REGION, 16);
        // In the browser the API key belongs to the hosting page, so JRock has no
        // key to show or set: the row is left out entirely rather than offered as a
        // field that would have no effect.
        boolean hostKey = http().hostHoldsCredentials();
        // Editable combo: free text, plus a dropdown of the most recently fetched
        // available models (empty until the first successful /v1/models call).
        javax.swing.JComboBox<String> modelF =
                new javax.swing.JComboBox<>(availableModels.toArray(new String[0]));
        modelF.setEditable(true);
        modelF.setSelectedItem(MODEL_ID);   // shows current value; typeable
        modelF.setFont(modelF.getFont().deriveFont(java.awt.Font.PLAIN));  // not bold
        modelF.getEditor().getEditorComponent()
                .setFont(modelF.getFont().deriveFont(java.awt.Font.PLAIN));

        javax.swing.JPanel fields = new javax.swing.JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(fields, c, row++, "Working directory:", cwdRow);
        if (!hostKey) {
            addRow(fields, c, row++, "BEDROCK_API_KEY:", keyF);
        }
        addRow(fields, c, row++, "AWS_REGION:", regionF);
        addRow(fields, c, row++, "Model:", modelF);

        // A plain (non-bold) font derived from the default label font, reused for
        // the notes, shortcuts and titled-border titles so nothing renders bold.
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        java.awt.Font plainFont = (base != null)
                ? base.deriveFont(java.awt.Font.PLAIN)
                : new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);

        String keyNote = hostKey
            ? "There is no API key field here: in the browser the Bedrock API key "
            + "belongs to the hosting page, which holds it in JavaScript and adds it "
            + "to each request itself - so it is never handed to the JVM. Change it "
            + "on the page.\n\n"
            : "The API key field is intentionally blank and write-only: leave it empty "
            + "to keep the current key (env var or a previous override); type a value "
            + "to override it for this session. The key is never displayed.\n\n";

        javax.swing.JTextArea note = new javax.swing.JTextArea(
            keyNote
          + "Clearing the log only clears jrock-log.txt (and the window); the "
          + "per-message files in JRock/messages/ are never deleted, so your inputs "
          + "and outputs are preserved.\n\n"
          + "Note: a true OS process chdir isn't possible from Java, so changing the "
          + "working directory reroutes JRock's own files (a JRock/ subfolder holding "
          + "the prompt, log and messages/) to the new directory rather than changing "
          + "the OS-level CWD of the process.");
        note.setEditable(false);
        note.setOpaque(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setFont(plainFont);

        // Shortcuts as a 2-column grid so keys and descriptions align cleanly
        // (no space-padding). The "Shortcuts" border title keeps the default bold.
        String[][] keys = {
            {"Ctrl+Enter", "Send message (call a Bedrock model)"},
            {"Ctrl+I", "Include a text or image file"},
            {"Ctrl+D", "Toggle Dialog only"},
            {"Ctrl+E", "Toggle Extend conversation"},
            {"Ctrl+S", "Save prompt as (a copy)"},
            {"Ctrl+O", "Load prompt from a file"},
            {"Ctrl+L", "Save log as (a copy, or just the selected text)"},
            {"Ctrl+P", "Print log (or the selected text) / save as PDF"},
        };
        javax.swing.JPanel shortcuts = new javax.swing.JPanel(new java.awt.GridBagLayout());
        shortcuts.setBorder(javax.swing.BorderFactory.createTitledBorder("Shortcuts"));
        java.awt.GridBagConstraints sc = new java.awt.GridBagConstraints();
        sc.anchor = java.awt.GridBagConstraints.WEST;
        sc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        sc.insets = new java.awt.Insets(1, 4, 1, 4);
        for (int r = 0; r < keys.length; r++) {
            javax.swing.JLabel keyLbl = new javax.swing.JLabel(keys[r][0]);
            keyLbl.setFont(plainFont);
            javax.swing.JLabel descLbl = new javax.swing.JLabel(keys[r][1]);
            descLbl.setFont(plainFont);
            // Golden-ratio-ish column weights: key column narrow (~38%), desc wide.
            sc.gridx = 0; sc.gridy = r; sc.weightx = 0.38;
            shortcuts.add(keyLbl, sc);
            sc.gridx = 1; sc.weightx = 0.62;
            shortcuts.add(descLbl, sc);
        }

        // About line (bold, default L&F) + a short plain description.
        // "JRock" is a hyperlink to the project on GitHub. Swing labels render
        // HTML for the link look but don't open URLs themselves, so a click
        // handler (below) opens the default browser.
        javax.swing.JLabel about = new javax.swing.JLabel(
                "<html><a href=\"" + GITHUB_URL + "\">JRock</a> version "
                + VERSION + " (c) 2026</html>");
        about.setToolTipText(GITHUB_URL);
        about.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        about.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { openUrl(GITHUB_URL); }
        });
        javax.swing.JTextArea desc = new javax.swing.JTextArea(
            "The Amazon Bedrock desktop GUI client in Java that just works: every prompt and session "
          + "is saved to disk so nothing is ever lost, and your credentials stay put "
          + "with no repeated sign-ins - so it keeps out of your way and lets you "
          + "focus on the models.\n\n"
          + "By Ivan Khvostishkov, with assistance of Kiro and JetBrains IntelliJ IDEA.");
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setFont(plainFont);

        javax.swing.JPanel aboutBox = new javax.swing.JPanel(new BorderLayout(0, 4));
        aboutBox.add(about, BorderLayout.NORTH);
        aboutBox.add(desc, BorderLayout.CENTER);
        aboutBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 8, 2));

        javax.swing.JPanel north = new javax.swing.JPanel(new BorderLayout(8, 8));
        north.add(aboutBox, BorderLayout.NORTH);
        north.add(fields, BorderLayout.CENTER);
        north.add(shortcuts, BorderLayout.SOUTH);

        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 8));
        panel.add(north, BorderLayout.NORTH);
        javax.swing.JScrollPane noteScroll = new javax.swing.JScrollPane(note);
        noteScroll.setBorder(javax.swing.BorderFactory.createTitledBorder("Notes"));
        noteScroll.setPreferredSize(new java.awt.Dimension(460, 150));
        noteScroll.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        panel.add(noteScroll, BorderLayout.CENTER);

        int result = javax.swing.JOptionPane.showConfirmDialog(
                frame, panel, "Configure",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (result != javax.swing.JOptionPane.OK_OPTION) return false;

        // Working directory.
        String cwdText = cwdF.getText().trim();
        if (!cwdText.isEmpty()) {
            Path newDir = Paths.get(cwdText).toAbsolutePath().normalize();
            try {
                Files.createDirectories(newDir);
                workingDir = newDir;
                // Best-effort: also update user.dir so relative paths elsewhere align.
                System.setProperty("user.dir", newDir.toString());
            } catch (IOException ex) {
                javax.swing.JOptionPane.showMessageDialog(frame,
                        "Could not use working directory " + newDir + ":\n" + ex.getMessage(),
                        "Invalid directory", javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        }

        // API key override: only set if the user typed something. Never store the
        // env value; an empty field means "keep whatever is already in effect".
        // Skipped when the host holds the key - the field wasn't even shown.
        if (!hostKey) {
            char[] key = keyF.getPassword();
            if (key.length > 0) {
                apiKeyOverride = new String(key);
            }
            java.util.Arrays.fill(key, '\0');   // wipe the transient char[]
        }

        // Region + model (free text).
        String r = regionF.getText().trim();
        if (!r.isEmpty()) { REGION = r; regionSource = null; }
        Object selected = modelF.getEditor().getItem();  // typed or picked value
        String m = (selected == null ? "" : selected.toString().trim());
        if (!m.isEmpty()) { MODEL_ID = m; }

        return true;
    }

    // Adds a "label: field" row to a GridBagLayout panel.
    private static void addRow(javax.swing.JPanel p, java.awt.GridBagConstraints c,
                               int row, String label, javax.swing.JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        p.add(new javax.swing.JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        p.add(field, c);
    }

    // ---- Save / load prompt (Ctrl+S / Ctrl+O) ------------------------------
    // Saves a copy of the given text to a user-chosen file. Does NOT touch the
    // persistent jrock-prompt.txt; this is an extra export.
    private static void savePromptAs(JFrame frame, String text) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(promptChooserDir.start());
        chooser.setDialogTitle("Save prompt copy as");
        chooser.setSelectedFile(promptChooserDir.startFile("jrock-prompt-copy.txt"));
        if (chooser.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        promptChooserDir.remember(chooser);

        Path target = chooser.getSelectedFile().toPath();
        try {
            Files.write(target, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not save to " + target + ":\n" + ex.getMessage(),
                    "Save failed", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Saves a copy of the on-screen log to a user-chosen file (symmetric to
    // savePromptAs). If text is selected in the log pane, only that selection is
    // written - saving an excerpt is the common case for a long transcript, and the
    // selection is right there on screen saying which part. On success the log is
    // marked as exported so Clear log won't warn - but ONLY for a full save: a
    // partial export is not a copy of the log.
    private static void saveLogAs(JFrame frame, LogView log) {
        String selection = log.selectedText();
        if (selection == null && log.isEmpty()) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "The log is empty - nothing to save.",
                    "Save log copy", javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(logChooserDir.start());
        chooser.setDialogTitle(selection != null ? "Save selected text as" : "Save log copy as");
        chooser.setSelectedFile(logChooserDir.startFile(
                selection != null ? "jrock-log-selection.txt" : "jrock-log-copy.txt"));
        if (chooser.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        logChooserDir.remember(chooser);

        Path target = chooser.getSelectedFile().toPath();
        // A selection usually ends mid-line; terminate it, like every other line.
        String text = (selection != null)
                ? (selection.endsWith("\n") ? selection : selection + "\n")
                : log.fullText();
        try {
            Files.write(target, text.getBytes(StandardCharsets.UTF_8));
            if (selection == null) log.markLogCopySaved();
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not save to " + target + ":\n" + ex.getMessage(),
                    "Save failed", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Builds app icons (several sizes) for the window/taskbar. The base is a
    // rounded teal "JR" tile; when a folder name is given, a short 2-3 letter
    // abbreviation of it is drawn instead, so multiple instances launched in
    // different folders are distinguishable at a glance.
    private static java.util.List<java.awt.Image> makeAppIcons(String folder) {
        String label = (folder == null || folder.isBlank()) ? "JR" : abbreviate(folder);
        java.util.List<java.awt.Image> icons = new ArrayList<>();
        for (int size : new int[] { 16, 32, 48, 64, 128 }) {
            icons.add(makeIcon(size, label));
        }
        return icons;
    }

    // A 2-3 char uppercase abbreviation of a folder name: initials of the first
    // words (split on space/-/_/.), else the first letters of the name.
    private static String abbreviate(String name) {
        String[] parts = name.trim().split("[\\s._-]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (!p.isEmpty()) sb.append(Character.toUpperCase(p.charAt(0)));
            if (sb.length() >= 3) break;
        }
        if (sb.length() <= 1) {   // single word: take its first up-to-3 letters
            String s = name.trim();
            sb.setLength(0);
            sb.append(s, 0, Math.min(3, s.length()));
        }
        return sb.toString().toUpperCase();
    }

    private static java.awt.Image makeIcon(int size, String label) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Rounded brand-colored tile.
        int arc = Math.max(3, size / 4);
        g.setColor(BRAND);
        g.fillRoundRect(0, 0, size, size, arc, arc);
        // Label, shrunk to fit the tile width.
        g.setColor(java.awt.Color.WHITE);
        int fontSize = size;   // start large, shrink until it fits
        java.awt.Font font;
        java.awt.FontMetrics fm;
        int textW;
        do {
            font = new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, fontSize);
            fm = g.getFontMetrics(font);
            textW = fm.stringWidth(label);
            fontSize--;
        } while (textW > size * 0.82 && fontSize > 5);
        g.setFont(font);
        int x = (size - textW) / 2;
        int y = (size - fm.getHeight()) / 2 + fm.getAscent();
        g.drawString(label, x, y);
        g.dispose();
        return img;
    }

    // Opens a URL in the user's default browser, if the platform supports it.
    // Best-effort: failures are ignored (the tooltip still shows the address).
    private static void openUrl(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop d = java.awt.Desktop.getDesktop();
                if (d.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    d.browse(java.net.URI.create(url));
                }
            }
        } catch (Exception ignore) {
            // No browser available (e.g. headless/sandboxed); nothing to do.
        }
    }

    // ---- Windows "JRock here!" folder context menu -------------------------
    // A right-click entry in Windows Explorer that launches JRock in the folder
    // you clicked (Explorer sets the new process's current directory to it), so
    // JRock's JRock/ folder is created there. Installed per-user (HKCU, no admin,
    // reversible). Uses javaw (no console), so no cmd window ever appears.

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // Distinct key names so uninstall removes exactly what we created.
    private static final String CTX_KEY = "OpenJRockHere";
    private static final String CTX_LABEL = "JRock here!";
    private static final String[] CTX_SHELL_ROOTS = {
        "Software\\Classes\\Directory\\Background\\shell",  // right-click empty space in a folder
        "Software\\Classes\\Directory\\shell",              // right-click on a folder icon
    };

    // Right-click verb on .txt files: "Open as prompt with JRock" (no workdir).
    private static final String TXT_KEY = "OpenAsJRockPrompt";
    private static final String TXT_LABEL = "Open as prompt with JRock";
    // Adds a verb to .txt without changing the default open action.
    private static final String TXT_SHELL_ROOT =
        "Software\\Classes\\SystemFileAssociations\\.txt\\shell";

    private static void installContextMenu(JFrame frame, LogView log) {
        try {
            String javaw = findJavaw();
            if (javaw == null) {
                showCtxError(frame, log, "Could not find javaw.exe next to the running JVM.");
                return;
            }
            String folderLaunch = buildCtxLaunch(javaw);   // "JRock here!" (runs in the clicked folder)
            String txtLaunch = buildTxtLaunch(javaw);       // .txt verb (%1 -> prompt file)
            if (folderLaunch == null || txtLaunch == null) {
                showCtxError(frame, log, "Could not locate jrock.jar or JRock.java to launch. "
                        + "Run JRock from its own folder, or build jrock.jar first.");
                return;
            }
            StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
            // Folder verbs: "JRock here!"
            for (String root : CTX_SHELL_ROOTS) {
                String base = "HKEY_CURRENT_USER\\" + root + "\\" + CTX_KEY;
                reg.append('[').append(base).append("]\r\n");
                reg.append("@=").append(regString(CTX_LABEL)).append("\r\n");
                reg.append("\"Icon\"=").append(regString(javaw)).append("\r\n\r\n");
                reg.append('[').append(base).append("\\command]\r\n");
                reg.append("@=").append(regString(folderLaunch)).append("\r\n\r\n");
            }
            // .txt verb: "Open as prompt with JRock" (passes the file as the prompt).
            String txtBase = "HKEY_CURRENT_USER\\" + TXT_SHELL_ROOT + "\\" + TXT_KEY;
            reg.append('[').append(txtBase).append("]\r\n");
            reg.append("@=").append(regString(TXT_LABEL)).append("\r\n");
            reg.append("\"Icon\"=").append(regString(javaw)).append("\r\n\r\n");
            reg.append('[').append(txtBase).append("\\command]\r\n");
            reg.append("@=").append(regString(txtLaunch)).append("\r\n\r\n");

            // Keep the .reg under JRock/ so the user can inspect exactly what was
            // applied (and re-run or delete it manually).
            Files.createDirectories(jrockDir());
            Path regFile = jrockDir().resolve("jrock-context-menu-install.reg");
            importReg(reg.toString(), regFile);
            // Record it in the log so there's a visible, persisted trace.
            log.gray("Installed the \"" + CTX_LABEL + "\" (folders) and \"" + TXT_LABEL
                    + "\" (.txt) Explorer right-click menus.");
            log.gray("Folder launch: " + folderLaunch);
            log.gray(".txt launch:   " + txtLaunch);
            log.gray("Applied registry file kept at: " + regFile);
            log.gray("");
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Installed two Explorer right-click entries:\n"
                        + "  \u2022 \"" + CTX_LABEL + "\" - inside or on a folder, launches JRock there.\n"
                        + "  \u2022 \"" + TXT_LABEL + "\" - on a .txt file, opens it as the prompt.\n\n"
                        + "(On Windows 11 they may appear under \"Show more options\".)\n\n"
                        + "The applied registry file was kept for your inspection at:\n"
                        + regFile,
                    CTX_LABEL, javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showCtxError(frame, log, ex.getMessage());
        }
    }

    private static void uninstallContextMenu(JFrame frame, LogView log) {
        try {
            StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
            for (String root : CTX_SHELL_ROOTS) {
                reg.append("[-HKEY_CURRENT_USER\\").append(root).append('\\').append(CTX_KEY).append("]\r\n\r\n");
            }
            reg.append("[-HKEY_CURRENT_USER\\").append(TXT_SHELL_ROOT).append('\\').append(TXT_KEY).append("]\r\n\r\n");
            Files.createDirectories(jrockDir());
            Path regFile = jrockDir().resolve("jrock-context-menu-uninstall.reg");
            importReg(reg.toString(), regFile);
            log.gray("Removed the \"" + CTX_LABEL + "\" and \"" + TXT_LABEL
                    + "\" Explorer right-click menus (if present).");
            log.gray("Applied registry file kept at: " + regFile);
            log.gray("");
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Removed the \"" + CTX_LABEL + "\" and \"" + TXT_LABEL
                        + "\" right-click entries (if present).\n\n"
                        + "The applied registry file was kept for your inspection at:\n"
                        + regFile,
                    CTX_LABEL, javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showCtxError(frame, log, ex.getMessage());
        }
    }

    private static void showCtxError(JFrame frame, LogView log, String msg) {
        log.gray("Context menu update failed: " + msg);
        log.gray("");
        javax.swing.JOptionPane.showMessageDialog(frame,
                "Could not update the context menu:\n" + msg,
                CTX_LABEL, javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    // javaw.exe next to the JVM currently running JRock.
    private static String findJavaw() {
        String home = System.getProperty("java.home");
        if (home == null) return null;
        Path javaw = Paths.get(home, "bin", "javaw.exe");
        return Files.isRegularFile(javaw) ? javaw.toAbsolutePath().toString() : null;
    }

    // The launch command for the folder verb: just javaw + JRock. Explorer starts
    // the process with its current directory set to the clicked folder, so JRock
    // roots its files there with no extra flag. No cmd, no console. Prefers this
    // running jar; falls back to the JRock.java source file.
    private static String buildCtxLaunch(String javaw) {
        Path self = ownJarOrSource();
        if (self == null) return null;
        String base = "\"" + javaw + "\" ";
        if (self.toString().toLowerCase().endsWith(".jar")) {
            return base + "-jar \"" + self + "\"";
        }
        return base + "\"" + self + "\"";   // single-file source launch (JDK 11+)
    }

    // The launch command for the .txt verb: javaw passing the clicked file (%1)
    // as JRock's prompt-source argument. JRock uses whatever current directory
    // Explorer starts it in (typically the file's folder). No cmd, no console.
    private static String buildTxtLaunch(String javaw) {
        Path self = ownJarOrSource();
        if (self == null) return null;
        String base = "\"" + javaw + "\" ";
        if (self.toString().toLowerCase().endsWith(".jar")) {
            return base + "-jar \"" + self + "\" \"%1\"";
        }
        return base + "\"" + self + "\" \"%1\"";   // single-file source launch (JDK 11+)
    }

    // Locates JRock's own artifact: the jar it's running from (via CodeSource),
    // else a jrock.jar / JRock.java near the current directory.
    private static Path ownJarOrSource() {
        try {
            java.net.URL loc = JRock.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Paths.get(loc.toURI());
            if (Files.isRegularFile(p) && p.toString().toLowerCase().endsWith(".jar")) {
                return p.toAbsolutePath().normalize();
            }
        } catch (Exception ignore) { /* source launch: CodeSource is a temp dir */ }
        Path[] candidates = {
            Paths.get("jrock.jar"), Paths.get("..", "jrock.jar"),
            Paths.get("JRock.java"), Paths.get("..", "JRock.java"),
        };
        for (Path c : candidates) {
            if (Files.isRegularFile(c)) return c.toAbsolutePath().normalize();
        }
        return null;
    }

    // Quotes/escapes a string as a .reg REG_SZ value (backslashes and quotes).
    private static String regString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // Writes .reg content to regFile (kept for the user to inspect) and applies
    // it via `reg import`. .reg files must be UTF-16LE with a BOM for reg.exe.
    private static void importReg(String content, Path regFile) throws IOException, InterruptedException {
        byte[] text = content.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[text.length + 2];
        withBom[0] = (byte) 0xFF; withBom[1] = (byte) 0xFE;   // UTF-16LE BOM
        System.arraycopy(text, 0, withBom, 2, text.length);

        Files.write(regFile, withBom);   // kept on disk (not a temp file)
        ProcessBuilder pb = new ProcessBuilder("reg.exe", "import", regFile.toString());
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        int code = pb.start().waitFor();
        if (code != 0) throw new IOException("reg import failed (exit " + code + ")");
    }

    // Opens the native print dialog for the log pane. JTextComponent.print()
    // paginates and shows the dialog, where the user can pick a printer (including
    // "Microsoft Print to PDF" on Windows) or save to PDF.
    //
    // If text is selected in the log pane, only the selection is printed. print()
    // always prints the whole document, so the selected part is copied into an
    // off-screen pane (colors and all) and that pane is printed instead.
    private static void printLog(JFrame frame, JTextPane output) {
        JTextPane toPrint = output;
        try {
            if (output.getSelectedText() != null && !output.getSelectedText().isEmpty()) {
                toPrint = selectionPane(output);
            }
        } catch (BadLocationException ex) {
            toPrint = output;   // can't isolate the selection: print the whole log
        }
        try {
            toPrint.print();   // shows the native print dialog; blocks until done
        } catch (java.awt.print.PrinterException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Printing failed: " + ex.getMessage(),
                    "Print", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // An off-screen JTextPane holding a copy of the source pane's selected text,
    // with its character styling (the teal headers, gray system lines) preserved by
    // copying each styled run with its own attributes. Used for printing only.
    private static JTextPane selectionPane(JTextPane source) throws BadLocationException {
        javax.swing.text.StyledDocument src = source.getStyledDocument();
        JTextPane copy = new JTextPane();
        copy.setFont(source.getFont());
        javax.swing.text.StyledDocument dst = copy.getStyledDocument();
        int end = source.getSelectionEnd();
        int pos = source.getSelectionStart();
        while (pos < end) {
            javax.swing.text.Element run = src.getCharacterElement(pos);
            int runEnd = Math.min(run.getEndOffset(), end);
            if (runEnd <= pos) break;   // defensive: never spin on a zero-length run
            dst.insertString(dst.getLength(), src.getText(pos, runEnd - pos), run.getAttributes());
            pos = runEnd;
        }
        // Never displayed; printing re-lays the text out to the page width anyway,
        // but give it the source pane's size so it is never a zero-sized component.
        copy.setSize(Math.max(source.getWidth(), 100), Math.max(source.getHeight(), 100));
        return copy;
    }

    // Clears the log after confirming when there are unsaved changes. If the log
    // hasn't been exported (Ctrl+L) since it last changed, warns and advises the
    // user to cancel and save first; only clears on explicit confirmation.
    private static void clearLogConfirmed(JFrame frame, LogView log) {
        if (!log.isEmpty() && !log.isLogCopySaved()) {
            int choice = javax.swing.JOptionPane.showConfirmDialog(frame,
                    "The log has changed since it was last saved.\n\n"
                        + "Clear it anyway? To keep a copy, cancel and save the log "
                        + "first with Ctrl+L.",
                    "Clear log without saving?",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (choice != javax.swing.JOptionPane.OK_OPTION) return;
        }
        log.clear();
    }

    // What "text file" means in the Load prompt and Include dialogs: .txt plus the
    // plain-text formats people actually reach for. A .csv, .html or .java file is
    // text like any other, and having to rename it to .txt to load or include it was
    // pure friction. (Any file still has to pass the looksBinary check on load.)
    private static final String[] TEXT_EXTENSIONS = { "txt", "csv", "html", "java" };
    private static final String TEXT_FILTER_LABEL = "Text files (*.txt, *.csv, *.html, *.java)";

    // Loads a prompt from a user-chosen file (read-only) into the input area.
    // The document listener then autosaves the loaded text to jrock-prompt.txt.
    private static void loadPromptInto(JFrame frame, JTextArea input, LogView log) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(promptChooserDir.start());
        chooser.setDialogTitle("Load prompt");
        // Prompts are text - restrict to text types so an image can't be loaded by
        // mistake.
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                TEXT_FILTER_LABEL, TEXT_EXTENSIONS));
        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        promptChooserDir.remember(chooser);

        Path source = chooser.getSelectedFile().toPath();
        String loaded = readFileQuietly(source);
        if (loaded == null) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not read " + source,
                    "Load failed", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Guard against loading binary (e.g. an image) as a prompt: that would put
        // garbage into the text area. To attach an image, use Ctrl+I instead.
        if (looksBinary(loaded)) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "That file doesn't look like text and was not loaded as a prompt.\n"
                    + "To attach an image or file, use Ctrl+I (Include) instead.",
                    "Not a text file", javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }
        input.setText(loaded);            // triggers autosave to jrock-prompt.txt
        input.setCaretPosition(0);
        if (log != null) log.gray("Loaded prompt from (read-only): " + source);
    }

    // ---- Include file (Ctrl+I) ---------------------------------------------
    // Lets the user pick a text or image file, or a PDF to convert (via
    // Ghostscript) into per-page text or per-page images. Each included file is
    // hashed, remembered as hash -> path in the non-persistent INCLUDES map,
    // logged (with image dimensions where applicable), and gets an
    // "@txt <hash>" / "@img <hash>" token inserted at the prompt cursor.
    private static void showIncludeDialog(JFrame frame, JTextArea input, LogView log,
                                          boolean extend) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(includeChooserDir.start());
        chooser.setDialogTitle("Include file");
        chooser.setAcceptAllFileFilterUsed(false);
        javax.swing.filechooser.FileNameExtensionFilter imageFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "Image files (png, jpg, jpeg, gif, webp)", "png", "jpg", "jpeg", "gif", "webp");
        javax.swing.filechooser.FileNameExtensionFilter textFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        TEXT_FILTER_LABEL, TEXT_EXTENSIONS);
        javax.swing.filechooser.FileNameExtensionFilter pdfTextFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("PDF as text pages (*.pdf)", "pdf");
        javax.swing.filechooser.FileNameExtensionFilter pdfImageFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("PDF as page images (*.pdf)", "pdf");
        chooser.addChoosableFileFilter(imageFilter);   // first in the dropdown
        chooser.addChoosableFileFilter(textFilter);
        chooser.addChoosableFileFilter(pdfTextFilter);
        chooser.addChoosableFileFilter(pdfImageFilter);
        chooser.setFileFilter(imageFilter);            // default selection = image
        chooser.setMultiSelectionEnabled(true);        // allow selecting several files

        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        includeChooserDir.remember(chooser);

        java.io.File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) return;
        javax.swing.filechooser.FileFilter chosen = chooser.getFileFilter();
        boolean asImages = chosen == pdfImageFilter;
        boolean pdf = chosen == pdfTextFilter || chosen == pdfImageFilter;
        boolean isImage = chosen == imageFilter;

        // Process each chosen file in turn, all under the selected filter's kind.
        for (java.io.File f : selected) {
            Path file = f.toPath();
            if (pdf) {
                includePdf(frame, input, log, extend, file, asImages);
            } else {
                includeOne(input, log, extend, file, isImage ? "img" : "txt", isImage);
            }
        }
        log.gray("");   // closes the include block (one per Ctrl+I, however many files)
        input.requestFocusInWindow();
    }

    // Registers one file as an include (hash -> path), logs it (with image
    // dimensions when applicable), and inserts its "@kind <hash>" token at the
    // cursor unless already referenced (dedup, also across prior turns in extend
    // mode). Returns true if a token was inserted.
    private static boolean includeOne(JTextArea input, LogView log, boolean extend,
                                      Path file, String kind, boolean isImage) {
        String hash = hashFile(file);
        if (hash == null) {
            log.gray("Include failed: could not read " + file);
            return false;
        }
        // Always (re)register the hash -> path mapping. After a restart this makes
        // an existing "@kind <hash>" token in the (recovered) prompt valid again.
        INCLUDES.put(hash, file);
        log.gray("Included @" + kind + " " + hash + " from " + file);

        long fileBytes = -1;
        try { fileBytes = Files.size(file); } catch (IOException ignore) { /* best-effort */ }

        if (isImage) {
            // Dimensions + total pixel count + file size (all locale-formatted).
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(file.toFile());
                if (bi != null) {
                    long pixels = (long) bi.getWidth() * bi.getHeight();
                    log.gray("Image: " + bi.getWidth() + " x " + bi.getHeight()
                            + ", " + fmtNum(pixels) + " pixels"
                            + (fileBytes >= 0 ? ", " + fmtNum(fileBytes) + " bytes" : ""));
                } else {
                    log.gray("Image: (could not decode dimensions)"
                            + (fileBytes >= 0 ? "; " + fmtNum(fileBytes) + " bytes" : ""));
                }
            } catch (IOException ex) {
                log.gray("Image: (could not read dimensions: " + ex.getMessage() + ")");
            }
        } else {
            // Text: symbol count (Unicode code points) + byte count.
            try {
                String content = readFileQuietly(file);
                if (content != null) {
                    int symbols = content.codePointCount(0, content.length());
                    log.gray("Text: " + fmtNum(symbols) + " symbols"
                            + (fileBytes >= 0 ? ", " + fmtNum(fileBytes) + " bytes" : ""));
                } else if (fileBytes >= 0) {
                    log.gray("Text: " + fmtNum(fileBytes) + " bytes (symbols unavailable)");
                }
            } catch (RuntimeException ex) {
                log.gray("Text: (could not read stats: " + ex.getMessage() + ")");
            }
        }

        // Dedup: if this exact token is already in the prompt (or, in extend mode,
        // in any prior human turn), don't insert a duplicate - just keep the
        // re-registered mapping above. This avoids duplicate tags after a restart
        // when the user reattaches files.
        String token = "@" + kind + " " + hash;
        boolean alreadyPresent = input.getText().contains(token);
        if (!alreadyPresent && extend) {
            for (String[] turn : log.dialogHistory()) {
                if (ROLE_HUMAN.equals(turn[0]) && turn[1].contains(token)) {
                    alreadyPresent = true;
                    break;
                }
            }
        }
        if (alreadyPresent) {
            log.gray("Already referenced (@" + kind + " " + hash + "); token not duplicated.");
            return false;
        }

        // Insert "@kind <hash>\n" at the cursor (no leading newline).
        int pos = input.getCaretPosition();
        try {
            input.getDocument().insertString(pos, token + "\n", null);
        } catch (BadLocationException ex) {
            input.append(token + "\n");   // fallback: append at end
        }
        return true;
    }

    // Converts a PDF to per-page files with Ghostscript, then includes
    // each produced page. asImages=false -> text pages (txtwrite), true -> PNG
    // page images. Output files are written under JRock/gs-pdf/, named
    // "<pdfname>.gs.NNN.txt" / "<pdfname>.gs.NNN.png". If Ghostscript isn't on
    // PATH, points the user to the download page and does nothing else.
    private static void includePdf(JFrame frame, JTextArea input, LogView log,
                                   boolean extend, Path pdf, boolean asImages) {
        String gs = findGhostscript();
        if (gs == null) {
            // Name the executable the current platform actually looks for.
            String exe = isWindows() ? "gswin64c" : "gs";
            log.gray("Ghostscript (" + exe + ") was not found on PATH. Install it from "
                    + "https://ghostscript.com/ to convert PDFs, then try again.");
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Ghostscript (" + exe + ") is required to convert PDFs but was not found "
                        + "on your PATH.\n\nInstall it from https://ghostscript.com/ and "
                        + "restart JRock (or your shell) so " + exe + " is on PATH.",
                    "Ghostscript not found", javax.swing.JOptionPane.WARNING_MESSAGE);
            openUrl("https://ghostscript.com/");
            return;
        }

        String ext = asImages ? "png" : "txt";
        String device = asImages ? "png16m" : "txtwrite";

        // Output goes to JRock/gs-pdf/. Page files are named "<pdfname>.gs.NNN.<ext>"
        // (pdf name kept as a prefix so pages from different PDFs don't collide).
        Path outDir = gsPdfDir();
        try {
            Files.createDirectories(outDir);
        } catch (IOException ex) {
            log.gray("Could not create " + outDir + ": " + ex.getMessage());
            return;
        }
        String base = pdf.getFileName().toString();
        String prefix = base + ".gs.";
        String suffix = "." + ext;
        // Ghostscript expands %03d in the output path to the page number.
        String outPattern = outDir.resolve(prefix + "%03d" + suffix).toString();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(gs);
        cmd.add("-q"); cmd.add("-dNOPAUSE"); cmd.add("-dBATCH"); cmd.add("-dSAFER");
        cmd.add("-sDEVICE=" + device);
        if (asImages) { cmd.add("-r150"); }   // 150 dpi page raster
        cmd.add("-o"); cmd.add(outPattern);
        cmd.add(pdf.toAbsolutePath().toString());

        log.gray("Converting PDF with Ghostscript: " + String.join(" ", cmd));
        int code;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output so the process can't block, and echo it to the log.
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) log.gray("gs: " + line.trim());
                }
            }
            code = p.waitFor();
        } catch (IOException | InterruptedException ex) {
            log.gray("Ghostscript failed to run: " + ex.getMessage());
            return;
        }
        if (code != 0) {
            log.gray("Ghostscript exited with code " + code + "; no pages included.");
            return;
        }

        // Collect the produced page files (in JRock/gs-pdf/) in order, matching
        // this PDF's prefix, and include each one.
        java.util.List<Path> pages = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.list(outDir)) {
            s.filter(pp -> {
                    String n = pp.getFileName().toString();
                    return n.startsWith(prefix) && n.endsWith(suffix);
                })
                .sorted(java.util.Comparator.comparing(pp -> pp.getFileName().toString()))
                .forEach(pages::add);
        } catch (IOException ex) {
            log.gray("Could not list produced pages: " + ex.getMessage());
            return;
        }
        if (pages.isEmpty()) {
            log.gray("Ghostscript produced no pages for " + pdf.getFileName() + ".");
            return;
        }

        log.gray("Ghostscript produced " + pages.size() + " page file(s); including them.");
        int inserted = 0;
        for (Path page : pages) {
            if (includeOne(input, log, extend, page, asImages ? "img" : "txt", asImages)) inserted++;
        }
        log.gray("Inserted " + inserted + " new @" + (asImages ? "img" : "txt")
                + " token(s) for " + pdf.getFileName() + ".");
        input.requestFocusInWindow();
    }

    // Finds the Ghostscript console executable on PATH (gswin64c/gswin32c on
    // Windows, "gs" elsewhere). Returns the command to run, or null if not found.
    private static String findGhostscript() {
        String[] names = isWindows()
                ? new String[] { "gswin64c", "gswin32c", "gs" }
                : new String[] { "gs" };
        String path = System.getenv("PATH");
        String[] dirs = path == null ? new String[0] : path.split(java.io.File.pathSeparator);
        for (String name : names) {
            String exe = isWindows() ? name + ".exe" : name;
            for (String d : dirs) {
                if (d.isBlank()) continue;
                try {
                    Path candidate = Paths.get(d.trim(), exe);
                    if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().toString();
                } catch (RuntimeException ignore) { /* skip malformed PATH entry */ }
            }
        }
        return null;
    }

    // ---- Move & resize dialog (Ctrl+M) -------------------------------------
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
                frame, panel, "Move & resize",
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

    // ---- Multimodal include verification & content assembly ----------------
    // Checks every @img/@txt token in the prompt: the hash must be known (in
    // INCLUDES) AND the file must still hash to the same value (unchanged). Returns
    // null if all good, else a human-readable error describing the first problem.
    private static String verifyIncludes(String prompt) {
        java.util.regex.Matcher m = INCLUDE_TOKEN.matcher(prompt);
        while (m.find()) {
            String kind = m.group(1);
            String hash = m.group(2);
            Path path = INCLUDES.get(hash);
            if (path == null) {
                return "Included @" + kind + " " + hash + " is not known (re-include the "
                        + "file with Ctrl+I; includes are not kept across restarts).";
            }
            if (!Files.exists(path)) {
                return "Included file is missing: " + path + " (@" + kind + " " + hash + ").";
            }
            String current = hashFile(path);
            if (current == null) {
                return "Could not read included file: " + path + " (@" + kind + " " + hash + ").";
            }
            if (!current.equals(hash)) {
                return "Included file has changed since it was added: " + path
                        + " (@" + kind + " " + hash + "). Re-include it with Ctrl+I.";
            }
        }
        return null;
    }

    // A content part destined for the OpenAI multimodal "content" array.
    private static final class Part {
        final boolean image;      // true = image_url part, false = text part
        final String text;        // text part: the literal text
        final String dataUrl;     // image part: "data:<mime>;base64,<...>"
        final String maskHash;    // image/text include hash for masking, or null
        Part(boolean image, String text, String dataUrl, String maskHash) {
            this.image = image; this.text = text; this.dataUrl = dataUrl; this.maskHash = maskHash;
        }
        static Part text(String t)                  { return new Part(false, t, null, null); }
        static Part includedText(String t, String h){ return new Part(false, t, null, h); }
        static Part image(String url, String h)     { return new Part(true, null, url, h); }
    }

    // Splits the prompt into ordered content parts, expanding @img/@txt tokens.
    // Plain text between tokens becomes text parts (empty segments skipped, so a
    // token at the very start/end yields no empty neighbour). Assumes verifyIncludes
    // already passed. May throw on file read.
    private static java.util.List<Part> buildParts(String prompt) throws IOException {
        java.util.List<Part> parts = new ArrayList<>();
        java.util.regex.Matcher m = INCLUDE_TOKEN.matcher(prompt);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                String seg = prompt.substring(last, m.start());
                if (!seg.isEmpty()) parts.add(Part.text(seg));
            }
            String kind = m.group(1);
            String hash = m.group(2);
            Path path = INCLUDES.get(hash);
            if (kind.equals("img")) {
                byte[] bytes = Files.readAllBytes(path);
                String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                String mime = imageMime(path);
                parts.add(Part.image("data:" + mime + ";base64," + b64, hash));
            } else {
                String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                parts.add(Part.includedText(content, hash));
            }
            last = m.end();
        }
        if (last < prompt.length()) {
            String seg = prompt.substring(last);
            if (!seg.isEmpty()) parts.add(Part.text(seg));
        }
        return parts;
    }

    private static String imageMime(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".png"))  return "image/png";
        if (n.endsWith(".gif"))  return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        return "image/jpeg";   // jpg/jpeg and default
    }

    // Emits the OpenAI "content" value for one user turn into the real (sb) and
    // masked (masked) builders: a JSON string when it's a single plain-text part,
    // otherwise an array of text/image_url parts. In the masked copy, included
    // text/image content is replaced by "<txt|img masked <hash>>" and ordinary
    // prompt text by "<input masked>".
    // Appends the REAL "content" value for one user turn: a JSON string when it's
    // a single plain-text part, otherwise an array of text/image_url parts.
    private static void appendRealContent(StringBuilder sb, java.util.List<Part> parts) {
        if (isSinglePlainText(parts)) {
            sb.append("\"").append(jsonEscape(parts.get(0).text)).append("\"");
            return;
        }
        sb.append("[");
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (i > 0) sb.append(",");
            if (p.image) {
                sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
                        .append(jsonEscape(p.dataUrl)).append("\"}}");
            } else {
                sb.append("{\"type\":\"text\",\"text\":\"")
                        .append(jsonEscape(p.text)).append("\"}");
            }
        }
        sb.append("]");
    }

    // Appends the MASKED "content" value for one user turn, mirroring the real
    // shape but replacing content: included text/image -> "<txt|img masked <hash>>",
    // ordinary prompt text -> "<input masked>".
    private static void appendMaskedContent(StringBuilder sb, java.util.List<Part> parts) {
        if (isSinglePlainText(parts)) {
            sb.append("\"<input masked>\"");
            return;
        }
        sb.append("[");
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            if (i > 0) sb.append(",");
            if (p.image) {
                sb.append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"")
                        .append("<img masked ").append(p.maskHash).append(">").append("\"}}");
            } else {
                String maskTxt = (p.maskHash != null)
                        ? "<txt masked " + p.maskHash + ">"
                        : "<input masked>";
                sb.append("{\"type\":\"text\",\"text\":\"").append(maskTxt).append("\"}");
            }
        }
        sb.append("]");
    }

    private static boolean isSinglePlainText(java.util.List<Part> parts) {
        return parts.size() == 1 && !parts.get(0).image && parts.get(0).maskHash == null;
    }

    // ---- HTTP transport ----------------------------------------------------
    // JRock makes exactly two kinds of request (GET /v1/models and POST
    // /v1/chat/completions), so the whole transport surface is one send() method.
    // There are two implementations because the browser has no sockets:
    //
    //   JdkHttpTransport     - java.net.http.HttpClient. Used on every normal JVM.
    //   BrowserHttpTransport - the hosting page's JavaScript HTTP client, called
    //                          through CheerpJ's JNI-in-JavaScript bridge. Used
    //                          when JRock runs as WebAssembly in the browser,
    //                          where HttpClient cannot work at all: there is no
    //                          native socket layer, so its networking
    //                          (sun.nio.ch.EPoll) is unavailable and throws
    //                          UnsatisfiedLinkError. The browser's own fetch()
    //                          performs the request instead, and the page - not
    //                          JRock - holds the Bedrock credentials.
    //
    // The choice is made once, on first use, by probing for the bridge; see http().

    // One response: HTTP status plus the body as text. Transport-level failures
    // are thrown instead of being represented here.
    private static final class HttpReply {
        final int status;
        final String body;
        HttpReply(int status, String body) { this.status = status; this.body = body; }
    }

    private interface HttpTransport {
        // Sends one request and returns its status + body. A null body means no
        // request body. Throws on any transport-level failure.
        HttpReply send(String method, String url, List<String[]> headers,
                       String body, int timeoutSeconds) throws Exception;

        // True when the HOST holds the Bedrock credentials and adds the
        // Authorization header itself. That is the browser case: the API key is
        // typed into the page and stays in JavaScript, so JRock has no key of its
        // own and must not treat "no key set" as an error.
        boolean hostHoldsCredentials();

        // Short description of the transport, for the startup log.
        String describe();
    }

    // The ordinary JVM transport: java.net.http.HttpClient.
    private static final class JdkHttpTransport implements HttpTransport {
        @Override
        public HttpReply send(String method, String url, List<String[]> headers,
                              String body, int timeoutSeconds) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds));
            for (String[] header : headers) {
                builder.header(header[0], header[1]);
            }
            builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    .build();
            HttpResponse<String> resp =
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new HttpReply(resp.statusCode(), resp.body());
        }

        @Override public boolean hostHoldsCredentials() { return false; }
        @Override public String describe() { return "java.net.http.HttpClient"; }
    }

    // ---- Browser bridge (JavaScript side of the transport) -------------------
    // CheerpJ resolves a Java native method to a JavaScript function named
    // Java_<mangled class>_<method>, which the hosting page registers with
    // cheerpjInit({ natives: { ... } }). Those functions may be async: CheerpJ
    // suspends the calling Java thread until the promise settles, which is what
    // makes a blocking, synchronous-looking send() on top of fetch() possible.
    // On any other JVM these methods are unlinked and calling one throws
    // UnsatisfiedLinkError - which is exactly how http() detects the bridge.
    //
    // The wire format is deliberately trivial, so neither side needs a JSON parser
    // it doesn't already have:
    //   browserHttpInfo() -> a small flat JSON object describing the page's client
    //                        ({"transport":...,"region":...}).
    //   browserHttpSend() -> "<status>\n<body>". Status 0 means the request never
    //                        completed and the body is the reason, ready to show.
    // Request headers travel in the other direction as a flat JSON object. The
    // Authorization header is NOT among them: the page's client adds it, because
    // the API key lives only in the page.
    static native String browserHttpInfo();
    static native String browserHttpSend(String method, String url,
                                         String headersJson, String body,
                                         int timeoutSeconds);

    // The browser transport: hands each request to the page's JavaScript client.
    private static final class BrowserHttpTransport implements HttpTransport {
        private final String label;

        BrowserHttpTransport(String info) {
            String reported = jsonStringField(info, "transport");
            this.label = (reported == null || reported.isBlank())
                    ? "browser HTTP bridge" : reported;
        }

        @Override
        public HttpReply send(String method, String url, List<String[]> headers,
                              String body, int timeoutSeconds) throws Exception {
            StringBuilder headersJson = new StringBuilder("{");
            for (int i = 0; i < headers.size(); i++) {
                String[] header = headers.get(i);
                if (i > 0) headersJson.append(",");
                headersJson.append("\"").append(jsonEscape(header[0])).append("\":\"")
                        .append(jsonEscape(header[1])).append("\"");
            }
            headersJson.append("}");

            String framed;
            try {
                framed = browserHttpSend(method, url, headersJson.toString(),
                        body, timeoutSeconds);
            } catch (Throwable ex) {
                // The bridge itself failed (rather than reporting a failed
                // request), e.g. the page's client threw something unexpected.
                throw new IOException("Browser HTTP bridge failed: "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            }
            if (framed == null || framed.isEmpty()) {
                throw new IOException("Browser HTTP bridge returned an empty reply.");
            }

            int nl = framed.indexOf('\n');
            String statusText = (nl < 0 ? framed : framed.substring(0, nl)).trim();
            String bodyText = (nl < 0) ? "" : framed.substring(nl + 1);
            int status;
            try {
                status = Integer.parseInt(statusText);
            } catch (NumberFormatException ex) {
                throw new IOException("Browser HTTP bridge returned a malformed reply: "
                        + framed);
            }
            if (status == 0) {
                // The request never reached a response; the body is the reason.
                throw new IOException(bodyText.isBlank()
                        ? "Browser HTTP request failed (no reason reported)." : bodyText);
            }
            return new HttpReply(status, bodyText);
        }

        @Override public boolean hostHoldsCredentials() { return true; }
        @Override public String describe() { return label; }
    }

    // The transport in use, chosen once by http().
    private static HttpTransport transport;

    // Returns the transport, picking it on first call. The bridge is detected by
    // calling it: on a normal JVM the native method is unlinked and throws, so
    // "the bridge answered" is the same thing as "the bridge works" - no runtime
    // sniffing that could disagree with reality. When the page reports a region,
    // JRock adopts it, so the endpoints it logs and calls match the page's key.
    private static synchronized HttpTransport http() {
        if (transport != null) return transport;
        String info;
        try {
            info = browserHttpInfo();
        } catch (Throwable ignored) {
            info = null;                     // no bridge here: ordinary JVM
        }
        if (info == null) {
            transport = new JdkHttpTransport();
            return transport;
        }
        transport = new BrowserHttpTransport(info);
        String hostRegion = jsonStringField(info, "region");
        if (hostRegion != null && !hostRegion.isBlank()) {
            REGION = hostRegion.trim();
            regionSource = "from the hosting page";
        }
        return transport;
    }

    // True when running on CheerpJ, the WebAssembly JVM in the browser. Used only
    // for reporting - the transport itself is chosen by probing the bridge.
    //
    // A live browser bridge is proof on its own. Failing that (a CheerpJ page that
    // never installed the bridge, where every call is doomed and the log should say
    // why) we look for CheerpJ's own runtime classes, which no other JVM has, then
    // at how the VM names itself.
    private static boolean isCheerpJ() {
        if (transport instanceof BrowserHttpTransport) return true;
        String[] markers = {
            "com.leaningtech.client.Global",
            "com.leaningtech.cheerpj.CJ",
            "org.cheerpj.CS",
        };
        for (String marker : markers) {
            try {
                Class.forName(marker);
                return true;
            } catch (Throwable ignored) { /* not this marker; try the next */ }
        }
        String vm = (System.getProperty("java.vm.name", "") + " "
                + System.getProperty("java.vendor", "")).toLowerCase();
        return vm.contains("cheerpj") || vm.contains("leaningtech");
    }

    // Best-effort read of a string field ("region":"us-east-1") from a small flat
    // JSON object. Escapes are not decoded - the bridge only reports plain
    // identifier-like values. Returns null when the field isn't there.
    private static String jsonStringField(String json, String key) {
        if (json == null) return null;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        int q2 = (q1 < 0) ? -1 : json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    // ---- Bedrock call ------------------------------------------------------
    // Returns a 3-element array:
    //   [0] = "1" on success, "0" on failure.
    //   [1] = the model reply (success) or the error message (failure).
    //   [2] = raw request/response/stats detail block (gray), or null.
    // Only a successful reply is treated as dialog; failures are logged in gray.
    private static String[] callModel(String prompt, java.util.List<String[]> history)
            throws Exception {
        HttpTransport http = http();
        String apiKey = resolveApiKey();
        boolean haveKey = apiKey != null && !apiKey.isBlank();
        // No key needed when the host holds the credentials (the browser case).
        if (!haveKey && !http.hostHoldsCredentials()) {
            return new String[] {
                "0",
                "No Bedrock API key set. Set the BEDROCK_API_KEY env var, or open "
                    + "the Configure dialog (top-left button) and enter a key.",
                null
            };
        }

        // If the resolved model card says this model isn't served via Chat
        // Completions on mantle (e.g. any anthropic.* model, which uses the
        // Messages API), don't send a Chat Completions request - it would fail.
        BedrockModelCard card = cardFor(MODEL_ID);
        if (card != null && !card.supportsMantleChatCompletions()) {
            String via = card.mantleMessagesPath() != null
                    ? "the Anthropic Messages API (" + card.mantleMessagesPath() + ")"
                    : "an API JRock does not implement";
            return new String[] {
                "0",
                "Model \"" + MODEL_ID + "\" is not served via Chat Completions on "
                    + "bedrock-mantle; it uses " + via + ", which JRock does not "
                    + "implement yet. Choose a Chat-Completions-capable model.",
                null
            };
        }

        // Expand the new prompt into multimodal content parts (@img/@txt tokens
        // become image/text parts). verifyIncludes() has already run in the UI.
        java.util.List<Part> parts = buildParts(prompt);

        // Build the real messages array. Human turns - both prior ones (extend
        // mode) and the new turn - are expanded via buildParts so their @img/@txt
        // tokens become image/text content parts. Assistant turns are plain text.
        StringBuilder messages = new StringBuilder("[");
        for (String[] turn : history) {
            if (ROLE_HUMAN.equals(turn[0])) {
                messages.append("{\"role\":\"user\",\"content\":");
                appendRealContent(messages, buildParts(turn[1]));
                messages.append("},");
            } else {
                messages.append("{\"role\":\"assistant\",\"content\":\"")
                        .append(jsonEscape(turn[1])).append("\"},");
            }
        }
        messages.append("{\"role\":\"user\",\"content\":");
        appendRealContent(messages, parts);
        messages.append("}]");

        // OpenAI Chat Completions request shape.
        String body = chatRequestBody(messages);

        // Masked copy of the request for display - built independently from the
        // same history + prompt parts.
        String maskedRequestBody = maskRequest(history, parts);

        List<String[]> headers = new ArrayList<>();
        headers.add(new String[] { "Content-Type", "application/json" });
        if (haveKey) {
            headers.add(new String[] { "Authorization", "Bearer " + apiKey.trim() });
        }

        HttpReply resp = http.send("POST", endpoint(), headers, body, CHAT_TIMEOUT_SECONDS);

        if (resp.status != 200) {
            return new String[] {
                "0",
                "HTTP " + resp.status,
                // Nothing to mask in the body: there is no reply, only an error.
                rawDump(maskedRequestBody, resp.body)
            };
        }

        String reply = extractContent(resp.body);

        // Text-symbol counts are computed locally. Input counts the prompt text
        // plus all prior turns (extend mode). Images don't contribute text symbols.
        int inputSymbols = prompt.length();
        for (String[] turn : history) {
            inputSymbols += turn[1].length();
        }
        int outputSymbols = reply.length();

        // Token counts come from the API's "usage" object (best-effort parse).
        long inputTokens = extractLong(resp.body, "prompt_tokens");
        long outputTokens = extractLong(resp.body, "completion_tokens");

        // Mask the reply text inside the raw response so it isn't duplicated
        // (it's already shown above). The request was masked during assembly.
        String maskedResponse = maskResponse(resp.body, reply);

        String details = rawDump(maskedRequestBody, maskedResponse)
                + "\n\n--- stats ---"
                + "\nInput text symbols:  " + inputSymbols
                + "\nOutput text symbols: " + outputSymbols
                + "\nInput tokens:   " + tokenStr(inputTokens)
                + "\nOutput tokens:  " + tokenStr(outputTokens);

        return new String[] { "1", reply, details };
    }

    // The raw request/response dump logged after a call, for either outcome. The
    // response body is the caller's choice: masked on success (the reply is already
    // shown above), verbatim on failure. A success then appends its stats block.
    private static String rawDump(String maskedRequestBody, String responseBody) {
        return "--- raw request ---\nPOST " + pathOf(endpoint()) + "\n" + maskedRequestBody
                + "\n\n--- raw response ---\n" + responseBody;
    }

    private static String tokenStr(long v) {
        return v < 0 ? "(not reported)" : Long.toString(v);
    }

    // ---- Masking for display -----------------------------------------------
    // The raw request and raw response are shown in the log for debugging, but we
    // strip the actual prompt/reply/attachment content so the transcript isn't a
    // noisy duplicate (and included files stay referenced only by hash). These two
    // methods are the request/response counterparts.

    // Builds the displayed (masked) request body from the same inputs as the real
    // request: prior turns plus the new prompt's content parts. Message content is
    // replaced with masked placeholders (see appendMaskedContent) so the raw
    // request shown in the log carries no prompt/reply/attachment content.
    private static String maskRequest(java.util.List<String[]> history,
                                      java.util.List<Part> parts) throws IOException {
        StringBuilder masked = new StringBuilder("[");
        for (String[] turn : history) {
            if (ROLE_HUMAN.equals(turn[0])) {
                masked.append("{\"role\":\"user\",\"content\":");
                appendMaskedContent(masked, buildParts(turn[1]));
                masked.append("},");
            } else {
                masked.append("{\"role\":\"assistant\",\"content\":\"")
                        .append("<input masked>").append("\"},");
            }
        }
        masked.append("{\"role\":\"user\",\"content\":");
        appendMaskedContent(masked, parts);
        masked.append("}]");

        return chatRequestBody(masked);
    }

    // Masks the assistant reply text inside the raw response body.
    private static String maskResponse(String rawResponse, String reply) {
        return maskFirst(rawResponse, jsonEscape(reply), "<output masked>");
    }

    // Low-level primitive: replaces the first occurrence of `needle` in `haystack`
    // with `placeholder`. Returns the haystack unchanged if needle is empty/absent.
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
    // Completions reply. Deliberately hand-rolled: pulling in a JSON library would
    // cost JRock its "single file, zero dependencies" property for one field.
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

    // Builds the OpenAI Chat Completions request JSON around a messages array.
    // Used for both the real request and its masked display copy, so they stay
    // in lockstep (same model, same fields).
    //
    // No max_tokens: the request deliberately carries no output cap, so each model
    // applies its own default. Capping it here would silently truncate long replies
    // from models whose useful output is longer than any number JRock could guess.
    private static String chatRequestBody(CharSequence messagesJson) {
        return "{"
                + "\"model\":\"" + jsonEscape(MODEL_ID) + "\","
                + "\"messages\":" + messagesJson
                + "}";
    }

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
        String v = env(name);
        return (v == null) ? fallback : v;
    }

    // Reads a setting from the OS environment; returns null (never blank) when it
    // isn't set. Settings are NOT read from JVM system properties: credentials
    // must not be passable on a command line (-Dname=value), where they end up in
    // shell history and process listings. Hosts that cannot set environment
    // variables - CheerpJ in the browser - supply credentials through their own
    // HTTP client instead (see the HTTP transport section).
    private static String env(String name) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? null : v;
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

    // Heuristic: treat text containing a NUL or an unusual amount of other C0
    // control characters (excluding tab/newline/carriage return) as binary. Used
    // to avoid loading an image/binary file into the prompt as garbage.
    private static boolean looksBinary(String s) {
        if (s.indexOf('\0') >= 0) return true;
        int controls = 0, n = Math.min(s.length(), 4096);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') controls++;
            else if (c == '\uFFFD') controls++;   // UTF-8 replacement char = decode failure
        }
        return n > 0 && controls * 100 / n >= 5;   // >= 5% control/invalid chars
    }

    // Rewrites the persistent prompt file (crash recovery for the input box).
    private static void savePromptQuietly(String text) {
        atomicWriteQuietly(promptFile(), text);
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

    // ---- Message-file persistence (append-only JRock/messages/ directory) ---
    // Maps a role to a filename-safe slug. Kept explicit (no user text in the
    // name) so filenames are always predictable and injection-free.
    private static String roleSlug(String role) {
        return role.equals(ROLE_HUMAN) ? "operator" : "assistant";
    }

    // Builds the per-message file path from a validated stamp + role. The stamp
    // is ALWAYS a value we produced/validated (yyyyMMdd-HHmmss-SSS), never raw
    // user text, so the path can't escape logs/.
    private static Path messageFile(String role, String stamp) {
        return logsDir().resolve(stamp + "-" + roleSlug(role) + ".txt");
    }

    // Writes a dialog message body to its own file in logs/. Written once and
    // never modified afterwards. Best-effort.
    private static void writeMessageFile(String role, String stamp, String text) {
        try {
            Files.createDirectories(logsDir());
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
            Path base = logsDir().toAbsolutePath().normalize();
            Path resolved = file.toAbsolutePath().normalize();
            if (!resolved.startsWith(base)) return null;
            return readFileQuietly(file);
        } catch (DateTimeParseException ex) {
            return null;   // not a valid datetime -> ignore, no file access
        }
    }
}
