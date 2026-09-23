# Android target (experimental, expected to fail)

A minimal Gradle project that tries to compile the repository's single `JRock.java`
into an APK. It does not work, and it is not meant to yet: JRock is a Swing
application, and Android has neither AWT nor Swing, so the build stops at `javac`
on the first `javax.swing` import.

The point is to have that failure printed in a CI log, from a real Android
toolchain, rather than argued about. See the
[Mobile builds (experimental)](../.github/workflows/mobile.yml) workflow, which is
run by hand from the Actions tab - there is no sense in failing it on every push.

## What is here

| File | What it does |
| --- | --- |
| `settings.gradle` | Repositories (`google()` first, for the Android plugin) and the one `:app` module. |
| `app/build.gradle` | Android Gradle Plugin 8.7.3, `compileSdk 35`, `minSdk 26`, Java 11, plus the `copyJRockSource` task. |
| `app/src/main/AndroidManifest.xml` | One exported launcher activity, no permissions. |
| `app/src/main/java/JRockActivity.java` | `onCreate` calls `JRock.main(new String[0])`. |

`JRock.java` stays where it is, in the repository root, with no build descriptor
beside it - that is deliberate, and this module does not change it. A `Copy` task
(`copyJRockSource`, wired to `preBuild`) pulls the file into
`app/build/generated/jrock/` and that directory is added to the `main` source set.
Pointing a source directory two levels up instead would have dragged `tests/` and
the samples in with it.

There is no Gradle wrapper, because the repository carries no jars. CI installs
Gradle with `gradle/actions/setup-gradle`; locally, use whatever Gradle 8.x you
have:

```
cd android
gradle --no-daemon assembleDebug
```

## Making it build

In rough order of how much work each is:

1. **Replace the UI.** Every `javax.swing` and `java.awt` reference has to go -
   the frame, the log pane, the choosers, the clipboard, the tray. That is most
   of the file.
2. **Replace the file access.** `java.nio.file.Path` works, but app-private
   storage and the document picker do not look like a `JFileChooser`.
3. **Drop the external tools.** No Ghostscript, no LibreOffice, no `pdftoppm`,
   so PDF page images, DOCX export and the duplex merge all need a different
   answer (or a server).
4. **Keep the rest.** The Bedrock client, the prompt assembly, the include
   bookkeeping and the Markdown handling are plain Java and should survive.

Until step 1 happens, the honest way to run JRock on a phone is the browser
build - see [Running in a browser](../README.md#running-in-a-browser).
