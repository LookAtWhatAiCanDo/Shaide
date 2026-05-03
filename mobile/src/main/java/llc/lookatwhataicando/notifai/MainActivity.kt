package llc.lookatwhataicando.notifai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartfoo.android.core.notification.FooNotification
import com.smartfoo.android.core.permission.FooPermission
import com.smartfoo.android.core.platform.FooPlatformUtils
import com.smartfoo.android.core.texttospeech.FooTextToSpeechHelper
import llc.lookatwhataicando.notifai.startup.Advisory
import llc.lookatwhataicando.notifai.startup.Requirement
import llc.lookatwhataicando.notifai.startup.StartupCoordinator
import llc.lookatwhataicando.notifai.startup.StartupSnapshot
import llc.lookatwhataicando.notifai.startup.StartupState
import llc.lookatwhataicando.notifai.ui.theme.NotifAITheme

/**
 * Responsibilities:
 *   1. Install system SplashScreen before super.onCreate()
 *   2. Hold splash until startupEvaluated = true
 *      (NOT until isReady — that would deadlock on first launch)
 *   3. Hand off entirely to Compose
 */
class MainActivity : ComponentActivity() {
    companion object {
        //private val TAG = FooLog.TAG(MainActivity::class)

        private const val ACTION_PIN = "llc.lookatwhataicando.notifai.MainActivity.action.PIN"

        fun intentShow(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun intentPin(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_PIN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    private val coordinator: StartupCoordinator by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition {
            !coordinator.startupEvaluated.value
        }
        val app = application as MyApp
        setContent {
            NotifAITheme {
                AppRoot(
                    coordinator = coordinator,
                    textToSpeechManager = app.textToSpeechManager
                )
            }
        }
    }
}

/**
 * Lifecycle re-evaluation:
 *   DisposableEffect + LifecycleEventObserver is the correct primitive here.
 *   recheck() is synchronous — it doesn't need a coroutine scope.
 *   LaunchedEffect/repeatOnLifecycle would work but implies async work.
 *
 *   ON_RESUME covers:
 *     - Returning from Settings after granting POST_NOTIFICATIONS
 *     - Returning from Settings after granting NOTIFICATION_LISTENER
 *       (belt-and-suspenders alongside the ContentObserver)
 *     - Returning from battery optimization settings
 *     - Task re-entry after process death/recreation
 */
@Composable
fun AppRoot(
    coordinator: StartupCoordinator,
    textToSpeechManager: TextToSpeechManager
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coordinator.recheck()
                textToSpeechManager.retryStartIfFailed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val state by coordinator.state.collectAsStateWithLifecycle()
    val textToSpeechInitState by textToSpeechManager.initState.collectAsStateWithLifecycle()
    val textToSpeechReady = textToSpeechInitState is TextToSpeechManager.InitState.Ready
    when (state) {
        // recheck() hasn't returned yet. Splash is covering the window.
        // Render nothing — avoids a single-frame flash of empty UI.
        is StartupState.Checking -> Unit
        is StartupState.Result -> {
            val snapshot = (state as StartupState.Result).snapshot
            when {
                !snapshot.evaluated -> {
                    // Shouldn't occur (Result always has evaluated=true) but
                    // guard defensively so splash logic stays correct.
                }
                snapshot.missing.isNotEmpty() || !textToSpeechReady -> {
                    // One or more hard requirements are missing.
                    PermissionsGateScreen(
                        snapshot = snapshot,
                        coordinator = coordinator,
                        textToSpeechManager = textToSpeechManager,
                        textToSpeechInitState = textToSpeechInitState
                    )
                }
                else -> {
                    // All requirements met. Advisory items may still be shown
                    // inside OperationalScreen as non-blocking recommendations.
                    OperationalScreen(snapshot, coordinator)
                }
            }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────

/**
 * Hard-requirement cards use filled Button  (primary visual weight).
 * Advisory cards use OutlinedButton         (secondary visual weight).
 * Visual weight communicates criticality without extra copy.
 *
 * POST_NOTIFICATIONS gets a secondary "Notification settings" button to
 * handle the edge case where the permission is granted but notifications
 * are channel-disabled in system settings.
 *
 * Permission callback result is intentionally ignored — recheck() is the
 * authoritative source of truth. This avoids stale state from the OS
 * callback race condition.
 */
@Composable
fun PermissionsGateScreen(
    snapshot: StartupSnapshot,
    coordinator: StartupCoordinator,
    textToSpeechManager: TextToSpeechManager,
    textToSpeechInitState: TextToSpeechManager.InitState
) {
    val forceShowAdvisories = false
    val textToSpeechReady = textToSpeechInitState is TextToSpeechManager.InitState.Ready
    val headerTitle = "Setup Required"
    val headerText = "This app speaks notifications in the background.\nThe following are required before it can start:"

    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val type = MaterialTheme.typography

    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        // Ignore the Boolean — recheck() is authoritative
        coordinator.recheck()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 56.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Header ───────────────────────────────────────────────────
        LaunchedEffect(textToSpeechInitState) {
            if (textToSpeechInitState is TextToSpeechManager.InitState.Ready) {
                textToSpeechManager.speak(headerTitle)
                textToSpeechManager.speak(headerText)
            }
        }
        Text(
            text = headerTitle,
            style = type.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = headerText,
            style = type.bodyMedium,
            color = colors.onSurfaceVariant,
            lineHeight = 22.sp
        )
        Spacer(Modifier.height(12.dp))
        // ── Required TEXT_TO_SPEECH card ─────────────────────────────
        RequirementCard(
            icon = Icons.Outlined.Warning,
            title = "Text To Speech Engine",
            description = when (textToSpeechInitState) {
                is TextToSpeechManager.InitState.Ready ->
                    "Required to speak notifications. Engine is initialized."
                is TextToSpeechManager.InitState.Initializing ->
                    "Required to speak notifications. Engine is currently initializing."
                is TextToSpeechManager.InitState.NotStarted ->
                    "Required to speak notifications. Engine has not started."
                is TextToSpeechManager.InitState.Failed ->
                    "Required to speak notifications. Engine failed to initialize."
            },
            isMissing = !textToSpeechReady,
            primaryAction = if (!textToSpeechReady) PermissionAction(
                label = "Retry Voice Setup",
                onClick = { textToSpeechManager.retryStartIfFailed() }
            ) else null,
            secondaryAction = if (!textToSpeechReady) PermissionAction(
                label = "Open TTS Settings",
                onClick = {
                    FooPlatformUtils.startActivity(context, FooTextToSpeechHelper.intentTextToSpeechSettings)
                }
            ) else null
        )
        Spacer(Modifier.height(12.dp))
        // ── Required POST_NOTIFICATIONS card ─────────────────────────
        val postMissing = Requirement.POST_NOTIFICATIONS in snapshot.missing
        RequirementCard(
            icon        = Icons.Outlined.Notifications,
            title       = "Post Notifications",
            description = "Required to display the persistent foreground-service notification.",
            isMissing   = postMissing,
            primaryAction = if (postMissing) PermissionAction(
                label   = "Grant Permission",
                onClick = {
                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            ) else null,
            secondaryAction = PermissionAction(
                label   = "Notification Settings",
                onClick = { FooNotification.startActivityAppNotificationSettings(context) }
            )
        )
        Spacer(Modifier.height(12.dp))
        // ── Required NOTIFICATION_LISTENER card ──────────────────────
        val listenerMissing = Requirement.NOTIFICATION_LISTENER in snapshot.missing
        RequirementCard(
            icon        = Icons.Outlined.Lock,
            title       = "Notification Listener",
            description = "Required to read notifications. " +
                    "Enable `NotifAI` in the list that opens.",
            isMissing   = listenerMissing,
            primaryAction = if (listenerMissing) PermissionAction(
                label   = "Notification read, reply, & control",
                onClick = { FooNotification.startActivityNotificationListenerSettings(context) }
            ) else null
        )
        Spacer(Modifier.height(12.dp))
        // ── Required ACCESSIBILITY_SERVICE card ──────────────────────
        val accessibilityMissing = Requirement.ACCESSIBILITY_SERVICE in snapshot.missing
        RequirementCard(
            icon        = Icons.Outlined.Lock,
            title       = "Accessibility Service",
            description = "Required to read notification content from apps that don't " +
                    "expose it via standard APIs (e.g. Google Chat). " +
                    "Enable `NotifAI` in the Accessibility settings that open.",
            isMissing   = accessibilityMissing,
            primaryAction = if (accessibilityMissing) PermissionAction(
                label   = "Enable NotifAI Accessibility",
                onClick = { FooPlatformUtils.showAccessibilitySettings(context) }
            ) else null
        )
        // ── Recommended BATTERY_OPTIMIZATION card ────────────────────
        if (forceShowAdvisories || Advisory.BATTERY_OPTIMIZATION in snapshot.advisories) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(12.dp))
            SectionLabel(
                text  = "RECOMMENDED",
                color = colors.tertiary  // amber in our scheme
            )
            Spacer(Modifier.height(12.dp))
            AdvisoryIgnoresBatteryOptimizations()
        }
    }
}

@Composable
fun AdvisoryIgnoresBatteryOptimizations() {
    val context = LocalContext.current
    AdvisoryCard(
        icon        = Icons.Outlined.Warning,
        title       = "Ignore Battery Optimizations",
        description = "Helps keep the app alive on OEMs with aggressive battery management " +
                "(Samsung, Xiaomi, OnePlus, etc). Not required, but strongly recommended.",
        action      = PermissionAction(
            label   = "Request Exemption",
            onClick = { FooPermission.startActivityIgnoreBatteryOptimizations(context) }
        )
    )
}


/**
 * By the time we reach here:
 *   ✓ POST_NOTIFICATIONS granted (or API < 33)
 *   ✓ NOTIFICATION_LISTENER enabled
 *   ✓ TextToSpeech engine initialized
 *   ✓ FGS is legal to start
 *
 * FGS is started via LaunchedEffect(Unit) — fires once when this
 * composable first enters composition. In a real app this would go
 * through a ServiceManager/Repository rather than directly here.
 *
 * Advisory items are shown as non-blocking cards. The user is already
 * in the app and can dismiss or action them at their leisure.
 */
@Composable
fun OperationalScreen(
    snapshot: StartupSnapshot,
    coordinator: StartupCoordinator
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        MyForegroundNotificationService.start(context)
        MyNotificationListenerService.requestNotificationListenerRebind(context)
    }

    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Service Running", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Notification listener is active.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        // Show pending advisories as non-blocking recommendations
        if (snapshot.advisories.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Recommended", style = MaterialTheme.typography.titleMedium, color = colors.onBackground)
            Spacer(Modifier.height(8.dp))
            if (Advisory.BATTERY_OPTIMIZATION in snapshot.advisories) {
                AdvisoryIgnoresBatteryOptimizations()
            }
        }
        Spacer(Modifier.height(24.dp))
        // Debug/QA convenience — harmless in production
        OutlinedButton(onClick = { coordinator.recheck() }) {
            Text("Re-check Permissions")
        }
    }
}

// ── Components ───────────────────────────────────────────────────────

data class PermissionAction(val label: String, val onClick: () -> Unit)

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight   = FontWeight.Bold,
            letterSpacing = 1.5.sp
        ),
        color = color
    )
}

