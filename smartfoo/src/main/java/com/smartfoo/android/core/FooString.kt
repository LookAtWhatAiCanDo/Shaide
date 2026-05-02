package com.smartfoo.android.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import com.smartfoo.android.core.FooString.quote
import com.smartfoo.android.core.FooString.repr
import com.smartfoo.android.core.platform.FooPlatformUtils
import java.io.UnsupportedEncodingException
import java.util.Vector
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

@Suppress("unused")
object FooString {
    @JvmField
    val LINEFEED: String? = System.lineSeparator()

    const val CHARSET_NAME_UTF8 = "UTF-8"

    @JvmField
    val CHARSET_UTF8 = charset(CHARSET_NAME_UTF8)

    @JvmField
    val EMPTY_BYTES = byteArrayOf(0)

    @JvmStatic
    fun getBytes(value: String): ByteArray {
        try {
            return value.toByteArray(CHARSET_UTF8)
        } catch (e: UnsupportedEncodingException) {
            throw IllegalStateException("UnsupportedEncodingException: Should never happen as long as CHARSET_UTF8 is valid")
        }
    }

    @JvmStatic
    fun getString(
        bytes: ByteArray?,
        offset: Int,
        length: Int,
    ): String =
        try {
            String(bytes!!, offset, length, CHARSET_UTF8)
        } catch (e: UnsupportedEncodingException) {
            throw IllegalStateException("UnsupportedEncodingException: Should never happen as long as CHARSET_UTF8 is valid")
        }

    /**
     * Tests if a String value is null or empty.
     *
     * @param value the String value to test
     * @return true if the String is null, zero length, or ""
     */
    @JvmStatic
    fun isNullOrEmpty(value: String?): Boolean = value.isNullOrEmpty()

    @JvmStatic
    fun isNullOrEmpty(value: CharSequence?): Boolean = value.isNullOrEmpty()

    @JvmStatic
    fun toString(value: Any?): String? = value?.toString()

    /**
     * Creates a String from a null-terminated array of default String encoded bytes.
     *
     * @param bytes  the array of bytes that contains the string
     * @param offset the offset in the bytes to start testing for null
     * @return the resulting String value of the bytes from offset to null or end (whichever comes first)
     */
    @JvmStatic
    fun fromNullTerminatedBytes(
        bytes: ByteArray?,
        offset: Int,
    ): String? {
        if (bytes == null) {
            return null
        }

        require(offset >= 0) { "offset must be >= 0" }

        var length = 0
        while (offset + length < bytes.size && bytes[offset + length] != '\u0000'.code.toByte()) {
            length++
        }

        if (length == 0) {
            return null
        }

        return String(bytes, offset, length)
    }

    /**
     * Creates a default encoded array of bytes of the given String value. This is not an efficient implementation, so
     * call sparingly.
     *
     * @param s the String value to convert to bytes
     * @return the bytes of the String followed by the null terminator '\0'
     */
    @JvmStatic
    fun toNullTerminatedBytes(s: String): ByteArray {
        val temp = s.toByteArray()
        val bytes = ByteArray(temp.size + 1) // +1 for null terminator
        System.arraycopy(temp, 0, bytes, 0, bytes.size - 1)
        return bytes
    }

    @JvmStatic
    fun toHexString(bytes: ByteArray?): String = toHexString(bytes, true)

    @JvmStatic
    fun toHexString(
        bytes: ByteArray?, //
        asByteArray: Boolean,
    ): String {
        if (bytes == null) return "null"
        return toHexString(bytes, 0, bytes.size, asByteArray)
    }

    @JvmStatic
    fun toHexString(
        bytes: ByteArray?,
        offset: Int,
        count: Int,
    ): String = toHexString(bytes, offset, count, true)

    private val HEX_CHARS =
        charArrayOf(
            '0',
            '1',
            '2',
            '3',
            '4',
            '5',
            '6',
            '7',
            '8',
            '9',
            'A',
            'B',
            'C',
            'D',
            'E',
            'F',
        )

    @JvmStatic
    fun toHexString(
        bytes: ByteArray?,
        offset: Int,
        count: Int, //
        asByteArray: Boolean,
    ): String {
        if (bytes == null) return "null"

        val sb = StringBuilder()
        if (asByteArray) {
            var i = offset
            while (i < count) {
                if (i != offset) {
                    sb.append('-')
                }
                sb.append(HEX_CHARS[((bytes[i]).toInt() and 0x000000f0) shr 4])
                sb.append(HEX_CHARS[((bytes[i]).toInt() and 0x0000000f)])
                i++
            }
            if (i < bytes.size) {
                sb.append('…')
            }
        } else {
            for (i in count - 1 downTo 0) {
                sb.append(HEX_CHARS[((bytes[i]).toInt() and 0x000000f0) shr 4])
                sb.append(HEX_CHARS[((bytes[i]).toInt() and 0x0000000f)])
            }
        }

        return sb.toString()
    }

