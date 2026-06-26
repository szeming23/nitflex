package com.nitflex.app.utils

import java.util.Calendar

enum class SeasonalEvent {
    PRIDE,
    CHRISTMAS,
    NEW_YEAR,
    HALLOWEEN;

    companion object {
        fun current(): SeasonalEvent? {
            val cal = Calendar.getInstance()
            val month = cal.get(Calendar.MONTH) // 0-based
            val day = cal.get(Calendar.DAY_OF_MONTH)
            return when {
                month == 5 -> PRIDE                             // June
                month == 11 && day >= 20 -> CHRISTMAS           // Dec 20–31
                month == 0 && day <= 5 -> NEW_YEAR              // Jan 1–5
                month == 9 && day >= 24 -> HALLOWEEN            // Oct 24–31
                else -> null
            }
        }
    }
}
