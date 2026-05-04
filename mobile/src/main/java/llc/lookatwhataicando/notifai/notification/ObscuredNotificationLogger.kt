package llc.lookatwhataicando.notifai.notification

import com.smartfoo.android.core.logging.FooLog

/**
 * Sink for [ObscuredNotification] telemetry.
 *
 * Register a custom sink via [ObscuredNotificationLogger.sink] to route events to a backend
 * (Firebase Firestore, Analytics, a local DB, etc.).
 * The default sink writes to logcat only.
 */
fun interface ObscuredNotificationSink {
    fun log(event: ObscuredNotification)
}

/**
 * Central logger for [ObscuredNotification] events.
 *
 * The [sink] is swappable at runtime — swap in a real backend implementation (e.g. Firebase)
 * at app startup via [ObscuredNotificationLogger.sink] = myFirebaseSink.
 */
object ObscuredNotificationLogger {

    private val TAG = FooLog.TAG(ObscuredNotificationLogger::class)

    var sink: ObscuredNotificationSink = ObscuredNotificationSink { event ->
        FooLog.i(
            TAG,
            "log: pkg=${event.packageName} app=${event.appLabel}" +
                " outcome=${event.resolutionOutcome} flags=0x${event.notificationFlags.toString(16)}"
        )
    }

    fun log(event: ObscuredNotification) = sink.log(event)
}
