package llc.lookatwhataicando.notifai

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.smartfoo.android.core.FooListenerAutoStartManager
import com.smartfoo.android.core.FooString
import com.smartfoo.android.core.logging.FooLog
import com.smartfoo.android.core.notification.FooNotification
import com.smartfoo.android.core.platform.FooPlatformUtils
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder
import llc.lookatwhataicando.notifai.NotificationShadeSnapshot.ShadeRow
import llc.lookatwhataicando.notifai.notification.NotificationParserUtils
import llc.lookatwhataicando.notifai.notification.ObscuredNotification
import llc.lookatwhataicando.notifai.notification.ObscuredNotificationLogger
import llc.lookatwhataicando.notifai.notification.ResolutionOutcome
import llc.lookatwhataicando.notifai.notification.parsers.NotificationParser
import llc.lookatwhataicando.notifai.notification.parsers.NotificationParserAndroidMessages
import llc.lookatwhataicando.notifai.notification.parsers.NotificationParserDiscord
import llc.lookatwhataicando.notifai.startup.Requirement
import java.util.concurrent.ConcurrentHashMap

class MyNotificationListenerService : NotificationListenerService() {
    companion object {
        private val TAG = FooLog.TAG(MyNotificationListenerService::class)

        @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions", "RedundantSuppression", "UNREACHABLE_CODE")
        private val LOG_NOTIFICATION = true && BuildConfig.DEBUG

        /** Window for sibling (child) notifications to arrive and cancel the accessibility lookup. */
        private const val PENDING_LOOKUP_DELAY_MS = 300L

        fun isNotificationListenerEnabled(context: Context) =
            FooNotification.isNotificationListenerEnabled(context, MyNotificationListenerService::class)

        /**
         * Do NOT call startForegroundService to start this service manually!
         * NotificationListenerService startup is managed entirely by the system.
         * If there is a need to ensure it is bound, call `MyNotificationListenerService.requestNotificationListenerRebind(context)`.
         */
        fun requestNotificationListenerRebind(context: Context) {
            FooNotification.requestNotificationListenerRebind(context, MyNotificationListenerService::class)
        }

        fun requestNotificationListenerUnbind(context: Context) {
            FooNotification.requestNotificationListenerUnbind(context, MyNotificationListenerService::class)
        }
    }

    interface NotificationParserServiceCallbacks {
        fun onNotificationParsed(parser: NotificationParser)
    }

    var isParserEnabled: Boolean = true
        get() = field
        set(value) {
            field = value
        }

    val textToSpeechManager: TextToSpeechManager by lazy {
        (applicationContext as MyApp).textToSpeechManager
    }

    private val parserCallbacks = object : NotificationParser.NotificationParserCallbacks {
        override val context: Context
            get() = this@MyNotificationListenerService
        override val textToSpeech: TextToSpeechManager
            get() = this@MyNotificationListenerService.textToSpeechManager
        override fun onNotificationParsed(parser: NotificationParser) = this@MyNotificationListenerService.onNotificationParsed(parser)
    }

    private val listenerManager = FooListenerAutoStartManager<NotificationParserServiceCallbacks>(this)

    /**
     * Returns true only when every hard [Requirement] — including POST_NOTIFICATIONS,
     * NOTIFICATION_LISTENER, and ACCESSIBILITY_SERVICE — is satisfied.
     *
     * The system binds this service as soon as NOTIFICATION_LISTENER is granted, regardless of
     * other requirements. Both [onListenerConnected] and [onNotificationPosted] check this before
     * doing any work, so the listener stays completely idle — no live notifications are spoken and
     * no launch catch-up occurs — until Accessibility (and all other requirements) are also
     * granted. This is intentional: the accessibility fallback path is required for correct
     * startup behaviour, so there is no partial mode to offer.
     */
    private fun areRequirementsMet() = Requirement.missing(this).isEmpty()

    private val notificationParsers = mutableMapOf<String, NotificationParser>()

    private fun addNotificationParser(notificationParser: NotificationParser) {
        notificationParsers[notificationParser.packageName] = notificationParser
    }

