package com.nitflex.app.fragments.explore

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.nitflex.app.R
import com.nitflex.app.adapters.AppAdapter
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.databinding.FragmentExploreMobileBinding
import com.nitflex.app.fragments.movies.MoviesViewModel
import com.nitflex.app.fragments.tv_shows.TvShowsViewModel
import com.nitflex.app.models.Movie
import com.nitflex.app.models.TvShow
import com.nitflex.app.providers.Provider
import com.nitflex.app.ui.SpacingItemDecoration
import com.nitflex.app.utils.CacheUtils
import com.nitflex.app.utils.UserPreferences
import com.nitflex.app.utils.dp
import com.nitflex.app.utils.viewModelsFactory
import kotlinx.coroutines.launch

class ExploreMobileFragment : Fragment() {

    private enum class Tab { MOVIES, TV_SHOWS }

    private var _binding: FragmentExploreMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val moviesViewModel by viewModelsFactory { MoviesViewModel(database) }
    private val tvShowsViewModel by viewModelsFactory { TvShowsViewModel(database) }

    private val appAdapter = AppAdapter()

    private var currentTab = Tab.MOVIES

    private var latestMovies: List<Movie> = emptyList()
    private var moviesHasMore = false
    private var latestTvShows: List<TvShow> = emptyList()
    private var tvShowsHasMore = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExploreMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvExplore.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            addItemDecoration(SpacingItemDecoration(10.dp(requireContext())))
        }

        val provider = UserPreferences.currentProvider
        val supportsMovies = provider != null && Provider.supportsMovies(provider)
        val supportsTvShows = provider != null && Provider.supportsTvShows(provider)

        binding.tvExploreTabMovies.visibility = if (supportsMovies) View.VISIBLE else View.GONE
        binding.tvExploreTabTvShows.visibility = if (supportsTvShows) View.VISIBLE else View.GONE

        binding.tvExploreTabMovies.setOnClickListener { selectTab(Tab.MOVIES) }
        binding.tvExploreTabTvShows.setOnClickListener { selectTab(Tab.TV_SHOWS) }

        observeMovies()
        observeTvShows()

        // Default to Movies; fall back to TV Shows when the provider has no movies.
        selectTab(if (supportsMovies) Tab.MOVIES else Tab.TV_SHOWS)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        styleTab(binding.tvExploreTabMovies, tab == Tab.MOVIES)
        styleTab(binding.tvExploreTabTvShows, tab == Tab.TV_SHOWS)
        when (tab) {
            Tab.MOVIES -> showMovies()
            Tab.TV_SHOWS -> showTvShows()
        }
    }

    private fun styleTab(textView: TextView, selected: Boolean) {
        textView.background =
            if (selected) androidx.core.content.ContextCompat.getDrawable(
                requireContext(), R.drawable.bg_explore_tab_selected
            ) else null
        textView.setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
        textView.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun showMovies() {
        if (latestMovies.isEmpty()) {
            showLoading()
        } else {
            binding.isLoading.root.visibility = View.GONE
        }
        appAdapter.submitList(latestMovies.onEach { it.itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM })
        if (moviesHasMore) {
            appAdapter.setOnLoadMoreListener { moviesViewModel.loadMoreMovies() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun showTvShows() {
        if (latestTvShows.isEmpty()) {
            showLoading()
        } else {
            binding.isLoading.root.visibility = View.GONE
        }
        appAdapter.submitList(latestTvShows.onEach { it.itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM })
        if (tvShowsHasMore) {
            appAdapter.setOnLoadMoreListener { tvShowsViewModel.loadMoreTvShows() }
        } else {
            appAdapter.setOnLoadMoreListener(null)
        }
    }

    private fun showLoading() {
        binding.isLoading.apply {
            root.visibility = View.VISIBLE
            pbIsLoading.visibility = View.VISIBLE
            gIsLoadingRetry.visibility = View.GONE
        }
    }

    private fun observeMovies() {
        viewLifecycleOwner.lifecycleScope.launch {
            moviesViewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    MoviesViewModel.State.Loading -> if (currentTab == Tab.MOVIES && latestMovies.isEmpty()) showLoading()
                    MoviesViewModel.State.LoadingMore -> if (currentTab == Tab.MOVIES) appAdapter.isLoading = true
                    is MoviesViewModel.State.SuccessLoading -> {
                        latestMovies = state.movies
                        moviesHasMore = state.hasMore
                        if (currentTab == Tab.MOVIES) {
                            appAdapter.isLoading = false
                            showMovies()
                        }
                    }
                    is MoviesViewModel.State.FailedLoading -> if (currentTab == Tab.MOVIES) handleError(state.error) { moviesViewModel.getMovies() }
                }
            }
        }
    }

    private fun observeTvShows() {
        viewLifecycleOwner.lifecycleScope.launch {
            tvShowsViewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    TvShowsViewModel.State.Loading -> if (currentTab == Tab.TV_SHOWS && latestTvShows.isEmpty()) showLoading()
                    TvShowsViewModel.State.LoadingMore -> if (currentTab == Tab.TV_SHOWS) appAdapter.isLoading = true
                    is TvShowsViewModel.State.SuccessLoading -> {
                        latestTvShows = state.tvShows
                        tvShowsHasMore = state.hasMore
                        if (currentTab == Tab.TV_SHOWS) {
                            appAdapter.isLoading = false
                            showTvShows()
                        }
                    }
                    is TvShowsViewModel.State.FailedLoading -> if (currentTab == Tab.TV_SHOWS) handleError(state.error) { tvShowsViewModel.getTvShows() }
                }
            }
        }
    }

    private fun handleError(error: Exception, retry: () -> Unit) {
        val code = (error as? retrofit2.HttpException)?.code()
        if (code == 401) {
            Toast.makeText(requireContext(), getString(R.string.error_401_api_key), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(requireContext(), error.message ?: "", Toast.LENGTH_SHORT).show()
        if (appAdapter.isLoading) {
            appAdapter.isLoading = false
        } else {
            binding.isLoading.apply {
                root.visibility = View.VISIBLE
                pbIsLoading.visibility = View.GONE
                gIsLoadingRetry.visibility = View.VISIBLE
                btnIsLoadingRetry.setOnClickListener { retry() }
                btnIsLoadingClearCache.setOnClickListener {
                    CacheUtils.clearAppCache(requireContext())
                    Toast.makeText(requireContext(), getString(R.string.clear_cache_done), Toast.LENGTH_SHORT).show()
                    retry()
                }
            }
        }
    }
}
