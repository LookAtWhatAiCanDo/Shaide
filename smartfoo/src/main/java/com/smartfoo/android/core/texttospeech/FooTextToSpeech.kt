package com.smartfoo.android.core.texttospeech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.smartfoo.android.core.FooListenerManager
import com.smartfoo.android.core.FooReflection
import com.smartfoo.android.core.FooString
import com.smartfoo.android.core.logging.FooLog
import com.smartfoo.android.core.media.FooAudioFocusController
import com.smartfoo.android.core.media.FooAudioUtils
import com.smartfoo.android.core.platform.FooPlatformUtils
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder.FooTextToSpeechPartEarcon
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder.FooTextToSpeechPartSilence
import com.smartfoo.android.core.texttospeech.FooTextToSpeechBuilder.FooTextToSpeechPartSpeech
import java.io.File

/**
 * **NOTE: There should be only one TextToSpeech instance per application:**
 * * Callers must use FooTextToSpeech.instance to get the singleton instance.
 * * Then call instance.start(context, callbacks) to initialize the instance.
 * * Then call instance.stop() when done.
 * * Optionally call instance.shutdown() when done done.
 *
 * FooTextToSpeech maintains its own queue of utterances using both a `sequenceId` and an `utteranceId`:
 * * `sequenceId` identifies the logical batch requested by a single public API call (`speak`, `silence`, `playEarcon`).
 *   A sequence can span multiple low-level TextToSpeech items (for example, a builder with speech and silence parts).
 *   Canceling by sequenceId removes the entire batch, whether already started or still queued.
 * * `utteranceId` matches the framework `TextToSpeech` call that is currently executing.
 *   It is only used internally to associate run-after callbacks and monitor progress callbacks from `UtteranceProgressListener`.
 * * Queue placement is configurable via [QueuePlacement]: append (default), play next, interrupt immediately, or clear everything first.
 *
 * References:
 *  * https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/core/java/android/speech/tts/
 *  * https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Settings/src/com/android/settings/tts/
 *
 *  NOTE: There should be no need to implement [android.speech.tts.TextToSpeech.setLanguage], the code of which has a comment that reads:
 *      "As of API level 21, setLanguage is implemented using setVoice"
 *
 *  TODO: Add wrappers around [android.speech.tts.TextToSpeech.addSpeech]?
 */
@Suppress("unused")
class FooTextToSpeech private constructor() {
    companion object {
        private val TAG = FooLog.TAG(FooTextToSpeech::class)

        var VERBOSE_LOG_INIT_QUEUE = false
        var VERBOSE_LOG_SPEAK = false
        var VERBOSE_LOG_SILENCE = false
        var VERBOSE_LOG_EARCON = false

        /**
         * Main thing1 to debug
         */
        var VERBOSE_LOG_SEQUENCE = false
        /**
         * Main thing2 to debug
         */
        var VERBOSE_LOG_UTTERANCE = false

        var VERBOSE_LOG_UTTERANCE_PROGRESS = false
        var VERBOSE_LOG_AUDIO_FOCUS = false

        const val DEFAULT_VOICE_SPEED = 1.35f
        const val DEFAULT_VOICE_PITCH = 1.0f
        const val DEFAULT_VOICE_VOLUME = 1.0f

        /**
         * Singleton instance of [FooTextToSpeech]
         */
        @JvmStatic
        val instance: FooTextToSpeech by lazy {
            FooTextToSpeech()
        }

        @JvmStatic
        fun speak(
            context: Context,
            text: String,
        ) = instance.start(context).speak(text)

        private val statusMap by lazy {
            FooReflection.mapConstants(TextToSpeech::class, "SUCCESS", "ERROR", "STOPPED")
        }

        @JvmStatic
        fun statusToString(status: Int) =
            FooReflection.toString(statusMap, status)

        private val queueModeMap by lazy {
            FooReflection.mapConstants(TextToSpeech::class, "QUEUE_")
                .toMutableMap()
                .apply {
                    /** [android.speech.tts.TextToSpeech.QUEUE_DESTROY] is hidden */
                    put(2, "QUEUE_DESTROY")
                }
        }

        @JvmStatic
        fun queueModeToString(queueMode: Int) =
            FooReflection.toString(queueModeMap, queueMode)

        private fun quote(s: String?) = FooString.quote(s)

        //
        //region test hooks
        //

        private val defaultAudioAttributesFactory = {
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        }
        private val defaultBundleFactory = { Bundle() }

        internal var audioAttributesFactory = defaultAudioAttributesFactory
        internal var bundleFactory = defaultBundleFactory

        internal fun resetAudioAttributesFactory() {
            audioAttributesFactory = defaultAudioAttributesFactory
        }

        internal fun resetBundleFactory() {
            bundleFactory = defaultBundleFactory
        }

        //
        //endregion test hooks
        //
    }

    interface FooTextToSpeechCallbacks {
        fun onTextToSpeechInitialized(status: Int)
    }

    interface SequenceCallbacks {
        /**
         * @param sequenceId the sequenceId returned from [sequenceEnqueue].
         * @see [UtteranceProgressListener.onStart]
         */
        fun onSequenceStart(sequenceId: String)

        /**
         * @param sequenceId the sequenceId returned from [sequenceEnqueue].
         * @param neverStarted If true, then the utterance was flushed before the synthesis started. If false, then the utterance was interrupted while being synthesized and its output is incomplete.
         * @param errorCode one of the ERROR_* codes from [TextToSpeech]
         * @see [UtteranceProgressListener.onDone], [UtteranceProgressListener.onError], and [UtteranceProgressListener.onStop].
         */
        fun onSequenceComplete(
            sequenceId: String,
            neverStarted: Boolean,
            errorCode: Int,
        )
    }

    /**
     * Controls how a new sequence is scheduled relative to existing work.
     */
    enum class QueuePlacement {
        /** Enqueue after all existing items. */
        APPEND,
        /** Place directly after the current utterance so it runs next. */
        NEXT,
        /** Interrupt only the current sequence and start this one immediately. */
        IMMEDIATE,
        /** Clear all sequences (active and pending) before enqueueing. */
        CLEAR,
    }