    @JvmStatic
    fun toHexString(
        value: Short,
        maxBytes: Int,
    ): String = toHexString(FooMemoryStream.newBytes(value), 0, maxBytes, false)

    @JvmStatic
    fun toHexString(
        value: Int,
        maxBytes: Int,
    ): String = toHexString(FooMemoryStream.newBytes(value), 0, maxBytes, false)

    @JvmStatic
    fun toHexString(
        value: Long,
        maxBytes: Int,
    ): String = toHexString(FooMemoryStream.newBytes(value), 0, maxBytes, false)

    @JvmStatic
    fun toHexString(value: String): String? = toHexString(value.toByteArray())

    @JvmOverloads
    @JvmStatic
    fun toBitString(
        value: Byte,
        maxBits: Int,
        spaceEvery: Int = 0,
    ): String {
        var maxBits = maxBits
        val bits = FooBitSet(value)
        maxBits = max(0, min(maxBits, bits.length))
        val sb = StringBuilder()
        for (i in maxBits - 1 downTo 0) {
            sb.append(if (bits.get(i)) '1' else '0')
            if ((spaceEvery != 0) && (i > 0) && (i % spaceEvery == 0)) {
                sb.append(' ')
            }
        }
        return sb.toString()
    }

    @JvmStatic
    fun toBitString(
        bytes: ByteArray,
        maxBits: Int,
        spaceEvery: Int,
    ): String {
        var maxBits = maxBits
        val bits = FooBitSet(bytes)
        maxBits = max(0, min(maxBits, bits.length))
        val sb = StringBuilder()
        for (i in maxBits - 1 downTo 0) {
            sb.append(if (bits.get(i)) '1' else '0')
            if ((spaceEvery != 0) && (i > 0) && (i % spaceEvery == 0)) {
                sb.append(' ')
            }
        }
        return sb.toString()
    }

    @JvmOverloads
    @JvmStatic
    fun toBitString(
        value: Short,
        maxBits: Int,
        spaceEvery: Int = 8,
    ): String = toBitString(FooMemoryStream.newBytes(value), maxBits, spaceEvery)

    @JvmOverloads
    @JvmStatic
    fun toBitString(
        value: Int,
        maxBits: Int,
        spaceEvery: Int = 8,
    ): String = toBitString(FooMemoryStream.newBytes(value), maxBits, spaceEvery)

    @JvmOverloads
    @JvmStatic
    fun toBitString(
        value: Long,
        maxBits: Int,
        spaceEvery: Int = 8,
    ): String = toBitString(FooMemoryStream.newBytes(value), maxBits, spaceEvery)

    @JvmStatic
    fun toChar(value: Boolean): Char = if (value) '1' else '0'

    @JvmStatic
    fun padNumber(
        number: Long,
        ch: Char,
        minimumLength: Int,
    ): String {
        var s = number.toString()
        while (s.length < minimumLength) {
            s = ch.toString() + s
        }
        return s
    }

    @JvmStatic
    fun formatNumber(
        number: Long,
        minimumLength: Int,
    ): String = padNumber(number, '0', minimumLength)

    @JvmStatic
    fun formatNumber(
        number: Double,
        leading: Int,
        trailing: Int,
    ): String {
        @Suppress("KotlinConstantConditions")
        if (number == Double.NaN || number == Double.NEGATIVE_INFINITY || number == Double.POSITIVE_INFINITY) {
            return number.toString()
        }

        // String.valueOf(1) is guaranteed to at least be of the form "1.0"
        val parts = split(number.toString(), ".", 0)!!
        while (parts[0].length < leading) {
            parts[0] = '0'.toString() + parts[0]
        }
        while (parts[1].length < trailing) {
            parts[1] = parts[1] + '0'
        }
        parts[1] = parts[1].substring(0, trailing)
        return parts[0] + '.' + parts[1]
    }

    @JvmStatic
    fun join(
        delimiter: String,
        vararg parts: String?,
    ): String? = TextUtils.join(delimiter, parts)

