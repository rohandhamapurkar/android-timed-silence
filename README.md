# TimedSilence

### Effortless temporary phone silencing.

**TimedSilence** is a personal utility app designed to temporarily silence your Android device for a user-defined duration. The app ensures that the device returns to its original ringer state automatically, preventing missed calls or notifications after the intended quiet period.

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
3.  **Schedule**: The end time is rounded to the nearest minute, and two triggers are armed for it:
    *   an exact `AlarmManager` alarm (`setExactAndAllowWhileIdle`), which fires on time through Doze and with the app not running;
    *   a `RingerRestorationWorker` as a backstop, in case the alarm is dropped.
4.  **Notify**: An ongoing notification shows the restore time and offers **Restore now** and **Extend +15m**.
5.  **Restore**: Whichever trigger arrives first restores the captured mode (and, for a ringing mode, the ring volume), cancels the other trigger, dismisses the notification and clears the stored session. A reboot re-arms the alarm from the stored session; if the deadline passed while the device was off, the ringer is restored immediately on boot.

## 🔐 Permissions

To function correctly, TimedSilence requires:
*   **Notification Policy Access (DND)**: To change the ringer mode.
*   **Post Notifications**: To show the active session status (Android 13+).
*   **Schedule Exact Alarm**: To restore the ringer at the exact minute you picked. Without it the app still works, but the restore can land a few minutes late; the home screen offers a shortcut to grant it.
*   **Receive Boot Completed**: To re-arm an in-flight session after a restart.

## 📦 Building & Sharing

See [BUILDING.md](BUILDING.md) for how to produce the small (~6.5 MB) debug APK
for internal sharing, and the gotchas that keep it small.

---
*Developed with a focus on Material Design 3 principles and high-performance Compose UI.*
