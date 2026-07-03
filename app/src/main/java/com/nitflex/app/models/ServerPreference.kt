package com.nitflex.app.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Remembers which streaming server last played successfully for a given TV show,
 * so the player can try that server first for subsequent episodes instead of
 * looping through every server again. Stored per-provider (each provider has its
 * own database file).
 */
@Entity("server_preferences")
class ServerPreference(
    @PrimaryKey
    var tvShowId: String = "",
    var serverName: String = "",
    var updatedAt: Long = 0L,
)
