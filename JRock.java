// JRock - minimal Swing app that calls Amazon Bedrock via the OpenAI-compatible
// Chat Completions API on the bedrock-mantle endpoint, authenticated with a
// lightweight Bedrock API key (no SigV4, no AWS SDK, no ~/.aws credentials).
//
// Run directly with: java JRock.java
//   Optionally: java JRock.java <initial-prompt-file>
//   Prompt library: java JRock.java --prompts-dir <dir> [<initial-prompt-file>]
//     --prompts-dir makes <dir> the directory Load/Save prompt (Ctrl+O / Ctrl+S)
//     opens in, and the initial-prompt-file argument is then resolved relative to
//     it - so it can be a bare file name. Absolute paths are unaffected. Without
//     the flag it follows the working directory.
//     The same setting is in the Configure dialog, which overrides the flag for the
//     rest of the session; installing the Explorer entries then bakes in whatever
//     is in effect at that moment.
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
//     1. If a file path is passed as a CLI arg, load it READ-ONLY and use its
//        contents; the original file is never modified. That text is then written
//        to the persistent file, which continues to receive autosaves. A relative
//        path resolves against --prompts-dir when that was given.
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
//   Bearer token = your Bedrock API key, kept in JRock/bedrock-key.txt in the
//   working directory - one line, nothing else. A short-term (recommended)
//   Bedrock API key can be generated from the AWS console at:
//   https://console.aws.amazon.com/bedrock-mantle/api-keys
//   Put it in that file, or type it into the Configure dialog (top-left button),
//   which writes it there. Nothing is read from the environment: the region and
//   the model live beside the key in JRock/jrock-config.txt (see "Settings
//   files"), where they can be read, edited and copied like any other file.
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
    private static final String VERSION = "2.2.0";

    // Project home page (linked from the About line in the Configure dialog).
    private static final String GITHUB_URL = "https://github.com/ivan-khvostishkov/jrock";

    // ---- Configuration (mutable: changed via the Configure dialog) ----------
    private static final String DEFAULT_REGION = "us-east-1";
    // Region/model start from the built-in defaults, are replaced by whatever the
    // working folder's JRock/jrock-config.txt holds (see adoptSettingsOfWorkingDir),
    // and can be changed at runtime in the Configure dialog.
    private static String REGION = DEFAULT_REGION;
    // Where REGION came from, phrased for the startup log; null for the built-in
    // default and once the user sets it in the Configure dialog.
    private static String regionSource = null;
    private static String MODEL_ID = "xai.grok-4.3";
    private static final String PROMPT = "Hello, assistant.";

    // Request timeouts (seconds). The model list is a quick metadata call; a
    // completion can legitimately take much longer.
    private static final int MODELS_TIMEOUT_SECONDS  = 30;
    private static final int CHAT_TIMEOUT_SECONDS    = 60;
    private static final int CONNECT_TIMEOUT_SECONDS = 30;
    // Downloading a page or an image the user asked for (see fetchUrl). Not
    // an API call at all - some other server's, on a link that may well be slow -
    // so it gets the same patience as a completion rather than the model list's.
    private static final int URL_TIMEOUT_SECONDS     = 60;

    // How long the prompt's cursor has to sit still before "Autobackup log" takes its
    // backup. Long enough that it never lands in the middle of writing a message, short
    // enough that a coffee break is already backed up when you come back.
    private static final int IDLE_BACKUP_MINUTES = 5;

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

    // The Bedrock API key in effect: read from JRock/bedrock-key.txt at startup and
    // whenever the working folder changes, or set by the Configure dialog - which
    // writes it to that file as well. Null means there is none, which is normal in
    // the browser, where the key stays in the page.
    //
    // Never prefilled into the UI: the dialog's field is write-only, so a credential
    // cannot appear on screen (or in a screenshot of it).
    private static String apiKey = null;

    // How fine a picture JRock keeps, in dots per inch of page. Settable in the
    // Configure dialog ("Images DPI").
    //
    // It is a real trade-off, which is why it is worth exposing: the image is what the
    // model actually sees, so too low and small print becomes unreadable, while too
    // high costs tokens and time for detail no model needs. 150 reads ordinary
    // documents reliably and stays modest in size, so it is the default.
    //
    // One number for two jobs, because they are the same question asked twice:
    //   - a PDF included as page images is rasterised at it (Ghostscript's -r, where
    //     the page's physical size is the PDF's own business);
    //   - an image included WITH A COPY is downscaled to it, the page it is measured
    //     against being A4 portrait with 2 cm margins - the same page the DOCX export
    //     lays a picture out on, in the orientation that is enough for either of them
    //     (see a4Pixels and downscaledCopy, and MarkdownExport.Image for the export's
    //     own, deliberately separate, 300 dpi ceiling).
    private static final int[] IMAGES_DPI_OPTIONS = { 72, 96, 150, 203, 300 };
    private static final int IMAGES_DPI_DEFAULT = 150;
    private static int imagesDpi = IMAGES_DPI_DEFAULT;

    // "Autobackup log" in the Configure dialog: when on, a spell of inactivity in the
    // prompt takes a backup of the whole JRock folder (see the idle timer in
    // createAndShowGui, and backupLog). On by default, because the work worth keeping
    // is already on disk and a backup is what survives the disk.
    //
    // A setting rather than a checkbox in the window: it is decided once and then left
    // alone, and the top bar is for the two things that are toggled while working.
    private static boolean autoBackupLog = true;

    // Working directory = the process current directory. When JRock is launched
    // from the "JRock here!" context menu, Explorer starts it in the clicked
    // folder, so the CWD is already correct with no extra flags. The window
    // title/icon reflect this folder so multiple instances in different folders
    // are distinguishable in Alt-Tab / the taskbar.
    private static Path workingDir = Paths.get("").toAbsolutePath();

    // What the session report should add after the working directory: where it came
    // from when it was not simply the folder JRock was started in, or why a
    // --working-dir was not used. Null when there is nothing to explain.
    private static String workingDirNote = null;

    // Where prompts live: --prompts-dir on the command line, or the Configure dialog.
    //
    // This is deliberately separate from workingDir: a collection of prompts is
    // reference material that tends to sit in one place, while the working directory
    // is wherever today's work is. Keeping prompts out of it means Ctrl+O opens in
    // the library rather than in whichever folder JRock was started from.
    //
    // NULL means "follow the working directory" - the unset state, and what a plain
    // launch gets. The invariant is that this is non-null ONLY when the prompts
    // directory actually differs from the working one, so a value equal to it is
    // stored as null instead. Two things read that directly: the session report,
    // which then has nothing extra to say, and an installed Explorer entry, which
    // then carries no --prompts-dir - a flag repeating the launch folder would be
    // noise at best and wrong the moment you launched from elsewhere.
    private static Path promptsDir = null;

    // The prompts directory in effect: the configured one, or the working directory
    // when prompts follow it. Every prompt dialog opens here.
    private static Path promptsDir() { return promptsDir != null ? promptsDir : workingDir; }

    // What to say about the prompts directory in the session report when promptsDir
    // itself doesn't say it: a --prompts-dir value that had to be rejected, or one
    // that was accepted but meant nothing. Held as a string because the command line
    // is parsed before the log exists. Null when there is no such story to tell.
    private static String promptsDirNote = null;

    // Remembers where a file chooser last browsed, so the next dialog of the same
    // kind opens there. One instance PER PURPOSE, because these files live in
    // different places in practice: included documents and images wherever the
    // source material is, exported logs somewhere else again. A single shared memory
    // meant that exporting a log dropped the user where they last included a file - a
    // directory away from what they wanted.
    //
    // The two prompt dialogs (Ctrl+O, Ctrl+S) deliberately have NO ChooserDir: both
    // always open in the prompts directory. That is a setting now, and a setting that
    // quietly drifts as you browse is not one. It also keeps the pair symmetric -
    // prompts are loaded from and saved to the same place, which is what a library
    // is. See loadPromptInto and savePromptAs.
    private static final class ChooserDir {
        // Where this chooser goes when it has nothing remembered. A supplier, not a
        // Path, because workingDir is mutable - the Configure dialog changes it, and
        // these fields are initialized long before that.
        private final java.util.function.Supplier<Path> home;
        private Path dir;

        ChooserDir(java.util.function.Supplier<Path> home) {
            this.home = home;
            this.dir = home.get();
        }

        // Directory the next chooser of this kind should open in.
        java.io.File start() { return dir.toFile(); }

        // Same directory, with a default file name pre-filled (for Save dialogs).
        java.io.File startFile(String name) { return new java.io.File(dir.toFile(), name); }

        // Records where the user ended up, once they've confirmed the dialog.
        void remember(javax.swing.JFileChooser chooser) {
            java.io.File current = chooser.getCurrentDirectory();
            if (current != null) dir = current.toPath();
        }

        // Back to this chooser's home directory; see initSession().
        void reset() { dir = home.get(); }
    }
    private static final ChooserDir includeChooserDir = new ChooserDir(() -> workingDir);

    private static final ChooserDir logChooserDir     = new ChooserDir(() -> workingDir);

    // The duplex merge's own, because scans are not includes: a batch of them lives in
    // whatever folder the scanner drops them in, and that folder is the one both of its
    // dialogs should keep coming back to (see mergeDuplexScans).
    private static final ChooserDir scanChooserDir     = new ChooserDir(() -> workingDir);

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

    // Shared traits of Google Gemma models on Bedrock. Their cards are blunt about the
    // entry point: "Gemma 4 models are available only on the bedrock-mantle endpoint",
    // and "on bedrock-mantle, this model is served at /openai/v1/responses, not the
    // default /v1/responses" - an OpenAI-compatible base of /openai/v1, so Chat
    // Completions lives at /openai/v1/chat/completions. Text-out only.
    //
    // Worth knowing before choosing one: the cards say Chat Completions returns no
    // reasoning tokens even though the model reasons, because the OpenAI Chat Completions
    // specification has nowhere to put them - that is a limit of the API JRock speaks,
    // not of the model. The reasoning still happens and is still charged for.
    //
    // CONCRETE (not abstract), the way OpenAiModelCard is: it doubles as a generic "any
    // Google model" card for partial matches (an id starting with "google."). Every
    // Google model Bedrock documents today is a Gemma 4 on that same base, so an
    // unrecognised google.* id is better sent there than to the mantle default /v1.
    private static class GoogleModelCard extends BedrockModelCard {
        String vendorPrefix()         { return "google."; }
        String modelId()              { return "google."; }
        String displayName()          { return "Google Gemma (generic)"; }
        String cardUrl()              { return "https://docs.aws.amazon.com/bedrock/latest/userguide/models-supported.html"; }
        String[] inputModalities()    { return new String[] { "Image", "Text", "Video" }; }
        String[] outputModalities()   { return new String[] { "Text" }; }
        String[] endpointsSupported() { return new String[] { "bedrock-mantle" }; }
        String[] apisOnRuntime()      { return new String[] {}; }  // runtime not supported
        String[] apisOnMantle()       { return new String[] { "Responses", "Chat Completions" }; }
        @Override String mantleChatCompletionsPath() { return "/openai/v1/chat/completions"; }
    }

    // Google Gemma 4 26B-A4B: the mixture-of-experts one, 256K context, 25.2B parameters
    // with 3.8B active per token. Card: mantle only, Image/Text/Video in, Text out - and
    // NO audio, which is a red cross on its modality table where its small sibling has a
    // tick (see Gemma4E2bCard). Request payload, images and video included, caps at
    // 3.5 MB, which is a real limit for a document sent as page images.
    private static final class Gemma426bA4bCard extends GoogleModelCard {
        String modelId()     { return "google.gemma-4-26b-a4b"; }
        String displayName() { return "Gemma 4 26B-A4B"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-26b-a4b.html"; }
    }

    // Google Gemma 4 E2B: the compact one, 128K context, 5.1B parameters - and the only
    // card in this registry whose input modalities include Audio, which makes it the one
    // to try an @audio include against. Same 3.5 MB payload cap as its bigger sibling,
    // which for a recording is about the ceiling a minute or two of wav runs into.
    //
    // Its card recommends reasoning_effort "high", because the model reasons at length
    // anyway and the effort setting is what keeps that reasoning in its own channel
    // instead of in the answer. JRock sends no reasoning_effort of its own, so an answer
    // here may well arrive with its thinking in front of it.
    private static final class Gemma4E2bCard extends GoogleModelCard {
        String modelId()     { return "google.gemma-4-e2b"; }
        String displayName() { return "Gemma 4 E2B"; }
        String cardUrl()     { return "https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-google-gemma-4-e2b.html"; }
        String[] inputModalities() { return new String[] { "Audio", "Image", "Text", "Video" }; }
    }

    // Registry of known model cards, and a lookup by model id.
    private static final BedrockModelCard[] MODEL_CARDS = {
        new Grok43Card(), new KimiK25Card(), new DeepSeekV31Card(), new Qwen332bCard(),
        new Gpt54Card(), new Gpt6AstraCard(),
        new Gemma426bA4bCard(), new Gemma4E2bCard(),
        new ClaudeOpus5Card(), new ClaudeFable51Card(),
    };

    // Generic per-vendor cards, used as a fallback when no exact model-id match is
    // found but the id starts with a known vendor prefix (e.g. any "anthropic.*"
    // model routes to the Anthropic/Messages behavior).
    private static final BedrockModelCard[] VENDOR_CARDS = {
        new AnthropicModelCard(), new OpenAiModelCard(), new GoogleModelCard(),
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
    private static Path rtfMdDir()         { return jrockDir().resolve("rtf-md"); }
    private static Path docxMdDir()        { return jrockDir().resolve("docx-md"); }
    private static Path includesDir()      { return jrockDir().resolve("includes"); }
    private static Path urlsDir()          { return jrockDir().resolve("urls"); }
    // The two settings files (see "Settings files"): the credential on its own, and
    // everything else beside it.
    private static Path keyFile()          { return jrockDir().resolve("bedrock-key.txt"); }
    private static Path configFile()       { return jrockDir().resolve("jrock-config.txt"); }

    // The API key to sign a request with, or null when there is none. Null means the
    // same thing on every runtime, the browser included: JRock keeps its key in the
    // working folder's JRock/bedrock-key.txt everywhere, so there is nowhere that a
    // missing key is somebody else's key instead.
    private static String resolveApiKey() {
        return (apiKey != null && !apiKey.isBlank()) ? apiKey : null;
    }

    // ---- Settings files (JRock/bedrock-key.txt, JRock/jrock-config.txt) -----
    // Nothing is read from the environment. Settings live in the working folder, in
    // files the user can open, edit, copy and back up - the same promise the log and
    // the prompt make. An environment variable is the opposite: set in a shell that is
    // gone by the time anything goes wrong, invisible from inside the running
    // application, and different for every way of launching it.
    //
    // The key gets a file of its own because it is a credential: one line and nothing
    // else, so it can be locked down, kept out of a copy of the configuration, or
    // deleted on its own.
    //
    // Both files belong to the working folder, like that folder's log and prompt, and
    // are adopted when JRock takes a folder on - startup, a change in the Configure
    // dialog, a restore from a backup. A folder with no configuration yet is seeded
    // with the settings in effect; the key is NEVER copied into a folder it was not
    // typed for, a credential being nobody's idea of a convenience feature.
    private static final String CONFIG_REGION = "region";
    private static final String CONFIG_MODEL  = "model";
    // Kept per folder for the same reason the model is: one folder holds receipts to
    // read at 203 dpi and another holds drawings that need 300, and an agent installed
    // from each (see installAgent) is then two agents with two resolutions.
    private static final String CONFIG_IMAGES_DPI = "images-dpi";

    // Written above the settings, and the only documentation the format needs. The
    // format itself exists for one reason: a value is a whole line of its own, so it
    // can be cut and pasted as a line - no quoting, no escaping, and no "everything
    // after the = sign, but trimmed" to get wrong.
    private static final String CONFIG_HEADER =
            "' JRock settings - " + GITHUB_URL + "\n"
          + "'\n"
          + "' A setting is a name ending in $ on one line, its value on the next.\n"
          + "' Leading and trailing spaces are dropped. Blank lines, and lines starting\n"
          + "' with ' (a single quote), are ignored.\n"
          + "\n";

    // Where JRock reports its settings came from: both are set by
    // adoptSettingsOfWorkingDir and read by the session report.
    private static String settingsSource = null;
    private static String apiKeySource = null;

    // Reads the settings of the working folder and applies them, seeding the file when
    // that folder has none. Called from initSession, which is every moment JRock takes
    // a folder on.
    private static void adoptSettingsOfWorkingDir() {
        imagesDpiNote = null;   // whatever the PREVIOUS folder's file said is now history
        String stored = readFileQuietly(configFile());
        if (stored == null) {
            saveConfigQuietly();
            settingsSource = "JRock/jrock-config.txt (created with the current settings)";
        } else {
            java.util.Map<String, String> settings = parseConfig(stored);
            String region = settings.get(CONFIG_REGION);
            if (region != null) {
                REGION = region;
                regionSource = "from JRock/jrock-config.txt";
            }
            String model = settings.get(CONFIG_MODEL);
            if (model != null) MODEL_ID = model;
            adoptImagesDpi(settings.get(CONFIG_IMAGES_DPI));
            settingsSource = "JRock/jrock-config.txt";
        }
        adoptKeyOfWorkingDir();
    }

    // The same for the key, which is read here but never written: an empty file is
    // created so there is somewhere obvious to put one, and that is all.
    private static void adoptKeyOfWorkingDir() {
        String stored = readFileQuietly(keyFile());
        if (stored == null) {
            atomicWriteQuietly(keyFile(), "");
            apiKey = null;
            apiKeySource = "none yet - put one in JRock/bedrock-key.txt, "
                    + "or type it into Configure";
            return;
        }
        String key = stored.trim();
        apiKey = key.isEmpty() ? null : key;
        apiKeySource = key.isEmpty()
                ? "none yet - JRock/bedrock-key.txt is empty, and Configure fills it in"
                : "loaded from JRock/bedrock-key.txt";
    }

    // Writes the key to the working folder, where the next start reads it from. One
    // line and a newline, so an editor shows it as a line and `cat` doesn't run it
    // into whatever is printed next.
    private static void saveKeyQuietly(String key) {
        atomicWriteQuietly(keyFile(), key.trim() + "\n");
    }

    // Writes the settings of the working folder: the region, the model and the images
    // DPI, which are the ones that have to survive a restart to be worth setting at all.
    // Per folder rather than per user on purpose - one folder's agent can then run on a
    // different model, or render its pages at a different DPI, than the next one's.
    //
    // A blank value is left out rather than written as an empty line - an empty line
    // is a separator in this format, so writing one would make the NEXT name read as
    // this setting's value.
    private static void saveConfigQuietly() {
        StringBuilder text = new StringBuilder(CONFIG_HEADER);
        appendSetting(text, CONFIG_REGION, REGION);
        appendSetting(text, CONFIG_MODEL, MODEL_ID);
        appendSetting(text, CONFIG_IMAGES_DPI, String.valueOf(imagesDpi));
        atomicWriteQuietly(configFile(), text.toString());
    }

    // The stored images DPI, taken only when it is one of the values the Configure
    // dialog offers.
    //
    // Restricted because that dialog's dropdown is not editable: a hand-edited 400 would
    // be loaded, shown as 72 (the dropdown cannot select what it has no item for), and
    // then written back as 72 by the next OK - a setting that changes itself. A value
    // outside the list is left alone and reported instead, and nothing is said about a
    // setting that is simply absent, which is every file written before this existed.
    private static void adoptImagesDpi(String stored) {
        if (stored == null) return;
        try {
            int dpi = Integer.parseInt(stored.trim());
            for (int option : IMAGES_DPI_OPTIONS) {
                if (option == dpi) { imagesDpi = dpi; return; }
            }
            imagesDpiNote = stored.trim() + " is not one of "
                    + java.util.Arrays.toString(IMAGES_DPI_OPTIONS) + " - ignored";
        } catch (NumberFormatException ex) {
            imagesDpiNote = "\"" + stored.trim() + "\" is not a number - ignored";
        }
    }

    // What the session report should say about a stored images DPI it could not use.
    private static String imagesDpiNote = null;

    private static void appendSetting(StringBuilder text, String name, String value) {
        if (value == null || value.trim().isEmpty()) return;
        text.append(name).append("$\n").append(value.trim()).append("\n\n");
    }

    // Reads the format: name$ on one line, its value on the next, with blank lines and
    // ' comments ignored anywhere.
    //
    // A line where a name is due and which does not end in $ is skipped rather than
    // guessed at - the file is meant to be edited by hand, and the reading of a
    // half-edited one should be "that setting is missing", not "that setting now holds
    // a stray line". A value, on the other hand, is whatever its line says, trailing $
    // and all: at that point the format is not asking a question.
    private static java.util.Map<String, String> parseConfig(String text) {
        java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        if (text == null) return settings;
        String name = null;
        for (String rawLine : text.split("\r\n|\r|\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("'")) continue;
            if (name == null) {
                if (line.length() > 1 && line.endsWith("$")) {
                    name = line.substring(0, line.length() - 1).trim();
                }
            } else {
                settings.put(name, line);
                name = null;
            }
        }
        return settings;
    }

    // ---- UI ----------------------------------------------------------------
    public static void main(String[] args) {
        // Settle the HTTP transport before anything is shown: in the browser this
        // also adopts the region configured by the hosting page, which the startup
        // log and the Configure dialog then report.
        http();
        // Command line:
        //   [--working-dir <dir>] [--prompts-dir <dir>] [<initial-prompt-file>]
        String sourceArg = parseArgs(args);
        SwingUtilities.invokeLater(() -> createAndShowGui(sourceArg));
    }

    private static final String PROMPTS_DIR_FLAG = "--prompts-dir";
    private static final String WORKING_DIR_FLAG = "--working-dir";

    // Reads the command line, applying the directory flags as a side effect and
    // returning the initial-prompt file argument (null when none was given). Flags may
    // appear on either side of that argument; the first thing that isn't a flag is the
    // file. Both spellings of every flag are taken, because both are habitual:
    // "--flag=X" and "--flag X".
    //
    // Two passes, and the order is the point: --working-dir decides which folder's
    // settings, key, log and prompt are in play, and --prompts-dir is judged against it
    // (see setPromptsDir), so it cannot be left to whichever order they were typed in.
    private static String parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = argAt(args, i);
            if (arg.startsWith(WORKING_DIR_FLAG + "=")) {
                setWorkingDir(arg.substring(WORKING_DIR_FLAG.length() + 1).trim());
            } else if (arg.equals(WORKING_DIR_FLAG)) {
                if (i + 1 < args.length) {
                    setWorkingDir(argAt(args, ++i));
                } else {
                    workingDirNote = "(none given after " + WORKING_DIR_FLAG + ")";
                }
            }
        }
        String sourceArg = null;
        for (int i = 0; i < args.length; i++) {
            String arg = argAt(args, i);
            if (arg.isEmpty()) continue;

            // Already applied in the pass above, value and all.
            if (arg.equals(WORKING_DIR_FLAG)) { i++; continue; }
            if (arg.startsWith(WORKING_DIR_FLAG + "=")) continue;

            if (arg.startsWith(PROMPTS_DIR_FLAG + "=")) {
                setPromptsDir(arg.substring(PROMPTS_DIR_FLAG.length() + 1).trim());
            } else if (arg.equals(PROMPTS_DIR_FLAG)) {
                if (i + 1 < args.length) {
                    setPromptsDir(argAt(args, ++i));
                } else {
                    promptsDirNote = "(none given after " + PROMPTS_DIR_FLAG + ")";
                }
            } else if (sourceArg == null) {
                sourceArg = arg;
            }
        }
        return sourceArg;
    }

    private static String argAt(String[] args, int i) {
        return (args[i] == null) ? "" : args[i].trim();
    }

    // ---- A path the command line could not spell ---------------------------
    // Not JRock's doing, and not something JRock can undo - but worth one note, because
    // it looks like an include problem and is nothing of the kind. On Windows the
    // arguments that reach main(String[]) are decoded with sun.jnu.encoding, the system
    // ANSI code page - Cp1252 on a Western install - and a character that code page has
    // no room for is already a literal '?' before Java starts. Right-click a file with a
    // Cyrillic name and the agent is launched with C:\?????????\????????.rtf, which
    // Paths.get refuses outright: "InvalidPathException: Illegal char <?>".
    //
    // Which is why "Open with Acrobat" on that very file works. Windows passes a command
    // line in Unicode and keeps it that way; a native program asks for it with
    // GetCommandLineW and gets the real name. The narrowing to ANSI is the Java
    // launcher's, so every Java program started from Explorer shares it. It is also why
    // the same file opens perfectly from JRock's own file chooser - a chooser never goes
    // through a command line.
    //
    // Nothing here tries to work out what a '?' stood for: those characters were thrown
    // away rather than encoded, and a name pieced together from whatever a folder happens
    // to contain would be a guess wearing the shape of a path. An unreadable path is
    // reported as unreadable, and that is the end of it. Two ways round it, both the
    // user's: start the agent on the FOLDER and pick the file in the chooser, or turn on
    // "Use Unicode UTF-8 for worldwide language support" (Windows Region settings,
    // Administrative tab), which makes the code page UTF-8 and lets those arguments
    // through untouched. Tried, and it does: which is why every note about such a path
    // names it, in the words the dialog uses.
    private static final String UTF8_HINT = " - if it shows '?' where letters were, turn on"
            + " Region settings > Administrative > Change system locale... > \"Beta: Use"
            + " Unicode UTF-8 for worldwide language support\" and restart Windows";

    // Applies --working-dir, which roots JRock's own files - JRock/, its settings, its
    // key, its log - in a named folder instead of the one the process happens to have
    // been started in.
    //
    // Which is what an installed agent needs: Explorer starts a right-click command in
    // whichever folder was clicked, and an agent's whole point is the opposite - the
    // document can be anywhere, while the model, the region, the key and the DPI come
    // from one folder that was set up for this kind of work (see installAgent). The
    // same flag lets the same agent be installed twice, from two folders, with two sets
    // of settings.
    //
    // A path that is not a directory is reported and ignored rather than created: unlike
    // the Configure dialog, where a typed path is a deliberate act, this one is usually
    // baked into a registry entry months ago, and quietly recreating a folder someone
    // has since deleted or renamed would hide the mistake rather than show it.
    private static void setWorkingDir(String value) {
        if (value.isEmpty()) {
            workingDirNote = "(empty " + WORKING_DIR_FLAG + " ignored)";
            return;
        }
        // Caught rather than thrown: this path was written into a registry entry months
        // ago and comes back through an ANSI command line, so it may not be a path at all
        // any more (see the note above), and a flag that cannot be read must still leave
        // a window standing.
        Path candidate;
        try {
            candidate = Paths.get(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            workingDirNote = "(" + WORKING_DIR_FLAG + " " + value
                    + " is not a path this system can read - ignored" + UTF8_HINT + ")";
            return;
        }
        if (!Files.isDirectory(candidate)) {
            workingDirNote = "(" + WORKING_DIR_FLAG + " " + candidate
                    + " is not a directory - ignored)";
            return;
        }
        workingDir = candidate;
        // Best-effort, as in the Configure dialog: a real chdir isn't possible from
        // Java, so relative paths resolved by anything else at least agree with this.
        System.setProperty("user.dir", candidate.toString());
        workingDirNote = "(" + WORKING_DIR_FLAG + ")";
    }

    // Validates the --prompts-dir value now, while there is still a command line to
    // blame it on, and records what the session report should say. A path that isn't
    // a directory is reported and then ignored, rather than handed to a file chooser
    // that would silently open somewhere else entirely.
    private static void setPromptsDir(String value) {
        if (value.isEmpty()) {
            promptsDirNote = "(empty " + PROMPTS_DIR_FLAG + " ignored)";
            return;
        }
        Path candidate;
        try {
            candidate = Paths.get(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException bad) {
            promptsDirNote = value + " (not a path this system can read - "
                    + "using the working directory" + UTF8_HINT + ")";
            return;
        }
        if (!Files.isDirectory(candidate)) {
            promptsDirNote = candidate + " (not a directory - using the working directory)";
        } else if (candidate.equals(workingDir)) {
            // Accepted, and then deliberately not stored: prompts already open here.
            // Left as null to keep promptsDir's invariant (see its declaration), so
            // an Explorer entry installed later doesn't carry a --prompts-dir naming
            // the folder it was launched from. Still reported, because the flag WAS
            // given and silence would look like it had been dropped.
            promptsDirNote = candidate + " (same as the working directory)";
        } else {
            promptsDir = candidate;
        }
    }

    // The same setting from the Configure dialog, which replaces whatever
    // --prompts-dir asked for - the dialog is the later word, and the one the user is
    // looking at. Unlike the command line, a directory that doesn't exist yet is
    // CREATED: this path was typed or browsed to deliberately, just like the working
    // directory a few lines above it, and refusing it would be the odd one out.
    private static void applyPromptsDir(JFrame frame, String value) {
        // Emptied on purpose: back to following the working directory.
        if (value.isEmpty()) {
            promptsDir = null;
            promptsDirNote = null;
            return;
        }
        Path candidate = Paths.get(value).toAbsolutePath().normalize();
        if (candidate.equals(workingDir)) {
            // Left null rather than stored, per promptsDir's invariant: prompts now
            // follow the working directory, including if it changes again later.
            promptsDir = null;
            promptsDirNote = null;
            return;
        }
        try {
            Files.createDirectories(candidate);
            promptsDir = candidate;
            promptsDirNote = null;   // the path itself is now the whole story
        } catch (IOException ex) {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not use prompts directory " + candidate + ":\n" + ex.getMessage(),
                    "Invalid directory", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    // Resolves the initial-prompt file argument. An absolute path is used as given;
    // a relative one is resolved against --prompts-dir when that was supplied, so the
    // flag can name the directory and the argument just name a file inside it.
    // Without the flag this is the process working directory, as it always was.
    private static Path resolvePromptArg(String arg) {
        Path given;
        try {
            given = Paths.get(arg);
        } catch (java.nio.file.InvalidPathException bad) {
            // A name the command line could not spell (see the note above). Reported as
            // "could not read" like any other unreadable prompt file, rather than thrown
            // out of the startup.
            return null;
        }
        if (given.isAbsolute() || promptsDir == null) return given;
        return promptsDir.resolve(given);
    }

    // Wires undo/redo into a text component: Ctrl+Z undo, Ctrl+Y (and Ctrl+Shift+Z)
    // redo. JTextArea has no built-in undo, so we attach an UndoManager to its
    // document and bind the keystrokes. Returns the manager so the context menu can
    // offer the same two actions to users without a keyboard.
    private static UndoManager enableUndo(javax.swing.text.JTextComponent comp) {
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
        return undo;
    }

    // JRock-branded color for the role headers ([HUMAN OPERATOR] / assistant).
    private static final java.awt.Color BRAND = new java.awt.Color(0x0F, 0x8B, 0x8D); // teal

    // Roles for dialog messages. The label is what shows as the teal header AND
    // is used to build the per-message filename in logs/.
    private static final String ROLE_HUMAN = "HUMAN OPERATOR";
    private static final String ROLE_ASSISTANT = "OPERATOR'S ASSISTANT";

    // The third "role" - and the only one that is never a dialog message. The clock
    // (see clockMessage) is sent with a request but is not part of the conversation:
    // it is not shown, not logged as a turn, and not resent by extend mode. It exists
    // here solely so its file in JRock/messages/ is named like the other two.
    private static final String ROLE_CLOCK = "CLOCK";

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

    // ---- Clock -------------------------------------------------------------
    // A model has no clock and no location: on its own it cannot tell whether "now"
    // is Monday morning or Friday night, nor what "this evening" would mean here. The
    // Clock checkbox answers both with one extra message per request.
    //
    // Both halves of the answer are there on purpose. The offset (+02:00) is what
    // makes the time unambiguous, and the zone id (Europe/Berlin) is what the offset
    // cannot say: which place this is, and therefore when its clocks next change.
    // Seconds resolution, because a request takes longer than that anyway.
    //
    // Lowercase "xxx", not "XXX": the two differ only at UTC, where the uppercase form
    // writes "Z" and this one writes "+00:00". One shape for every zone is worth more
    // here than the shorter spelling - not least to whoever reads the file afterwards.
    private static final DateTimeFormatter CLOCK_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");

    // The clock message body, in the tagged form the model is meant to read it in.
    // Deliberately machine-shaped rather than localized: this one is addressed to the
    // model, not to the user (humanNow above is the user's copy).
    private static String clockMessage() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        return "<clock><now>" + now.format(CLOCK_FMT) + " " + now.getZone()
                + "</now></clock>";
    }

    // ---- Multimodal includes (Ctrl+I) --------------------------------------
    // Non-persistent map of file hash -> path. Cleared on restart (users must
    // re-include files to reuse them). Text, images and audio share this map; the token
    // kind (@img/@txt/@audio) in the prompt disambiguates how each is sent.
    private static final java.util.Map<String, Path> INCLUDES = new java.util.HashMap<>();

    // Hex digits kept from a file's SHA-256. Enough to identify a handful of
    // attachments per session without the token dominating the prompt and the log.
    private static final int HASH_LEN = 12;

    // Prompt token that stands in for an included file: "@img <hash>", "@txt <hash>" or
    // "@audio <hash>". Hash is a shortened hex SHA-256. Matched anywhere in the prompt.
    // The trailing lookahead requires the hash to end there, so a longer hex run isn't
    // read as a token plus leftover text.
    private static final java.util.regex.Pattern INCLUDE_TOKEN =
            java.util.regex.Pattern.compile(
                    "@(img|txt|audio) ([0-9a-f]{" + HASH_LEN + "})(?![0-9a-f])");

    // The include's own log line, read back: "Included @img <hash> from <path>", as
    // written by includeOne. The log is the only record of where an included file was
    // - INCLUDES itself is not persisted - so "Reload all includes" recovers the
    // mapping by reading the transcript it kept (see reloadAllIncludes). The two must
    // stay in step; the line is written in exactly one place for that reason.
    private static final java.util.regex.Pattern INCLUDE_LOG_LINE =
            java.util.regex.Pattern.compile(
                    "Included @(img|txt|audio) ([0-9a-f]{" + HASH_LEN + "}) from (.+)");

    // The first HASH_LEN hex digits of a file's SHA-256. Null on read failure.
    private static String hashFile(Path p) {
        try {
            return hashBytes(Files.readAllBytes(p));
        } catch (Exception ex) {
            return null;
        }
    }

    // The same, for bytes not (yet) on disk: a download that has to be compared with
    // what is already saved before it is written (see urlSaveTarget).
    private static String hashBytes(byte[] bytes) {
        try {
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
                rememberMessageFile(role, stamp);
                renderDialog(e);        // dialog is always visible
                persistMainLog();
            });
        }

        // The raw request/response block: a gray entry like any other, with its two
        // headers naming the message files this exchange wrote.
        void grayDump(String text) {
            SwingUtilities.invokeLater(() -> {
                Entry e = new Entry(false, null, null, namedHeaders(text), "");
                entries.add(e);
                logCopySaved = false;
                if (isVisible(e)) renderGray(e.text);
                persistMainLog();
            });
        }

        // ---- Which file each half of the last exchange went into ------------
        // The dump in the log is masked - the prompt, the reply and every attachment
        // are hashes in it - and the files under JRock/messages/ are not. So the two
        // headers name them: "--- raw request < 20260924-004543-348-operator.txt ---"
        // is one glance from the log line to the file that holds that request's text
        // in full, instead of a folder to be searched by the timestamp of a send.
        //
        // Remembered here rather than passed in, because the names do not exist when
        // the dump is built: callModel runs on a worker thread, and the reply's file is
        // not written until the reply is logged - which is queued ahead of this entry
        // and therefore done by the time it is added.
        //
        // A request clears the reply of the exchange before it. A send that fails logs
        // its dump with no reply file written at all, and naming the previous one would
        // be worse than naming none.
        private String requestFile, responseFile;

        private void rememberMessageFile(String role, String stamp) {
            String name = messageFile(role, stamp).getFileName().toString();
            if (role.equals(ROLE_HUMAN)) {
                requestFile = name;
                responseFile = null;
            } else if (role.equals(ROLE_ASSISTANT)) {
                responseFile = name;
            }
        }

        private String namedHeaders(String text) {
            String named = namedHeader(text, RAW_REQUEST_HEADER, '<', requestFile);
            return namedHeader(named, RAW_RESPONSE_HEADER, '>', responseFile);
        }

        // One header, named: "< file" for the request, "> file" for the response - the
        // arrow being the direction that file went, out of JRock and into it.
        //
        // The first occurrence that starts a line, which is where rawDump puts it, and
        // otherwise nothing: a half with no file and a dump that does not look the way
        // it should are both left exactly as they came.
        private String namedHeader(String text, String header, char arrow, String file) {
            if (file == null) return text;
            int at;
            if (text.startsWith(header)) {
                at = 0;
            } else {
                int found = text.indexOf("\n" + header);
                if (found < 0) return text;
                at = found + 1;
            }
            String stem = header.substring(0, header.length() - " ---".length());
            return text.substring(0, at) + stem + " " + arrow + " " + file + " ---"
                    + text.substring(at + header.length());
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

        // Every entry in order, as {role, text} pairs with role null for a gray line:
        // dialogHistory() without the filter. "Reload all includes" needs both kinds
        // and needs them interleaved, because an include line is what says where a
        // file was, and the message under it is what says the file was used - and a
        // second include of the same hash later moves it (see reloadAllIncludes).
        java.util.List<String[]> timeline() {
            java.util.List<String[]> out = new ArrayList<>();
            for (Entry e : entries) out.add(new String[] { e.dialog ? e.role : null, e.text });
            return out;
        }

        // The stamp of the most recent message of that role, or null when there is
        // none yet. This is the whole of the "reference" a send hands an automation
        // back (see automationSend): the stamp already IS the name of the file the
        // body was written to, so there is nothing else to remember.
        //
        // EDT only, like every other reader of the entry list.
        String lastStamp(String role) {
            for (int i = entries.size() - 1; i >= 0; i--) {
                Entry e = entries.get(i);
                if (e.dialog && role.equals(e.role)) return e.stamp;
            }
            return null;
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

    // Normalizes a message as it is taken from the window or out of a reply, BEFORE
    // it is either sent or logged - so the request and the transcript carry one
    // identical string, and an extended conversation resends exactly what is on
    // screen. Trailing spaces and surrounding blank lines are invisible but a
    // selection picks them up, so a line copied as a filename would arrive with
    // spaces on it, and blank lines at the end push the separator after a message
    // down. Indentation on the first kept line survives, so code keeps its shape.
    private static String stripMessage(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        int first = -1, last = -1;
        for (int i = 0; i < lines.length; i++) {
            lines[i] = lines[i].stripTrailing();   // also drops a \r from CRLF input
            if (!lines[i].isEmpty()) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return "";                  // nothing but whitespace
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = first; i <= last; i++) {
            if (i > first) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
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
        // Not ready while the report is being written, and ready again when it ends.
        // The Configure dialog runs this a second time, so an automation that arrives
        // mid-reconfiguration waits for the new session rather than the old one (see
        // automationAwaitReady).
        sessionReady = false;
        // Every file chooser starts fresh in the (possibly new) working directory.
        includeChooserDir.reset();
        logChooserDir.reset();
        scanChooserDir.reset();

        // Recover any previous log from disk FIRST. loadFromDisk() replaces the
        // entry list (rebuild -> setText), so it must run before we log anything
        // for this session, otherwise those lines would be wiped.
        boolean hadLog = Files.exists(logFile());
        int restored = log.loadFromDisk();

        // The settings of this folder, before anything reports what they are. After
        // loadFromDisk for the same reason the report lines are: it replaces the entry
        // list, so anything logged before it would be wiped.
        adoptSettingsOfWorkingDir();

        // The session report begins here; the working directory is its first line.
        // A restored log already ends with its own trailing blank line.
        log.gray("Working directory: " + workingDir
                + (workingDirNote == null ? "" : "  " + workingDirNote));
        // Only when prompts don't simply follow the working directory - saying so
        // when they do would just repeat the line above. promptsDirNote covers the
        // cases where promptsDir is null but something still needs explaining: a
        // --prompts-dir that was rejected, or one that named the working directory.
        if (promptsDir != null) {
            log.gray("Prompts & agents: " + promptsDir);
        } else if (promptsDirNote != null) {
            log.gray("Prompts & agents: " + promptsDirNote);
        }
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
        if (apiKeySource != null) {
            log.gray("Bedrock API key: " + apiKeySource);
        }
        log.gray("Settings: " + settingsSource);
        if (regionSource != null) {
            log.gray("AWS region: " + REGION + " (" + regionSource + ")");
        } else {
            log.gray("AWS region: " + REGION);
        }
        // The one place the host is spelled out; later request lines log paths only.
        log.gray("Bedrock endpoint: " + mantleHost());
        log.gray("Configured model: " + MODEL_ID);
        resolveAndLogCard(MODEL_ID, log);
        // Only when it has been changed: silent for everyone on the default, but
        // applying the dialog re-runs this report, so a change is acknowledged.
        // Every conversion logs its full Ghostscript command line anyway, -r and all.
        if (imagesDpi != IMAGES_DPI_DEFAULT) {
            log.gray("Images DPI: " + imagesDpi + " dpi (default " + IMAGES_DPI_DEFAULT + ")");
        }
        // A hand-edited images-dpi$ that could not be used, which is the one case where
        // saying nothing would be misleading: the file asks for 400 and the pages come
        // out at 150, and only this line explains why.
        if (imagesDpiNote != null) {
            log.gray("Stored images DPI: " + imagesDpiNote);
        }
        // Same rule, and the more important one to say out loud: a backup that is not
        // being taken is worth a line, so nobody counts on one that was switched off.
        if (!autoBackupLog) {
            log.gray("Autobackup log: off");
        }
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
                // The same word, for a program: this is the moment the window can be
                // used, and the moment automationAwaitReady returns.
                sessionReady = true;
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
        if (!haveKey) {
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
        JFrame frame = new JFrame();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        applyWindowIdentity(frame);

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

        // Top bar: [Configure] on the left; [Dialog only] [Clear log] on the right.
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
            // Resolved, not raw: with --prompts-dir the argument can be a bare file
            // name, and the full path is what makes the line useful when it didn't
            // load.
            Path argPath = resolvePromptArg(sourceArg);
            String fromArg = (argPath == null) ? null : readFileQuietly(argPath);
            if (fromArg != null) {
                initialPrompt = fromArg;
                promptSource = "command-line file (read-only): " + argPath;
            } else {
                initialPrompt = PROMPT;
                promptSource = "default (could not read "
                        + (argPath == null ? sourceArg + UTF8_HINT : argPath.toString())
                        + ")";
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
        UndoManager promptUndo = enableUndo(input);

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

        // "History" mode: when on, each send includes the full prior dialog so the model
        // sees a continuous conversation, not a single message.
        //
        // Called History and not "Extend conversation" because the bottom bar has to fit
        // on a phone, and because a checkbox wants a noun: it sits beside Clock, and the
        // pair reads as the two things that can travel with a message - the time, and
        // what was said before. A verb like Continue or Append reads as a button that
        // does something now, which is the one thing a checkbox never does.
        javax.swing.JCheckBox extendMode = new javax.swing.JCheckBox("History");
        extendMode.setToolTipText(
                "Send the whole prior dialog with each message, so the model follows the "
                + "conversation (Ctrl+E)");

        // "Clock" mode: tell the model what time it is here, with each message. On by
        // default - a model that has to guess the date guesses wrong, and one extra
        // short message is a cheap way to stop it (see clockMessage).
        javax.swing.JCheckBox clockMode = new javax.swing.JCheckBox("Clock", true);
        clockMode.setToolTipText(
                "Send the local time and time zone with each message, as <clock><now>...</now></clock>");

        JButton send = new JButton("Send (Ctrl-Enter)");

        // "Enter" mode: when on, plain Enter sends too. Off at startup and never
        // remembered - deliberately, both times.
        //
        // Off, because Enter is the key a text area owes a new line to, and a window that
        // sends on Enter without being asked sends half-written prompts. Never
        // remembered, because the answer to "what does Enter do here" must not depend on
        // what happened in a window that is already closed: the checkbox IS the answer,
        // and it starts in the state that cannot surprise anyone. That makes it useful to
        // the careful user as much as to the fast one - unticked, it says in the one place
        // they are looking that Enter is safe.
        javax.swing.JCheckBox enterSends = new javax.swing.JCheckBox("Enter");
        enterSends.setToolTipText(
                "Enter sends the prompt as well as Ctrl+Enter; Shift+Enter is always a "
                + "new line. Off when JRock starts, and not remembered");

        // Two things disable Send - a request in flight and a backup in progress - and
        // they can overlap: a backup started while an answer was on its way must not
        // re-enable the button when it finishes, and the answer arriving must not
        // re-enable it while the backup is still running. So the button is gated by a
        // count of reasons rather than by whoever spoke last. EDT only, like the button.
        int[] sendBlockers = { 0 };
        java.util.function.Consumer<Boolean> sendGate = allow -> {
            if (allow) {
                if (sendBlockers[0] > 0) sendBlockers[0]--;
            } else {
                sendBlockers[0]++;
            }
            send.setEnabled(sendBlockers[0] == 0);
        };

        send.addActionListener(e -> {
            // Normalized here, at the one point the prompt leaves the text area, so
            // the request and the transcript get the identical string.
            String prompt = stripMessage(input.getText());
            if (prompt.isEmpty()) {
                log.gray("Nothing to send - type a prompt first...");
                log.gray("");
                sendFinished("the prompt is empty.");
                return;
            }
            sendGate.accept(false);
            // In "extend" mode, capture the prior dialog turns BEFORE adding the new
            // prompt, so the request is [history...] + [new prompt].
            boolean extend = extendMode.isSelected();
            boolean clock = clockMode.isSelected();
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
                sendGate.accept(true);
                sendFinished(includeError);
                return;
            }

            // If the model isn't served via Chat Completions on mantle, don't even
            // log "Calling ..." - report why and stop, without any HTTP request.
            BedrockModelCard card = cardFor(MODEL_ID);
            if (card != null && !card.supportsMantleChatCompletions()) {
                String via = card.mantleMessagesPath() != null
                        ? "the Anthropic Messages API (" + card.mantleMessagesPath() + ")"
                        : "an API JRock does not implement";
                String why = "model \"" + MODEL_ID + "\" is not served via Chat Completions "
                        + "on bedrock-mantle; it uses " + via + ".";
                log.gray("Model \"" + MODEL_ID + "\" is not served via Chat Completions on "
                        + "bedrock-mantle; it uses " + via + ", which JRock does not implement "
                        + "yet. Choose a Chat-Completions-capable model.");
                log.gray("");
                sendGate.accept(true);
                sendFinished(why);
                return;
            }

            log.gray(extend
                    ? "Calling " + pathOf(endpoint()) + " (extend: " + history.size() + " prior turns) ..."
                    : "Calling " + pathOf(endpoint()) + " ...");
            new SwingWorker<String[], Void>() {
                @Override
                protected String[] doInBackground() {
                    try {
                        return callModel(prompt, history, clock);
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
                    // Whatever comes out of the block below, one of the two is what an
                    // automation waiting on this send is told (see sendFinished).
                    String failure = "the reply was lost.";
                    try {
                        String[] result = get();
                        // result[0] = "1" success / "0" failure.
                        // result[1] = model reply (success) or error text (failure).
                        // result[2] = raw request/response/stats -> always gray, or null.
                        boolean ok = "1".equals(result[0]);
                        failure = ok ? null : result[1];
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
                            log.grayDump(result[2]);
                            log.gray("");        // closes the raw request/response/stats block
                        }
                    } catch (Exception ex) {
                        failure = "ERROR: " + ex.getMessage();
                        log.gray("ERROR: " + ex.getMessage());
                        log.gray("");
                    }
                    sendGate.accept(true);
                    sendFinished(failure);
                }
            }.execute();
        });

        // Autobackup: a one-shot timer, restarted by every caret event in the prompt
        // area, so it only ever fires when the cursor has not moved there - typed,
        // clicked or arrowed - for IDLE_BACKUP_MINUTES. That is the point: a backup
        // zips the whole JRock folder and holds the Send button while it does, which
        // nobody wants mid-sentence, and an idle window is exactly when it is free.
        //
        // Deliberately NOT restarted after it fires: one backup per idle spell, not one
        // every five minutes for as long as the window is left open. The next caret
        // event arms it again.
        //
        // The timer runs whatever the setting says and asks autoBackupLog only when it
        // fires, so turning "Autobackup log" off in Configure takes effect at once -
        // and turning it back on does not need the timer re-armed by hand.
        javax.swing.Timer idleBackup =
                new javax.swing.Timer(IDLE_BACKUP_MINUTES * 60_000, null);
        idleBackup.setRepeats(false);
        idleBackup.addActionListener(e -> {
            if (autoBackupLog) backupLog(frame, log, sendGate, false);
        });
        input.addCaretListener(e -> idleBackup.restart());
        idleBackup.start();   // so the first idle spell counts from startup

        // Ctrl+Enter in the prompt area triggers Send.
        input.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "jrock-send");
        input.getActionMap().put("jrock-send", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (send.isEnabled()) send.doClick();
            }
        });

        // Plain Enter: Send when the "Enter" checkbox is on, a new line when it is off.
        // Shift+Enter is a new line in BOTH states, so there is always one key that types
        // one and never sends - which is what makes the checkbox safe to tick.
        //
        // Shift+Enter has to be bound, not left alone: a plain JTextArea has no binding
        // for it at all (its InputMap answers null), and Windows sends no KEY_TYPED for
        // it either, so before this it did nothing whatsoever. Verified with a Robot, both
        // ways round, rather than assumed.
        //
        // The newline is inserted here rather than delegated to the editor kit's
        // insert-break action: that action is looked up through the L&F's ActionMap, and a
        // lookup that comes back null is a key that silently does nothing. replaceSelection
        // is the same edit - it honours the selection, the caret and undo - and it cannot
        // be missing.
        javax.swing.Action newLine = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { input.replaceSelection("\n"); }
        };
        input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "jrock-enter");
        input.getActionMap().put("jrock-enter", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!enterSends.isSelected()) {
                    newLine.actionPerformed(e);
                } else if (send.isEnabled()) {
                    send.doClick();
                }
            }
        });
        input.getInputMap().put(KeyStroke.getKeyStroke(
                KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "jrock-newline");
        input.getActionMap().put("jrock-newline", newLine);

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

        // Bottom bar: Send and what Enter does on the left, the two send-time checkboxes
        // on the right, same row. Zero gaps in this layout keep History flush with the
        // bar's own right margin, so the space before it is a strut rather than a hgap.
        //
        // Enter belongs beside the button and not with the other two: it says what a key
        // does in this window, while Clock and History say what goes out with the next
        // message. Beside the button it also sits where the eye already is.
        javax.swing.JPanel sendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        sendSide.add(send);
        sendSide.add(javax.swing.Box.createHorizontalStrut(10));
        sendSide.add(enterSends);
        javax.swing.JPanel extendSide = new javax.swing.JPanel(
                new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        extendSide.add(clockMode);
        extendSide.add(javax.swing.Box.createHorizontalStrut(10));
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

        // Ctrl+E toggles History (E for extend, which is what this was called and what
        // the field is still named; Ctrl+H is a text area's backspace).
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

        // Ctrl+I includes a text, image or audio file: hashes it, remembers hash -> path,
        // and inserts an "@img" / "@txt" / "@audio <hash>" token at the prompt cursor.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_I, InputEvent.CTRL_DOWN_MASK), "jrock-include");
        frame.getRootPane().getActionMap().put("jrock-include", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showIncludeDialog(frame, input, log, extendMode.isSelected(), false);
            }
        });

        // Ctrl+Shift+I is the same include, keeping a copy: the file is copied into
        // JRock/includes/ first and included from there, so the include outlives
        // whatever happens to the original (see includeCopyOf). Shift because it is
        // Ctrl+I with something added, not a different thing.
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_I,
                        InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "jrock-include-copy");
        frame.getRootPane().getActionMap().put("jrock-include-copy", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showIncludeDialog(frame, input, log, extendMode.isSelected(), true);
            }
        });

        // Ctrl+U is the same include for something that is not on this machine: U for
        // URL, next to Ctrl+I because that is what it is - Include, over the network
        // (see showFetchUrlDialog).
        frame.getRootPane().getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_U, InputEvent.CTRL_DOWN_MASK), "jrock-fetch-url");
        frame.getRootPane().getActionMap().put("jrock-fetch-url", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                showFetchUrlDialog(frame, input, log, extendMode.isSelected());
            }
        });

        // Configure button: opens the settings dialog, then re-runs the session
        // report (CWD first, models loaded, ... Ready) exactly like startup.
        // initSession reloads the log from the (possibly new) working directory,
        // fully replacing the window contents - so switching directories shows
        // only the new directory's log, with nothing carried over from the old one.
        //
        // The prompt goes with it when the directory changed (see
        // adoptPromptOfWorkingDir), and is reported like it is at startup. A dialog
        // that changed something else leaves the prompt, and its report line, alone.
        configure.addActionListener(e -> {
            Path dirBefore = workingDir;
            if (showConfigureDialog(frame)) {
                boolean movedDir = !workingDir.equals(dirBefore);
                // The title and the icon name the folder, so they move with it too.
                if (movedDir) applyWindowIdentity(frame);
                initSession(log, movedDir ? adoptPromptOfWorkingDir(input) : null);
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
        // ...and two that only make sense for a selection, so they are simply greyed
        // out without one: the model answers in Markdown, and these write that answer
        // out as a document a word processor - or a layout application - can open.
        javax.swing.JMenuItem exportRtfItem =
                addMenuItem(logMenu, "Export selected Markdown as RTF...",
                        () -> exportSelectedMarkdown(frame, log, false));
        javax.swing.JMenuItem exportDocxItem =
                addMenuItem(logMenu, "Export selected Markdown with images as DOCX...",
                        () -> exportSelectedMarkdown(frame, log, true));
        // The log pane is read-only, so Copy is the only clipboard verb it needs.
        logMenu.addSeparator();
        javax.swing.JMenuItem copyLogItem = addEditItem(logMenu, "Copy", output,
                () -> clipboardCopy(output.getSelectedText(), log));
        logMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean selected = log.selectedText() != null;
                saveLogItem.setText(selected ? "Save selected text as..." : "Save log copy as...");
                printLogItem.setText(selected ? "Print selected text..."  : "Print...");
                exportRtfItem.setEnabled(selected);
                exportDocxItem.setEnabled(selected);
                copyLogItem.setEnabled(selected);
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        attachPopup(output, logMenu);

        // Prompt area: Include... / Load prompt... / Save prompt copy...
        javax.swing.JPopupMenu promptMenu = new javax.swing.JPopupMenu();
        addMenuItem(promptMenu, "Include text, image, audio, PDF, RTF or DOCX file...",
                () -> showIncludeDialog(frame, input, log, extendMode.isSelected(), false));
        // The same dialog, the same filters, one thing more: the chosen file is copied
        // into JRock/includes/ and included from the copy, which is the include that
        // still works after a restart (see includeCopyOf). A second menu item rather
        // than a checkbox in the chooser, because a chooser has nowhere to put one
        // except the accessory column down its right-hand side.
        addMenuItem(promptMenu, "Include with copy...",
                () -> showIncludeDialog(frame, input, log, extendMode.isSelected(), true));
        // The same include for a file that is not on this machine: the address is
        // fetched into JRock/urls/ and included from there, as text or as a picture
        // according to what it answered with (see fetchUrl).
        addMenuItem(promptMenu, "Fetch URL...",
                () -> showFetchUrlDialog(frame, input, log, extendMode.isSelected()));
        // Next to it, the repair for a conversation that outlived the session that
        // started it: the includes are read back out of the log rather than attached
        // again one by one (see reloadAllIncludes).
        addMenuItem(promptMenu, "Reload all includes",
                () -> reloadAllIncludes(input, log));
        addMenuItem(promptMenu, "Load prompt from file...",
                () -> loadPromptInto(frame, input, log));
        addMenuItem(promptMenu, "Save prompt copy as...",
                () -> savePromptAs(frame, input.getText()));
        // Clipboard and undo/redo, which on a touch device have no keyboard to come
        // from. Paste stays enabled whatever the clipboard holds: an empty one is a
        // harmless no-op, and asking for its contents just to grey out an item can
        // fail when another process owns it.
        promptMenu.addSeparator();
        javax.swing.JMenuItem cutItem   = addEditItem(promptMenu, "Cut",   input,
                () -> clipboardCut(input, log));
        javax.swing.JMenuItem copyItem  = addEditItem(promptMenu, "Copy",  input,
                () -> clipboardCopy(input.getSelectedText(), log));
        addEditItem(promptMenu, "Paste", input, () -> clipboardPaste(input, log));
        // Select all, which is also how a touch device clears the prompt: select the lot,
        // then Backspace. There is no Ctrl+A to press, and dragging a selection from the
        // top of a long prompt to the bottom of it on a phone is its own small ordeal.
        addEditItem(promptMenu, "Select all", input, input::selectAll);
        javax.swing.JMenuItem undoItem  = addEditItem(promptMenu, "Undo",  input,
                () -> { if (promptUndo.canUndo()) promptUndo.undo(); });
        javax.swing.JMenuItem redoItem  = addEditItem(promptMenu, "Redo",  input,
                () -> { if (promptUndo.canRedo()) promptUndo.redo(); });
        promptMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                boolean selected = input.getSelectedText() != null;
                cutItem.setEnabled(selected);
                copyItem.setEnabled(selected);
                undoItem.setEnabled(promptUndo.canUndo());
                redoItem.setEnabled(promptUndo.canRedo());
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        attachPopup(input, promptMenu);

        // In the browser, point Ctrl+C/X/V at the page's clipboard too, so the
        // keyboard and the menu reach the same place.
        useBrowserClipboard(output, log, false);   // read-only: copy only
        useBrowserClipboard(input, log, true);

        // Window chrome (empty area of the top bar, e.g. right of Configure): the duplex
        // merge, then the backup pair, then Move & resize window...; in the browser, also
        // show/hide the page's own header and footer; on Windows, install/uninstall the
        // "Open JRock here" folder context-menu entry and the agent entries.
        //
        // The duplex merge leads the menu, alone above its separator, because it is the
        // only item here that does something to documents rather than to JRock; backup
        // and restore come next, above their own separator, because they are about the
        // work rather than about the window - and because they are the items a hurry
        // would look for.
        javax.swing.JPopupMenu windowMenu = new javax.swing.JPopupMenu();
        addMenuItem(windowMenu, "Merge two-sided (duplex) PDF scans...",
                () -> mergeDuplexScans(frame, log));
        windowMenu.addSeparator();
        addMenuItem(windowMenu, "Backup log...", () -> backupLog(frame, log, sendGate, true));
        addMenuItem(windowMenu, "Load from backup...",
                () -> showRestoreDialog(frame, input, log, sendGate));
        windowMenu.addSeparator();
        addMenuItem(windowMenu, "Move & resize window...", () -> showMoveResizeDialog(frame));
        if (isCheerpJ()) {
            windowMenu.addSeparator();
            addMenuItem(windowMenu, "Show/hide the page header & footer",
                    () -> toggleBrowserChrome(log));
        }
        if (isWindows()) {
            windowMenu.addSeparator();
            addMenuItem(windowMenu, "Install \"JRock here!\" (Explorer menu)...",
                    () -> installContextMenu(frame, log));
            addMenuItem(windowMenu, "Uninstall \"JRock here!\" (Explorer menu)...",
                    () -> uninstallContextMenu(frame, log));
            // The same pair for one automation from the prompts & agents directory,
            // installed for THIS working directory: see the agent context menu section.
            addMenuItem(windowMenu, "Install agent (Explorer menu)...",
                    () -> installAgent(frame, log));
            addMenuItem(windowMenu, "Uninstall agent (Explorer menu)...",
                    () -> uninstallAgent(frame, log));
        }
        attachPopup(topBar, windowMenu);

        // The window is now complete, so publish its parts for the automation API -
        // last, and only once, so nothing can be driven from outside before all of it
        // exists (see the Automation API section).
        ui = new Ui(frame, input, log, send, extendMode, sendGate);

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

    // A menu item acting on a text component, which then takes focus back so the
    // caret is where the user continues typing (or, in the log, so the selection
    // they just copied stays highlighted).
    private static javax.swing.JMenuItem addEditItem(javax.swing.JPopupMenu menu, String label,
                                                     javax.swing.text.JTextComponent comp,
                                                     Runnable action) {
        return addMenuItem(menu, label, () -> {
            action.run();
            comp.requestFocusInWindow();
        });
    }

    // The one-item context menu a single-line field gets: Paste, on a long tap or a
    // right-click (see attachPopup).
    //
    // For the fields whose value comes from somewhere else - a URL, an API key - which
    // is to say the fields nobody types by hand. On a phone there is no Ctrl+V and no
    // menu bar, so without this the only way in is to retype the value; in the browser
    // build Ctrl+V is pointed at the page's clipboard here too, so the key and the menu
    // reach the same place (see useBrowserClipboard).
    //
    // Paste alone: these are fields a value is put into, not text that is edited.
    private static void addPasteMenu(javax.swing.text.JTextComponent field, LogView log) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        addEditItem(menu, "Paste", field, () -> clipboardPaste(field, log));
        attachPopup(field, menu);
        useBrowserClipboard(field, log, true);
    }

    // The same for a field whose text is worth taking away as well as putting in: Copy
    // and Paste, and Select all beside them because a field that is being pasted over is
    // a field whose old contents are in the way.
    //
    // Written for a file chooser's "File name" line, where on a phone it is the only way
    // to open more than one file at a time: multi-selection in the list wants Shift or
    // Ctrl, and a touch screen has neither - so the names have to go in by hand, and by
    // hand on a phone means pasted. The chooser's own syntax for several is each name in
    // double quotes, separated by spaces: "a.png" "b.png".
    private static void addCopyPasteMenu(javax.swing.text.JTextComponent field, LogView log) {
        if (field == null) return;
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        addEditItem(menu, "Copy", field,
                () -> clipboardCopy(field.getSelectedText() == null
                        ? field.getText() : field.getSelectedText(), log));
        addEditItem(menu, "Paste", field, () -> clipboardPaste(field, log));
        addEditItem(menu, "Select all", field, field::selectAll);
        attachPopup(field, menu);
        useBrowserClipboard(field, log, true);
    }

    // ---- Automation API (public) -------------------------------------------
    // The window, driven from outside it: a program in the same JVM does what a user
    // would do by hand - load a prompt, include a file, press Send, read the reply -
    // while the user watches it happen. See automation-samples/ for one that chains
    // the two sample prompts into a document archive.
    //
    // Why a public API at all. Chaining prompts is a habit rather than a one-off (see
    // "Prompt library and chaining" in the README), and the alternatives are both
    // worse: a second, headless code path that sends requests JRock's own window never
    // sees, or a robot clicking at screen coordinates. These methods go through the
    // very same code the menu items and the Send button go through, so the
    // conversions, the log lines, the JRock/messages/ files and the token bill are the
    // ones the application always produces - and the log is the record of the
    // automation as much as of the conversation.
    //
    // The shape is deliberately plain: static methods, String arguments, String or
    // String[] results, null for "nothing went wrong". An automation is a single .java
    // file run with `java -cp jrock.jar Automation.java` - the source-file launcher
    // compiles it in memory against that class path, so these are ordinary typed calls
    // the compiler checks, and a jar too old to have one of them is a compile error
    // naming the method rather than a surprise halfway through a document. Static
    // because there is nothing to instantiate: the window, the log, the JRock/
    // directory and the session are one per process.
    //
    // Plain also because the results are read by scripts. A reason travels with every
    // failure, in the language the log is written in, so an automation can put it
    // straight into its own dialog without a table of error codes in between.
    //
    // Every one of them must be called from a thread that is NOT the event dispatch
    // thread. They block, and two of them block for a long time: an include of a PDF
    // waits for Ghostscript, a send waits for Bedrock. On the EDT that is a frozen
    // window, and for a send it is a deadlock - the reply is delivered by the EDT.

    // The window's own parts, published by createAndShowGui once all of them exist.
    // One object rather than six fields so there is no moment at which half a window
    // is visible to a caller; volatile because it is written on the EDT and read from
    // the automation's thread.
    private static final class Ui {
        final JFrame frame;
        final JTextArea input;
        final LogView log;
        final JButton send;
        final javax.swing.JCheckBox extend;
        final java.util.function.Consumer<Boolean> sendGate;

        Ui(JFrame frame, JTextArea input, LogView log, JButton send,
           javax.swing.JCheckBox extend, java.util.function.Consumer<Boolean> sendGate) {
            this.frame = frame; this.input = input; this.log = log;
            this.send = send; this.extend = extend; this.sendGate = sendGate;
        }
    }

    private static volatile Ui ui;

    // How often automationAwaitReady looks again, in milliseconds. Startup is measured
    // in seconds, so there is nothing to gain from looking harder than ten times one.
    private static final long AUTOMATION_POLL_MS = 100;

    // False while the session report is being written, true once it ends - which is
    // the moment the window can be used (see initSession).
    private static volatile boolean sessionReady = false;

    // Held for as long as an automation is driving the window. What it buys is the
    // read-only prompt: the prompt is the automation's workspace between steps, and a
    // line typed into it would be sent as part of the next request.
    private static volatile boolean automating = false;

    // What History was set to before the automation turned it off, so
    // automationEnd can put it back exactly as the user left it.
    //
    // Off for the duration, because a chain sends independent prompts: pass two of the
    // sample chain asks for a file name, and with extend on it would resend every page
    // image pass one attached - paying for the whole document twice to answer a
    // question about a page of text.
    private static boolean automationExtendWas = false;

    // One send, waited for. The latch is made by automationSend before it presses the
    // button and counted down by sendFinished wherever the send path stops; the failure
    // is null when the reply arrived.
    private static volatile java.util.concurrent.CountDownLatch sendLatch;
    private static volatile String sendFailure;

    // Tells whoever is waiting for a send that it is over, with null for "the reply is
    // in the log" and otherwise the reason it is not. Called from every point the send
    // path can stop at - an empty prompt, a stale include, a model JRock cannot call,
    // and the worker's own end - so an automation finds out at once instead of at its
    // timeout.
    //
    // Queued rather than run, even though every caller is already on the EDT: LogView
    // writes each entry through invokeLater, so the lines this send produced - and the
    // message files written with them - are still on the queue when the send path gets
    // here. Going through the queue too puts the signal behind them, which is what
    // lets automationSend read the stamps it came for.
    private static void sendFinished(String failure) {
        java.util.concurrent.CountDownLatch latch = sendLatch;
        if (latch == null) return;      // nobody is automating; a user pressed Send
        SwingUtilities.invokeLater(() -> {
            sendFailure = failure;
            latch.countDown();
        });
    }

    // JRock's own window, for an automation whose dialogs should belong to it rather
    // than float on their own. Null until the window exists.
    public static JFrame automationWindow() {
        Ui live = ui;
        return live == null ? null : live.frame;
    }

    // Enters automation mode: the prompt goes read-only, Send is held between steps,
    // and History is turned off. Returns null, or why it refused.
    //
    // what finishes the sentence "Automation started: ", so the transcript says which
    // automation this was - the log being the only record of it afterwards.
    public static String automationBegin(String what) {
        String problem = offEdt();
        if (problem != null) return problem;
        Ui live = ui;
        if (live == null) return "JRock has no window yet - call main() and wait for it.";
        if (automating) return "an automation is already running.";
        automating = true;
        onEdt(() -> {
            automationExtendWas = live.extend.isSelected();
            live.extend.setSelected(false);
            live.input.setEditable(false);
            live.sendGate.accept(false);
        });
        live.log.gray("Automation started: " + what);
        live.log.gray("The prompt is read-only and Send is held until it finishes; "
                + "\"History\" is off for the duration.");
        live.log.gray("");
        return null;
    }

    // Waits until the window exists and its session report has finished - the model
    // list fetched, "Ready." printed - and then answers the one question an automation
    // has to ask before it converts anything: can this JRock call a model at all?
    // Returns null when it can, and otherwise why not.
    //
    // The key is checked here rather than left to the first send because of the order
    // the work comes in: the first send is after a PDF has been rasterised page by
    // page, which is minutes of Ghostscript spent to find out there was never a
    // credential to send the result with.
    public static String automationAwaitReady(long timeoutMillis) {
        String problem = offEdt();
        if (problem != null) return problem;
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMillis);
        while (ui == null || !sessionReady) {
            if (System.currentTimeMillis() > deadline) {
                return "JRock was not ready within " + timeoutMillis + " ms.";
            }
            try {
                Thread.sleep(AUTOMATION_POLL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return "interrupted while waiting for JRock to be ready.";
            }
        }
        if (resolveApiKey() == null) {
            return "no Bedrock API key is set. Put one in JRock/bedrock-key.txt, or "
                    + "type it into the Configure dialog, and run this again.";
        }
        return null;
    }

    // Loads a prompt file into the prompt area, exactly as Ctrl+O does, and leaves the
    // caret at the end of it so whatever is included next is appended. Returns null,
    // or why that file is not a prompt.
    public static String automationLoadPrompt(String file) {
        String problem = requireAutomation();
        if (problem != null) return problem;
        Ui live = ui;
        Path source = Paths.get(file).toAbsolutePath().normalize();
        String loaded = readFileQuietly(source);
        if (loaded == null) return "could not read " + source;
        // The same guard Ctrl+O applies, and for the same reason: a prompt is text, and
        // an image loaded as one fills the prompt area with rubbish.
        if (looksBinary(loaded)) return source + " does not look like a text file.";
        onEdt(() -> {
            live.input.setText(loaded);      // triggers the autosave, as Ctrl+O does
            live.input.setCaretPosition(live.input.getDocument().getLength());
        });
        live.log.gray("Loaded prompt from (read-only): " + source);
        return null;
    }

    // Removes the bare "@img" / "@txt" / "@audio" lines a sample prompt ends with, leaving
    // the caret at the end of what is left. Returns how many lines it removed.
    //
    // Those lines are placeholders and not tokens - a token carries a 12-hex-digit
    // hash - so left in place they are sent as the two words they are. A person reads
    // them as "the attachments belong here" and presses Ctrl+I on one; a program has to
    // be told, and this is the telling. Call it after automationLoadPrompt and before
    // the includes.
    public static int automationDropPlaceholders() {
        if (requireAutomation() != null) return 0;
        Ui live = ui;
        int[] removed = { 0 };
        onEdt(() -> {
            java.util.List<String> keep = new ArrayList<>();
            for (String line : live.input.getText().split("\n", -1)) {
                String bare = line.trim();
                if (bare.equals("@img") || bare.equals("@txt") || bare.equals("@audio")) {
                    removed[0]++;
                } else {
                    keep.add(line);
                }
            }
            if (removed[0] == 0) return;
            String text = String.join("\n", keep);
            // The tokens are inserted AT the caret, each with its own newline after it,
            // so the caret has to sit at the start of a line - which at the end of the
            // text means the text has to end with one.
            if (!text.endsWith("\n")) text = text + "\n";
            live.input.setText(text);
            live.input.setCaretPosition(live.input.getDocument().getLength());
        });
        if (removed[0] > 0) {
            live.log.gray("Dropped " + removed[0] + " placeholder line(s) from the prompt; "
                    + "the include tokens go at the end of it.");
        }
        return removed[0];
    }

    // Includes one file in the prompt under the kind the include dialog's filters stand
    // for, and returns null - or why nothing was included:
    //
    //   "img"    an image file, as a picture
    //   "imgref" the same, with a Markdown "![](<hash>)" reference above the token
    //   "txt"    a text file, as it is
    //   "audio"  a recording (wav, mp3), as an input_audio part
    //   "pdf"    a PDF, rasterised by Ghostscript into one page image per page
    //   "rtf"    an RTF, converted to Markdown text
    //   "docx"   a DOCX, converted to Markdown text
    //
    // Blocks for as long as the conversion takes, which for a long PDF is minutes.
    //
    // "nothing was included" is a failure here and not a shrug, because the next step
    // is a send: Ghostscript missing, or a PDF it could not read, would otherwise go
    // out as a prompt that asks about a document and attaches none of it.
    public static String automationInclude(String file, String kind) {
        String problem = requireAutomation();
        if (problem != null) return problem;
        Ui live = ui;
        Path path = Paths.get(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return "not a file: " + path;
        int before = promptTokenCount(live);
        try {
            switch (kind) {
                case "pdf":
                    includePdf(live.frame, live.input, live.log, false, path);
                    break;
                case "rtf":
                    includeRtfAsMarkdown(live.input, live.log, false, path);
                    break;
                case "docx":
                    includeDocxAsMarkdown(live.input, live.log, false, path);
                    break;
                case "img":
                case "imgref":
                case "audio":
                case "txt": {
                    boolean image = kind.equals("img") || kind.equals("imgref");
                    onEdt(() -> includeOne(live.input, live.log, false, path,
                            image ? "img" : kind.equals("audio") ? "audio" : "txt",
                            image, kind.equals("imgref")));
                    break;
                }
                default:
                    return "unknown include kind \"" + kind + "\".";
            }
        } catch (RuntimeException ex) {
            return "including " + path + " failed: " + ex;
        }
        live.log.gray("");   // closes the include block, as the dialog's own done() does
        int after = promptTokenCount(live);
        if (after == before) {
            return "nothing was included from " + path + " - the log says why.";
        }
        live.log.gray("Included " + (after - before) + " file(s) from " + path.getFileName()
                + " as \"" + kind + "\".");
        live.log.gray("");
        return null;
    }

    // Sends the prompt as it stands and waits for the reply: the Send button pressed,
    // and the answer logged and written to its own file, exactly as by hand.
    //
    // Returns four strings, in the shape callModel uses for the same reason - a result
    // and a reason travel together:
    //
    //   [0] "1" when the reply arrived, "0" when it did not
    //   [1] the stamp of the request's message file, or null if none was written
    //   [2] the stamp of the reply's message file, or null
    //   [3] why it failed, or null
    //
    // A stamp is what automationMessageFile turns into a path under JRock/messages/.
    // Both are compared with what was there before the send, so a step that logged no
    // message of a kind reports null for it rather than the previous turn's file.
    //
    // A timeout leaves the request in flight - there is nothing here that could recall
    // it - so an automation that times out should stop rather than send again.
    public static String[] automationSend(long timeoutMillis) {
        String problem = requireAutomation();
        if (problem != null) return new String[] { "0", null, null, problem };
        Ui live = ui;
        String[] before = new String[2];
        onEdt(() -> {
            before[0] = live.log.lastStamp(ROLE_HUMAN);
            before[1] = live.log.lastStamp(ROLE_ASSISTANT);
        });

        sendFailure = null;
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        sendLatch = latch;
        String[] refused = { null };
        // The hold automationBegin put on Send comes off for exactly as long as the
        // click takes, and on the EDT, where no user event can land in between. Another
        // hold - a backup running - leaves the button disabled even then, and that is
        // reported rather than waited out.
        onEdt(() -> {
            live.sendGate.accept(true);
            if (live.send.isEnabled()) {
                live.send.doClick();
            } else {
                live.sendGate.accept(false);
                refused[0] = "Send is not available - something else is holding it.";
            }
        });

        String failure = refused[0];
        if (failure == null) {
            try {
                failure = latch.await(Math.max(0, timeoutMillis),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                        ? sendFailure
                        : "no reply within " + timeoutMillis + " ms.";
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failure = "interrupted while waiting for the reply.";
            }
            onEdt(() -> live.sendGate.accept(false));   // held again between the steps
        }
        sendLatch = null;

        String[] after = new String[2];
        onEdt(() -> {
            after[0] = live.log.lastStamp(ROLE_HUMAN);
            after[1] = live.log.lastStamp(ROLE_ASSISTANT);
        });
        return new String[] {
                failure == null ? "1" : "0",
                newStamp(after[0], before[0]),
                newStamp(after[1], before[1]),
                failure };
    }

    // The file one of a send's stamps names, under JRock/messages/, as an absolute path
    // - or null when the stamp is not a stamp, the role is not a role, or the file is
    // not there. role is "operator" for the request and "assistant" for the reply,
    // which is what those files are called.
    //
    // A path and not the text, on purpose: the reply is already a file on disk, written
    // once and never modified, so an automation that wants it can read it - and an
    // automation that only wants to check the request was logged need not. The stamp is
    // validated by parsing it and rebuilding the name from the parsed value, the same
    // guard resolveReference uses, so nothing derived from it can leave that directory.
    public static String automationMessageFile(String role, String stamp) {
        if (stamp == null) return null;
        String want = "assistant".equals(role) ? ROLE_ASSISTANT
                : "operator".equals(role) ? ROLE_HUMAN : null;
        if (want == null) return null;
        try {
            LocalDateTime dt = LocalDateTime.parse(stamp, STAMP_FMT);
            Path file = messageFile(want, dt.format(STAMP_FMT));
            return Files.isRegularFile(file) ? file.toAbsolutePath().toString() : null;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    // Leaves automation mode: the prompt is editable again, Send is released, and
    // History goes back to what the user had it at. note finishes the
    // sentence "Automation finished: ", or is null for the sentence on its own.
    //
    // The window stays open with the whole run in its log, and the conversation can be
    // carried on by hand from where the automation left off - which is the point of
    // driving the real window instead of a headless copy of it.
    public static void automationEnd(String note) {
        Ui live = ui;
        if (live == null || !automating) return;
        onEdt(() -> {
            live.input.setEditable(true);
            live.extend.setSelected(automationExtendWas);
            live.sendGate.accept(true);
        });
        automating = false;
        live.log.gray(note == null ? "Automation finished."
                                  : "Automation finished: " + note);
        live.log.gray("The prompt is editable again - carry on from here.");
        live.log.gray("");
    }

    // Refuses a call made from the event dispatch thread. Every public method above
    // blocks; see the section comment for what that would do on the EDT.
    private static String offEdt() {
        return SwingUtilities.isEventDispatchThread()
                ? "the automation API must not be called on the event dispatch thread."
                : null;
    }

    // The same, plus the two things every step after automationBegin needs: a window
    // to drive, and an automation that has actually been started.
    private static String requireAutomation() {
        String problem = offEdt();
        if (problem != null) return problem;
        if (ui == null) return "JRock has no window yet - call main() and wait for it.";
        if (!automating) return "no automation is running - call automationBegin first.";
        return null;
    }

    // How many include tokens the prompt holds, which is how automationInclude knows
    // whether the conversion it just ran put anything there.
    private static int promptTokenCount(Ui live) {
        int[] count = { 0 };
        onEdt(() -> {
            java.util.regex.Matcher m = INCLUDE_TOKEN.matcher(live.input.getText());
            while (m.find()) count[0]++;
        });
        return count[0];
    }

    // The stamp a send just produced for one role: the one the log holds now, unless it
    // is the stamp that was already there - in which case this send wrote no message of
    // that role, and there is no file to point an automation at.
    private static String newStamp(String now, String before) {
        return (now == null || now.equals(before)) ? null : now;
    }

    // ---- Clipboard ---------------------------------------------------------
    // On a normal JVM Swing's clipboard IS the OS clipboard and the default
    // cut/copy/paste need no help. In the browser they are two different things,
    // so everything here routes through the page instead (see the clipboard
    // bridge). Calls happen straight from the menu item or keystroke, while the
    // browser still counts the tap or key press as a user gesture - the clipboard
    // APIs refuse to run outside one.

    // Splits a bridge reply of "<ok>\n<rest>" into { ok, rest }.
    private static String[] bridgeReply(String reply) {
        if (reply == null) return new String[] { "0", "the bridge returned nothing" };
        int nl = reply.indexOf('\n');
        return nl < 0 ? new String[] { reply.trim(), "" }
                      : new String[] { reply.substring(0, nl).trim(), reply.substring(nl + 1) };
    }

    // Where a clipboard note goes, when there is anywhere for it to go: log is null for
    // a field in a dialog that has no log to reach - the Configure dialog's API key
    // field is one - and a note nobody can read is not worth an exception, the paste
    // itself having happened either way.
    private static void clipboardNote(LogView log, String text) {
        if (log != null) log.gray(text);
    }

    // Copies to BOTH clipboards: the browser's is the one other apps read, and
    // Swing's keeps paste working inside JRock even where the browser blocks reads.
    private static void clipboardCopy(String text, LogView log) {
        if (text == null || text.isEmpty()) return;
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Throwable ignored) {
            // No usable JVM clipboard; the page's is the one that matters here.
        }
        if (!isCheerpJ()) return;
        try {
            String[] r = bridgeReply(browserClipboardWrite(text));
            if (!"1".equals(r[0])) {
                clipboardNote(log,
                        "Copied inside JRock only - the browser refused the clipboard: " + r[1]);
            }
        } catch (Throwable ex) {
            clipboardNote(log,
                    "Copied inside JRock only - no clipboard bridge on this page: " + ex);
        }
    }

    private static void clipboardCut(javax.swing.text.JTextComponent comp, LogView log) {
        String sel = comp.getSelectedText();
        if (sel == null || sel.isEmpty()) return;
        clipboardCopy(sel, log);      // always reaches Swing's, so the text is never lost
        comp.replaceSelection("");
    }

    private static void clipboardPaste(javax.swing.text.JTextComponent comp, LogView log) {
        if (!isCheerpJ()) { comp.paste(); return; }
        try {
            String[] r = bridgeReply(browserClipboardRead());
            if ("1".equals(r[0])) {
                comp.replaceSelection(r[1]);   // "" is a genuinely empty clipboard
                return;
            }
            clipboardNote(log, "Could not read the browser clipboard (" + r[1]
                    + "). Pasting what was last copied inside JRock instead.");
        } catch (Throwable ex) {
            clipboardNote(log, "No clipboard bridge on this page (" + ex
                    + "). Pasting what was last copied inside JRock instead.");
        }
        comp.paste();
    }

    // ---- Hosting page chrome (browser only) --------------------------------
    // The page hides its header (title, checksum, credentials button) and its
    // footer (the page's own log) a few seconds after launch, so the Swing display
    // gets the whole tab. Sometimes you want them back - to check the checksum, or
    // to change the API key the page holds - and then gone again.
    //
    // This is a proxy and nothing more: the page has one function that flips the
    // chrome and reports which way it went, so there is no second copy of the
    // state here to fall out of step with the one that matters.
    private static void toggleBrowserChrome(LogView log) {
        try {
            String[] r = bridgeReply(browserToggleChrome());
            if ("1".equals(r[0])) {
                log.gray("Page header and footer: " + r[1] + ".");
            } else {
                log.gray("Could not toggle the page header and footer: " + r[1]);
            }
        } catch (Throwable ex) {
            log.gray("This page provides no header/footer bridge (" + ex + ").");
        }
    }

    // Points a component's own cut/copy/paste actions at the page's clipboard, so
    // Ctrl+C/X/V behave like the menu items. Only needed in the browser; elsewhere
    // the defaults already talk to the OS. editable=false wires copy alone.
    private static void useBrowserClipboard(javax.swing.text.JTextComponent comp,
                                            LogView log, boolean editable) {
        if (!isCheerpJ()) return;
        comp.getActionMap().put(javax.swing.text.DefaultEditorKit.copyAction,
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) {
                        clipboardCopy(comp.getSelectedText(), log);
                    }
                });
        if (!editable) return;
        comp.getActionMap().put(javax.swing.text.DefaultEditorKit.cutAction,
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) { clipboardCut(comp, log); }
                });
        comp.getActionMap().put(javax.swing.text.DefaultEditorKit.pasteAction,
                new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) { clipboardPaste(comp, log); }
                });
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
            private long pressedAt;
            private boolean shown;

            private void showAt(int x, int y) { shown = true; menu.show(comp, x, y); }

            private void cancel() {
                if (timer != null) { timer.stop(); timer = null; }
                origin = null;
            }

            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                shown = false;
                if (e.isPopupTrigger()) { showAt(e.getX(), e.getY()); return; }   // desktop right-click
                // Otherwise arm a long-press timer for touch/left-press.
                cancel();
                origin = e.getPoint();
                pressedAt = System.currentTimeMillis();
                final int x = e.getX(), y = e.getY();
                timer = new javax.swing.Timer(LONG_PRESS_MS, ev -> { cancel(); showAt(x, y); });
                timer.setRepeats(false);
                timer.start();
            }

            // A long press is measured on release as well as by the timer, so the menu
            // still opens where a Swing Timer never fires: a modal dialog runs the event
            // queue in a nested loop, and in the browser runtime the timers queued behind
            // it stay queued - which is exactly why Paste could not be reached in the
            // Fetch URL and Configure dialogs, the two places it is needed most. Mouse
            // events are delivered there (the field takes the tap and the keyboard comes
            // up), so the clock is read from the press instead of trusted to fire.
            @Override public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) { cancel(); showAt(e.getX(), e.getY()); return; }
                boolean held = !shown && origin != null
                        && System.currentTimeMillis() - pressedAt >= LONG_PRESS_MS;
                cancel();   // released before the threshold: normal click, no menu
                if (held) showAt(e.getX(), e.getY());
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
    // Shows working directory, prompts & agents directory, API key (write-only
    // override), region and model.
    // Returns true if the user applied changes (so the caller re-inits the session).
    private static boolean showConfigureDialog(JFrame frame) {
        // Named, because these two rows are otherwise indistinguishable from each
        // other: same widget, and on a plain launch the same text as well.
        javax.swing.JTextField cwdF = new javax.swing.JTextField(workingDir.toString(), 30);
        cwdF.setName("workingDir");
        javax.swing.JPanel cwdRow = dirRow(frame, cwdF, "Choose working directory");

        // The prompts & agents directory, shown as the path actually in effect rather
        // than as an empty field meaning "wherever the working directory is": the user
        // is being asked where their prompts live, and the honest answer is a path.
        // Which is why what happens on OK depends on whether this was EDITED, not on
        // what it contains - see the apply block.
        //
        // Called "prompts & agents" because both live here: the prompt files, and the
        // .java automations that drive JRock through them (Install agent browses this
        // directory). The field name stays "promptsDir" - it is what the tests address
        // this row by, and what the flag is called.
        String promptsShown = promptsDir().toString();
        javax.swing.JTextField promptsF = new javax.swing.JTextField(promptsShown, 30);
        promptsF.setName("promptsDir");
        promptsF.setToolTipText("Where Load prompt (Ctrl+O), Save prompt copy (Ctrl+S) "
                + "and Install agent open. Set it to the working directory to have "
                + "prompts and agents follow that instead.");
        javax.swing.JPanel promptsRow =
                dirRow(frame, promptsF, "Choose prompts & agents directory");

        // The key row: masked on the desktop, and a PLAIN field in the browser. On a
        // phone CheerpJ raises the on-screen keyboard for the region field next to it
        // and not for this one, and the echo character is the only difference between
        // the two - so the mask is what makes the row impossible to fill in, and a row
        // you cannot fill in is worse than one whose text can be read over your
        // shoulder. It opens blank, is never prefilled, is never written back into the
        // dialog, and is gone when the dialog closes.
        javax.swing.text.JTextComponent keyF = isCheerpJ()
                ? new javax.swing.JTextField(24)
                : new javax.swing.JPasswordField(24);
        // An API key is never typed from memory: it is pasted from wherever it was
        // issued. Paste alone - a key that goes in does not need to come back out, and
        // Select all has nothing to select in a field that opens empty. No log here: a
        // modal dialog has none to write to (see addPasteMenu).
        addPasteMenu(keyF, null);
        javax.swing.JTextField regionF = new javax.swing.JTextField(REGION, 16);
        // Copy, Paste and Select all on every row a value is put into, because on a
        // phone there is no Ctrl+V - and Select all because a field that already holds a
        // region, a model or a path is a field whose contents are in the way of the one
        // being pasted over it (see addCopyPasteMenu). No log, as above.
        addCopyPasteMenu(cwdF, null);
        addCopyPasteMenu(promptsF, null);
        addCopyPasteMenu(regionF, null);
        // Editable combo: free text, plus a dropdown of the most recently fetched
        // available models (empty until the first successful /v1/models call).
        javax.swing.JComboBox<String> modelF =
                new javax.swing.JComboBox<>(availableModels.toArray(new String[0]));
        modelF.setEditable(true);
        modelF.setSelectedItem(MODEL_ID);   // shows current value; typeable
        modelF.setFont(modelF.getFont().deriveFont(java.awt.Font.PLAIN));  // not bold
        modelF.getEditor().getEditorComponent()
                .setFont(modelF.getFont().deriveFont(java.awt.Font.PLAIN));
        // The menu goes on the combo's EDITOR and not on the combo: the editor is the
        // text component, it is what a tap lands on, and it is what a paste has to reach.
        java.awt.Component modelEditor = modelF.getEditor().getEditorComponent();
        if (modelEditor instanceof javax.swing.text.JTextComponent) {
            addCopyPasteMenu((javax.swing.text.JTextComponent) modelEditor, null);
        }

        // Image resolution: a fixed list, so not editable - unlike the model, an
        // arbitrary number here has no meaning worth supporting.
        javax.swing.JComboBox<Integer> dpiF = new javax.swing.JComboBox<>();
        for (int dpi : IMAGES_DPI_OPTIONS) dpiF.addItem(dpi);
        dpiF.setSelectedItem(imagesDpi);
        dpiF.setFont(dpiF.getFont().deriveFont(java.awt.Font.PLAIN));
        dpiF.setToolTipText("How fine a picture to keep, per inch of page (72/96 "
                + "screen, 150 documents, 203 fax/receipt, 300 print). PDF pages are "
                + "rasterised at it, and \"Include with copy...\" downscales an image "
                + "to it. Higher is sharper but costs more tokens.");
        // Autobackup, on a line of its own. It used to share the DPI row, which made two
        // unrelated settings look like one thing: how fine a picture to keep has nothing
        // to do with zipping the folder up. The checkbox says what it is in its own label,
        // so the left column of its row stays empty rather than repeating it.
        javax.swing.JCheckBox autoBackupF =
                new javax.swing.JCheckBox("Autobackup log", autoBackupLog);
        autoBackupF.setFont(autoBackupF.getFont().deriveFont(java.awt.Font.PLAIN));
        autoBackupF.setToolTipText("Zip the JRock folder into a jrock-backup-....zip in "
                + "the working directory after " + IDLE_BACKUP_MINUTES + " minutes "
                + "without the cursor moving in the prompt");

        // In a wrapper so the layout's horizontal fill doesn't stretch a
        // three-digit dropdown across the whole dialog.
        javax.swing.JPanel dpiRow = new javax.swing.JPanel(new BorderLayout(12, 0));
        dpiRow.add(dpiF, BorderLayout.WEST);

        javax.swing.JPanel fields = new javax.swing.JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(fields, c, row++, "Working directory:", cwdRow);
        addRow(fields, c, row++, "Prompts & agents:", promptsRow);
        // In the browser the row carries a Paste button of its own - see pasteRow.
        addRow(fields, c, row++, "Bedrock API key:",
                isCheerpJ() ? pasteRow(keyF, "key") : keyF);
        addRow(fields, c, row++, "AWS region:", regionF);
        addRow(fields, c, row++, "Model:", modelF);
        addRow(fields, c, row++, "Images DPI:", dpiRow);
        addRow(fields, c, row++, "", autoBackupF);

        java.awt.Font plainFont = plainLabelFont();

        // First line: which JRock this is, and a Help button at the other end of it.
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

        // What JRock is, the shortcut list and the notes on these settings are all behind
        // this button now - see showHelpDialog. This dialog is a form: someone opening it
        // to change the model was being handed three screens of prose to scroll past
        // first, and the prose was no easier to find for being in the way.
        JButton help = new JButton("Help");
        help.setToolTipText("What JRock is, who wrote it, every shortcut, and what "
                + "these settings do");
        help.addActionListener(e -> showHelpDialog(help));

        javax.swing.JPanel titleLine = new javax.swing.JPanel(new BorderLayout(8, 0));
        titleLine.add(about, BorderLayout.WEST);
        titleLine.add(help, BorderLayout.EAST);

        // Second line: who made it, including the tools that helped make it.
        javax.swing.JLabel credits = new javax.swing.JLabel(
                "By Ivan Khvostishkov, with assistance of Kiro, Claude and "
                + "JetBrains IntelliJ IDEA.");
        credits.setFont(plainFont);

        javax.swing.JPanel aboutBox = new javax.swing.JPanel(new BorderLayout(0, 4));
        aboutBox.add(titleLine, BorderLayout.NORTH);
        aboutBox.add(credits, BorderLayout.CENTER);
        aboutBox.setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 2, 8, 2));

        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 8));
        panel.add(aboutBox, BorderLayout.NORTH);
        panel.add(fields, BorderLayout.CENTER);

        int result = javax.swing.JOptionPane.showConfirmDialog(
                frame, panel, "Configure JRock",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (result != javax.swing.JOptionPane.OK_OPTION) return false;

        // The folder whose settings this dialog was showing, so the apply below can
        // tell "the settings of this folder changed" from "we are moving to another
        // folder, which has settings of its own".
        Path dirAtOpen = workingDir;

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

        // Prompts & agents directory. Applied only when the field was EDITED, which is
        // what leaves "prompts follow the working directory" intact for someone who
        // opened this dialog to change the working directory and nothing else: the
        // field showed them the old working directory, and taking that at face value
        // would silently pin prompts to a folder they just moved away from.
        //
        // Deliberately after the working directory above, so "same as the working
        // directory" is judged against the new one.
        String promptsText = promptsF.getText().trim();
        if (!promptsText.equals(promptsShown)) {
            applyPromptsDir(frame, promptsText);
        }

        // API key: only set if the user typed something - an empty field means "keep
        // whatever is already in effect".
        //
        // Written to the working folder's key file, which is the one place a key is
        // ever written: the folder it was typed for is the folder that keeps it (see
        // "Settings files").
        //
        // Trimmed, because a key that was pasted arrives with whatever the page copied
        // around it, and no Bedrock key has a space or a newline in it.
        char[] typed = (keyF instanceof javax.swing.JPasswordField)
                ? ((javax.swing.JPasswordField) keyF).getPassword()
                : keyF.getText().toCharArray();
        String key = new String(typed).trim();
        java.util.Arrays.fill(typed, '\0');   // wipe the transient char[]
        if (!key.isEmpty()) {
            apiKey = key;
            saveKeyQuietly(apiKey);
        }

        // Region + model (free text).
        String r = regionF.getText().trim();
        if (!r.isEmpty()) { REGION = r; regionSource = null; }
        Object selected = modelF.getEditor().getItem();  // typed or picked value
        String m = (selected == null ? "" : selected.toString().trim());
        if (!m.isEmpty()) { MODEL_ID = m; }

        // Image resolution: picked from the list, so always valid.
        Object dpi = dpiF.getSelectedItem();
        if (dpi instanceof Integer) { imagesDpi = (Integer) dpi; }

        // Autobackup: the idle timer reads this when it next fires, so a change here
        // applies to the spell of inactivity that starts the moment this dialog closes.
        autoBackupLog = autoBackupF.isSelected();

        // The settings of the folder being worked in, so the next start comes up the
        // way this dialog was left.
        //
        // Not written when the working directory just changed: the folder being moved
        // to has settings of its own, which initSession is about to adopt (see
        // adoptSettingsOfWorkingDir), and writing first would overwrite them with the
        // values this dialog was showing for the folder being left.
        if (workingDir.equals(dirAtOpen)) saveConfigQuietly();

        return true;
    }

    // A directory field with its own "Browse..." button, which opens on whatever the
    // field currently holds. Two rows in the Configure dialog are exactly this, and
    // wiring a chooser by hand twice is how two rows end up behaving differently.
    private static javax.swing.JPanel dirRow(JFrame frame, javax.swing.JTextField field,
                                             String chooserTitle) {
        JButton browse = new JButton("Browse...");
        browse.addActionListener(ev -> {
            javax.swing.JFileChooser dc = new javax.swing.JFileChooser(field.getText().trim());
            dc.setDialogTitle(chooserTitle);
            dc.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
            if (dc.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION
                    && dc.getSelectedFile() != null) {
                field.setText(dc.getSelectedFile().getAbsolutePath());
            }
        });
        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    // A field with a "Paste" button beside it, the way dirRow gives one a "Browse..."
    // button. For the browser only: the API key row, and the URL the Fetch URL dialog
    // asks for. what names the thing in the tooltip ("key", "address").
    //
    // The long-press menu (addPasteMenu) is already on those fields, and on a phone a
    // long press is a gesture competing with the browser's own - the iOS text callout,
    // the drag-and-drop pick-up - so it is not something to depend on for a row that has
    // no other way in. A directory row has Browse..., a region and a model can be typed
    // once the keyboard is up; a key can only be pasted, and an address practically
    // always is. A button is one tap, and it is visible, which a gesture is not.
    private static javax.swing.JPanel pasteRow(javax.swing.text.JTextComponent field,
                                               String what) {
        JButton paste = new JButton("Paste");
        paste.setToolTipText("Paste the " + what + " from the browser's clipboard");
        paste.addActionListener(ev -> {
            clipboardPaste(field, null);        // no log to write to in a modal dialog
            field.requestFocusInWindow();
        });
        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(paste, BorderLayout.EAST);
        return panel;
    }

    // Adds a "label: field" row to a GridBagLayout panel.
    private static void addRow(javax.swing.JPanel p, java.awt.GridBagConstraints c,
                               int row, String label, javax.swing.JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        p.add(new javax.swing.JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        p.add(field, c);
    }

    // ---- Help dialog (the Help button in Configure) -------------------------
    // What JRock is, who to write to, every shortcut, and what the settings do. All of
    // it used to be in the Configure dialog, above and below the rows it is about; it
    // opens on top of that dialog instead, so the form is a form and nothing typed into
    // it is lost while this is being read.
    private static final String CONTACT_EMAIL = "jrock@nosocial.net";

    private static void showHelpDialog(java.awt.Component parent) {
        java.awt.Font plainFont = plainLabelFont();

        // Sized against the screen rather than against a desktop window, for the reason
        // spelled out in showFetchUrlDialog: a dialog wider or taller than the screen is
        // one whose button is off the edge of it. Everything here scrolls, so a phone
        // gets a short window it can scroll and a desktop gets the whole thing at once.
        java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        int contentW = Math.max(240, Math.min(520, screen.width - 140));
        int contentH = Math.max(240, Math.min(540, screen.height - 220));

        javax.swing.JTextArea about = wrapped(
            "The Amazon Bedrock desktop GUI client in Java that just works: every prompt "
          + "and session is saved to disk so nothing is ever lost, and your credentials "
          + "stay put with no repeated sign-ins - so it keeps out of your way and lets "
          + "you focus on the models.\n\n"
          + "Questions, bugs and wishes: " + CONTACT_EMAIL + "\n"
          + GITHUB_URL, plainFont, contentW);

        javax.swing.JTextArea notes = wrapped(configNotes(), plainFont, contentW);
        notes.setBorder(javax.swing.BorderFactory.createTitledBorder("Notes"));

        javax.swing.JPanel content = new javax.swing.JPanel();
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.add(about);
        content.add(javax.swing.Box.createVerticalStrut(10));
        content.add(shortcutsPanel(plainFont, (int) (contentW * 0.62) - 16));
        content.add(javax.swing.Box.createVerticalStrut(10));
        content.add(notes);

        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(content);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(
                javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setPreferredSize(new java.awt.Dimension(contentW + 28, contentH));

        // parent is the Help button, so this comes up centred on the Configure dialog
        // and modal above it - not behind it, which a dialog owned by the frame would be.
        javax.swing.JOptionPane.showMessageDialog(parent, scroll, "JRock Help",
                javax.swing.JOptionPane.PLAIN_MESSAGE);
    }

    // A plain (non-bold) font derived from the default label font, for the prose, the
    // shortcut rows and the titled-border titles, so none of it renders bold.
    private static java.awt.Font plainLabelFont() {
        java.awt.Font base = javax.swing.UIManager.getFont("Label.font");
        return (base != null)
                ? base.deriveFont(java.awt.Font.PLAIN)
                : new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
    }

    // A read-only text area that wraps at about the given width in pixels.
    //
    // The columns are worked back from the width because that is what a wrapping text
    // area answers getPreferredSize with - a column being the width of an 'm' in its
    // font, which is JTextArea's own definition (getColumnWidth). Left to itself it asks
    // for one line as wide as its longest paragraph, and the dialog holding it comes out
    // wider than the screen.
    private static javax.swing.JTextArea wrapped(String text, java.awt.Font font, int width) {
        javax.swing.JTextArea area = new javax.swing.JTextArea(text);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(font);
        int em = Math.max(1, area.getFontMetrics(font).charWidth('m'));
        area.setColumns(Math.max(20, width / em));
        return area;
    }

    // What the Configure dialog's rows mean, and where what they set is kept. One text
    // for every runtime, because there is one behaviour: the browser build holds the key
    // in JRock's own settings exactly as the desktop does.
    private static String configNotes() {
        String keyNote =
              "The API key field is intentionally blank and write-only: leave it empty "
            + "to keep the current key; type or paste a value to replace it. What you "
            + "enter is written to JRock/bedrock-key.txt in the working directory, which "
            + "is where it is read from at startup. The stored key is never shown here, "
            + "and what you enter is trimmed - a pasted key brings the page's spaces "
            + "and newlines with it.\n\n"
            + "Every row in Configure has a right-click - or, on a touch screen, a "
            + "long-press - menu with Copy, Paste and Select all; the API key row has "
            + "Paste alone. "
            + (isCheerpJ()
               ? "In the browser that row is also the one field left unmasked, and it has "
               + "a Paste button of its own: CheerpJ brings up no on-screen keyboard for "
               + "a masked field, so on a phone there would otherwise be no way to fill "
               + "it in at all.\n\n"
               : "\n\n")
            + "The region, the model and the images DPI are kept in "
            + "JRock/jrock-config.txt beside it, so they survive a restart. Both files "
            + "belong to the working directory and are plain text you can edit "
            + "yourself - which is what lets one folder's agent run on a different "
            + "model, or at a different DPI, than another's.\n\n";

        return keyNote
            + "Clearing the log only clears jrock-log.txt (and the window); the "
            + "per-message files in JRock/messages/ are never deleted, so your inputs "
            + "and outputs are preserved.\n\n"
            + "The prompts & agents directory is where Load prompt (Ctrl+O) and Save "
            + "prompt copy (Ctrl+S) always open - they don't drift to wherever you last "
            + "browsed - and where Install agent looks for the .java automations that "
            + "drive JRock. Set it to the working directory to have both simply follow "
            + "that. It overrides " + PROMPTS_DIR_FLAG + " for this session, and "
            + "installing the Explorer entries writes whichever directory is in effect "
            + "then.\n\n"
            + "Note: a true OS process chdir isn't possible from Java, so changing the "
            + "working directory reroutes JRock's own files (a JRock/ subfolder holding "
            + "the prompt, log and messages/) to the new directory rather than changing "
            + "the OS-level CWD of the process.";
    }

    // The shortcut list, for the Help dialog. descWidth is how much room the second
    // column has in pixels: the descriptions are wrapped to it, because on a phone the
    // widest of them ("Include a text, image, audio, PDF, RTF or DOCX file") is wider
    // than the whole screen, and a grid in a window that does not scroll sideways would
    // simply have its right-hand end cut off.
    private static javax.swing.JPanel shortcutsPanel(java.awt.Font plainFont, int descWidth) {
        // A 2-column grid so keys and descriptions align cleanly (no
        // space-padding). The "Shortcuts" border title keeps the default bold.
        String[][] keys = {
            {"Ctrl+Enter", "Send message (call a Bedrock model)"},
            {"Ctrl+I", "Include a text, image, audio, PDF, RTF or DOCX file"},
            {"Ctrl+Shift+I", "Include it with a copy kept under JRock/includes/"},
            {"Ctrl+U", "Fetch a URL and include what it answers with"},
            {"Ctrl+D", "Toggle Dialog only"},
            {"Ctrl+E", "Toggle History (send the prior dialog too)"},
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
            javax.swing.JLabel descLbl = new javax.swing.JLabel(
                    "<html><body style='width:" + descWidth + "px'>" + keys[r][1]
                    + "</body></html>");
            descLbl.setFont(plainFont);
            // Golden-ratio-ish column weights: key column narrow (~38%), desc wide.
            sc.gridx = 0; sc.gridy = r; sc.weightx = 0.38;
            shortcuts.add(keyLbl, sc);
            sc.gridx = 1; sc.weightx = 0.62;
            shortcuts.add(descLbl, sc);
        }
        return shortcuts;
    }

    // ---- Save / load prompt (Ctrl+S / Ctrl+O) ------------------------------
    // Saves a copy of the given text to a user-chosen file. Does NOT touch the
    // persistent jrock-prompt.txt; this is an extra export.
    private static void savePromptAs(JFrame frame, String text) {
        // The prompts directory, every time - see loadPromptInto for why neither of
        // these two remembers where it was last.
        Path prompts = promptsDir();
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser(prompts.toFile());
        chooser.setDialogTitle("Save prompt copy as");
        chooser.setSelectedFile(new java.io.File(prompts.toFile(), "jrock-prompt-copy.txt"));
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

    // ---- Backup & restore (window menu) ------------------------------------
    // Everything JRock keeps lives under JRock/ in the working directory: the log, every
    // message as its own file, the prompt, the includes and the copies converted from
    // them. So a backup is that one folder in a zip and a restore is that zip back over
    // it - there is no format to define and no state kept anywhere else to be missed.
    //
    // The archive holds the folder itself ("JRock/..." entries), not its contents loose.
    // That makes it unambiguous what a restore unpacks, and lets one be told apart from
    // any other zip somebody might pick in the dialog (see unpackBackup).

    // yymmddhhmm: short, sorts chronologically, and distinct for any two backups a
    // minute apart. Two in the same minute are one file, which is the right answer to
    // "I clicked it twice".
    private static final DateTimeFormatter BACKUP_STAMP_FMT =
            DateTimeFormatter.ofPattern("yyMMddHHmm");

    // The top-level folder inside a backup zip, which is also the folder it restores.
    private static final String BACKUP_ROOT = "JRock";

    // The name the Backup dialog offers, and the one an automatic backup takes.
    private static String backupFileName() {
        return "jrock-backup-" + LocalDateTime.now().format(BACKUP_STAMP_FMT) + ".zip";
    }

    // Packs the whole JRock folder into a zip. Two callers, one difference: `ask` true is
    // the menu item, which offers a chooser starting where Save log copy does; `ask`
    // false is "Autobackup log", which takes that same directory and the default name
    // without a word - it fires when nobody is at the keyboard, and a modal dialog with
    // nobody there to answer it would leave the window blocked instead of backed up.
    //
    // Send is held for the duration through sendGate, because the folder being zipped is
    // the folder a request writes its message files into.
    //
    // No lock is taken on the folder, and none would do the job. JRock's own writes are
    // already serialized (WRITE_LOCK in atomicWriteQuietly) but they happen on the EDT,
    // so a backup that held that lock for the length of a zip would stall the next
    // keystroke instead of the next write. An OS file lock would say nothing to the
    // Explorer copy, the editor or the sync client that can be in that folder too, and
    // the browser build's filesystem has no such lock to take. So a backup promises what
    // it can: every file that was there and readable while it ran, with whatever was
    // caught mid-write skipped and counted rather than failing the backup - see
    // writeBackup.
    private static void backupLog(JFrame frame, LogView log,
                                  java.util.function.Consumer<Boolean> sendGate,
                                  boolean ask) {
        if (!Files.isDirectory(jrockDir())) {
            backupNote(frame, log, ask, "There is no " + jrockDir()
                    + " folder yet - nothing to back up.",
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Path target;
        if (ask) {
            javax.swing.JFileChooser chooser =
                    new javax.swing.JFileChooser(logChooserDir.start());
            chooser.setDialogTitle("Backup the JRock folder as");
            chooser.setSelectedFile(logChooserDir.startFile(backupFileName()));
            // Zip and nothing else: a backup is written as a zip whatever the name says
            // (see withExtension below), so offering "All files" would only invite a name
            // that ends in something the file is not.
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Zip archives (*.zip)", "zip"));
            if (chooser.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
            logChooserDir.remember(chooser);
            // The dialog says .zip; a name typed without it gets it anyway.
            target = withExtension(chooser.getSelectedFile().toPath(), "zip");
        } else {
            target = logChooserDir.startFile(backupFileName()).toPath();
        }
        final Path zip = target.toAbsolutePath().normalize();

        // A backup written inside the folder it packs would be packing itself, half
        // finished. Worth one check: the chooser opens in the working directory, which
        // is one double-click away from JRock/.
        if (zip.startsWith(jrockDir().toAbsolutePath().normalize())) {
            backupNote(frame, log, ask,
                    "A backup cannot be written inside the folder it packs.\nChoose a "
                    + "place outside " + jrockDir() + ".",
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            return;
        }

        log.gray("Backing up " + jrockDir() + " to " + zip + " ...");
        sendGate.accept(false);
        new SwingWorker<String, Void>() {
            private long files, bytes, skipped;

            // Returns null when it went well, or the line to log when it did not.
            @Override
            protected String doInBackground() {
                try {
                    long[] counts = writeBackup(zip);
                    files = counts[0];
                    bytes = counts[1];
                    skipped = counts[2];
                    return null;
                } catch (IOException ex) {
                    return "Could not write the backup " + zip + ": " + ex;
                }
            }

            @Override
            protected void done() {
                String failure;
                try {
                    failure = get();
                } catch (Exception ex) {
                    failure = "Could not write the backup " + zip + ": " + ex;
                }
                if (failure == null) {
                    log.gray("Backed up " + files + " file(s), " + fmtNum(bytes)
                            + " bytes, to " + zip);
                    if (skipped > 0) {
                        log.gray(skipped + " file(s) were being written while the backup "
                                + "ran and were left out of it. Nothing is missing from "
                                + "the zip because of it: a file JRock replaces is in "
                                + "there either as it was before the write or as it is "
                                + "after it.");
                    }
                    log.gray("");
                } else {
                    backupNote(frame, log, ask, failure,
                            javax.swing.JOptionPane.WARNING_MESSAGE);
                }
                sendGate.accept(true);
            }
        }.execute();
    }

    // Says the same thing twice over, once in the log and once in a dialog - but only
    // when a person asked for this backup. An automatic one says it in the log alone:
    // nobody is there to click OK, and a dialog left standing would sit on top of the
    // window until they came back.
    private static void backupNote(JFrame frame, LogView log, boolean ask,
                                   String message, int messageType) {
        log.gray(message);
        log.gray("");
        if (ask) {
            javax.swing.JOptionPane.showMessageDialog(frame, message, "Backup log", messageType);
        }
    }

    // Writes the JRock folder into a zip, every entry named "JRock/..." with the folder
    // itself as the root. Returns {files, bytes, skipped} for the log line.
    //
    // Directories get entries of their own, so one that happens to be empty survives the
    // round trip: an empty includes/ is still part of the layout.
    //
    // A backup runs while JRock runs, so the folder changes under it. The prompt
    // autosaves on every keystroke and every autosave writes a jrock<digits>.tmp beside
    // the file and moves it into place (atomicWriteQuietly), which is a name that exists
    // for a matter of milliseconds. A backup that listed one of those and then read it
    // failed outright with "NoSuchFileException: .../JRock/jrock1234....tmp" - the whole
    // zip lost to a file that was never worth having in it. Three things keep that from
    // happening:
    //
    //   - JRock's own in-flight temp files are not backed up at all (see inFlightWrite).
    //     The file one of them is about to become is in the zip anyway, either as it was
    //     before the move or as it is after it;
    //   - every other file is OPENED BEFORE its entry is created, and one that has gone
    //     or cannot be read by then is counted and skipped instead of ending the backup.
    //     Opening first is also what stops a half-written entry: the copy reads from the
    //     stream it already holds, not from a name somebody else may be replacing;
    //   - the walk is retried, because listing the folder is a read of it too.
    private static long[] writeBackup(Path zip) throws IOException {
        Path root = jrockDir().toAbsolutePath().normalize();
        Path parent = zip.getParent();
        if (parent != null) Files.createDirectories(parent);

        java.util.List<Path> paths = walkForBackup(root);

        long files = 0, bytes = 0, skipped = 0;
        byte[] buffer = new byte[8192];
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(
                Files.newOutputStream(zip))) {
            for (Path path : paths) {
                String name = BACKUP_ROOT + "/" + zipName(root.relativize(path));
                if (Files.isDirectory(path)) {
                    out.putNextEntry(new java.util.zip.ZipEntry(
                            name.endsWith("/") ? name : name + "/"));
                    out.closeEntry();
                    continue;
                }
                // Anything that is not a plain file, and anything JRock is in the middle
                // of writing, is skipped rather than guessed at.
                if (!Files.isRegularFile(path) || inFlightWrite(path)) continue;
                java.io.InputStream in;
                try {
                    in = Files.newInputStream(path);
                } catch (IOException gone) {
                    // Moved away, deleted or briefly locked between the walk and now.
                    // Counted, said in the log, and not the end of the backup.
                    skipped++;
                    continue;
                }
                try (java.io.InputStream stream = in) {
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
                    java.nio.file.attribute.FileTime when = modifiedOrNull(path);
                    // An entry left without one gets the time the zip was written, which
                    // is the closest thing to the truth there is for a file whose
                    // timestamp could not be read.
                    if (when != null) entry.setLastModifiedTime(when);
                    out.putNextEntry(entry);
                    int read;
                    while ((read = stream.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        bytes += read;
                    }
                    out.closeEntry();
                    files++;
                }
            }
        }
        return new long[] { files, bytes, skipped };
    }

    // The files and folders to pack, sorted - retried, because a walk stats what it
    // lists, and a temp file that was there when the directory was read and gone when it
    // was stat'ed ends the whole walk with an UncheckedIOException. Which is as transient
    // as the name that caused it: the next walk does not see it at all.
    private static final int BACKUP_WALK_ATTEMPTS = 3;
    private static final long BACKUP_WALK_RETRY_MS = 60;

    private static java.util.List<Path> walkForBackup(Path root) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= BACKUP_WALK_ATTEMPTS; attempt++) {
            try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
                return walk.sorted().collect(java.util.stream.Collectors.toList());
            } catch (java.io.UncheckedIOException ex) {
                last = ex.getCause();
                if (attempt < BACKUP_WALK_ATTEMPTS) {
                    try { Thread.sleep(BACKUP_WALK_RETRY_MS); }
                    catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw (last != null) ? last
                : new IOException("could not list " + root);
    }

    // JRock caught in the act of replacing a file: atomicWriteQuietly writes
    // jrock<digits>.tmp beside the prompt, the log, the config or the key file before
    // moving it into place, and a scaled include copy is written as scaling-<digits>.<ext>
    // before it gets its real name. Both patterns are Files.createTempFile's, digits and
    // all, so nothing a person named is going to match one - and neither is worth backing
    // up: what they become is already in the zip, and what they are is half a file.
    private static final java.util.regex.Pattern IN_FLIGHT_WRITE =
            java.util.regex.Pattern.compile(
                    "(?i)^(jrock[0-9]+\\.tmp|scaling-[0-9]+\\.[a-z0-9]+)$");

    private static boolean inFlightWrite(Path path) {
        Path name = path.getFileName();
        return name != null && IN_FLIGHT_WRITE.matcher(name.toString()).matches();
    }

    // A file's timestamp, or null when it no longer has one to read.
    private static java.nio.file.attribute.FileTime modifiedOrNull(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (IOException ex) {
            return null;
        }
    }

    // A relative path as a zip entry spells it: forward slashes whatever the platform
    // separator is, which is what the format says and what every other tool reading the
    // archive expects. The folder's own (empty) relative path becomes "".
    private static String zipName(Path relative) {
        StringBuilder name = new StringBuilder();
        for (Path part : relative) {
            String element = part.toString();
            if (element.isEmpty()) continue;
            if (name.length() > 0) name.append('/');
            name.append(element);
        }
        return name.toString();
    }

    // "Load from backup": which archive, and which working directory to unpack it into.
    // Two paths, so two rows with a browse button each - the file one for the archive,
    // and for where it lands the very row the Configure dialog uses for the working
    // directory, so the two behave the same.
    //
    // A restore replaces the target's JRock folder outright and then switches JRock into
    // that directory, prompt and all. That is the point of having taken a backup: what
    // comes back is the session, not a folder to go looking through.
    private static void showRestoreDialog(JFrame frame, JTextArea input, LogView log,
                                          java.util.function.Consumer<Boolean> sendGate) {
        // Named, because both rows are a path field with a button next to it.
        javax.swing.JTextField zipF = new javax.swing.JTextField("", 32);
        zipF.setName("backupFile");
        javax.swing.JPanel zipRow = fileRow(frame, zipF, "Locate the backup file",
                new javax.swing.filechooser.FileNameExtensionFilter("Zip archives (*.zip)", "zip"),
                logChooserDir.start());

        javax.swing.JTextField dirF = new javax.swing.JTextField(workingDir.toString(), 32);
        dirF.setName("restoreDir");
        javax.swing.JPanel unpackRow = dirRow(frame, dirF, "Choose working directory");

        javax.swing.JPanel fields = new javax.swing.JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        addRow(fields, c, 0, "Backup file:", zipRow);
        addRow(fields, c, 1, "Unpack into:", unpackRow);

        // Width-bounded HTML rather than a wrapping JTextArea. A JTextArea asked how big
        // it would like to be before it has a width answers for one unwrapped line, so
        // the dialog came up as short as one line and the wrapped text then pushed the OK
        // and Cancel buttons out through the bottom of the window frame. An HTML label is
        // asked the same question with the width already settled, so the height it gives
        // back is the height it uses. The width is the one the two path rows ask for
        // anyway, which is why this does not widen the dialog.
        javax.swing.JLabel note = new javax.swing.JLabel(
                "<html><body style='width:" + fields.getPreferredSize().width + "px'>"
                + "The " + BACKUP_ROOT + " folder in the target directory is deleted and "
                + "replaced by the one in the backup. JRock then works in that directory, "
                + "with the restored log and prompt.</body></html>");
        note.setVerticalAlignment(javax.swing.SwingConstants.TOP);

        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 8));
        panel.add(fields, BorderLayout.NORTH);
        panel.add(note, BorderLayout.CENTER);

        int result = javax.swing.JOptionPane.showConfirmDialog(
                frame, panel, "Load from backup",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (result != javax.swing.JOptionPane.OK_OPTION) return;

        String zipText = zipF.getText().trim();
        if (zipText.isEmpty()) {
            restoreRefused(frame, "Name the backup file to load from.");
            return;
        }
        Path zip = Paths.get(zipText).toAbsolutePath().normalize();
        if (!Files.isRegularFile(zip)) {
            restoreRefused(frame, "There is no file at " + zip + ".");
            return;
        }

        String dirText = dirF.getText().trim();
        if (dirText.isEmpty()) {
            restoreRefused(frame, "Name the working directory to unpack into.");
            return;
        }
        Path target = Paths.get(dirText).toAbsolutePath().normalize();
        try {
            Files.createDirectories(target);
        } catch (IOException ex) {
            restoreRefused(frame, "Could not use " + target + ":\n" + ex.getMessage());
            return;
        }

        // The one target that cannot work: the archive sitting inside the folder about to
        // be deleted, which would take the backup with it.
        final Path root = target.resolve(BACKUP_ROOT);
        if (zip.startsWith(root.toAbsolutePath().normalize())) {
            restoreRefused(frame, "The backup is inside " + root
                    + ", which a restore deletes.\nMove it elsewhere first.");
            return;
        }

        // Not empty means there is work in there, whether or not it is JRock's. Said
        // plainly, because "overwrite everything?" is what is actually being asked.
        if (!isEmptyDir(target)) {
            int choice = javax.swing.JOptionPane.showConfirmDialog(frame,
                    target + " is not empty.\n\nOverwrite everything? The " + BACKUP_ROOT
                        + " folder there will be deleted and replaced by the backup's.",
                    "Overwrite everything?",
                    javax.swing.JOptionPane.OK_CANCEL_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE);
            if (choice != javax.swing.JOptionPane.OK_OPTION) return;
        }

        log.gray("Restoring " + zip + " into " + target + " ...");
        sendGate.accept(false);
        new SwingWorker<String, Void>() {
            private long files;

            // Returns null when it went well, or the line to log when it did not.
            @Override
            protected String doInBackground() {
                try {
                    deleteRecursively(root);
                    files = unpackBackup(zip, target);
                    return null;
                } catch (IOException ex) {
                    return "Could not restore " + zip + " into " + target + ": " + ex;
                }
            }

            @Override
            protected void done() {
                String failure;
                try {
                    failure = get();
                } catch (Exception ex) {
                    failure = "Could not restore " + zip + " into " + target + ": " + ex;
                }
                if (failure != null) {
                    log.gray(failure);
                    log.gray("");
                    javax.swing.JOptionPane.showMessageDialog(frame, failure,
                            "Restore failed", javax.swing.JOptionPane.WARNING_MESSAGE);
                    sendGate.accept(true);
                    return;
                }
                // Switched exactly as the Configure dialog switches it: working
                // directory, user.dir for anything reading it, then the window's own
                // name, and finally the session report - which reloads the log and the
                // prompt from the directory that has just been restored.
                //
                // The count goes in after initSession, not before: loading the restored
                // log replaces everything on screen, so a line logged first would be
                // wiped by the very restore it was reporting.
                workingDir = target;
                System.setProperty("user.dir", target.toString());
                applyWindowIdentity(frame);
                initSession(log, adoptPromptOfWorkingDir(input));
                log.gray("Restored " + files + " file(s) from " + zip);
                sendGate.accept(true);
            }
        }.execute();
    }

    // A restore that cannot start says so and stops. In a dialog only: the log it would
    // otherwise write to may be about to be replaced.
    private static void restoreRefused(JFrame frame, String message) {
        javax.swing.JOptionPane.showMessageDialog(frame, message,
                "Load from backup", javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    // Unpacks a backup, recreating target/JRock from the archive. Returns the number of
    // files written.
    //
    // Only "JRock/..." entries are taken, and an archive with none of them is refused
    // rather than scattered - that is what tells a JRock backup from any other zip that
    // could be picked in the dialog. Each entry is then resolved and checked to land
    // inside target/JRock, so an archive carrying ".." in its names cannot write outside
    // it (zip slip).
    private static long unpackBackup(Path zip, Path target) throws IOException {
        Path root = target.resolve(BACKUP_ROOT).toAbsolutePath().normalize();
        String prefix = BACKUP_ROOT + "/";
        long files = 0;
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(
                Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                // Zips are written with forward slashes; a backslash in a name is some
                // other tool's idea of a separator, and means the same thing here.
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith(prefix)) continue;
                Path out = root.resolve(name.substring(prefix.length()))
                        .toAbsolutePath().normalize();
                if (!out.startsWith(root)) {
                    throw new IOException("the archive holds an entry outside "
                            + BACKUP_ROOT + "/: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                if (entry.getLastModifiedTime() != null) {
                    Files.setLastModifiedTime(out, entry.getLastModifiedTime());
                }
                files++;
            }
        }
        // Nothing written AND no folder made: not one entry of this archive was ours.
        if (files == 0 && !Files.isDirectory(root)) {
            throw new IOException("there is no " + prefix + " folder in the archive, so "
                    + "this is not a JRock backup");
        }
        return files;
    }

    // Deletes a directory tree, if it is there at all. Used on the one directory a
    // restore owns: the JRock folder the archive is about to replace.
    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException failure)
                    throws IOException {
                if (failure != null) throw failure;
                Files.delete(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    // Whether a directory has nothing in it. A directory that isn't there counts as
    // empty - the restore creates it, and there is nothing in it to overwrite. One that
    // cannot be listed counts as occupied, so the question gets asked rather than
    // assumed away.
    private static boolean isEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return true;
        try (java.util.stream.Stream<Path> entries = Files.list(dir)) {
            return !entries.findAny().isPresent();
        } catch (IOException ex) {
            return false;
        }
    }

    // A file field with its own "Locate..." button: dirRow's counterpart for one file,
    // offered under one extension. The chooser opens on whatever the field holds, or on
    // the given directory while it is still empty.
    private static javax.swing.JPanel fileRow(JFrame frame, javax.swing.JTextField field,
                                              String chooserTitle,
                                              javax.swing.filechooser.FileFilter filter,
                                              java.io.File fallbackDir) {
        JButton browse = new JButton("Locate...");
        browse.addActionListener(ev -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setDialogTitle(chooserTitle);
            // The caller's filter only: this button is for locating a file of one
            // particular kind, and "All files" in the dropdown is an offer to pick
            // something the caller has already said it cannot use.
            fc.setAcceptAllFileFilterUsed(false);
            fc.setFileFilter(filter);
            String current = field.getText().trim();
            if (current.isEmpty()) {
                fc.setCurrentDirectory(fallbackDir);
            } else {
                java.io.File chosen = new java.io.File(current);
                fc.setCurrentDirectory(chosen.getParentFile());
                fc.setSelectedFile(chosen);
            }
            if (fc.showOpenDialog(frame) == javax.swing.JFileChooser.APPROVE_OPTION
                    && fc.getSelectedFile() != null) {
                field.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(4, 0));
        panel.add(field, BorderLayout.CENTER);
        panel.add(browse, BorderLayout.EAST);
        return panel;
    }

    // Where CheerpJ starts a JAR in the browser: its own writable mount, which is
    // the working directory until the user opens one of their own.
    private static final Path CHEERPJ_HOME = Paths.get("/files");

    // Names the window and its taskbar icon after the working directory. Applied at
    // startup and again whenever that directory changes, so both always say which
    // folder this window is working in.
    private static void applyWindowIdentity(JFrame frame) {
        String folder = titleFolder();
        frame.setTitle(folder != null ? folder + " - JRock" : "JRock - Bedrock (mantle)");
        frame.setIconImages(makeAppIcons(folder));
    }

    // The folder name for the title and the icon, or null for the plain ones.
    //
    // The name goes FIRST in the title so it survives Alt-Tab truncation ("myproj -
    // JRock" rather than "JRock - Bedro..."), which is what tells instances in
    // different folders apart.
    //
    // There is no name at a filesystem root, and none worth showing while the browser
    // sits in CheerpJ's mount: that would title the window "files - JRock" and label
    // the icon "FIL" after a folder the user never chose. A folder they did choose is
    // named here exactly as on the desktop.
    private static String titleFolder() {
        if (isCheerpJ() && workingDir.equals(CHEERPJ_HOME)) return null;
        Path name = workingDir.getFileName();
        if (name == null) return null;
        String folder = name.toString();
        return folder.isBlank() ? null : folder;
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
            // Spelled out because it is baked in at install time, not read live: if
            // the prompts directory is changed in Configure afterwards, these entries
            // keep the old one until they are installed again.
            String promptsNote = (promptsDir == null)
                    ? "Prompts follow the working directory, so the entries carry no "
                    + PROMPTS_DIR_FLAG + ":\nCtrl+O opens in whichever folder you "
                    + "launch from. Set a prompts directory\nin Configure and "
                    + "reinstall to pin it.\n\n"
                    : "Both entries carry " + PROMPTS_DIR_FLAG + " " + promptsDir + ",\n"
                    + "so Ctrl+O opens there whichever folder you launch from.\n"
                    + "Change it in Configure and reinstall to update this.\n\n";
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Installed two Explorer right-click entries:\n"
                        + "  \u2022 \"" + CTX_LABEL + "\" - inside or on a folder, launches JRock there.\n"
                        + "  \u2022 \"" + TXT_LABEL + "\" - on a .txt file, opens it as the prompt.\n\n"
                        + promptsNote
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

    // ---- Windows "run an agent" context menu -------------------------------
    // The same idea as "JRock here!", for the .java automations that live beside the
    // prompts (see the Automation API): a right-click entry that runs one of them.
    //
    // What it adds is the one thing Explorer gets backwards here. Explorer starts the
    // process in the folder that was clicked - exactly right for "JRock here!", and
    // wrong for an agent. An agent is installed FROM a folder whose settings it is
    // meant to work with (that folder's model, its images DPI, its Bedrock key), and
    // is then run on a document that may be anywhere on the disk. So the installed
    // command names that folder itself, with --working-dir, which overrides whatever
    // directory Explorer starts the process in.
    //
    // Hence two commands per agent, written by one install:
    //   - inside or on a folder: no argument, so the agent asks. Its own file chooser
    //     opens in the working directory it was handed, which is the folder the agent
    //     was installed from - not the folder that was clicked.
    //   - on a file, of any type, in any folder: that file's full path as the single
    //     argument. This is what lets one agent work on a file anywhere while still
    //     using one particular folder's settings.
    //
    // And hence one entry per (agent, folder) pair rather than per agent: installing
    // the same automation from two folders gives two entries with two sets of
    // settings, which is the point - "Doc Inventory (HPScan)" on one model, "Doc
    // Inventory (Documents)" at another DPI.
    private static final String AGENT_KEY_PREFIX = "JRockAgent-";
    private static final String AGENT_TITLE = "JRock agent";
    // Every file, whatever its type: an agent decides for itself what it can do with
    // what it was handed, and a filter here could only hide the file it wanted. The
    // folder verbs reuse CTX_SHELL_ROOTS - the same two places "JRock here!" appears.
    private static final String AGENT_FILE_SHELL_ROOT = "Software\\Classes\\*\\shell";

    private static void installAgent(JFrame frame, LogView log) {
        try {
            Path agent = chooseAgent(frame, "Install agent");
            if (agent == null) return;
            String javaw = findJavaw();
            if (javaw == null) {
                showAgentError(frame, log, "Could not find javaw.exe next to the running JVM.");
                return;
            }
            Path jar = agentJar(agent);
            if (jar == null) {
                showAgentError(frame, log,
                        "An agent runs as javaw -cp jrock.jar Agent.java, so installing "
                        + "one needs jrock.jar itself.\n\n"
                        + "There is none beside " + agent.getFileName() + " in\n"
                        + agent.getParent() + "\nand this JRock is not running from one "
                        + "either.\n\n"
                        + "Copy jrock.jar into the agent's directory - which is what "
                        + "enables\nautomations there anyway - and install again.");
                return;
            }
            String label = agentLabel(agent);
            String key = agentKey(agent);
            String dirLaunch = buildAgentLaunch(javaw, jar, agent, null);
            String fileLaunch = buildAgentLaunch(javaw, jar, agent, "\"%1\"");

            StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
            for (String root : CTX_SHELL_ROOTS) {
                appendAgentVerb(reg, root, key, label, javaw, dirLaunch);
            }
            appendAgentVerb(reg, AGENT_FILE_SHELL_ROOT, key, label, javaw, fileLaunch);

            // Kept under JRock/, named after the agent, for the same reason the
            // "JRock here!" file is: it is the record of exactly what was applied, and
            // the thing to open when an entry has to be understood or removed by hand.
            Files.createDirectories(jrockDir());
            Path regFile = jrockDir()
                    .resolve("jrock-agent-" + keyPart(stemOf(agent)) + "-install.reg");
            importReg(reg.toString(), regFile);
            log.gray("Installed the \"" + label + "\" Explorer right-click entry.");
            log.gray("Agent: " + agent);
            log.gray("Registry key name: " + key);
            log.gray("In a folder:  " + dirLaunch);
            log.gray("On a file:    " + fileLaunch);
            log.gray("Applied registry file kept at: " + regFile);
            log.gray("");
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Installed the Explorer right-click entry\n"
                        + "  \"" + label + "\"\n"
                        + "for " + agent.getFileName() + ", in two places:\n\n"
                        + "  \u2022 inside or on a folder - the agent runs with no "
                        + "file, so it asks\n    which one to work on, starting in the "
                        + "folder Explorer ran it from.\n"
                        + "  \u2022 on a file of any type, in any folder - the agent "
                        + "works on THAT\n    file, still using the settings of "
                        + workingDir + ".\n\n"
                        + "Both run JRock in that working directory, so the model, the "
                        + "images DPI\nand the Bedrock key are the ones kept there. "
                        + "Install the same agent from\nanother folder to get a second "
                        + "entry with that folder's settings.\n\n"
                        + "(On Windows 11 it may appear under \"Show more options\".)\n\n"
                        + "The applied registry file was kept for your inspection at:\n"
                        + regFile,
                    AGENT_TITLE, javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAgentError(frame, log, ex.getMessage());
        }
    }

    // Removes what the same agent, installed from THIS working directory, wrote: the
    // key name is derived the same way, so uninstalling means picking the same file
    // from the same folder. Removing some other folder's entry is a matter of
    // launching JRock there ("JRock here!") and uninstalling from it - or of reading
    // the install .reg this wrote, which names the key in full.
    private static void uninstallAgent(JFrame frame, LogView log) {
        try {
            Path agent = chooseAgent(frame, "Uninstall agent");
            if (agent == null) return;
            String label = agentLabel(agent);
            String key = agentKey(agent);
            StringBuilder reg = new StringBuilder("Windows Registry Editor Version 5.00\r\n\r\n");
            for (String root : CTX_SHELL_ROOTS) {
                reg.append("[-HKEY_CURRENT_USER\\").append(root)
                   .append('\\').append(key).append("]\r\n\r\n");
            }
            reg.append("[-HKEY_CURRENT_USER\\").append(AGENT_FILE_SHELL_ROOT)
               .append('\\').append(key).append("]\r\n\r\n");
            Files.createDirectories(jrockDir());
            Path regFile = jrockDir()
                    .resolve("jrock-agent-" + keyPart(stemOf(agent)) + "-uninstall.reg");
            importReg(reg.toString(), regFile);
            log.gray("Removed the \"" + label + "\" Explorer right-click entry (if present).");
            log.gray("Registry key name: " + key);
            log.gray("Applied registry file kept at: " + regFile);
            log.gray("");
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Removed the \"" + label + "\" right-click entry (if present).\n\n"
                        + "This removes the entry installed for " + agent.getFileName()
                        + "\nfrom " + workingDir + ". An entry installed from another "
                        + "folder\nis a separate one, and is removed from there.\n\n"
                        + "The applied registry file was kept for your inspection at:\n"
                        + regFile,
                    AGENT_TITLE, javax.swing.JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            showAgentError(frame, log, ex.getMessage());
        }
    }

    // One verb: the label, an icon, and the command under it.
    private static void appendAgentVerb(StringBuilder reg, String root, String key,
                                        String label, String javaw, String launch) {
        String base = "HKEY_CURRENT_USER\\" + root + "\\" + key;
        reg.append('[').append(base).append("]\r\n");
        reg.append("@=").append(regString(label)).append("\r\n");
        reg.append("\"Icon\"=").append(regString(javaw)).append("\r\n\r\n");
        reg.append('[').append(base).append("\\command]\r\n");
        reg.append("@=").append(regString(launch)).append("\r\n\r\n");
    }

    // Picks the agent, in the prompts & agents directory and among .java files only:
    // that directory is where both the prompts and the automations that chain them
    // live, and an agent is a .java file by definition - it is run as one.
    private static Path chooseAgent(JFrame frame, String title) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(promptsDir().toFile());
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Java agents (*.java)", "java"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION
                || chooser.getSelectedFile() == null) {
            return null;
        }
        return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
    }

    // The jar an agent's installed command will run against: jrock.jar in the AGENT's
    // own directory first, and only then the jar this JRock is itself running from.
    //
    // That order is the one that matches how automations are set up: an automation
    // directory holds the agent, the prompts it chains and a copy of jrock.jar, put
    // there by hand (see the samples), and that copy is the one the agent is developed
    // and run against. It is also the only one there is when JRock itself was started
    // as `java JRock.java` - an agent needs a class path, and a class path is a jar or
    // a directory of classes, never a .java file.
    private static Path agentJar(Path agent) {
        Path parent = agent.getParent();
        if (parent != null) {
            Path beside = parent.resolve("jrock.jar");
            if (Files.isRegularFile(beside)) return beside.toAbsolutePath().normalize();
        }
        Path self = ownJarOrSource();
        return (self != null && self.toString().toLowerCase().endsWith(".jar"))
                ? self : null;
    }

    // The command Explorer runs: this jar on the class path, the agent as the source
    // file to run (single-file source mode compiles it in memory against that jar),
    // then the flags that pin the folder whose settings it is to use, and last what was
    // clicked. No cmd, no console.
    //
    // The file verb passes what was clicked (%1); the folder verb passes nothing, and
    // an agent started with no document asks for one in a file chooser. Where that
    // chooser opens is the agent's business and needs no flag: Explorer starts a
    // right-click command IN the folder it was clicked in, so the process working
    // directory already is that folder - which is what the samples open in. (An earlier
    // attempt passed the folder as --start-dir "%V" and did not work; %V was not the
    // folder the chooser wanted, and the command line was the wrong place to look for
    // something the process already knows.)
    private static String buildAgentLaunch(String javaw, Path jar, Path agent,
                                           String fileArg) {
        StringBuilder cmd = new StringBuilder();
        cmd.append('"').append(javaw).append("\" -cp \"").append(jar).append("\" ");
        cmd.append('"').append(agent).append('"');
        cmd.append(' ').append(WORKING_DIR_FLAG).append(' ').append(quotedDir(workingDir));
        cmd.append(ctxPromptsDirArg());
        if (fileArg != null) cmd.append(' ').append(fileArg);
        return cmd.toString();
    }

    // What Explorer shows: "JRockDocInventory.java", installed from a folder called
    // HPScan, becomes "JRock agent: Doc Inventory (HPScan)...".
    //
    //   - the JRock prefix moves to the front of the label, where it groups the agents
    //     next to "JRock here!" in a context menu full of other applications' entries;
    //   - the camel case is split into words, because a menu is read, not compiled;
    //   - the folder in parentheses is what tells two installs of one agent apart, and
    //     it names the folder whose settings that install uses;
    //   - the ellipsis promises a window that asks something, as it does everywhere
    //     else in this application.
    private static String agentLabel(Path agent) {
        String stem = stemOf(agent);
        if (stem.startsWith("JRock") && stem.length() > "JRock".length()) {
            stem = stem.substring("JRock".length());
        }
        // Split at a lower-to-upper boundary, and before the last capital of a run
        // ("PDFReport" -> "PDF Report"), which is where the words of a camel-case
        // name actually are.
        String words = stem
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ").trim();
        if (words.isEmpty()) words = stemOf(agent);
        String folder = titleFolder();
        return AGENT_TITLE + ": " + words
                + (folder == null ? "" : " (" + folder + ")") + "...";
    }

    // The registry key name for this agent in this folder: readable, and unique per
    // pair. The hash is of the working directory, lower-cased because Windows paths
    // are not case-sensitive and "C:\HPScan" and "c:\hpscan" are one folder. It is
    // what keeps two folders whose names both end in "Scans" apart, and what lets
    // uninstall address exactly the entry install wrote.
    private static String agentKey(Path agent) {
        String folder = titleFolder();
        String hash = hashBytes(workingDir.toString().toLowerCase()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return AGENT_KEY_PREFIX + keyPart(stemOf(agent))
                + "-" + keyPart(folder == null ? "root" : folder)
                + (hash == null ? "" : "-" + hash);
    }

    // A file name without its extension.
    private static String stemOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // A registry key name and a file name hold far less than a path can, so anything
    // that is not a letter, a digit, a dash or an underscore becomes a dash. Only
    // readability rests on this: what makes an agent key unique is the hash beside it.
    private static String keyPart(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9_-]", "-");
        return clean.isEmpty() ? "x" : clean;
    }

    private static void showAgentError(JFrame frame, LogView log, String msg) {
        log.gray("Agent context menu update failed: " + msg);
        log.gray("");
        javax.swing.JOptionPane.showMessageDialog(frame,
                "Could not update the agent context menu:\n" + msg,
                AGENT_TITLE, javax.swing.JOptionPane.WARNING_MESSAGE);
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
    // roots its files there with no extra flag. No cmd, no console.
    private static String buildCtxLaunch(String javaw) {
        return buildLaunch(javaw, null);
    }

    // The launch command for the .txt verb: javaw passing the clicked file (%1)
    // as JRock's prompt-source argument. JRock uses whatever current directory
    // Explorer starts it in (typically the file's folder). No cmd, no console.
    private static String buildTxtLaunch(String javaw) {
        return buildLaunch(javaw, "\"%1\"");
    }

    // Shared shape of both installed commands: javaw, this JRock, the current
    // --prompts-dir if there is one, then the clicked file for the .txt verb.
    // Prefers this running jar; falls back to the JRock.java source file.
    private static String buildLaunch(String javaw, String fileArg) {
        Path self = ownJarOrSource();
        if (self == null) return null;
        StringBuilder cmd = new StringBuilder("\"").append(javaw).append("\" ");
        if (self.toString().toLowerCase().endsWith(".jar")) {
            cmd.append("-jar ");
        }   // else: single-file source launch (JDK 11+), which needs no flag
        cmd.append('"').append(self).append('"');
        cmd.append(ctxPromptsDirArg());
        if (fileArg != null) cmd.append(' ').append(fileArg);
        return cmd.toString();
    }

    // The --prompts-dir fragment to bake into an installed command, or "" when the
    // flag wasn't given.
    //
    // This is what makes the context menu carry the prompt library into any folder.
    // Explorer supplies the working directory - that is the whole point of "JRock
    // here!" - but nothing would otherwise supply the prompts directory, so without
    // this the entry would always fall back to opening Ctrl+O in the clicked folder.
    // Installing records the directory in effect at that moment, whether that came
    // from the flag or from the Configure dialog since; change it and you reinstall,
    // which is why the dialog and the log both spell out what was written.
    //
    // Nothing is written when prompts simply follow the working directory (promptsDir
    // null, per its invariant): the flag would then name the folder this instance
    // happened to be launched from, and bake it into every future launch from
    // everywhere else - the opposite of what "JRock here!" means.
    private static String ctxPromptsDirArg() {
        if (promptsDir == null) return "";
        return " " + PROMPTS_DIR_FLAG + " " + quotedDir(promptsDir);
    }

    // A directory as one quoted argument of an installed command line. A trailing
    // backslash - which only a drive root like "D:\" still has after normalize() -
    // would escape the closing quote when Windows parses the command line, so it is
    // doubled, which is the standard fix.
    private static String quotedDir(Path dir) {
        String s = dir.toString();
        if (s.endsWith("\\")) s = s + "\\";
        return "\"" + s + "\"";
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
    // plain-text formats people actually reach for. A .csv, .json, .html or .java file
    // is text like any other, and having to rename it to .txt to load or include it was
    // pure friction. (Any file still has to pass the looksBinary check on load.)
    //
    // .rtf is in the list because an RTF file is text too - its markup is ASCII, which
    // is how it carries everything else - and a model that knows RTF can read it as it
    // stands, and answer in it. "as is" is what separates this from the include
    // dialog's other offer for the same file: "RTF as Markdown text", which converts it
    // and sends the Markdown instead.
    private static final String[] TEXT_EXTENSIONS =
            { "txt", "csv", "json", "html", "java", "rtf" };
    private static final String TEXT_FILTER_LABEL =
            "Text files as is (*.txt, *.csv, *.json, *.html, *.java, *.rtf)";

    // The image formats ImageHeader can read a size out of, which is also the set the
    // DOCX export can place: named once, because two filters in the include dialog
    // offer the same files under different terms.
    private static final String[] IMAGE_EXTENSIONS = { "png", "jpg", "jpeg", "gif", "webp" };
    private static final String IMAGE_FILTER_SUFFIX = " (png, jpg, jpeg, gif, webp)";

    // The audio formats the include dialog offers, which are also the ones AudioHeader
    // can read a header out of.
    //
    // Exactly the two the chat API documents for an "input_audio" part, whose format field
    // is an enum of those two and nothing else ("Currently supports "wav" and "mp3""). m4a
    // was offered here for one version, because it is what a phone's voice memo hands back
    // and the endpoint is the only authority on whether it takes one - it does not, and
    // that is now a tested answer rather than a guess, so the filter no longer offers a
    // file the request cannot carry.
    private static final String[] AUDIO_EXTENSIONS = { "wav", "mp3" };
    private static final String AUDIO_FILTER_SUFFIX = " (wav, mp3)";

    // The chooser's "File name" field: the first text field in it, every look and feel
    // putting that one first and the rest of the chooser having none. Found by looking
    // rather than by asking the UI delegate, which keeps it in a protected field of its
    // own in every look and feel.
    private static javax.swing.text.JTextComponent findFileNameField(java.awt.Container root) {
        for (java.awt.Component child : root.getComponents()) {
            // Not a combo box's editor, which is a text field inside something that is
            // not a field at all.
            if (child instanceof javax.swing.JComboBox) continue;
            if (child instanceof javax.swing.JTextField) {
                return (javax.swing.JTextField) child;
            }
            if (child instanceof java.awt.Container) {
                javax.swing.text.JTextComponent found =
                        findFileNameField((java.awt.Container) child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Loads a prompt from a user-chosen file (read-only) into the input area.
    // The document listener then autosaves the loaded text to jrock-prompt.txt.
    //
    // Always opens in the prompts directory - it does NOT remember where it was last.
    // A prompt library is a place you go back to, so Ctrl+O landing somewhere else
    // because of where you last browsed is a small navigation chore added to every
    // single load. Where that place is is now a setting (Configure), which is the
    // thing to change if it's wrong; Ctrl+S is the same, so the pair stays symmetric.
    private static void loadPromptInto(JFrame frame, JTextArea input, LogView log) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(promptsDir().toFile());
        chooser.setDialogTitle("Load prompt");
        // All files, and only that - exactly what Save prompt copy offers, so the pair
        // stays symmetric. A prompt is a prompt whatever it is called: "*.md", "*.prompt"
        // or no extension at all are all of them ways people keep one, so a filter here
        // would only hide prompts. Nothing has to be said about the kinds of file either,
        // which is the include dialog's business: its filters say what to DO with a file
        // (as text, as an image, as Markdown), a question a prompt never asks - it is
        // loaded as the text it is. What refuses a file that cannot be one is the
        // looksBinary check below, after it has been read.
        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;

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
    // Lets the user pick a text, image or audio file, a PDF to convert (via Ghostscript)
    // into per-page text or per-page images, or an RTF or DOCX to convert into
    // Markdown (with the JDK's own RTF reader and XML parser). Each included file is hashed, remembered as
    // hash -> path in the non-persistent INCLUDES map, logged (with image dimensions or
    // a recording's own header where applicable), and gets an "@txt <hash>" /
    // "@img <hash>" / "@audio <hash>"
    // token inserted at the prompt cursor - an image optionally with a Markdown
    // "![](<hash>)" reference above it, which is what the DOCX export places.
    //
    // With copies on (Ctrl+Shift+I, "Include with copy..."), each file the user picks
    // directly is copied into JRock/includes/ first and included from the copy - see
    // includeCopyOf. Everything else about the dialog is the same, which is the point
    // of it being the same dialog.
    private static void showIncludeDialog(JFrame frame, JTextArea input, LogView log,
                                          boolean extend, boolean copies) {
        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(includeChooserDir.start());
        chooser.setDialogTitle(copies ? "Include file, with a copy under JRock"
                                      : "Include file");
        chooser.setAcceptAllFileFilterUsed(false);
        javax.swing.filechooser.FileNameExtensionFilter imageFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "Image files" + IMAGE_FILTER_SUFFIX, IMAGE_EXTENSIONS);
        // The same files, with one line more in the prompt: a Markdown "![](<hash>)"
        // above the token. The model reads it as a picture belonging to the text, and
        // writes it back into its answer where the picture belongs - which is what the
        // DOCX export then places (see exportSelectedMarkdown).
        javax.swing.filechooser.FileNameExtensionFilter imageRefFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "Image with a Markdown reference" + IMAGE_FILTER_SUFFIX, IMAGE_EXTENSIONS);
        javax.swing.filechooser.FileNameExtensionFilter textFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        TEXT_FILTER_LABEL, TEXT_EXTENSIONS);
        // Page images and nothing else, for a PDF: Ghostscript's txtwrite is a poor
        // reader of a real document - it takes the text operators as they come and
        // hands back something a model has to guess at. A PDF whose text matters is
        // better turned into RTF or DOCX in Acrobat and included under one of those
        // filters, where the headings and tables survive as structure.
        javax.swing.filechooser.FileNameExtensionFilter pdfImageFilter =
                new javax.swing.filechooser.FileNameExtensionFilter("PDF as page images (*.pdf)", "pdf");
        javax.swing.filechooser.FileNameExtensionFilter rtfMarkdownFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "RTF as Markdown text (*.rtf)", "rtf");
        javax.swing.filechooser.FileNameExtensionFilter docxMarkdownFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "DOCX as Markdown text (*.docx)", "docx");
        // A recording, sent as itself: the model is given the audio, not a transcript of
        // it made here. There is nothing to convert and nothing to rasterise - the file
        // goes out base64 in an "input_audio" part, the way an image goes out in an
        // "image_url" one (see AUDIO_EXTENSIONS and buildParts).
        javax.swing.filechooser.FileNameExtensionFilter audioFilter =
                new javax.swing.filechooser.FileNameExtensionFilter(
                        "Audio files" + AUDIO_FILTER_SUFFIX, AUDIO_EXTENSIONS);
        chooser.addChoosableFileFilter(imageFilter);   // first in the dropdown
        chooser.addChoosableFileFilter(imageRefFilter);
        chooser.addChoosableFileFilter(textFilter);
        chooser.addChoosableFileFilter(audioFilter);
        chooser.addChoosableFileFilter(pdfImageFilter);
        chooser.addChoosableFileFilter(rtfMarkdownFilter);
        chooser.addChoosableFileFilter(docxMarkdownFilter);
        chooser.setFileFilter(imageFilter);            // default selection = image
        chooser.setMultiSelectionEnabled(true);        // allow selecting several files

        // A clipboard for the "File name" line, which on a touch screen is the only way
        // to include several files at once - see addCopyPasteMenu for why, and for the
        // "a.png" "b.png" the chooser expects there.
        addCopyPasteMenu(findFileNameField(chooser), log);

        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        includeChooserDir.remember(chooser);

        java.io.File[] selected = chooser.getSelectedFiles();
        if (selected == null || selected.length == 0) return;
        javax.swing.filechooser.FileFilter chosen = chooser.getFileFilter();
        boolean pdf = chosen == pdfImageFilter;
        boolean rtf = chosen == rtfMarkdownFilter;
        boolean docx = chosen == docxMarkdownFilter;
        boolean isImage = chosen == imageFilter || chosen == imageRefFilter;
        boolean isAudio = chosen == audioFilter;
        boolean markdownRef = chosen == imageRefFilter;

        // Process each chosen file in turn, all under the selected filter's kind.
        //
        // Off the EDT, because a PDF include runs Ghostscript and waits for it: on a
        // big document that is minutes, and on the EDT nothing would repaint for all
        // of them - not the "Converting PDF with Ghostscript: ..." line, not gs's own
        // page-by-page progress, not even the window. The user would see a frozen
        // application and no explanation. Everything that touches a widget from here
        // hops back onto the EDT with onEdt(). An RTF conversion is quick by
        // comparison, but it parses a whole document, so it goes the same way.
        final java.io.File[] files = selected;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                for (java.io.File f : files) {
                    Path file = f.toPath();
                    if (pdf) {
                        includePdf(frame, input, log, extend, file);
                    } else if (rtf) {
                        includeRtfAsMarkdown(input, log, extend, file);
                    } else if (docx) {
                        includeDocxAsMarkdown(input, log, extend, file);
                    } else {
                        // The copy, if one was asked for, happens here and not inside
                        // includeOne: what the three conversions above include is
                        // already a file they wrote under JRock/ themselves, so only
                        // the file the user picked directly needs copying.
                        final Path included =
                                copies ? includeCopyOf(file, log, isImage) : file;
                        // Left on the EDT: hashing and reading a plain include is
                        // quick, and this is what it always did.
                        onEdt(() -> includeOne(input, log, extend, included,
                                isImage ? "img" : isAudio ? "audio" : "txt",
                                isImage, markdownRef));
                    }
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();   // surfaces anything doInBackground threw
                } catch (Exception ex) {
                    log.gray("Include failed: " + ex.getMessage());
                }
                log.gray("");   // closes the include block (one per Ctrl+I, however many files)
                input.requestFocusInWindow();
            }
        }.execute();
    }

    // Runs body on the EDT and waits for it to finish.
    //
    // For work started on a background thread that still has to read or change a
    // widget: Swing components are not thread-safe, and the prompt's document in
    // particular is being edited by the user at the same time.
    private static void onEdt(Runnable body) {
        if (SwingUtilities.isEventDispatchThread()) {
            body.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // Rethrown so the caller's SwingWorker.done() can report it, rather
            // than the failure vanishing on a thread nobody is watching.
            Throwable cause = ex.getCause();
            throw (cause instanceof RuntimeException)
                    ? (RuntimeException) cause : new RuntimeException(cause);
        }
    }

    // Copies the file into JRock/includes/ and returns the copy, which is then what
    // gets included; a file that already lives there is returned as it stands.
    //
    // Why anyone would want that: the file JRock remembers is not always a file that
    // stays. In the browser build an uploaded file lands in CheerpJ's /uploads, and
    // that is gone after a reload - taking the path INCLUDES remembers with it, so
    // "Reload all includes" would find a file that no longer exists. A copy under
    // JRock/ is in the folder the user owns, next to the log that names it.
    //
    // A failed copy is reported and the original included anyway: the point of
    // "Include with copy..." is to keep the include available later, and refusing the
    // include now would be a worse answer to "the copy didn't work" than including the
    // file where it lies. Runs off the EDT with the rest of the include (it reads and
    // writes a whole file, which on a 40 MB photograph is not instant).
    //
    // An image gets one thing more: a copy is a new file, which is the moment at which
    // it can be made no larger than it needs to be - see downscaledCopy. isImage says
    // whether to try, the filter the user chose being what decides that.
    private static Path includeCopyOf(Path file, LogView log, boolean isImage) {
        Path dir = includesDir();
        try {
            // Already a copy (a re-include of something under JRock/includes/, or a
            // file the user browsed to there): copying it again would only make
            // "photo-2.png" out of "photo.png".
            if (file.toAbsolutePath().normalize().startsWith(dir.toAbsolutePath().normalize())) {
                return file;
            }
            Files.createDirectories(dir);
            if (isImage) {
                Path smaller = downscaledCopy(dir, file, log);
                if (smaller != null) return smaller;
            }
            Path target = includeCopyTarget(dir, file);
            if (Files.exists(target)) {
                log.gray("Include copy already saved: " + target);
            } else {
                Files.copy(file, target);
                log.gray("Copied for the include: " + file + " -> " + target);
            }
            return target;
        } catch (IOException | RuntimeException ex) {
            log.gray("Could not copy " + file + " into " + dir + ": " + ex.getMessage()
                    + " - including the file where it is.");
            return file;
        }
    }

    // Where a copy of file goes in dir: its own name, or the name with "-2", "-3", ...
    // before the extension when that is taken by a DIFFERENT file. A name taken by a
    // file with the same bytes is returned as it stands - that copy has already been
    // saved, and including two identical files under two names would be nothing but
    // two names for one include.
    private static Path includeCopyTarget(Path dir, Path file) {
        return includeCopyTarget(dir, file.getFileName().toString(), hashFile(file));
    }

    // The same, for a copy that is not a copy of any file yet: the downscaled one, which
    // is written to a temporary file and named after what came out of the scaling rather
    // than after the file that went in. So the name to aim for and the bytes to compare
    // against are passed in, and the numbering and the "same bytes" rule are one piece
    // of code for both kinds of copy.
    private static Path includeCopyTarget(Path dir, String name, String hash) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)   : "";
        Path target = dir.resolve(name);
        for (int n = 2; Files.exists(target); n++) {
            if (hash != null && hash.equals(hashFile(target))) return target;
            target = dir.resolve(stem + "-" + n + ext);
        }
        return target;
    }

    // A copy of an image no bigger than the page it is going to be read on, or null when
    // it is already no bigger than that - in which case the caller copies the file as it
    // stands, byte for byte.
    //
    // Why downscale at all: an image costs tokens by the pixel, and a phone photograph
    // or a 600 dpi scan carries several times more of them than anything will be seen
    // through. The page a picture is laid out on is A4 with 2 cm margins - the DOCX
    // export's text frame - so at "Images DPI" dots to the inch that page has room for a
    // definite number of dots each way, and past that number nobody reads the detail:
    // the export shrinks the picture to fit the frame, the model is shown small print
    // that was already as fine as it was going to get, and the extra pixels are paid for
    // twice, once in tokens and once in the time spent sending them.
    //
    // Only for "Include with copy...", deliberately. A plain include is a pointer at a
    // file JRock does not own, and rewriting somebody's photograph is not what "include
    // this file" asks for; a copy under JRock/includes/ is JRock's own file, made for
    // this purpose - and it says in its name what it is: "IMG_4002-1004x753.png".
    //
    // The export's own 300 dpi ceiling (MarkdownExport.Image) is left exactly as it was:
    // that one decides how big a picture is PRINTED, which is the page's business rather
    // than the token bill's, and an image already trimmed to the setting is under it.
    private static Path downscaledCopy(Path dir, Path file, LogView log) {
        int[] size = ImageHeader.size(file);
        if (size == null) return null;              // size unknown: copy it as it is
        int[] room = a4Pixels(imagesDpi);
        if (size[0] <= room[0] && size[1] <= room[1]) return null;   // small enough

        String name = file.getFileName().toString();
        String format = imageWriteFormat(name);
        if (format == null) {
            log.gray("Copied at full size: " + name + " is " + size[0] + " x " + size[1]
                    + ", more than A4 at " + imagesDpi + " dpi has room for (" + room[0]
                    + " x " + room[1] + "), but rewriting that format would change what "
                    + "the file is.");
            return null;
        }

        // The arithmetic the export sizes a picture with, for the same reason: one factor
        // for both directions, so whichever of them binds, the other cannot drift out of
        // proportion.
        double factor = Math.min((double) room[0] / size[0], (double) room[1] / size[1]);
        int width  = Math.max(1, (int) Math.round(size[0] * factor));
        int height = Math.max(1, (int) Math.round(size[1] * factor));

        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)   : "";
        Path scratch = null;
        try {
            // Written to a temporary file in the same folder first, because the name the
            // copy ends up under depends on its bytes: includeCopyTarget hands back the
            // copy that is already there when the bytes match, and a scaled image's
            // bytes are not the original's.
            scratch = Files.createTempFile(dir, "scaling-", ext.isEmpty() ? ".tmp" : ext);
            writeScaled(file, scratch, format, width, height);
            Path target = includeCopyTarget(dir, stem + "-" + width + "x" + height + ext,
                    hashFile(scratch));
            if (Files.exists(target)) {
                Files.delete(scratch);
                log.gray("Include copy already saved: " + target);
            } else {
                Files.move(scratch, target);
                log.gray("Downscaled for the include: " + size[0] + " x " + size[1]
                        + " -> " + width + " x " + height + ", which is what A4 at "
                        + imagesDpi + " dpi has room for.");
                // The line a full-size copy writes too, and on purpose: it is what says
                // where an include came from.
                log.gray("Copied for the include: " + file + " -> " + target);
            }
            scratch = null;
            return target;
        } catch (IOException | RuntimeException | LinkageError ex) {
            // LinkageError on purpose: an ImageIO read can go looking for a native
            // colour-management library the JVM cannot load and throw an Error rather
            // than an exception (see ImageHeader, which is header arithmetic for that
            // reason). An image that cannot be scaled is still an image to copy, so this
            // is a note on the way past, not a failed include.
            log.gray("Could not downscale " + name + " (" + ex + ") - copying it at full "
                    + "size.");
            if (scratch != null) {
                try {
                    Files.deleteIfExists(scratch);
                } catch (IOException ignore) { /* a stray scaling- file, nothing worse */ }
            }
            return null;
        }
    }

    // How many pixels A4 has room for at that resolution: the text frame the DOCX export
    // lays a picture out in - A4 portrait less its 2 cm margins - measured in dots
    // instead of twips. Read from the export's own constants, so the two cannot drift
    // apart on what "the page" is.
    //
    // Portrait on purpose, although the export can also write landscape (where the frame
    // is the same two numbers the other way round): an include is copied long before
    // anybody picks an orientation in the save dialog, so the budget has to be the one
    // that is enough for either. Portrait is that one - it is the narrower frame, so a
    // copy cut to it is never stretched to fill a landscape page; it is placed at 300 dpi
    // and sits a little narrower than the frame instead.
    private static int[] a4Pixels(int dpi) {
        final int twipsPerInch = 1440;         // a twip is 1/20 point, a point 1/72 inch
        long across = Math.round(MarkdownExport.TABLE_WIDTH * (double) dpi / twipsPerInch);
        long down   = Math.round(MarkdownExport.FRAME_HEIGHT * (double) dpi / twipsPerInch);
        return new int[] { (int) Math.max(1, across), (int) Math.max(1, down) };
    }

    // The ImageIO format a copy of this file should be written back as, or null for one
    // this JVM has no business rewriting.
    //
    // PNG and JPEG only, which is what a scan, a screenshot and a photograph are. GIF is
    // left out because a GIF may be an animation and ImageIO would hand back its first
    // frame - a downscale that quietly throws the rest of the file away. WEBP is left
    // out because the JDK has no reader for it at all. Both are copied at full size
    // instead, which keeps the file the file the user chose.
    private static String imageWriteFormat(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpeg";
        return null;
    }

    // Reads source, scales it to width x height and writes it to target in that format.
    //
    // Two implementations of the same sentence, because the browser cannot run the
    // desktop's one at all - see scaledByBrowser for what it does instead and why.
    private static void writeScaled(Path source, Path target, String format,
                                    int width, int height) throws IOException {
        if (isCheerpJ()) {
            scaledByBrowser(source, target, format, width, height);
        } else {
            scaledByImageIo(source, target, format, width, height);
        }
    }

    // In the browser: the page's canvas does the work.
    //
    // Nothing of the path below is available here. ImageIO.read on a JPEG goes looking
    // for the native colour-management library a browser JVM cannot load (ImageHeader
    // says more), and CheerpJ's Graphics2D.drawImage does not resample - so this used to
    // log a downscale and hand over the picture at full size, which is what the log line
    // promising fewer pixels was measured against. Writing a decoder in Java instead
    // means writing a JPEG decoder: Huffman tables, an inverse DCT, chroma upsampling,
    // and an encoder to match. Every browser already has all of that, in native code,
    // behind two calls - so the picture goes out as base64 and comes back smaller.
    //
    // A failure here is an IOException like any other, and the caller's answer to one is
    // to copy the file at full size with a note saying so.
    private static void scaledByBrowser(Path source, Path target, String format,
                                        int width, int height) throws IOException {
        String framed;
        try {
            framed = browserScaleImage(
                    java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(source)),
                    width, height, "jpeg".equals(format) ? "image/jpeg" : "image/png");
        } catch (Throwable ex) {
            // No scaler on this page at all: an older jrock-web, or some other host.
            throw new IOException("this page has no image scaler in its bridge ("
                    + ex.getClass().getSimpleName() + ")", ex);
        }
        String[] reply = bridgeReply(framed);
        if (!"1".equals(reply[0])) {
            throw new IOException(reply[1].isBlank()
                    ? "the page's image scaler gave no reason" : reply[1].trim());
        }
        byte[] scaled;
        try {
            scaled = java.util.Base64.getMimeDecoder().decode(reply[1].trim());
        } catch (IllegalArgumentException ex) {
            throw new IOException("the page's image scaler returned something that is "
                    + "not base64: " + ex.getMessage(), ex);
        }
        if (scaled.length == 0) {
            throw new IOException("the page's image scaler returned no bytes");
        }
        Files.write(target, scaled);
    }

    // On any normal JVM: ImageIO in, Graphics2D through, ImageIO out.
    //
    // Halved repeatedly and then drawn to the exact size, rather than scaled in one step:
    // a single bilinear draw reads four pixels out of the dozens each output pixel
    // covers, and small print comes out as aliased crumbs - which is the very detail the
    // resolution is being chosen for. Every halving averages everything it passes over,
    // so nothing is dropped unseen, and the last step is at most a factor of two.
    private static void scaledByImageIo(Path source, Path target, String format,
                                        int width, int height) throws IOException {
        java.awt.image.BufferedImage full = javax.imageio.ImageIO.read(source.toFile());
        if (full == null) throw new IOException("no reader for " + source.getFileName());
        // JPEG has no alpha channel and its writer refuses an image that has one, so a
        // transparent PNG saved as JPEG is drawn onto white - which is the page a scan
        // came off, and better than the black an ignored alpha channel leaves.
        boolean alpha = !"jpeg".equals(format) && full.getColorModel().hasAlpha();
        java.awt.image.BufferedImage current = full;
        while (current.getWidth() / 2 >= width && current.getHeight() / 2 >= height) {
            current = drawnInto(current, current.getWidth() / 2, current.getHeight() / 2,
                    alpha);
        }
        writeImage(drawnInto(current, width, height, alpha), format, target);
    }

    // One scaling step: source drawn smoothly into a new image of exactly that size.
    private static java.awt.image.BufferedImage drawnInto(
            java.awt.image.BufferedImage source, int width, int height, boolean alpha) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(width, height,
                alpha ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                      : java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, width, height);
            }
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();   // a Graphics2D holds native resources until it is let go
        }
        return out;
    }

    // Writes one image file in the given ImageIO format.
    //
    // JPEG has its quality set rather than taking ImageIO's default of 0.75: what this
    // writes are scans and screenshots, where 0.75 leaves rings around small print, and
    // legibility is the whole reason for keeping the pixels that are kept.
    private static void writeImage(java.awt.image.BufferedImage image, String format,
                                   Path file) throws IOException {
        if (!"jpeg".equals(format)) {
            if (!javax.imageio.ImageIO.write(image, format, file.toFile())) {
                throw new IOException("no " + format + " writer in this JVM");
            }
            return;
        }
        java.util.Iterator<javax.imageio.ImageWriter> writers =
                javax.imageio.ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) throw new IOException("no JPEG writer in this JVM");
        javax.imageio.ImageWriter writer = writers.next();
        try (javax.imageio.stream.ImageOutputStream out =
                javax.imageio.ImageIO.createImageOutputStream(file.toFile())) {
            javax.imageio.ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.92f);
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
    }

    // Registers one file as an include (hash -> path), logs it (with image
    // dimensions when applicable), and inserts its "@kind <hash>" token at the
    // cursor unless already referenced (dedup, also across prior turns in extend
    // mode). Returns true if a token was inserted.
    private static boolean includeOne(JTextArea input, LogView log, boolean extend,
                                      Path file, String kind, boolean isImage) {
        return includeOne(input, log, extend, file, kind, isImage, false);
    }

    // As above, and with markdownRef it also writes a Markdown image reference,
    // "![](<hash>)", on the line above the token - two lines for one file.
    //
    // The token is what JRock sends; the reference is what the model sees as a picture
    // sitting in the text, so its answer can put the picture back where it belongs and
    // the DOCX export can place it there (see MarkdownExport).
    private static boolean includeOne(JTextArea input, LogView log, boolean extend,
                                      Path file, String kind, boolean isImage,
                                      boolean markdownRef) {
        return includeOne(input, log, extend, file, kind, isImage,
                markdownRef ? hash -> "![](" + hash + ")" : null);
    }

    // The general form: reference, when given, is asked for the Markdown line to write
    // above the token, and is handed the include's hash - which is why it is a function
    // and not a string, the hash being known only once the file has been read.
    //
    // Two callers, wanting two different lines for the same reason: an image include
    // refers to the picture by hash, which the DOCX export turns back into the file it
    // placed, and a fetched URL refers to the address the file came from, which is
    // what says where a page or a picture was found (see fetchUrl). null writes the
    // token alone.
    private static boolean includeOne(JTextArea input, LogView log, boolean extend,
                                      Path file, String kind, boolean isImage,
                                      java.util.function.Function<String, String> reference) {
        String hash = hashFile(file);
        if (hash == null) {
            log.gray("Include failed: could not read " + file);
            return false;
        }
        // Always (re)register the hash -> path mapping. After a restart this makes
        // an existing "@kind <hash>" token in the (recovered) prompt valid again.
        INCLUDES.put(hash, file);
        // The one place this line is written: INCLUDE_LOG_LINE reads it back when the
        // includes are reloaded from the log, so its wording is part of the format.
        log.gray("Included @" + kind + " " + hash + " from " + file);
        String refLine = (reference == null) ? null : reference.apply(hash);
        if (refLine != null) log.gray("With a Markdown reference above it: " + refLine);

        long fileBytes = -1;
        try { fileBytes = Files.size(file); } catch (IOException ignore) { /* best-effort */ }

        if (isImage) {
            // Dimensions + total pixel count + file size (all locale-formatted).
            int[] size = ImageHeader.size(file);
            if (size != null) {
                long pixels = (long) size[0] * size[1];
                log.gray("Image: " + size[0] + " x " + size[1]
                        + ", " + fmtNum(pixels) + " pixels"
                        + (fileBytes >= 0 ? ", " + fmtNum(fileBytes) + " bytes" : ""));
            } else {
                log.gray("Image: (dimensions not in the header)"
                        + (fileBytes >= 0 ? "; " + fmtNum(fileBytes) + " bytes" : ""));
            }
        } else if ("audio".equals(kind)) {
            // A recording's own header, the way an image include reports its dimensions:
            // what the file claims to be, how it was sampled, and how long it plays -
            // the three things that say whether the right file was picked. Unreadable, or
            // a format AudioHeader does not know: the byte count, which is all that is
            // certain then (see AudioHeader).
            String about = AudioHeader.describe(file, fileBytes);
            log.gray("Audio: " + (about != null ? about : "(nothing readable in the header)")
                    + (fileBytes >= 0 ? ", " + fmtNum(fileBytes) + " bytes" : ""));
            // The dialog's filter cannot offer anything but wav and mp3, but
            // automationInclude can be handed any path at all - so a file the request
            // cannot carry is said here, where it was picked, rather than left to come back
            // as an HTTP 400 from the send.
            String format = audioFormat(file);
            if (!format.equals("wav") && !format.equals("mp3")) {
                log.gray("This would go out as input_audio with format \"" + format
                        + "\", which the API does not take: the field is an enum of \"wav\" "
                        + "and \"mp3\", and an m4a comes back refused. Convert the file to "
                        + "one of those two first.");
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

        // Insert "@kind <hash>\n" at the cursor (no leading newline), preceded by the
        // Markdown reference when one was asked for.
        String insert = (refLine == null ? "" : refLine + "\n") + token + "\n";
        int pos = input.getCaretPosition();
        try {
            input.getDocument().insertString(pos, insert, null);
        } catch (BadLocationException ex) {
            input.append(insert);   // fallback: append at end
        }
        return true;
    }

    // ---- Fetch URL (prompt menu, Ctrl+U) -----------------------------------
    // The same include as Ctrl+I, for something that is not on this machine: a URL is
    // asked for, downloaded into JRock/urls/, and then included from there like any
    // other file - a web page as "@txt", a picture as "@img". Two lines go into the
    // prompt, the address above the token:
    //
    //     [](https://example.org/article)
    //     @txt 1f3a9c0b7e42
    //
    // The link is what says where the text or the picture came from, in a form the
    // model reads as a reference belonging to the content below it (and the Markdown
    // export keeps as a link). The token is what is actually sent.
    //
    // What decides which kind it is - and the extension the file is saved under - is
    // the response's own Content-Type, not the URL: a link ending in ".png" that
    // answers with HTML is a web page, and saving it as a PNG would produce an @img
    // token no model can read. A media type that is neither text (HTML, plain, JSON) nor
    // one of the image types JRock sends is refused, named, and nothing is written or
    // inserted.

    // Accepted media type -> the extension the download is saved under.
    //
    // The images are the four types buildParts can send, spelled as the extensions
    // imageMime() reads back, so the file JRock saves is a file it can send. HTML is
    // saved as ".html", which the include dialog already offers as text - so from the
    // request's point of view the model is simply reading a text file, markup and all.
    // XHTML is in the list because it is a web page by any other name; it, too, is
    // saved and sent as HTML. Plain text and JSON are text the same way, saved as the
    // ".txt" and ".json" the include dialog offers too, and decoded like a page.
    private static final java.util.Map<String, String> URL_EXTENSIONS = urlExtensions();

    private static java.util.Map<String, String> urlExtensions() {
        java.util.Map<String, String> types = new java.util.LinkedHashMap<>();
        types.put("text/html", "html");
        types.put("application/xhtml+xml", "html");
        types.put("text/plain", "txt");
        types.put("application/json", "json");
        types.put("image/png", "png");
        types.put("image/jpeg", "jpg");
        types.put("image/gif", "gif");
        types.put("image/webp", "webp");
        return java.util.Collections.unmodifiableMap(types);
    }

    // How long a saved name may be before the path itself becomes the problem: a URL
    // path can be hundreds of characters, and Windows still has MAX_PATH to answer to.
    private static final int URL_NAME_MAX = 80;

    // Asks for a URL and, if one is given, fetches and includes it.
    private static void showFetchUrlDialog(JFrame frame, JTextArea input, LogView log,
                                           boolean extend) {
        // Why the width of this dialog is worked out rather than left to the content: a
        // JTextField asks for room for its columns and an HTML label with <br> in it asks
        // for room for its longest line, so a dialog written for a desktop window comes
        // out wider than a phone screen - and a JOptionPane that does not fit is one
        // whose OK button is off the edge of it, which is what happened here. The field
        // asks for fewer columns in the browser (it is stretched by the layout anyway),
        // and the text is given a width to wrap inside, taken from the screen.
        javax.swing.JTextField urlF = new javax.swing.JTextField(isCheerpJ() ? 14 : 48);
        urlF.setName("url");

        // A URL is pasted far more often than it is typed, and on a phone there is no
        // Ctrl+V to paste it with (see addPasteMenu, and pasteRow below for the button
        // that does not depend on a gesture at all).
        addPasteMenu(urlF, log);

        // In the browser the fetching is the page's doing, and a page may only read an
        // address that allows it - so the one failure worth warning about beforehand is
        // said here rather than only in the reason that comes back.
        String cors = isCheerpJ()
                ? " In the browser the page fetches it, so the address has to allow "
                  + "cross-origin reads (CORS); plenty of sites do not."
                : "";
        // 120px of it goes to the option pane's own borders, the icon gap and the frame -
        // measured, not guessed: on a 360px screen a 280px wrap still gave a 398px dialog,
        // while 240px gives 346x232, which fits with room to spare.
        int wrapAt = Math.max(220, Math.min(440,
                java.awt.Toolkit.getDefaultToolkit().getScreenSize().width - 120));
        javax.swing.JLabel what = new javax.swing.JLabel(
                "<html><body style='width:" + wrapAt + "px'>"
                + "A web page comes in as text (@txt), a picture as a picture (@img) - "
                + "whichever the address itself answers with. HTML, plain text, JSON, "
                + "PNG, JPEG, GIF and WEBP are accepted, and the file is saved under "
                + "JRock/urls/." + cors
                + "</body></html>");

        javax.swing.JPanel panel = new javax.swing.JPanel(new BorderLayout(8, 8));
        panel.add(what, BorderLayout.NORTH);
        javax.swing.JPanel row = new javax.swing.JPanel(new BorderLayout(6, 0));
        row.add(new javax.swing.JLabel("URL:"), BorderLayout.WEST);
        row.add(isCheerpJ() ? pasteRow(urlF, "address") : urlF, BorderLayout.CENTER);
        panel.add(row, BorderLayout.SOUTH);

        int result = javax.swing.JOptionPane.showConfirmDialog(
                frame, panel, "Fetch URL",
                javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (result != javax.swing.JOptionPane.OK_OPTION) return;

        final String typed = urlF.getText().trim();
        if (typed.isEmpty()) return;   // OK on an empty field: nothing was asked for

        // Off the EDT, for the reason the include dialog goes the same way: this waits
        // for someone else's web server, and on the EDT the window would not repaint -
        // not even to show the "Fetching URL: ..." line explaining the wait.
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                fetchUrl(frame, input, log, extend, typed);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();   // surfaces anything doInBackground threw
                } catch (Exception ex) {
                    log.gray("Fetch URL failed: " + ex.getMessage());
                }
                log.gray("");   // closes the block, as an include does
                input.requestFocusInWindow();
            }
        }.execute();
    }

    // Downloads one address and includes what came back. Runs on a background thread
    // (see showFetchUrlDialog); the insertion itself goes through onEdt().
    private static void fetchUrl(JFrame frame, JTextArea input, LogView log,
                                 boolean extend, String typed) {
        // A bare "example.org/page" is what a paste from an address bar often looks
        // like, and it has an obvious reading. Anything else keeps the scheme it was
        // given, so a mistyped one is reported rather than papered over.
        String address = typed.contains("://") ? typed : "https://" + typed;
        if (!address.equals(typed)) log.gray("Reading \"" + typed + "\" as " + address);

        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException ex) {
            urlRefused(frame, log, "Not a URL", "\"" + address + "\" is not a URL: "
                    + ex.getMessage());
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            urlRefused(frame, log, "Not a web address",
                    "Only http and https addresses can be inserted; \"" + address
                    + "\" is " + (scheme.isEmpty() ? "missing a scheme" : scheme + ":") + ".");
            return;
        }

        // Through the transport, which is how every other request goes out: on a normal
        // JVM its own HttpClient, and in the browser the hosting page's client, one method
        // along from the one Bedrock is called through (see HttpTransport.fetch).
        log.gray("Fetching URL: " + address);
        UrlReply resp;
        try {
            resp = http().fetch(address, URL_TIMEOUT_SECONDS);
        } catch (Exception ex) {
            // The browser's failures arrive already explained, in words meant to be read
            // (a CORS refusal, a timeout); the JVM's arrive as an exception, which needs
            // its type to make sense of.
            String why = (ex instanceof IOException && ex.getMessage() != null)
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName() + ": " + ex.getMessage();
            urlRefused(frame, log, "Could not fetch the URL",
                    "Could not fetch " + address + ": " + why);
            return;
        }

        // The address the bytes really came from, which is not always the one asked
        // for. It names the file below; the link stays the address the user gave, that
        // being the one they can go back to.
        URI finalUri = uri;
        try {
            if (resp.finalUrl != null && !resp.finalUrl.isBlank()) {
                finalUri = URI.create(resp.finalUrl);
            }
        } catch (IllegalArgumentException ignored) {
            finalUri = uri;      // whatever it answered with, it is not a URI we can use
        }
        if (!finalUri.equals(uri)) log.gray("Redirected to: " + finalUri);

        if (resp.status != 200) {
            urlRefused(frame, log, "The server refused the URL",
                    "HTTP " + resp.status + " from " + finalUri
                    + " - nothing was inserted.");
            return;
        }

        String contentType = resp.contentType;
        String mime = mediaTypeOf(contentType);
        String ext = URL_EXTENSIONS.get(mime);
        if (ext == null) {
            urlRefused(frame, log, "Unsupported media type",
                    "The URL answered with " + (mime.isEmpty() ? "no media type" : mime)
                    + ", which JRock cannot include. Only HTML pages, plain text, JSON "
                    + "and PNG, JPEG, GIF or WEBP images can be inserted - nothing was "
                    + "saved.");
            return;
        }
        boolean isImage = mime.startsWith("image/");

        // An image is saved byte for byte: it is the file that gets sent. A page is
        // decoded with the charset it declares and written back out as UTF-8, because
        // that is how an included text file is read (see buildParts) - a page served as
        // windows-1251 would otherwise reach the model as mojibake, which is the same
        // failure the transport avoids for JSON.
        byte[] body = resp.body;
        byte[] bytes = body;
        if (!isImage) {
            java.nio.charset.Charset declared = charsetOf(contentType);
            bytes = new String(body, declared).getBytes(StandardCharsets.UTF_8);
            log.gray("Decoded as " + declared.name() + ", saved as UTF-8.");
        }

        Path dir = urlsDir();
        Path out;
        try {
            Files.createDirectories(dir);
            out = urlSaveTarget(dir, urlFileName(finalUri, ext), bytes);
            if (Files.exists(out)) {
                log.gray("Already saved from this URL: " + out);
            } else {
                Files.write(out, bytes);
                log.gray("Saved " + fmtNum(bytes.length) + " bytes of " + mime + " to " + out);
            }
        } catch (IOException | RuntimeException ex) {
            urlRefused(frame, log, "Could not save the download",
                    "Could not save " + address + " into " + dir + ": " + ex.getMessage());
            return;
        }

        String kind = isImage ? "img" : "txt";
        boolean[] added = new boolean[1];
        // Touches the prompt's document, so: on the EDT.
        onEdt(() -> added[0] = includeOne(input, log, extend, out, kind, isImage,
                hash -> "[](" + address + ")"));
        log.gray("Inserted " + (added[0] ? 1 : 0) + " new @" + kind + " token(s) for "
                + address + ".");
    }

    // Reports a URL insert that cannot go on: the reason in the log, where the rest of
    // the fetch is recorded, and the same reason in a dialog - this one was asked for by
    // hand, and the answer to it is that nothing was inserted.
    private static void urlRefused(JFrame frame, LogView log, String title, String message) {
        log.gray(message);
        onEdt(() -> javax.swing.JOptionPane.showMessageDialog(frame, message, title,
                javax.swing.JOptionPane.WARNING_MESSAGE));
    }

    // The media type out of a Content-Type header: lower-cased, without its
    // parameters. "text/html; charset=utf-8" -> "text/html". "" when there is none.
    private static String mediaTypeOf(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).trim().toLowerCase();
    }

    // The charset a Content-Type declares, or UTF-8 when it declares none - or names
    // one this JVM has never heard of, which is not a reason to refuse the page.
    private static java.nio.charset.Charset charsetOf(String contentType) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)charset\\s*=\\s*\"?([^\";\\s]+)")
                .matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try {
                return java.nio.charset.Charset.forName(m.group(1));
            } catch (Exception ignored) {
                // Unknown or malformed: fall through to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }

    // The name a download is saved under: the last segment of the URL's path, with the
    // extension its media type calls for.
    //
    //   https://example.org/a/article       -> article.html
    //   https://example.org/pics/cat.png    -> cat.png
    //   https://example.org/page.php        -> page.php.html
    //   https://example.org/                -> example.org.html
    //
    // The existing name is kept whole and the extension added to it (as gs-pdf/ and
    // rtf-md/ do), rather than replaced: ".php" is not what the file is, but it is part
    // of what the file is called, and dropping it would make one name out of two pages.
    // Anything a file system might object to becomes "-", and a very long name is cut.
    private static String urlFileName(URI uri, String ext) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String name = path.substring(path.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = uri.getHost() == null ? "download" : uri.getHost();

        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && safe.length() < URL_NAME_MAX; i++) {
            char c = name.charAt(i);
            safe.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    ? c : '-');
        }
        // A name of nothing but dots is a name the file system reads as a directory.
        String stem = safe.toString();
        while (stem.startsWith(".")) stem = stem.substring(1);
        if (stem.isEmpty() || stem.chars().allMatch(c -> c == '.')) stem = "download";

        return stem.toLowerCase().endsWith("." + ext) ? stem : stem + "." + ext;
    }

    // Where a download goes in dir: its own name, or the name with "-2", "-3", ...
    // before the extension when that is taken by DIFFERENT bytes. A name already held
    // by these exact bytes is returned as it stands - the same page fetched twice is
    // one file, and the caller then leaves it alone. Same rule as an include copy
    // (see includeCopyTarget), on bytes that are not on disk yet.
    private static Path urlSaveTarget(Path dir, String name, byte[] bytes) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)   : "";
        String hash = hashBytes(bytes);
        Path target = dir.resolve(name);
        for (int n = 2; Files.exists(target); n++) {
            if (hash != null && hash.equals(hashFile(target))) return target;
            target = dir.resolve(stem + "-" + n + ext);
        }
        return target;
    }

    // ---- Reload all includes (prompt menu) ---------------------------------
    // Rebuilds INCLUDES out of the log, so a conversation can be carried on after a
    // restart without attaching every file again.
    //
    // The map of hash -> path is deliberately not persisted, and a restart therefore
    // loses it - while the prompt and the whole transcript are recovered from disk, so
    // the tokens that need the map are all still there. That is the broken state this
    // repairs: the log says "Included @img <hash> from <path>" for every include ever
    // made, which is the same information the map held.
    //
    // It is read top to bottom, treating the log as the history it is:
    //
    //   an include line          -> remember hash -> path, replacing an earlier path
    //                               for that hash (the file was re-included, perhaps
    //                               from somewhere else)
    //   a message with a token   -> that hash is wanted, at the path remembered for it
    //                               AT THIS POINT - so a later include of the same
    //                               hash doesn't rewrite what an earlier message meant
    //
    // The current prompt is read last, being the newest thing there is: after a restart
    // its recovered tokens are usually the whole reason for doing this.
    //
    // Nothing is hashed here. Whether each file is still the file it was is exactly
    // what verifyIncludes checks on send, one hash per token, and repeating it now
    // would only be slower and no more certain - the answer can change between the two
    // moments anyway. What this does check is that the file is still there, because a
    // path in the log that no longer exists is the one problem the user can do
    // something about before sending.
    private static void reloadAllIncludes(JTextArea input, LogView log) {
        java.util.Map<String, Path> remembered = new java.util.HashMap<>();
        java.util.Map<String, String> kinds = new java.util.LinkedHashMap<>();   // hash -> img/txt
        java.util.Map<String, Path> wanted = new java.util.LinkedHashMap<>();    // hash -> path

        java.util.List<String[]> steps = new ArrayList<>(log.timeline());
        steps.add(new String[] { ROLE_HUMAN, input.getText() });
        for (String[] step : steps) {
            String text = step[1];
            if (text == null || text.isEmpty()) continue;
            if (step[0] == null) {
                // A gray line. Only one of them is a record of an include, and it is
                // its own whole line.
                java.util.regex.Matcher m = INCLUDE_LOG_LINE.matcher(text.trim());
                if (m.matches()) remembered.put(m.group(2), Paths.get(m.group(3)));
                continue;
            }
            // A message, from either side: the model's answer can carry a reference
            // too, having been given one to write back (and the DOCX export needs the
            // file for it). Both spellings count - the token JRock sends, and the
            // Markdown reference that stands for the picture in the text.
            java.util.regex.Matcher tokens = INCLUDE_TOKEN.matcher(text);
            while (tokens.find()) {
                kinds.put(tokens.group(2), tokens.group(1));
                if (remembered.containsKey(tokens.group(2))) {
                    wanted.put(tokens.group(2), remembered.get(tokens.group(2)));
                }
            }
            java.util.regex.Matcher refs = MarkdownExport.IMAGE_REF.matcher(text);
            while (refs.find()) {
                kinds.putIfAbsent(refs.group(1), "img");
                if (remembered.containsKey(refs.group(1))) {
                    wanted.put(refs.group(1), remembered.get(refs.group(1)));
                }
            }
        }

        if (kinds.isEmpty()) {
            log.gray("Reload all includes: nothing refers to an include - "
                    + "no @img/@txt/@audio token in the log or the prompt.");
            log.gray("");
            return;
        }

        int reloaded = 0, missing = 0, unknown = 0;
        for (java.util.Map.Entry<String, String> e : kinds.entrySet()) {
            String hash = e.getKey(), token = "@" + e.getValue() + " " + hash;
            Path path = wanted.get(hash);
            if (path == null) {
                // No include line for it anywhere above: the log was cleared, or the
                // hash came from somewhere other than an include of this session.
                log.gray("No include recorded for " + token + " - attach the file again "
                        + "with Ctrl+I (the log may have been cleared since).");
                unknown++;
            } else if (!Files.exists(path)) {
                // Still registered: the mapping is what the log recorded, and saying so
                // now is more use than dropping it and repeating "is not known" later.
                INCLUDES.put(hash, path);
                log.gray("Reloaded " + token + " from " + path
                        + " - but that file is not there any more.");
                missing++;
            } else {
                INCLUDES.put(hash, path);
                log.gray("Reloaded " + token + " from " + path);
                reloaded++;
            }
        }
        log.gray("Reload all includes: " + fmtNum(reloaded) + " reloaded"
                + (missing > 0 ? ", " + fmtNum(missing) + " with a missing file" : "")
                + (unknown > 0 ? ", " + fmtNum(unknown) + " not recorded in the log" : "")
                + " (of " + fmtNum(kinds.size()) + " referred to). "
                + "Each file is checked again, by its hash, on send.");
        log.gray("");
    }

    // An image's dimensions, read straight out of its header.
    //
    // Deliberately NOT ImageIO. In the browser (CheerpJ) ImageIO.read on a JPEG walks
    // into the JDK's colour management, which wants the native lcms library that a
    // browser JVM has no way to load:
    //   java.lang.UnsatisfiedLinkError: no lcms in java.library.path
    //   ... sun.java2d.cmm.lcms.LCMS.getModule -> JPEGImageReader.setImageData
    // That is an Error, not an IOException, so it went straight past the catch around
    // it and out of the include - losing the file over a line of log text.
    //
    // A header is a handful of integers in a documented place, so reading it needs no
    // native code, no image decoder and no platform: web and desktop report the same
    // numbers by running the same arithmetic. It is also strictly less work - a 40 MB
    // photo is no longer decoded into memory in full just to log its size.
    //
    // A class of its own, rather than a dozen loose static helpers: byte-level format
    // parsing has nothing in common with the rest of JRock, and names as generic as
    // le16 or positive only mean anything next to the formats they decode. It also
    // gives the format arithmetic its own line in a coverage report, where mixing it
    // into the surrounding UI code said nothing about either.
    //
    // The four formats the include filter accepts: PNG, JPEG, GIF, WEBP. The bytes
    // JRock sends the model are untouched by any of this - an image is uploaded as
    // itself (base64), so these numbers are only ever for the log.
    private static final class ImageHeader {
        private static final int HEAD_BYTES = 32;   // enough for PNG, GIF and WEBP

        private ImageHeader() { }   // static-only: there is no per-image state to hold

        // {width, height}, or null if the format isn't one of these, the file is
        // truncated, or it can't be read at all.
        static int[] size(Path file) {
            try (java.io.InputStream in =
                         new java.io.BufferedInputStream(Files.newInputStream(file), 8192)) {
                in.mark(HEAD_BYTES);
                byte[] h = new byte[HEAD_BYTES];
                int n = 0;
                for (int r; n < h.length && (r = in.read(h, n, h.length - n)) >= 0; ) n += r;
                in.reset();

                // PNG: 8-byte signature, then the IHDR chunk, whose first two fields
                // are the dimensions as big-endian 32-bit.
                if (n >= 24 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                        && isAscii(h, 12, "IHDR")) {
                    return positive(be32(h, 16), be32(h, 20));
                }
                // GIF: "GIF87a"/"GIF89a", then the logical screen size, LE 16-bit.
                if (n >= 10 && isAscii(h, 0, "GIF8")) {
                    return positive(le16(h, 6), le16(h, 8));
                }
                // WEBP: a RIFF container whose payload says which encoding it is.
                if (n >= 16 && isAscii(h, 0, "RIFF") && isAscii(h, 8, "WEBP")) {
                    return webpSize(h, n);
                }
                // JPEG: no fixed place at all - the size lives in a frame header that
                // can sit behind any amount of EXIF, ICC or thumbnail data, so the
                // segments have to be walked. Continues from the stream, past the SOI.
                if (n >= 2 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) {
                    skipFully(in, 2);
                    return jpegSize(in);
                }
                return null;
            } catch (IOException ex) {
                return null;
            }
        }

        // WEBP, from the 32-byte head: one of three payload chunks, each packing the
        // canvas size differently. VP8X is the extended format (the one an animation
        // or an alpha channel produces), VP8 the ordinary lossy one, VP8L lossless.
        private static int[] webpSize(byte[] h, int n) {
            if (n >= 30 && isAscii(h, 12, "VP8X")) {
                // Canvas size is stored minus one, as 24-bit little-endian.
                return positive(le24(h, 24) + 1, le24(h, 27) + 1);
            }
            if (n >= 30 && isAscii(h, 12, "VP8 ")) {
                // A 3-byte frame tag, then the 3-byte start code, then 14 bits of
                // width and 14 of height, each with 2 scaling bits above them.
                if ((h[23] & 0xFF) != 0x9D || (h[24] & 0xFF) != 0x01 || (h[25] & 0xFF) != 0x2A) {
                    return null;
                }
                return positive(le16(h, 26) & 0x3FFF, le16(h, 28) & 0x3FFF);
            }
            if (n >= 25 && isAscii(h, 12, "VP8L") && (h[20] & 0xFF) == 0x2F) {
                // 14 bits of width-1 then 14 of height-1, in 4 little-endian bytes.
                int bits = le16(h, 21) | (le16(h, 23) << 16);
                return positive((bits & 0x3FFF) + 1, ((bits >>> 14) & 0x3FFF) + 1);
            }
            return null;
        }

        // JPEG: walks the segments from just after the SOI to the first frame header
        // (SOF), which carries the size. Reached the image data without finding one ->
        // null, rather than a guess.
        private static int[] jpegSize(java.io.InputStream in) throws IOException {
            while (true) {
                // Segments are introduced by 0xFF; a run of them is padding.
                int b = u8(in);
                if (b != 0xFF) continue;
                int marker = u8(in);
                while (marker == 0xFF) marker = u8(in);

                // Standalone markers: no length, no payload.
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD9)) continue;

                int length = (u8(in) << 8) | u8(in);   // includes these two bytes
                if (length < 2) return null;
                if (isStartOfFrame(marker)) {
                    u8(in);                            // sample precision
                    int height = (u8(in) << 8) | u8(in);
                    int width = (u8(in) << 8) | u8(in);
                    return positive(width, height);
                }
                if (marker == 0xDA) return null;       // start of scan: pixels, no SOF
                skipFully(in, length - 2);
            }
        }

        // SOF0..SOF15 - baseline through lossless and hierarchical - minus the three
        // markers that share that range without being frame headers: DHT (0xC4),
        // JPG (0xC8) and DAC (0xCC).
        private static boolean isStartOfFrame(int marker) {
            return marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        }

        private static int u8(java.io.InputStream in) throws IOException {
            int b = in.read();
            if (b < 0) throw new java.io.EOFException("truncated image header");
            return b;
        }

        private static void skipFully(java.io.InputStream in, long count) throws IOException {
            long left = count;
            while (left > 0) {
                long skipped = in.skip(left);
                // skip() may legitimately do nothing, so fall back to reading a byte;
                // this way a stream that only ever returns 0 still terminates on EOF.
                if (skipped <= 0) { u8(in); left--; } else { left -= skipped; }
            }
        }

        private static boolean isAscii(byte[] b, int at, String want) {
            if (at + want.length() > b.length) return false;
            for (int i = 0; i < want.length(); i++) {
                if ((b[at + i] & 0xFF) != want.charAt(i)) return false;
            }
            return true;
        }

        private static int be32(byte[] b, int at) {
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                    | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }

        private static int le16(byte[] b, int at) {
            return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
        }

        private static int le24(byte[] b, int at) {
            return le16(b, at) | ((b[at + 2] & 0xFF) << 16);
        }

        // Both dimensions or nothing: a zero or negative size means the header was
        // not what it claimed to be, and reporting "0 x 0 pixels" would be worse
        // than saying the dimensions could not be read.
        private static int[] positive(int width, int height) {
            return (width > 0 && height > 0) ? new int[] { width, height } : null;
        }
    }

    // What an audio include is, read out of the file's own header: the format, how it was
    // sampled, and how long it plays.
    //
    // ImageHeader's counterpart, and for the same reasons: header arithmetic only, no
    // decoder, no native library, nothing that behaves differently in the browser build -
    // and the bytes JRock sends are untouched by all of it, so these numbers are only ever
    // for the log. The byte-order helpers ARE ImageHeader's: a nested class can reach a
    // sibling's private statics, so le16 and isAscii exist once and not twice.
    //
    // Both of the formats the include filter offers, which are the two the request can
    // carry at all:
    //
    //   WAV  the RIFF chunks, walked for "fmt " (how it was sampled) and "data" (how much
    //        of it there is), so the length is exact.
    //   MP3  the first frame header after any ID3v2 tag - MPEG version, layer, bitrate,
    //        sample rate. The length follows from the file size at that bitrate: exact for
    //        a constant-bitrate file, an estimate for a variable one, and nothing in the
    //        header says which, so it is always called one.
    //
    // Anything else, or a header that does not say: null, and the caller reports the byte
    // count alone - which is the honest answer and is what the file is charged by anyway.
    // That is also the deal this class is kept to: a hundred lines of arithmetic and not
    // one more, so the moment a format needs more than that (a VBR header for an exact MP3
    // length, an MP4 box walk for an m4a's duration) the answer is the byte count, not a
    // decoder inside JRock.
    private static final class AudioHeader {
        // Enough for a WAV's leading chunks, and for an ID3v2 tag's own length plus the
        // frame sync behind it. Nothing here reads past the front of the file.
        private static final int HEAD_BYTES = 4096;

        // Layer III bitrates, in kbps, indexed by the header's 4-bit field: one table for
        // MPEG 1, one for MPEG 2 and 2.5. Index 0 (free) and 15 (bad) are not bitrates.
        private static final int[] MP3_V1 = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128,
                160, 192, 224, 256, 320, 0 };
        private static final int[] MP3_V2 = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80,
                96, 112, 128, 144, 160, 0 };

        // Sample rates by MPEG version (1, 2, 2.5) and the header's 2-bit field.
        private static final int[][] MP3_RATES = { { 44100, 48000, 32000 },
                { 22050, 24000, 16000 }, { 11025, 12000, 8000 } };

        private AudioHeader() { }   // static-only: there is no per-file state to hold

        // One line about the file, or null when its header says nothing this can read.
        // bytes is the file's size, which is what the two estimates are made from.
        static String describe(Path file, long bytes) {
            try {
                byte[] h = head(file, HEAD_BYTES);
                if (ImageHeader.isAscii(h, 0, "RIFF") && ImageHeader.isAscii(h, 8, "WAVE")) {
                    return wav(h, bytes);
                }
                // An MP4 (an m4a, say, handed to automationInclude) is stopped here
                // rather than read: its boxes can spell something the frame scan below
                // would take for a sync, and a made-up "MP3" line is worse than none.
                if (ImageHeader.isAscii(h, 4, "ftyp")) return null;
                return mp3(h, bytes);
            } catch (IOException ex) {
                return null;
            }
        }

        // The first count bytes, or as many as there are.
        private static byte[] head(Path file, int count) throws IOException {
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[count];
                int n = 0;
                for (int r; n < count && (r = in.read(buf, n, count - n)) >= 0; ) n += r;
                return (n == count) ? buf : java.util.Arrays.copyOf(buf, n);
            }
        }

        // WAV: chunk headers from byte 12 on, each an id, a little-endian length and a
        // payload padded to an even boundary. "fmt " says how it was sampled, "data" how
        // much of it follows - and "data" is where the walk stops, that payload being the
        // recording itself.
        private static String wav(byte[] h, long bytes) {
            int channels = 0, bits = 0, byteRate = 0;
            long rate = 0, dataBytes = -1;
            for (int at = 12; at + 8 <= h.length; ) {
                long size = le32(h, at + 4);
                int body = at + 8;
                if (ImageHeader.isAscii(h, at, "fmt ") && body + 16 <= h.length) {
                    channels = ImageHeader.le16(h, body + 2);
                    rate = le32(h, body + 4);
                    byteRate = (int) le32(h, body + 8);
                    bits = ImageHeader.le16(h, body + 14);
                } else if (ImageHeader.isAscii(h, at, "data")) {
                    dataBytes = size;
                    break;
                }
                if (size <= 0 || size > h.length) break;   // nonsense, or past what was read
                at = body + (int) size + (int) (size & 1);
            }
            if (rate <= 0 || channels <= 0) return null;
            String about = "WAV, " + kHz(rate)
                    + ", " + (channels == 1 ? "mono" : channels == 2 ? "stereo" : channels
                            + " channels")
                    + (bits > 0 ? ", " + bits + "-bit" : "");
            if (byteRate <= 0) return about;
            about += ", " + fmtNum(byteRate * 8L / 1000) + " kbps";
            // 44 bytes is the smallest WAV header there is, so that estimate is only
            // reached when a tag chunk pushed "data" past the bytes read above.
            long audio = (dataBytes > 0) ? dataBytes : bytes - 44;
            return (audio <= 0) ? about
                    : about + ", " + clock(audio / (double) byteRate)
                            + (dataBytes > 0 ? "" : " (estimated)");
        }

        // MP3: the first frame header, which is 11 bits of 1 followed by the fields. Only
        // Layer III is read - that is what an .mp3 is - and a byte pair that looks like a
        // sync but says something else is skipped rather than believed.
        private static String mp3(byte[] h, long bytes) {
            int at = 0;
            // An ID3v2 tag comes first when there is one: 10 bytes, then a length written
            // 7 bits to the byte (the 8th is always 0, so a tag cannot spell a frame sync).
            if (ImageHeader.isAscii(h, 0, "ID3") && h.length >= 10) {
                at = 10 + (((h[6] & 0x7F) << 21) | ((h[7] & 0x7F) << 14)
                        | ((h[8] & 0x7F) << 7) | (h[9] & 0x7F));
            }
            for (; at >= 0 && at + 4 <= h.length; at++) {
                if ((h[at] & 0xFF) != 0xFF || (h[at + 1] & 0xE0) != 0xE0) continue;
                int version = (h[at + 1] >> 3) & 3;     // 3 = MPEG 1, 2 = MPEG 2, 0 = 2.5
                int layer = (h[at + 1] >> 1) & 3;       // 1 = Layer III
                int bitrateIx = (h[at + 2] >> 4) & 15;
                int rateIx = (h[at + 2] >> 2) & 3;
                if (layer != 1 || version == 1 || rateIx == 3
                        || bitrateIx == 0 || bitrateIx == 15) continue;
                int kbps = (version == 3 ? MP3_V1 : MP3_V2)[bitrateIx];
                long rate = MP3_RATES[version == 3 ? 0 : version == 2 ? 1 : 2][rateIx];
                String about = "MP3 (MPEG " + (version == 3 ? "1" : version == 2 ? "2" : "2.5")
                        + " Layer III), " + kHz(rate)
                        + ", " + (((h[at + 3] >> 6) & 3) == 3 ? "mono" : "stereo")
                        + ", " + kbps + " kbps";
                return (bytes <= at) ? about
                        : about + ", " + clock((bytes - at) * 8.0 / (kbps * 1000))
                                + " at that bitrate";
            }
            return null;
        }

        private static long le32(byte[] b, int at) {
            return ImageHeader.le16(b, at) | ((long) ImageHeader.le16(b, at + 2) << 16);
        }

        // 44100 -> "44.1 kHz", 48000 -> "48 kHz": nobody reads a sample rate in hertz.
        private static String kHz(long rate) {
            return (rate % 1000 == 0) ? (rate / 1000) + " kHz"
                    : String.format(java.util.Locale.ROOT, "%.1f kHz", rate / 1000.0);
        }

        // Seconds as m:ss, or h:mm:ss once there is an hour of it.
        private static String clock(double seconds) {
            long total = Math.round(seconds);
            return (total >= 3600)
                    ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d",
                            total / 3600, (total % 3600) / 60, total % 60)
                    : String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60);
        }
    }

    // Rasterises a PDF to one PNG per page with Ghostscript, then includes each
    // produced page. Output files are written under JRock/gs-pdf/, named
    // "<pdfname>.gs.NNN.png". If Ghostscript isn't on PATH, points the user to the
    // download page and does nothing else.
    //
    // Page images rather than extracted text: see the filters in showIncludeDialog for
    // why the text half of this was taken out again.
    //
    // Runs on a background thread (see showIncludeDialog): it waits for Ghostscript,
    // which on a large PDF takes a long time. Anything touching a widget goes
    // through onEdt().
    private static void includePdf(JFrame frame, JTextArea input, LogView log,
                                   boolean extend, Path pdf) {
        String gs = findGhostscript();
        if (gs == null) {
            ghostscriptMissing(frame, log, "convert PDFs");
            return;
        }

        // Output goes to JRock/gs-pdf/. Page files are named "<pdfname>.gs.NNN.png"
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
        String suffix = ".png";
        // Ghostscript expands %03d in the output path to the page number.
        String outPattern = outDir.resolve(prefix + "%03d" + suffix).toString();

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(gs);
        // No -q: Ghostscript's own progress ("Processing pages 1 through N.", then a
        // "Page N" as each one is finished) is the only honest answer to "is this
        // working, and how far along is it?" on a long document.
        cmd.add("-dNOPAUSE"); cmd.add("-dBATCH"); cmd.add("-dSAFER");
        boolean windowed = isWindowedGhostscript(gs);
        if (!windowed) {
            // Console build: the messages are read back through the pipe and echoed
            // into the log below. They are asked for on stderr rather than stdout
            // because a pipe makes the C runtime buffer stdout in 4 KB blocks -
            // progress would then arrive in bursts, or all at once at the end, which
            // is precisely what it is there to avoid. stderr is unbuffered, and
            // redirectErrorStream below reads both as one. Page output is unaffected:
            // -o writes that to files, not to stdout.
            cmd.add("-sstdout=%stderr");
        }
        // The windowed build gets no -sstdout at all, deliberately: it honours the
        // redirect, and its own window - the whole reason for preferring it - would
        // then sit there empty.
        cmd.add("-sDEVICE=png16m");
        cmd.add("-r" + imagesDpi);                  // page raster resolution (Configure)
        cmd.add("-o"); cmd.add(outPattern);
        cmd.add(pdf.toAbsolutePath().toString());

        // Logged before the process is started, and now actually seen: this method is
        // off the EDT, so the pane repaints while Ghostscript works.
        log.gray("Converting PDF with Ghostscript: " + String.join(" ", cmd));
        if (windowed) {
            // Said plainly, because the log falls silent for the whole conversion and
            // the window is somewhere else on screen - possibly behind this one.
            log.gray("Ghostscript reports its progress in its own window; it closes "
                    + "when the conversion finishes.");
        }
        int code;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain output so the process can't block, and echo it to the log. The
            // windowed build writes nothing here - its messages go to its window - so
            // this reads to end-of-stream and logs nothing, which is also what keeps
            // the wait below from starting before the process has finished.
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
            log.gray("Ghostscript exited with code " + code + "; no pages included."
                    // Nothing was echoed, so say where the reason went.
                    + (windowed ? " It reported the reason in its own window." : ""));
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
            final Path p = page;
            // Inserts the token into the prompt's document, so: on the EDT.
            boolean[] added = new boolean[1];
            onEdt(() -> added[0] = includeOne(input, log, extend, p, "img", true));
            if (added[0]) inserted++;
        }
        log.gray("Inserted " + inserted + " new @img token(s) for "
                + pdf.getFileName() + ".");
        // No requestFocusInWindow here: the caller's done() puts focus back on the
        // prompt once the whole batch is finished, on the EDT where it belongs.
    }

    // Finds Ghostscript on PATH. Returns the command to run, or null if not found.
    //
    // On Windows the WINDOWED build (gswin64.exe) is preferred over the console one
    // (gswin64c.exe): it puts up its own window and reports its progress there, which
    // is what a long conversion needs - a native window that is visibly working,
    // rather than a hidden console. The console builds stay as the fallback, for an
    // installation that ships only those. Elsewhere there is one "gs" and no choice.
    private static String findGhostscript() { return findGhostscript(true); }

    // The same, asked the other way round: windowed=false prefers the CONSOLE build,
    // whose messages come back on a pipe and can therefore be read (see pdfPageCount).
    // Both lists end in whatever else is installed, so a machine carrying only one of
    // the two builds still gets an answer - and a caller that needed the other kind
    // finds out by not getting the number it asked for.
    private static String findGhostscript(boolean windowed) {
        String[] names = isWindows()
                ? (windowed
                    ? new String[] { "gswin64", "gswin32", "gswin64c", "gswin32c", "gs" }
                    : new String[] { "gswin64c", "gswin32c", "gswin64", "gswin32", "gs" })
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

    // Says Ghostscript is missing - in the log, in a dialog, and by opening the download
    // page, which is the one thing there is to do about it. "task" finishes the sentence
    // "Ghostscript is required to <task>", so the message names what was being attempted
    // rather than PDFs in general.
    //
    // Safe from either thread: the dialog goes through onEdt, which runs it straight away
    // when the caller is already on the EDT.
    private static void ghostscriptMissing(JFrame frame, LogView log, String task) {
        // Name the executable the current platform actually looks for.
        String exe = isWindows() ? "gswin64" : "gs";
        log.gray("Ghostscript (" + exe + ") was not found on PATH. Install it from "
                + "https://ghostscript.com/ to " + task + ", then try again.");
        onEdt(() -> {
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Ghostscript (" + exe + ") is required to " + task + " but was not "
                        + "found on your PATH.\n\nInstall it from https://ghostscript.com/ "
                        + "and restart JRock (or your shell) so " + exe + " is on PATH.",
                    "Ghostscript not found", javax.swing.JOptionPane.WARNING_MESSAGE);
            openUrl("https://ghostscript.com/");
        });
    }

    // Whether that command is one of Ghostscript's windowed Windows builds.
    //
    // The naming is Ghostscript's own and has been stable for decades: gswin64 is the
    // windowed build, gswin64c the console one - the trailing "c". It decides two
    // things below: that JRock must not redirect gs's messages (they are what the
    // window shows), and that it therefore cannot echo them into the log.
    private static boolean isWindowedGhostscript(String command) {
        String name = Paths.get(command).getFileName().toString().toLowerCase();
        if (name.endsWith(".exe")) name = name.substring(0, name.length() - 4);
        return name.startsWith("gswin") && !name.endsWith("c");
    }

    // ---- Duplex scan merge (Ghostscript) -----------------------------------
    // Merges the two passes of a double-sided scan into one PDF.
    //
    // A sheet-feed scanner with no duplex unit takes a two-sided batch in two goes: the
    // first pass gives the fronts in order, and then the stack goes back into the feeder
    // as it came out of it - so the second pass gives the backs in reverse, the back of
    // the last sheet first. Two PDFs, neither of them readable on its own, and
    // interleaving them by hand is a job nobody does twice.
    //
    // Which is all this is: front 1, back N, front 2, back N-1, ... written out as one
    // document. By the Ghostscript that PDF includes already need, so there is no pdftk,
    // no Python and nothing else to install.
    //
    // Which of the two picked files is the front pass is decided by their NAMES, not by
    // the order the chooser hands them over in - see frontThenBack for why that order is
    // not one.
    //
    // First in the window menu because it is the one item there that is a tool rather
    // than a setting - and the one somebody comes to this menu looking for.
    //
    // Runs on the EDT as far as its two dialogs; the counting and the merge, which wait
    // for a subprocess each, go to a worker.
    private static void mergeDuplexScans(JFrame frame, LogView log) {
        String gs = findGhostscript();              // windowed where there is one
        if (gs == null) {
            ghostscriptMissing(frame, log, "merge duplex scans");
            return;
        }

        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(scanChooserDir.start());
        chooser.setDialogTitle(
                "Merge duplex scans: both passes (the earlier name is the front)");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "PDF files (*.pdf)", "pdf"));
        chooser.setMultiSelectionEnabled(true);
        // Both names in the File Name box, in quotes, is how a touch device selects two
        // files - a tap selects one and there is no Shift to hold (see addCopyPasteMenu).
        addCopyPasteMenu(findFileNameField(chooser), log);
        if (chooser.showOpenDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        scanChooserDir.remember(chooser);

        java.io.File[] picked = chooser.getSelectedFiles();
        int count = (picked == null) ? 0 : picked.length;
        if (count != 2) {
            duplexWarn(frame, "Pick exactly two PDF files - both passes of the same "
                    + "batch. " + count + " were selected.");
            return;
        }
        // Sorted by name, front first (see frontThenBack). Both are logged before
        // anything runs, and the front one names the merged file, so a pair the names put
        // the wrong way round shows before the merge as well as in it.
        Path[] passes = frontThenBack(
                picked[0].toPath().toAbsolutePath().normalize(),
                picked[1].toPath().toAbsolutePath().normalize());
        final Path front = passes[0];
        final Path back  = passes[1];
        if (front.equals(back)) {
            duplexWarn(frame, "The front pass and the back pass are the same file.");
            return;
        }

        javax.swing.JFileChooser save =
                new javax.swing.JFileChooser(scanChooserDir.start());
        save.setDialogTitle("Save the merged PDF as");
        save.setSelectedFile(scanChooserDir.startFile(mergedScanName(front)));
        save.setAcceptAllFileFilterUsed(false);
        save.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "PDF files (*.pdf)", "pdf"));
        addCopyPasteMenu(findFileNameField(save), log);
        if (save.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        scanChooserDir.remember(save);
        // The dialog says .pdf; a name typed without it gets it anyway.
        final Path out = withExtension(save.getSelectedFile().toPath(), "pdf")
                .toAbsolutePath().normalize();
        if (out.equals(front) || out.equals(back)) {
            duplexWarn(frame, "That name is one of the two scans, which the merge reads "
                    + "as it writes. Choose another.");
            return;
        }

        log.gray("Duplex merge, front pass (the earlier name): " + front);
        log.gray("Duplex merge, back pass (in reverse): " + back);
        final String command = gs;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                runDuplexMerge(frame, log, command, front, back, out);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();   // surfaces anything doInBackground threw
                } catch (Exception ex) {
                    log.gray("The duplex merge failed: " + ex.getMessage());
                }
                log.gray("");   // closes the block, one per merge
            }
        }.execute();
    }

    // The two passes in the order their names put them: the earlier name is the front.
    //
    // NOT the order the file chooser handed them over in, which this used to trust and
    // which is not an order at all. JFileChooser.getSelectedFiles() comes back in
    // whatever order the selection was made or the File Name box was parsed in, and it
    // differs by look-and-feel and by how the files were picked - so a box reading
    // "doc.pdf" "doc-2.pdf" could hand back doc-2.pdf first and the merge would
    // interleave the whole document inside out, every front against the wrong back. The
    // names are the one thing about a scanner's output that IS in order: it writes the
    // first pass before the second, so the earlier name holds the fronts.
    //
    // Compared without the extension first, because a plain compare of the whole names
    // gets exactly the pair above backwards: "doc-2.pdf" sorts before "doc.pdf", '-'
    // being 0x2D and '.' 0x2E. The extension is then the tie-break, and the whole path
    // after it, so the answer is total and the same every time.
    private static Path[] frontThenBack(Path a, Path b) {
        int order = naturalCompare(stemOf(a), stemOf(b));
        if (order == 0) {
            order = naturalCompare(a.getFileName().toString(), b.getFileName().toString());
        }
        if (order == 0) order = a.toString().compareTo(b.toString());
        return (order <= 0) ? new Path[] { a, b } : new Path[] { b, a };
    }

    // Compares two names the way a listing does: runs of digits as numbers, everything
    // else character by character with case ignored (and then applied, so the order is
    // total rather than "equal" for two names that differ).
    //
    // Numbers, because a scanner that does not pad its counter writes scan9.pdf and
    // scan10.pdf, and a string compare puts the tenth sheet before the ninth - which as
    // the choice of front pass means the merge is wrong once every ten sheets. Leading
    // zeros are dropped and the digits compared by length first, so this holds for a
    // counter longer than a long as well as for a padded one.
    private static int naturalCompare(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int startA = i;
                int startB = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String da = a.substring(startA, i).replaceFirst("^0+(?=.)", "");
                String db = b.substring(startB, j).replaceFirst("^0+(?=.)", "");
                if (da.length() != db.length()) return da.length() - db.length();
                int digits = da.compareTo(db);
                if (digits != 0) return digits;
                continue;
            }
            int one = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (one != 0) return one;
            i++;
            j++;
        }
        // One name ran out: the shorter is the earlier ("scan" before "scan-2").
        int rest = (a.length() - i) - (b.length() - j);
        return (rest != 0) ? rest : a.compareTo(b);
    }

    // Counts the pages of both passes, then interleaves them into out with Ghostscript.
    //
    // Runs off the EDT: it waits for two or three subprocesses, and on a long document
    // the merge is minutes. Everything it says goes into the log, which is why it can;
    // a refusal also gets a dialog, through duplexNote.
    private static void runDuplexMerge(JFrame frame, LogView log, String gs,
                                       Path front, Path back, Path out) {
        // The page count is asked of the CONSOLE build, whose answer comes back on a
        // pipe; the merge itself runs the windowed one where there is one, for the
        // progress window. One installation, two executables.
        String gsConsole = findGhostscript(false);
        int sheets = pdfPageCount(gsConsole, front, log);
        int backs  = pdfPageCount(gsConsole, back, log);
        if (sheets < 1 || backs < 1) {
            duplexNote(frame, log, "Ghostscript would not say how many pages "
                    + (sheets < 1 ? front : back).getFileName() + " has, so its pages "
                    + "cannot be paired with the other pass's.");
            return;
        }
        if (sheets != backs) {
            duplexNote(frame, log, "The two passes have different page counts: "
                    + front.getFileName() + " has " + sheets + ", " + back.getFileName()
                    + " has " + backs + ". Both passes feed the same sheets, so one of "
                    + "these is not the file it was taken for - merging them would put "
                    + "the wrong back on every front.");
            return;
        }

        // Both passes named relative to the folder they share, where they share one.
        // Length is the whole reason: every sheet names both files again, and Windows
        // stops accepting a command line at 32767 characters - which a few hundred
        // sheets with long paths would reach.
        Path dir = front.getParent();
        boolean relative = dir != null && dir.equals(back.getParent());
        String frontArg = relative ? front.getFileName().toString() : front.toString();
        String backArg  = relative ? back.getFileName().toString()  : back.toString();

        boolean windowed = isWindowedGhostscript(gs);
        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(gs);
        cmd.add("-dNOPAUSE"); cmd.add("-dBATCH"); cmd.add("-dSAFER");
        if (!windowed) cmd.add("-sstdout=%stderr");   // see includePdf for why
        cmd.add("-sDEVICE=pdfwrite");
        cmd.add("-o"); cmd.add(out.toString());
        // The shuffle itself. Ghostscript reads its arguments in order, so a
        // -dFirstPage / -dLastPage pair in front of a named file picks one page out of
        // that file - and pdfwrite writes the pages in the order they are interpreted.
        // Front 1, back N, front 2, back N-1, ... which is sheet 1 front, sheet 1 back,
        // sheet 2 front, ... because the second pass came out in reverse.
        for (int sheet = 1; sheet <= sheets; sheet++) {
            cmd.add("-dFirstPage=" + sheet);
            cmd.add("-dLastPage=" + sheet);
            cmd.add(frontArg);
            int reverse = sheets + 1 - sheet;
            cmd.add("-dFirstPage=" + reverse);
            cmd.add("-dLastPage=" + reverse);
            cmd.add(backArg);
        }
        int length = String.join(" ", cmd).length();
        if (isWindows() && length > 30_000) {
            duplexNote(frame, log, sheets + " sheets need a " + fmtNum(length)
                    + "-character command line, which is past what Windows accepts "
                    + "(32767). Put the two scans in a folder with a short path, or "
                    + "scan the batch in two halves.");
            return;
        }

        // Not the command line itself: it names both files once per sheet, which for a
        // full feeder is thousands of characters of the same two names.
        log.gray("Merging with Ghostscript (" + gs + "): " + front.getFileName()
                + " pages 1.." + sheets + " interleaved with " + back.getFileName()
                + " pages " + sheets + "..1, into " + out);
        if (windowed) {
            // Said plainly, because the log falls silent for the whole merge and the
            // window is somewhere else on screen - possibly behind this one.
            log.gray("Ghostscript reports its progress in its own window; it closes "
                    + "when the merge finishes.");
        }
        int code;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (relative) pb.directory(dir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drained so the process cannot block on a full pipe, and echoed. The
            // windowed build writes nothing here - its messages go to its window.
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) log.gray("gs: " + line.trim());
                }
            }
            code = p.waitFor();
        } catch (IOException ex) {
            duplexNote(frame, log, "Ghostscript failed to run: " + ex.getMessage());
            return;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            duplexNote(frame, log, "The merge was interrupted; " + out
                    + " is whatever Ghostscript had written by then.");
            return;
        }
        if (code != 0) {
            duplexNote(frame, log, "Ghostscript exited with code " + code + ", so " + out
                    + " is not a finished merge."
                    + (windowed ? " It reported the reason in its own window." : ""));
            return;
        }

        long size = -1;
        try {
            size = Files.size(out);
        } catch (IOException ignore) { /* the line below simply says less */ }
        log.gray("Merged " + (2 * sheets) + " pages into " + out
                + (size < 0 ? "" : " (" + fmtNum(size) + " bytes)"));
        // Said out loud, because the shell one-liner this replaces ended in an rm.
        log.gray("The two scans are untouched; look through the merge before deleting "
                + "them.");
    }

    // The default name for a merge: "scan0166.Merged.pdf" beside "scan0166.pdf". The
    // front pass names it because its page 1 is the merged document's page 1.
    private static String mergedScanName(Path front) {
        String name = front.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + ".Merged.pdf";
    }

    // How many pages a PDF has, or -1 when Ghostscript would not say.
    //
    // Asked of the console build, whose answer comes back on a pipe. Two ways, because
    // the one that is instant is the one a modern Ghostscript dropped:
    //
    //   1. runpdfbegin / pdfpagecount - a PostScript one-liner that reads the page tree
    //      and looks at no page at all, so it answers at once however long the document
    //      is. It belongs to the PostScript PDF interpreter, which Ghostscript 10
    //      replaced with one written in C, and there it fails.
    //   2. Failing that, interpret the file with no output device and read the number
    //      out of "Processing pages 1 through N." - a line every version has printed
    //      for decades. It costs a pass over the whole document, which on a scan of
    //      photographs is a moment rather than nothing.
    //
    // The file is handed to the first one as a Ghostscript string parameter rather than
    // written into the PostScript, so a Windows path full of backslashes needs no
    // escaping. -dNOSAFER goes with it because SAFER will not let a PostScript program
    // open a file at all - not even one the user picked in a chooser a moment ago. The
    // second names the file as an ordinary input, which SAFER allows.
    private static int pdfPageCount(String gsConsole, Path pdf, LogView log) {
        if (gsConsole == null) return -1;
        String path = pdf.toAbsolutePath().toString();
        int quick = gsNumber(java.util.Arrays.asList(
                gsConsole, "-q", "-dNODISPLAY", "-dBATCH", "-dNOSAFER",
                "-sJRockPdf=" + path,
                "-c", "JRockPdf (r) file runpdfbegin pdfpagecount = quit"), null);
        if (quick > 0) return quick;
        log.gray("Ghostscript would not count the pages of " + pdf.getFileName()
                + " from its page tree (a version 10 build cannot); reading the file "
                + "through to count them instead.");
        return gsNumber(java.util.Arrays.asList(
                gsConsole, "-dNODISPLAY", "-dBATCH", "-dNOPAUSE", "-dSAFER", path),
                "Processing pages 1 through ");
    }

    // Runs a Ghostscript command that is expected to name a number, and returns the
    // last number it named, or -1.
    //
    // needle null: the number is a line of its own, which is what "=" prints. Otherwise
    // it follows that text on a line, as in "Processing pages 1 through 12.". Output and
    // errors are read as one stream and nothing is echoed: both of these are questions
    // put to Ghostscript, and the first one is expected to fail half the time.
    private static int gsNumber(java.util.List<String> cmd, String needle) {
        int found = -1;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String text = line.trim();
                    int at = (needle == null) ? 0 : text.indexOf(needle);
                    if (at < 0) continue;
                    int number = leadingInt(needle == null
                            ? text : text.substring(at + needle.length()));
                    if (number > 0) found = number;
                }
            }
            p.waitFor();
        } catch (IOException ex) {
            return -1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return -1;
        }
        return found;
    }

    // The number at the start of text, or -1 when it does not start with one. Anything
    // after the digits is ignored: Ghostscript's line ends in a full stop.
    private static int leadingInt(String text) {
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        if (end == 0) return -1;
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException ex) {
            return -1;   // more digits than an int holds, which no document has
        }
    }

    // Says why a merge did not happen, in the log and in a dialog. Called from the
    // worker, so the dialog goes through onEdt.
    private static void duplexNote(JFrame frame, LogView log, String message) {
        log.gray(message);
        onEdt(() -> duplexWarn(frame, message));
    }

    // The same dialog on its own, for the two dialogs' own refusals: those happen on
    // the EDT, before there is anything in the log worth a line.
    private static void duplexWarn(JFrame frame, String message) {
        javax.swing.JOptionPane.showMessageDialog(frame, message,
                "Merge duplex scans", javax.swing.JOptionPane.WARNING_MESSAGE);
    }

    // ---- RTF as Markdown ---------------------------------------------------
    // Converts an RTF file to Markdown, writes it under JRock/rtf-md/ as
    // "<rtfname>.md", and includes that file as an ordinary @txt token - so from the
    // prompt's point of view the model is simply reading a text file.
    //
    // Nothing external is needed, unlike the PDF path: the reader is the JDK's own
    // javax.swing.text.rtf.RTFEditorKit, the same one a JTextPane uses, so this works
    // on a bare JVM with nothing installed.
    // Markdown rather than flat text because RTF's whole point is the formatting:
    // headings, bold and italic survive as markup a model reads as structure,
    // instead of being thrown away. See RtfMarkdown for what is kept.
    //
    // Runs on a background thread (see showIncludeDialog); the include itself, which
    // touches the prompt's document, goes through onEdt().
    private static void includeRtfAsMarkdown(JTextArea input, LogView log,
                                             boolean extend, Path rtf) {
        log.gray("Converting RTF to Markdown with Swing's RTF reader: " + rtf);

        String markdown;
        try {
            markdown = RtfMarkdown.of(rtf);
        } catch (IOException | BadLocationException | RuntimeException ex) {
            log.gray("Could not read RTF " + rtf.getFileName() + ": " + ex.getMessage());
            return;
        }
        if (markdown.isBlank()) {
            // Also what a file that isn't really RTF looks like: the reader wants the
            // "{\rtf1" header and control words, and finds no text without them. It
            // does not complain about that, so this line has to cover both cases.
            log.gray("No text found in " + rtf.getFileName() + "; nothing included. "
                    + "(An empty document - or a file that is not really RTF.)");
            return;
        }

        // Output goes to JRock/rtf-md/, named "<rtfname>.md" - the RTF's full name
        // kept as the prefix (as with gs-pdf/), so "notes.rtf" becomes "notes.rtf.md"
        // and two RTFs of the same stem can't overwrite each other's Markdown.
        Path outDir = rtfMdDir();
        Path out = outDir.resolve(rtf.getFileName().toString() + ".md");
        try {
            Files.createDirectories(outDir);
            Files.write(out, markdown.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.gray("Could not write " + out + ": " + ex.getMessage());
            return;
        }
        log.gray("Markdown written to " + out);

        boolean[] added = new boolean[1];
        // Inserts the token into the prompt's document, so: on the EDT.
        onEdt(() -> added[0] = includeOne(input, log, extend, out, "txt", false));
        log.gray("Inserted " + (added[0] ? 1 : 0) + " new @txt token(s) for "
                + rtf.getFileName() + ".");
    }

    // RTF -> Markdown, with the JDK's RTF reader doing the parsing.
    //
    // The reader hands back a styled document: paragraphs of runs, each run carrying
    // the attributes RTFEditorKit understood (bold, italic, font size, ...). That is
    // all this needs, and all it uses - the mapping is deliberately small, because
    // every guess made here is a guess about someone else's document:
    //
    //   paragraph          -> a block, separated by a blank line
    //   bold / italic      -> **bold**, *italic*, ***both***
    //   a larger font size -> a heading, #/##/### by how much larger (short lines only)
    //   a bullet character -> a "-" list item ("1." etc. are kept as they are)
    //
    // A line break (RTF's \line) is a paragraph of its own to the reader, and comes
    // out as one here: a blank line, which in Markdown reads the same way.
    //
    // Not attempted: tables (RTF's table rows reach the reader as ordinary
    // paragraphs), embedded images, colours, alignment. Underline has no Markdown of
    // its own and is left as plain text rather than invented into emphasis.
    //
    // Deliberately static and free of any widget: it reads a file and returns a
    // String, which is also what makes it testable without a GUI.
    private static final class RtfMarkdown {

        // A paragraph longer than this is never a heading, however large its font: a
        // heading is a line, not a page. Without it, a document set in one big face
        // would come back as nothing but headings.
        private static final int MAX_HEADING_CHARS = 120;

        // How far above the body font size (in points) a paragraph has to be for h1
        // and h2. Anything larger than the body at all is at least an h3.
        private static final int H1_POINTS_OVER_BODY = 6;
        private static final int H2_POINTS_OVER_BODY = 3;

        // The characters word processors leave in the text where a bullet belongs.
        // RTF has list *markup* too, but Word and friends also write the bullet as a
        // literal character followed by a tab, and that is what the reader delivers.
        //
        // Written as escapes, and kept to characters that are only ever bullets: the
        // source file stays ASCII (so no compiler's default encoding can quietly
        // change this list), and a paragraph opening with a section sign and a number
        // keeps both instead of losing them to a list marker.
        private static final java.util.regex.Pattern BULLET = java.util.regex.Pattern.compile(
                "^ *[\\u2022\\u2023\\u2043\\u00b7\\u2219\\u25aa\\u25cf\\u25e6] *");
        // "1." / "12)" and the like: already Markdown, so only the spacing is redone.
        private static final java.util.regex.Pattern NUMBERED =
                java.util.regex.Pattern.compile("^ *(\\d{1,3}[.)]) +");

        private RtfMarkdown() { }

        /** The Markdown for one RTF file. Never null; empty when the RTF has no text. */
        static String of(Path rtf) throws IOException, BadLocationException {
            javax.swing.text.rtf.RTFEditorKit kit = new javax.swing.text.rtf.RTFEditorKit();
            javax.swing.text.DefaultStyledDocument doc =
                    new javax.swing.text.DefaultStyledDocument();
            try (java.io.InputStream in = Files.newInputStream(rtf)) {
                kit.read(in, doc, 0);
            }
            return markdown(paragraphs(doc));
        }

        /** One styled stretch of text: what the RTF said about it, reduced to this. */
        private static final class Run {
            final String text;
            final boolean bold;
            final boolean italic;
            final int size;

            Run(String text, boolean bold, boolean italic, int size) {
                this.text = text;
                this.bold = bold;
                this.italic = italic;
                this.size = size;
            }

            boolean sameStyleAs(Run other) {
                return bold == other.bold && italic == other.italic && size == other.size;
            }
        }

        // The document as paragraphs of runs.
        //
        // Walked by offset rather than by element tree: getParagraphElement is the
        // document's own answer to "which paragraph is this character in", so it holds
        // whatever the reader nested the content in.
        private static List<List<Run>> paragraphs(javax.swing.text.DefaultStyledDocument doc)
                throws BadLocationException {
            List<List<Run>> paragraphs = new ArrayList<>();
            int length = doc.getLength();
            int at = 0;
            while (at <= length) {
                javax.swing.text.Element paragraph = doc.getParagraphElement(at);
                List<Run> runs = new ArrayList<>();
                collect(doc, paragraph, runs);
                paragraphs.add(runs);
                int end = paragraph.getEndOffset();
                if (end <= at) break;   // a zero-length paragraph would loop for ever
                at = end;
            }
            return paragraphs;
        }

        // Appends the runs under one element, descending to the leaves (the styled
        // stretches) and merging neighbours the RTF happened to split but styled
        // identically - "**a****b**" is not the bold "ab" any Markdown reader wants.
        private static void collect(javax.swing.text.DefaultStyledDocument doc,
                                    javax.swing.text.Element element, List<Run> runs)
                throws BadLocationException {
            if (!element.isLeaf()) {
                for (int i = 0; i < element.getElementCount(); i++) {
                    collect(doc, element.getElement(i), runs);
                }
                return;
            }
            int start = element.getStartOffset();
            int end = Math.min(element.getEndOffset(), doc.getLength());
            if (end <= start) return;
            String text = text(doc.getText(start, end - start));
            if (text.isEmpty()) return;

            javax.swing.text.AttributeSet a = element.getAttributes();
            Run run = new Run(text, StyleConstants.isBold(a), StyleConstants.isItalic(a),
                    StyleConstants.getFontSize(a));
            int last = runs.size() - 1;
            if (last >= 0 && runs.get(last).sameStyleAs(run)) {
                runs.set(last, new Run(runs.get(last).text + run.text,
                        run.bold, run.italic, run.size));
            } else {
                runs.add(run);
            }
        }

        // The characters of a run that are text rather than structure. A tab becomes a
        // space (a leading tab in Markdown is a code block), and the control characters
        // an RTF can carry - a form feed, say - are dropped. The newline ending every
        // paragraph is left to be trimmed off with the rest of its whitespace below.
        private static String text(String raw) {
            StringBuilder out = new StringBuilder(raw.length());
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '\t') out.append(' ');
                else if (c == '\n' || c >= ' ') out.append(c);
            }
            return out.toString();
        }

        private static String markdown(List<List<Run>> paragraphs) {
            int bodySize = bodySize(paragraphs);
            StringBuilder md = new StringBuilder();
            boolean previousWasListItem = false;
            for (List<Run> paragraph : paragraphs) {
                String block = block(paragraph, bodySize);
                if (block.isEmpty()) continue;
                boolean listItem = block.startsWith("- ") || NUMBERED.matcher(block).find();
                if (md.length() > 0 && !(listItem && previousWasListItem)) {
                    md.append('\n');   // blank line between blocks, but not within a list
                }
                md.append(block).append('\n');
                previousWasListItem = listItem;
            }
            return md.toString();
        }

        // The document's body font size: the size most of its text is set in, by
        // character count rather than by paragraph count, so one enormous title can't
        // outvote the body it sits above. Headings are then judged against it -
        // "16pt" means nothing on its own, "4pt larger than everything else" does.
        private static int bodySize(List<List<Run>> paragraphs) {
            java.util.Map<Integer, Integer> characters = new java.util.HashMap<>();
            for (List<Run> paragraph : paragraphs) {
                for (Run run : paragraph) {
                    String trimmed = run.text.trim();
                    if (trimmed.isEmpty()) continue;
                    characters.merge(run.size, trimmed.length(), Integer::sum);
                }
            }
            int body = 0;
            int most = 0;
            for (java.util.Map.Entry<Integer, Integer> e : characters.entrySet()) {
                // On a tie the smaller size wins: body text is the smaller one.
                if (e.getValue() > most || (e.getValue() == most && e.getKey() < body)) {
                    most = e.getValue();
                    body = e.getKey();
                }
            }
            return body;
        }

        // One paragraph as a Markdown block, or "" when it holds no text.
        private static String block(List<Run> paragraph, int bodySize) {
            String plain = plain(paragraph);
            if (plain.trim().isEmpty()) return "";

            // The list marker, and how many characters of the text it replaces: the
            // bullet is part of the text, so it has to come off before the runs are
            // formatted - otherwise a bold bullet ends up inside the emphasis.
            String marker = "";
            int drop = 0;
            java.util.regex.Matcher bullet = BULLET.matcher(plain);
            java.util.regex.Matcher numbered = NUMBERED.matcher(plain);
            if (bullet.find()) {
                marker = "- ";
                drop = bullet.end();
            } else if (numbered.find()) {
                marker = numbered.group(1) + " ";
                drop = numbered.end();
            }

            if (marker.isEmpty()) {
                int heading = headingLevel(paragraph, plain, bodySize);
                if (heading > 0) {
                    // No emphasis inside a heading: the bold a heading is set in is
                    // what identified it, and "# **Title**" says the same thing twice.
                    String title = escape(plain).trim();
                    if (title.isEmpty()) return "";
                    StringBuilder hashes = new StringBuilder();
                    for (int i = 0; i < heading; i++) hashes.append('#');
                    return hashes.toString() + " " + title;
                }
            }

            String text = inline(paragraph, drop).trim();
            if (text.isEmpty()) return "";
            // A paragraph that happens to start with Markdown's own markup is text,
            // not markup: "#" would silently become a heading, ">" a quote.
            if (marker.isEmpty() && (text.startsWith("#") || text.startsWith(">"))) {
                text = "\\" + text;
            }
            return marker + text;
        }

        /** The paragraph's text, unformatted - what the decisions above are made on. */
        private static String plain(List<Run> paragraph) {
            StringBuilder out = new StringBuilder();
            for (Run run : paragraph) out.append(run.text);
            return out.toString();
        }

        // The paragraph as Markdown text, skipping the first "skip" characters (the
        // list marker, when there was one).
        private static String inline(List<Run> paragraph, int skip) {
            StringBuilder out = new StringBuilder();
            int left = skip;
            for (Run run : paragraph) {
                String text = run.text;
                if (left > 0) {
                    if (left >= text.length()) {
                        left -= text.length();
                        continue;
                    }
                    text = text.substring(left);
                    left = 0;
                }
                out.append(emphasised(text, run.bold, run.italic));
            }
            return out.toString();
        }

        // 1/2/3 for a heading, 0 for an ordinary paragraph. Decided on the font size
        // alone: bold-only headings are indistinguishable from a bold sentence, and
        // turning every bold line into a heading wrecks more documents than it fixes.
        private static int headingLevel(List<Run> paragraph, String plain, int bodySize) {
            if (bodySize <= 0) return 0;
            String trimmed = plain.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_HEADING_CHARS) return 0;

            int size = 0;
            for (Run run : paragraph) {
                if (run.text.trim().isEmpty()) continue;   // trailing break, indentation
                size = Math.max(size, run.size);
            }
            if (size <= bodySize) return 0;
            if (size >= bodySize + H1_POINTS_OVER_BODY) return 1;
            if (size >= bodySize + H2_POINTS_OVER_BODY) return 2;
            return 3;
        }

        // Wraps a run in its emphasis markers, keeping any surrounding whitespace
        // outside them: "** bold **" is not emphasis in Markdown, it is four asterisks
        // and a word, and a run that ends in a space is entirely normal in RTF.
        private static String emphasised(String text, boolean bold, boolean italic) {
            String escaped = escape(text);
            if (!bold && !italic) return escaped;
            int start = 0;
            int end = escaped.length();
            while (start < end && Character.isWhitespace(escaped.charAt(start))) start++;
            while (end > start && Character.isWhitespace(escaped.charAt(end - 1))) end--;
            if (start == end) return escaped;   // whitespace only: nothing to emphasise
            String marker = bold && italic ? "***" : (bold ? "**" : "*");
            return escaped.substring(0, start) + marker + escaped.substring(start, end)
                    + marker + escaped.substring(end);
        }

        // The document's text is data, not markup: an asterisk someone typed must come
        // out as an asterisk. Escaped are the characters that would otherwise be read
        // as the markup this converter itself emits, plus the backslash that escapes
        // them. "_" only where it could actually open emphasis - Markdown does not
        // emphasise inside a word, and escaping every one would turn a file_name_here
        // into noise for no gain.
        private static String escape(String text) {
            StringBuilder out = new StringBuilder(text.length() + 8);
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                boolean special = c == '\\' || c == '`' || c == '*'
                        || (c == '_' && !insideWord(text, i));
                if (special) out.append('\\');
                out.append(c);
            }
            return out.toString();
        }

        private static boolean insideWord(String text, int at) {
            return at > 0 && at + 1 < text.length()
                    && Character.isLetterOrDigit(text.charAt(at - 1))
                    && Character.isLetterOrDigit(text.charAt(at + 1));
        }
    }

    // ---- DOCX as Markdown --------------------------------------------------
    // The same thing as includeRtfAsMarkdown for the other format a word processor
    // saves: unzips the .docx, reads word/document.xml, writes Markdown under
    // JRock/docx-md/ as "<docxname>.md", and includes that as an ordinary @txt token.
    //
    // Also with nothing installed - a .docx is a ZIP of XML, so java.util.zip and the
    // JDK's XML parser are the whole toolchain, and this works in the browser too.
    // Tables survive this direction, which is more than the RTF reader manages: they
    // are w:tbl elements and plain to read, whereas RTF's table markup reaches
    // RTFEditorKit as ordinary paragraphs.
    //
    // Runs on a background thread (see showIncludeDialog); the include itself, which
    // touches the prompt's document, goes through onEdt().
    private static void includeDocxAsMarkdown(JTextArea input, LogView log,
                                              boolean extend, Path docx) {
        log.gray("Converting DOCX to Markdown: " + docx);

        String markdown;
        try {
            markdown = DocxMarkdown.of(docx);
        } catch (IOException | RuntimeException ex) {
            log.gray("Could not read DOCX " + docx.getFileName() + ": " + ex.getMessage());
            return;
        }
        if (markdown.isBlank()) {
            log.gray("No text found in " + docx.getFileName() + "; nothing included. "
                    + "(An empty document - or a .docx with no body text of its own.)");
            return;
        }

        // Output goes to JRock/docx-md/, named "<docxname>.md" - the same naming as
        // gs-pdf/ and rtf-md/, so two documents of the same stem can't collide.
        Path outDir = docxMdDir();
        Path out = outDir.resolve(docx.getFileName().toString() + ".md");
        try {
            Files.createDirectories(outDir);
            Files.write(out, markdown.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            log.gray("Could not write " + out + ": " + ex.getMessage());
            return;
        }
        log.gray("Markdown written to " + out);

        boolean[] added = new boolean[1];
        // Inserts the token into the prompt's document, so: on the EDT.
        onEdt(() -> added[0] = includeOne(input, log, extend, out, "txt", false));
        log.gray("Inserted " + (added[0] ? 1 : 0) + " new @txt token(s) for "
                + docx.getFileName() + ".");
    }

    // DOCX -> Markdown.
    //
    // What is read is word/document.xml and nothing else: the body's paragraphs and
    // tables, each paragraph's style name, and each run's bold and italic. That is
    // where a Word document keeps its meaning, and it maps onto Markdown directly:
    //
    //   Heading 1..6 / Title      -> #, ##, ### ...
    //   bold / italic runs        -> **bold**, *italic*, ***both***
    //   a Code-like style         -> an indented code block
    //   a Quote-like style        -> "> "
    //   a numbered/bulleted list  -> a "-" list item
    //   w:tbl                     -> a Markdown pipe table
    //
    // Deliberately not read: numbering.xml (so an ordered list's real numbers are not
    // recovered - a "-" item says the same structural thing), headers and footers,
    // footnotes, images, colours, revision marks. Text a document typed itself, like a
    // literal bullet character, is still cleaned up by the same rules the RTF side
    // uses, whose escaping and emphasis this shares outright.
    //
    // A file that is not a .docx, or one Word wrote in some way not covered here,
    // comes back as an IOException or as empty text - includeDocxAsMarkdown reports
    // both and includes nothing, which is better than including nonsense.
    private static final class DocxMarkdown {

        /** The WordprocessingML namespace: every element and attribute read here. */
        private static final String W =
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        private static final String DOCUMENT_PART = "word/document.xml";

        // Style names, matched loosely because they are not standardised: "Heading1"
        // and "heading 1" are the same style, and a monospace paragraph is called
        // "Code" by some applications and "HTMLPreformatted" or "SourceCode" by others.
        private static final java.util.regex.Pattern HEADING_STYLE =
                java.util.regex.Pattern.compile("(?i)^heading[ _-]*([1-6])$");
        private static final java.util.regex.Pattern CODE_STYLE =
                java.util.regex.Pattern.compile("(?i)code|preformat|listing|source");
        private static final java.util.regex.Pattern QUOTE_STYLE =
                java.util.regex.Pattern.compile("(?i)quot|citation");

        private DocxMarkdown() { }

        /** The Markdown for one .docx. Never null; empty when it has no body text. */
        static String of(Path docx) throws IOException {
            byte[] part = part(docx, DOCUMENT_PART);
            if (part == null) {
                throw new IOException("no " + DOCUMENT_PART + " inside it"
                        + " - is it really a Word .docx?");
            }
            org.w3c.dom.Element body = child(document(part).getDocumentElement(), "body");
            if (body == null) throw new IOException("its " + DOCUMENT_PART + " has no body");
            return markdown(body);
        }

        // One named entry of the ZIP, or null when it isn't there.
        //
        // Read with ZipInputStream rather than ZipFile: a Path is not always a file a
        // ZipFile can open (the browser build's filesystem is not the local one), and
        // a sequential scan for one entry is nothing next to parsing the XML anyway.
        private static byte[] part(Path zip, String name) throws IOException {
            try (java.io.InputStream in = Files.newInputStream(zip);
                 java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(in)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (!name.equals(entry.getName())) continue;
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = zin.read(buffer)) > 0) out.write(buffer, 0, read);
                    return out.toByteArray();
                }
            } catch (IllegalArgumentException | java.util.zip.ZipException ex) {
                // Not a ZIP at all, or one this JDK won't read: the same answer either
                // way, and the caller has the file name to put in front of it.
                throw new IOException("not a readable ZIP archive: " + ex.getMessage());
            }
            return null;
        }

        // The part as a DOM tree.
        //
        // Parsed with the external world switched off: this XML comes from a file
        // somebody sent, and a DOCTYPE in it must not be able to make the parser fetch
        // anything or expand an entity into a gigabyte of text.
        private static org.w3c.dom.Document document(byte[] xml) throws IOException {
            try {
                javax.xml.parsers.DocumentBuilderFactory factory =
                        javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setExpandEntityReferences(false);
                return factory.newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(xml));
            } catch (javax.xml.parsers.ParserConfigurationException
                     | org.xml.sax.SAXException ex) {
                throw new IOException("its " + DOCUMENT_PART + " is not readable XML: "
                        + ex.getMessage());
            }
        }

        // The body as Markdown: one block per paragraph or table, blank line between
        // them - except between consecutive list items, which are one list.
        private static String markdown(org.w3c.dom.Element body) {
            StringBuilder md = new StringBuilder();
            boolean previousWasListItem = false;
            for (org.w3c.dom.Element element : children(body)) {
                String name = element.getLocalName();
                String block;
                boolean listItem = false;
                if ("p".equals(name)) {
                    block = paragraph(element);
                    listItem = block.startsWith("- ")
                            || RtfMarkdown.NUMBERED.matcher(block).find();
                } else if ("tbl".equals(name)) {
                    block = table(element);
                } else {
                    continue;   // sectPr and anything else structural
                }
                if (block.isEmpty()) continue;
                if (md.length() > 0 && !(listItem && previousWasListItem)) md.append('\n');
                md.append(block).append('\n');
                previousWasListItem = listItem;
            }
            return md.toString();
        }

        // One w:p as a Markdown block, or "" when it holds no text.
        private static String paragraph(org.w3c.dom.Element p) {
            List<RtfMarkdown.Run> runs = runs(p);
            String plain = RtfMarkdown.plain(runs);
            if (plain.trim().isEmpty()) return "";
            String style = style(p);

            java.util.regex.Matcher heading = HEADING_STYLE.matcher(style);
            int level = heading.matches() ? Integer.parseInt(heading.group(1))
                    : ("Title".equalsIgnoreCase(style) ? 1 : 0);
            if (level > 0) {
                // No emphasis inside a heading: the style already said what this is.
                String title = RtfMarkdown.escape(plain).trim();
                if (title.isEmpty()) return "";
                StringBuilder hashes = new StringBuilder();
                for (int i = 0; i < level; i++) hashes.append('#');
                return hashes + " " + title;
            }
            // Code keeps its own characters - escaping them would be escaping the code
            // - and four spaces make a Markdown code block without needing a fence to
            // be opened and closed around a run of paragraphs.
            if (CODE_STYLE.matcher(style).find()) return "    " + stripEnd(plain);

            // The list marker: a character the document typed into the text, or the
            // list markup (w:numPr) that carries no character at all. Either way it
            // comes out as "-": the real numbers live in numbering.xml, which is a
            // whole numbering machine to implement for something Markdown renumbers
            // by itself.
            String marker = "";
            int drop = 0;
            java.util.regex.Matcher bullet = RtfMarkdown.BULLET.matcher(plain);
            java.util.regex.Matcher numbered = RtfMarkdown.NUMBERED.matcher(plain);
            if (bullet.find()) {
                marker = "- ";
                drop = bullet.end();
            } else if (numbered.find()) {
                marker = numbered.group(1) + " ";
                drop = numbered.end();
            } else if (numbering(p)) {
                marker = "- ";
            }

            String text = RtfMarkdown.inline(runs, drop).trim();
            if (text.isEmpty()) return "";
            if (marker.isEmpty() && QUOTE_STYLE.matcher(style).find()) return "> " + text;
            // A paragraph that happens to start with Markdown's own markup is text,
            // not markup: "#" would silently become a heading, ">" a quote.
            if (marker.isEmpty() && (text.startsWith("#") || text.startsWith(">"))) {
                text = "\\" + text;
            }
            return marker + text;
        }

        // One w:tbl as a Markdown pipe table, with the first row as the header -
        // Markdown has no other kind - and every row padded to the widest one, since a
        // ragged pipe table is not a table to a reader.
        private static String table(org.w3c.dom.Element tbl) {
            List<List<String>> rows = new ArrayList<>();
            int columns = 0;
            for (org.w3c.dom.Element tr : children(tbl, "tr")) {
                List<String> row = new ArrayList<>();
                for (org.w3c.dom.Element tc : children(tr, "tc")) row.add(cell(tc));
                if (row.isEmpty()) continue;
                columns = Math.max(columns, row.size());
                rows.add(row);
            }
            if (rows.isEmpty() || columns == 0) return "";

            StringBuilder md = new StringBuilder();
            for (int r = 0; r < rows.size(); r++) {
                if (r > 0) md.append('\n');
                md.append(row(rows.get(r), columns));
                if (r == 0) {
                    md.append('\n');
                    List<String> dashes = new ArrayList<>();
                    for (int c = 0; c < columns; c++) dashes.add("---");
                    md.append(row(dashes, columns));
                }
            }
            return md.toString();
        }

        private static String row(List<String> cells, int columns) {
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < columns; c++) {
                line.append(' ').append(c < cells.size() ? cells.get(c) : "").append(" |");
            }
            return line.toString();
        }

        // One w:tc: its paragraphs, joined with a space. A cell holding several
        // paragraphs is not something a Markdown table row can reproduce, and keeping
        // the words on one line loses less than dropping all but the first.
        private static String cell(org.w3c.dom.Element tc) {
            StringBuilder text = new StringBuilder();
            for (org.w3c.dom.Element p : children(tc, "p")) {
                String part = RtfMarkdown.inline(runs(p), 0).trim();
                if (part.isEmpty()) continue;
                if (text.length() > 0) text.append(' ');
                text.append(part);
            }
            // A pipe inside a cell would end it; a newline would end the whole row.
            return text.toString().replace("|", "\\|").replace("\n", " ");
        }

        // The paragraph's runs, reusing the RTF side's run model so that the emphasis
        // and escaping are decided in exactly one place for both formats.
        //
        // Neighbours with the same style are merged, because Word splits runs freely -
        // a spell-check boundary is enough - and "**a****b**" is not the bold "ab" to
        // any Markdown reader. getElementsByTagNameNS is in document order and finds
        // runs nested in a w:hyperlink too, which is where a link's text lives.
        private static List<RtfMarkdown.Run> runs(org.w3c.dom.Element p) {
            List<RtfMarkdown.Run> runs = new ArrayList<>();
            org.w3c.dom.NodeList found = p.getElementsByTagNameNS(W, "r");
            for (int i = 0; i < found.getLength(); i++) {
                org.w3c.dom.Element r = (org.w3c.dom.Element) found.item(i);
                String text = text(r);
                if (text.isEmpty()) continue;
                RtfMarkdown.Run run =
                        new RtfMarkdown.Run(text, on(r, "b"), on(r, "i"), 0);
                int last = runs.size() - 1;
                if (last >= 0 && runs.get(last).sameStyleAs(run)) {
                    runs.set(last, new RtfMarkdown.Run(
                            runs.get(last).text + text, run.bold, run.italic, 0));
                } else {
                    runs.add(run);
                }
            }
            return runs;
        }

        // One run's text. w:t is the text itself; a tab and a line break become a
        // space, as they do on the RTF side, and w:delText - text someone deleted with
        // track-changes on - is deliberately not among them.
        private static String text(org.w3c.dom.Element r) {
            StringBuilder out = new StringBuilder();
            for (org.w3c.dom.Element child : children(r)) {
                String name = child.getLocalName();
                if ("t".equals(name)) out.append(child.getTextContent());
                else if ("tab".equals(name) || "br".equals(name) || "cr".equals(name)) {
                    out.append(' ');
                } else if ("noBreakHyphen".equals(name)) out.append('-');
            }
            return out.toString();
        }

        /** A w:rPr toggle: present, and not switched off with w:val="0"/"false". */
        private static boolean on(org.w3c.dom.Element run, String name) {
            org.w3c.dom.Element rPr = child(run, "rPr");
            org.w3c.dom.Element toggle = rPr == null ? null : child(rPr, name);
            if (toggle == null) return false;
            String value = toggle.getAttributeNS(W, "val");
            return !("0".equals(value) || "false".equals(value) || "off".equals(value));
        }

        /** The paragraph's style id, or "" when it has none (i.e. body text). */
        private static String style(org.w3c.dom.Element p) {
            org.w3c.dom.Element pPr = child(p, "pPr");
            org.w3c.dom.Element style = pPr == null ? null : child(pPr, "pStyle");
            return style == null ? "" : style.getAttributeNS(W, "val");
        }

        /** Whether the paragraph is part of a list (w:numPr), bulleted or numbered. */
        private static boolean numbering(org.w3c.dom.Element p) {
            org.w3c.dom.Element pPr = child(p, "pPr");
            return pPr != null && child(pPr, "numPr") != null;
        }

        // Direct element children only, all of them or by name. Direct, because
        // getElementsByTagNameNS descends: it would find a nested table's rows among
        // its parent's, and a cell's paragraphs among the table's.
        private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent) {
            return children(parent, null);
        }

        private static List<org.w3c.dom.Element> children(org.w3c.dom.Element parent,
                                                          String name) {
            List<org.w3c.dom.Element> found = new ArrayList<>();
            org.w3c.dom.NodeList nodes = parent.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                org.w3c.dom.Node node = nodes.item(i);
                if (node.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                org.w3c.dom.Element element = (org.w3c.dom.Element) node;
                if (name == null || name.equals(element.getLocalName())) found.add(element);
            }
            return found;
        }

        private static org.w3c.dom.Element child(org.w3c.dom.Element parent, String name) {
            List<org.w3c.dom.Element> found = children(parent, name);
            return found.isEmpty() ? null : found.get(0);
        }

        /** Trailing whitespace off, leading whitespace kept: code is indented. */
        private static String stripEnd(String text) {
            int end = text.length();
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
            return text.substring(0, end);
        }
    }

    // ---- Markdown as RTF and DOCX (export) ---------------------------------
    // The other direction, and the only one that writes a document: takes the text
    // selected in the log - a model's answer, which is Markdown - and writes it out as
    // a word processor file, with the markup turned into real formatting.
    //
    // DOCX is the one to export for anything that will be typeset, because it carries
    // named paragraph styles a layout application can map onto its own (see the
    // README's PDF section) - and the session's pictures, placed where the answer put
    // them. RTF is the fallback that nearly everything opens, text only.
    //
    // Both writers are built here out of strings. For DOCX there is no alternative in
    // a bare JDK, and none needed: the format is a ZIP of XML parts, so java.util.zip
    // and a StringBuilder are exactly enough. For RTF there nearly is one - Swing's
    // RTFEditorKit - and it is deliberately not used: its *writer* has no notion of
    // tables, which is half of what these documents need, and going through it would
    // mean building a StyledDocument only to have it flattened again. Its *reader* is
    // a different matter and is used, on the import side, in RtfMarkdown.
    //
    // Nothing shells out and nothing is installed, so both exports also work in the
    // browser build - where "export" is how a phone gets a document out of JRock at
    // all.

    // Exports the log's selection, read as Markdown, as an RTF or DOCX file.
    //
    // Offered only while text is selected, and the selection is what it exports: the
    // whole log is mostly JRock's own status lines, and a document made of those is
    // not a document anyone wants. The conversion itself cannot fail (see
    // MarkdownExport), so what the log reports afterwards is what was written.
    //
    // The DOCX also carries the pictures: the session's includes are handed to the
    // converter, so a "![](<hash>)" in the selection is placed as the image it names
    // and an "@img <hash>" becomes that image's file name. The RTF keeps the simpler
    // job - text only, both tags left as the text they are - because a picture in an
    // RTF is the picture's bytes hex-encoded into the file, and the format is offered
    // here as the one anything can open, not as the one to typeset from.
    //
    // The DOCX also asks WHICH A4: the file-type dropdown holds portrait and landscape,
    // because the orientation is a property of the document being written and the save
    // dialog is the last moment anybody is asked anything. A wide table or a landscape
    // photograph is the case for it - both are laid out to fit the text frame, and on a
    // portrait page that frame is the narrow way round. Two filters with the same
    // extension, so the name typed is unaffected by which one is picked.
    private static void exportSelectedMarkdown(JFrame frame, LogView log, boolean asDocx) {
        String selection = log.selectedText();
        if (selection == null) return;   // the menu item is disabled without one
        String ext = asDocx ? "docx" : "rtf";
        String kind = asDocx ? "DOCX" : "RTF";
        String what = asDocx ? "Markdown with images" : "Markdown";

        javax.swing.JFileChooser chooser =
                new javax.swing.JFileChooser(logChooserDir.start());
        chooser.setDialogTitle("Export selected " + what + " as " + kind);
        chooser.setAcceptAllFileFilterUsed(false);
        javax.swing.filechooser.FileNameExtensionFilter landscapeFilter = null;
        if (asDocx) {
            javax.swing.filechooser.FileNameExtensionFilter portraitFilter =
                    new javax.swing.filechooser.FileNameExtensionFilter(
                            "DOCX document as A4 portrait (*.docx)", ext);
            landscapeFilter = new javax.swing.filechooser.FileNameExtensionFilter(
                    "DOCX document as A4 landscape (*.docx)", ext);
            chooser.addChoosableFileFilter(portraitFilter);
            chooser.addChoosableFileFilter(landscapeFilter);
            chooser.setFileFilter(portraitFilter);          // the usual page, so the default
        } else {
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    kind + " document (*." + ext + ")", ext));
        }
        chooser.setSelectedFile(logChooserDir.startFile("jrock-selection." + ext));
        if (chooser.showSaveDialog(frame) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        logChooserDir.remember(chooser);
        boolean landscape = asDocx && chooser.getFileFilter() == landscapeFilter;
        if (asDocx) kind = "DOCX (A4 " + (landscape ? "landscape" : "portrait") + ")";

        // The extension belongs to the format, not to the typist: "notes" or
        // "notes.txt" holding a DOCX is a file nothing will open by double-click.
        Path target = withExtension(chooser.getSelectedFile().toPath(), ext);
        MarkdownExport document = asDocx
                ? MarkdownExport.of(selection, INCLUDES, landscape)
                : MarkdownExport.of(selection);
        // Anything the selection referred to and this could not place: said here, before
        // the result line, because the export goes ahead either way and the reference is
        // left in the text as it stands.
        for (String warning : document.warnings()) log.gray(warning);
        try {
            byte[] bytes = asDocx ? document.docx() : document.rtf();
            Files.write(target, bytes);
            log.gray("Exported the selection as " + kind + ": " + target + " - "
                    + fmtNum(document.blocks()) + " block(s), "
                    + fmtNum(document.tables()) + " table(s), "
                    + (asDocx ? fmtNum(document.images()) + " image(s), " : "")
                    + fmtNum(bytes.length) + " bytes.");
            if (document.simplified()) {
                log.gray("Its Markdown could not be read as Markdown, so the lines went "
                        + "in as plain paragraphs - the text is all there, the "
                        + "formatting is not.");
            }
        } catch (IOException | RuntimeException ex) {
            log.gray("Could not export " + kind + " to " + target + ": " + ex);
            javax.swing.JOptionPane.showMessageDialog(frame,
                    "Could not export to " + target + ":\n" + ex.getMessage(),
                    "Export failed", javax.swing.JOptionPane.WARNING_MESSAGE);
        }
    }

    /** The path with the format's extension, unless it already ends in it. */
    private static Path withExtension(Path file, String ext) {
        String name = file.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith("." + ext)) return file;
        return file.resolveSibling(name + "." + ext);
    }

    // Markdown -> RTF and DOCX.
    //
    // Parsed once into a small block model, then written out twice, because the two
    // formats differ only in spelling: heading, paragraph, list item, quote, code,
    // rule and table, with bold, italic, `code` and links inside them. Links keep both
    // the text and the URL - a printed page cannot be clicked.
    //
    // Forgiving by design. Every rule reads "if the line looks like this...", and the
    // answer when none of them match is a paragraph of body text, so input that is
    // half-written Markdown, or not Markdown at all, still exports - with less
    // formatting, never with an error. of() additionally catches anything thrown by
    // this class itself and falls back to one paragraph per line, so an export always
    // produces a document and the log can say which of the two happened.
    //
    // No widgets: a String in and bytes out, which is what makes it testable without a
    // GUI (see JRockMarkdownExportTest). The only files it reads are the images it is
    // given the includes for, and only on the DOCX path.
    private static final class MarkdownExport {

        // Block kinds. `level` is the heading level for HEADING, and the nesting depth
        // for ITEM and QUOTE.
        private static final int BODY = 0, HEADING = 1, ITEM = 2, QUOTE = 3, CODE = 4,
                RULE = 5, TABLE = 6;

        // Font sizes for h1..h6 in half-points, which is what both formats count in
        // (RTF's \fs and DOCX's w:sz). 22 half-points is the 11pt body.
        private static final int[] HEADING_SIZE = { 36, 32, 28, 24, 22, 22 };
        private static final int BODY_SIZE = 22;
        private static final int CODE_SIZE = 20;

        /** One indent step, in twips: a quarter inch, the usual list indent. */
        private static final int INDENT = 360;

        /** Deepest indent honoured, so a runaway "          - x" stays on the page. */
        private static final int MAX_DEPTH = 5;

        // The page, in twips: A4 with 2 cm margins, named the way it is printed - the
        // short side across, the long side down. What is left of it is the text frame,
        // which is what a table's columns are shared out of and what an image is fitted
        // into.
        private static final int PAGE_WIDTH = 11906, PAGE_HEIGHT = 16838, MARGIN = 1134;
        private static final int TABLE_WIDTH = PAGE_WIDTH - 2 * MARGIN;    // 9638
        private static final int FRAME_HEIGHT = PAGE_HEIGHT - 2 * MARGIN;  // 14570

        // EMU (English Metric Units) are what a drawing is sized in: 914400 to the inch,
        // so 635 to the twip, and 3048 for one pixel of a 300 dpi image.
        private static final long EMU_PER_TWIP = 635;
        private static final long EMU_PER_PIXEL_AT_300_DPI = 914400 / 300;

        private static final java.util.regex.Pattern HEADING_LINE =
                java.util.regex.Pattern.compile("(#{1,6})\\s+(.*)");
        private static final java.util.regex.Pattern RULE_LINE =
                java.util.regex.Pattern.compile("-{3,}|\\*{3,}|_{3,}");
        private static final java.util.regex.Pattern ITEM_LINE =
                java.util.regex.Pattern.compile("( *)([-*+]|\\d{1,3}[.)])\\s+(.*)");
        private static final java.util.regex.Pattern QUOTE_LINE =
                java.util.regex.Pattern.compile("> ?(.*)");
        private static final java.util.regex.Pattern TABLE_DASHES =
                java.util.regex.Pattern.compile(":?-+:?");

        /** A bullet for an unordered item; an ordered one keeps its own number. */
        private static final String BULLET = "\u2022";

        // A JRock include referred to from the Markdown: "![](<hash>)" for the picture
        // itself, and the hash on its own as the thing that has to be looked up.
        private static final java.util.regex.Pattern IMAGE_REF =
                java.util.regex.Pattern.compile("!\\[[^\\]\\n]*\\]\\(([0-9a-f]{" + HASH_LEN + "})\\)");
        private static final java.util.regex.Pattern HASH_REF =
                java.util.regex.Pattern.compile("[0-9a-f]{" + HASH_LEN + "}");

        /** A line holding nothing but image references, which becomes a paragraph of them. */
        private static final java.util.regex.Pattern IMAGE_LINE =
                java.util.regex.Pattern.compile("(?:" + IMAGE_REF.pattern() + "\\s*)+");

        private final List<Block> blocks = new ArrayList<>();
        private List<Run> pending;         // body text being gathered across lines
        private boolean simplified;

        // hash -> file for the session's includes, or null when this export does not
        // place pictures at all (the RTF path). Then: every hash looked up so far, so a
        // file is read once however often it is referred to; the images the document
        // actually places, in the order it places them, which is the order of the media
        // parts and their relationship ids; and what could not be placed, for the log.
        private final java.util.Map<String, Path> images;
        private final java.util.Map<String, Image> resolved = new java.util.HashMap<>();
        private final java.util.Map<String, Image> media = new java.util.LinkedHashMap<>();
        private final List<String> warnings = new ArrayList<>();
        private int drawings;              // one id per placement, which a .docx wants unique

        // The page this document is laid out on. Landscape turns A4 on its side, and that
        // is the whole of it: the two page dimensions swap, and so do the frame's - which
        // is why every number below is read from these four fields and not from the
        // constants. A layout measured against PAGE_WIDTH would be a portrait layout on a
        // landscape page: a table 9638 twips wide in a frame of 14570, and a picture
        // fitted to a height that is now the width.
        //
        // Instance fields rather than constants because one session exports both, one
        // after the other, and a document's page is a property of that document.
        private final boolean landscape;
        private final int pageWidth, pageHeight, frameWidth, frameHeight;

        private MarkdownExport(java.util.Map<String, Path> images, boolean landscape) {
            this.images = images;
            this.landscape = landscape;
            this.pageWidth   = landscape ? PAGE_HEIGHT : PAGE_WIDTH;
            this.pageHeight  = landscape ? PAGE_WIDTH  : PAGE_HEIGHT;
            this.frameWidth  = pageWidth  - 2 * MARGIN;
            this.frameHeight = pageHeight - 2 * MARGIN;
        }

        /** Parses the Markdown, placing no pictures. Never throws. */
        static MarkdownExport of(String markdown) { return of(markdown, null); }

        /**
         * Parses the Markdown for a portrait page. Never throws.
         *
         * @param includes hash -&gt; file of the session's includes, so that a
         *                 "![](&lt;hash&gt;)" can be placed as a picture and an
         *                 "@img &lt;hash&gt;" written as that file's name; null to do
         *                 neither and leave both as the text they are.
         */
        static MarkdownExport of(String markdown, java.util.Map<String, Path> includes) {
            return of(markdown, includes, false);
        }

        /**
         * Parses the Markdown. Never throws: worst case, every line is a paragraph.
         *
         * @param includes  as above.
         * @param landscape A4 on its side, which the DOCX states and lays out to; the RTF
         *                  declares no page of its own, so it is always written portrait.
         */
        static MarkdownExport of(String markdown, java.util.Map<String, Path> includes,
                                 boolean landscape) {
            String text = markdown == null ? "" : markdown;
            MarkdownExport document = new MarkdownExport(includes, landscape);
            try {
                document.parse(text);
            } catch (RuntimeException ex) {
                // A bug here is not a reason to lose someone's text. Keep the text,
                // drop the structure, and let the caller say so in the log.
                document.blocks.clear();
                document.pending = null;
                document.media.clear();    // nothing is placed any more, so nothing is packed
                document.simplified = true;
                for (String line : text.split("\n", -1)) {
                    if (line.trim().isEmpty()) continue;
                    document.block(BODY, 0).runs.add(plain(line.trim()));
                }
            }
            return document;
        }

        /** How many blocks came out of it, and how many of those are tables. */
        int blocks() { return blocks.size(); }

        int tables() {
            int tables = 0;
            for (Block b : blocks) if (b.kind == TABLE) tables++;
            return tables;
        }

        /** Whether the Markdown had to be given up on and written as plain lines. */
        boolean simplified() { return simplified; }

        /** How many pictures the document places, i.e. how many go into the package. */
        int images() { return media.size(); }

        /** What was referred to and could not be placed, in the words the log uses. */
        List<String> warnings() { return warnings; }

        // ---- the model ----

        /** One inline stretch of text and the three things this converter tracks. */
        private static final class Run {
            final String text;
            final boolean bold, italic, mono;
            // A picture instead of the text, when this run is a placed image. The text is
            // then the Markdown it came from, so a format that cannot place one - RTF -
            // still says what was meant to be here.
            final Image image;

            Run(String text, boolean bold, boolean italic, boolean mono) {
                this(text, bold, italic, mono, null);
            }

            Run(String text, boolean bold, boolean italic, boolean mono, Image image) {
                this.text = text;
                this.bold = bold;
                this.italic = italic;
                this.mono = mono;
                this.image = image;
            }
        }

        private static Run plain(String text) { return new Run(text, false, false, false); }

        // One image the document places: the bytes that go into the package, the part
        // name and content type the package has to describe them with, and the size the
        // page gives the picture.
        //
        // That size is the whole of what is decided here, because nothing else decides
        // it: a .docx states how big a picture IS, in EMU, and neither the Markdown nor
        // the file says. So it is as wide as the text frame - unless that would stretch
        // the pixels thinner than 300 dpi, in which case 300 dpi is the width it gets and
        // the picture sits narrower than the frame. A tall image is held to the frame's
        // height by the same rule, which is what keeps a portrait photograph on one page.
        // All three limits are applied as one number, the EMU given to each pixel, so
        // width and height cannot drift out of proportion whichever of them binds.
        //
        // The frame is passed in rather than read from the constants, because which way
        // round it is depends on the page: on a landscape A4 the room across is 14570
        // twips and the room down 9638, the exact opposite of a portrait one. A landscape
        // photograph therefore comes out larger on a landscape page - it is the width
        // that binds, and there is more of it - which is the reason to export one.
        private static final class Image {
            final String fileName, extension, mime;
            final byte[] bytes;
            final long cx, cy;             // the placed size, in EMU
            int index, relId;              // assigned when the document first places it

            Image(String fileName, String extension, String mime, byte[] bytes,
                  int pixelWidth, int pixelHeight, int frameWidth, int frameHeight) {
                this.fileName = fileName;
                this.extension = extension;
                this.mime = mime;
                this.bytes = bytes;
                long perPixel = Math.min(EMU_PER_PIXEL_AT_300_DPI,
                        Math.min(frameWidth * EMU_PER_TWIP / pixelWidth,
                                 frameHeight * EMU_PER_TWIP / pixelHeight));
                this.cx = Math.max(1, pixelWidth * perPixel);
                this.cy = Math.max(1, pixelHeight * perPixel);
            }

            /** Its name inside the package, which is numbered rather than the file's own. */
            String part() { return "image" + index + "." + extension; }
        }

        private static final class Block {
            int kind = BODY;
            int level;
            String marker = "";              // ITEM: its bullet or number
            final List<Run> runs = new ArrayList<>();
            Table table;                     // TABLE only
        }

        // A table as rows of cells of runs. Nested lists rather than a cell class: the
        // shape is the whole content, and both writers walk it the same way.
        private static final class Table {
            final List<List<List<Run>>> rows = new ArrayList<>();
            int columns;
            int[] align = new int[0];        // -1 left, 0 centre, 1 right, per column
        }

        // ---- the images ----

        // The image included under this hash, ready to be placed, or null with a warning
        // recorded. Each hash is looked up once: the same picture referred to twice is
        // read from disk once, and a hash nothing is included under is complained about
        // once.
        private Image image(String hash) {
            if (resolved.containsKey(hash)) return resolved.get(hash);
            resolved.put(hash, null);
            Path file = images.get(hash);
            if (file == null) {
                warn("No image is included under the hash " + hash);
                return null;
            }
            int[] size = ImageHeader.size(file);
            if (size == null) {
                warn("Could not read the pixel size of " + file
                        + ", so it is not a PNG, JPEG, GIF or WEBP this can place");
                return null;
            }
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException | RuntimeException ex) {
                warn("Could not read " + file + ": " + ex);
                return null;
            }
            String[] type = mediaType(bytes);
            if (type == null) {
                warn("The bytes of " + file + " are not a PNG, JPEG, GIF or WEBP");
                return null;
            }
            Image image = new Image(file.getFileName().toString(), type[0], type[1], bytes,
                    size[0], size[1], frameWidth, frameHeight);
            resolved.put(hash, image);
            return image;
        }

        // The same, and counted in as one of the package's parts: a media part and a
        // relationship exist because the document places the picture, so they are numbered
        // here, at the first placement, and not again if it is placed twice.
        private Image placed(String hash) {
            Image image = image(hash);
            if (image != null && image.relId == 0) {
                image.index = media.size() + 1;
                image.relId = image.index + 1;     // rId1 is styles.xml
                media.put(hash, image);
            }
            return image;
        }

        // {extension, content type}, read from the bytes rather than from the name,
        // because a package states what a part IS and the extension on disk is only what
        // somebody typed. Null for anything else, which is then left as text: a .docx
        // whose media part is not what its content type claims is a repair dialog.
        private static String[] mediaType(byte[] bytes) {
            if (magic(bytes, 0, 0x89, 'P', 'N', 'G')) return new String[] { "png", "image/png" };
            if (magic(bytes, 0, 0xFF, 0xD8, 0xFF)) return new String[] { "jpeg", "image/jpeg" };
            if (magic(bytes, 0, 'G', 'I', 'F', '8')) return new String[] { "gif", "image/gif" };
            if (magic(bytes, 0, 'R', 'I', 'F', 'F') && magic(bytes, 8, 'W', 'E', 'B', 'P')) {
                return new String[] { "webp", "image/webp" };
            }
            return null;
        }

        private static boolean magic(byte[] bytes, int at, int... expected) {
            if (bytes.length < at + expected.length) return false;
            for (int i = 0; i < expected.length; i++) {
                if ((bytes[at + i] & 0xFF) != expected[i]) return false;
            }
            return true;
        }

        // A reference that could not be turned into a picture. Not a failure - the export
        // goes on and the reference stays in the text - so it is said once, in a sentence
        // the log can print as it stands.
        private void warn(String problem) {
            String line = problem + " - its reference is left in the text as it stands.";
            if (!warnings.contains(line)) warnings.add(line);
        }

        // ---- parsing ----

        private void parse(String markdown) {
            String[] lines = fileNames(markdown)
                    .replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            boolean fenced = false;
            int i = 0;
            while (i < lines.length) {
                String line = lines[i];
                String text = line.trim();
                java.util.regex.Matcher m;
                if (text.startsWith("```") || text.startsWith("~~~")) {
                    endParagraph();                 // the fence itself is not content
                    fenced = !fenced;
                    i++;
                } else if (fenced) {
                    // Verbatim, markup and indentation included: that is what a code
                    // block is for.
                    block(CODE, 0).runs.add(new Run(line, false, false, true));
                    i++;
                } else if (text.isEmpty()) {
                    endParagraph();
                    i++;
                } else if ((m = HEADING_LINE.matcher(text)).matches()) {
                    block(HEADING, m.group(1).length()).runs.addAll(inline(m.group(2).trim()));
                    i++;
                } else if (RULE_LINE.matcher(text).matches()) {
                    block(RULE, 0);
                    i++;
                } else if (text.indexOf('|') >= 0 && i + 1 < lines.length
                        && isTableDashes(lines[i + 1])) {
                    i = table(lines, i);
                } else if (images != null && IMAGE_LINE.matcher(text).matches()) {
                    // A picture on a line of its own gets a paragraph of its own, rather
                    // than being swept into the sentence below it - which here is usually
                    // the "@img" line the include wrote directly under the reference.
                    block(BODY, 0).runs.addAll(inline(text));
                    i++;
                } else if ((m = ITEM_LINE.matcher(line)).matches()) {
                    Block item = block(ITEM, 1 + Math.min(m.group(1).length() / 2, MAX_DEPTH - 1));
                    String marker = m.group(2);
                    item.marker = Character.isDigit(marker.charAt(0)) ? marker : BULLET;
                    item.runs.addAll(inline(m.group(3).trim()));
                    i++;
                } else if ((m = QUOTE_LINE.matcher(text)).matches()) {
                    block(QUOTE, 1).runs.addAll(inline(m.group(1).trim()));
                    i++;
                } else {
                    // Anything else is body text, and consecutive lines of it are one
                    // paragraph - which is what Markdown says, and what keeps a wrapped
                    // answer from coming out as a column of short lines.
                    if (pending == null) pending = new ArrayList<>();
                    else pending.add(plain(" "));
                    pending.addAll(inline(text));
                    i++;
                }
            }
            endParagraph();
        }

        // "@img <hash>" -> the name the file has on disk. A hash is JRock's handle on an
        // attachment and says nothing to whoever reads the page, whereas "IMG_4002.jpg"
        // is what the picture above it is called. An "@txt" token is left alone - it
        // stands for text that was sent, not for a file the document shows - and so is an
        // "@img" whose hash nothing is included under, with a warning for the log.
        private String fileNames(String markdown) {
            if (images == null) return markdown;
            java.util.regex.Matcher m = INCLUDE_TOKEN.matcher(markdown);
            StringBuffer out = new StringBuffer(markdown.length());
            while (m.find()) {
                Image image = "img".equals(m.group(1)) ? image(m.group(2)) : null;
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                        image == null ? m.group() : escaped(image.fileName)));
            }
            m.appendTail(out);
            return out.toString();
        }

        // A file name written as Markdown that means itself: inline() has to read it back
        // as the name, not as whatever markup its punctuation happens to spell.
        private static String escaped(String name) {
            StringBuilder out = new StringBuilder(name.length() + 4);
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if ("\\`*_[]()!#|<>".indexOf(c) >= 0) out.append('\\');
                out.append(c);
            }
            return out.toString();
        }

        /** Starts a block, closing any paragraph being gathered before it. */
        private Block block(int kind, int level) {
            endParagraph();
            Block block = new Block();
            block.kind = kind;
            block.level = level;
            blocks.add(block);
            return block;
        }

        private void endParagraph() {
            if (pending == null) return;
            Block block = new Block();
            block.runs.addAll(pending);
            blocks.add(block);
            pending = null;
        }

        // "|---|:--:|" and the like: the row of dashes under a table's header, which is
        // what marks the line above it as a table rather than a sentence with pipes.
        private static boolean isTableDashes(String line) {
            if (line.indexOf('-') < 0 || line.indexOf('|') < 0) return false;
            List<String> cells = cells(line);
            if (cells.isEmpty()) return false;
            for (String cell : cells) {
                if (!TABLE_DASHES.matcher(cell.trim()).matches()) return false;
            }
            return true;
        }

        // Reads a table starting at the header row, and returns the line after it.
        //
        // The dashes decide the column count and the alignments; a row with fewer cells
        // is padded and a longer one is cut, so the table stays rectangular whatever
        // the source did. It ends at the first line that is blank or has no pipe.
        private int table(String[] lines, int start) {
            Table table = new Table();
            List<String> dashes = cells(lines[start + 1]);
            table.columns = Math.max(1, dashes.size());
            table.align = new int[table.columns];
            for (int c = 0; c < table.columns; c++) table.align[c] = align(dashes.get(c));
            table.rows.add(row(lines[start], table.columns));

            int i = start + 2;
            while (i < lines.length) {
                String line = lines[i];
                if (line.trim().isEmpty() || line.indexOf('|') < 0) break;
                table.rows.add(row(line, table.columns));
                i++;
            }
            block(TABLE, 0).table = table;
            return i;
        }

        /** ":-:" centre, "--:" right, anything else left. */
        private static int align(String dashes) {
            String cell = dashes.trim();
            boolean left = cell.startsWith(":");
            boolean right = cell.endsWith(":");
            if (left && right) return 0;
            return right ? 1 : -1;
        }

        private List<List<Run>> row(String line, int columns) {
            List<String> cells = cells(line);
            List<List<Run>> row = new ArrayList<>();
            for (int c = 0; c < columns; c++) {
                row.add(c < cells.size() ? inline(cells.get(c).trim())
                        : new ArrayList<>());
            }
            return row;
        }

        // A row split on its unescaped pipes. The outer pipes of "| a | b |" are the
        // table's borders, not two empty cells; an inner empty cell is kept, because
        // that one is a cell.
        private static List<String> cells(String line) {
            List<String> cells = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\\' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                    cell.append('|');
                    i++;
                } else if (c == '|') {
                    cells.add(cell.toString());
                    cell.setLength(0);
                } else {
                    cell.append(c);
                }
            }
            cells.add(cell.toString());
            if (!cells.isEmpty() && cells.get(0).trim().isEmpty()) cells.remove(0);
            int last = cells.size() - 1;
            if (last >= 0 && cells.get(last).trim().isEmpty()) cells.remove(last);
            return cells;
        }

        // Markdown's inline markup, as much of it as a document needs: **bold**,
        // *italic*, `code`, [text](url), "![](<hash>)" for an included picture, and a
        // backslash escape.
        //
        // An asterisk only opens next to a non-space and only closes after one, which
        // is the rule that keeps "2 * 3 * 4" out of italics; an underscore also needs a
        // non-word character on its outer side, which is what saves snake_case. Inside
        // `code` nothing else is markup at all.
        private List<Run> inline(String text) {
            List<Run> runs = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean bold = false, italic = false, mono = false;
            int i = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                int afterLink, afterImage;
                if (c == '\\' && next != '\0' && !Character.isLetterOrDigit(next)
                        && !Character.isWhitespace(next)) {
                    current.append(next);
                    i += 2;
                } else if (c == '`') {
                    flush(runs, current, bold, italic, mono);
                    mono = !mono;
                    i++;
                } else if (!mono && (c == '*' || c == '_') && next == c
                        && delimits(text, i, 2, !bold)) {
                    flush(runs, current, bold, italic, mono);
                    bold = !bold;
                    i += 2;
                } else if (!mono && (c == '*' || c == '_') && delimits(text, i, 1, !italic)) {
                    flush(runs, current, bold, italic, mono);
                    italic = !italic;
                    i++;
                } else if (!mono && images != null && c == '!' && next == '['
                        && (afterImage = picture(text, i, runs, current, bold, italic)) > 0) {
                    i = afterImage;
                } else if (!mono && (c == '[' || (c == '!' && next == '['))
                        // Any other image is written as its alt text and its URL, same as
                        // a link: there is no picture to place, only what it was called.
                        && (afterLink = link(text, c == '!' ? i + 1 : i, current)) > 0) {
                    i = afterLink;
                } else {
                    current.append(c);
                    i++;
                }
            }
            flush(runs, current, bold, italic, mono);
            return runs;
        }

        // The "![alt](<hash>)" at `at`, placed as the picture that hash names.
        //
        // Returns the index just past the reference, or -1 when what is there is not a
        // reference to an include at all - an ordinary Markdown image, with a URL where
        // the hash would be - which then goes on being written as its text, like a link.
        //
        // A hash nothing is included under is appended as the reference itself, character
        // for character: the reader of the document at least sees that a picture was meant
        // to be there, and the log says why it is not (see image()).
        private int picture(String text, int at, List<Run> runs, StringBuilder current,
                            boolean bold, boolean italic) {
            java.util.regex.Matcher m = IMAGE_REF.matcher(text);
            if (!m.find(at) || m.start() != at) return -1;
            Image image = placed(m.group(1));
            if (image == null) {
                current.append(m.group());
                return m.end();
            }
            flush(runs, current, bold, italic, false);
            runs.add(new Run(m.group(), false, false, false, image));
            return m.end();
        }

        // Appends the "[label](url)" at `at` as text the reader of a document can use:
        // the label, and the URL after it unless the two say the same thing - a page
        // cannot be clicked, so the address has to be readable. Returns the index just
        // past the link, or -1 when what is there is a bracket in prose rather than a
        // link, in which case nothing is appended.
        private static int link(String text, int at, StringBuilder out) {
            int close = text.indexOf(']', at);
            if (close < 0 || close + 1 >= text.length() || text.charAt(close + 1) != '(') {
                return -1;
            }
            int end = text.indexOf(')', close + 2);
            if (end < 0) return -1;
            String label = text.substring(at + 1, close);
            String url = text.substring(close + 2, end).trim();
            out.append(label);
            if (!url.isEmpty() && !url.equals(label)) out.append(" (").append(url).append(')');
            return end + 1;
        }

        // Whether the run of `length` delimiter characters at `at` can do what is
        // wanted of it: open emphasis (text must follow) or close it (text must
        // precede).
        private static boolean delimits(String text, int at, int length, boolean opening) {
            char c = text.charAt(at);
            char before = at > 0 ? text.charAt(at - 1) : ' ';
            char after = at + length < text.length() ? text.charAt(at + length) : ' ';
            boolean ok = opening ? !Character.isWhitespace(after)
                    : !Character.isWhitespace(before);
            if (c == '_') {
                ok = ok && (opening ? !Character.isLetterOrDigit(before)
                        : !Character.isLetterOrDigit(after));
            }
            // Opening also requires something later that can close it. Without that,
            // one lone asterisk in a sentence italicises everything after it - the kind
            // of formatting a reader notices and the writer never wrote.
            return ok && (!opening || closer(text, at + length, length, c));
        }

        // Whether a run of `length` copies of `c` appears later in the line that could
        // close emphasis. A scan per delimiter, which a line's worth of text can afford.
        private static boolean closer(String text, int from, int length, char c) {
            for (int i = from; i + length <= text.length(); i++) {
                boolean run = true;
                for (int j = 0; j < length; j++) {
                    if (text.charAt(i + j) != c) run = false;
                }
                if (run && delimits(text, i, length, false)) return true;
            }
            return false;
        }

        private static void flush(List<Run> runs, StringBuilder current,
                                 boolean bold, boolean italic, boolean mono) {
            if (current.length() == 0) return;
            runs.add(new Run(current.toString(), bold, italic, mono));
            current.setLength(0);
        }

        // ---- RTF ----

        /** The document as RTF. Pure ASCII: everything else is escaped as \\uN?. */
        byte[] rtf() {
            StringBuilder rtf = new StringBuilder();
            rtf.append("{\\rtf1\\ansi\\ansicpg1252\\uc1\\deff0\\deflang1033")
               .append("{\\fonttbl{\\f0\\fswiss\\fcharset0 Calibri;}")
               .append("{\\f1\\fmodern\\fcharset0 Consolas;}}\n");
            for (Block block : blocks) rtf(rtf, block);
            rtf.append("}\n");
            return rtf.toString().getBytes(StandardCharsets.US_ASCII);
        }

        // Every paragraph starts \pard\plain, which resets both the paragraph and the
        // character formatting - so no block can leak its bold or its indent into the
        // next one, whatever order they come in.
        private void rtf(StringBuilder rtf, Block block) {
            switch (block.kind) {
                case RULE:
                    rtf.append("\\pard\\plain\\brdrb\\brdrs\\brdrw10\\brsp20\\sa120\\par\n");
                    return;
                case TABLE:
                    rtfTable(rtf, block.table);
                    return;
                case HEADING:
                    rtf.append("\\pard\\plain\\keepn\\sb240\\sa120\\f0\\b\\fs")
                       .append(HEADING_SIZE[Math.min(block.level, HEADING_SIZE.length) - 1])
                       .append(' ');
                    rtfRuns(rtf, block.runs, false, false);
                    break;
                case CODE:
                    // The paragraph is already in the monospace font, so its text goes
                    // in as it stands - no group and no switches around it.
                    rtf.append("\\pard\\plain\\sa0\\li").append(INDENT)
                       .append("\\f1\\fs").append(CODE_SIZE).append(' ');
                    for (Run run : block.runs) rtf.append(rtfText(run.text));
                    break;
                case ITEM:
                    // A hanging indent, so a wrapped item lines up under its own text
                    // rather than under its bullet.
                    rtf.append("\\pard\\plain\\sa60\\li").append(INDENT * block.level)
                       .append("\\fi-").append(INDENT).append("\\f0\\fs").append(BODY_SIZE)
                       .append(' ').append(rtfText(block.marker)).append("\\tab ");
                    rtfRuns(rtf, block.runs, false, false);
                    break;
                case QUOTE:
                    // Italic on the paragraph, so every run inherits it and a bold run
                    // inside the quote comes out bold-italic instead of losing one.
                    rtf.append("\\pard\\plain\\sa120\\li").append(INDENT * block.level)
                       .append("\\f0\\i\\fs").append(BODY_SIZE).append(' ');
                    rtfRuns(rtf, block.runs, false, false);
                    break;
                default:
                    rtf.append("\\pard\\plain\\sa120\\sl276\\slmult1\\f0\\fs")
                       .append(BODY_SIZE).append(' ');
                    rtfRuns(rtf, block.runs, false, false);
            }
            rtf.append("\\par\n");
        }

        private void rtfTable(StringBuilder rtf, Table table) {
            int columns = Math.max(1, table.columns);
            for (int r = 0; r < table.rows.size(); r++) {
                boolean header = r == 0;
                rtf.append("\\trowd\\trgaph108\\trleft0");
                if (header) rtf.append("\\trhdr");   // repeats on every page
                for (int c = 1; c <= columns; c++) {
                    rtf.append("\\clbrdrt\\brdrs\\brdrw10\\clbrdrl\\brdrs\\brdrw10")
                       .append("\\clbrdrb\\brdrs\\brdrw10\\clbrdrr\\brdrs\\brdrw10")
                       // frameWidth, which on this path is always A4 portrait's: RTF is
                       // written without a page of its own, so there is no other to use.
                       .append("\\cellx").append(frameWidth * c / columns);
                }
                rtf.append('\n');
                List<List<Run>> row = table.rows.get(r);
                for (int c = 0; c < columns; c++) {
                    rtf.append("\\pard\\plain\\intbl\\f0\\fs").append(BODY_SIZE)
                       .append(rtfAlign(table.align[c])).append(' ');
                    rtfRuns(rtf, c < row.size() ? row.get(c) : new ArrayList<>(),
                            header, false);
                    rtf.append("\\cell ");
                }
                rtf.append("\\row\n");
            }
            rtf.append("\\pard\\plain\\sa120\\f0\\fs").append(BODY_SIZE).append('\n');
        }

        private static String rtfAlign(int align) {
            return align == 0 ? "\\qc" : (align > 0 ? "\\qr" : "\\ql");
        }

        private static void rtfRuns(StringBuilder rtf, List<Run> runs,
                                    boolean allBold, boolean allItalic) {
            for (Run run : runs) {
                boolean bold = run.bold || allBold;
                boolean italic = run.italic || allItalic;
                if (!bold && !italic && !run.mono) {
                    rtf.append(rtfText(run.text));
                    continue;
                }
                // A group, so the switches turn themselves off again at its end.
                rtf.append('{');
                if (bold) rtf.append("\\b ");
                if (italic) rtf.append("\\i ");
                if (run.mono) rtf.append("\\f1 ");
                rtf.append(rtfText(run.text)).append('}');
            }
        }

        // Text as RTF: its three special characters escaped, and everything outside
        // ASCII written as \\uN? - the decimal code point as a SIGNED 16-bit number,
        // which is what the format says, followed by the "?" a reader too old to
        // understand that control word shows instead. So the whole file stays ASCII,
        // and no encoding anywhere can change what it says.
        private static String rtfText(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\\') out.append("\\\\");
                else if (c == '{') out.append("\\{");
                else if (c == '}') out.append("\\}");
                else if (c == '\t') out.append("\\tab ");
                else if (c == '\n') out.append("\\line ");
                else if (c >= ' ' && c < 127) out.append(c);
                else if (c >= 127) out.append("\\u").append((int) (short) c).append('?');
            }
            return out.toString();
        }

        // ---- DOCX ----

        private static final String XML_HEAD =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
        private static final String W_NS =
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        private static final String R_NS =
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        private static final String RELS_NS =
                "http://schemas.openxmlformats.org/package/2006/relationships";
        // Declared on w:document only when there is a picture in it: r for the
        // relationship a drawing points at, and the three DrawingML namespaces the
        // drawing itself is written in.
        private static final String DRAWING_NS = " xmlns:r=\"" + R_NS + "\""
                + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/"
                + "wordprocessingDrawing\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";
        private static final String PICTURE_URI =
                "http://schemas.openxmlformats.org/drawingml/2006/picture";
        private static final String CONTENT_TYPES_HEAD = XML_HEAD
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
                + "content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/"
                + "vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>";
        private static final String CONTENT_TYPES_TAIL =
                "<Override PartName=\"/word/document.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/"
                + "vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
        private static final String ROOT_RELS = XML_HEAD
                + "<Relationships xmlns=\"" + RELS_NS + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + R_NS + "/officeDocument\""
                + " Target=\"word/document.xml\"/></Relationships>";

        /**
         * The document as a .docx: the five parts of the smallest package Word, Pages,
         * LibreOffice and a layout application will all open, plus one media part per
         * picture it places.
         */
        byte[] docx() throws IOException {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip =
                         new java.util.zip.ZipOutputStream(bytes)) {
                entry(zip, "[Content_Types].xml", contentTypes());
                entry(zip, "_rels/.rels", ROOT_RELS);
                entry(zip, "word/_rels/document.xml.rels", documentRels());
                entry(zip, "word/styles.xml", styles());
                entry(zip, "word/document.xml", documentXml());
                for (Image image : media.values()) {
                    entry(zip, "word/media/" + image.part(), image.bytes);
                }
            }
            return bytes.toByteArray();
        }

        private static void entry(java.util.zip.ZipOutputStream zip, String name,
                                  String xml) throws IOException {
            entry(zip, name, xml.getBytes(StandardCharsets.UTF_8));
        }

        private static void entry(java.util.zip.ZipOutputStream zip, String name,
                                  byte[] content) throws IOException {
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            zip.write(content);
            zip.closeEntry();
        }

        // Every extension in the package needs a content type, images included - and it
        // has to be the type the bytes really are, which is what mediaType() reads.
        private String contentTypes() {
            StringBuilder xml = new StringBuilder(CONTENT_TYPES_HEAD);
            java.util.Set<String> written = new java.util.HashSet<>();
            for (Image image : media.values()) {
                if (written.add(image.extension)) {
                    xml.append("<Default Extension=\"").append(image.extension)
                       .append("\" ContentType=\"").append(image.mime).append("\"/>");
                }
            }
            return xml.append(CONTENT_TYPES_TAIL).toString();
        }

        // What word/document.xml is allowed to point at: the styles, and one image part
        // per placed picture under the id the drawing names (see docxDrawing).
        private String documentRels() {
            StringBuilder xml = new StringBuilder(XML_HEAD);
            xml.append("<Relationships xmlns=\"").append(RELS_NS).append("\">")
               .append("<Relationship Id=\"rId1\" Type=\"").append(R_NS)
               .append("/styles\" Target=\"styles.xml\"/>");
            for (Image image : media.values()) {
                xml.append("<Relationship Id=\"rId").append(image.relId).append("\" Type=\"")
                   .append(R_NS).append("/image\" Target=\"media/").append(image.part())
                   .append("\"/>");
            }
            return xml.append("</Relationships>").toString();
        }

        // The named styles, which are the point of exporting DOCX rather than RTF: a
        // layout application imports a document by mapping style names onto its own, so
        // the headings are Heading 1..6 under their conventional names and not just
        // bigger type.
        private static String styles() {
            StringBuilder xml = new StringBuilder(XML_HEAD);
            xml.append("<w:styles xmlns:w=\"").append(W_NS).append("\">")
               .append("<w:docDefaults><w:rPrDefault><w:rPr>")
               .append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/>")
               .append("<w:sz w:val=\"").append(BODY_SIZE).append("\"/>")
               .append("</w:rPr></w:rPrDefault><w:pPrDefault><w:pPr>")
               .append("<w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/>")
               .append("</w:pPr></w:pPrDefault></w:docDefaults>")
               .append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">")
               .append("<w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
            for (int level = 1; level <= HEADING_SIZE.length; level++) {
                xml.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading")
                   .append(level).append("\"><w:name w:val=\"heading ").append(level)
                   .append("\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>")
                   .append("<w:pPr><w:keepNext/>")
                   .append("<w:spacing w:before=\"240\" w:after=\"120\"/>")
                   .append("<w:outlineLvl w:val=\"").append(level - 1).append("\"/></w:pPr>")
                   .append("<w:rPr><w:b/><w:sz w:val=\"").append(HEADING_SIZE[level - 1])
                   .append("\"/></w:rPr></w:style>");
            }
            xml.append("<w:style w:type=\"paragraph\" w:styleId=\"Code\">")
               .append("<w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>")
               .append("<w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/>")
               .append("<w:ind w:left=\"").append(INDENT).append("\"/></w:pPr>")
               .append("<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>")
               .append("<w:sz w:val=\"").append(CODE_SIZE).append("\"/></w:rPr></w:style>")
               .append("<w:style w:type=\"paragraph\" w:styleId=\"Quote\">")
               .append("<w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/>")
               .append("<w:pPr><w:ind w:left=\"").append(INDENT).append("\"/></w:pPr>")
               .append("<w:rPr><w:i/></w:rPr></w:style>")
               .append("</w:styles>");
            return xml.toString();
        }

        private String documentXml() {
            StringBuilder xml = new StringBuilder(XML_HEAD);
            xml.append("<w:document xmlns:w=\"").append(W_NS).append('"');
            if (!media.isEmpty()) xml.append(DRAWING_NS);
            xml.append("><w:body>");
            for (Block block : blocks) docx(xml, block);
            // An empty paragraph to end on: a body whose last element is a table is
            // what Word repairs documents for, and an empty body needs something.
            // The section: the page, and the margins that leave the text frame every
            // width above was measured against. Landscape says so as well as measuring
            // it - a reader that only looks at w:orient would print the right way round,
            // and one that only looks at the two dimensions already has them.
            xml.append("<w:p/>")
               .append("<w:sectPr><w:pgSz w:w=\"").append(pageWidth)
               .append("\" w:h=\"").append(pageHeight)
               .append(landscape ? "\" w:orient=\"landscape\"/>" : "\"/>")
               .append("<w:pgMar w:top=\"").append(MARGIN).append("\" w:right=\"")
               .append(MARGIN).append("\" w:bottom=\"").append(MARGIN)
               .append("\" w:left=\"").append(MARGIN)
               .append("\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>")
               .append("</w:sectPr></w:body></w:document>");
            return xml.toString();
        }

        // The elements of w:pPr and w:rPr have to come in the schema's order, which is
        // why each of these writes them in one fixed sequence rather than as needed.
        private void docx(StringBuilder xml, Block block) {
            switch (block.kind) {
                case RULE:
                    xml.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\"")
                       .append(" w:space=\"1\" w:color=\"auto\"/></w:pBdr></w:pPr></w:p>");
                    return;
                case TABLE:
                    docxTable(xml, block.table);
                    return;
                case HEADING:
                    xml.append("<w:p><w:pPr><w:pStyle w:val=\"Heading")
                       .append(Math.min(block.level, HEADING_SIZE.length))
                       .append("\"/></w:pPr>");
                    docxRuns(xml, block.runs, false, false);
                    break;
                case CODE:
                    xml.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr>");
                    docxRuns(xml, block.runs, false, false);
                    break;
                case ITEM:
                    // The marker is written as text, not as list markup: real numbering
                    // means a numbering.xml part with its own definitions, and what
                    // this converter knows is the character in front of the item.
                    xml.append("<w:p><w:pPr><w:spacing w:after=\"60\"/><w:ind w:left=\"")
                       .append(INDENT * block.level).append("\" w:hanging=\"")
                       .append(INDENT).append("\"/></w:pPr>")
                       .append("<w:r><w:t xml:space=\"preserve\">").append(xml(block.marker))
                       .append("</w:t><w:tab/></w:r>");
                    docxRuns(xml, block.runs, false, false);
                    break;
                case QUOTE:
                    // Italic comes from the Quote style, not from the runs: a reader
                    // importing this document should see a quotation, not a paragraph
                    // that happens to be in italics.
                    xml.append("<w:p><w:pPr><w:pStyle w:val=\"Quote\"/><w:ind w:left=\"")
                       .append(INDENT * block.level).append("\"/></w:pPr>");
                    docxRuns(xml, block.runs, false, false);
                    break;
                default:
                    xml.append("<w:p>");
                    docxRuns(xml, block.runs, false, false);
            }
            xml.append("</w:p>");
        }

        private void docxTable(StringBuilder xml, Table table) {
            int columns = Math.max(1, table.columns);
            int width = frameWidth / columns;      // the frame of THIS page, not of A4 portrait
            xml.append("<w:tbl><w:tblPr><w:tblW w:w=\"").append(frameWidth)
               .append("\" w:type=\"dxa\"/><w:tblBorders>");
            for (String side : new String[] { "top", "left", "bottom", "right",
                                              "insideH", "insideV" }) {
                xml.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"8\"")
                   .append(" w:space=\"0\" w:color=\"auto\"/>");
            }
            xml.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>");
            for (int c = 0; c < columns; c++) {
                xml.append("<w:gridCol w:w=\"").append(width).append("\"/>");
            }
            xml.append("</w:tblGrid>");
            for (int r = 0; r < table.rows.size(); r++) {
                boolean header = r == 0;
                xml.append("<w:tr>");
                if (header) xml.append("<w:trPr><w:tblHeader/></w:trPr>");
                List<List<Run>> row = table.rows.get(r);
                for (int c = 0; c < columns; c++) {
                    xml.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(width)
                       .append("\" w:type=\"dxa\"/></w:tcPr><w:p>");
                    if (table.align[c] >= 0) {
                        xml.append("<w:pPr><w:jc w:val=\"")
                           .append(table.align[c] == 0 ? "center" : "right")
                           .append("\"/></w:pPr>");
                    }
                    docxRuns(xml, c < row.size() ? row.get(c) : new ArrayList<>(),
                            header, false);
                    xml.append("</w:p></w:tc>");
                }
                xml.append("</w:tr>");
            }
            xml.append("</w:tbl>");
        }

        private void docxRuns(StringBuilder xml, List<Run> runs,
                              boolean allBold, boolean allItalic) {
            for (Run run : runs) {
                if (run.image != null) {
                    docxDrawing(xml, run.image);
                    continue;
                }
                boolean bold = run.bold || allBold;
                boolean italic = run.italic || allItalic;
                xml.append("<w:r>");
                if (bold || italic || run.mono) {
                    xml.append("<w:rPr>");
                    if (run.mono) {
                        xml.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/>");
                    }
                    if (bold) xml.append("<w:b/>");
                    if (italic) xml.append("<w:i/>");
                    xml.append("</w:rPr>");
                }
                // xml:space, because a run can legitimately be " " - the space between
                // two differently formatted words.
                xml.append("<w:t xml:space=\"preserve\">").append(xml(run.text))
                   .append("</w:t></w:r>");
            }
        }

        // A picture in the text flow: wp:inline, as opposed to the floating wp:anchor a
        // word processor uses for a picture text wraps around. Its size is stated twice,
        // as the frame the page gives it (wp:extent) and as the picture's own extent
        // inside that frame (a:ext), which is what makes it fill the frame exactly.
        //
        // The element order is the schema's, not a preference: a .docx whose children come
        // in another order is a document Word offers to repair. The ids only have to be
        // unique within the document, so they are simply counted.
        private void docxDrawing(StringBuilder xml, Image image) {
            int id = ++drawings;
            xml.append("<w:r><w:drawing>")
               .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
               .append("<wp:extent cx=\"").append(image.cx).append("\" cy=\"")
               .append(image.cy).append("\"/>")
               .append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id)
               .append("\" descr=\"").append(attr(image.fileName)).append("\"/>")
               .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/>")
               .append("</wp:cNvGraphicFramePr>")
               .append("<a:graphic><a:graphicData uri=\"").append(PICTURE_URI).append("\">")
               .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id)
               .append("\" name=\"").append(attr(image.fileName)).append("\"/>")
               .append("<pic:cNvPicPr/></pic:nvPicPr>")
               .append("<pic:blipFill><a:blip r:embed=\"rId").append(image.relId).append("\"/>")
               .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
               .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"")
               .append(image.cx).append("\" cy=\"").append(image.cy).append("\"/></a:xfrm>")
               .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
               .append("</pic:pic></a:graphicData></a:graphic>")
               .append("</wp:inline></w:drawing></w:r>");
        }

        // Text as XML content. The control characters XML 1.0 cannot carry are dropped
        // rather than written: a model's answer is not guaranteed clean, and one stray
        // byte would make the whole part unreadable and the document unopenable.
        private static String xml(String text) {
            StringBuilder out = new StringBuilder(text.length());
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '&') out.append("&amp;");
                else if (c == '<') out.append("&lt;");
                else if (c == '>') out.append("&gt;");
                else if (c == '\t' || c == '\n' || c >= ' ') out.append(c);
            }
            return out.toString();
        }

        /** The same, for an attribute value, where a quote would end the attribute. */
        private static String attr(String text) {
            return xml(text).replace("\"", "&quot;");
        }
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

        // Named, because four fields of the same width holding four numbers are
        // otherwise told apart only by where they sit in the grid.
        javax.swing.JTextField widthF  = new javax.swing.JTextField(String.valueOf(prefill.width), 6);
        widthF.setName("windowWidth");
        javax.swing.JTextField heightF = new javax.swing.JTextField(String.valueOf(prefill.height), 6);
        heightF.setName("windowHeight");
        javax.swing.JTextField xF      = new javax.swing.JTextField(String.valueOf(prefill.x), 6);
        xF.setName("windowX");
        javax.swing.JTextField yF      = new javax.swing.JTextField(String.valueOf(prefill.y), 6);
        yF.setName("windowY");

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
        final boolean image;      // true = image_url part, false = text or input_audio
        final String text;        // text part: the literal text
        final String dataUrl;     // image part: "data:<mime>;base64,<...>"
        final String maskHash;    // image/text/audio include hash for masking, or null
        // Audio part: the base64 and the format name that go into "input_audio", or null
        // for every other kind of part. Bare base64 and not a data URL, which is what
        // that part is specified to carry - the format travels in its own field beside it
        // rather than as a MIME type inside the string.
        final String base64;
        final String audioFormat;
        Part(boolean image, String text, String dataUrl, String maskHash,
             String base64, String audioFormat) {
            this.image = image; this.text = text; this.dataUrl = dataUrl; this.maskHash = maskHash;
            this.base64 = base64; this.audioFormat = audioFormat;
        }
        static Part text(String t) {
            return new Part(false, t, null, null, null, null);
        }
        static Part includedText(String t, String h) {
            return new Part(false, t, null, h, null, null);
        }
        static Part image(String url, String h) {
            return new Part(true, null, url, h, null, null);
        }
        static Part audio(String b64, String format, String h) {
            return new Part(false, null, null, h, b64, format);
        }
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
            } else if (kind.equals("audio")) {
                // The recording as it is, base64, in an "input_audio" part - no transcript
                // made here, no re-encoding, nothing but the bytes and the name of the
                // format they are in (see audioFormat).
                byte[] bytes = Files.readAllBytes(path);
                parts.add(Part.audio(java.util.Base64.getEncoder().encodeToString(bytes),
                        audioFormat(path), hash));
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

    // The name that goes into an input_audio part's "format" field: the file's own
    // extension, lowercased.
    //
    // Not a MIME type and not a table of them - that field is a short format name, and the
    // two the API takes ("wav", "mp3") are spelled exactly like the extensions. So an
    // extension is the answer, and a file that is neither is sent under its own name rather
    // than relabelled as one of the two it is not; includeOne says in the log that such a
    // file will be refused. No extension at all -> "wav", that being the one format with a
    // header you can be sure of.
    private static String audioFormat(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String ext = (dot > 0 && dot < n.length() - 1)
                ? n.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        return ext.isEmpty() ? "wav" : ext;
    }

    // Appends the REAL "content" value for one user turn: a JSON string when it's
    // a single plain-text part, otherwise an array of text/image_url/input_audio parts.
    //
    // The order is the prompt's own, part for part, whatever the parts are. JRock does not
    // sort them - not images before text, not a recording before the question it belongs to.
    // Where the tokens sit in the prompt is the one place that order is decided, because it
    // is the only place you can see it; a model that wants the recording first is a model
    // you put the @audio token first for. (Voxtral is one: it reads a recording as the
    // instruction rather than as something the instruction is about, so a question typed in
    // front of it is answered as a question about nothing. Move the token, or leave the text
    // out - that is a prompt, and prompts are yours.)
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
            } else if (p.audioFormat != null) {
                // Base64 and a format name, both already free of anything JSON would have
                // to escape - but escaped all the same, because the alternative is a rule
                // about this one string that the next reader has to take on trust.
                sb.append("{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"")
                        .append(jsonEscape(p.base64)).append("\",\"format\":\"")
                        .append(jsonEscape(p.audioFormat)).append("\"}}");
            } else {
                sb.append("{\"type\":\"text\",\"text\":\"")
                        .append(jsonEscape(p.text)).append("\"}");
            }
        }
        sb.append("]");
    }

    // Appends the MASKED "content" value for one user turn, mirroring the real shape but
    // replacing content: an included text/image/recording -> "<txt|img|audio masked
    // <hash>>", ordinary prompt text -> "<input masked>".
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
            } else if (p.audioFormat != null) {
                // The format is kept and the recording is not: the masked copy is there to
                // be read, and a minute of audio is about a megabyte of base64.
                sb.append("{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"")
                        .append("<audio masked ").append(p.maskHash).append(">")
                        .append("\",\"format\":\"").append(jsonEscape(p.audioFormat))
                        .append("\"}}");
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

    // Appends the clock's message, with its trailing comma, to a messages array that
    // is still being built - it always goes first, so there is always something after
    // it. One helper for both copies of the request (real and masked) because the two
    // have to stay the same shape, and this message is identical in both.
    //
    // A "system" message, not a "user" one: the time is not something the operator
    // said, and an extra user turn in front of the real one would break the
    // user/assistant alternation that several models on mantle insist on.
    //
    // The clock goes out exactly as the checkbox says, for every model and every kind of
    // include. It is not moved into the user's turn, not dropped behind your back and not
    // switched off for you when a request also carries a recording: folding it into the
    // message was tried and taken out again - it is a text model's feature, it makes no
    // difference to a transcription, and a request that quietly disagrees with the checkbox
    // is worse than one that does what it was told to do. If some model does refuse the
    // pair, untick Clock: that is one click in the same window.
    private static void appendClockMessage(StringBuilder messages, String clockNow) {
        messages.append("{\"role\":\"system\",\"content\":\"")
                .append(jsonEscape(clockNow)).append("\"},");
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

    // One downloaded address: the bytes, the media type they were served as, and where
    // they really came from after any redirect. Everything Fetch URL decides from (see
    // fetchUrl), and all three are needed: a picture is bytes, what kind of thing it is
    // comes from the Content-Type rather than from the URL, and the name it is saved
    // under comes from the address that answered.
    private static final class UrlReply {
        final int status;
        final String contentType;
        final String finalUrl;
        final byte[] body;
        UrlReply(int status, String contentType, String finalUrl, byte[] body) {
            this.status = status;
            this.contentType = (contentType == null) ? "" : contentType;
            this.finalUrl = finalUrl;
            this.body = body;
        }
    }

    private interface HttpTransport {
        // Sends one request and returns its status + body. A null body means no
        // request body. Throws on any transport-level failure.
        HttpReply send(String method, String url, List<String[]> headers,
                       String body, int timeoutSeconds) throws Exception;

        // Fetches one address that is NOT a Bedrock endpoint, as bytes. Separate from
        // send() because the two are asked different things: send() carries JSON to an
        // endpoint this application knows, with credentials attached and a text answer
        // expected, while this one carries nothing, goes wherever it is told, and has to
        // bring back the response's own media type and an image's bytes intact.
        UrlReply fetch(String url, int timeoutSeconds) throws Exception;

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
            // UTF-8 spelled out, rather than ofString()'s "whatever the response's
            // Content-Type says": JSON is UTF-8 by RFC 8259, and a gateway that
            // mislabels it (charset=ISO-8859-1 is the classic) would otherwise turn
            // every accented character into two - "für" into "fÃ¼r" - with no way
            // to tell from the text that the transport was what broke it.
            HttpResponse<String> resp = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpReply(resp.statusCode(), resp.body());
        }

        @Override
        public UrlReply fetch(String url, int timeoutSeconds) throws Exception {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    // Named, because a server that is given no User-Agent at all is a
                    // server that sometimes answers 403 instead of the page.
                    .header("User-Agent", "JRock/" + VERSION)
                    .GET()
                    .build();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                    // Links move, and a link that has moved is still the link the user
                    // pasted. NORMAL rather than ALWAYS: it declines an https address
                    // that redirects to http, which is a downgrade nobody asked for.
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<byte[]> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new UrlReply(resp.statusCode(),
                    resp.headers().firstValue("content-type").orElse(""),
                    resp.uri() == null ? url : resp.uri().toString(),
                    resp.body());
        }

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
    //   browserHttpInfo() -> a small flat JSON object naming the page's client
    //                        ({"transport":...}), for the startup log.
    //   browserHttpSend() -> "<status>\n<body>". Status 0 means the request never
    //                        completed and the body is the reason, ready to show.
    // Request headers travel in the other direction as a flat JSON object, the
    // Authorization header among them - JRock holds the key in the browser exactly as
    // it does on the desktop, in the working folder's JRock/bedrock-key.txt, which is
    // what makes it outlive a reload.
    //
    // The page configures nothing. It says what its client is called, so the startup
    // log can name it, and that is the whole of what it is asked.
    //
    // Two optional fields used to override JRock's own settings - "region" pinned the
    // region, and "credentials":"page" said the page held the Bedrock key and attached
    // it itself. Both are gone as of 2.2.0, and the page that ships here never set
    // either. Every setting lives in the working folder's files on every runtime, so
    // there is one place each one is read from and one answer to what it is - rather
    // than a file, a page that may disagree with it, and an order of precedence
    // between them to remember.
    static native String browserHttpInfo();
    static native String browserHttpSend(String method, String url,
                                         String headersJson, String body,
                                         int timeoutSeconds);

    // Fetch URL (Ctrl+U) over the same bridge: any address, no credentials, and the
    // answer as bytes rather than as text.
    //   browserHttpFetch() -> "<status>\n<content-type>\n<final url>\n<base64 body>",
    //                         or "0\n<reason>" when the request never completed.
    // Base64 because this carries pictures: the bridge hands over a Java String, and
    // a PNG put through one is a PNG no longer. Three header lines rather than a JSON
    // object, for the same reason the rest of this bridge has none - each side reads
    // what it needs with indexOf.
    static native String browserHttpFetch(String url, int timeoutSeconds);

    // Clipboard, over the same bridge and the same "<ok>\n<rest>" wire format.
    // CheerpJ gives the JVM a clipboard of its own, private to the tab's Java
    // world, so without this a copy in JRock cannot be pasted into an email and
    // text copied from one cannot get in. The page has the real one.
    //   browserClipboardRead()  -> "1\n<text>", or "0\n<reason it was refused>".
    //   browserClipboardWrite() -> "1\n", or "0\n<reason>".
    static native String browserClipboardRead();
    static native String browserClipboardWrite(String text);

    // The hosting page's own header and footer, again over the same wire format.
    // The page hides them a few seconds after launch to give the Swing display the
    // whole tab; this asks it to put them back, or to take them away again.
    //   browserToggleChrome() -> "1\nshown" or "1\nhidden" - which it now is - or
    //                            "0\n<reason>".
    // One call FLIPS the state, so JRock never has to track it: the page owns the
    // chrome, including whether it is currently there.
    static native String browserToggleChrome();

    // Downscaling an image, over the same bridge and the same "<ok>\n<rest>" format.
    // The browser JVM has no image pipeline to speak of - ImageIO.read wants a native
    // library CheerpJ cannot load (see ImageHeader) and CheerpJ's Graphics2D resamples
    // nothing - while the browser decodes, resamples and encodes PNG and JPEG for a
    // living. So the page does it, on a canvas.
    //   browserScaleImage("<base64 in>", width, height, "image/png"|"image/jpeg")
    //       -> "1\n<base64 out>", or "0\n<reason>".
    // Base64 both ways, for the reason browserHttpFetch has it: the bridge carries
    // Java Strings, and a JPEG put through one is a JPEG no longer.
    static native String browserScaleImage(String base64, int width, int height,
                                           String mime);

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

        // Fetch URL, through the page's client as well - the same client, one method
        // along (see browserHttpFetch). The page is the only thing in a browser tab that
        // can reach the network at all, so there is nothing else for this to be.
        //
        // What the browser cannot do anything about is the other end: a page may only
        // read an address that permits a cross-origin request, so a site without CORS
        // headers refuses this where the desktop build simply downloads it. That failure
        // arrives as a reason from the page, and is reported as it stands.
        @Override
        public UrlReply fetch(String url, int timeoutSeconds) throws Exception {
            String framed;
            try {
                framed = browserHttpFetch(url, timeoutSeconds);
            } catch (Throwable ex) {
                throw new IOException("This page's HTTP bridge cannot fetch other "
                        + "addresses (an older jrock-web page does not have it): "
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            }
            if (framed == null || framed.isEmpty()) {
                throw new IOException("Browser HTTP bridge returned an empty reply.");
            }

            // "<status>\n<content-type>\n<final url>\n<base64>", or "0\n<reason>".
            String[] lines = framed.split("\n", 4);
            int status;
            try {
                status = Integer.parseInt(lines[0].trim());
            } catch (NumberFormatException ex) {
                throw new IOException("Browser HTTP bridge returned a malformed reply: "
                        + framed);
            }
            if (status == 0) {
                String why = (lines.length > 1) ? lines[1].trim() : "";
                throw new IOException(why.isEmpty()
                        ? "Browser fetch failed (no reason reported)." : why);
            }
            if (lines.length < 4) {
                throw new IOException("Browser HTTP bridge returned a short reply: "
                        + framed);
            }
            byte[] body;
            try {
                body = java.util.Base64.getMimeDecoder().decode(lines[3].trim());
            } catch (IllegalArgumentException ex) {
                throw new IOException("Browser HTTP bridge returned a body that is not "
                        + "base64: " + ex.getMessage(), ex);
            }
            return new UrlReply(status, lines[1].trim(), lines[2].trim(), body);
        }

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

    // Best-effort read of a string field ("transport":"...") from a small flat JSON
    // object - the bridge's info reply, which is the only JSON either side parses.
    // Escapes are not decoded, the reported values being plain text. Returns null
    // when the field isn't there.
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
    private static String[] callModel(String prompt, java.util.List<String[]> history,
                                      boolean clock) throws Exception {
        HttpTransport http = http();
        String apiKey = resolveApiKey();
        boolean haveKey = apiKey != null && !apiKey.isBlank();
        if (!haveKey) {
            return new String[] {
                "0",
                "No Bedrock API key set. Put one in JRock/bedrock-key.txt, or open "
                    + "the Configure dialog (top-left button) and enter a key - which "
                    + "writes it there.",
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

        // The clock, when the checkbox is on: read once here, so the request, the
        // masked copy in the log and the file on disk all carry the same instant. Its
        // own file in JRock/messages/ is written at the same time, for the record -
        // nothing ever reads it back (see writeMessageFile).
        String clockNow = clock ? clockMessage() : null;
        if (clockNow != null) {
            writeMessageFile(ROLE_CLOCK, LocalDateTime.now().format(STAMP_FMT), clockNow);
        }

        // Build the real messages array. Human turns - both prior ones (extend
        // mode) and the new turn - are expanded via buildParts so their @img/@txt
        // tokens become image/text content parts. Assistant turns are plain text.
        StringBuilder messages = new StringBuilder("[");
        if (clockNow != null) appendClockMessage(messages, clockNow);
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
        String maskedRequestBody = maskRequest(history, parts, clockNow);

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

        // The reply as it arrived, kept only to mask it out of the raw response
        // below: that works by finding it as a substring of the raw body, which a
        // normalized copy would no longer match.
        String rawReply = extractContent(resp.body);
        String reply = stripMessage(rawReply);

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
        String maskedResponse = maskResponse(resp.body, rawReply);

        String details = rawDump(maskedRequestBody, maskedResponse)
                + "\n\n--- stats ---"
                + "\nInput text symbols:  " + inputSymbols
                + "\nOutput text symbols: " + outputSymbols
                + "\nInput tokens:   " + tokenStr(inputTokens)
                + "\nOutput tokens:  " + tokenStr(outputTokens)
                + priceLines(inputTokens, outputTokens);

        return new String[] { "1", reply, details };
    }

    // The two headers of the dump. Constants because the log names the message file
    // each half went into in them once both files exist, which is after this is built -
    // see LogView.grayDump and namedHeader.
    private static final String RAW_REQUEST_HEADER = "--- raw request ---";
    private static final String RAW_RESPONSE_HEADER = "--- raw response ---";

    // The raw request/response dump logged after a call, for either outcome. The
    // response body is the caller's choice: masked on success (the reply is already
    // shown above), verbatim on failure. A success then appends its stats block.
    private static String rawDump(String maskedRequestBody, String responseBody) {
        return RAW_REQUEST_HEADER + "\nPOST " + pathOf(endpoint()) + "\n" + maskedRequestBody
                + "\n\n" + RAW_RESPONSE_HEADER + "\n" + responseBody;
    }

    private static String tokenStr(long v) {
        return v < 0 ? "(not reported)" : Long.toString(v);
    }

    // What a request like this one costs, in the roughest terms that are still useful:
    // dollars per million tokens, low and high, across the frontier text models as a
    // group. Output is the expensive half by a factor of about five, which is the one
    // thing about pricing worth carrying in your head.
    //
    // A band and not a number, on purpose. JRock talks to whatever endpoint it is pointed
    // at - Bedrock, an OpenAI-compatible gateway, something local and free - and looking
    // up the real rate behind that URL is not this window's job. The band answers the
    // question actually being asked after a long reply, which is not "what do I owe" but
    // "was that cents or dollars".
    private static final double PRICE_IN_LOW = 2.0;
    private static final double PRICE_IN_HIGH = 5.0;
    private static final double PRICE_OUT_LOW = 10.0;
    private static final double PRICE_OUT_HIGH = 25.0;

    // The two price lines of the stats block: the rule of thumb, then this exchange
    // costed by it. Both say they are a guide - a figure in dollars invites being read as
    // a bill, and this one never is.
    //
    // No counts from the API means nothing to multiply. The guide line still goes out:
    // the rates are worth seeing either way, and a silent stats block would look like the
    // estimate had been dropped.
    private static String priceLines(long inputTokens, long outputTokens) {
        String guide = "\nRough price guide: $" + rate(PRICE_IN_LOW) + "-"
                + rate(PRICE_IN_HIGH) + " per 1M input tokens, $" + rate(PRICE_OUT_LOW)
                + "-" + rate(PRICE_OUT_HIGH) + " per 1M output (frontier average)";
        if (inputTokens < 0 || outputTokens < 0) {
            return guide + "\nRough cost here:   (no token counts came back to price)";
        }
        double inLow = inputTokens * PRICE_IN_LOW / 1000000.0;
        double inHigh = inputTokens * PRICE_IN_HIGH / 1000000.0;
        double outLow = outputTokens * PRICE_OUT_LOW / 1000000.0;
        double outHigh = outputTokens * PRICE_OUT_HIGH / 1000000.0;
        // One shape for all six figures, chosen from the largest: "$0.0057-0.014" reads
        // as a typo, and a range whose two ends are written to different precisions is
        // harder to compare than one that is simply too precise at the low end.
        String shape = shapeFor(inHigh + outHigh);
        return guide + "\nRough cost here:   in $" + usd(inLow, shape) + "-"
                + usd(inHigh, shape) + " + out $" + usd(outLow, shape) + "-"
                + usd(outHigh, shape) + " = $" + usd(inLow + outLow, shape) + "-"
                + usd(inHigh + outHigh, shape) + ", not this model's real price";
    }

    // A rate per million, without the ".0" that makes a round number look measured.
    private static String rate(double perMillion) {
        return (perMillion == Math.floor(perMillion))
                ? Long.toString((long) perMillion)
                : String.format(java.util.Locale.ROOT, "%.1f", perMillion);
    }

    // How many digits an amount of this size deserves: cents at a dollar, hundredths of
    // a cent below one. A request small enough to print as $0.0000 has answered the
    // question it was asked.
    private static String shapeFor(double dollars) {
        return (dollars >= 1.0) ? "%.2f" : (dollars >= 0.01) ? "%.3f" : "%.4f";
    }

    // Locale.ROOT because a decimal comma in a price reads as a thousands separator to
    // half the world.
    private static String usd(double dollars, String shape) {
        return String.format(java.util.Locale.ROOT, shape, dollars);
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
    // The clock message (or null when Clock is off) is the one thing carried over
    // verbatim: a time JRock generated itself is not the operator's content, and
    // masking the very line that says what time was sent would defeat logging it.
    private static String maskRequest(java.util.List<String[]> history,
                                      java.util.List<Part> parts, String clockNow)
            throws IOException {
        StringBuilder masked = new StringBuilder("[");
        if (clockNow != null) appendClockMessage(masked, clockNow);
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

    // Masks the assistant reply text inside the raw response body, and shortens the
    // response id, which is noise either way.
    private static String maskResponse(String rawResponse, String reply) {
        return shortenId(maskFirst(rawResponse, jsonEscape(reply), "<output masked>"));
    }

    // How much of the response id to keep: the "chatcmpl-" prefix plus four
    // characters, enough to tell two replies apart or to quote one in a support
    // question, which is all this field is ever good for.
    private static final int ID_KEPT_CHARS = 13;

    // Replaces the tail of the response's "id" with <...>. The id is ~40 characters
    // that nobody reads, and on its own long enough to put a horizontal scrollbar
    // under the raw response - so the rest of the dump has to be scrolled to be read.
    // Only the first "id" is touched: in a chat-completion response that is the
    // top-level one.
    private static String shortenId(String json) {
        int k = json.indexOf("\"id\"");
        if (k < 0) return json;
        int i = k + 4;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != ':') return json;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return json;
        int start = i + 1;
        int end = json.indexOf('"', start);   // ids are alphanumeric: no escapes to skip
        if (end < 0) return json;
        // Don't "shorten" something that is already short: the elision costs 5 of its own.
        if (end - start <= ID_KEPT_CHARS + 5) return json;
        return json.substring(0, start + ID_KEPT_CHARS) + "<...>" + json.substring(end);
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
                    case 'u':
                        // JSON's numeric escape, which a server may use for any
                        // non-ASCII character - so without this, "ü" reached the
                        // pane as the literal text u00fc, and a Cyrillic or emoji
                        // reply became a wall of u04xx. A character outside the BMP
                        // arrives as a surrogate PAIR of these escapes, and appending
                        // each unit in turn is what puts it back together.
                        if (i + 4 < json.length() && isHex4(json, i + 1)) {
                            sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                            i += 4;
                        } else {
                            sb.append(n);   // not a real escape; keep it as it came
                        }
                        break;
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

    // Whether four hex digits start at `at`. Integer.parseInt would accept a sign or
    // whitespace, which JSON does not, so the digits are checked first.
    private static boolean isHex4(String s, int at) {
        for (int i = at; i < at + 4; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
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

    // Takes over the prompt stored in the (just changed) working directory, and
    // returns the "Prompt source:" line for the session report.
    //
    // The same thing initSession does with that directory's log, and for the same
    // reason: after a switch, everything on screen is about the new directory. It is
    // also what keeps the file safe - every keystroke rewrites the prompt of the
    // current working directory in full, so whatever is left in the box is what that
    // directory will hold from then on, and a directory's prompt is the work someone
    // left in it.
    //
    // A directory with no stored prompt keeps what is on screen and is seeded with it,
    // which is how a prompt is carried into a new folder to work in.
    private static String adoptPromptOfWorkingDir(JTextArea input) {
        String stored = readFileQuietly(promptFile());
        if (stored == null) {
            savePromptQuietly(input.getText());
            return "carried over from the previous working directory";
        }
        input.setText(stored);        // autosave rewrites the same file with the same text
        input.setCaretPosition(0);
        return "recovered persistent file: JRock/jrock-prompt.txt";
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
        if (role.equals(ROLE_HUMAN)) return "operator";
        if (role.equals(ROLE_CLOCK)) return "clock";
        return "assistant";
    }

    // Builds the per-message file path from a validated stamp + role. The stamp
    // is ALWAYS a value we produced/validated (yyyyMMdd-HHmmss-SSS), never raw
    // user text, so the path can't escape logs/.
    private static Path messageFile(String role, String stamp) {
        return logsDir().resolve(stamp + "-" + roleSlug(role) + ".txt");
    }

    // Writes a message body to its own file in messages/. Written once and never
    // modified afterwards. Best-effort.
    //
    // Three kinds of file end up here: -operator.txt and -assistant.txt, which are the
    // dialog and are read back when the log is restored, and -clock.txt, which is not.
    // A clock file is written for the record only: nothing parses it, nothing loads it,
    // and the log has no line pointing at it (see clockMessage and parseMainLog). It is
    // there to answer "what time did it think it was?" after the fact.
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
