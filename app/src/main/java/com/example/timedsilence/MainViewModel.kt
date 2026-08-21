package com.example.timedsilence

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.example.timedsilence.util.PermissionUtils
import com.example.timedsilence.util.RestorationScheduler
import com.example.timedsilence.util.RingerRestorer
import com.example.timedsilence.util.SettingsStore
import com.example.timedsilence.util.SilenceNotifications
import com.example.timedsilence.util.SilenceSession
import com.example.timedsilence.util.SilenceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** What the active-session screen needs to know about the session in progress. */
data class ActiveSession(
    val endTimeMillis: Long,
    /** The mode the device was silenced into, [SilenceStore.NO_MODE] when unknown. */
    val targetMode: Int
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var internalWorkManager: WorkManager? = null

    private val workManager: WorkManager?
        get() = internalWorkManager ?: runCatching { WorkManager.getInstance(getApplication()) }.getOrNull()

    fun setWorkManager(wm: WorkManager) {
        internalWorkManager = wm
        observeWorkStatus()
    }

    private val _activeSession = MutableStateFlow<ActiveSession?>(null)
    val activeSession: StateFlow<ActiveSession?> = _activeSession.asStateFlow()

    private val _isSilenced = MutableStateFlow(false)
    val isSilenced: StateFlow<Boolean> = _isSilenced.asStateFlow()

    /**
     * A start request parked while the user is off granting DND access.
     * Consumed by [onResumed] the moment permission is available, so the user
     * does not have to find and press Start a second time.
     */
    private var pendingStart: Pair<Int, Int>? = null
    private val _hasPendingStart = MutableStateFlow(false)
    val hasPendingStart: StateFlow<Boolean> = _hasPendingStart.asStateFlow()

    /**
     * Milliseconds left in the session, ticking once per displayed second.
     *
     * Only collected while something is subscribed, so the ticker costs nothing
     * while the app is in the background - the restore itself is driven by the
     * alarm, not by this.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val remainingMillis: StateFlow<Long> = _activeSession
        .flatMapLatest { session ->
            if (session == null) flowOf(0L) else countdown(session.endTimeMillis)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), 0L)

    private var observeJob: Job? = null
    private var expiryJob: Job? = null

    companion object {
        const val KEY_CAPTURED_MODE = SilenceStore.KEY_CAPTURED_MODE
        const val KEY_CAPTURED_VOLUME = SilenceStore.KEY_CAPTURED_VOLUME
        const val KEY_END_TIME = SilenceStore.KEY_END_TIME
        const val NOTIFICATION_ID = SilenceNotifications.NOTIFICATION_ID
        const val EXTENSION_MINUTES = SilenceNotifications.EXTENSION_MINUTES

        private const val TAG = "MainViewModel"
        private const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }

    /** The single place UI state flips between idle and active. */
    private fun setActive(session: ActiveSession?) {
        _activeSession.value = session
        _isSilenced.value = session != null
    }

    private fun setPendingStart(request: Pair<Int, Int>?) {
        pendingStart = request
        _hasPendingStart.value = request != null
    }

    init {
        SilenceNotifications.createChannel(getApplication())
        refreshState()
        observeWorkStatus()
    }

    fun lastDurationMinutes(): Int = SettingsStore.lastDurationMinutes(getApplication())
    fun lastTargetMode(): Int = SettingsStore.lastTargetMode(getApplication())

    private fun observeWorkStatus() {
        observeJob?.cancel()
        val wm = workManager ?: return
        observeJob = viewModelScope.launch {
            try {
                wm.getWorkInfosForUniqueWorkLiveData(RestorationScheduler.UNIQUE_WORK_NAME)
                    .asFlow()
                    .collect { refreshState() }
            } catch (e: Exception) {
                Log.w(TAG, "Could not observe the restoration work", e)
            }
        }
    }

    /**
     * Recomputes the UI state from the stored session.
     *
     * The session, not the WorkManager queue, is the source of truth. The queue
     * lags behind an enqueue by a moment, and deriving state from it used to wipe
     * the captured ringer settings right after they had been saved - leaving
     * nothing to restore. It also catches a session whose deadline passed while
     * nothing was running, and restores immediately.
     */
    fun refreshState() {
        val session = SilenceStore.read(getApplication())
        if (session == null) {
            setActive(null)
            return
        }
        if (session.isDue()) {
            RingerRestorer.restore(getApplication(), workManager)
            setActive(null)
            return
        }
        setActive(ActiveSession(session.endTimeMillis, session.targetMode))
        SilenceNotifications.showOngoing(getApplication(), session.endTimeMillis)
        scheduleExpiryRefresh(session.endTimeMillis)
    }

    /**
     * Starts a session, or parks the request until DND access is granted.
     * Returns true when the session started, false when permission is missing
     * (the caller should send the user to the permission screen).
     */
    fun requestStart(durationMinutes: Int, targetMode: Int): Boolean {
        return if (PermissionUtils.hasNotificationPolicyAccess(getApplication())) {
            setPendingStart(null)
            startSilence(durationMinutes, targetMode)
            true
        } else {
            setPendingStart(durationMinutes to targetMode)
            false
        }
    }

    /**
     * Called on every resume. Refreshes state, and completes a start that was
     * waiting on the DND permission screen.
     */
    fun onResumed() {
        refreshState()
        val pending = pendingStart ?: return
        if (PermissionUtils.hasNotificationPolicyAccess(getApplication())) {
            setPendingStart(null)
            if (_activeSession.value == null) {
                startSilence(pending.first, pending.second)
            }
        }
    }

    fun abandonPendingStart() {
        setPendingStart(null)
    }

    fun startSilence(durationMinutes: Int, targetMode: Int) {
        if (_activeSession.value != null) return

        try {
            val modeToRestore = audioManager.ringerMode
            val volumeToRestore = try {
                audioManager.getStreamVolume(AudioManager.STREAM_RING)
            } catch (e: Exception) {
                SilenceStore.NO_VOLUME
            }

            // One end time drives the alarm, the backstop work, the notification
            // and the countdown. Exact: 10 minutes means 10:00 on the countdown.
            val endTimeMillis =
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(durationMinutes.toLong())

            // Persist before touching the ringer: a crash in between must never leave
            // the phone silent with no record of how to put it back.
            SilenceStore.save(
                getApplication(),
                SilenceSession(modeToRestore, volumeToRestore, endTimeMillis, targetMode)
            )
            SettingsStore.remember(getApplication(), durationMinutes, targetMode)

            audioManager.ringerMode = targetMode

            RestorationScheduler.schedule(getApplication(), endTimeMillis, workManager)
            SilenceNotifications.showOngoing(getApplication(), endTimeMillis)

            setActive(ActiveSession(endTimeMillis, targetMode))
            scheduleExpiryRefresh(endTimeMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting silence", e)
            RingerRestorer.restore(getApplication(), workManager, tearDownOnFailure = true)
            setActive(null)
        }
    }

    /** Pushes the deadline out by [EXTENSION_MINUTES] and re-arms both triggers. */
    fun extendSilence() {
        val current = _activeSession.value ?: return
        val newEndTime = maxOf(current.endTimeMillis, System.currentTimeMillis()) +
            TimeUnit.MINUTES.toMillis(EXTENSION_MINUTES.toLong())

        SilenceStore.updateEndTime(getApplication(), newEndTime)
        RestorationScheduler.schedule(getApplication(), newEndTime, workManager)
        SilenceNotifications.showOngoing(getApplication(), newEndTime)

        setActive(current.copy(endTimeMillis = newEndTime))
        scheduleExpiryRefresh(newEndTime)
    }

    fun cancelSilence() {
        expiryJob?.cancel()
        RingerRestorer.restore(getApplication(), workManager, tearDownOnFailure = true)
        setActive(null)
    }

    /** Flips the UI back to idle the moment the session ends while the app is open. */
    private fun scheduleExpiryRefresh(endTimeMillis: Long) {
        expiryJob?.cancel()
        val remaining = endTimeMillis - System.currentTimeMillis()
        if (remaining <= 0L) return
        expiryJob = viewModelScope.launch {
            delay(remaining)
            refreshState()
        }
    }

    /** Emits the time left, waking only when the displayed second is about to change. */
    private fun countdown(endTimeMillis: Long): Flow<Long> = flow {
        while (true) {
            val remaining = endTimeMillis - System.currentTimeMillis()
            if (remaining <= 0L) {
                emit(0L)
                break
            }
            emit(remaining)
            delay((remaining - 1L) % 1_000L + 1L)
        }
    }
}
