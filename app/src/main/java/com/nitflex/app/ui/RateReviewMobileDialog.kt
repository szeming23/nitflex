package com.nitflex.app.ui

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nitflex.app.R
import com.nitflex.app.adapters.AppAdapter
import com.nitflex.app.database.AppDatabase
import com.nitflex.app.databinding.DialogRateReviewMobileBinding
import com.nitflex.app.models.Movie
import com.nitflex.app.models.TvShow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Bottom sheet to set a 1-10 score and optional comment on a Movie or TV Show.
 * Powers the "Reviewed" bucket in My List.
 */
class RateReviewMobileDialog(
    context: Context,
    private val show: AppAdapter.Item,
) : BottomSheetDialog(context) {

    private val binding = DialogRateReviewMobileBinding.inflate(LayoutInflater.from(context))

    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)

    init {
        setContentView(binding.root)

        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        val title = when (show) {
            is Movie -> show.title
            is TvShow -> show.title
            else -> ""
        }
        val existingRating = when (show) {
            is Movie -> show.userRating
            is TvShow -> show.userRating
            else -> null
        }
        val existingReview = when (show) {
            is Movie -> show.userReview
            is TvShow -> show.userReview
            else -> null
        }

        binding.tvRateShowTitle.text = title
        binding.etRateComment.setText(existingReview ?: "")

        // SeekBar 0..9 maps to score 1..10.
        binding.sbRateScore.progress = (existingRating ?: 7) - 1
        updateScoreLabel(binding.sbRateScore.progress + 1)
        binding.sbRateScore.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateScoreLabel(progress + 1)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnRateRemove.visibility = if (existingRating != null) View.VISIBLE else View.GONE

        binding.btnRateSave.setOnClickListener {
            val rating = binding.sbRateScore.progress + 1
            val review = binding.etRateComment.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            saveReview(rating, review)
            dismiss()
        }

        binding.btnRateRemove.setOnClickListener {
            saveReview(null, null)
            dismiss()
        }
    }

    private fun updateScoreLabel(score: Int) {
        binding.tvRateScore.text = context.getString(R.string.rate_review_score, score)
    }

    private fun saveReview(rating: Int?, review: String?) {
        val db = database
        val show = show
        // Fire-and-forget on a background scope so the write is never skipped just
        // because the Activity couldn't be resolved from the dialog's context.
        CoroutineScope(Dispatchers.IO).launch {
            when (show) {
                is Movie -> db.movieDao().upsertReview(show, rating, review)
                is TvShow -> db.tvShowDao().upsertReview(show, rating, review)
            }
            UserDataNotifier.notifyChanged()
        }
    }
}
