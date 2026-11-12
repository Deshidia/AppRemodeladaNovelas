package com.lagradost.quicknovel

import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonProperty
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