    fun addNotificationParsers() {
        addNotificationParser(NotificationParserDiscord(parserCallbacks))
        addNotificationParser(NotificationParserAndroidMessages(parserCallbacks))

        // TODO:
        //  * "llc.lookatwhataicando.notifai" Might be fine, but need to add a title and tweak
        //  * "com.amazon.dee.app" speaks "Alexa" too much
        //  * "com.ebay.mobile" speaks "ebay" too much
        //  * "com.google.android.apps.dynamite" Chat does not speak any text
        //  * "com.google.android.apps.messaging" does not speak any messages
        //  * "com.google.android.apps.magazines" does not speak any text
        //  * "com.google.android.apps.photos" does not speak any text
        //  * "com.google.android.gm" does not speak any text
        //  * "com.google.android.youtube" does not speak any text
        //  * "com.meetup" a little weird
        //  * "com.offerup" does not speak any text
        //  * "com.thehomedepot" speaks "Home Depot" too much and does not speak message text
        //  * "epic.mychart.android" does not speak any text

        // NOTE: NotificationParser.defaultOnNotificationPosted is confirmed to work OK on:
        //  * "android"
        //  * "com.amazon.mShop.android.shopping"
        //  * "com.android.chrome": ***MAYBE*** speaks "Chrome" too much?
        //  * "com.google.android.apps.weather"
        //  * "com.google.android.dialer"
        //  * "com.hematerra.bloodworks"
        //  * "com.openai.chatgpt"
        //  * "com.robinhood.android"
        //  * "com.samsung.wearable.watch7plugin"
        //  * "tv.twitch.android.app"
        //  * "com.qnap.qmanager": a little chatty; could be summarized better?
    }

    override fun onCreate() {
        FooLog.d(TAG, "+onCreate()")
        super.onCreate()
        addNotificationParsers()
        FooLog.d(TAG, "-onCreate()")
    }

    override fun onDestroy() {
        FooLog.d(TAG, "+onDestroy()")
        super.onDestroy()
        FooLog.d(TAG, "-onDestroy()")
    }

    private val activeNotificationsSnapshot = ActiveNotificationsSnapshot(this)

    private val handler = Handler(Looper.getMainLooper())

