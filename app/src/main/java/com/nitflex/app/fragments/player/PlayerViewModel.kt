package com.nitflex.app.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nitflex.app.database.dao.ServerPreferenceDao
import com.nitflex.app.models.ServerPreference
import com.nitflex.app.models.Video
import com.nitflex.app.utils.CustomTabHelper
import com.nitflex.app.utils.EpisodeManager
import com.nitflex.app.utils.OpenSubtitles
import com.nitflex.app.utils.TmdbUtils
import com.nitflex.app.utils.UserPreferences
import com.nitflex.app.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.nitflex.app.utils.SubDL

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
    private val serverPreferenceDao: ServerPreferenceDao? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode
    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        // Reset per-content state used for auto English subtitle selection.
        videoIsReady = false
        autoEnglishApplied = false
        userPickedSubtitle = false
        pendingAutoEnglish = null
        _state.emit(State.LoadingServers)
        try {
            val servers = UserPreferences.currentProvider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")

            // Put the server that last played this show first, so we don't loop through
            // every server again for each new episode.
            val orderedServers = reorderByPreferredServer(servers, videoType)

            // LOG POTENZIATO: Mostra tutti i server disponibili per il player
            Log.i("NitflexES", "[SERVERS LIST] -> Provider: ${UserPreferences.currentProvider!!.name}")
            Log.i("NitflexES", "[SERVERS LIST] -> Found ${orderedServers.size} servers: ${orderedServers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${orderedServers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(orderedServers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    /**
     * Stable key for the content whose working server we remember: the TV show id for
     * episodes (so all episodes of a show share it) or the movie id for movies.
     */
    private fun contentKeyFor(videoType: Video.Type?): String? = when (videoType) {
        is Video.Type.Episode -> videoType.tvShow.id.takeIf { it.isNotBlank() }
        is Video.Type.Movie -> videoType.id.takeIf { it.isNotBlank() }
        null -> null
    }

    /**
     * Returns [servers] with the remembered working server moved to the front (matched by
     * name, which is stable across episodes). Original relative order of the remaining
     * servers is preserved. No-op when nothing is remembered.
     */
    private fun reorderByPreferredServer(
        servers: List<Video.Server>,
        videoType: Video.Type,
    ): List<Video.Server> {
        val key = contentKeyFor(videoType) ?: return servers
        val preferredName = try {
            serverPreferenceDao?.getByTvShowId(key)?.serverName
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore lettura server preferito: ", e)
            null
        }
        if (preferredName.isNullOrBlank()) {
            Log.i("NitflexES", "[SERVER MEMORY] -> No remembered server for key=$key")
            return servers
        }

        val preferred = servers.filter { it.name == preferredName }
        if (preferred.isEmpty()) {
            Log.i("NitflexES", "[SERVER MEMORY] -> Remembered '$preferredName' not in current list ${servers.joinToString { it.name }}")
            return servers
        }

        Log.i("NitflexES", "[SERVER MEMORY] -> Trying remembered server first: '$preferredName' (key=$key)")
        return preferred + servers.filterNot { it.name == preferredName }
    }

    /**
     * Persists [server] as the working server for the current content, so it's tried first
     * next time. Called by the player once playback has actually started. Written
     * synchronously so it survives the player being closed right after playback begins
     * (Room is configured with allowMainThreadQueries).
     */
    fun rememberWorkingServer(server: Video.Server?) {
        val name = server?.name ?: return
        val key = contentKeyFor(lastVideoType) ?: return
        try {
            serverPreferenceDao?.save(
                ServerPreference(
                    tvShowId = key,
                    serverName = name,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            Log.i("NitflexES", "[SERVER MEMORY] -> Saved working server '$name' for key=$key")
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore salvataggio server preferito: ", e)
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            val video = UserPreferences.currentProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = UserPreferences.currentProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
		}
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            _state.emit(State.SuccessLoadingVideo(video, server))

            // A real playable video is now on screen; apply the auto English subtitle if
            // its search already finished.
            videoIsReady = true
            maybeApplyAutoEnglishSubtitle()
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        val season = (videoType as? Video.Type.Episode)?.season?.number
        val episode = (videoType as? Video.Type.Episode)?.number
        // The show's IMDb id (from TMDb enrichment) — the most reliable, language-independent
        // way to match subtitles for providers whose titles are localized (e.g. AniWorld's
        // German names). OpenSubtitles' legacy API wants the numeric id, so strip "tt".
        val imdbId = subtitleImdbId(videoType)?.removePrefix("tt")
        // Query variants: the raw title, a normalized one (brackets / audio tags stripped),
        // and the canonical English title resolved via TMDb — so localized/anime titles still
        // match providers that index English/romaji names. Resolved once, shared by both
        // searches below (the TMDb lookup would otherwise run twice).
        val queries = subtitleSearchTitles(videoType)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")

                // Run a general (all-language) search for the picker AND an explicit
                // English-only search, then merge + de-duplicate. Scoping to English surfaces
                // English tracks that a mixed-language result set can crowd out or omit.
                // Prefer the IMDb id (language-independent) and also try each title variant.
                val results = mutableListOf<OpenSubtitles.Subtitle>()
                // Resilient per call: one failing request must not wipe out the others.
                if (!imdbId.isNullOrBlank()) {
                    results += runCatching {
                        OpenSubtitles.search(imdbId = imdbId, season = season, episode = episode)
                    }.getOrDefault(emptyList())
                    results += runCatching {
                        OpenSubtitles.search(imdbId = imdbId, season = season, episode = episode, subLanguageId = "eng")
                    }.getOrDefault(emptyList())
                }
                for (q in queries) {
                    results += runCatching {
                        OpenSubtitles.search(query = q, season = season, episode = episode)
                    }.getOrDefault(emptyList())
                    results += runCatching {
                        OpenSubtitles.search(query = q, season = season, episode = episode, subLanguageId = "eng")
                    }.getOrDefault(emptyList())
                }

                val subtitles = results
                    .distinctBy { it.idSubtitleFile ?: it.subDownloadLink }
                    .sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))

                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))

                // Remember the best English track (most downloaded) so it can be enabled
                // automatically once the video is ready.
                pendingAutoEnglish = subtitles
                    .filter { it.isEnglish() }
                    .maxByOrNull { it.subDownloadsCnt?.toIntOrNull() ?: 0 }
                maybeApplyAutoEnglishSubtitle()
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val type = if (videoType is Video.Type.Episode) "tv" else "movie"

                // Search each title variant (SubDL has no IMDb-id param), explicitly asking
                // for English subtitles (previously no language was requested, so English was
                // often missing).
                val subtitles = queries
                    .flatMap { q ->
                        SubDL.search(
                            filmName = q,
                            seasonNumber = season,
                            episodeNumber = episode,
                            type = type,
                            languages = "EN",
                        )
                    }
                    .distinctBy { it.url ?: (it.releaseName ?: it.name) }

                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    /**
     * The show's IMDb id, when the provider (via TMDb enrichment) carries one. Used as the
     * primary, language-independent OpenSubtitles query — the reliable fix for providers
     * whose titles are localized (e.g. AniWorld's German names).
     */
    private fun subtitleImdbId(videoType: Video.Type): String? = when (videoType) {
        is Video.Type.Episode -> videoType.tvShow.imdbId
        is Video.Type.Movie -> videoType.imdbId
    }?.takeIf { it.isNotBlank() }

    /**
     * Title variants to query subtitle providers with. Returns the raw title, a normalized
     * form with bracketed/parenthetical segments and common Spanish audio tags removed, and
     * the canonical English title resolved via TMDb — so localized/anime titles (e.g. from
     * AniWorld or AnimeAV1) match OpenSubtitles/SubDL, which index English/romaji names
     * rather than localized ones. The TMDb lookup is skipped when TMDb is disabled.
     */
    private suspend fun subtitleSearchTitles(videoType: Video.Type): List<String> {
        val raw = when (videoType) {
            is Video.Type.Episode -> videoType.tvShow.title
            is Video.Type.Movie -> videoType.title
        }.trim()

        val normalized = raw
            .replace(Regex("[\\(\\[（【][^)\\]）】]*[)\\]）】]"), " ") // (…) […] （…） 【…】
            .replace(Regex("(?i)\\b(audio\\s+)?(latino|castellano|espa[nñ]ol|sub\\s*espa[nñ]ol)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Ask TMDb for the canonical English title from the (possibly localized) raw title.
        // Self-gated on the enableTmdb preference; failures fall back to title-only search.
        val englishTitle = runCatching {
            when (videoType) {
                is Video.Type.Episode -> TmdbUtils.getTvShow(raw, language = "en")?.title
                is Video.Type.Movie -> TmdbUtils.getMovie(raw, language = "en")?.title
            }
        }.getOrNull()?.trim()

        return listOfNotNull(
            raw.takeIf { it.isNotBlank() },
            normalized.takeIf { it.isNotBlank() && !it.equals(raw, ignoreCase = true) },
            englishTitle?.takeIf {
                it.isNotBlank() && !it.equals(raw, ignoreCase = true) && !it.equals(normalized, ignoreCase = true)
            },
        ).ifEmpty { listOf(raw) }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle, isAuto: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        // A manual pick should not be overridden by the automatic English selection.
        if (!isAuto) userPickedSubtitle = true
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        userPickedSubtitle = true
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null

    // Auto English subtitle state (reset per content in getServers).
    private var videoIsReady = false
    private var autoEnglishApplied = false
    private var userPickedSubtitle = false
    private var pendingAutoEnglish: OpenSubtitles.Subtitle? = null

    /**
     * Enables the best English subtitle automatically once both the video is playable and
     * the OpenSubtitles search has produced an English result. Skipped if the user has
     * already chosen a subtitle for this content, or if it was already applied.
     */
    private fun maybeApplyAutoEnglishSubtitle() {
        if (!videoIsReady) return
        if (autoEnglishApplied || userPickedSubtitle) return
        val subtitle = pendingAutoEnglish ?: return
        autoEnglishApplied = true
        Log.i("NitflexES", "[AUTO SUBS] -> Enabling English subtitle: ${subtitle.subFileName}")
        downloadSubtitle(subtitle, isAuto = true)
    }

    private fun OpenSubtitles.Subtitle.isEnglish(): Boolean {
        return languageName?.equals("English", ignoreCase = true) == true ||
                subLanguageID?.equals("eng", ignoreCase = true) == true ||
                iso639?.equals("en", ignoreCase = true) == true
    }

    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
