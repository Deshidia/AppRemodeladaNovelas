package com.lagradost.quicknovel.ui.history

import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BaseApplication.Companion.removeKey
import com.lagradost.quicknovel.BaseApplication.Companion.removeKeys
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.BookDownloader2
import com.lagradost.quicknovel.BookDownloader2Helper.generateId
import com.lagradost.quicknovel.BookDownloader2Helper.createQuickStream
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.QuickStreamData
import com.lagradost.quicknovel.QuickStreamMetaData
import com.lagradost.quicknovel.providers.WebLinkProvider
import com.lagradost.quicknovel.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.Toast
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.HISTORY_FOLDER
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.RESULT_BOOKMARK
import com.lagradost.quicknovel.ui.LibraryListManager
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ResultCached

class HistoryViewModel : ViewModel() {
    private fun updateHistory() {
        ioSafe {
            val list = ArrayList<ResultCached>()
            val keys = getKeys(HISTORY_FOLDER) ?: return@ioSafe
            for (k in keys) {
                val res =
                    getKey<ResultCached>(k) ?: continue
                list.add(res)
            }
            list.sortBy { -it.cachedTime }
            cards.postValue(list)
        }
    }

    init {
        updateHistory()
    }

    val cards: MutableLiveData<ArrayList<ResultCached>> by lazy {
        MutableLiveData<ArrayList<ResultCached>>()
    }

    fun deleteAllAlert() {
        val act = activity ?: return

        act.runOnUiThread {
            val dialogClickListener =
                DialogInterface.OnClickListener { _, which ->
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            removeKeys(HISTORY_FOLDER)
                            updateHistory()
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                        }
                    }
                }

            val builder: AlertDialog.Builder =
                AlertDialog.Builder(act)
            builder.setMessage(R.string.remove_all_history)
                .setTitle(R.string.remove_history)
                .setPositiveButton(R.string.remove, dialogClickListener)
                .setNegativeButton(R.string.cancel, dialogClickListener)
                .show()
        }
    }

    fun open(card: ResultCached) {
        loadResult(card.source, card.apiName)
    }

    fun showMetadata(card: ResultCached) {
        MainActivity.loadPreviewPage(card)
    }

    fun deleteAlert(card: ResultCached) {
        val act = activity ?: return
        act.runOnUiThread {
            val dialogClickListener =
                DialogInterface.OnClickListener { _, which ->
                    when (which) {
                        DialogInterface.BUTTON_POSITIVE -> {
                            delete(card)
                        }

                        DialogInterface.BUTTON_NEGATIVE -> {
                        }
                    }
                }

            val builder: AlertDialog.Builder =
                AlertDialog.Builder(act)
            builder.setMessage(act.getString(R.string.remove_from_history_format).format(card.name))
                .setTitle(R.string.remove)
                .setPositiveButton(R.string.remove, dialogClickListener)
                .setNegativeButton(R.string.cancel, dialogClickListener)
                .show()
        }
    }

    fun delete(card: ResultCached) {
        removeKey(HISTORY_FOLDER, card.id.toString())
        updateHistory()
    }

    fun stream(card: ResultCached) {
        BookDownloader2.stream(card)
    }

    /** Descarga la página del enlace y la abre en el lector. Devuelve false si el enlace no es válido. */
    fun openLink(rawUrl: String): Boolean {
        val url = WebLinkProvider.normalizeUrl(rawUrl) ?: return false

        showToast(R.string.history_open_link_loading, Toast.LENGTH_SHORT)
        ioSafe {
            try {
                val page = WebLinkProvider.fetchPage(url)
                saveLinkToHistory(url, page)
                val act = activity ?: return@ioSafe
                val uri = withContext(Dispatchers.IO) {
                    act.createQuickStream(
                        QuickStreamData(
                            QuickStreamMetaData(page.host, page.title, WebLinkProvider.NAME),
                            null,
                            mutableListOf(ChapterData(page.title, url, null, null))
                        )
                    )
                }
                BookDownloader2.openQuickStream(uri)
            } catch (t: Throwable) {
                logError(t)
                showToast(R.string.history_open_link_error, Toast.LENGTH_LONG)
            }
        }
        return true
    }

    /**
     * Guarda la página abierta con "Abrir enlace" en el historial para que aparezca en la lista
     * y se pueda mover a la biblioteca. El id sale de (proveedor, autor, nombre), igual que en
     * ResultViewModel, así que abrir la misma página otra vez no crea duplicados.
     */
    private fun saveLinkToHistory(url: String, page: WebLinkProvider.WebPage) {
        val id = generateId(WebLinkProvider.NAME, page.host, page.title)
        setKey(
            HISTORY_FOLDER, id.toString(), ResultCached(
                source = url,
                name = page.title,
                apiName = WebLinkProvider.NAME,
                id = id,
                author = page.host,
                poster = null,
                tags = null,
                rating = null,
                totalChapters = 1,
                cachedTime = System.currentTimeMillis()
            )
        )
        updateHistory()
    }

    /** Ids de las listas de la biblioteca en las que está [card] (vacío si no está en ninguna). */
    fun getLibraryStates(card: ResultCached): Set<Int> = LibraryListManager.getNovelLists(card.id)

    /** Pone [card] en las listas [states] de la biblioteca; con un conjunto vacío la quita de la biblioteca. */
    fun setLibraryStates(card: ResultCached, states: Set<Int>) {
        LibraryListManager.setNovelLists(card.id, states)
        if (LibraryListManager.getNovelLists(card.id).isEmpty()) {
            removeKey(RESULT_BOOKMARK, card.id.toString())
        } else {
            setKey(RESULT_BOOKMARK, card.id.toString(), card.copy(cachedTime = System.currentTimeMillis()))
        }
    }
}