    private sealed class Utterance(
        val sequenceId: String,
        utteranceId: Long,
        val requestAudioFocus: Boolean,
    ) {
        val utteranceId = "${sequenceId}_${utteranceId}_$tag"

        companion object {
            fun create(
                sequenceId: String,
                utteranceId: Long,
                part: FooTextToSpeechBuilder.FooTextToSpeechPart,
            ): Utterance =
                when (part) {
                    is FooTextToSpeechPartSpeech -> {
                        Text(sequenceId, utteranceId, part.text)
                    }
                    is FooTextToSpeechPartSilence -> {
                        Silence(sequenceId, utteranceId, part.durationMillis)
                    }
                    is FooTextToSpeechPartEarcon -> {
                        Earcon(sequenceId, utteranceId, part.earcon)
                    }
                    else -> throw IllegalArgumentException("Unhandled part type ${part.javaClass}")
                }
        }

        abstract val tag: String

        class Text(
            sequenceId: String,
            utteranceId: Long,
            val text: String,
        ) : Utterance(sequenceId, utteranceId, true) {
            override val tag: String
                get() = "text"
        }

        class Silence(
            sequenceId: String,
            utteranceId: Long,
            val durationMillis: Int,
        ) : Utterance(sequenceId, utteranceId, false) {
            override val tag: String
                get() = "silence"
        }

        class Earcon(
            sequenceId: String,
            utteranceId: Long,
            val earcon: String,
        ) : Utterance(sequenceId, utteranceId, true) {
            override val tag: String
                get() = "earcon"
        }
    }

    private data class SequenceState(
        val callbacks: SequenceCallbacks?,
        var started: Boolean = false,
    )

    private val syncLock = Any()
    private val listeners = FooListenerManager<FooTextToSpeechCallbacks>(TAG)
    private val utteranceQueue = ArrayDeque<Utterance>()
    private var currentUtterance: Utterance? = null
    private val sequenceStates = mutableMapOf<String, SequenceState>()
    private val runAfterSpeak = Runnable { onRunAfterSpeak() }
    private var callbackHandlerThread: HandlerThread? = null
    private val callbackHandler: Handler by lazy {
        val mainLooper = Looper.getMainLooper()
        if (mainLooper != null) {
            Handler(mainLooper)
        } else {
            val thread = HandlerThread("FooTextToSpeechCallbacks").apply { start() }
            callbackHandlerThread = thread
            Handler(thread.looper)
        }
    }

    private val audioFocusController: FooAudioFocusController
    private val audioFocusControllerCallbacks: FooAudioFocusController.Callbacks

    @Volatile
    private var audioFocusControllerHandle: FooAudioFocusController.FocusHandle? = null

    /**
     * Audio-focus acquisition strategy:
     *  - Read/assign `audioFocusControllerHandle` only while holding `syncLock`.
     *  - Perform the potentially blocking framework `acquire(...)` call *outside* the lock.
     *  - Use double‑checked publication under `syncLock`; if another thread wins the race,
     *    release the extra handle to avoid leaks.
     */
    private fun audioFocusAcquireTry(audioAttributes: AudioAttributes): Boolean {
        val context =
            applicationContext
                ?: throw IllegalStateException("start(context) must be called before acquiring audio focus")

        // Fast path: if we already hold a handle, reuse it.
        synchronized(syncLock) {
            if (audioFocusControllerHandle != null) {
                if (VERBOSE_LOG_AUDIO_FOCUS) {
                    FooLog.v(TAG, "#TTS_AUDIO_FOCUS audioFocusAcquireTry: audioFocusControllerHandle already acquired; reusing")
                }
                return true
            }
        }

        // Slow path: request focus WITHOUT holding the lock.
        // This avoids blocking other threads that might be calling speak()/stop() and also
        // prevents lock inversions with framework callbacks.
        val newHandle =
            audioFocusController.acquire(
                context = context,
                audioAttributes = audioAttributes,
                callbacks = audioFocusControllerCallbacks,
            )
        if (newHandle == null) {
            FooLog.w(TAG, "#TTS_AUDIO_FOCUS audioFocusAcquireTry: audio focus request denied (ex: another app has AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE); ignoring")
            return false
        }

        synchronized(syncLock) {
            if (VERBOSE_LOG_AUDIO_FOCUS) {
                FooLog.v(TAG, "#TTS_AUDIO_FOCUS audioFocusAcquireTry: audio focus newHandle acquired; checking if no other thread acquired one...")
            }
            if (audioFocusControllerHandle == null) {
                if (VERBOSE_LOG_AUDIO_FOCUS) {
                    FooLog.v(TAG, "#TTS_AUDIO_FOCUS audioFocusAcquireTry: no other thread acquired audioFocusControllerHandle; audioFocusControllerHandle = newHandle")
                }
                audioFocusControllerHandle = newHandle
                null
            } else {
                newHandle
            }
        }?.let {
            if (VERBOSE_LOG_AUDIO_FOCUS) {
                FooLog.v(TAG, "#TTS_AUDIO_FOCUS audioFocusAcquireTry: another thread already acquired audioFocusControllerHandle; newHandle.release()")
            }
            it.release()
        }
        return true
    }

    private fun audioFocusHandleClearLocked(): FooAudioFocusController.FocusHandle? {
        val handle = audioFocusControllerHandle ?: return null
        audioFocusControllerHandle = null
        return handle
    }

    private var applicationContext: Context? = null
    private var tts: TextToSpeech? = null
    private var nextSequenceId: Long = 0

    val voices: Set<Voice>?
        get() {
            synchronized(syncLock) { return tts?.voices }
        }

    var voiceName: String? = null
        get() {
            synchronized(syncLock) {
                return field
            }
        }
        private set(value) {
            synchronized(syncLock) {
                field = value
            }
        }

    /**
     * @param value null to set default voice, or the name of a voice in [.getVoices]
     * @return true if changed, otherwise false
     */
    fun setVoiceName(value: String?): Boolean {
        var voiceName = value
        FooLog.v(TAG, "#TTS setVoiceName($voiceName)")
        if (voiceName.isNullOrEmpty()) {
            voiceName = null
        }
        val oldValue: String?
        val changed: Boolean
        synchronized(syncLock) {
            oldValue = this.voiceName
            if (tts == null) {
                this.voiceName = voiceName
                changed = oldValue != this.voiceName
            } else {
                var foundVoice = tts!!.defaultVoice
                if (voiceName != null) {
                    val voices = voices
                    if (voices != null) {
                        for (voice in voices) {
                            //FooLog.e(TAG, "#TTS setVoiceName: voice=${quote(voice.name)}"); // debug
                            if (voiceName.equals(voice.name, ignoreCase = true)) {
                                foundVoice = voice
                                break
                            }
                        }
                    }
                }
                this.voiceName = foundVoice.name
                changed = oldValue != this.voiceName
                tts!!.setVoice(foundVoice)
            }
        }
        return changed
    }

