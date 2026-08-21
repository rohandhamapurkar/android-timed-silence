# Building a debug APK for internal sharing

How to produce the small, shareable debug APK (~6.5 MB) that friends and testers
can install directly.

## Prerequisites

- Android Studio installed (its bundled JDK is all Gradle needs — no separate
  JDK install required).
- If `java` is not on your PATH, point `JAVA_HOME` at Android Studio's bundled
  JBR before invoking Gradle:

  ```bash
  # Git Bash
  export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
  ```

  ```powershell
  # PowerShell
  $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
  ```

  > Gotcha: if you have more than one Android Studio install, make sure the
  > `jbr` you pick actually works (`"$JAVA_HOME/bin/java" -version`). A stale
  > install can leave a broken JBR behind (missing `lib/jvm.cfg`) — use the
  > other install's `jbr` in that case.

## Build

Always build from clean when the APK is going to be shared:

```bash
./gradlew clean assembleDebug
```

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Rename it to something recognisable before sending, e.g. `TimedSilence-debug.apk`.

### Why `clean` matters

Gradle's incremental packager **reuses the previous APK file** and leaves dead
space where removed entries used to be. After dependency or resource changes,
an incremental `assembleDebug` can produce an APK many times larger than its
real contents (we have seen 57 MB of file for 6.5 MB of content). A clean build
packs it tight. If the APK looks suspiciously large, `clean` first.

## Why the APK is small

Two deliberate choices in `app/build.gradle.kts` keep the debug APK shareable —
don't undo them casually:

1. **The dependency list only contains what the code imports** (Compose,
   WorkManager, Activity, Lifecycle, Core, Coroutines). At one point the build
   carried CameraX, Retrofit, Moshi, Room, Coil, OkHttp, Play Services and more
   without a single import — that alone was tens of MB. Before adding a
   dependency, make sure something actually uses it.
2. **The `debug` build type runs R8 + resource shrinking**
   (`isMinifyEnabled = true`, `isShrinkResources = true`). If you need an
   unminified build to debug against, flip those to `false` locally — but don't
   ship that APK to testers, and don't commit the flip.

Because the shrunken build is what testers get, **smoke-test the APK after any
shrinking-related change** (new reflection use, new receivers/services, R8
version bumps): install it, start a short session, kill the app, and confirm
the ringer comes back on time. Minification failures show up at runtime, not
compile time.

## Sending it to someone

- **Android version**: `minSdk` is 36, so the phone must run **Android 16+**.
  The install fails with an uninformative error on anything older.
- **Debug signing**: the APK is signed with the shared debug key. The recipient
  installs it via a file manager and must allow "install from unknown sources";
  Google Play Protect may show a warning — expected for any sideloaded debug
  build.
- **Upgrades**: a newer debug APK installs over an older one (same key). If the
  recipient ever had a differently-signed build (e.g. a release build), they
  must uninstall it first.
- **Permissions**: no setup instructions needed — on first start the app walks
  the user through the Do Not Disturb access grant, and prompts for
  notifications and exact alarms itself.

## Sanity checklist before sharing

```bash
./gradlew clean assembleDebug testDebugUnitTest
```

- [ ] Build succeeds and all unit tests pass
- [ ] APK size is in the single-digit MB range (if not, see "Why `clean` matters")
- [ ] Installed once on a real device: session starts, ringer restores on expiry
