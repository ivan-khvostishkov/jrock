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
  Ghostscript and RTF/DOCX-to-Markdown with no external tool at all) referenced by hash;
  multi-select supported, with optional **copies kept under `JRock/`** and every include
  **reloaded from the log** in one menu item after a restart
  ([**includes that outlive the session**](#includes-that-outlive-the-session)).
- **Include a URL**, not just a file: an address is downloaded into `JRock/urls/` and attached
  as text or as a picture according to the `Content-Type` it answered with, with a Markdown
  link to where it came from above the token ([**inserting a URL**](#inserting-a-url)).
- **Markdown export** of an answer as **RTF or DOCX**, written in-process — the model replies in
  Markdown, and a selected reply becomes a document a word processor, or a layout application,
  opens with its headings, tables and **the pictures it was given** intact
  ([**Markdown export**](#markdown-export-rtf-and-docx)).
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

## Mobile support for iOS and Android

Both work today, through [JRock Web](#jrock-web-in-the-browser): the phone's browser runs the same
unmodified `jrock.jar`, so there is nothing to install and no app store in the way.

<img src="images/mobile-touch-menu.png" width="340"
     alt="JRock in Edge on an iPhone, with the prompt's long-tap context menu open">

*Edge on an iPhone, with the page's own header and footer left showing: the jar's size and SHA-256
to check before typing a key, and a footer log ending in `GET .../v1/models -> 200` — a real Bedrock
call from a phone. In the window, the session report (`/files`, the page's `fetch()` as the transport,
the key held by the page) and the prompt's context menu, opened with a long tap because that is how
a phone reaches everything the Ctrl shortcuts do.*

The UX is a little clumsy, and worth knowing about before you judge it:

- The on-screen keyboard sometimes needs a tap on the window **outside** the text area before it
  will come up.
- Scroll bars, text selection and the menus are Swing's own, drawn for a desktop and driven by a
  fingertip. It takes some learning — but you do get used to it.

For an app like this that is a fair trade: one jar, one build, no per-platform code and no store
review, and the phone gets the same client as the desktop, with the same plain files under
`JRock/`.

### Working with PDFs

The two PDF filters are the one thing a phone doesn't get. Java ships no PDF support of its own, so
JRock converts with [Ghostscript](#pdf-conversion-ghostscript) as a subprocess — and there is no
process to start under a browser JVM, CheerpJ having no operating system beneath it. The filters are
still in the dropdown, and picking one there gets you the same "not found" note as a desktop without
Ghostscript installed. Two ways round, both of which work in a browser exactly as they do on the
desktop:

- **Page images.** Screenshot the pages in whatever PDF viewer the phone already has and include
  them under **Image files**. This is what *PDF as page images* produces anyway, done by hand — and
  image includes need nothing native, JRock reading their dimensions straight out of the file header.
- **Via a word processor's format.** Export the PDF as RTF or as Word in Acrobat (which has a mobile
  app too), then include the file as [**RTF as Markdown text**](#rtf-and-docx-conversion-no-external-tool) or
  [**DOCX as Markdown text**](#rtf-and-docx-conversion-no-external-tool) — or, for an `.rtf`, under
  **Text files as is**, markup and all. All of them run in-process on the JDK's own RTF reader and
  XML parser, so all of them work in a browser tab.

Which one depends on the document: a scan or anything where the layout carries meaning goes as page
images, while a text document is better via RTF or DOCX, where headings, bold, italic and — from a
`.docx` — tables survive into the Markdown as structure the model reads.

**And the other direction: making a PDF.** Select the answer in the log, long-tap (or right-click)
and pick **Export selected Markdown with images as DOCX...** — the Markdown becomes a real
document, headings and tables and all (and the photographs from the phone's camera roll, if they
were included with a [Markdown reference](#multimodal-includes-ctrli) and the model wrote it back),
under [named paragraph styles](#markdown-export-rtf-and-docx). Place that in
**InDesign**, which imports a Word file by mapping its style names onto your own paragraph styles,
and the PDF that comes out is typeset rather than printed from a text editor. **Export selected
Markdown as RTF...** is the lower standard for the same idea: no style names, but anything that
opens an RTF — Word, Pages, LibreOffice, TextEdit, WordPad — will read it. Neither is mobile-only:
the two items are in the log's context menu wherever JRock runs, and on the desktop they are the
short way from an answer to a document somebody else can edit.

### What native mobile support would take

The compilers already exist. For iOS,
[MobiVM/RoboVM](https://github.com/MobiVM/robovm) translates Java bytecode ahead of time into a
native iOS binary; for Android, the platform's own toolchain compiles Java straight to DEX and runs
it natively, Android having been a Java platform from the start. What is missing on both is
**Swing**: an AWT/Swing backend plugged into UIKit and Android's own view primitives, so that a
`JFrame` is a native window and a `JTextArea` a native text view with the keyboard, selection and
scrolling that the platform already knows how to do. That is a months-long project in its own right,
and none of it is specific to JRock.

Which is also why it would be worth more than JRock. A Swing backend on native mobile primitives
would make plain Java a cross-platform UI target again — desktop, iOS and Android from one source —
and put Java and Swing back in the same row as Kotlin and Unity/C#. If that interests you, vote with
the **Sponsor** button at the top of this repository ;)

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
- `JRock/docx-md/` — the same for a `.docx` included as Markdown text.

The main log is a bit-perfect copy of the pane, except that each role header is followed by an
`@<datetime>` include-style reference to the message's own file under `JRock/messages/`.

**Clear log** empties `jrock-log.txt` and the window but never touches `JRock/messages/`, so
paid-for inputs/outputs are preserved. If the log has changed since it was last exported with
**Ctrl+L** (Save log as a copy), Clear log first asks for confirmation and suggests saving.

## Multimodal includes (Ctrl+I)

Attach **text or image** files to a prompt (and convert **PDFs**, **RTFs** or **DOCX** documents
into either):

1. **Ctrl+I** opens a file picker. It's **multi-select**, so you can attach several files at
   once, and the dropdown offers seven kinds:
   - **Image files** (png, jpg, jpeg, gif, webp)
   - **Image with a Markdown reference** (the same files) — one line more in the prompt: a
     Markdown `![](<hash>)` above the token. The model reads it as a picture belonging to the
     text and writes it back into its answer where the picture belongs, which is what
     [**Export selected Markdown with images as DOCX...**](#markdown-export-rtf-and-docx) then
     places
   - **Text files as is** (txt, csv, html, java, rtf) — sent exactly as they are on disk,
     RTF markup and all, for a model that reads (and writes) the format itself
   - **PDF as text pages** — converts the PDF to one text file per page
   - **PDF as page images** — converts the PDF to one PNG per page
   - **RTF as Markdown text** — converts the RTF to one Markdown file
   - **DOCX as Markdown text** — the same for a Word `.docx`, tables included

   The dialog also carries a **Save include copies** checkbox, which keeps a copy of each
   chosen file under `JRock/includes/` and includes it from there
   ([why](#includes-that-outlive-the-session)).
2. Each file is hashed (SHA-256, shortened to 12 hex digits). The hash → path mapping is kept **in memory only**
   (not persisted), so after a restart the files have to be attached again — or their paths read
   back out of the log with **Reload all includes**
   ([below](#includes-that-outlive-the-session)).
3. A token `@img <hash>` or `@txt <hash>` is inserted at the cursor (one per file / per PDF
   page), preceded by a `![](<hash>)` line under the Markdown-reference filter. Duplicate
   tokens for the same file are not added again (also checked across prior turns in Extend
   mode).
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

### Inserting a URL

The same include, for something that isn't on this machine. **Right-click the prompt →
Insert URL...** asks for an address, downloads it into `JRock/urls/`, and includes the saved
file exactly as if you had picked it with Ctrl+I — a **web page as text**, an **image as a
picture**. Two lines go into the prompt, the address above the token:

```
[](https://example.org/a/article)
@txt 1f3a9c0b7e42
```

The link says where the text or the picture came from, in a form the model reads as a
reference belonging to the content below it; the token is what is actually sent.

What decides which of the two it is — and the extension the file is saved under — is the
**response's own `Content-Type`**, not the URL. A link ending in `.png` that answers with HTML
is a web page, and saving it as a PNG would only produce an `@img` token no model can read:

| It answers with | Saved as | Inserted as |
|---|---|---|
| `text/html`, `application/xhtml+xml` | `.html` | `@txt` — sent as text, markup and all |
| `image/png` | `.png` | `@img` — sent as a picture |
| `image/jpeg` | `.jpg` | `@img` |
| `image/gif` | `.gif` | `@img` |
| `image/webp` | `.webp` | `@img` |
| **anything else** | — | **nothing.** The type is named, in the log and in a dialog, and no file is written |

Details:

- **The file is named after the URL**, by its last path segment, with the extension its media
  type calls for *added* rather than substituted — `/a/article` → `article.html`,
  `/pics/cat.png` → `cat.png`, `/page.php` → `page.php.html`, and an address with no path of
  its own → `<host>.html`. Anything a file system might object to becomes `-`, and a very long
  name is cut. The same URL fetched twice is **one file** (same bytes, same name, nothing
  rewritten); a different page that wants a taken name becomes `article-2.html`, exactly as an
  [include copy](#includes-that-outlive-the-session) does.
- **A page is saved as UTF-8**, decoded first with the charset the response declares — an
  included text file is *read back* as UTF-8, so a page served as `windows-1251` would
  otherwise reach the model as mojibake. An image is saved byte for byte: those bytes are what
  gets sent.
- **`https://` is assumed** when the address has no scheme, so a bare `example.org/page` works;
  anything else keeps the scheme it was given, and one that is not `http` or `https` is
  refused by name. Redirects are followed (but not an `https` → `http` downgrade), and the
  address the bytes really came from is logged when it differs.
- Because the file lands under `JRock/`, it is **already where the copies go** — a URL include
  survives a restart the same way, with **Reload all includes**.
- **Not in the browser build.** It needs a network client of its own, and the page's client
  returns text without response headers — so neither an image's bytes nor its media type would
  survive. The item says so rather than half-working.

### Includes that outlive the session

The hash → path map is not persisted, and a restart therefore loses it — while the prompt and
the whole transcript *are* restored from disk. So the tokens come back and nothing knows what
they stand for: sending says `Included @img <hash> is not known`, and the conversation is stuck
until every file is attached again. Two things fix that, and they are meant to be used together.

**Save include copies** — a checkbox in the include dialog itself, next to the file list. With
it on, a chosen file is **copied into `JRock/includes/` first and included from the copy**, so
the log's line points inside the folder you own:

```
Copied for the include: C:\photos\IMG_4002.jpg -> C:\demo\JRock\includes\IMG_4002.jpg
Included @img 1f3a9c0b7e42 from C:\demo\JRock\includes\IMG_4002.jpg
```

It is off by default and remembered for the session. Two different files of the same name both
survive, the second as `IMG_4002-2.jpg`; the same file twice is not copied twice. A file already
under `JRock/includes/` is included where it is, and so is anything the conversions wrote (they
write under `JRock/` themselves). A copy that fails is reported and the original included anyway —
the option is there to keep a file within reach, not to refuse the include. It matters most in
the [browser](#jrock-web-in-the-browser), where an uploaded file lands in CheerpJ's `/uploads`
and is gone after a reload, taking the only path the log recorded with it.

**Reload all includes** — an item in the prompt's context menu, which rebuilds the map from the
log. The log recorded every include ever made, which is the same information the map held, so it
is read top to bottom as the history it is:

| In the log | What it means |
|---|---|
| `Included @img <hash> from <path>` | remember `<hash>` → `<path>`, replacing an earlier path for that hash — the file was included again, perhaps from somewhere else |
| a message (yours or the model's) referring to a hash | that hash is wanted, at the path remembered for it **at that point**, so a later include cannot rewrite what an earlier message meant |

Both spellings of a reference count: the `@img`/`@txt` token, and the `![](<hash>)` a
[Markdown-reference include](#multimodal-includes-ctrli) leaves — an answer carrying one needs
the file to export as a DOCX with the picture in it. The current prompt is read last, being the
newest thing there is, and after a restart its recovered tokens are usually the whole reason for
doing this. Each hash is then reported on its own line:

```
Reloaded @img 1f3a9c0b7e42 from C:\demo\JRock\includes\IMG_4002.jpg
Reloaded @txt 8b52e0ad91cc from C:\demo\JRock\rtf-md\quarterly.rtf.md - but that file is not there any more.
No include recorded for @img 4d0c1a77e6b3 - attach the file again with Ctrl+I (the log may have been cleared since).
Reload all includes: 1 reloaded, 1 with a missing file, 1 not recorded in the log (of 3 referred to). Each file is checked again, by its hash, on send.
```

**Nothing is hashed here**, deliberately. Whether each file is still the file it was is exactly
what the send checks, one hash per token, and the answer can change between the two moments
anyway — so a reload is cheap on a folder of large attachments, and a file that has been edited
since is caught at the only moment that matters. What the reload *does* check is that the file is
still there, because a path that no longer exists is the one problem you can do something about
before sending.

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

### RTF and DOCX conversion (no external tool)

Selecting **RTF as Markdown text** or **DOCX as Markdown text** converts the document to
Markdown and includes *that* file as an ordinary `@txt` token — so what the model receives is a
text part, and the prompt shows one token for the document.

Nothing has to be installed, unlike the PDF path. The RTF reader is the JDK's own
`javax.swing.text.rtf.RTFEditorKit`, the same one a `JTextPane` uses; a `.docx` is a ZIP of XML,
so it is read with `java.util.zip` and the JDK's XML parser. Both work on a bare JVM — and in a
browser tab, where no subprocess can run at all. Markdown rather than flat text because the
formatting is what these formats are for: headings, bold, italic and (from a `.docx`) tables
survive as markup a model reads as structure instead of being thrown away.

**From an RTF.**

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

**From a `.docx`.** A Word document says what each paragraph *is* — a style name, a list
reference, a table cell — so the conversion reads that structure rather than guessing at it from
font sizes:

- Output is written under **`JRock/docx-md/`**, named `<docxname>.md` — `quarterly.docx` becomes
  `quarterly.docx.md`, the same rule as for RTF.
- Only `word/document.xml` is read, and only these parts of it:

  | In the document | In the Markdown |
  |---|---|
  | a `Heading1`…`Heading6` or `heading 1`… style (either spelling), or `Title` | `#` … `######` |
  | a paragraph with `w:numPr` (list markup) | a `-` list item — Word keeps the bullet in the numbering, not in the text |
  | a `Quote`-ish style | a `>` quote line |
  | a `Code`/`Preformatted`/`Listing`/`Source` style | an indented code block |
  | `w:b`, `w:i` on a run (`w:val="false"` switches it back off) | `**bold**`, `*italic*`, `***both***` |
  | `w:tbl` | a pipe table, with a `\| --- \|` separator after the first row; a `\|` inside a cell is escaped |
  | `w:tab`, `w:br`, `w:cr` in a run | a space; a `w:noBreakHyphen` a `-` |
  | runs Word split mid-word | one word — adjacent runs with the same bold/italic are joined back together |
  | a `w:hyperlink` | its text, in place; the URL lives in a part this does not read |

  Markdown's own characters in the text are escaped here too, so a document about asterisks
  still says so.
- **Not** read: `numbering.xml` (so every list level is a `-`, and numbered lists are not
  renumbered), headers, footers and footnotes, embedded images, colours, alignment and
  underline (which has no Markdown of its own, same as on the RTF side). With track changes on,
  an insertion is read as the text it is and a **deletion is not read at all** — `w:delText` is
  text someone took out, not text the document says.
- A file that isn't really a `.docx` — an older `.doc`, a PDF, anything renamed — has no
  `word/document.xml`, and the log says exactly that: *Could not read DOCX renamed.docx: no
  word/document.xml inside it - is it really a Word .docx?* Nothing is included and no
  `JRock/docx-md/` is created.

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
from the new folder, so nothing carries over from the old one. A folder with no prompt of its
own keeps the one on screen and stores it there.

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

## Markdown export (RTF and DOCX)

The model answers in Markdown. Select an answer in the log, right-click, and two more items
write that answer out as a document: **Export selected Markdown as RTF...** and **Export
selected Markdown with images as DOCX...**. Both are greyed out without a selection — there is
nothing to export but a selection — and a save dialog gives the file the right extension whatever
you type into it. Not mobile-only, and not desktop-only: it is the same code in a browser tab,
because both converters are built in and neither starts a process.

- **RTF** is written by hand as plain ASCII: every character above it becomes a `\u` control
  word, so no encoding anywhere can change what the file says. Swing's `RTFEditorKit` reads the
  import direction but does *not* write this one — its writer has no notion of a table, and a
  table is half of what a document is for.
- **DOCX** is a five-part OOXML package (`[Content_Types].xml`, two `_rels`, `word/styles.xml`,
  `word/document.xml`) zipped with `java.util.zip`, plus one `word/media/` part per picture it
  places. The paragraphs carry **named styles** — Normal, Heading 1–6, Code, Quote — and the page
  is A4 with 2 cm margins, which is why this is the format to export for typesetting: a layout
  application imports a document by mapping style *names* onto its own.

What the Markdown becomes, in both:

| In the Markdown | In the document |
|---|---|
| `#` … `######` | Heading 1–6, sized 18 pt down to 11 pt |
| `**bold**`, `*italic*`, `` `code` `` | bold, italic, a monospace run |
| `-`/`*`/`+` and `1.`/`1)` items, indented | a list paragraph, bullet or its own number, nested up to five levels |
| `> quoted` | an indented italic paragraph — the *Quote* style in the DOCX, so a bold run inside it stays bold-italic |
| a fenced block (three backticks or tildes) | a Code paragraph, verbatim |
| `---`, `***`, `___` | a horizontal rule |
| `\| a \| table \|` with a `\| --- \|` row | a real table: bordered cells, columns shared across the page width, first row repeated as a header, and `:---:` / `---:` honoured as cell alignment |
| `[text](url)` | the text, with the URL after it unless the two say the same thing — paper cannot be clicked |
| `\*` and the rest of Markdown's escapes | the character itself |

An export **does not fail**. The parser's answer to anything it doesn't recognise is "a
paragraph of text"; if it throws anyway, the document is rebuilt as one paragraph per line and
the log says so — *the text is all there, the formatting is not*. The log line reports what was
written: the file, the block and table counts, and the byte count.

A `.docx` written here reads back through **DOCX as Markdown text** as the same Markdown, give
or take the two honest differences a round trip has: a table's header row was written bold, so
it comes back as bold markup, and column alignment is layout the import doesn't read.

### Images in the DOCX

The DOCX export is the one that carries pictures, which is what *with images* in its name means.
It works on the two things an include leaves in the text, and the model repeats in its answer:

| In the Markdown | In the DOCX |
|---|---|
| `![](<hash>)` of an **included** image | the picture itself, zipped into the package as a `word/media/` part and placed as an inline drawing |
| `@img <hash>` of an **included** image | the file's **base name**, `IMG_4002.jpg`, as text — the token named a file, and the document says which |
| either one, for a hash **not included** in this session | the line exactly as it stands, plus a warning in the log |

**Nothing about an image stops an export.** A hash whose file was never included — a restart
forgets the includes, and the model can invent a hash as easily as a word — is left in the text
as the text it is, and the log says which hash and why once, however many times it occurs. The
same goes for an include that is not an image at all (a `@txt` file the model referenced as a
picture) or a file whose bytes are not a PNG, JPEG, GIF or WEBP: the reference stays, the export
finishes. The log line counts the pictures it did place along with the blocks and tables.

**Sizes are physical, and resolution is the limit.** The page is A4 portrait with 2 cm margins,
so the text frame is 17 × 25.7 cm, and an image is placed as large as it can be without falling
below **300 dpi** in either direction:

- A picture big enough in pixels fills the text frame — the width for a landscape one, the height
  for a portrait one, whichever runs out first.
- A picture too small for that is placed at exactly 300 dpi instead, so it takes up *less* than
  the frame and stays sharp rather than being blown up into a blur. A 300 × 200 px image becomes
  2.54 × 1.69 cm, not a page-wide smudge.
- Width and height are scaled by one and the same factor in every case, so nothing is ever
  distorted. That factor is an integer number of EMU per pixel (the smallest of the three
  limits), which is why the aspect ratio cannot drift as it would with two independent divisions.

The pixel dimensions come from the same header reader the include stats use, and the media part's
extension and content type are decided by **sniffing the file's magic bytes**, not by trusting its
name: a `.png` that is really a JPEG would otherwise produce a package whose content types lie,
and Word answers that with a repair dialog rather than a document.

**The RTF export keeps the simplified logic**: no pictures, and `![](<hash>)` and `@img <hash>`
are written out as the text they are. An RTF has no package to put a picture in — the bytes would
have to be hex-dumped into the file itself — and the format is there for anything that opens an
RTF, not for typesetting.

## Context menus (right-click / long tap)

Right-clicking (or long-tapping on touch devices) opens a context menu:

- **Log pane** — Save log copy as..., Print... When text is selected in the log, both act
  on the **selection only**, and the menu says so (*Save selected text as...*, *Print
  selected text...*). A partial export doesn't count as saving the log, so Clear log still
  warns about unsaved changes. Two more items, *Export selected Markdown as RTF...* and
  *Export selected Markdown with images as DOCX...*, need a selection to mean anything and are
  greyed out without one (see [**Markdown export**](#markdown-export-rtf-and-docx)).
- **Prompt area** — Include text, image, PDF, RTF or DOCX file... (multi-select), *Insert
  URL...* (which downloads an address into `JRock/urls/` and includes it as text or as a
  picture, according to what it answered with — see [**inserting a
  URL**](#inserting-a-url)), *Reload all includes* (which rebuilds the hash → path map from
  the log, so a conversation survives a restart — see [**includes that outlive the
  session**](#includes-that-outlive-the-session)), Load prompt from file..., Save prompt copy
  as...
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

Both follow a change of working directory, so they always name the folder the window is
actually working in.

In the browser the plain title `JRock - Bedrock (mantle)` and the plain `JR` icon are used
while the working directory is CheerpJ's own mount (`/files`) — a folder nobody chose. Open one
of your own and it is named in the title just as on the desktop.

## Keyboard shortcuts

| Shortcut | Action |
|---|---|
| Ctrl+Enter | Send |
| Ctrl+S | Save prompt as (a copy) |
| Ctrl+L | Save log as (a copy, or just the selected text) |
| Ctrl+O | Load prompt from a file (text only) |
| Ctrl+I | Include text/image files, a PDF, an RTF or a DOCX (multi-select) |
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
026b60b231b28abd29a7223115b43547039e9e87a0c90f84d44d435d54390de3  jrock.jar
4114c44cb96b4eaae6f6b792e9413d1f  jrock.jar
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

They do **not** move the mouse or type on the keyboard. Both are delivered by the operating
system, to whichever window it believes is focused or under the pointer, and on a headless
runner with no window manager that need not be JRock's. So a test presses the real buttons,
picks the real filters and sets text in the real fields, then waits on what JRock logs.

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
- **`JRockDocxIncludeTest`** is the symmetric twin of that, for `.docx`: it includes a document
  through the real dialog with the *DOCX as Markdown text* filter and compares
  `JRock/docx-md/quarterly.docx.md` against the **whole expected Markdown** — headings from the
  styles (spelled both `Heading1` and `heading 2`), runs Word split mid-word joined back into
  words, a toggle switched off with `w:val="false"`, list markup that carries no bullet
  character at all, a hyperlink, a table with a bold header row, an umlaut, asterisks escaped.
  The document is written by hand (`FormattedDocx`), zip entries and all. A second test renames
  a text file to `.docx` and checks the log says there is no `word/document.xml` in it and that
  no `JRock/docx-md/` is created.
- **`JRockImageRefIncludeTest`** includes one PNG twice through the real dialog, under each of the
  two filters that offer images, and checks the one line that is the whole difference: *Image with
  a Markdown reference* leaves `![](<hash>)` above the `@img` token and says so in the log, *Image
  files* leaves the token alone.
- **`JRockInsertUrlTest`** starts a **web server of its own** on loopback — a real one, since
  what the feature turns on is the response — and drives **Insert URL...** from the prompt's
  context menu against three of its paths. A page served as `ISO-8859-1` has to land in
  `JRock/urls/article.html` **re-encoded as UTF-8**, with `[](<url>)` above an `@txt` token; a
  PNG has to land as `cat.png` **byte for byte**, with an `@img` token and its 120 × 80 read out
  of the saved file's header; and `application/json` has to be **refused by name**, in the log
  and in a dialog, leaving no `JRock/urls/` at all and the prompt untouched.
- **`JRockIncludeCopyTest`** ticks the dialog's own **Save include copies** checkbox and checks
  where the include then points: the copy line comes *before* the include it was made for, the
  copy under `JRock/includes/` is byte-for-byte the original, and it — not the original — is what
  the hash is registered against. Unticked, nothing is copied and no `JRock/includes/` is created.
  A third test includes two *different* files both called `photo.png` and checks that neither is
  lost (the second becomes `photo-2.png`), and that the same file again is not copied a third time.
- **`JRockReloadIncludesTest`** includes a PNG and a text file, throws the hash → path map away by
  reflection — which is the state a restart leaves, minus the restart — and invokes **Reload all
  includes** from the prompt's context menu. The map has to come back identical, each entry named
  in the log with the path it was recorded at. Three more tests cover what a log can say instead:
  a token no include line accounts for is named rather than dropped, a file deleted since is
  reloaded *and* reported as gone, and a file included twice from two folders is reloaded from
  where it was included **last**. The menu is opened by dispatching a popup-trigger event to the
  prompt, so the item is reached through the listener a right-click reaches — with no mouse.
- **`JRockMarkdownExportTest`** takes the other direction, without a window: it exports a
  Markdown selection and checks each format against something other than itself. The RTF must be
  all ASCII, must read back through the JDK's **own `RTFEditorKit`** with its bold run, bullet
  and umlaut intact, and must contain the table that reader cannot see. The DOCX must be exactly
  five parts, each **well-formed XML**, with its styles *named* (`w:val="heading 1"`) and its
  header row marked as one. Then a **round trip**: exported as DOCX, read back by the importer,
  and equal to the Markdown it started as. Then the pictures: a real PNG handed to the export as
  an include must come out **byte-identical** in `word/media/image1.png`, declared in the content
  types, related as `rId2`, placed as a `<w:drawing>` — and the `@img` token must have become
  `IMG_4002.png`, with the hash nowhere in the document. The **sizes** are asserted in EMU against
  the arithmetic by hand, 4000 × 2000 px and 500 × 4000 px filling the frame's width and height
  and 300 × 200 px placed at exactly one inch across. An unplaceable reference — an unknown hash,
  an include that is a `.txt`, an ordinary `![alt](url)` — must leave the text alone, add no media
  part, and warn once for each. The last test feeds it markup that is wrong or isn't
  markup — a lone `*`, `snake_case`, ragged pipes, an unclosed link, an unclosed fence, a NUL —
  and checks it still exports, still reads, and that the control character was dropped rather
  than written into the XML.
- **`JRockWorkingDirTest`** opens another working directory from the Configure dialog and
  checks nothing is lost at either end: the prompt stored in the folder being opened is what
  ends up on screen and still what is in its file, the folder left behind keeps what was on
  screen when it was left, and a folder with no prompt of its own is given the current one.
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
