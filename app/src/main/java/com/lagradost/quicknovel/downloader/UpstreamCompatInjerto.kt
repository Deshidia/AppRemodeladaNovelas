package com.lagradost.quicknovel.downloader

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.compose.runtime.Immutable
import com.anggrayudi.storage.StorageFile
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.BaseApplication
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2.LOCAL_EPUB_MIN_SIZE
import com.lagradost.quicknovel.DOWNLOAD_FOLDER
import com.lagradost.quicknovel.DOWNLOAD_OFFSET
import com.lagradost.quicknovel.DOWNLOAD_POSTER
import com.lagradost.quicknovel.DOWNLOAD_TOTAL
import com.lagradost.quicknovel.DownloadFileWorkManager
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.extractors.ExtractorApi
import com.lagradost.quicknovel.get
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.mvvm.safeAsync
import com.lagradost.quicknovel.ui.download.DownloadFragment
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.FileNotFoundException
import java.io.IOException
import java.util.BitSet
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile


enum class BookAction {
    Stop,
    Resume,
    Pause,
}

enum class BookDownloadStatus {
    /** Paused by user */
    Paused,

    /** Currently downloading */
    Downloading,

    /** Download fully done */
    Done,

    /** Download failed due to e.g. networking */
    Failed,

    /** Download stopped by the user */
    Stopped,

    /** Download currently pending */
    Pending,

    /** Download in idle, nothing has happened */
    Idle,
}