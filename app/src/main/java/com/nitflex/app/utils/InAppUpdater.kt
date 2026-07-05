package com.nitflex.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.nitflex.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.math.max

object InAppUpdater {

    // ─── Update source configuration ─────────────────────────────────────────
    // The in-app updater checks for new APK releases from the repo below.
    //
    // It is DISABLED by default so this fork does NOT push users toward the
    // upstream project's releases. To ship your OWN updates to your users, see
    // docs/SELF_HOSTED_UPDATES.md — in short:
    //   1) set UPDATES_ENABLED = true
    //   2) point UPDATE_OWNER / UPDATE_REPO at your own GitHub repo
    //      (or a GitHub-API-compatible endpoint; the base URL lives in GitHub.kt)
    const val UPDATES_ENABLED = false
    private const val UPDATE_OWNER = "streamflix-reborn"
    private const val UPDATE_REPO = "streamflix"
    // ─────────────────────────────────────────────────────────────────────────

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getReleaseUpdate(): GitHub.Release? {
        if (!UPDATES_ENABLED) return null
        val latestRelease = GitHub.Releases.getLatestRelease(UPDATE_OWNER, UPDATE_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        if (!UPDATES_ENABLED) return emptyList()
        val releases = GitHub.Releases.getReleases(UPDATE_OWNER, UPDATE_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }

        return newReleases
    }

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}"
            )
        }

        withContext(Dispatchers.IO) {
            URL(asset.browserDownloadUrl).openStream()
        }.use { input ->
            FileOutputStream(apk).use { output -> input.copyTo(output) }
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.data = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(uri.path!!)
            )
        }
        context.startActivity(intent)
    }
}