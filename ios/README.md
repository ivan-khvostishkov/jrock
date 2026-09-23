# iOS target (experimental, expected to fail)

A minimal [MobiVM](https://mobivm.github.io/) (the maintained fork of RoboVM)
project that tries to ahead-of-time compile the repository's single `JRock.java`
into an iOS binary. It does not work, and it is not meant to yet: JRock is a Swing
application, and MobiVM ships no AWT and no Swing, so the AOT compiler fails while
resolving `javax.swing` - well before anything needs an Apple signing identity.

The point is to have that failure printed in a CI log, from a real iOS toolchain on
a real macOS runner, rather than argued about. See the
[Mobile builds (experimental)](../.github/workflows/mobile.yml) workflow, which is
run by hand from the Actions tab - there is no sense in failing it on every push.

## What is here

| File | What it does |
| --- | --- |
| `settings.gradle` | Maven Central, and the single root project. |
| `build.gradle` | RoboVM Gradle plugin 2.3.23, `robovm-rt` + `robovm-cocoatouch`, Java 11, plus the `copyJRockSource` task. |
| `robovm.xml` | `mainClass JRock`, `os ios`, `arch arm64`, and the plist. |
| `Info.plist.xml` | Bundle name, identifier, version, iPhone only. |

`JRock.java` stays where it is, in the repository root, with no build descriptor
beside it - that is deliberate, and this module does not change it. A `Copy` task
(`copyJRockSource`, wired to `compileJava`) pulls the file into
`build/generated/jrock/` and that directory is added to the `main` source set.

There is no Gradle wrapper, because the repository carries no jars. CI installs
Gradle with `gradle/actions/setup-gradle`; locally, on a Mac with Xcode installed:

```
cd ios
gradle --no-daemon build createIPA
```

Nothing about this works on Windows or Linux - MobiVM needs Xcode's linker and
`codesign`, which is why the CI job runs on `macos-latest`.

## Making it build

Same list as [Android](../android/README.md#making-it-build), plus two iOS-only
items:

- **A `UIApplicationDelegate`.** MobiVM apps start from
  `UIApplication.main(args, null, MyDelegate.class)`, not from a `main` that opens
  a window. `robovm.xml`'s `mainClass` would point at that instead.
- **Signing.** `createIPA` needs a development certificate and a provisioning
  profile, so a green build here still would not produce an installable app
  without secrets in the repository. `build.gradle` configures no signing identity
  at all, deliberately.

Until the UI is replaced, the honest way to run JRock on an iPhone is the browser
build - see [Running in a browser](../README.md#running-in-a-browser). Note the
known keyboard-focus quirk there.
