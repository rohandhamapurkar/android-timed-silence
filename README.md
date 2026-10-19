# TimedSilence

### Effortless temporary phone silencing.

**TimedSilence** temporarily silences your Android device for a chosen duration. When the timer expires, it enables normal ringer mode even if the phone was silent or vibrating before the timer started. It uses the captured ring volume when positive, otherwise the current positive volume or the lowest audible level. Cancelling early restores the original mode instead.

## 🌟 Key Features

*   **Material 3 UI**: A vibrant, energetic interface built with Jetpack Compose, featuring full edge-to-edge support for a modern look and feel.
*   **Smart Minute Picker**: A high-performance, smooth-scrolling wheel to select your silence duration from 1 to 120 minutes.
*   **Mode Choice**: Easily toggle between **Silent** and **Vibrate** modes for the timed period.
*   **Status Notification**: An ongoing notification keeps you informed of the active session, showing the exact time your ringer will be restored.
*   **Reliability**: An **exact alarm** enables audible ringing at expiry, with a **WorkManager** job as a backstop. The saved original settings are used when cancelling early. Expiry is handled even when the app is closed, the screen is locked or the device has been restarted.
*   **DND Access**: Seamlessly handles the "Notification Policy Access" required to modify ringer modes on modern Android versions.

## 🛠️ Tech Stack

*   **Language**: Kotlin
*   **UI Framework**: Jetpack Compose (Material Design 3)
*   **Background Tasks**: AlarmManager (exact alarms) with a WorkManager backstop
*   **Persistence**: SharedPreferences
*   **Asynchrony**: Kotlin Coroutines & Flow
*   **Dependency Injection**: ViewModel with AndroidViewModel

## 🏗️ Architecture

The app follows the **MVVM (Model-View-ViewModel)** architecture pattern:
*   **View**: Jetpack Compose screens that react to state changes.
*   **ViewModel**: Manages UI state, captures ringer settings, and arms the restoration triggers.
*   **SilenceStore**: The source of truth. The captured mode, volume and deadline live in SharedPreferences, so every trigger reads the same state and a session can only be torn down once.
*   **RingerRestorer**: The single implementation of the restore, shared by the alarm, the backstop worker, the notification actions and the in-app Cancel button.

## 🔄 How it Works

1.  **Capture**: When you start a session, the app captures your current ringer mode and volume level and writes them to disk *before* touching the ringer.
2.  **Silence**: The device is set to your chosen target mode (Silent or Vibrate).
3.  **Schedule**: One exact deadline drives the countdown and two restoration triggers, armed before the phone is silenced:
    *   an exact `AlarmManager` alarm (`setExactAndAllowWhileIdle`), which fires on time through Doze and with the app not running;
    *   a `RingerRestorationWorker` as a backstop, in case the alarm is dropped.
4.  **Notify**: An ongoing notification shows the restore time and offers **Restore now** and **Extend +15m**.
5.  **Restore**: At expiry, either trigger enables normal ringer mode with an audible ring volume, regardless of the starting mode. It verifies the mode and volume before clearing the session. A failed attempt preserves the settings and retry work, and shows a recovery notification. Cancelling before expiry restores the captured mode. A reboot re-arms an active session, or enables ringing if its deadline already passed.

## 🔐 Permissions

To function correctly, TimedSilence requires:
*   **Notification Policy Access (DND)**: To change the ringer mode.
*   **Post Notifications**: To show the active session status (Android 13+).
*   **Schedule Exact Alarm**: Required before starting a timer so expiry can run precisely while the phone is idle. The app guides you to grant access and starts the pending timer when you return. Granting access during an existing session also re-arms its alarm.
*   **Receive Boot Completed**: To re-arm an in-flight session after a restart.

## 📦 Building & Sharing

Download the debug APK from the **Assets** section of the
[latest GitHub release](https://github.com/rohandhamapurkar/android-timed-silence/releases/latest).
The APK requires **Android 16+ (API 36)** and is built for internal sharing.

### Automated releases

The [release workflow](.github/workflows/android-build.yml) runs **only when a tag
is pushed**. Branch pushes, pull requests and manual dispatches do not build APKs.

To publish a version, create a GitHub release with a **new tag targeting `main`**,
or tag the desired commit locally and push it:

```bash
git switch main
git pull --ff-only origin main
git tag v1.1.0
git push origin v1.1.0
```

Use a new version tag for each release. The workflow builds the debug APK and
attaches it to the matching GitHub release. If no release exists, it creates one
with generated notes. Existing release notes and publication status are preserved;
re-running the tag workflow replaces an APK asset with the same filename.

CI uses JDK 21 and Android SDK command-line tools. SDK setup explicitly installs
`platform-tools`, avoiding the obsolete `tools` package that previously caused
the pipeline to fail.

### Local builds

See [BUILDING.md](BUILDING.md) for how to produce the small (~6.5 MB) debug APK
for internal sharing, and the gotchas that keep it small.

---
*Developed with a focus on Material Design 3 principles and high-performance Compose UI.*