    /**
     * Speech rate. `1.0` is the normal speech rate,
     * lower values slow down the speech (`0.5` is half the normal speech rate),
     * greater values accelerate it (`2.0` is twice the normal speech rate).
     */
    var voiceSpeed: Float = DEFAULT_VOICE_SPEED
        set(value) {
            field = value
            tts?.setSpeechRate(value)
        }

    /**
     * Speech pitch. `1.0` is the normal pitch,
     * lower values lower the tone of the synthesized voice,
     * greater values increase it.
     */
    var voicePitch: Float = DEFAULT_VOICE_PITCH
        set(value) {
            field = value
            tts?.setPitch(value)
        }

    var audioAttributes: AudioAttributes = audioAttributesFactory()
        get() {
            synchronized(syncLock) { return field }
        }
        private set(value) {
            synchronized(syncLock) {
                field = value
                tts?.setAudioAttributes(field)
            }
        }

    var volumeRelativeToAudioStream: Float = DEFAULT_VOICE_VOLUME
        /**
         * @return 0 (silence) to 1 (maximum)
         */
        get() {
            synchronized(syncLock) { return field }
        }

        /**
         * @param volumeRelativeToAudioStream 0 (silence) to 1 (maximum)
         */
        set(volumeRelativeToAudioStream) {
            synchronized(syncLock) { field = volumeRelativeToAudioStream }
        }

    var isStarted: Boolean = false
        get() {
            synchronized(syncLock) { return field }
        }
        private set(value) {
            synchronized(syncLock) {
                field = value
            }
        }

    var isInitialized: Boolean = false
        get() {
            synchronized(syncLock) { return field }
        }
        private set(value) {
            synchronized(syncLock) {
                field = value
            }
        }

    init {
        FooLog.v(TAG, "#TTS +FooTextToSpeech()")
        audioFocusController = FooAudioFocusController.instance
        audioFocusControllerCallbacks =
            object : FooAudioFocusController.Callbacks() {
                override fun onFocusGained(
                    audioFocusController: FooAudioFocusController,
                    audioFocusRequest: AudioFocusRequest,
                ): Boolean = this@FooTextToSpeech.onAudioFocusGained()

                override fun onFocusLost(
                    audioFocusController: FooAudioFocusController,
                    audioFocusRequest: AudioFocusRequest,
                    focusChange: Int,
                ): Boolean = this@FooTextToSpeech.onAudioFocusLost(focusChange)
            }
        FooLog.v(TAG, "#TTS -FooTextToSpeech()")
    }

    /**
     * Registers callbacks that will be notified once the underlying TextToSpeech engine finishes initializing.
     */
    fun attach(callbacks: FooTextToSpeechCallbacks) {
        synchronized(syncLock) { listeners.attach(callbacks) }
    }

    /**
     * Unregisters previously attached callbacks.
     */
    fun detach(callbacks: FooTextToSpeechCallbacks) {
        synchronized(syncLock) { listeners.detach(callbacks) }
    }

    /**
     * Checks whether the TTS engine is busy speaking. Note that a speech item is considered complete once it's audio data has been sent to the audio mixer, or written to a file. There might be a finite lag between this point, and when the audio hardware completes playback.
     *
     * @return tts?.isSpeaking ?: false
     *
     * @see [android.speech.tts.TextToSpeech.isSpeaking]
     */
    val isSpeaking: Boolean = synchronized(syncLock) { tts?.isSpeaking ?: false }

    /**
     * Stops all playback, clears internal state, and shuts down the TTS engine.
     * Callers should invoke this when they no longer need FooTextToSpeech.
     */
    fun stop() {
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
        synchronized(syncLock) {
            clearLocked(interrupt = true, runAfters = runAfters, focusHandles = focusHandles)
            isStarted = false
            tts?.let {
                it.stop()
                it.shutdown()
                tts = null
            }
            isInitialized = false
        }
        callbackHandler.removeCallbacksAndMessages(null)
        if (runAfters.isNotEmpty() || focusHandles.isNotEmpty()) {
            executeCleanup(runAfters, focusHandles)
        }
    }

    /**
     * Prepares the singleton for use, creating the underlying [TextToSpeech] instance on first call.
     *
     * @return this FooTextToSpeech instance for chaining
     */
    @JvmOverloads
    fun start(
        context: Context,
        callbacks: FooTextToSpeechCallbacks? = null,
    ): FooTextToSpeech {
        FooLog.v(TAG, "#TTS +start(context=$context, callbacks=$callbacks)")
        synchronized(syncLock) {
            if (applicationContext == null) {
                applicationContext = context.applicationContext
            }
            if (callbacks != null) {
                attach(callbacks)
            }
            if (isStarted) {
                if (isInitialized) {
                    callbacks?.onTextToSpeechInitialized(TextToSpeech.SUCCESS)
                }
                FooLog.v(TAG, "#TTS start: already started; ignoring")
            } else {
                isStarted = true
                tts = TextToSpeech(applicationContext) { status -> onTextToSpeechInitialized(status) }
            }
        }
        FooLog.v(TAG, "#TTS -start(context=$context, callbacks=$callbacks)")
        return this
    }

