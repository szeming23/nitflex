package com.nitflex.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nitflex.app.models.ServerPreference

@Dao
interface ServerPreferenceDao {

    @Query("SELECT * FROM server_preferences WHERE tvShowId = :tvShowId")
    fun getByTvShowId(tvShowId: String): ServerPreference?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(preference: ServerPreference)

    @Query("DELETE FROM server_preferences")
    fun deleteAll()
}
