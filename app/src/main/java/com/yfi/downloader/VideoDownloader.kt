package com.yfi.downloader

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoDownloader(private val context: Context) {

    suspend fun downloadVideo(
        url: String,
        platform: Int,
        mode: Int,
        maxHeight: Int,
        processId: String,
        saveLocation: Int = SAVE_DEFAULT,
        onProgress: (DownloadProgress) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Exception) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

            val baseDir: File = when (saveLocation) {
                SAVE_MOVIES -> File(downloadsRoot, "Movies")
                SAVE_MUSIC -> File(downloadsRoot, "Music")
                SAVE_INSTAGRAM -> File(downloadsRoot, "Instagram")
                SAVE_FACEBOOK -> File(downloadsRoot, "Facebook")
                else -> downloadsRoot
            }

            if (!baseDir.exists()) baseDir.mkdirs()

            val downloadDir = baseDir

            val request = YoutubeDLRequest(url)
            request.addOption("--retries", "10")
            request.addOption("--restrict-filenames")
            request.addOption("--trim-filenames", "100")
            request.addOption(
                "-o",
                "${downloadDir.absolutePath}/%(title).80s.%(ext)s"
            )

            val silentCookies = File(downloadDir, "cookies.txt")
            if (silentCookies.exists()) {
                request.addOption("--cookies", silentCookies.absolutePath)
            }

            val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"

            when (platform) {
                PLATFORM_FACEBOOK -> {
                    request.addOption("--user-agent", mobileUserAgent)
                    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                    request.addOption("-f", "best[ext=mp4]/best")
                }
                PLATFORM_INSTAGRAM -> {
                    request.addOption("--user-agent", mobileUserAgent)
                    request.addOption("--add-header", "Accept-Language:en-US,en;q=0.9")
                    request.addOption("-f", "best[ext=mp4]/best")
                    request.addOption("--no-playlist")
                }
                else -> {
                    if (mode == DOWNLOAD_MODE_MUSIC) {
                        request.addOption("-f", "ba/b")
                        request.addOption("-x")
                        request.addOption("--audio-format", "mp3")
                        request.addOption("--audio-quality", "0")
                    } else {
                        val formatString = if (maxHeight <= 0) {
                            "bv*+ba/b"
                        } else {
                            "bv*[height<=$maxHeight][ext=mp4]+ba[ext=m4a]/" +
                                    "bv*[height<=$maxHeight]+ba/" +
                                    "b[height<=$maxHeight]/b"
                        }
                        request.addOption("-f", formatString)
                        request.addOption("--merge-output-format", "mp4")
                    }
                }
            }

            val response = YoutubeDL.getInstance().execute(
                request,
                processId
            ) { progress: Float, etaInSeconds: Long, line: String ->
                onProgress(DownloadProgress(progress, etaInSeconds, line))
            }

            if (response.exitCode == 0) {
                var downloadedFile = findLatestFile(downloadDir, mode, platform)
                if (downloadedFile != null) {
                    val originalName = downloadedFile.name
                    var baseName = originalName.substringBeforeLast('.')
                    val ext = originalName.substringAfterLast('.')
                    baseName = baseName.replace(Regex("\\s*\\[\\d+p\\]"), "")
                    baseName = baseName.replace(Regex("\\s*\\[Best\\]"), "")
                    baseName = baseName.replace(Regex("\\s*\\[Music\\]"), "")
                    baseName = baseName.replace(Regex("\\s*\\[Facebook\\]"), "")
                    baseName = baseName.replace(Regex("\\s*\\[Instagram\\]"), "")

                    val tag = when {
                        platform == PLATFORM_FACEBOOK -> "[Facebook]"
                        platform == PLATFORM_INSTAGRAM -> "[Instagram]"
                        mode == DOWNLOAD_MODE_MUSIC -> "[Music]"
                        maxHeight <= 0 -> "[Best]"
                        else -> "[${maxHeight}p]"
                    }
                    val newName = "$baseName $tag.$ext"
                    val newFile = File(downloadDir, newName)
                    if (downloadedFile.renameTo(newFile)) downloadedFile = newFile
                }

                downloadedFile?.let { file ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        null
                    ) { _, _ -> }
                }

                onComplete(downloadedFile?.absolutePath ?: downloadDir.absolutePath)
            } else {
                onError(Exception("Download failed with exit code ${response.exitCode}.\nOutput: ${response.out}"))
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    fun cancelDownload(processId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (_: Exception) { }
    }

    private fun findLatestFile(dir: File, mode: Int, platform: Int): File? {
        val targetExtension = if (mode == DOWNLOAD_MODE_MUSIC && platform == PLATFORM_YOUTUBE) "mp3" else "mp4"
        return dir.listFiles()
            ?.filter { it.isFile && it.extension.equals(targetExtension, ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }

    companion object {
        const val PLATFORM_YOUTUBE = 0
        const val PLATFORM_FACEBOOK = 1
        const val PLATFORM_INSTAGRAM = 2

        const val DOWNLOAD_MODE_VIDEO = 0
        const val DOWNLOAD_MODE_MUSIC = 1

        const val SAVE_DEFAULT = 0
        const val SAVE_MOVIES = 1
        const val SAVE_MUSIC = 2
        const val SAVE_INSTAGRAM = 3
        const val SAVE_FACEBOOK = 4

        fun generateProcessId(): String = "download-${System.currentTimeMillis()}"
    }
}