package com.lagradost.quicknovel.ui.search

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.HomePageList
import com.lagradost.quicknovel.databinding.FragmentSearchBinding
import com.lagradost.quicknovel.databinding.HomeEpisodesExpandedBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.mvvm.observeNullable
import com.lagradost.quicknovel.ui.BaseFragment
import com.lagradost.quicknovel.ui.home.BrowseAdapter
import com.lagradost.quicknovel.ui.home.HomeViewModel
import com.lagradost.quicknovel.ui.result.MigrationSource
import com.lagradost.quicknovel.ui.setRecycledViewPool
import com.lagradost.quicknovel.ui.settings.SettingsFragment
import com.lagradost.quicknovel.util.Event
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar

class SearchFragment : BaseFragment<FragmentSearchBinding>(
    BindingCreator.Inflate(FragmentSearchBinding::inflate)
) {
    private val viewModel: SearchViewModel by viewModels()
    private val homeViewModel: HomeViewModel by viewModels()

    companion object {
        val configEvent = Event<Int>()
        var currentSpan = 1
        var currentDialog: Dialog? = null

        private const val ARG_QUERY = "query"

        /**
         * Argumentos para abrir la búsqueda ya lanzada con [query] y en modo avanzado (resultados
         * separados por proveedor). [migrationSource] es la novela desde la que se busca, para
         * ofrecer duplicar o migrar si se guarda en marcadores alguno de los resultados.
         */
        fun newInstance(query: String, migrationSource: MigrationSource?): Bundle =
            Bundle().apply {
                putString(ARG_QUERY, query)
                migrationSource?.writeTo(this)
            }

        fun loadHomepageList(viewModel: SearchViewModel, item: HomePageList) {
            if (currentDialog != null) return
            val act = activity ?: return

            val bottomSheetDialog = BottomSheetDialog(act)
            val binding = HomeEpisodesExpandedBinding.inflate(act.layoutInflater, null, false)
            bottomSheetDialog.setContentView(binding.root)

            binding.homeExpandedText.text = item.name
            binding.homeExpandedDragDown.setOnClickListener {
                bottomSheetDialog.dismiss()
            }


            binding.homeExpandedRecycler.apply {
                setRecycledViewPool(SearchAdapter.sharedPool)
                val searchAdapter = SearchAdapter(viewModel, binding.homeExpandedRecycler)
                searchAdapter.submitList(item.list)
                adapter = searchAdapter
                spanCount = currentSpan
            }

            val spanListener = { span: Int ->
                binding.homeExpandedRecycler.spanCount = span
            }

            configEvent += spanListener

            bottomSheetDialog.setOnDismissListener {
                configEvent -= spanListener
                currentDialog = null
            }
            currentDialog = bottomSheetDialog
            bottomSheetDialog.show()
        }
    }

    override fun fixLayout(view: View) {
        val compactView = false//activity?.getGridIsCompact() ?: false
        val spanCountLandscape = if (compactView) 2 else 6
        val spanCountPortrait = if (compactView) 1 else 3
        val orientation = resources.configuration.orientation

        currentSpan = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            spanCountLandscape
        } else {
            spanCountPortrait
        }

        binding?.homeBrowselist?.spanCount = currentSpan
        binding?.searchAllRecycler?.spanCount = currentSpan
        configEvent.invoke(currentSpan)
    }

    lateinit var searchExitIcon: ImageView
    lateinit var searchMagIcon: ImageView

    override fun onBindingCreated(binding: FragmentSearchBinding, savedInstanceState: Bundle?) {
        // Búsqueda lanzada desde la ficha de una novela (pulsación larga en el título).
        val startQuery = arguments?.getString(ARG_QUERY)?.takeIf { it.isNotBlank() }
        viewModel.migrationSource = MigrationSource.readFrom(arguments)

        activity?.window?.setSoftInputMode(
            // ya viene con la consulta hecha: no hace falta el teclado
            if (startQuery != null) WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            else WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        val settingsManager = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
        // Buscando la misma novela en otros proveedores siempre se muestran separados por
        // proveedor, aunque el ajuste general de búsqueda avanzada esté desactivado.
        val isAdvancedSearch =
            startQuery != null || settingsManager?.getBoolean("advanced_search", true) == true
        binding.searchMasterRecycler.isVisible = false
        binding.searchAllRecycler.isGone = false

        val masterAdapter = ParentItemAdapter(viewModel)
        val allAdapter = SearchAdapter(viewModel, binding.searchAllRecycler)
        binding.searchAllRecycler.adapter = allAdapter
        binding.searchAllRecycler.setRecycledViewPool(SearchAdapter.sharedPool)
        binding.searchMasterRecycler.apply {
            setRecycledViewPool(ParentItemAdapter.sharedPool)
            adapter = masterAdapter
            layoutManager = GridLayoutManager(context, 1)
        }

        observeNullable(viewModel.searchResponse) { response ->
            binding.homeBrowselist.isVisible = response == null
            if (response == null) {
                binding.searchAllRecycler.isVisible = false
                allAdapter.submitIncomparableList(emptyList())
                searchExitIcon.alpha = 1f
                binding.searchLoadingBar.alpha = 0f
                return@observeNullable
            }
            binding.searchAllRecycler.isGone = isAdvancedSearch
            when (response) {
                is Resource.Success -> {
                    response.value.let { data ->
                        allAdapter.submitList(data)
                    }
                    searchExitIcon.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Failure -> {
                    // Toast.makeText(activity, "Server error", Toast.LENGTH_LONG).show()
                    searchExitIcon.alpha = 1f
                    binding.searchLoadingBar.alpha = 0f
                }

                is Resource.Loading -> {
                    searchExitIcon.alpha = 0f
                    binding.searchLoadingBar.alpha = 1f
                }
            }
        }

        observeNullable(viewModel.currentSearch) { list ->
            if (list == null) {
                binding.searchMasterRecycler.isVisible = false
                masterAdapter.submitIncomparableList(emptyList())
            } else {
                binding.searchMasterRecycler.isVisible = isAdvancedSearch
                masterAdapter.submitList(list.map {
                    HomePageList(
                        it.apiName, if (it.data is Resource.Success) it.data.value else emptyList()
                    )
                })
            }
        }

        activity?.fixPaddingStatusbar(binding.searchToolbar)

        binding.searchLoadingBar.alpha = 0f
        searchExitIcon = binding.mainSearch.findViewById(androidx.appcompat.R.id.search_close_btn)
        searchMagIcon = binding.mainSearch.findViewById(androidx.appcompat.R.id.search_mag_icon)
        searchMagIcon.scaleX = 0.65f
        searchMagIcon.scaleY = 0.65f

        binding.searchFilter.setOnClickListener {
            SettingsFragment.showSearchProviders(it.context)
        }

        binding.mainSearch.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchExitIcon.alpha = 0f
                binding.searchLoadingBar.alpha = 1f
                viewModel.search(query)
                return true
            }

            override fun onQueryTextChange(newText: String): Boolean {
                if (newText.isEmpty()) {
                    viewModel.clearSearch()
                }
                return true
            }
        })

        /*binding.mainSearch.setOnQueryTextFocusChangeListener { searchView, b ->
            if (b) {
                // https://stackoverflow.com/questions/12022715/unable-to-show-keyboard-automatically-in-the-searchview
                searchView.doOnLayout {
                    val imm: InputMethodManager? =
                        activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager?
                    imm?.showSoftInput(searchView.findFocus(), 0)
                }
            }
        }*/
        //binding.mainSearch.onActionViewExpanded()


        val browseAdapter = BrowseAdapter()
        binding.homeBrowselist.apply {
            adapter = browseAdapter
            // layoutManager = GridLayoutManager(context, 1)
            setHasFixedSize(true)
        }

        observe(homeViewModel.homeApis) { list ->
            browseAdapter.submitList(list)
        }

        // Al volver desde una ficha este método se ejecuta otra vez, pero el ViewModel conserva
        // los resultados: la búsqueda solo se lanza la primera vez.
        if (startQuery != null && !viewModel.startQueryConsumed) {
            viewModel.startQueryConsumed = true
            binding.mainSearch.setQuery(startQuery, true)
            binding.mainSearch.clearFocus()
        }

        /*
        thread {
            searchDowloads.clear()
            val keys = DataStore.getKeys(DOWNLOAD_FOLDER)
            for (k in keys) {
                val data = DataStore.getKey<DownloadFragment.DownloadData>(k)
                if (data != null) {
                    val info = requireContext().downloadInfo(data.author, data.name, 100000, data.apiName)
                    if(info != null && info.progress > 0) {
                        searchDowloads.add(data)
                    }
                }
            }
        }*/
    }
}