    /**
     * Returns a string array that contains the substrings in a source string that are delimited by a specified string.
     *
     * @param source    String to split with the given delimiter.
     * @param separator String that delimits the substrings in the source string.
     * @param limit     Determines the maximum number of entries in the resulting array, and the treatment of trailing
     * empty strings.
     *
     *  * For n &gt; 0, the resulting array contains at most n entries. If this is fewer than the
     * number of matches, the final entry will contain all remaining input.
     *  * For n &lt; 0, the length of the resulting array is exactly the number of occurrences of the
     * Pattern plus one for the text after the final separator. All entries are included.
     *  * For n == 0, the result is as for n &lt; 0, except trailing empty strings will not be
     * returned. (Note that the case where the input is itself an empty string is special, as
     * described above, and the limit parameter does not apply there.)
     *
     * @return An array whose elements contain the substrings in a source string that are delimited by a separator
     * string.
     */
    @JvmStatic
    fun split(
        source: String?,
        separator: String?,
        limit: Int,
    ): Array<String>? {
        if (source == null) return null
        val kotlinLimit = if (limit < 0) 0 else limit
        return source.split(separator ?: "", limit = kotlinLimit).toTypedArray()
    }

    @JvmStatic
    fun replaceFirst(
        source: String?,
        pattern: String,
        replacement: String?,
    ): String = replace(source, pattern, replacement, 1)

    @JvmOverloads
    @JvmStatic
    fun replace(
        source: String?,
        pattern: String,
        replacement: String?,
        limit: Int = -1,
    ): String {
        if (source == null) {
            return ""
        }

        val sb = StringBuilder()
        var index = -1
        var fromIndex = 0
        var count = 0
        while ((
                    source
                        .indexOf(pattern, fromIndex)
                        .also { index = it }
                    ) != -1 &&
            (limit == -1 || count < limit)
        ) {
            sb.append(source.substring(fromIndex, index))
            sb.append(replacement)
            fromIndex = index + pattern.length
            count++
        }
        sb.append(source.substring(fromIndex))
        return sb.toString()
    }

    @JvmStatic
    fun contains(
        s: String,
        cs: String,
    ): Boolean = s.contains(cs)

    /**
     * Identical to [repr], but grammatically intended for Strings.
     *
     * @param value value
     * @return "null", or '\"' + value.toString + '\"', or value.toString()
     */
    @JvmStatic
    fun quote(value: Any?): String = repr(value, false)

    /**
     * Identical to [quote], but grammatically intended for Objects.
     *
     * @param value    value
     * @param typeOnly typeOnly
     * @return "null", or '\"' + value.toString + '\"', or value.toString(), or getShortClassName(value)
     */
    @JvmOverloads
    @JvmStatic
    fun repr(
        value: Any?,
        typeOnly: Boolean = false,
    ): String {
        if (value == null) {
            return "null"
        }
        if (typeOnly) {
            return FooReflection.getShortClassName(value)
        }
        return when (value) {
            is String -> "\"$value\""
            is CharSequence -> "\"$value\""
            is Intent -> FooPlatformUtils.toString(value)
            is Bundle -> FooPlatformUtils.toString(value)
            else -> value.toString()
        }
    }

    @JvmStatic
    fun <T> toString(
        items: Iterable<T?>?,
        multiline: Boolean = false,
    ): String {
        val sb = StringBuilder()

        if (items == null) {
            sb.append("null")
        } else {
            sb.append('[')
            val it = items.iterator()
            while (it.hasNext()) {
                val item = it.next()
                sb.append(quote(item))
                if (it.hasNext()) {
                    sb.append(", ")
                }
                if (multiline) {
                    sb.append(LINEFEED)
                }
            }
            sb.append(']')
        }
        return sb.toString()
    }

    @JvmStatic
    fun toString(items: Array<out Any?>?,
                 formatter: (Any?) -> String = { item -> quote(item) }): String {
        val sb = StringBuilder()

        if (items == null) {
            sb.append("null")
        } else {
            sb.append('[')
            for (i in items.indices) {
                val item = items[i]
                if (i != 0) {
                    sb.append(", ")
                }
                sb.append(formatter(item))
            }
            sb.append(']')
        }
        return sb.toString()
    }

    @JvmStatic
    fun capitalize(s: String?): String {
        if (s == null || s.isEmpty()) {
            return ""
        }
        val first = s[0]
        return if (Character.isUpperCase(first)) {
            s
        } else {
            first.uppercaseChar().toString() + s.substring(1)
        }
    }

