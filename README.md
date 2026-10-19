# TimedSilence

### Effortless temporary phone silencing.

**TimedSilence** temporarily silences your Android device for a chosen duration. When the timer expires, it enables normal ringer mode even if the phone was silent or vibrating before the timer started. It uses the captured ring volume when positive, otherwise the current positive volume or the lowest audible level. Cancelling early restores the original mode instead.

## 🌟 Key Features

*   **Material 3 UI**: A vibrant, energetic interface built with Jetpack Compose, featuring full edge-to-edge support for a modern look and feel.
*   **Smart Minute Picker**: A high-performance, smooth-scrolling wheel to select your silence duration from 1 to 120 minutes.
*   **Mode Choice**: Easily toggle between **Silent** and **Vibrate** modes for the timed period.
*   **Status Notification**: An ongoing notification keeps you informed of the active session, showing the exact time your ringer will be restored.
*   **Reliability**: Original ringer settings (mode and volume) are persisted to disk and restored by an **exact alarm**, with a **WorkManager** job as a backstop. Restoration happens even when the app is closed, has been swapped out of memory, the screen is locked or the device has been restarted.
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

See [BUILDING.md](BUILDING.md) for how to produce the small (~6.5 MB) debug APK
for internal sharing, and the gotchas that keep it small.

---
*Developed with a focus on Material Design 3 principles and high-performance Compose UI.*
