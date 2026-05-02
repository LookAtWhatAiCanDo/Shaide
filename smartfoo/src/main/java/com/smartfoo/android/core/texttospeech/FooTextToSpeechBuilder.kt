package com.smartfoo.android.core.texttospeech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.smartfoo.android.core.FooString
import com.smartfoo.android.core.collections.FooCollections
import com.smartfoo.android.core.logging.FooLog
import java.util.LinkedList

@Suppress("unused")
class FooTextToSpeechBuilder {
    companion object {
        private val TAG = FooLog.TAG(FooTextToSpeechBuilder::class)

        enum class SilenceMillis(val value: Int) {
            Word(300),
            Sentence(500),
            Paragraph(750),
        }

        /**
         * 4000 in API36
         *
         * @see [TextToSpeech.getMaxSpeechInputLength]
         */
        val MAX_SPEECH_INPUT_LENGTH = TextToSpeech.getMaxSpeechInputLength()
    }

    abstract class FooTextToSpeechPart

    class FooTextToSpeechPartSpeech(
        text: String,
    ) : FooTextToSpeechPart() {
        companion object {
            private val TAG = FooLog.TAG(FooTextToSpeechPartSpeech::class)
        }

        val text =
            if (text.length > MAX_SPEECH_INPUT_LENGTH) {
                FooLog.w(TAG, "FooTextToSpeechPartSpeech: text > $MAX_SPEECH_INPUT_LENGTH; trimming text to $MAX_SPEECH_INPUT_LENGTH characters")
                text.substring(0, MAX_SPEECH_INPUT_LENGTH)
            } else {
                text
            }
                // Remove any unspeakable/unprintable characters
                //noinspection TrimLambda
                .trim { it <= ' ' }

        override fun toString() = "text=${FooString.quote(text)}"

        override fun equals(other: Any?) = other is FooTextToSpeechPartSpeech && FooString.equals(text, other.text)

        override fun hashCode() = text.hashCode()
    }

    class FooTextToSpeechPartSilence(
        val durationMillis: Int,
    ) : FooTextToSpeechPart() {
        override fun toString() = "durationMillis=$durationMillis"

        override fun equals(other: Any?) = other is FooTextToSpeechPartSilence && durationMillis == other.durationMillis

        override fun hashCode() = durationMillis.hashCode()
    }

    class FooTextToSpeechPartEarcon(
        val earcon: String,
    ) : FooTextToSpeechPart() {
        override fun toString() = "earcon=$earcon"

        override fun equals(other: Any?) = other is FooTextToSpeechPartEarcon && FooString.equals(earcon, other.earcon)

        override fun hashCode() = earcon.hashCode()
    }

    private var context: Context? = null
    private val parts = mutableListOf<FooTextToSpeechPart>()

    constructor()

    constructor(context: Context) {
        this.context = context
    }

    constructor(text: String) {
        appendSpeech(text)
    }

    constructor(silenceMillis: Int) {
        appendSilence(silenceMillis)
    }

    constructor(context: Context, text: String) {
        this.context = context
        appendSpeech(text)
    }

    constructor(context: Context, textResId: Int, vararg formatArgs: Any?) {
        this.context = context
        appendSpeech(context, textResId, *formatArgs)
    }

    constructor(builder: FooTextToSpeechBuilder) {
        append(builder)
    }

