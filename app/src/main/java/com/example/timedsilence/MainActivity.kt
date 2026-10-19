package com.example.timedsilence

import android.Manifest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.timedsilence.ui.theme.TimedSilenceTheme
import com.example.timedsilence.util.PermissionUtils
import com.example.timedsilence.util.ReadinessStatus
import com.example.timedsilence.util.SilenceNotifications
import kotlinx.coroutines.flow.distinctUntilChanged
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TimedSilenceTheme {
                TimedSilenceApp(viewModel)
            }
        }
    }
}

/** Durations that cover nearly every real session, one tap each. */
private val QUICK_DURATIONS = listOf(15, 30, 45, 60, 90)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun TimedSilenceApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val isSilenced by viewModel.isSilenced.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val remainingMillis by viewModel.remainingMillis.collectAsState()
    val hasPendingStart by viewModel.hasPendingStart.collectAsState()
    val restorationFailed by viewModel.restorationFailed.collectAsState()

    // Seeded from the last session so a repeat is a single tap.
    var selectedDuration by rememberSaveable { mutableStateOf(viewModel.lastDurationMinutes()) }
    var selectedMode by rememberSaveable { mutableStateOf(viewModel.lastTargetMode()) }
    var endingVolume by rememberSaveable { mutableStateOf(viewModel.endingVolumePercent()) }
    var readiness by remember { mutableStateOf(PermissionUtils.readiness(context)) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            readiness = PermissionUtils.readiness(context)
            if (!isGranted) {
                Toast.makeText(context, "Permission denied for notifications.", Toast.LENGTH_SHORT).show()
            }
        }
    )

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val hasDndAccess = readiness.soundAccess

    // The session can end while the app is in the background, permissions can be
    // granted from Settings, and a start may be waiting on the DND screen - so
    // re-evaluate everything on every resume.
    LifecycleResumeEffect(Unit) {
        viewModel.onResumed()
        readiness = PermissionUtils.readiness(context)
        onPauseOrDispose { }
    }

    // Rationale shown instead of dumping the user straight into system Settings.
    if (hasPendingStart) {
        val needsDndAccess = !hasDndAccess
        AlertDialog(
            onDismissRequest = { viewModel.abandonPendingStart() },
            title = { Text(if (needsDndAccess) "Allow Do Not Disturb access" else "Allow exact alarms") },
            text = {
                Text(
                    if (needsDndAccess) "To silence the ringer and turn it on when the timer expires, Android requires " +
                        "“Do Not Disturb” access for this app.\n\n" +
                        "Grant it on the next screen to continue setting up your timer."
                    else "Allow alarms and reminders so Android can restore your ringer when the timer " +
                        "expires, even with the screen off. Your session will start when you return."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (needsDndAccess) PermissionUtils.requestNotificationPolicyAccess(context)
                    else PermissionUtils.requestExactAlarmAccess(context)
                }) {
                    Text("Open settings")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.abandonPendingStart() }) {
                    Text("Not now")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (showSettings) "Settings" else "Timed Silence") },
                navigationIcon = {
                    if (showSettings) IconButton(onClick = { showSettings = false }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to timer")
                    }
                },
                actions = {
                    if (!showSettings) IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Open settings")
                    }
                }
            )
        },
        bottomBar = {
            if (!showSettings && !isSilenced) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.requestStart(selectedDuration, selectedMode, endingVolume)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text("Start Silence", style = MaterialTheme.typography.titleLarge)
                    }
                    if (!readiness.canStartTimer) {
                        TextButton(onClick = { showSettings = true }) { Text("Setup needs attention") }
                    } else {
                        Text("Ending volume: $endingVolume%", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        if (showSettings) {
            TimerSettingsScreen(
                modifier = Modifier.padding(innerPadding),
                endingVolume = endingVolume,
                onEndingVolumeChange = {
                    endingVolume = it
                    viewModel.setEndingVolumePercent(it)
                },
                isTimerActive = isSilenced,
                readiness = readiness,
                onSoundAccess = { PermissionUtils.requestNotificationPolicyAccess(context) },
                onExactAlarms = { PermissionUtils.requestExactAlarmAccess(context) },
                onNotifications = { PermissionUtils.requestNotificationSettings(context) }
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // Landscape, small screens and large display/font scales must
                // still be able to reach the button at the bottom.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isSilenced) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsPaused,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isSilenced) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = if (isSilenced) "Silence Active" else "Set a quiet timer",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp
                )
            )

            Text(
                text = if (isSilenced) activeSession.describe() else "The ringer turns on when the timer ends.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!isSilenced) {
                Text(
                    text = "Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedMode == AudioManager.RINGER_MODE_SILENT,
                        onClick = { selectedMode = AudioManager.RINGER_MODE_SILENT },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        label = { Text("Silent") }
                    )
                    SegmentedButton(
                        selected = selectedMode == AudioManager.RINGER_MODE_VIBRATE,
                        onClick = { selectedMode = AudioManager.RINGER_MODE_VIBRATE },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        label = { Text("Vibrate") }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Duration (Minutes)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    QUICK_DURATIONS.forEach { minutes ->
                        FilterChip(
                            selected = selectedDuration == minutes,
                            onClick = { selectedDuration = minutes },
                            label = { Text(minutes.toString()) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                MinutePicker(
                    selectedMinutes = selectedDuration,
                    onMinutesSelected = { selectedDuration = it }
                )

            } else {
                if (restorationFailed) {
                    Text(
                        text = "Sound could not be restored. Check Do Not Disturb access, then retry.",
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = { PermissionUtils.requestNotificationPolicyAccess(context) }) {
                        Text("Check sound access")
                    }
                }
                Text(
                    text = "Restoring in",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = formatRemaining(remainingMillis),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum"
                    ),
                    color = MaterialTheme.colorScheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clearAndSetSemantics {
                            contentDescription = describeRemaining(remainingMillis)
                        }
                )

                activeSession?.let { session ->
                    Text(
                        text = "Ringer turns on at ${formatWallClock(session.endTimeMillis)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

                OutlinedButton(
                    enabled = !restorationFailed,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.extendSilence()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        "Extend +${SilenceNotifications.EXTENSION_MINUTES} min",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.cancelSilence()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(if (restorationFailed) "Retry restore" else "Cancel & Restore", style = MaterialTheme.typography.titleLarge)
                }
            }

        }
    }
}

@Composable
private fun TimerSettingsScreen(
    modifier: Modifier = Modifier,
    endingVolume: Int,
    onEndingVolumeChange: (Int) -> Unit,
    isTimerActive: Boolean,
    readiness: ReadinessStatus,
    onSoundAccess: () -> Unit,
    onExactAlarms: () -> Unit,
    onNotifications: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sound", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Ending ringer volume", style = MaterialTheme.typography.titleSmall)
                Text("$endingVolume%", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 12.dp))
                Slider(
                    value = endingVolume.toFloat(),
                    onValueChange = { onEndingVolumeChange(it.roundToInt()) },
                    valueRange = 1f..100f,
                    modifier = Modifier.semantics { contentDescription = "Ringer volume at expiry" }
                )
                Text(
                    if (isTimerActive) "Applies to your next timer. The running timer keeps its saved ending volume."
                    else "The ringer turns on at this volume when your timer ends. Saved automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text("Device readiness", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        ReadinessPanel(readiness, onSoundAccess, onExactAlarms, onNotifications)
        Text("About", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
        Text("Timed Silence · Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReadinessPanel(
    readiness: ReadinessStatus,
    onSoundAccess: () -> Unit,
    onExactAlarms: () -> Unit,
    onNotifications: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                when {
                    readiness.fullyReady -> "Ready for timers"
                    readiness.canStartTimer -> "Timers ready; notifications off"
                    else -> "Setup needs attention"
                },
                style = MaterialTheme.typography.titleSmall
            )
            ReadinessRow("Sound access", readiness.soundAccess, "Allow", onSoundAccess)
            ReadinessRow("Exact alarms", readiness.exactAlarms, "Allow", onExactAlarms)
            ReadinessRow("Notifications", readiness.notifications, "Enable", onNotifications)
            if (!readiness.notifications) {
                Text("Timers can run, but status and completion notifications are unavailable.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean, action: String, onAction: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (ready) Text("Ready", style = MaterialTheme.typography.labelMedium)
        else TextButton(onClick = onAction) { Text(action, modifier = Modifier.semantics {
            contentDescription = "$action $label"
        }) }
    }
}

private fun ActiveSession?.describe(): String = when (this?.targetMode) {
    AudioManager.RINGER_MODE_SILENT -> "Phone is on silent."
    AudioManager.RINGER_MODE_VIBRATE -> "Phone is on vibrate."
    else -> "Phone handled automatically."
}

/**
 * Formats the time left as mm:ss, or h:mm:ss once an hour or more remains.
 * Rounds up so the countdown starts at the full duration and only shows 00:00
 * once the session is actually over.
 */
fun formatRemaining(remainingMillis: Long): String {
    val totalSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

/** Spoken form of the countdown for screen readers ("Restores in 5 minutes"). */
fun describeRemaining(remainingMillis: Long): String {
    val totalSeconds = ((remainingMillis + 999L) / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val parts = buildList {
        if (hours > 0L) add("$hours hour${if (hours == 1L) "" else "s"}")
        if (minutes > 0L) add("$minutes minute${if (minutes == 1L) "" else "s"}")
        if (hours == 0L && (seconds > 0L || isEmpty())) {
            add("$seconds second${if (seconds == 1L) "" else "s"}")
        }
    }
    return "Restores in ${parts.joinToString(" ")}"
}

private fun formatWallClock(timeMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMillis))

@Preview(showBackground = true, device = "spec:width=411dp,height=891dp,dpi=420")
@Composable
fun TimedSilenceAppPreview() {
    TimedSilenceTheme {
        TimedSilenceApp(viewModel = MainViewModel(LocalContext.current.applicationContext as android.app.Application))
    }
}

@Composable
fun MinutePicker(
    selectedMinutes: Int,
    onMinutesSelected: (Int) -> Unit
) {
    val minutesRange = remember { (1..120).toList() }
    val itemHeight = 48.dp
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedMinutes - 1)
    val snapFlingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // True while the wheel is being animated to a chip selection. The wheel's
    // own position callback must stay quiet then, or the intermediate positions
    // it passes through would overwrite the very selection being animated to.
    var followingExternalSelection by remember { mutableStateOf(false) }

    // Requirement 2: High-performance mathematical scroll calculation (no list searches)
    LaunchedEffect(listState) {
        snapshotFlow {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleOffset = listState.firstVisibleItemScrollOffset

            // At scroll 0, item 0 is centered due to contentPadding.
            // Each itemHeightPx of scroll moves the next item into the center.
            val centeredIndex = firstVisibleIndex + Math.round(firstVisibleOffset / itemHeightPx).toInt()

            centeredIndex.coerceIn(0, minutesRange.size - 1)
        }
        .distinctUntilChanged()
        .collect { index ->
            if (!followingExternalSelection) {
                onMinutesSelected(minutesRange[index])
            }
        }
    }

    // Follow selections made outside the wheel (the quick-pick chips).
    LaunchedEffect(selectedMinutes) {
        val targetIndex = (selectedMinutes - 1).coerceIn(0, minutesRange.size - 1)
        val centeredIndex = listState.firstVisibleItemIndex +
            Math.round(listState.firstVisibleItemScrollOffset / itemHeightPx).toInt()
        if (centeredIndex != targetIndex && !listState.isScrollInProgress) {
            followingExternalSelection = true
            try {
                listState.animateScrollToItem(targetIndex)
            } finally {
                // A drag can cancel the animation; the callback resumes either way.
                followingExternalSelection = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp)
            .semantics {
                contentDescription = "Duration picker"
                stateDescription = "$selectedMinutes minutes"
                liveRegion = LiveRegionMode.Polite
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent, 0.3f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .padding(horizontal = 16.dp)
                .graphicsLayer { alpha = 0.1f }
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
        )

        LazyColumn(
            state = listState,
            flingBehavior = snapFlingBehavior,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(minutesRange) { index, minute ->
                val isSelected = selectedMinutes == minute

                // Requirement 2: Performant distance calculation for scaling
                val scale by remember(index) {
                    derivedStateOf {
                        val firstVisibleIndex = listState.firstVisibleItemIndex
                        val firstVisibleOffset = listState.firstVisibleItemScrollOffset

                        // Distance calculation relative to the "centered" position
                        val itemOffset = (index - firstVisibleIndex) * itemHeightPx - firstVisibleOffset
                        val distanceFromCenter = abs(itemOffset)

                        (1f - (distanceFromCenter / 300f)).coerceIn(0.7f, 1f)
                    }
                }

                Box(
                    modifier = Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale; alpha = scale
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = minute.toString(),
                        style = if (isSelected)
                            MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold)
                        else
                            MaterialTheme.typography.headlineSmall,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
