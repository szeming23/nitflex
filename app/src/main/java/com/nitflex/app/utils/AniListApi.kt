package com.nitflex.app.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight AniList GraphQL client.
 *
 * Usage:
 *   val data = AniListApi.fetch("Attack on Titan")
 *   val isAnime = AniListApi.isAnime(genres, originCountry, originalLanguage)
 *
 * Results are cached in-memory for the app session.
 */
object AniListApi {

    private const val API_URL = "https://graphql.anilist.co"
    private const val TAG = "AniListApi"

    private val cache = mutableMapOf<String, AniListData?>()

    private val QUERY = """
        query (${'$'}search: String, ${'$'}type: MediaType) {
          Media(search: ${'$'}search, type: ${'$'}type, sort: SEARCH_MATCH) {
            id
            idMal
            title { romaji english native }
            description(asHtml: false)
            coverImage { extraLarge large }
            bannerImage
            genres
            averageScore
            episodes
            status
            season
            seasonYear
            studios(isMain: true) { nodes { name } }
            startDate { year month }
            relations {
              edges {
                relationType
                node {
                  id type format
                  title { romaji english }
                  episodes
                  startDate { year month }
                  seasonYear
                }
              }
            }
          }
        }
    """.trimIndent()

    data class AniListData(
        val id: Int,
        val malId: Int?,
        val titleRomaji: String?,
        val titleEnglish: String?,
        val titleNative: String?,
        val description: String?,
        val coverImage: String?,
        val bannerImage: String?,
        val genres: List<String>,
        val averageScore: Int?,
        val episodes: Int?,
        val status: String?,
        val season: String?,
        val seasonYear: Int?,
        val studio: String?,
    )

    /**
     * Fetch AniList metadata for a title. Returns null if not found or on error.
     * Results are cached for the app session.
     */
    suspend fun fetch(title: String, type: String = "ANIME"): AniListData? {
        val key = "${type}__${title.lowercase().trim()}"
        if (cache.containsKey(key)) return cache[key]

        return withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("query", QUERY)
                    put("variables", JSONObject().apply {
                        put("search", title)
                        put("type", type)
                    })
                }.toString()

                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                conn.outputStream.use { it.write(body.toByteArray()) }
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val media = JSONObject(response)
                    .optJSONObject("data")
                    ?.optJSONObject("Media")

                val result = media?.let { m ->
                    val studioNode = m.optJSONObject("studios")
                        ?.optJSONArray("nodes")
                        ?.optJSONObject(0)
                    val genreArr = m.optJSONArray("genres")
                    val genres = (0 until (genreArr?.length() ?: 0)).map { genreArr!!.getString(it) }

                    AniListData(
                        id = m.optInt("id"),
                        malId = m.optInt("idMal").takeIf { it != 0 },
                        titleRomaji = m.optJSONObject("title")?.optString("romaji"),
                        titleEnglish = m.optJSONObject("title")?.optString("english"),
                        titleNative = m.optJSONObject("title")?.optString("native"),
                        description = cleanDescription(m.optString("description")),
                        coverImage = m.optJSONObject("coverImage")?.optString("extraLarge")
                            ?: m.optJSONObject("coverImage")?.optString("large"),
                        bannerImage = m.optString("bannerImage").takeIf { it.isNotEmpty() },
                        genres = genres,
                        averageScore = m.optInt("averageScore").takeIf { it != 0 },
                        episodes = m.optInt("episodes").takeIf { it != 0 },
                        status = m.optString("status").takeIf { it.isNotEmpty() },
                        season = m.optString("season").takeIf { it.isNotEmpty() },
                        seasonYear = m.optInt("seasonYear").takeIf { it != 0 },
                        studio = studioNode?.optString("name"),
                    )
                }

                cache[key] = result
                result
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for '$title': ${e.message}")
                null
            }
        }
    }

    /**
     * Returns true when the genre list contains "Animation", which is a prerequisite
     * for AniList enrichment. The AniList API call itself confirms if it's an anime series.
     * (We don't have reliable language/country data from all providers.)
     */
    fun isAnime(genreNames: List<String>): Boolean =
        genreNames.any { it.equals("Animation", ignoreCase = true) }

    private fun cleanDescription(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(Regex("<[^>]+>"), "")  // strip HTML tags
            .replace(Regex("\\(Source:[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\bNote:[^\n]*", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { null }
    }
}
