package llc.lookatwhataicando.notifai.notification.parsers

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import com.smartfoo.android.core.FooString
import com.smartfoo.android.core.logging.FooLog
import com.smartfoo.android.core.notification.FooNotification
import com.smartfoo.android.core.platform.FooPlatformUtils
import com.smartfoo.android.core.platform.FooRes
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder
import llc.lookatwhataicando.notifai.TextToSpeechManager
import llc.lookatwhataicando.notifai.notification.NotificationParserUtils

abstract class NotificationParser(private val hashtag: String, protected val parserCallbacks: NotificationParserCallbacks) {
    companion object {
        private val TAG = FooLog.TAG(NotificationParser::class)

        /**
         * Related:
         * * com.smartfoo.android.core.notification.FooNotification.toString
         * * https://claude.ai/share/a94dc67e-9b17-4e66-a33c-f572f928d499
         */
        fun defaultOnNotificationPosted(
            context: Context,
            sbn: StatusBarNotification,
            textToSpeechManager: TextToSpeechManager?,
            packageAppSpokenName: String? = null): NotificationParseResult {

            val packageName = NotificationParserUtils.getPackageName(sbn)
            FooLog.v(TAG, "defaultOnNotificationPosted: packageName=${FooString.quote(packageName)}")

            val packageAppSpokenName = if (!FooString.isNullOrEmpty(packageAppSpokenName)) packageAppSpokenName else FooPlatformUtils.getApplicationName(context, packageName)
            FooLog.v(TAG, "defaultOnNotificationPosted: packageAppSpokenName=${FooString.quote(packageAppSpokenName)}")

            val notification = NotificationParserUtils.getNotification(sbn)
            FooLog.v(TAG, "defaultOnNotificationPosted: notification=$notification")

            val extras = NotificationParserUtils.getExtras(notification)
            FooLog.v(TAG, "defaultOnNotificationPosted: extras=${FooPlatformUtils.toString(extras)}")

            val tickerText = notification.tickerText
            FooLog.v(TAG, "defaultOnNotificationPosted: tickerText=${FooString.quote(tickerText)}")

            // Always add packageAppSpokenName (initializing builder.numberOfParts == 1)
            val builder = FooTextToSpeechBuilder(packageAppSpokenName!!)

            /*
            // TODO:(pv) Seriously, introspect and walk all StatusBarNotification fields, especially:
            //  Notification.tickerText
            //  All ImageView Resource Ids and TextView Texts in BigContentView
            //  All ImageView Resource Ids and TextView Texts in ContentView
            val walkViewCallbacks: NotificationParserUtils.WalkViewCallbacks =
                object : NotificationParserUtils.WalkViewCallbacks {
                    override fun onTextView(textView: TextView) {
                        if (textView.visibility != View.VISIBLE) {
                            return
                        }

                        val text = textView.getText().toString()
                        if (FooString.isNullOrEmpty(text)) {
                            return
                        }

                        builder.appendSpeech(text)
                    }
                }
            */

            /*
            FooLog.v(TAG, "defaultOnNotificationPosted: ---- bigContentView ----")
            // NOTE: "As of N, this field may be null." :(
            val bigContentView = notification.bigContentView
            val inflatedBigContentView: View? = NotificationParserUtils.inflateRemoteView(context, bigContentView)
            NotificationParserUtils.walkView(
                inflatedBigContentView,
                null,
                true,
                walkViewCallbacks)
            //View mockBigContentView = mockRemoteView(mainApplication, bigContentView);
            //Set<Integer> bigContentViewIds = new LinkedHashSet<>();
            //walkView(mockBigContentView, bigContentViewIds);
            /*
            List<KeyValue> bigContentViewKeyValues = new LinkedList<>();
            walkActions(bigContentView, bigContentViewKeyValues);
            for (int i = 0; i < bigContentViewKeyValues.size(); i++)
            {
                KeyValue keyValue = bigContentViewKeyValues.get(i);
                FooLog.e(TAG, "bigContentView.mAction[" + i + "]=" + keyValue);
            }
            */
            */

            /*
            FooLog.v(TAG, "defaultOnNotificationPosted: ---- contentView ----")
            // NOTE: "As of N, this field may be null." :(
            val contentView = notification.contentView
            val inflatedContentView: View? = NotificationParserUtils.inflateRemoteView(context, contentView)
            NotificationParserUtils.walkView(
                inflatedContentView,
                null,
                true,
                if (bigContentView != null) null else walkViewCallbacks
            )
            //View mockContentView = mockRemoteView(mainApplication, contentView);
            //Set<Integer> contentViewIds = new LinkedHashSet<>();
            //walkView(mockContentView, contentViewIds);
            /*
            List<KeyValue> contentViewKeyValues = new LinkedList<>();
            walkActions(contentView, contentViewKeyValues);
            for (int i = 0; i < contentViewKeyValues.size(); i++)
            {
                KeyValue keyValue = contentViewKeyValues.get(i);
                FooLog.e(TAG, "contentView.mAction[" + i + "]=" + keyValue);
            }
            */
            */

            /*
            val headUpContentView = notification.headsUpContentView
            FooLog.v(TAG, "defaultOnNotificationPosted: headUpContentView=$headUpContentView")
            */

            val actions = notification.actions
            FooLog.v(TAG, "defaultOnNotificationPosted: actions(${actions?.size})=${FooString.toString(actions, ({ item ->
                when (item) {
                    is Notification.Action -> FooNotification.toString(item)
                    else -> FooString.quote(item)
                }
            }))}")

            val category = notification.category
            FooLog.v(TAG, "defaultOnNotificationPosted: category=$category")

            if (builder.numberOfParts == 1) {
                //
                // Found nothing in the above [currently commented out] bespoke parsing,
                // so fall back to trying any generic content...
                //
                if (tickerText != null) {
                    builder.appendSilenceSentenceBreak()
                        .appendSpeech(tickerText.toString())
                } else {
                    var appended = false

                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)
                    FooLog.v(TAG, "defaultOnNotificationPosted: title=${FooString.quote(title)}")
                    if (title != null) {
                        builder.appendSilenceSentenceBreak()
                            .appendSpeech(title.toString())
                        appended = true
                    }

                    val text = extras.getCharSequence(Notification.EXTRA_TEXT)
                    FooLog.v(TAG, "defaultOnNotificationPosted: text=${FooString.quote(text)}")
                    if (text != null) {
                        if (!appended) {
                            builder.appendSilenceSentenceBreak()
                        }
                        builder.appendSpeech(text.toString())
                    }

                    val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() // "subtitle"
                    val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
                    val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()

                    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
                    style?.messages?.let { messages ->
                        FooLog.v(TAG, "defaultOnNotificationPosted: messages(${messages.size})")
                        for (i in messages.indices) {
                            val message = messages[i]
                            FooLog.v(TAG, "defaultOnNotificationPosted:   message[$i]: ${FooNotification.toString(message)}")
                        }
                        FooLog.v(TAG, "defaultOnNotificationPosted:")
                    }
                }
            }

            // TODO: Clean up this ugly logic...

            if (textToSpeechManager == null) {
                FooLog.w(TAG, "defaultOnNotificationPosted: textToSpeechManager == null; ignore and return ParsedIgnored")
                return NotificationParseResult.ParsedIgnored
            }

            if (builder.numberOfParts > 1) {
                textToSpeechManager.speak(builder)
            } else {
                FooLog.w(TAG, "defaultOnNotificationPosted: No notification parts found; ignore and return ParsedEmpty")
                return NotificationParseResult.ParsedEmpty
            }

            return NotificationParseResult.DefaultWithTickerText
        }
    }

    protected var lastTextToSpeechString: String? = null
    protected var lastTextToSpeechBuilder: FooTextToSpeechBuilder? = null

    enum class NotificationParseResult {
        UnparsedIgnored,
        DefaultWithTickerText,
        DefaultWithoutTickerText,
        Unparsable,
        ParsedEmpty,
        ParsedIgnored,
        ParsedHandled,
    }

    interface NotificationParserCallbacks {
        val context: Context

        val textToSpeech: TextToSpeechManager

        fun onNotificationParsed(parser: NotificationParser)
    }

    protected fun hashtag(): String? {
        return hashtag(null)
    }

    protected fun hashtag(methodName: String?): String? {
        return if (FooString.isNullOrEmpty(methodName)) hashtag else ("$methodName: $hashtag")
    }

    protected val context: Context
        get() {
            return parserCallbacks.context
        }

    abstract val packageName: String

    val packageAppSpokenName: String?
        get() {
            return FooPlatformUtils.getApplicationName(context, packageName)
        }

    protected fun getString(resId: Int, vararg formatArgs: Any?): String {
        return FooRes.getString(context, resId, formatArgs)
    }

    protected val textToSpeech: TextToSpeechManager
        get() {
            return parserCallbacks.textToSpeech
        }

    protected val speakDefaultNotification: Boolean = false

    open fun onNotificationPosted(sbn: StatusBarNotification): NotificationParseResult {
        return defaultOnNotificationPosted(
            context,
            sbn,
            if (speakDefaultNotification) textToSpeech else null,
            packageAppSpokenName
        )
    }

    fun onNotificationRemoved(sbn: StatusBarNotification) {
    }

    protected fun speak(speech: String) {
        if (speech == lastTextToSpeechString) {
            return
        }

        lastTextToSpeechString = speech

        textToSpeech.speak(speech)
    }

    protected fun speak(builder: FooTextToSpeechBuilder) {
        if (builder == lastTextToSpeechBuilder) {
            return
        }

        lastTextToSpeechBuilder = FooTextToSpeechBuilder(builder)

        textToSpeech.speak(builder)
    }
}