/**
 * Card for a hard Requirement.
 * Filled primary Button signals "you must do this".
 * Optional OutlinedButton secondary action for edge cases.
 */
@Composable
private fun RequirementCard(
    icon: ImageVector,
    title: String,
    description: String,
    isMissing: Boolean,
    primaryAction: PermissionAction?,
    secondaryAction: PermissionAction? = null,
) {
    val colors = MaterialTheme.colorScheme

    // Border pulses red when missing, subtle when satisfied
    val borderColor = if (isMissing) colors.error.copy(alpha = 0.5f)
    else colors.outline.copy(alpha = 0.3f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        border   = BorderStroke(1.dp, borderColor),
        colors   = CardDefaults.cardColors(
            containerColor = colors.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Title row with icon + status chip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isMissing) colors.error else colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(granted = !isMissing)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                lineHeight = 20.sp
            )
            if (primaryAction != null || secondaryAction != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    primaryAction?.let {
                        Button(
                            onClick = it.onClick,
                            shape   = RoundedCornerShape(10.dp),
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = if (isMissing) colors.primary else colors.secondaryContainer,
                                contentColor   = if (isMissing) colors.onPrimary else colors.onSecondaryContainer
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(
                                it.label,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    secondaryAction?.let {
                        OutlinedButton(
                            onClick = it.onClick,
                            shape   = RoundedCornerShape(10.dp),
                            border  = BorderStroke(1.dp, colors.outline), // explicit — never invisible
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                it.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Card for an Advisory.
 * OutlinedButton signals "this is recommended, not required".
 */
@Composable
private fun AdvisoryCard(
    icon: ImageVector,
    title: String,
    description: String,
    action: PermissionAction,
    secondaryAction: PermissionAction? = null,
) {
    val colors = MaterialTheme.colorScheme

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        border   = BorderStroke(1.dp, colors.tertiary.copy(alpha = 0.35f)),
        colors   = CardDefaults.cardColors(
            // Slightly warmer surface to visually distinguish from requirement cards
            containerColor = colors.tertiaryContainer.copy(alpha = 0.15f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text  = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text  = description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // OutlinedButton with amber/tertiary tint for advisory actions
                OutlinedButton(
                    onClick = action.onClick,
                    shape   = RoundedCornerShape(10.dp),
                    border  = BorderStroke(1.dp, colors.tertiary),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(
                        action.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.tertiary
                    )
                }
                secondaryAction?.let {
                    OutlinedButton(
                        onClick = it.onClick,
                        shape   = RoundedCornerShape(10.dp),
                        border  = BorderStroke(1.dp, colors.outline),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            it.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(granted: Boolean) {
    val colors = MaterialTheme.colorScheme

    val chipColor = if (granted) colors.primaryContainer else colors.errorContainer
    val textColor = if (granted) colors.onPrimaryContainer else colors.onErrorContainer
    val label     = if (granted) "Granted" else "Missing"

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(chipColor)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (granted) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = textColor
        )
    }
}
