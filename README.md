# JRock

A minimal, dependency-free Swing GUI desktop client (playground) for **Amazon Bedrock**,
built as a single Java source file. It calls the OpenAI-compatible **Chat Completions
API** on the **`bedrock-mantle`** endpoint using a lightweight **Bedrock API key**, and
keeps every prompt and conversation only on your local disk, as plain, visible files.

> The Amazon Bedrock desktop GUI playground in Java that just works: every prompt and 
> session is saved to disk so nothing is ever lost, and your credentials stay put with no
> repeated sign-ins, so it keeps out of your way and lets you focus on the models.

![A fresh JRock session: the startup report, one prompt, and one reply from xai.grok-4.3](images/simple-request.png)

*A fresh session in `C:\demo\bedrock-api-lab`, which is also where the window title and its `BAL`
icon come from. Everything before the first prompt is JRock reporting what it configured and where
it put your files.*

By Ivan Khvostishkov, with assistance of Kiro and JetBrains IntelliJ IDEA.

---

## Highlights

- **Single file, zero dependencies.** Just `JRock.java`, no build tool, no jars, no AWS SDK.
- **Runs directly:** `java JRock.java`.
- **Bedrock API key auth** (Bearer token), no SigV4, no AWS SDK, no `~/.aws` credentials.
- **Everything local & transparent.** No server-side session state; all history lives in
  plain files under a `JRock/` folder you own and can inspect.
- **Crash-safe persistence** of the prompt and the full conversation.
- **Multimodal includes** (text and image files, plus PDF-to-text/PDF-to-images via
  Ghostscript and RTF-to-Markdown with no external tool at all) referenced by hash;
  multi-select supported.
