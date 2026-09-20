package com.yfi.downloader

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException

class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        applySavedTheme()
        initYoutubeDL()
        DownloadQueueManager.init(this)
    }

    private fun applySavedTheme() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val themeMode = prefs.getInt(KEY_THEME, THEME_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(
            when (themeMode) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun initYoutubeDL() {
        try {
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)
            Log.d(TAG, "YoutubeDL and FFmpeg initialized successfully")
            updateYtDlpInBackground()
        } catch (e: YoutubeDLException) {
            Log.e(TAG, "Failed to initialize YoutubeDL", e)
        }
    }

    private fun updateYtDlpInBackground() {
        Thread {
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(
                    this,
                    YoutubeDL.UpdateChannel.STABLE
                )
                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> {
                        val version = YoutubeDL.getInstance().version(this)
                        Log.d(TAG, "yt-dlp updated to: $version")
                    }
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> {
                        Log.d(TAG, "yt-dlp is already up to date")
                    }
                    else -> {
                        Log.d(TAG, "yt-dlp update finished with status: $status")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update yt-dlp", e)
            }
        }.start()
    }

    companion object {
        private const val TAG = "MyApp"
        const val PREFS_NAME = "yfi_prefs"
        const val KEY_THEME = "theme_mode"
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}