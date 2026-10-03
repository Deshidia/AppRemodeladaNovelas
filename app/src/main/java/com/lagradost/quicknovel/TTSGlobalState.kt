package com.lagradost.quicknovel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lagradost.quicknovel.ui.UiText

/**
 * ReadActivity2 owns the actual TTS engine (through its ReadActivityViewModel), and that engine
 * is intentionally kept alive even after the user leaves the reader to browse the library
 * (see ReadActivity2.exitReader()). This object is the bridge that lets other screens (e.g.
 * MainActivity) know that a TTS session is running, show a small mini-player for it, and send
 * play/pause/skip commands to it, all without holding a direct/strong reference to the reader.
 */
object TTSGlobalState {
    data class Info(
        val bookTitle: String,
        val chapterTitle: UiText?,
        val status: TTSHelper.TTSStatus,
        /** Portada del libro; null mientras no se haya cargado (o si no tiene). */
        val poster: Bitmap? = null
    )

    private val _info = MutableLiveData<Info?>(null)
    val info: LiveData<Info?> = _info

    /** Called by ReadActivityViewModel every time its TTS status changes. */
    fun update(
        bookTitle: String,
        chapterTitle: UiText?,
        status: TTSHelper.TTSStatus,
        poster: Bitmap? = null
    ) {
        _info.postValue(
            if (status == TTSHelper.TTSStatus.IsStopped) {
                null
            } else {
                Info(bookTitle, chapterTitle, status, poster)
            }
        )
    }

    fun clear() {
        _info.postValue(null)
    }

    fun playPause() {
        ReadActivity2.readActivity?.activeViewModel()?.pausePlayTTS()
    }

    fun skipForward() {
        ReadActivity2.readActivity?.activeViewModel()?.forwardsTTS()
    }

    fun skipBackward() {
        ReadActivity2.readActivity?.activeViewModel()?.backwardsTTS()
    }

    fun stop() {
        ReadActivity2.readActivity?.activeViewModel()?.stopTTS()
    }

    /** Brings the reader for the book currently being listened to back to the foreground. */
    fun openReader(context: Context) {
        if (ReadActivity2.readActivity == null) return
        val intent = Intent(context, ReadActivity2::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(intent)
    }
}