    /**
     * @param flags flags
     * @return String in the form of "(flag1|flag3|flag5)"
     */
    @JvmStatic
    fun toFlagString(flags: Vector<*>): String {
        var flag: String?
        val sb = StringBuilder()
        sb.append('(')
        for (i in flags.indices) {
            flag = flags.elementAt(i) as String?
            if (i != 0) {
                sb.append('|')
            }
            sb.append(flag)
        }
        sb.append(')')
        return sb.toString()
    }

    /**
     * @param str1 str1
     * @param str2 str2
     * @return str1 != null ? str1.equals(str2) : str2 == null
     */
    @JvmStatic
    fun equals(
        str1: String?,
        str2: String?,
    ): Boolean = if (str1 != null) (str1 == str2) else str2 == null

    /**
     * @param msElapsed msElapsed
     * @return HH:MM:SS.MMM
     */
    @JvmStatic
    fun getTimeElapsedString(msElapsed: Long): String {
        var msElapsed = msElapsed
        var h: Long = 0
        var m: Long = 0
        var s: Long = 0
        if (msElapsed > 0) {
            h = (msElapsed / (3600 * 1000)).toInt().toLong()
            msElapsed -= (h * 3600 * 1000)
            m = (msElapsed / (60 * 1000)).toInt().toLong()
            msElapsed -= (m * 60 * 1000)
            s = (msElapsed / 1000).toInt().toLong()
            msElapsed -= (s * 1000)
        } else {
            msElapsed = 0
        }

        return formatNumber(h, 2) + ":" + formatNumber(m, 2) + ":" + formatNumber(s, 2) + "." +
                formatNumber(msElapsed, 3)
    }

    @JvmStatic
    fun getTimeDurationString(
        context: Context,
        elapsedMillis: Long,
    ): String? = getTimeDurationString(context, elapsedMillis, true)

    @JvmStatic
    fun getTimeDurationString(
        context: Context,
        elapsedMillis: Long,
        expanded: Boolean,
    ): String? = getTimeDurationString(context, elapsedMillis, expanded, null)

    @JvmStatic
    fun getTimeDurationString(
        context: Context,
        elapsedMillis: Long,
        minimumTimeUnit: TimeUnit?,
    ): String? = getTimeDurationString(context, elapsedMillis, true, minimumTimeUnit)