    constructor(part: FooTextToSpeechPart) {
        append(part)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        val iterator: Iterator<FooTextToSpeechPart> = parts.iterator()
        while (iterator.hasNext()) {
            val part = iterator.next()
            sb.append(part)
            if (iterator.hasNext()) {
                sb.append(", ")
            }
        }
        sb.append(']')
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean = other is FooTextToSpeechBuilder && FooCollections.identical(parts, other.parts)

    override fun hashCode(): Int = FooCollections.hashCode(parts)

    val numberOfParts: Int
        get() = parts.size

    val isEmpty: Boolean
        get() = numberOfParts == 0

    val isNotEmpty: Boolean
        get() = !isEmpty

    fun appendSpeech(
        context: Context,
        textResId: Int,
        vararg formatArgs: Any?,
    ): FooTextToSpeechBuilder {
        this.context = context
        return appendSpeech(textResId, formatArgs)
    }

    fun appendSpeech(
        textResId: Int,
        vararg formatArgs: Any?,
    ): FooTextToSpeechBuilder {
        val context =
            this.context
                ?: throw IllegalStateException("Must first call FooTextToSpeechBuilder(context, ...) or appendSpeech(context, ...)")
        return appendSpeech(context.getString(textResId, *formatArgs))
    }

    fun appendSpeech(text: CharSequence) = appendSpeech(text.toString())

    fun appendSpeech(text: String) = append(FooTextToSpeechPartSpeech(text))

    fun appendSilenceWordBreak() = appendSilence(SilenceMillis.Word.value)

    fun appendSilenceSentenceBreak() = appendSilence(SilenceMillis.Sentence.value)

    fun appendSilenceParagraphBreak() = appendSilence(SilenceMillis.Paragraph.value)

    fun appendSilence(silenceMillis: Int): FooTextToSpeechBuilder {
        if (silenceMillis <= 0) {
            FooLog.w(TAG, "appendSilence: silenceMillis <= 0; ignoring")
        } else {
            append(FooTextToSpeechPartSilence(silenceMillis))
        }
        return this
    }

    fun appendEarcon(earcon: String): FooTextToSpeechBuilder {
        if (earcon.isBlank()) {
            FooLog.w(TAG, "appendEarcon: earcon.isBlank(); ignoring")
        } else {
            append(FooTextToSpeechPartEarcon(earcon))
        }
        return this
    }

    var dedupe = true

    fun append(part: FooTextToSpeechPart): FooTextToSpeechBuilder {
        when (part) {
            is FooTextToSpeechPartSpeech ->
                if (part.text.isNotBlank()) {
                    var add = true
                    if (dedupe and parts.isNotEmpty()) {
                        val last = parts.last()
                        if (last is FooTextToSpeechPartSpeech) {
                            add = part.text != last.text
                        }
                    }
                    if (add) {
                        parts.add(part)
                    } else {
                        FooLog.w(TAG, "append: duplicate text; ignoring")
                    }
                } else {
                    FooLog.w(TAG, "append: part.text.isBlank(); ignoring")
                }
            is FooTextToSpeechPartSilence ->
                if (part.durationMillis > 0) {
                    var add = true
                    if (dedupe and parts.isNotEmpty()) {
                        val last = parts.last()
                        if (last is FooTextToSpeechPartSilence) {
                            add = part.durationMillis != last.durationMillis
                        }
                    }
                    if (add) {
                        parts.add(part)
                    } else {
                        FooLog.w(TAG, "append: duplicate silence; ignoring")
                    }
                } else {
                    FooLog.w(TAG, "append: part.durationMillis <= 0; ignoring")
                }
            is FooTextToSpeechPartEarcon ->
                if (part.earcon.isNotBlank()) {
                    var add = true
                    if (dedupe and parts.isNotEmpty()) {
                        val last = parts.last()
                        if (last is FooTextToSpeechPartEarcon) {
                            add = part.earcon != last.earcon
                        }
                    }
                    if (add) {
                        parts.add(part)
                    } else {
                        FooLog.w(TAG, "append: duplicate earcon; ignoring")
                    }
                } else {
                    FooLog.w(TAG, "append: part.earcon.isBlank(); ignoring")
                }
        }
        return this
    }

    fun append(builder: FooTextToSpeechBuilder): FooTextToSpeechBuilder {
        context = builder.context
        for (part in builder.parts) {
            append(part)
        }
        return this
    }

    /**
     * @param ensureNonEmptyEndsWithSilence if true and [isNotEmpty] is false and the last part is NOT a [FooTextToSpeechPartSilence] then call [appendSilenceSentenceBreak]
     * @return a copy of the parts
     */
    fun build(ensureNonEmptyEndsWithSilence: Boolean = false): List<FooTextToSpeechPart> {
        if (ensureNonEmptyEndsWithSilence && isNotEmpty && parts.last() !is FooTextToSpeechPartSilence) {
            appendSilenceSentenceBreak()
        }
        val parts = LinkedList(parts)
        this.parts.clear()
        return parts
    }
}
