package llc.lookatwhataicando.notifai

import android.content.Context
import android.speech.tts.TextToSpeech
import com.smartfoo.android.core.logging.FooLog
import com.smartfoo.android.core.texttospeech.FooTextToSpeech
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TextToSpeechManager {
    companion object {
        private val TAG = FooLog.TAG(TextToSpeechManager::class)
    }

    sealed interface InitState {
        object NotStarted : InitState
        object Initializing : InitState
        object Ready : InitState
        data class Failed(val status: Int) : InitState
    }

    val textToSpeech: FooTextToSpeech = FooTextToSpeech.instance

    val isStarted: Boolean
        get() = textToSpeech.isStarted

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var callbacksAttached = false

    private val _initState = MutableStateFlow<InitState>(InitState.NotStarted)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    private val callbacks =
        object : FooTextToSpeech.FooTextToSpeechCallbacks {
            override fun onTextToSpeechInitialized(status: Int) {
                val nextState =
                    if (status == TextToSpeech.SUCCESS) {
                        InitState.Ready
                    } else {
                        InitState.Failed(status)
                    }
                _initState.value = nextState
                if (nextState is InitState.Failed) {
                    FooLog.w(
                        TAG,
                        "onTextToSpeechInitialized: failed status=${FooTextToSpeech.statusToString(status)}"
                    )
                }
            }
        }

    private fun requireContext(): Context {
        return appContext ?: throw IllegalStateException("TextToSpeechManager.start(context) must be called first")
    }

    @Synchronized
    fun start(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (isStarted) {
            return
        }
        _initState.value = InitState.Initializing

        //
        // FooTextToSpeech debug preferences...
        //
        //FooTextToSpeech.VERBOSE_LOG_SEQUENCE = false
        FooTextToSpeech.VERBOSE_LOG_UTTERANCE = true

        val applicationContext = appContext!!
        if (!callbacksAttached) {
            callbacksAttached = true
            textToSpeech.start(applicationContext, callbacks)
        } else {
            textToSpeech.start(applicationContext)
        }
    }

    fun retryStartIfFailed() {
        val state = _initState.value
        if (state is InitState.Failed || !isStarted) {
            start(requireContext())
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) {
            return
        }
        if (!isStarted || _initState.value is InitState.Failed) {
            start(requireContext())
        }
        runCatching { textToSpeech.speak(text) }
            .onFailure { t ->
                FooLog.w(TAG, "speak(text): failed", t)
                if (t is IllegalStateException) {
                    start(requireContext())
                    runCatching { textToSpeech.speak(text) }
                        .onFailure { retryError -> FooLog.w(TAG, "speak(text): retry failed", retryError) }
                }
            }
    }

    fun speak(builder: FooTextToSpeechBuilder) {
        if (builder.numberOfParts <= 0) {
            return
        }
        if (!isStarted || _initState.value is InitState.Failed) {
            start(requireContext())
        }
        runCatching { textToSpeech.sequenceEnqueue(builder) }
            .onFailure { t ->
                FooLog.w(TAG, "speak(builder): failed", t)
                if (t is IllegalStateException) {
                    start(requireContext())
                    runCatching { textToSpeech.sequenceEnqueue(builder) }
                        .onFailure { retryError -> FooLog.w(TAG, "speak(builder): retry failed", retryError) }
                }
            }
    }

    fun stop() {
        textToSpeech.stop()
        _initState.value = InitState.NotStarted
    }
}
