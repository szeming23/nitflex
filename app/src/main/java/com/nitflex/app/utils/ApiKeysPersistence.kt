package com.nitflex.app.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONObject
import java.io.File

object ApiKeysPersistence {

    private const val TAG = "ApiKeysPersistence"
    private const val FOLDER = "Nitflex"
    private const val FILE_NAME = "nitflex_keys.json"
    private const val KEY_TMDB = "tmdb_api_key"
    private const val KEY_SUBDL = "subdl_api_key"

    fun save(context: Context) {
        val appContext = context.applicationContext
        Thread {
            try {
                val json = JSONObject().apply {
                    put(KEY_TMDB, UserPreferences.tmdbApiKey)
                    put(KEY_SUBDL, UserPreferences.subdlApiKey)
                }.toString(2)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveViaMediaStore(appContext, json)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
                    dir.mkdirs()
                    File(dir, FILE_NAME).writeText(json)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save API keys to local file", e)
            }
        }.start()
    }

    fun load(context: Context) {
        try {
            val json = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                loadViaMediaStore(context)
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "$FOLDER/$FILE_NAME"
                )
                if (file.exists()) file.readText() else null
            } ?: return

            val obj = JSONObject(json)
            if (UserPreferences.tmdbApiKey.isEmpty()) {
                val key = obj.optString(KEY_TMDB, "")
                if (key.isNotEmpty()) UserPreferences.tmdbApiKey = key
            }
            if (UserPreferences.subdlApiKey.isEmpty()) {
                val key = obj.optString(KEY_SUBDL, "")
                if (key.isNotEmpty()) UserPreferences.subdlApiKey = key
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load API keys from local file", e)
        }
    }

    private fun saveViaMediaStore(context: Context, json: String) {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

        // Check if file already exists — overwrite it
        val existingUri = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.net.Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            } else null
        }

        val targetUri = existingUri ?: run {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } ?: return

        context.contentResolver.openOutputStream(targetUri, "wt")?.use { it.writer().write(json) }
    }

    private fun loadViaMediaStore(context: Context): String? {
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

        val fileUri = context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(FILE_NAME, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                android.net.Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
            } else null
        } ?: return null

        return context.contentResolver.openInputStream(fileUri)?.use { it.reader().readText() }
    }
}
