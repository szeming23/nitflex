package com.nitflex.app.fragments.settings.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nitflex.app.BuildConfig
import com.nitflex.app.R
import com.nitflex.app.utils.GitHub
import kotlinx.coroutines.launch

class SettingsAboutMobileFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about_mobile, rootKey)

        displaySettingsAbout()
    }

    private fun displaySettingsAbout() {
        findPreference<Preference>("p_settings_about_version")?.apply {
            summary = getString(R.string.settings_about_version_name, BuildConfig.VERSION_NAME)
        }

        findPreference<Preference>("p_settings_check_upstream_updates")?.apply {
            setOnPreferenceClickListener {
                checkUpstreamUpdate(this)
                true
            }
        }
    }

    private fun checkUpstreamUpdate(preference: Preference) {
        val defaultSummary = getString(R.string.settings_check_upstream_updates_summary)
        preference.summary = getString(R.string.settings_check_upstream_updates_checking)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val release = GitHub.Releases.getLatestRelease("streamflix-reborn", "streamflix")
                val publishedDate = release.publishedAt?.substringBefore("T") ?: ""
                val notes = release.body.orEmpty().let {
                    if (it.length > 500) it.take(500) + "…" else it
                }

                if (isAdded) {
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.settings_check_upstream_updates_result_title)
                        .setMessage(
                            getString(
                                R.string.settings_check_upstream_updates_available,
                                release.tagName,
                                publishedDate,
                                notes,
                            )
                        )
                        .setPositiveButton(R.string.settings_check_upstream_updates_view_release) { _, _ ->
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                preference.summary = getString(
                    R.string.settings_check_upstream_updates_summary_checked,
                    release.tagName,
                )
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.settings_check_upstream_updates_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                preference.summary = defaultSummary
            }
        }
    }
}