- **A prompt library in plain files** — a folder of `.txt` prompts you can chain into a
  workflow, which is Bedrock Prompt management and Flows without the cloud
  ([`automation-samples/`](#prompt-library-and-chaining-automation-samples)).
- **Keyboard-driven**, with a Configure dialog for API key, region, model and working directory.

## Requirements

- **Minimum: JDK 11** — needed for single-file source launch (JEP 330) and
  `java.net.http.HttpClient`. On JDK 8-10 it won't run as a single file.
- **Maximum: none** — uses only core JDK APIs (`javax.swing`, `java.net.http`). Runs on
  the latest JDK.

## Getting started

1. **Get a Bedrock API key.** A short-term (recommended) key can be generated from the AWS
   console: https://console.aws.amazon.com/bedrock-mantle/api-keys
2. **Provide the key** either as an environment variable or via the in-app Configure dialog:

   ```powershell
   $env:BEDROCK_API_KEY = "..."
   $env:AWS_REGION = "us-east-1"   # optional; defaults to us-east-1
   ```

3. **Run it:**

   ```powershell
   java JRock.java
   # optionally load a prompt file on startup (read-only):
   java JRock.java path\to\prompt.txt
   # point Load/Save prompt (Ctrl+O / Ctrl+S) at a prompt library:
   java JRock.java --prompts-dir D:\prompts
   # ...and then name a prompt in it directly:
   java JRock.java --prompts-dir D:\prompts review.txt
   ```

4. Type a prompt and press **Ctrl+Enter** (or the **Send** button).

### The prompts directory

Prompts tend to live together in one folder, while the working directory is wherever
today's work is. The **prompts directory** separates the two:

- **Ctrl+O and Ctrl+S always open there.** Not "there the first time, then wherever you last
  browsed" — *every* time. A prompt library is a place you keep going back to, so a chooser
  that drifts adds a small navigation chore to every single load. If it's opening in the wrong
  place, that's a setting to change, not a folder to re-navigate.
- The **initial-prompt-file argument** is resolved relative to it, so it can be a bare file
  name. Absolute paths are unaffected.
- **Include (Ctrl+I)** and log exports are deliberately *not* affected — those files live
  wherever the source material is, so those choosers do remember where you last browsed.

Set it in the **Configure dialog** (its own row, under the working directory), or on the
command line with `--prompts-dir <dir>`. The dialog wins: changing it there replaces
whatever the flag asked for, for the rest of the session.

Unset, prompts simply **follow the working directory** — which is what a plain launch gets,
and what setting the prompts directory equal to the working directory means. That state isn't
a path frozen at launch: change the working directory later and prompts come with it.

On Windows, installing the **"JRock here!"** Explorer entries bakes in whichever prompts
directory is in effect at that moment — but only if it differs from the working directory,
since otherwise the flag would pin every future launch to today's folder. So the library
follows you into every folder; see
[Explorer right-click integration](#windows-explorer-right-click-integration).

For a ready-made one, point it at **`automation-samples/`** in this repository — two prompts
that chain into a document-archiving workflow, and a template for a library of your own. See
[Prompt library and chaining](#prompt-library-and-chaining-automation-samples).

`--prompts-dir=<dir>` works too, and the flag can come before or after the prompt file. The
directory is reported in the startup log whenever it isn't just the working directory —
including when the path isn't usable, in which case it's reported and ignored rather than
silently applied.

## JRock Web (in the browser)

JRock also runs in your browser at **https://jrock.nosocial.net/** — no locally
installed JVM required. It loads the same unmodified `jrock.jar` and runs it entirely
**locally in your browser** via [CheerpJ](https://cheerpj.com/) (which executes JVM bytecode
as WebAssembly), so nothing runs on a server.

- **Real Bedrock calls work.** The browser has no socket layer, so JRock detects the browser
  runtime and sends its requests through the page's own `fetch()` instead — see
  [HTTP transport](#http-transport).
- **Entering your API key is safe here.** The page shows the jar's SHA-256 so you can confirm
  it matches the reproducible build before typing anything. The region and key stay in the
  page's JavaScript (`window.myBrowserHttp`): they are never passed to the in-browser JVM and
  never sent anywhere but the Bedrock endpoint. You can change the key at any time — a
  missing or rejected one reopens the credentials dialog by itself.
- **Right-click is a long tap.** On touch devices, press and hold to open the context menus.
- **The page gets out of the way — and comes back.** A few seconds after launch the page
  hides its own header and footer, giving the Swing display the whole tab. To check the
  checksum again, or change the key, the top-bar context menu has **Show/hide the page
  header & footer**: it calls one function in the page, which flips the chrome and reports
  which way it went. The page owns that state, so JRock never has to guess.
- **Copy and paste reach other apps.** CheerpJ gives the JVM a clipboard of its own that
  nothing else can see, so JRock goes through the browser's clipboard instead: text moves
  between JRock and your mail or notes, by Ctrl+C/X/V or from the context menus. A browser
  may ask permission the first time a page reads the clipboard, and some refuse reads
  outright — paste then falls back to whatever was last copied inside JRock, and says so.
- **Image includes work too.** A browser JVM has no native libraries, so nothing here may
  depend on one — JRock reads an image's dimensions from its header rather than decoding it
  (see [Multimodal includes](#multimodal-includes-ctrli)).

![JRock running in a browser tab, saving just the selected reply to the downloads folder](images/web-save-selection.png)

*The same jar in a browser tab — a Swing window, its own file chooser and all. Only the reply is
selected, so the context menu offers **Save selected text as...**; saving into `downloads` hands the
file to the browser as a download.*

## Endpoint & API design

- Uses the **`bedrock-mantle`** endpoint. AWS recommends `bedrock-runtime` for new apps
  (where Amazon Nova, Meta Llama and other families are also available), but as of
  Sep 2026 several frontier models run on `bedrock-mantle` only. JRock prefers mantle for
  the simplicity of a single API surface.
- Uses **Chat Completions**, not the Responses API. Responses is *stateful* (the backend
  retains conversation state). JRock deliberately avoids that: it stores **nothing** on the
  backend. All history stays local, unlike a browser client where session data can hide
  non-transparently in cookies / sessionStorage / IndexedDB. Chat Completions is stateless,
  so each request carries its own context.

## HTTP transport

JRock issues exactly two kinds of request (`GET /v1/models` and `POST /v1/chat/completions`).
Which transport carries them is decided once at startup and reported in the log:

| Runtime | Transport | Credentials |
|---|---|---|
| Any normal JVM | `java.net.http.HttpClient` | `BEDROCK_API_KEY` env var, or the Configure dialog |
| CheerpJ (browser) | the page's `window.myBrowserHttp.fetch` | held by the page; never passed to the JVM |

In the browser there is no socket layer, so `HttpClient` cannot work at all and the hosting page
provides the transport instead. Any host page can serve JRock by providing
`window.myBrowserHttp.fetch(url, options)` resolving to `{ status, body }`;
`jrock-web/index.html` is the reference implementation. It attaches the `Authorization` header
itself, so the API key never reaches the JVM, and it reports the region it holds a key for, which
JRock adopts at startup.

Credentials are deliberately **not** read from JVM system properties (`-Dname=value`): a key
passed on a command line leaks into shell history and process listings.

## Models

Known model "cards" record each model's modalities, supported APIs/endpoints, and the
correct mantle path (`/v1/...` vs `/openai/v1/...`). The **Model** field in Configure is
free-text with a dropdown pre-populated from the most recently fetched available models.

Model matching: exact model-id match wins; otherwise a **vendor-prefix** partial match
(e.g. any `anthropic.*` or `openai.*`); otherwise a default config is used. This is logged
at startup and after reconfiguration.

Built-in cards include:

| Model | Model ID | Chat Completions on mantle |
|---|---|---|
| Grok 4.3 | `xai.grok-4.3` | yes (`/openai/v1`) |
| Kimi K2.5 | `moonshotai.kimi-k2.5` | yes (`/v1`) |
| DeepSeek-V3.1 | `deepseek.v3.1` | yes (`/v1`) |
| Qwen3 32B | `qwen.qwen3-32b` | yes (`/v1`) |
| GPT-5.4 | `openai.gpt-5.4` | yes (`/openai/v1`) |
| GPT-6 Astra | `openai.gpt-6-astra` | yes (`/openai/v1`) |
| Claude Opus 5 | `anthropic.claude-opus-5` | no (Messages API only) |
| Claude Fable 5.1 | `anthropic.claude-fable-5-1` | no (Messages API only) |

> Anthropic Claude models are served on mantle via the Anthropic **Messages API**
> (`/anthropic/v1/messages`), not Chat Completions. JRock doesn't implement Messages yet,
> so selecting a Claude model reports this and doesn't send a (broken) request.

## Conversation log

- The transcript shows **branded role headers** (`[HUMAN OPERATOR]` / `[OPERATOR'S
  ASSISTANT]`) in a teal brand color, with the actual dialog text in black and all
  system/status output in gray.
- **Dialog only** mode (Ctrl+D) hides the gray system lines, leaving a clean, copy-pastable
  transcript.
- **Extend conversation** mode (Ctrl+E): each send includes the full prior dialog so the
  model sees a continuous conversation (stateless multi-turn). In this mode role headers are
  timestamped (e.g. `[OPERATOR'S ASSISTANT] · Monday, 14 September 2026, 10:01:34`) so the
  time order of stateless turns is visible.
- **Stats** per response: input/output text symbols, and input/output tokens (from the API's
  `usage`).
- The **raw request and raw response** are shown for debugging, but prompt/reply/attachment
  content is **masked** (shown by hash/placeholder) so the transcript isn't a noisy duplicate
  and included files stay referenced only by hash. The response `id` is elided after its
  first few characters (`"id":"chatcmpl-abcd<...>"`) — in full it is long enough on its own
  to put a horizontal scrollbar under the dump.

![Two timestamped turns with Extend conversation and Dialog only both on](images/extend-dialog-only.png)

*Extend conversation (Ctrl+E) with Dialog only (Ctrl+D): the second question says "that risk" and
is answered correctly, because the whole prior dialog was resent. Every gray line is hidden, so
what's left is a clean, copy-pastable transcript — and the header timestamps keep the order of
these stateless turns visible.*

## Persistence (crash recovery + full local history)

Everything lives under a **`JRock/`** subfolder of the working directory:

- `JRock/jrock-prompt.txt` — the prompt, autosaved on every keystroke (atomic temp-file swap,
  so a crash can't corrupt it).
- `JRock/jrock-log.txt` — the conversation transcript, restored on startup so a session
  survives restarts.
- `JRock/messages/` — one **append-only** file per human/assistant message. These are never
  modified or deleted (not even by Clear log). `cat`-ing them in order reproduces the
  dialog-only transcript.
- `JRock/gs-pdf/` — per-page text/image files produced when a PDF is included via Ghostscript
  (see Multimodal includes).
- `JRock/rtf-md/` — the Markdown produced when an RTF is included as Markdown text
  (see Multimodal includes).

The main log is a bit-perfect copy of the pane, except that each role header is followed by an
`@<datetime>` include-style reference to the message's own file under `JRock/messages/`.

**Clear log** empties `jrock-log.txt` and the window but never touches `JRock/messages/`, so
paid-for inputs/outputs are preserved. If the log has changed since it was last exported with
**Ctrl+L** (Save log as a copy), Clear log first asks for confirmation and suggests saving.

## Multimodal includes (Ctrl+I)

Attach **text or image** files to a prompt (and convert **PDFs** or **RTFs** into either):

1. **Ctrl+I** opens a file picker. It's **multi-select**, so you can attach several files at
   once, and the dropdown offers five kinds:
   - **Image files** (png, jpg, jpeg, gif, webp)
   - **Text files as is** (txt, csv, html, java, rtf) — sent exactly as they are on disk,
     RTF markup and all, for a model that reads (and writes) the format itself
   - **PDF as text pages** — converts the PDF to one text file per page
   - **PDF as page images** — converts the PDF to one PNG per page
   - **RTF as Markdown text** — converts the RTF to one Markdown file
2. Each file is hashed (SHA-256, shortened to 12 hex digits). The hash → path mapping is kept **in memory only**
   (not persisted), so after a restart you must re-include files to reuse them.
3. A token `@img <hash>` or `@txt <hash>` is inserted at the cursor (one per file / per PDF
   page). Duplicate tokens for the same file are not added again (also checked across prior
   turns in Extend mode).
4. The log records each include with stats (locale-formatted numbers):
   - **Images**: dimensions, total pixel count, and file size in bytes.
   - **Text**: symbol count (Unicode code points) and file size in bytes.

Image dimensions are read **straight out of the file header** — the PNG, GIF, WEBP and JPEG
headers all state the size in a documented place — rather than by decoding the image (an
`ImageHeader` class of its own, since byte-level format parsing has nothing to do with the
rest of JRock and is worth being able to read, and measure, on its own). That is
the same arithmetic on every platform, so the browser build reports the same numbers as the
desktop one; `ImageIO` could not, because in CheerpJ it reaches for the JDK's *native* colour
management (`UnsatisfiedLinkError: no lcms in java.library.path`) and an image include failed
outright over a line of log text. It is also less work: a 40 MB photo is no longer decoded in
full just to say how big it is. If a header can't be read, the include still happens and the
log says the dimensions were unavailable — the bytes sent to the model are the file itself
either way, so none of this touches what the model receives.

### PDF conversion (Ghostscript)

Selecting a PDF filter runs **Ghostscript** to convert the PDF, one file per page, then
includes each produced page. Ghostscript must be on your PATH: `gswin64` on Windows, `gs` on
macOS and Linux.

A long conversion says so while it runs, rather than after. The command line is logged before
Ghostscript is started, and the conversion happens off the UI thread so the window stays
alive. On **Windows** JRock runs the **windowed** build (`gswin64.exe`, preferring it over
`gswin64c.exe`), which reports its progress in its own window and closes when finished.
Everywhere else the console `gs` is run with `-q` omitted, and its progress — `Page 1`,
`Page 2`, … — is echoed into the log as `gs:` lines while it works.

- Output is written under **`JRock/gs-pdf/`**, named `<pdfname>.gs.NNN.txt` (text pages via
  the `txtwrite` device) or `<pdfname>.gs.NNN.png` (page images at the **PDF image DPI** set
  in Configure — 150 by default).
- The exact Ghostscript command and its output are echoed to the log.
- If Ghostscript isn't found on your PATH, JRock logs a note, shows a dialog, and opens
  https://ghostscript.com/ so you can install it. (Text extraction quality depends on the
  PDF; for an LLM, page-image includes are a reliable fallback for tricky PDFs.)

![A PDF converted to two page images, attached by hash, and read back by the model](images/pdf-page-images.png)

*A transcript printed to PDF with Ctrl+P and included straight back as page images: the exact
Ghostscript command, both pages with their dimensions and byte counts, and the `@img` hash tokens
still sitting in the prompt. The model then reads its own transcript and answers from the image
alone. Below it, the masked raw request and response, and the token stats.*

### RTF conversion (no external tool)

Selecting **RTF as Markdown text** converts the `.rtf` to Markdown and includes *that* file as
an ordinary `@txt` token — so what the model receives is a text part, and the prompt shows one
token for the document.

Nothing has to be installed, unlike the PDF path: the reader is the JDK's own
`javax.swing.text.rtf.RTFEditorKit`, the same one a `JTextPane` uses, so this works on a bare
JVM. Markdown rather than flat text because the formatting is what RTF is for — headings, bold
and italic survive as markup a model reads as structure instead of being thrown away.

- Output is written under **`JRock/rtf-md/`**, named `<rtfname>.md` — `notes.rtf` becomes
  `notes.rtf.md`, so two RTFs with the same stem can't overwrite each other.
- What is mapped, and nothing more:

  | In the RTF | In the Markdown |
  |---|---|
  | paragraph | a block, separated by a blank line |
  | bold, italic | `**bold**`, `*italic*`, `***both***` |
  | a font size larger than the document's body size | a heading — `#`/`##`/`###` by how much larger (6 pt, 3 pt, any), short lines only |
  | a bullet character (Word writes the bullet as text) | a `-` list item; `1.`, `2)` … are kept as they are |

  Markdown's own characters in the text (`*`, `` ` ``, `\`, a leading `#`) are escaped, so a
  document that talks about asterisks still says so.
- **Not** attempted: tables (RTF table rows reach the reader as ordinary paragraphs, so their
  cells run together), embedded images, colours, alignment. Underline has no Markdown of its
  own and stays plain text rather than being invented into emphasis.
- A file that isn't really RTF (some other document renamed, say) has no text the reader can
  find; JRock logs that and includes nothing, rather than attaching an empty file.

On send, every referenced include is verified (known hash **and** the file still hashes the
same, i.e. unchanged); on any problem the message is not sent and the reason is logged. Valid
includes are expanded into a **multi-part message**: text segments become text parts, `@img`
becomes a base64 image part, `@txt` becomes a text part with the file's contents. In Extend mode,
includes in prior turns are expanded too.

## Prompt library and chaining (`automation-samples/`)

Bedrock has a managed answer to reusable prompts:
[Prompt management](https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-management.html)
stores prompts as AWS resources with *variables*, *variants* and immutable *versions*, edited in
a console *prompt builder*; [Flows](https://docs.aws.amazon.com/bedrock/latest/userguide/flows.html)
chains them by wiring one node's output into another's input, then publishes a version, points
an *alias* at it, and runs it with `InvokeFlow`.

JRock does the same thing with **plain text files in a folder you own**. A prompt is a `.txt`
file; the library is a directory ([the prompts directory](#the-prompts-directory)); a variable
is an [`@txt` / `@img` include token](#multimodal-includes-ctrli); and chaining is you —
**Ctrl+L** the part of the reply you want, **Ctrl+I** it into the next prompt.

| Bedrock | JRock |
|---|---|
| Prompt, stored as an AWS resource in one region | a `.txt` file in your prompts directory |
| Variable, filled in at invoke time | an `@txt` / `@img` token, filled in by Ctrl+I |
| Version / variant (immutable snapshot) | a file copy — or whatever your version control already does |
| Prompt builder in the console | the prompt pane, and any text editor |
| Flow: prompt nodes wired output → input, version + alias, `InvokeFlow` | Ctrl+L the output, Ctrl+I it into the next prompt |
| IAM permissions, service quotas, per-node billing | files |

Nothing to deploy, nothing to keep in sync with a region, and the whole workflow is
greppable. What you give up is automation: a flow runs itself, whereas this is two keystrokes
between the steps — which is also where you get to read the intermediate result before it
becomes the next prompt's input.

### The samples

`automation-samples/` holds two prompts that chain, and doubles as a template for a prompts
directory of your own:

- **`jrock-prompt-pdf-to-ascii.txt`** — turn a scanned or printed PDF into plain text that
  keeps its layout: ASCII rules for tables, right-aligned text kept right-aligned to a fixed
  column, centred text centred, one separator line per page.
- **`jrock-prompt-doc-inventory.txt`** — read a document and answer with **nothing but a file
  name**, following one convention:
  `<date>-<counterparty>-<what it is>-<document number>`, e.g.
  `2026-06-14-DHL-FollowUpOnParcelDelivery-1234567890`. Titles and counterparties are
  normalised to English and Latin-1 and shortened to the name people actually use
  (*Beitragsservice* → `GEZ`, *Bayerische Landesbank* → `BayernLB`).

Both end in bare `@img` / `@txt` lines. Those are **placeholders, not tokens** — a real include
token carries a 12-hex-digit hash, so a bare one is only ever sent as the text it is. They mark
where the attachments belong: put the cursor on that line and press Ctrl+I.

### The scenario: a PDF you can find again

The point of chaining these two is an archive whose file names mean something, plus a text copy
of every document for reading and further automation.

1. **Ctrl+O** → `jrock-prompt-pdf-to-ascii.txt`.
2. **Ctrl+I** → the PDF, with the **PDF as page images** filter. Ghostscript rasterises it, one
   PNG per page, and each page's `@img` token lands in the prompt. Page images rather than
   `txtwrite` text because layout is the whole question here, and a scan has no text layer at
   all.
3. **Ctrl+Enter**. The reply is the document as plain text.
4. **Select that reply in the log and press Ctrl+L** — with a selection, Ctrl+L saves *just the
   selection* ("Save selected text as"), so you get the document and not the transcript around
   it. Save it next to the PDF.
5. **Ctrl+O** → `jrock-prompt-doc-inventory.txt`. Ctrl+O always opens back in the prompts
   directory, so step 5 is the same two keystrokes as step 1 even though step 4 just saved a
   file somewhere else entirely.
6. **Ctrl+I** → the `.txt` you just saved, with the **Text files as is** filter. One `@txt` token.
7. **Ctrl+Enter**. The reply is a base file name.
8. **Rename both files to it** — `<name>.pdf` and `<name>.txt`.

The result is a PDF you can identify from the file listing alone, and a text twin of it that
`grep` can read. Repeat per document; the prompts don't change.

### Using them

Point JRock's prompts directory at the folder — `--prompts-dir` on the command line, or the
**Prompts directory** row in [Configure](#configure-dialog-top-left-button) — and both prompts
are two keystrokes away from any working directory. On Windows, installing the
["JRock here!" entries](#windows-explorer-right-click-integration) with it set bakes it in, so
the library follows you into whichever folder you right-click.

These two are worth reading before they're worth running: the useful part is not the wording but
the shape — one prompt per step, the step's input left as an include token at the bottom, and
an output narrow enough to be the next step's input. Copy the folder and rewrite the contents
for your own documents. The `jrock-prompt-*.txt` names are only a convention, matching the
`jrock-prompt.txt` autosave and the `jrock-prompt-copy.txt` that Ctrl+S offers; JRock loads any
text file.

## Configure dialog (top-left button)

- **Working directory** (with a Browse button) — reroutes JRock's own files to the chosen
  folder. The OS-level process working directory is unchanged.
- **Prompts directory** (with a Browse button) — where Ctrl+O and Ctrl+S open, always (see
  [the prompts directory](#the-prompts-directory)). It shows the path actually in effect, so
  on a plain launch it shows the working directory; set it back to that to have prompts follow
  the working directory again. Leaving the row untouched changes nothing, so moving the
  working directory alone doesn't pin prompts to the folder you just left. A directory that
  doesn't exist yet is created.
- **BEDROCK_API_KEY** — write-only: left blank, it keeps the current key; type a value to
  override for the session. The key is never displayed or stored beyond the running process.
  In the browser this row is absent: the key belongs to the page (see
  [HTTP transport](#http-transport)) and is changed there.
- **AWS_REGION** — free text.
- **Model** — free text with a dropdown of recently fetched models.
- **PDF image DPI** — the resolution Ghostscript rasterises PDF pages at (`-r`) when a PDF is
  included as images: 72 / 96 (screen), **150** (documents, the default), 203 (fax/receipt),
  300 (print). A page image is what the model actually sees, so this is a real trade-off —
  too low and small print is unreadable, too high and you pay tokens for detail no model
  needs. Only reported at startup when it isn't the default; every conversion logs its full
  Ghostscript command line regardless.

Applying re-runs the session init (working directory reported first, then models loaded,
ending with `Ready.`). Changing the working directory reloads **both the log and the prompt**
from the new folder, so nothing carries over from the old one — and, since the prompt is
autosaved over on every keystroke, nothing in the new folder is overwritten by what was on
screen. A new folder with no prompt of its own keeps the current one and is seeded with it.

The dialog also shows an **About** line with the version and a **JRock** link to the project
on GitHub, a short description, keyboard shortcuts, and authorship.

## Window move & resize (Ctrl+M)

A dialog to set the window **width/height** and **on-screen X/Y** numerically, plus info
about the screens (which monitor holds the window, each screen's bounds). Handy for precise
placement or moving across monitors without the mouse.

## Printing / PDF (Ctrl+P)

Opens the native print dialog for the log. On Windows you can pick "Microsoft Print to PDF"
to save the transcript to a PDF, or print to a physical printer. If text is selected in the
log pane, only the selection is printed (with its colors), so a single answer can be printed
without the surrounding transcript.

## Context menus (right-click / long tap)

Right-clicking (or long-tapping on touch devices) opens a context menu:

- **Log pane** — Save log copy as..., Print... When text is selected in the log, both act
  on the **selection only**, and the menu says so (*Save selected text as...*, *Print
  selected text...*). A partial export doesn't count as saving the log, so Clear log still
  warns about unsaved changes.
- **Prompt area** — Include text, image, PDF or RTF file... (multi-select), Load prompt from
  file..., Save prompt copy as...
- **Top bar (empty area)** — Move & resize window...; in the **browser**, also Show/hide
  the page header & footer; on **Windows**, Install / Uninstall the "JRock here!" Explorer
  entry (see below).

## Windows: Explorer right-click integration

On Windows, the top-bar context menu (right-click the empty area of the top bar) offers
**Install "JRock here!" (Explorer menu)...** and **Uninstall "JRock here!" (Explorer
menu)...**. These items appear only on Windows. Installing adds two Explorer right-click
entries at once:

- **"JRock here!"** — on a folder's empty space or on a folder icon. Launches JRock in that
  folder, so its `JRock/` files (prompt, log, messages) are created right there. Explorer
  starts the process with its current directory set to the clicked folder, so no path
  argument is needed.
- **"Open as prompt with JRock"** — on a **`.txt`** file. Launches JRock with that file loaded
  as the initial (read-only) prompt. This adds a verb without changing the default open action
  for `.txt`.

![The Explorer context menu for a folder, with a "JRock here!" entry among the other shell verbs](images/explorer-jrock-here.png)

*The installed entry, sitting with the other developer verbs and carrying JRock's own icon.
Clicking it starts JRock in that folder — no path argument, no console window.*

Details:

- **Per-user and reversible.** Entries are written under `HKEY_CURRENT_USER`, so no admin rights
  are needed, and Uninstall removes all of them.
- **No console window.** Both launch `javaw.exe`, so nothing flashes on screen.
- **Self-configuring.** They use the `javaw.exe` of the JVM currently running JRock, and launch
  JRock's own `jrock.jar` (or the `JRock.java` file when running from source) — no paths to edit.
- **The prompt library comes along.** If a [prompts directory](#the-prompts-directory) is set
  — from `--prompts-dir` or from the Configure dialog — both entries are installed carrying
  that flag, so **Ctrl+O opens your prompt library from whichever folder you right-clicked**.
  Explorer supplies the working directory; this supplies the prompts directory. Nothing is
  written when prompts just follow the working directory, since the flag would then pin every
  future launch to the folder you installed from. It is captured at install time, so reinstall
  after changing it — the confirmation dialog and the log both spell out exactly what was
  written, including when nothing was.
- **Inspectable.** The applied registry file is kept under `JRock/`
  (`jrock-context-menu-install.reg` / `jrock-context-menu-uninstall.reg`), and each action is
  recorded in the log.
- On **Windows 11** the entries may appear under **"Show more options"**.

## Per-folder window title & icon

The window title and taskbar/Alt-Tab icon reflect the **working directory** so multiple JRock
windows opened in different folders are easy to tell apart:

- The title is `<folder> - JRock` (folder name first, so it stays visible even when Alt-Tab
  truncates the text).
- The app icon is a generated teal tile badged with a short abbreviation of the folder name
  (e.g. `my-cool-project` → `MCP`, `research` → `RES`).

In the browser this is switched off (plain title, plain `JR` icon): there's a single instance
and the directory is CheerpJ's own virtual mount, so naming it would say nothing useful.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| Ctrl+Enter | Send |
| Ctrl+S | Save prompt as (a copy) |
| Ctrl+L | Save log as (a copy, or just the selected text) |
| Ctrl+O | Load prompt from a file (text only) |
| Ctrl+I | Include text/image files, a PDF or an RTF (multi-select) |
| Ctrl+D | Toggle Dialog only |
| Ctrl+E | Toggle Extend conversation |
| Ctrl+M | Move & resize the window |
| Ctrl+P | Print log (or the selected text) / save as PDF |
| Ctrl+Z / Ctrl+Y | Undo / redo in the prompt |

## Reproducible builds

CI compiles `JRock.java` with a **pinned OpenJDK 11 patch (Temurin 11.0.32+9)** and repacks
the classes into a **byte-for-byte reproducible** `jrock.jar` (see `.github/build/BuildJar.java`).
The same source therefore yields the **same SHA-256 and MD5 on any machine or OS**.

For robustness the build runs on **three operating systems** and publishes three artifacts:

- `jrock-macos-latest.zip`
- `jrock-ubuntu-latest.zip`
- `jrock-windows-latest.zip`

Each archive contains the **same bit-perfect `jrock.jar`** plus its checksum files
(`jrock.jar.sha256`, `jrock.jar.md5`) and the zipped source (`jrock-src.zip`). Because the
build is reproducible, the `jrock.jar` inside all three archives is identical.

To verify and run JRock from a build artifact, unzip it, then:

```sh
cd jrock-ubuntu-latest/

sha256sum jrock.jar
md5sum jrock.jar

java -jar jrock.jar
```

Current build hashes:

```
c69e907e41cc81cccc6a88a1c4fdc147481abdc10e93b9334bde64d2357d4899  jrock.jar
f6a72c247de285b577680067fca5fd96  jrock.jar
```

Or, if you want to modify the source and run it in place (no build step):

```sh
java JRock.java
```

## Tests

Tests live in **`tests/`** and run on **JUnit 5**. Most are GUI tests, using
[AssertJ-Swing](https://github.com/assertj/assertj-swing) (the maintained descendant of
FEST-Swing) to find the real widgets and read them: each one starts the actual application
via `JRock.main(...)` and then works its window.

They do **not** move the mouse or type on the keyboard. Both go through the operating system,
which hands them to whichever window it believes is focused or under the pointer — and the
headless runner has no window manager to make that JRock's, while a desktop has whatever its
owner is doing in front. Injected input can also be dropped outright, and a lost click looks
exactly like a button that does nothing: the test sits at its timeout waiting for a dialog
that was never opened. So buttons are pressed with `doClick`, the Ctrl shortcuts are looked up
in the root pane's input map and run, dropdowns are given `setSelectedItem`, and text is set on
the field's document — in each case the very thing a real click or keystroke reaches, once it
has arrived. What this gives up is evidence that a widget is where a mouse could reach it;
what it buys is a suite that reports on JRock rather than on the window manager.

- **`JRockStartupTest`** waits for the log pane to report `Ready.` among the rest of the
  session report.
- **`JRockConfigureTest`** opens the Configure dialog, types an API key, applies it, waits
  for the session report to run again, and reopens the dialog to check the key is **not
  shown back** — the field is write-only, and the failure mode is a credential appearing on
  screen. It also checks the key never reaches the log.
- **`JRockPdfIncludeTest`** sets **PDF image DPI** to 300 in the Configure dialog, then
  includes a two-page A4 PDF through the real include dialog with the *PDF as page images*
  filter — and checks Ghostscript was asked for `-r300`, that two pages came back, that the
  prompt gained **two `@img` tokens**, and that both PNGs really are **2480 × 3508 px**. The
  PDF is written by hand (`A4Pdf`) so its page box is exactly A4: `gs -sPAPERSIZE=a4` is the
  rounded 595 × 842 pt, which would rasterise one pixel narrower.
- **`JRockRtfIncludeTest`** includes an RTF through the real include dialog with the *RTF as
  Markdown text* filter, and compares the file under `JRock/rtf-md/` against the **whole
  expected Markdown** — headings from the font sizes, bold and italic as markup, Word's bullets
  as a list, asterisks escaped, an umlaut intact as UTF-8 — plus the single `@txt` token in the
  prompt. The document is written by hand (`FormattedRtf`), one control word per mapping. A
  second test renames a plain text file to `.rtf` and checks JRock says it found no text and
  includes nothing. A third includes the same RTF under *Text files as is* and checks the
  include is the `.rtf` itself, with nothing converted and no `JRock/rtf-md/` written — the
  filter is the whole difference. No external program: the reader is the JDK's.
- **`JRockWorkingDirTest`** opens another working directory from the Configure dialog and
  checks nothing is lost at either end: the prompt stored in the folder being opened is what
  ends up on screen and still what is in its file, the folder left behind keeps what was on
  screen when it was left, and a folder with no prompt of its own is given the current one.
  The prompt is autosaved on every change, so a switch that left the previous prompt in the box
  would overwrite the opened folder's prompt on the next keystroke — unrecoverably.
- **`JRockReplyTextTest`** feeds chat-completion JSON to the reply parser and checks non-ASCII
  text comes back intact — as characters, as `\uXXXX` escapes (a server may use either, and an
  emoji arrives as a *pair* of them), and mixed. No window.
- **`JRockImageSizeTest`** checks the image-header reader: PNG, GIF and JPEG against what the
  JDK's own encoder wrote, a JPEG whose size sits behind a 60 KB metadata segment, all three
  WEBP encodings from hand-built headers, and junk or truncated files, which must report
  nothing rather than a number read from whatever bytes followed. No window.

```sh
cd tests/
mvn test
```

Nothing is stubbed and no credentials are needed: tests run with `BEDROCK_API_KEY` blank, so
JRock skips its startup model-list fetch. No test contacts AWS — the one case that does set a
key points the region at a host that doesn't resolve, so the fetch fails at DNS.

One external program is needed: **Ghostscript on `PATH`** (`gs`, or `gswin64`/`gswin64c` on
Windows),
for the PDF test — which converts a real PDF rather than pretending to. CI installs it; the
test fails with that as its message if it's missing.

`JRock.java` is compiled **in place** from the repository root — the root keeps its single
Java file, and nothing is copied. Maven writes everything to `tests/target/`, which is
git-ignored, and that's also where the app's own `JRock/` folder goes during a test run.

CI runs this on every push (`.github/workflows/tests.yml`), under `xvfb` since the Linux
runners are headless, on JDK 11 — the minimum JRock supports.

### Coverage

[JaCoCo](https://www.jacoco.org/jacoco/) measures coverage on the same `mvn test` — no extra
phase or profile. Reports land in `tests/target/site/jacoco/`: open `index.html` for the
line-by-line view of `JRock.java`, and CI uploads that directory as the `coverage-report`
artifact.

Coverage is **reported, not enforced**. There is no threshold to fail a build on, because a
threshold mostly buys tests written to move a number. One startup test currently reaches
about **22% of lines and 9% of branches** — that is the honest baseline, and it is what a
single test against a GUI application buys.

### Reporting

Results are published to the **job summary** on the run's own page: how many tests passed,
each test's name, for a failure the assertion text inline, and the coverage totals — rather
than only in the raw console log or the downloadable report zips. GitHub has no built-in
JUnit view, so `.github/build/TestSummary.java` renders Surefire's XML and JaCoCo's CSV into
Markdown. It uses nothing but the JDK and needs no third-party action, the same reasoning as
`BuildJar.java`; run it locally with:

```sh
java .github/build/TestSummary.java tests/target/surefire-reports
```

## Notes

- All files created by the app are under `JRock/` and are git-ignored.
- File writes are atomic, so a crash or a failed write never leaves a truncated file.