    /**
     * @param context         context
     * @param elapsedMillis   elapsedMillis
     * @param expanded        if true then formatted as "X days, X hours, X minutes, X seconds, …", otherwise,
     * formatted as "XX minutes", or "XX hours", or "X days"
     * @param minimumTimeUnit must be &gt;= TimeUnit.MILLISECONDS, or null to default to TimeUnit.SECONDS
     * @return null if elapsedMillis &lt; 0
     */
    @JvmStatic
    fun getTimeDurationString(
        context: Context,
        elapsedMillis: Long,
        expanded: Boolean,
        minimumTimeUnit: TimeUnit?,
    ): String? {
        var elapsedMillis = elapsedMillis
        var minimumTimeUnit = minimumTimeUnit
        requireNotNull(context) { "context must not be null" }

        if (minimumTimeUnit == null) {
            minimumTimeUnit = TimeUnit.SECONDS
        }

        require(TimeUnit.MILLISECONDS.compareTo(minimumTimeUnit) <= 0) { "minimumTimeUnit must be null or >= TimeUnit.MILLISECONDS" }

        var result: String? = null

        if (elapsedMillis >= 0) {
            val sb = StringBuilder()

            val res = context.resources

            if (expanded) {
                val days = TimeUnit.MILLISECONDS.toDays(elapsedMillis).toInt()
                if (days > 0 && TimeUnit.DAYS.compareTo(minimumTimeUnit) >= 0) {
                    elapsedMillis -= TimeUnit.DAYS.toMillis(days.toLong())
                    val temp = res.getQuantityString(R.plurals.days, days, days)
                    sb.append(' ').append(temp)
                }

                val hours = TimeUnit.MILLISECONDS.toHours(elapsedMillis).toInt()
                if (hours > 0 && TimeUnit.HOURS.compareTo(minimumTimeUnit) >= 0) {
                    elapsedMillis -= TimeUnit.HOURS.toMillis(hours.toLong())
                    val temp = res.getQuantityString(R.plurals.hours, hours, hours)
                    sb.append(' ').append(temp)
                }

                val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis).toInt()
                if (minutes > 0 && TimeUnit.MINUTES.compareTo(minimumTimeUnit) >= 0) {
                    elapsedMillis -= TimeUnit.MINUTES.toMillis(minutes.toLong())
                    val temp = res.getQuantityString(R.plurals.minutes, minutes, minutes)
                    sb.append(' ').append(temp)
                }

                val seconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis).toInt()
                if (seconds > 0 && TimeUnit.SECONDS.compareTo(minimumTimeUnit) >= 0) {
                    elapsedMillis -= TimeUnit.SECONDS.toMillis(seconds.toLong())
                    val temp = res.getQuantityString(R.plurals.seconds, seconds, seconds)
                    sb.append(' ').append(temp)
                }

                val milliseconds = elapsedMillis.toInt()
                if (TimeUnit.MILLISECONDS.compareTo(minimumTimeUnit) >= 0) {
                    val temp =
                        res.getQuantityString(R.plurals.milliseconds, milliseconds, milliseconds)
                    sb.append(' ').append(temp)
                }

                if (sb.isEmpty()) {
                    val timeUnitNameResId =
                        when (minimumTimeUnit) {
                            TimeUnit.DAYS -> R.plurals.days
                            TimeUnit.HOURS -> R.plurals.hours
                            TimeUnit.MINUTES -> R.plurals.minutes
                            TimeUnit.SECONDS -> R.plurals.seconds
                            TimeUnit.MILLISECONDS -> R.plurals.milliseconds
                            else -> R.plurals.milliseconds
                        }
                    val temp = res.getQuantityString(timeUnitNameResId, 0, 0)
                    sb.append(' ').append(temp)
                }
            } else {
                val timeUnitNameResId: Int

                var timeUnitValue = TimeUnit.MILLISECONDS.toDays(elapsedMillis).toInt()
                if (timeUnitValue > 0 || TimeUnit.DAYS.compareTo(minimumTimeUnit) <= 0) {
                    timeUnitNameResId = R.plurals.days
                } else {
                    timeUnitValue = TimeUnit.MILLISECONDS.toHours(elapsedMillis).toInt()
                    if (timeUnitValue > 0 || TimeUnit.HOURS.compareTo(minimumTimeUnit) <= 0) {
                        timeUnitNameResId = R.plurals.hours
                    } else {
                        timeUnitValue = TimeUnit.MILLISECONDS.toMinutes(elapsedMillis).toInt()
                        if (timeUnitValue > 0 || TimeUnit.MINUTES.compareTo(minimumTimeUnit) <= 0) {
                            timeUnitNameResId = R.plurals.minutes
                        } else {
                            timeUnitValue = TimeUnit.MILLISECONDS.toSeconds(elapsedMillis).toInt()
                            if (timeUnitValue > 0 || TimeUnit.SECONDS.compareTo(minimumTimeUnit) <= 0) {
                                timeUnitNameResId = R.plurals.seconds
                            } else {
                                timeUnitValue = elapsedMillis.toInt()
                                timeUnitNameResId = R.plurals.milliseconds
                            }
                        }
                    }
                }

                sb.append(res.getQuantityString(timeUnitNameResId, timeUnitValue, timeUnitValue))
            }

            result =
                sb
                    .toString()
                    // Remove any unspeakable/unprintable characters
                    //noinspection TrimLambda
                    .trim { it <= ' ' }
        }

        return result
    }

    /**
     * @param msElapsed msElapsed
     * @return HH:MM:SS.MMM
     */
    @JvmStatic
    fun getTimeDurationFormattedString(msElapsed: Long): String {
        var msElapsed = msElapsed
        var h: Long = 0
        var m: Long = 0
        var s: Long = 0
        if (msElapsed > 0) {
            h = (msElapsed / (3600 * 1000)).toInt().toLong()
            msElapsed -= (h * 3600 * 1000)
            m = (msElapsed / (60 * 1000)).toInt().toLong()
            msElapsed -= (m * 60 * 1000)
            s = (msElapsed / 1000).toInt().toLong()
            msElapsed -= (s * 1000)
        } else {
            msElapsed = 0
        }

        return formatNumber(h, 2) + ":" + formatNumber(m, 2) + ":" + formatNumber(s, 2) + "." +
                formatNumber(msElapsed, 3)
    }

    @JvmStatic
    fun separateCamelCaseWords(s: String?): String {
        val sb = StringBuilder()
        if (s != null) {
            val parts: Array<String?> =
                s.split("(?=\\p{Lu})".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (part in parts) {
                sb.append(part).append(' ')
            }
        }
        return sb
            .toString()
            // Remove any unspeakable/unprintable characters
            //noinspection TrimLambda
            .trim { it <= ' ' }
    }

    @JvmStatic
    fun startsWithVowel(
        vowels: String?,
        s: String?,
    ): Boolean = s != null && s.matches(("^[$vowels].*").toRegex())
}
