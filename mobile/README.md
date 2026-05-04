
## Architecture

### Startup / permissions
- Single Activity + Compose UI gate (no navigation trampoline)
- System SplashScreen API (androidx.core.splashscreen)
- `StartupCoordinator` (ViewModel) as single source of truth
- `Requirement` enum → hard gates (blocks `isReady`)
- `Advisory` enum → soft hints (never blocks `isReady`)
- `ListenerEnabledMonitor` → callbackFlow/ContentObserver for instant mid-session revocation detection (no ON_RESUME polling needed for NOTIFICATION_LISTENER specifically)
- DisposableEffect + ON_RESUME still re-checks POST_NOTIFICATIONS and BATTERY_OPTIMIZATION which have no observable Settings key

### Notification delivery
Two paths — see [`notification/parsers/README.md`](src/main/java/llc/lookatwhataicando/notifai/notification/parsers/README.md) for full ground truth.

- **`MyNotificationListenerService`** — primary path; handles all live notifications via `onNotificationPosted` + per-package `NotificationParser` subclasses
- **`MyAccessibilityService`** — fallback for "obscured" notifications (empty `GROUP_SUMMARY` payload, content only in the shade); **primarily needed at launch** to catch up on notifications that arrived before the app started; largely not needed for live delivery

### Accessibility permission — true scope
The `ACCESSIBILITY_SERVICE` requirement is a hard gate in `StartupCoordinator`, but its real
impact is narrow: **without it, only the launch catch-up path is degraded**. Live notifications
are unaffected. See `StartupCoordinator.Requirement.ACCESSIBILITY_SERVICE` KDoc for details.
