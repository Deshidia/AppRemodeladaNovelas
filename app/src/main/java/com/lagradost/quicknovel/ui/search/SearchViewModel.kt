package com.lagradost.quicknovel.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.quicknovel.APIRepository
import com.lagradost.quicknovel.APIRepository.Companion.providersActive
import com.lagradost.quicknovel.HomePageList
import com.lagradost.quicknovel.MainActivity
import com.lagradost.quicknovel.MainActivity.Companion.loadResult
import com.lagradost.quicknovel.OnGoingSearch
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.ui.result.MigrationSource
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.Coroutines
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.amap
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

class SearchViewModel : ViewModel() {
    private val _searchResponse: MutableLiveData<Resource<ArrayList<SearchResponse>>?> =
        MutableLiveData(null)
    val searchResponse: LiveData<Resource<ArrayList<SearchResponse>>?> get() = _searchResponse

    private val _currentSearch: MutableLiveData<ArrayList<OnGoingSearch>?> = MutableLiveData(null)
    val currentSearch: LiveData<ArrayList<OnGoingSearch>?> get() = _currentSearch

    @Volatile
    var searchCounter = 0
    private val repos = apis.map { APIRepository(it) }

    fun clearSearch() {
        searchCounter++
        ongoingSearchJob?.cancel()
        ongoingSearchJob = null
        _searchResponse.postValue(null)
        _currentSearch.postValue(null)
    }

    /**
     * Novela desde la que se lanzó esta búsqueda (pulsación larga en el título de su ficha), o
     * null en una búsqueda normal. Se pasa a las fichas que se abren desde los resultados para
     * que, al guardar una en marcadores, se ofrezca duplicar o migrar.
     */
    var migrationSource: MigrationSource? = null

    /** La búsqueda inicial (la del título original) solo se lanza la primera vez que se abre. */
    var startQueryConsumed = false

    fun load(card: SearchResponse) {
        loadResult(card.url, card.apiName, migrationSource = migrationSource)
    }

    fun showMetadata(card: SearchResponse) {
        MainActivity.loadPreviewPage(card, migrationSource)
        //showToast(card.name)
    }

    var ongoingSearchJob : Job? = null

    // Filtros de búsqueda elegidos por el usuario, por proveedor (nombre -> clave -> valores)
    private val activeFilters = HashMap<String, Map<String, List<String>>>()

    fun getFilters(apiName: String): Map<String, List<String>> =
        synchronized(activeFilters) { activeFilters[apiName] ?: emptyMap() }

    fun setFilters(apiName: String, values: Map<String, List<String>>) {
        synchronized(activeFilters) {
            if (values.isEmpty()) activeFilters.remove(apiName)
            else activeFilters[apiName] = values.toMap()
        }
    }

    private fun hasFilters(repo: APIRepository): Boolean =
        repo.api.searchFilters.isNotEmpty() && getFilters(repo.name).isNotEmpty()

    fun search(query: String) {
        val activeRepos = repos.filter { a ->
            providersActive.isEmpty() || providersActive.contains(a.name)
        }
        val queryTooShort = query.length <= 1
        // Sin texto (o con una sola letra) solo se busca en los proveedores con filtros elegidos
        val targets = if (queryTooShort) activeRepos.filter { hasFilters(it) } else activeRepos
        if (targets.isEmpty()) {
            clearSearch()
            return
        }
        val effectiveQuery = if (queryTooShort) "" else query

        ongoingSearchJob?.cancel()
        ongoingSearchJob = ioSafe {
            searchCounter++
            val localSearchCounter = searchCounter
            _searchResponse.postValue(Resource.Loading())

            val currentList = Coroutines.threadSafeListOf<OnGoingSearch>()

            _currentSearch.postValue(ArrayList())

            targets.amap { a ->
                if(!isActive) return@amap
                val res = a.search(effectiveQuery, getFilters(a.name))
                if (res is Resource.Success && res.value.isNotEmpty()) {
                    currentList.add(OnGoingSearch(a.name, res))
                    if (localSearchCounter == searchCounter) {
                        val sorted = synchronized(currentList) {
                            ArrayList(currentList.sortedBy { it.apiName })
                        }
                        _currentSearch.postValue(sorted)
                    }
                }
            }
            if(!isActive) return@ioSafe

            val finalSortedList = synchronized(currentList) {
                ArrayList(currentList.sortedBy { it.apiName })
            }
            _currentSearch.postValue(finalSortedList)

            if (localSearchCounter != searchCounter) return@ioSafe

            val list = ArrayList<SearchResponse>()
            val nestedList =
                finalSortedList.map { it.data }
                    .filterIsInstance<Resource.Success<List<SearchResponse>>>()
                    .map { it.value }

            // I do it this way to move the relevant search results to the top
            var index = 0
            while (true) {
                var added = 0
                for (sublist in nestedList) {
                    if (sublist.size > index) {
                        list.add(sublist[index])
                        added++
                    }
                }
                if (added == 0) break
                index++
            }
            if(!isActive) return@ioSafe

            _searchResponse.postValue(Resource.Success(list))
        }
    }

    fun loadHomepageList(item: HomePageList) {
        SearchFragment.loadHomepageList(this, item)
    }
}