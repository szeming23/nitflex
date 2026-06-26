package com.nitflex.app.models

import com.nitflex.app.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
