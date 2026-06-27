package com.nitflex.app.fragments.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.nitflex.app.R
import com.nitflex.app.adapters.AppAdapter
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.databinding.FragmentHomeMobileBinding
import com.nitflex.app.models.Category
import com.nitflex.app.models.Episode
import com.nitflex.app.models.Movie
import com.nitflex.app.models.TvShow
import com.nitflex.app.ui.SpacingItemDecoration
import com.nitflex.app.utils.UserPreferences
import com.nitflex.app.utils.dp
import com.nitflex.app.utils.CacheUtils
import com.nitflex.app.utils.LoggingUtils
import com.nitflex.app.utils.ProviderChangeNotifier
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView

class HomeMobileFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentHomeMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by lazy {
        val providerKey = UserPreferences.currentProvider?.name ?: "default"
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(AppDatabase.getInstance(requireContext())) as T
            }
        }
        ViewModelProvider(this, factory).get(providerKey, HomeViewModel::class.java)
    }

    private val appAdapter = AppAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeHome()

        // Lightweight refresh when provider changes
        viewLifecycleOwner.lifecycleScope.launch {
            com.nitflex.app.utils.ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { viewModel.getHome() }
        }

        // Initial load
        viewModel.getHome()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    HomeViewModel.State.Loading -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }
                    is HomeViewModel.State.SuccessLoading -> {
                        displayHome(state.categories)
                        binding.isLoading.root.visibility = View.GONE
                    }
                    is HomeViewModel.State.FailedLoading -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 401) {
                            Toast.makeText(requireContext(), getString(com.nitflex.app.R.string.error_401_api_key), Toast.LENGTH_LONG).show()
                            return@collect
                        }
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(com.nitflex.app.R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getHome()
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            val doRetry = { viewModel.getHome() }
                            btnIsLoadingRetry.setOnClickListener { doRetry() }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(com.nitflex.app.R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                doRetry()
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        appAdapter.onSaveInstanceState(binding.rvHome)
        _binding = null
    }


    private fun initializeHome() {
        binding.rvHome.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(
                SpacingItemDecoration(20.dp(requireContext()))
            )
        }

        binding.ivProviderLogo.apply {
            Glide.with(context)
                .load(UserPreferences.currentProvider?.logo?.takeIf { it.isNotEmpty() }
                    ?: R.drawable.ic_provider_default_logo)
                .error(R.drawable.ic_provider_default_logo)
                .fitCenter()
                .into(this)

            setOnClickListener {
                findNavController().navigate(R.id.providers)
            }
        }
        
        // Ensure background image is hidden on mobile to show theme color
        binding.ivHomeBackground.visibility = View.GONE
    }

    private fun displayHome(allCategories: List<Category>) {
        // Continue Watching + Favorites now live in the My List tab, so drop them from Home.
        val categories = allCategories.filterNot {
            it.name == Category.CONTINUE_WATCHING ||
                it.name == Category.FAVORITE_MOVIES ||
                it.name == Category.FAVORITE_TV_SHOWS
        }

        categories
            .find { it.name == Category.FEATURED }
            ?.also {
                it.list.forEach { show ->
                    when (show) {
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_SWIPER_MOBILE_ITEM
                        is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_SWIPER_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.CONTINUE_WATCHING }
            ?.also {
                it.name = getString(R.string.home_continue_watching)
                it.list.forEach { show ->
                    when (show) {
                        is Episode -> show.itemType = AppAdapter.Type.EPISODE_CONTINUE_WATCHING_MOBILE_ITEM
                        is Movie -> show.itemType = AppAdapter.Type.MOVIE_CONTINUE_WATCHING_MOBILE_ITEM
                    }
                }
            }

        categories
            .find { it.name == Category.FAVORITE_MOVIES }
            ?.also { it.name = getString(R.string.home_favorite_movies) }

        categories
            .find { it.name == Category.FAVORITE_TV_SHOWS }
            ?.also { it.name = getString(R.string.home_favorite_tv_shows) }

        appAdapter.submitList(
            categories
                .filter { it.list.isNotEmpty() }
                .onEach { category ->
                    if (category.name != Category.FEATURED && category.name != getString(R.string.home_continue_watching)) {
                        category.list.onEach { show ->
                            when (show) {
                                is Episode -> show.itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
                                is Movie -> show.itemType = AppAdapter.Type.MOVIE_MOBILE_ITEM
                                is TvShow -> show.itemType = AppAdapter.Type.TV_SHOW_MOBILE_ITEM
                            }
                        }
                    }
                    category.itemSpacing = 10.dp(requireContext())
                    category.itemType = when (category.name) {
                        Category.FEATURED -> AppAdapter.Type.CATEGORY_MOBILE_SWIPER
                        else -> AppAdapter.Type.CATEGORY_MOBILE_ITEM
                    }
                }
        )
    }
}
