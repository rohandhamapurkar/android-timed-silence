package com.example.timedsilence

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.ArgumentMatchers.longThat
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    @Mock
    lateinit var application: Application
    @Mock
    lateinit var audioManager: AudioManager
    @Mock
    lateinit var notificationManager: NotificationManager
    @Mock
    lateinit var workManager: WorkManager
    @Mock
    lateinit var sharedPreferences: SharedPreferences
    @Mock
    lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    private val workInfoLiveData = MutableLiveData<List<WorkInfo>>()

    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(testDispatcher)

        `when`(application.getSystemService(Context.AUDIO_SERVICE)).thenReturn(audioManager)
        `when`(application.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)
        `when`(application.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor)
        `when`(sharedPreferencesEditor.putInt(anyString(), anyInt())).thenReturn(sharedPreferencesEditor)
        `when`(sharedPreferencesEditor.putLong(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(sharedPreferencesEditor)
        `when`(sharedPreferencesEditor.clear()).thenReturn(sharedPreferencesEditor)
        
        `when`(workManager.getWorkInfosForUniqueWorkLiveData(anyString())).thenReturn(workInfoLiveData)

        `when`(application.applicationContext).thenReturn(application)
        `when`(application.packageName).thenReturn("com.example.timedsilence")
        `when`(application.resources).thenReturn(mock(android.content.res.Resources::class.java))

        viewModel = MainViewModel(application)
        viewModel.setWorkManager(workManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startSilence captures state and sets mode`() {
        val originalMode = AudioManager.RINGER_MODE_NORMAL
        val originalVolume = 7
        val targetMode = AudioManager.RINGER_MODE_VIBRATE
        val duration = 30

        `when`(audioManager.ringerMode).thenReturn(originalMode)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(originalVolume)

        viewModel.startSilence(duration, targetMode)
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify state capture
        verify(sharedPreferencesEditor).putInt(MainViewModel.KEY_CAPTURED_MODE, originalMode)
        verify(sharedPreferencesEditor).putInt(MainViewModel.KEY_CAPTURED_VOLUME, originalVolume)
        verify(sharedPreferencesEditor).putLong(org.mockito.ArgumentMatchers.eq(MainViewModel.KEY_END_TIME), org.mockito.ArgumentMatchers.anyLong())
        verify(sharedPreferencesEditor, atLeastOnce()).apply()

        // Verify ringer change
        verify(audioManager).ringerMode = targetMode
    }

    @Test
    fun `cancelSilence restores state and clears prefs`() {
        val savedMode = AudioManager.RINGER_MODE_NORMAL
        val savedVolume = 5
        
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_MODE, -1)).thenReturn(savedMode)
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_VOLUME, -1)).thenReturn(savedVolume)
        `when`(sharedPreferences.getLong(MainViewModel.KEY_END_TIME, 0L))
            .thenReturn(System.currentTimeMillis() + 600_000L)

        viewModel.cancelSilence()
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify restoration
        verify(audioManager).ringerMode = savedMode
        verify(audioManager).setStreamVolume(AudioManager.STREAM_RING, savedVolume, 0)

        // Verify cleanup
        verify(sharedPreferencesEditor).clear()
        verify(notificationManager).cancel(MainViewModel.NOTIFICATION_ID)
    }

    @Test
    fun `startSilence stores an exact end time`() {
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(7)
        val before = System.currentTimeMillis()

        viewModel.startSilence(15, AudioManager.RINGER_MODE_SILENT)
        val after = System.currentTimeMillis()

        // 15 minutes means exactly 15 minutes - the countdown starts at 15:00.
        verify(sharedPreferencesEditor).putLong(
            org.mockito.ArgumentMatchers.eq(MainViewModel.KEY_END_TIME),
            longThat { it in (before + 15 * 60_000L)..(after + 15 * 60_000L) }
        )
        assertTrue(viewModel.isSilenced.value)
    }

    @Test
    fun `startSilence remembers the chosen duration and mode`() {
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(7)

        viewModel.startSilence(45, AudioManager.RINGER_MODE_SILENT)

        verify(sharedPreferencesEditor).putInt("last_duration_minutes", 45)
        verify(sharedPreferencesEditor).putInt("last_target_mode", AudioManager.RINGER_MODE_SILENT)
    }

    @Test
    fun `extendSilence pushes the deadline out by fifteen minutes`() {
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(7)

        viewModel.startSilence(10, AudioManager.RINGER_MODE_VIBRATE)
        val endBefore = viewModel.activeSession.value!!.endTimeMillis

        viewModel.extendSilence()

        val endAfter = viewModel.activeSession.value!!.endTimeMillis
        org.junit.Assert.assertEquals(
            endBefore + MainViewModel.EXTENSION_MINUTES * 60_000L,
            endAfter
        )
        verify(sharedPreferencesEditor).putLong(MainViewModel.KEY_END_TIME, endAfter)
    }

    @Test
    fun `requestStart without DND access parks the request instead of starting`() {
        `when`(notificationManager.isNotificationPolicyAccessGranted).thenReturn(false)

        val started = viewModel.requestStart(30, AudioManager.RINGER_MODE_VIBRATE)

        assertFalse(started)
        assertTrue(viewModel.hasPendingStart.value)
        assertFalse(viewModel.isSilenced.value)
        verify(audioManager, never()).ringerMode = anyInt()
    }

    @Test
    fun `onResumed completes a parked start once DND access is granted`() {
        `when`(notificationManager.isNotificationPolicyAccessGranted).thenReturn(false)
        `when`(audioManager.ringerMode).thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(audioManager.getStreamVolume(AudioManager.STREAM_RING)).thenReturn(7)
        viewModel.requestStart(30, AudioManager.RINGER_MODE_VIBRATE)

        // The user grants access on the Settings screen and comes back.
        `when`(notificationManager.isNotificationPolicyAccessGranted).thenReturn(true)
        viewModel.onResumed()

        assertFalse(viewModel.hasPendingStart.value)
        assertTrue(viewModel.isSilenced.value)
        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_VIBRATE
    }

    @Test
    fun `work status updates never wipe the captured session`() {
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_MODE, -1))
            .thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_VOLUME, -1)).thenReturn(5)
        `when`(sharedPreferences.getLong(MainViewModel.KEY_END_TIME, 0L))
            .thenReturn(System.currentTimeMillis() + 600_000L)

        // WorkManager reports nothing queued yet - it lags behind an enqueue.
        // That must not be read as "the session is over".
        testDispatcher.scheduler.runCurrent()
        workInfoLiveData.value = emptyList()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.isSilenced.value)
        verify(sharedPreferencesEditor, never()).clear()
        verify(notificationManager, never()).cancel(MainViewModel.NOTIFICATION_ID)
    }

    @Test
    fun `a session whose deadline has passed is restored on refresh`() {
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_MODE, -1))
            .thenReturn(AudioManager.RINGER_MODE_NORMAL)
        `when`(sharedPreferences.getInt(MainViewModel.KEY_CAPTURED_VOLUME, -1)).thenReturn(5)
        `when`(sharedPreferences.getLong(MainViewModel.KEY_END_TIME, 0L))
            .thenReturn(System.currentTimeMillis() - 60_000L)

        viewModel.refreshState()

        verify(audioManager).ringerMode = AudioManager.RINGER_MODE_NORMAL
        verify(sharedPreferencesEditor).clear()
        assertFalse(viewModel.isSilenced.value)
    }
}