    fun initializeActiveNotifications() {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION +initializeActiveNotifications()")
        }
        val activeNotificationsSnapshot = activeNotificationsSnapshot.snapshot(this)
        val activeNotificationsRanked = activeNotificationsSnapshot.activeNotificationsRanked.orEmpty()
        if (activeNotificationsRanked.isNotEmpty()) {
            FooLog.v(TAG, "#NOTIFICATION initializeActiveNotifications: activeNotificationsRanked.size=${activeNotificationsRanked.size}")
            for (activeNotification in activeNotificationsRanked) {
                //FooLog.v(TAG, "#NOTIFICATION initializeActiveNotifications: activeNotification=$activeNotification")
                onNotificationPosted(activeNotification, activeNotificationsSnapshot.currentRanking)
            }
        }
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION -initializeActiveNotifications()")
        }
    }

    override fun onListenerConnected() {
        if (LOG_NOTIFICATION) {
            FooLog.d(TAG, "#NOTIFICATION onListenerConnected()")
        }
        super.onListenerConnected()
        if (!areRequirementsMet()) {
            FooLog.d(TAG, "#NOTIFICATION onListenerConnected: requirements not met; unbinding until ready")
            // Unbind so the system stops delivering notifications.
            // OperationalScreen calls requestNotificationListenerRebind() once all requirements are met,
            // at which point onListenerConnected fires again and initializeActiveNotifications()
            // catches up via getActiveNotifications() — no notifications are permanently lost.
            requestNotificationListenerUnbind(this)
            return
        }
        /*
        * Delay initialization to avoid system_server process binder transaction issues during the onListenerConnected callback:
        * ```
        * 2026-03-04 13:01:03.519  1469-1469  BoundServiceSession  system_server  E  Bad key 0 received in binderTransactionCompleted! Closing all transactions on CR{fe8bec1 1469->llc.lookatwhataicando.notifai/.MyNotificationListenerService flags=0x805000101}. Current keys: {onListenerConnected=0}; Counts: [0] (Fix with AI)
        *     android.util.Log$TerribleFailure: Bad key 0 received in binderTransactionCompleted! Closing all transactions on CR{fe8bec1 1469->llc.lookatwhataicando.notifai/.MyNotificationListenerService flags=0x805000101}. Current keys: {onListenerConnected=0}; Counts: [0]
        *       at android.util.Log.wtf(Log.java:339)
        *       at android.util.Slog.wtfStack(Slog.java:246)
        *       at com.android.server.am.BoundServiceSession.handleInvalidToken(BoundServiceSession.java:128)
        *       at com.android.server.am.BoundServiceSession.binderTransactionCompleted(BoundServiceSession.java:183)
        *       at com.android.server.notification.NotificationManagerService$NotificationListeners$1.$r8$lambda$QiDnMMKg1JpZvr40fh3mCflc3tA(NotificationManagerService.java:13958)
        *       at com.android.server.notification.NotificationManagerService$NotificationListeners$1$$ExternalSyntheticLambda0.run(R8$$SyntheticClass:0)
        *       at android.os.Handler.handleCallback(Handler.java:1070)
        *       at android.os.Handler.dispatchMessage(Handler.java:125)
        *       at android.os.Looper.dispatchMessage(Looper.java:333)
        *       at android.os.Looper.loopOnce(Looper.java:263)
        *       at android.os.Looper.loop(Looper.java:367)
        *       at com.android.server.SystemServer.run(SystemServer.java:1081)
        *       at com.android.server.SystemServer.main(SystemServer.java:711)
        *       at java.lang.reflect.Method.invoke(Native Method)
        *       at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:566)
        *       at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:907)
        * ```
        * The app itself and phone still seems to be running fine.
        * This error has not occurred since adding this delay on 2026/03/04.
        */
        Handler(Looper.getMainLooper()).post {
            initializeActiveNotifications()
        }
    }

    override fun onListenerDisconnected() {
        if (LOG_NOTIFICATION) {
            FooLog.d(TAG, "#NOTIFICATION onListenerDisconnected()")
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (LOG_NOTIFICATION) {
            FooLog.d(TAG, "#NOTIFICATION onNotificationPosted(sbn=${FooNotification.toString(sbn)}, rankingMap=${FooNotification.toString(rankingMap)})")
        }
        super.onNotificationPosted(sbn, rankingMap)
        if (sbn == null) {
            return
        }

        if (!isParserEnabled) {
            FooLog.v(TAG, "onNotificationPosted: isEnabled() == false; ignoring")
            return
        }

        if (!areRequirementsMet()) {
            FooLog.v(TAG, "onNotificationPosted: requirements not met; ignoring")
            return
        }

        val packageName = NotificationParserUtils.getPackageName(sbn)
        //FooLog.v(TAG, "onNotificationPosted: packageName=${FooString.quote(packageName)}")

        val notificationParser = notificationParsers[packageName]
        val result = notificationParser?.onNotificationPosted(sbn)
            ?: NotificationParser.defaultOnNotificationPosted(
                parserCallbacks.context,
                sbn,
                parserCallbacks.textToSpeech
            )
        when (result) {
            NotificationParser.NotificationParseResult.Unparsable ->
                FooLog.w(TAG, "onNotificationPosted: Unparsable StatusBarNotification")
            NotificationParser.NotificationParseResult.ParsedEmpty -> {
                // Content was not readable via NLS — schedule a deferred accessibility lookup
                // in case the content is visible in the notification shade.
                schedulePendingLookup(sbn)
            }
            else -> {
                // Content arrived via NLS — cancel any pending accessibility lookup for this package.
                cancelPendingLookup(packageName)
            }
        }
    }

    /** Pending accessibility lookups keyed by packageName. Runnables fire after PENDING_LOOKUP_DELAY_MS. */
    private val pendingLookups = ConcurrentHashMap<String, Runnable>()

    private fun schedulePendingLookup(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        // Cancel any existing pending lookup for this package (e.g. rapid repost).
        cancelPendingLookup(packageName)

        val appLabel = FooPlatformUtils.getApplicationName(this, packageName) ?: packageName
        FooLog.d(TAG, "schedulePendingLookup: packageName=$packageName appLabel=$appLabel delay=${PENDING_LOOKUP_DELAY_MS}ms")

        val runnable = Runnable {
            pendingLookups.remove(packageName)
            val accessibility = MyAccessibilityService.instance
            if (accessibility == null) {
                FooLog.w(TAG, "schedulePendingLookup: accessibility unavailable for $packageName")
                ObscuredNotificationLogger.log(
                    ObscuredNotification(
                        packageName        = packageName,
                        appLabel           = appLabel,
                        notificationFlags  = sbn.notification.flags,
                        postedAtMs         = sbn.postTime,
                        resolutionOutcome  = ResolutionOutcome.ACCESSIBILITY_UNAVAILABLE,
                    )
                )
                return@Runnable
            }
            if (MyAccessibilityService.DEBUG_FULL_SCAN_MODE) {
                accessibility.debugFullShadeScan()
                return@Runnable
            }
            accessibility.findRowForAppLabel(
                appLabel         = appLabel,
                shadeAlreadyOpen = false,
                callback         = { rows ->
                    val outcome = if (!rows.isNullOrEmpty()) ResolutionOutcome.FOUND else ResolutionOutcome.NOT_FOUND
                    ObscuredNotificationLogger.log(
                        ObscuredNotification(
                            packageName       = packageName,
                            appLabel          = appLabel,
                            notificationFlags = sbn.notification.flags,
                            postedAtMs        = sbn.postTime,
                            resolutionOutcome = outcome,
                        )
                    )
                    if (!rows.isNullOrEmpty()) {
                        speakShadeRows(appLabel, rows)
                    } else {
                        FooLog.i(TAG, "schedulePendingLookup: no shade rows found for $appLabel")
                    }
                }
            )
        }

        pendingLookups[packageName] = runnable
        handler.postDelayed(runnable, PENDING_LOOKUP_DELAY_MS)
        // Stale-obscured (confirmed, e.g. com.google.android.apps.dynamite / Google Chat):
        //   Live  — GROUP_SUMMARY is immediately followed by a content-bearing child
        //           (CHAT_CHIME, MessagingStyle, etc.) within milliseconds. That child arrives
        //           at onNotificationPosted, hits the `else` branch above, and cancels this
        //           runnable before it fires. MyAccessibilityService is NOT involved.
        //   Stale — initializeActiveNotifications iterates getActiveNotifications(). The
        //           content-bearing child has already been dismissed; only the empty
        //           GROUP_SUMMARY is active. No cancel arrives → this runnable fires →
        //           MyAccessibilityService opens the shade and reads the row.
        //           This is the primary reason the Accessibility permission is required.
        //
        // Always-obscured (theoretical, unconfirmed):
        //   Some apps may never post a content-bearing sibling — not even for live delivery.
        //   This runnable would always fire for those packages. Theory: if this class of app
        //   exists, it is limited to system-signed / AOSP / Google apps. See
        //   ObscuredNotificationLogger and notification/parsers/README.md for detection.
    }

    private fun cancelPendingLookup(packageName: String) {
        val runnable = pendingLookups.remove(packageName) ?: return
        handler.removeCallbacks(runnable)
        FooLog.d(TAG, "cancelPendingLookup: cancelled pending accessibility lookup for $packageName")
    }

    private fun speakShadeRows(appLabel: String, rows: List<ShadeRow>) {
        val builder = FooTextToSpeechBuilder(appLabel)
        for (row in rows) {
            row.title?.let { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
            if (row.messages.isNotEmpty()) {
                row.messages.forEach { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
            } else {
                row.text?.let { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
            }
        }
        if (builder.numberOfParts > 1) {
            parserCallbacks.textToSpeech.speak(builder)
        } else {
            FooLog.w(TAG, "speakShadeRows: shade rows for $appLabel have no speakable content: $rows")
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int
    ) {
        if (LOG_NOTIFICATION) {
            FooLog.d(TAG, "#NOTIFICATION onNotificationRemoved(sbn=${FooNotification.toString(sbn)}, rankingMap=${FooNotification.toString(rankingMap)}, reason=${FooString.quote(FooNotification.notificationCancelReasonToString(reason))})")
        }
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) {
            return
        }

        if (!isParserEnabled) {
            FooLog.v(TAG, "onNotificationRemoved: isEnabled() == false; ignoring")
            return
        }

        val packageName = NotificationParserUtils.getPackageName(sbn)
        FooLog.d(TAG, "onNotificationRemoved: packageName=${FooString.quote(packageName)}")

        val notificationParser = notificationParsers[packageName] ?: return

        // TODO:(pv) Reset any cache in the parser…
        notificationParser.onNotificationRemoved(sbn)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onNotificationRankingUpdate(rankingMap=${FooNotification.toString(rankingMap)})")
        }
        super.onNotificationRankingUpdate(rankingMap)
    }

    override fun onListenerHintsChanged(hints: Int) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onListenerHintsChanged(hints=${FooNotification.notificationHintsToString(hints)})")
        }
        super.onListenerHintsChanged(hints)
    }

    override fun onSilentStatusBarIconsVisibilityChanged(hideSilentStatusIcons: Boolean) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onSilentStatusBarIconsVisibilityChanged(hideSilentStatusIcons=$hideSilentStatusIcons)")
        }
        super.onSilentStatusBarIconsVisibilityChanged(hideSilentStatusIcons)
    }

    override fun onNotificationChannelModified(
        pkg: String?,
        user: UserHandle?,
        channel: NotificationChannel?,
        modificationType: Int,
    ) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onNotificationChannelModified(...)")
        }
        super.onNotificationChannelModified(pkg, user, channel, modificationType)
    }

    override fun onNotificationChannelGroupModified(
        pkg: String?,
        user: UserHandle?,
        group: NotificationChannelGroup?,
        modificationType: Int,
    ) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onNotificationChannelGroupModified(...)")
        }
        super.onNotificationChannelGroupModified(pkg, user, group, modificationType)
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        if (LOG_NOTIFICATION) {
            FooLog.v(TAG, "#NOTIFICATION onInterruptionFilterChanged(interruptionFilter=${FooNotification.notificationInterruptionFilterToString(interruptionFilter)})")
        }
        super.onInterruptionFilterChanged(interruptionFilter)
    }

    fun onNotificationParsed(parser: NotificationParser) {
        for (callbacks in listenerManager.beginTraversing()) {
            callbacks?.onNotificationParsed(parser)
        }
        listenerManager.endTraversing()
    }
}
