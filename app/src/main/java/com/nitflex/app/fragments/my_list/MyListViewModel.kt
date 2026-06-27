package com.nitflex.app.fragments.my_list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitflex.app.adapters.AppAdapter
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.models.Category
import com.nitflex.app.ui.UserDataNotifier
import com.nitflex.app.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MyListViewModel(private val database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    sealed class State {
        data object Loading : State()
        data class Success(val categories: List<Category>) : State()
    }

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect { load() }
        }
        viewModelScope.launch {
            UserDataNotifier.updates.collect { load() }
        }
        load()
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        val favoriteMovies = database.movieDao().getFavorites().first()
        val favoriteTvShows = database.tvShowDao().getFavorites().first()

        val watchingMovies = database.movieDao().getWatchingMovies().first()
        val watchingEpisodes = database.episodeDao().getWatchingEpisodes().first()
        val continueTvShowIds = watchingEpisodes.mapNotNull { it.tvShow?.id }.distinct()
        val continueTvShows =
            if (continueTvShowIds.isEmpty()) emptyList()
            else database.tvShowDao().getByIds(continueTvShowIds).first()

        val reviewedMovies = database.movieDao().getReviewed().first()
        val reviewedTvShows = database.tvShowDao().getReviewed().first()

        val watchlist = buildList<AppAdapter.Item> {
            addAll(favoriteMovies)
            addAll(favoriteTvShows)
        }
        val continueWatching = buildList<AppAdapter.Item> {
            addAll(watchingMovies)
            addAll(continueTvShows)
        }
        val reviewed = buildList<AppAdapter.Item> {
            addAll(reviewedMovies)
            addAll(reviewedTvShows)
        }

        _state.emit(
            State.Success(
                listOf(
                    Category(Category.MY_LIST_WATCHLIST, watchlist),
                    Category(Category.MY_LIST_CONTINUE, continueWatching),
                    Category(Category.MY_LIST_REVIEWED, reviewed),
                )
            )
        )
    }
}
