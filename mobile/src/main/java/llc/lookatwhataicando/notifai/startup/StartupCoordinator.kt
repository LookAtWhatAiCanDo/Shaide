package llc.lookatwhataicando.notifai.startup

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import llc.lookatwhataicando.notifai.MyAccessibilityService
import llc.lookatwhataicando.notifai.MyNotificationListenerService
import com.smartfoo.android.core.notification.FooNotification
import com.smartfoo.android.core.permission.FooPermission
import com.smartfoo.android.core.platform.FooPlatformUtils

/**
 * Requirement  = hard gate. App cannot function without these.
 */
enum class Requirement {
    POST_NOTIFICATIONS,      // Runtime permission (API 33+ only)
    NOTIFICATION_LISTENER,   // Settings-mediated special access
    ACCESSIBILITY_SERVICE;   // Settings-mediated special access

    companion object {
        fun missing(context: Context): Set<Requirement> = buildSet {
            if (!FooNotification.isPostNotificationsPermissionGranted(context))
                add(POST_NOTIFICATIONS)
            if (!MyNotificationListenerService.isNotificationListenerEnabled(context))
                add(NOTIFICATION_LISTENER)
            if (!FooPlatformUtils.isAccessibilityServiceEnabled(context, MyAccessibilityService::class.java))
                add(ACCESSIBILITY_SERVICE)
        }
    }
}

/**
 * Advisory     = soft hint. Shown as recommendations, never blocks isReady.
 */
enum class Advisory {
    BATTERY_OPTIMIZATION     // Recommended for FGS survivability on aggressive OEMs
}

data class StartupSnapshot(
    val missing: Set<Requirement> = emptySet(),
    val advisories: Set<Advisory> = emptySet(),
    val evaluated: Boolean = false
) {
    /** True only when evaluation is complete AND all hard requirements are met. */
    val isReady: Boolean get() = evaluated && missing.isEmpty()
}

/**
 * StartupState sealed class:
 *   Checking  = ViewModel constructed but recheck() not yet returned.
 *               Exists only to give the splash screen a clean condition
 *               to hold on. In practice resolves in <1ms since all checks
 *               are synchronous.
 *   Result    = recheck() has run at least once. snapshot.evaluated = true.
 */
sealed class StartupState {
    /** Pre-evaluation. Splash screen holds here. */
    object Checking : StartupState()

    /** Post-evaluation. snapshot.evaluated is always true here. */
    data class Result(val snapshot: StartupSnapshot) : StartupState()
}

/**
 * Single source of truth for all runtime capability state.
 *
 * Two signal sources feed into state:
 *
 *   1. ListenerEnabledMonitor (callbackFlow) — fires immediately when
 *      NOTIFICATION_LISTENER is toggled. Collected in viewModelScope.
 *   2. recheck() — synchronous poll called:
 *        a. In init {} (startup)
 *        b. On every ON_RESUME via DisposableEffect in AppRoot
 *      Handles POST_NOTIFICATIONS and BATTERY_OPTIMIZATION which have
 *      no observable Settings key.
 * Both paths converge via _state.update { } so there is never a race
 * condition between them.
 */
class StartupCoordinator(private val app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<StartupState>(StartupState.Checking)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    /**
     * Derived StateFlow consumed by MainActivity's splash gate.
     * Avoids re-collecting the full state just for the splash condition.
     */
    val startupEvaluated: StateFlow<Boolean> = state
        .map { it is StartupState.Result }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // 1. Reactive: observe NOTIFICATION_LISTENER via ContentObserver.
        //    drop(1) skips the first emission because recheck() below already
        //    performs the full evaluation including listener state on startup.
        //    Subsequent emissions are genuine mid-session changes.
        viewModelScope.launch {
            NotificationListenerEnabledMonitor
                .observe(app)
                .drop(1)
                .collect { recheck() }
        }

        // 2. Synchronous: evaluate everything on startup.
        recheck()
    }

    /**
     * Evaluates all requirements and advisories synchronously.
     * Safe to call from any thread; state update is thread-safe via StateFlow.
     *
     * Called:
     *  - init {} (startup)
     *  - ON_RESUME (via DisposableEffect in AppRoot)
     *  - After POST_NOTIFICATIONS runtime permission result callback
     *  - After ListenerEnabledMonitor emits (via collect above)
     */
    fun recheck() {
        val missing = Requirement.missing(app)

        val advisories = buildSet {
            if (!FooPermission.isIgnoringBatteryOptimizations(app)) add(Advisory.BATTERY_OPTIMIZATION)
        }

        _state.value = StartupState.Result(
            StartupSnapshot(
                missing    = missing,
                advisories = advisories,
                evaluated  = true
            )
        )
    }
}
