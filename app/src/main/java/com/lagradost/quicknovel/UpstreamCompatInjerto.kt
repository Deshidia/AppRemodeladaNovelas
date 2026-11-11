package com.lagradost.quicknovel

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE
import com.lagradost.quicknovel.BookDownloader2Helper.IMPORT_SOURCE_PDF
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.ui.UiImage
import com.lagradost.quicknovel.ui.img
import com.lagradost.quicknovel.util.DefaultImagesHeaders
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlinx.coroutines.sync.Mutex
import org.jsoup.nodes.Element


const val DOWNLOAD_POSTER: String = "downloads_poster"
const val DOWNLOAD_SIZE: String = "downloads_size"

@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JvmInline
@Suppress("UNUSED")
value class Score private constructor(
    /** Decimal between [0, 10^9] representing the min score and max score respectively */
    @JsonProperty("data") private val data: Int,
) {
    /*@Deprecated(
        "toOld() is deprecated. Use other Score methods instead.",
        level = DeprecationLevel.ERROR
    )*/
    fun toOld(): Int = toInt(1000)

    fun toByte(maxScore: Int): Byte = toLong(maxScore).toByte()

    fun toInt(maxScore: Int = 10): Int = toLong(maxScore).toInt()

    fun toLong(maxScore: Int = 10): Long = (data.toLong() * maxScore.toLong()) / MAX.toLong()

    fun toFloat(maxScore: Int = 10): Float =
        (data.toFloat() / MAX.toFloat()) * maxScore.toFloat()

    fun toDouble(maxScore: Int = 10): Double =
        (data.toDouble() / MAX.toDouble()) * maxScore.toDouble()

    override fun toString(): String = this.toString(10)

    /** Formats the rating to a human-readable format (with no rounding)
     *
     * However, it may also return null if the score is less than the minimum score,
     * this is to avoid 0.0/10.0 in case of default = 0
     * */
    @Throws(IllegalArgumentException::class)
    fun toStringNull(
        minScore: Double,
        maxScore: Int,
        decimals: Int = 1,
        removeTrailingZeros: Boolean = true,
        decimalChar: Char = '.'
    ): String? {
        if (toDouble() < minScore) return null
        return toString(maxScore, decimals, removeTrailingZeros, decimalChar)
    }

    /** Formats the rating to a human-readable format (with no rounding) */
    @Throws(IllegalArgumentException::class)
    fun toString(
        maxScore: Int,
        decimals: Int = 1,
        removeTrailingZeros: Boolean = true,
        decimalChar: Char = '.'
    ): String {
        require(maxScore in 1..1000) {
            "maxScore ∈ [1,1000]"
        }
        require(decimals in 0..MAX_ZEROS) {
            "decimals ∈ [0,$MAX_ZEROS]"
        }
        var number = data.toLong() * maxScore.toLong()
        val chars = CharArray(MAX_ZEROS + 6)

        for (i in chars.indices) {
            chars[i] = (number % 10L).toInt().digitToChar()
            number /= 10L
        }

        var trailingZeros = MAX_ZEROS - decimals
        for (i in (MAX_ZEROS - decimals) until chars.size) {
            if (chars[i] != '0') {
                break
            }
            trailingZeros += 1
        }

        var leadingZeros = 0
        for (i in chars.indices.reversed()) {
            if (chars[i] != '0') {
                break
            }
            leadingZeros += 1
        }

        val stringBuilder = StringBuilder()
        for (i in maxOf(MAX_ZEROS, (chars.size - leadingZeros - 1)) downTo MAX_ZEROS) {
            stringBuilder.append(chars[i])
        }

        val end = if (removeTrailingZeros) {
            maxOf(MAX_ZEROS - decimals, trailingZeros)
        } else {
            MAX_ZEROS - decimals
        }

        if (end <= MAX_ZEROS - 1) {
            stringBuilder.append(decimalChar)
            for (i in MAX_ZEROS - 1 downTo end) {
                stringBuilder.append(chars[i])
            }
        }

        return stringBuilder.toString()
    }

    companion object {
        const val MAX: Int = 1000_000_000
        //const val MIN: Int = 0
        const val MAX_ZEROS: Int = 9
        //private const val TAG: String = "Score"

        /*@Deprecated(
            "Score.fromOld is deprecated. Use other Score.from* methods instead.",
            level = DeprecationLevel.ERROR
        )*/
        fun fromOld(value: Int?): Score? {
            if (value == null) return null
            if (value !in 0..1000) {
                //Log.w(TAG, "old: $value ∉ [0, 1000]")
                return null
            }
            return Score(value * 1_000_000)
        }

        /** `value ∈ [0, maxScore]` */
        fun from(value: Int?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value !in 0..maxScore) {
                //Log.w(TAG, "fromInt: $value ∉ [0, $maxScore]")
                return null
            }
            return Score((MAX / maxScore) * value)
        }

        /** `value ∈ [0.0, maxScore]` */
        fun from(value: Double?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value < 0.0 || value > maxScore) {
                //Log.w(TAG, "fromDouble: $value ∉ [0.0, $maxScore]")
                return null
            }
            return Score(((MAX / maxScore).toDouble() * value).roundToInt())
        }

        /** `value ∈ [0.0f, maxScore]` */
        fun from(value: Float?, maxScore: Int): Score? {
            if (value == null) {
                return null
            }
            if (value < 0.0 || value > maxScore) {
                //Log.w(TAG, "fromFloat: $value ∉ [0.0f, $maxScore]")
                return null
            }
            return Score(((MAX / maxScore).toFloat() * value).roundToInt())
        }

        /** `value ∈ ["0.0", maxScore]` */
        fun from(value: String?, maxScore: Int): Score? =
            from(value?.trim()?.toDoubleOrNull()?.absoluteValue, maxScore)

        /** `value ∈ [0, 5]` */
        fun from5(value: Int?): Score? = from(value, 5)

        /** `value ∈ [0, 10]` */
        fun from10(value: Int?): Score? = from(value, 10)

        /** `value ∈ [0, 100]` */
        fun from100(value: Int?): Score? = from(value, 100)

        /** `value ∈ [0.0, 5.0]` */
        fun from5(value: Double?): Score? = from(value, 5)

        /** `value ∈ [0.0, 10.0]` */
        fun from10(value: Double?): Score? = from(value, 10)

        /** `value ∈ [0.0, 100.0]` */
        fun from100(value: Double?): Score? = from(value, 100)

        /** `value ∈ [0.0f, 5.0f]` */
        fun from5(value: Float?): Score? = from(value, 5)

        /** `value ∈ [0.0f, 10.0f]` */
        fun from10(value: Float?): Score? = from(value, 10)

        /** `value ∈ [0.0f, 100.0f]` */
        fun from100(value: Float?): Score? = from(value, 100)

        /** `value ∈ ["0.0", "5.0"]` */
        fun from5(value: String?): Score? = from(value, 5)

        /** `value ∈ ["0.0", "10.0"]` */
        fun from10(value: String?): Score? = from(value, 10)

        /** `value ∈ ["0.0", "100.0"]` */
        fun from100(value: String?): Score? = from(value, 100)
    }
}

enum class SlopStatus {
    Human,
    AiAssisted,
    AiGenerated,
}

const val EPUB_CURRENT_VISUAL_POSITION: String = "reader_epub_visual_position"
const val EPUB_BITSET_POSITION: String = "reader_epub_read_biset"