    private fun onTextToSpeechInitialized(status: Int) {
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
        try {
            FooLog.v(TAG, "#TTS +onTextToSpeechInitialized(status=${statusToString(status)})")
            synchronized(syncLock) {
                if (!isStarted) {
                    return
                }
                val success = status == TextToSpeech.SUCCESS
                if (!success) {
                    FooLog.w(TAG, "#TTS onTextToSpeechInitialized: TextToSpeech failed to initialize: status == ${statusToString(status)}")
                } else {
                    tts?.let {
                        //it.language = Locale.getDefault()
                        it.setAudioAttributes(audioAttributes)
                        it.setOnUtteranceProgressListener(
                            object : UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) {
                                    this@FooTextToSpeech.onUtteranceStart(utteranceId)
                                }

                                override fun onDone(utteranceId: String?) {
                                    this@FooTextToSpeech.onUtteranceDone(utteranceId)
                                }

                                override fun onStop(
                                    utteranceId: String?,
                                    interrupted: Boolean,
                                ) {
                                    this@FooTextToSpeech.onUtteranceStop(utteranceId, interrupted)
                                }

                                @Deprecated(
                                    "Deprecated in Java",
                                    ReplaceWith(
                                        "onError(utteranceId, TextToSpeech.ERROR)",
                                        "android.speech.tts.TextToSpeech",
                                    ),
                                )
                                // Kotlin 2.2.20 bug falsely warns:
                                // "This declaration overrides a deprecated member but is not marked as deprecated itself. Add the '@Deprecated' annotation or suppress the diagnostic."
                                // https://youtrack.jetbrains.com/issue/KT-80399/ Fixed in Kotlin 2.3
                                // Available in: 2.3.0-Beta1, State: Fixed, Fix in builds: 2.3.0-dev-5366
                                @Suppress("OVERRIDE_DEPRECATION")
                                override fun onError(utteranceId: String?) {
                                    onError(utteranceId, TextToSpeech.ERROR)
                                }

                                override fun onError(
                                    utteranceId: String?,
                                    errorCode: Int,
                                ) {
                                    this@FooTextToSpeech.onUtteranceError(utteranceId, errorCode)
                                }
                            },
                        )
                    }

                    setVoiceName(voiceName)
                    voiceSpeed = voiceSpeed
                    voicePitch = voicePitch

                    isInitialized = true
                }
                for (callbacks in listeners.beginTraversing()) {
                    callbacks.onTextToSpeechInitialized(status)
                }
                listeners.endTraversing()

                if (!isInitialized) {
                    stop()
                } else {
                    startNextLocked().collectInto(runAfters, focusHandles)
                }
            } // syncLock
        } finally {
            FooLog.v(TAG, "#TTS -onTextToSpeechInitialized(status=${statusToString(status)})")
        }
        executeCleanup(runAfters, focusHandles)
    }

    private fun onUtteranceStart(utteranceId: String?) {
        if (VERBOSE_LOG_UTTERANCE_PROGRESS) {
            FooLog.v(TAG, "#TTS_UTTERANCE_PROGRESS +onUtteranceStart(utteranceId=${quote(utteranceId)})")
        }
        audioFocusAcquireTry(audioAttributes)
        if (VERBOSE_LOG_UTTERANCE_PROGRESS) {
            FooLog.v(TAG, "#TTS_UTTERANCE_PROGRESS -onUtteranceStart(utteranceId=${quote(utteranceId)})")
        }
    }

    private fun onUtteranceDone(utteranceId: String?) {
        handleUtteranceCompletion("onUtteranceDone", utteranceId, false, TextToSpeech.SUCCESS)
    }

    private fun onUtteranceError(
        utteranceId: String?,
        errorCode: Int,
    ) {
        handleUtteranceCompletion("onUtteranceError($errorCode)", utteranceId, false, errorCode)
    }

    private fun onUtteranceStop(
        utteranceId: String?,
        interrupted: Boolean,
    ) {
        handleUtteranceCompletion("onUtteranceStopped(interrupted=$interrupted)", utteranceId, !interrupted, TextToSpeech.STOPPED)
    }

    private fun onRunAfterSpeak() {
        if (VERBOSE_LOG_AUDIO_FOCUS) {
            FooLog.v(TAG, "#TTS_AUDIO_FOCUS +onRunAfterSpeak()")
        }
        synchronized(syncLock) {
            if (currentUtterance == null && utteranceQueue.isEmpty()) {
                if (VERBOSE_LOG_AUDIO_FOCUS) {
                    FooLog.v(TAG, "#TTS_AUDIO_FOCUS onRunAfterSpeak: queue empty; audioFocusStop()")
                }
                audioFocusHandleClearLocked()
            } else {
                if (VERBOSE_LOG_AUDIO_FOCUS) {
                    FooLog.v(TAG, "#TTS_AUDIO_FOCUS onRunAfterSpeak: work remaining; keeping audio focus")
                }
                null
            }
        }?.let {
            it.release()
            onAudioFocusStop()
        }
        if (VERBOSE_LOG_AUDIO_FOCUS) {
            FooLog.v(TAG, "#TTS_AUDIO_FOCUS -onRunAfterSpeak()")
        }
    }

    private fun onAudioFocusGained(): Boolean {
        if (VERBOSE_LOG_AUDIO_FOCUS) {
            FooLog.e(TAG, "#TTS_AUDIO_FOCUS onAudioFocusGained()")
        }
        return false
    }

    private fun onAudioFocusLost(focusChange: Int): Boolean {
        if (VERBOSE_LOG_AUDIO_FOCUS) {
            FooLog.e(TAG, "#TTS_AUDIO_FOCUS onAudioFocusLost(focusChange=${FooAudioUtils.audioFocusGainLossToString(focusChange)})")
        }
        return false
    }

    private fun onAudioFocusStop() {
        if (VERBOSE_LOG_AUDIO_FOCUS) {
            FooLog.e(TAG, "#TTS_AUDIO_FOCUS onAudioFocusStop()")
        }
    }

    private fun scheduleSequenceStart(
        sequenceId: String,
        runAfters: MutableList<Runnable>,
    ) {
        val state = sequenceStates[sequenceId] ?: return
        if (state.started) {
            return
        }
        state.started = true
        state.callbacks?.let { callbacks ->
            runAfters.add(Runnable { callbacks.onSequenceStart(sequenceId) })
        }
    }

    private fun scheduleSequenceComplete(
        sequenceId: String,
        neverStarted: Boolean,
        errorCode: Int,
        runAfters: MutableList<Runnable>,
    ) {
        val state = sequenceStates.remove(sequenceId) ?: return
        runAfters.add(runAfterSpeak)
        state.callbacks?.let { callbacks ->
            runAfters.add(Runnable { callbacks.onSequenceComplete(sequenceId, neverStarted, errorCode) })
        }
    }

    private fun computeNeverStarted(
        sequenceId: String,
        interruptedSequenceId: String?,
    ): Boolean = sequenceStates[sequenceId]?.started != true && sequenceId != interruptedSequenceId

    private fun computeErrorCode(
        sequenceId: String,
        interruptedSequenceId: String?,
    ): Int = if (computeNeverStarted(sequenceId, interruptedSequenceId)) TextToSpeech.SUCCESS else TextToSpeech.STOPPED

    /**
     * @param errorCode if null then completed successfully, if 0 then interrupted, else [TextToSpeech]`.ERROR_*`.
     */
    private fun handleUtteranceCompletion(
        caller: String,
        utteranceId: String?,
        neverStarted: Boolean,
        errorCode: Int,
    ) {
        if (VERBOSE_LOG_UTTERANCE_PROGRESS) {
            FooLog.v(TAG, "#TTS_UTTERANCE_PROGRESS +handleUtteranceCompletion(caller=${quote(caller)}, utteranceId=${quote(utteranceId)})")
        }
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
        synchronized(syncLock) {
            val current = currentUtterance
            if (utteranceId != null) {
                if (current != null) {
                    if (current.utteranceId == utteranceId) {
                        val sequenceId = current.sequenceId
                        val moreInQueue = utteranceQueue.any { it.sequenceId == sequenceId }
                        currentUtterance = null
                        if (!moreInQueue) {
                            scheduleSequenceComplete(sequenceId, neverStarted, errorCode, runAfters)
                        }
                    } else if (VERBOSE_LOG_UTTERANCE_PROGRESS) {
                        FooLog.w(TAG, "#TTS_UTTERANCE_PROGRESS handleUtteranceCompletion: UNEXPECTED currentUtteranceId=${quote(current.utteranceId)} does not match callback utteranceId=${quote(utteranceId)}")
                    }
                }
            } else {
                FooLog.w(TAG, "#TTS_UTTERANCE_PROGRESS handleUtteranceCompletion: UNEXPECTED utteranceId == null")
                currentUtterance?.let { scheduleSequenceComplete(it.sequenceId, neverStarted, errorCode, runAfters) }
                currentUtterance = null
            }
            startNextLocked().collectInto(runAfters, focusHandles)
        }
        executeCleanup(runAfters, focusHandles)
        if (VERBOSE_LOG_UTTERANCE_PROGRESS) {
            FooLog.v(TAG, "#TTS_UTTERANCE_PROGRESS -handleUtteranceCompletion(caller=${quote(caller)}, utteranceId=${quote(utteranceId)})")
        }
    }

    //
    //region cleanup/clear/cancel
    //

    /**
     * If [sequenceId] is not currently playing then removes it from the queue.<br>
     * If [sequenceId] is currently playing the stops it.<br>
     * Continues with any next queued sequence.
     *
     * @return true if work was canceled (either currently playing or queued)
     */
    fun sequenceStop(sequenceId: String): Boolean {
        if (VERBOSE_LOG_SEQUENCE) {
            FooLog.d(TAG, "#TTS_SEQUENCE +stopSequence(sequenceId=${quote(sequenceId)})")
        }
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
        val canceled =
            synchronized(syncLock) {
                cancelSequenceLocked(sequenceId, runAfters, focusHandles, startNext = true)
            }
        if (canceled) {
            executeCleanup(runAfters, focusHandles)
        }
        if (VERBOSE_LOG_SEQUENCE) {
            FooLog.d(TAG, "#TTS_SEQUENCE -stopSequence(sequenceId=${quote(sequenceId)}) -> canceled=$canceled")
        }
        return canceled
    }

    /**
     * Clears all queued sequences, optionally interrupting any current one.
     *
     * @param interrupt true to interrupt in-flight sequence, false to allow it to finish before any newly enqueued work begins.
     * @return true if any work was canceled (either currently playing or queued)
     */
    fun clear(interrupt: Boolean = true): Boolean {
        FooLog.d(TAG, "#TTS +clear(interrupt=$interrupt)")
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
        synchronized(syncLock) {
            clearLocked(interrupt, runAfters, focusHandles)
        }
        val canceled = runAfters.isNotEmpty() || focusHandles.isNotEmpty()
        if (canceled) {
            executeCleanup(runAfters, focusHandles)
        }
        FooLog.d(TAG, "#TTS -clear(interrupt=$interrupt) -> canceled=$canceled")
        return canceled
    }

    private data class CleanupActions(
        val runAfters: List<Runnable> = emptyList(),
        val focusHandles: List<FooAudioFocusController.FocusHandle> = emptyList(),
    )

    private fun CleanupActions?.collectInto(
        runAftersOut: MutableList<Runnable>,
        focusHandlesOut: MutableList<FooAudioFocusController.FocusHandle>,
    ) {
        if (this == null) {
            return
        }
        runAftersOut.addAll(runAfters)
        focusHandlesOut.addAll(focusHandles)
    }

    private fun executeCleanup(
        runAfters: Collection<Runnable>,
        focusHandles: Collection<FooAudioFocusController.FocusHandle>,
    ) {
        runAfters.forEach { runnable ->
            callbackHandler.post(
                Runnable {
                    try {
                        runnable.run()
                    } catch (throwable: Throwable) {
                        FooLog.e(TAG, "#TTS executeCleanup: runAfter threw", throwable)
                    }
                },
            )
        }
        focusHandles.forEach { handle ->
            handle.release()
            onAudioFocusStop()
        }
    }

    private fun removeQueuedBySequenceLocked(sequenceId: String): Int {
        var removedUtteranceCount = 0
        val iterator = utteranceQueue.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            if (queued.sequenceId == sequenceId) {
                // Remove from the queue first so we don't modify while iterating.
                iterator.remove()

                removedUtteranceCount++
            }
        }
        if (VERBOSE_LOG_SEQUENCE && removedUtteranceCount > 0) {
            FooLog.d(TAG, "#TTS_SEQUENCE removeQueued(sequenceId=${quote(sequenceId)}, …): utteranceQueue.size=${utteranceQueue.size}, removedUtteranceCount=$removedUtteranceCount")
        }
        return removedUtteranceCount
    }

    /**
     * Very similar to [clearLocked], but optimized for a single sequence
     */
    private fun cancelSequenceLocked(
        sequenceId: String,
        runAfters: MutableList<Runnable>,
        focusHandles: MutableList<FooAudioFocusController.FocusHandle>,
        startNext: Boolean,
    ): Boolean {
        var canceled = false
        var stopSuccess = true

        var interruptedSequenceId: String? = null

        // Remove the currentUtterance if it is a match
        val current = currentUtterance
        if (current != null && current.sequenceId == sequenceId) {
            interruptedSequenceId = current.sequenceId
            if (isInitialized) {
                stopSuccess = tts?.stop() == TextToSpeech.SUCCESS
            }
            currentUtterance = null
            canceled = true
        }

        // Remove any queued utterances if they are a match
        val removedUtteranceCount = removeQueuedBySequenceLocked(sequenceId)
        if (removedUtteranceCount > 0) {
            canceled = true
        }

        if (canceled) {
            if (VERBOSE_LOG_SEQUENCE) {
                val assertRemainingShouldBeZero = utteranceQueue.count { it.sequenceId == sequenceId }
                FooLog.d(TAG, "#TTS_SEQUENCE cancelSequenceLocked(sequenceId=${quote(sequenceId)}, …, startNext=$startNext): interruptedSequenceId=${quote(interruptedSequenceId)}, removedUtteranceCount=$removedUtteranceCount, assertRemainingShouldBeZero=$assertRemainingShouldBeZero")
            }
            if (startNext) {
                startNextLocked().collectInto(runAfters, focusHandles)
            } else if (currentUtterance == null && utteranceQueue.isEmpty()) {
                audioFocusHandleClearLocked()?.let(focusHandles::add)
            }
        } else {
            if (VERBOSE_LOG_SEQUENCE) {
                FooLog.w(TAG, "#TTS_SEQUENCE cancelSequenceLocked(sequenceId=${quote(sequenceId)}, …, startNext=$startNext): no matching sequence")
            }
        }

        if (canceled) {
            scheduleSequenceComplete(
                sequenceId = sequenceId,
                neverStarted = computeNeverStarted(sequenceId, interruptedSequenceId),
                errorCode = computeErrorCode(sequenceId, interruptedSequenceId),
                runAfters = runAfters,
            )
        }

        return canceled && stopSuccess
    }

    /**
     * Very similar to [cancelSequenceLocked], but optimized for all sequences
     */
    private fun clearLocked(
        interrupt: Boolean,
        runAfters: MutableList<Runnable>,
        focusHandles: MutableList<FooAudioFocusController.FocusHandle>,
    ) {
        if (VERBOSE_LOG_SEQUENCE) {
            FooLog.d(TAG, "#TTS_SEQUENCE +clearLocked(interrupt=$interrupt, …): utteranceQueue.size=${utteranceQueue.size}, currentUtterance?.sequenceId=${quote(currentUtterance?.sequenceId)}")
        }

        var interruptedSequenceId: String? = null

        val completedSequenceIds = mutableSetOf<String>()
        if (interrupt) {
            currentUtterance?.let { current ->
                interruptedSequenceId = current.sequenceId
                if (isInitialized) {
                    tts?.stop()
                }
                completedSequenceIds += current.sequenceId
                currentUtterance = null
            }
        }

        var removedUtteranceCount = 0
        val activeSequenceId = if (interrupt) null else currentUtterance?.sequenceId
        if (activeSequenceId == null) {
            while (utteranceQueue.isNotEmpty()) {
                val removed = utteranceQueue.removeFirst()
                completedSequenceIds += removed.sequenceId
                removedUtteranceCount++
            }
        } else {
            val iterator = utteranceQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.sequenceId == activeSequenceId) {
                    continue
                }
                iterator.remove()
                completedSequenceIds += queued.sequenceId
                removedUtteranceCount++
            }
        }

        completedSequenceIds.forEach { sequenceId ->
            scheduleSequenceComplete(
                sequenceId = sequenceId,
                neverStarted = computeNeverStarted(sequenceId, interruptedSequenceId),
                errorCode = computeErrorCode(sequenceId, interruptedSequenceId),
                runAfters = runAfters,
            )
        }

        val releaseFocus = interrupt || currentUtterance == null
        if (releaseFocus) {
            audioFocusHandleClearLocked()?.let(focusHandles::add)
        }

        if (VERBOSE_LOG_SEQUENCE) {
            FooLog.d(TAG, "#TTS_SEQUENCE -clearLocked(interrupt=$interrupt, …): utteranceQueue.size=${utteranceQueue.size}, interruptedSequenceId=${quote(interruptedSequenceId)} removedUtteranceCount=$removedUtteranceCount, releaseFocus=$releaseFocus")
        }
    }

    //
    //endregion cleanup/clear/cancel
    //

    //
    //region enqueue internal
    //

    private fun enqueueUtterancesLocked(
        utterances: List<Utterance>,
        prepend: Boolean,
    ) {
        if (prepend) {
            for (utterance in utterances.asReversed()) {
                utteranceQueue.addFirst(utterance)
            }
        } else {
            utterances.forEach(utteranceQueue::addLast)
        }
    }

    /**
     * @return sequenceId if enqueued, otherwise null
     */
    private fun sequenceEnqueue(
        caller: String,
        builder: FooTextToSpeechBuilder,
        placement: QueuePlacement,
        callbacks: SequenceCallbacks?,
    ): String? {
        var sequenceId: String? = null
        try {
            if (VERBOSE_LOG_SEQUENCE) {
                FooLog.d(TAG, "#TTS_SEQUENCE +sequenceEnqueue(caller=${quote(caller)}, builder, placement=$placement, callbacks=$callbacks)")
            }
            val parts = builder.build(ensureNonEmptyEndsWithSilence = true)
            //FooLog.e(TAG, "#TTS enqueueInternal: parts(${parts.size})=$parts")
            if (parts.isEmpty()) {
                FooLog.w(TAG, "#TTS sequenceEnqueue: builder is empty; not enqueueing")
                runAfterSpeak.run()
                return null
            }
            val runAfters = mutableListOf<Runnable>()
            val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()
            synchronized(syncLock) {
                if (tts == null) {
                    throw IllegalStateException("start(context) must be called first")
                }
                if (VERBOSE_LOG_SEQUENCE) {
                    FooLog.d(TAG, "#TTS_SEQUENCE sequenceEnqueue: BEFORE utteranceQueue.size=${utteranceQueue.size}, currentUtterance?.sequenceId=${quote(currentUtterance?.sequenceId)}")
                }
                when (placement) {
                    QueuePlacement.CLEAR -> {
                        if (VERBOSE_LOG_SEQUENCE) {
                            FooLog.d(TAG, "#TTS_SEQUENCE sequenceEnqueue: placement=CLEAR -> clearing all sequences before enqueue")
                        }
                        clearLocked(interrupt = true, runAfters = runAfters, focusHandles = focusHandles)
                    }
                    QueuePlacement.IMMEDIATE -> {
                        currentUtterance?.sequenceId?.let { currentUtteranceSequenceId ->
                            if (VERBOSE_LOG_SEQUENCE) {
                                FooLog.d(TAG, "#TTS_SEQUENCE sequenceEnqueue: placement=IMMEDIATE -> interrupting currentUtterance?.sequenceId=${quote(currentUtteranceSequenceId)}")
                            }
                            cancelSequenceLocked(currentUtteranceSequenceId, runAfters, focusHandles, startNext = false)
                        }
                    }
                    QueuePlacement.NEXT,
                    QueuePlacement.APPEND,
                    -> {
                        // no-op
                    }
                }
                sequenceId = "seq_${nextSequenceId++}"
                sequenceStates[sequenceId] = SequenceState(callbacks)
                if (VERBOSE_LOG_SEQUENCE) {
                    FooLog.d(TAG, "#TTS_SEQUENCE sequenceEnqueue: START sequenceId=${quote(sequenceId)}, parts.size=(${parts.size})")
                }
                var nextUtteranceId = 0L
                val utterances =
                    parts.mapIndexed { index, part ->
                        Utterance.create(sequenceId, nextUtteranceId++, part)
                    }
                val prepend = placement != QueuePlacement.APPEND
                enqueueUtterancesLocked(utterances, prepend)
                if (VERBOSE_LOG_SEQUENCE) {
                    FooLog.d(TAG, "#TTS_SEQUENCE sequenceEnqueue: AFTER utteranceQueue.size=${utteranceQueue.size}, currentUtterance?.sequenceId=${quote(currentUtterance?.sequenceId)}")
                }
                startNextLocked().collectInto(runAfters, focusHandles)
            }
            executeCleanup(runAfters, focusHandles)
            return sequenceId
        } finally {
            if (VERBOSE_LOG_SEQUENCE) {
                FooLog.d(TAG, "#TTS_SEQUENCE -sequenceEnqueue(caller=${quote(caller)}, builder, placement=$placement, callbacks=$callbacks) -> sequenceId=${quote(sequenceId)}")
            }
        }
    }

    //
    //endregion enqueue internal
    //

    var dedupe = true
    private var lastText: String? = null
    private var lastSilence: Int? = null
    private var lastEarcon: String? = null

    private fun startNextLocked(): CleanupActions? {
        if (!isInitialized || currentUtterance != null) {
            return null
        }
        val runAfters = mutableListOf<Runnable>()
        val focusHandles = mutableListOf<FooAudioFocusController.FocusHandle>()

        val params = bundleFactory()
        /** NOTE: Setting KEY_PARAM_UTTERANCE_ID is deprecated by calling [android.speech.tts.TextToSpeech.speak] with utteranceId */
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeRelativeToAudioStream)
        // TODO: KEY_PARAM_STREAM and KEY_PARAM_SESSION_ID
        // TODO: KEY_PARAM_PAN?

        val queueMode = TextToSpeech.QUEUE_FLUSH

        while (utteranceQueue.isNotEmpty()) {
            val next = utteranceQueue.removeFirst()
            if (dedupe) {
                when (next) {
                    is Utterance.Text -> {
                        if (next.text == lastText) {
                            FooLog.w(TAG, "#TTS_UTTERANCE SPEAK startNextLocked: duplicate text; ignoring")
                            continue
                        }
                        lastText = next.text
                    }
                    is Utterance.Silence -> {
                        if (next.durationMillis == lastSilence) {
                            FooLog.w(TAG, "#TTS_UTTERANCE SILENCE startNextLocked: duplicate silence; ignoring")
                            continue
                        }
                        lastSilence = next.durationMillis
                    }
                    is Utterance.Earcon -> {
                        if (next.earcon == lastEarcon) {
                            FooLog.w(TAG, "#TTS_UTTERANCE EARCON startNextLocked: duplicate earcon; ignoring")
                            continue
                        }
                        lastEarcon = next.earcon
                    }
                }
            }
            if (next.requestAudioFocus) {
                if (!audioFocusAcquireTry(audioAttributes)) {
                    /**
                     * Being denied an audio focus request poses an interesting dilemma.
                     * Something else has exclusive audio focus.
                     * The right thing to do is to either queue this utterance or drop it and notify callbacks of completion...
                     * ```
                     * FooLog.w(TAG, "#TTS speak: audio focus denied; skipping utteranceId=${quote(utteranceId)}")
                     * runAfter?.run()
                     * return false
                     * ```
                     * BUT FOR NOW we will do neither and INTENTIONALLY CONTINUE to speak even if audio focus is denied!
                     * If this causes any usability problem (ex: TTS speaking while we are in a call) then it should be obvious and we can address it then.
                     * If it is never obvious then there is nothing to fix here and we are already doing the right thing.
                     *
                     * TODO: Make Aggressive/Lenient audio focus requirement a settable property
                     */
                    FooLog.w(TAG, "#TTS_AUDIO_FOCUS startNextLocked: audio focus denied; proceeding anyway; TODO: handle denied focus more gracefully")
                }
            }
            val result =
                when (next) {
                    is Utterance.Text -> {
                        if (VERBOSE_LOG_UTTERANCE) {
                            FooLog.d(TAG, "#TTS_UTTERANCE SPEAK startNextLocked: tts.speak(utteranceId=${quote(next.utteranceId)}, queueMode=${queueModeToString(queueMode)}, params=${FooPlatformUtils.toString(params)}, text=${quote(next.text)})")
                        }
                        tts!!.speak(next.text, queueMode, params, next.utteranceId)
                    }
                    is Utterance.Silence -> {
                        if (VERBOSE_LOG_UTTERANCE) {
                            FooLog.i(TAG, "#TTS_UTTERANCE SILENCE startNextLocked: tts.playSilentUtterance(utteranceId=${quote(next.utteranceId)}, queueMode=${queueModeToString(queueMode)}, durationMillis=${next.durationMillis})")
                        }
                        tts!!.playSilentUtterance(next.durationMillis.toLong(), queueMode, next.utteranceId)
                    }
                    is Utterance.Earcon -> {
                        if (VERBOSE_LOG_UTTERANCE) {
                            FooLog.i(TAG, "#TTS_UTTERANCE EARCON startNextLocked: tts.playEarcon(utteranceId=${quote(next.utteranceId)}, queueMode=${queueModeToString(queueMode)}, params=${FooPlatformUtils.toString(params)}, earcon=${quote(next.earcon)})")
                        }
                        tts!!.playEarcon(next.earcon, queueMode, params, next.utteranceId)
                    }
                }
            if (result == TextToSpeech.SUCCESS) {
                currentUtterance = next
                scheduleSequenceStart(next.sequenceId, runAfters)
                if (VERBOSE_LOG_SEQUENCE) {
                    val remainingForSequence = utteranceQueue.count { it.sequenceId == next.sequenceId }
                    FooLog.d(TAG, "#TTS_SEQUENCE startNextLocked: sequenceId=${quote(next.sequenceId)} utteranceId=${quote(next.utteranceId)} remainingInSequence=$remainingForSequence utteranceQueue.size=${utteranceQueue.size}")
                }
                return if (runAfters.isEmpty() && focusHandles.isEmpty()) null else CleanupActions(runAfters, focusHandles)
            } else {
                if (VERBOSE_LOG_UTTERANCE) {
                    FooLog.w(TAG, "#TTS_UTTERANCE startNextLocked: failed to play utteranceId=${quote(next.utteranceId)}; result=${statusToString(result)}")
                }
                currentUtterance = null
                val sequenceHasMore = utteranceQueue.any { it.sequenceId == next.sequenceId }
            if (!sequenceHasMore) {
                scheduleSequenceComplete(
                    sequenceId = next.sequenceId,
                    neverStarted = computeNeverStarted(next.sequenceId, currentUtterance?.sequenceId),
                    errorCode = result,
                    runAfters = runAfters,
                )
            }
                audioFocusHandleClearLocked()?.let(focusHandles::add)
            }
        }
        audioFocusHandleClearLocked()?.let(focusHandles::add)
        return if (runAfters.isEmpty() && focusHandles.isEmpty()) null else CleanupActions(runAfters, focusHandles)
    }

    /**
     * Enqueues [builder] respecting [placement] (defaults to [QueuePlacement.APPEND])
     * and notifying [callbacks] when the sequence starts and completes.
     *
     * @return a `sequenceId` that can be canceled via [sequenceStop], or null if builder is empty.
     */
    fun sequenceEnqueue(
        builder: FooTextToSpeechBuilder,
        placement: QueuePlacement = QueuePlacement.APPEND,
        callbacks: SequenceCallbacks? = null,
    ) = sequenceEnqueue("sequenceEnqueue", builder, placement, callbacks)

    /**
     * [sequenceEnqueue]s [text] respecting [placement] (defaults to [QueuePlacement.APPEND])
     * and notifying [callbacks] when the sequence starts and completes.
     *
     * @return a `sequenceId` that can be canceled via [sequenceStop], or null if text is empty.
     */
    @JvmOverloads
    fun speak(
        text: String,
        placement: QueuePlacement = QueuePlacement.APPEND,
        callbacks: SequenceCallbacks? = null,
    ): String? {
        if (VERBOSE_LOG_SPEAK) {
            FooLog.d(TAG, "#TTS_SPEAK speak(text=${quote(text)}, placement=$placement, callbacks=$callbacks)")
        }
        return sequenceEnqueue("speak", FooTextToSpeechBuilder(text), placement, callbacks)
    }

    /**
     * [sequenceEnqueue]s silence of [durationMillis] respecting [placement] (defaults to [QueuePlacement.APPEND])
     * and notifying [callbacks] when the sequence starts and completes.
     *
     * Non-audio silence placement does not make much sense, but it is included for consistency.
     *
     * @return a `sequenceId` that can be canceled via [sequenceStop], or null if durationMillis is <= 0.
     */
    @JvmOverloads
    fun silence(
        durationMillis: Int,
        placement: QueuePlacement = QueuePlacement.APPEND,
        callbacks: SequenceCallbacks? = null,
    ): String? {
        if (VERBOSE_LOG_SPEAK) {
            FooLog.d(TAG, "#TTS_SILENCE silence(durationMillis=$durationMillis, placement=$placement, callbacks=$callbacks)")
        }
        return sequenceEnqueue("silence", FooTextToSpeechBuilder(durationMillis), placement, callbacks)
    }

    /**
     * [sequenceEnqueue]s [earcon] (aka: "audio icon") respecting [placement] (defaults to [QueuePlacement.APPEND])
     * and notifying [callbacks] when the sequence starts and completes.
     *
     * @return a `sequenceId` that can be canceled via [sequenceStop], or null if earcon is empty.
     */
    @JvmOverloads
    fun earcon(
        earcon: String,
        placement: QueuePlacement = QueuePlacement.APPEND,
        callbacks: SequenceCallbacks? = null,
    ): String? {
        if (VERBOSE_LOG_EARCON) {
            FooLog.d(TAG, "#TTS_EARCON earcon(earcon=${quote(earcon)}, placement=$placement, callbacks=$callbacks)")
        }
        return sequenceEnqueue("earcon", FooTextToSpeechBuilder().appendEarcon(earcon), placement, callbacks)
    }

    /**
     * @see [android.speech.tts.TextToSpeech.addEarcon]
     */
    fun addEarcon(
        earcon: String,
        packageName: String,
        resourceId: Int,
    ) {
        synchronized(syncLock) {
            if (tts == null) {
                throw IllegalStateException("start(context) must be called first")
            }
            tts!!.addEarcon(earcon, packageName, resourceId)
        }
    }

    /**
     * @see [android.speech.tts.TextToSpeech.addEarcon]
     */
    fun addEarcon(
        earcon: String,
        file: File,
    ) {
        synchronized(syncLock) {
            if (tts == null) {
                throw IllegalStateException("start(context) must be called first")
            }
            tts!!.addEarcon(earcon, file)
        }
    }

    /**
     * @see [android.speech.tts.TextToSpeech.addEarcon]
     */
    fun addEarcon(
        earcon: String,
        uri: Uri,
    ) {
        synchronized(syncLock) {
            if (tts == null) {
                throw IllegalStateException("start(context) must be called first")
            }
            tts!!.addEarcon(earcon, uri)
        }
    }
}
