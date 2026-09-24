package com.yfi.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import android.content.Intent
import androidx.core.content.ContextCompat

object DownloadQueueManager {

    const val QUEUE_MODE_ONE_BY_ONE = 0
    const val QUEUE_MODE_ALL_AT_ONCE = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private const val TAG = "DownloadQueueManager"

    @Volatile
    private var downloader: VideoDownloader? = null

    @Volatile
    private var appContext: Context? = null

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue

    private val _queueMode = MutableStateFlow(QUEUE_MODE_ONE_BY_ONE)
    val queueMode: StateFlow<Int> = _queueMode

    private val activeJobs = mutableMapOf<String, Job>()
    private val pauseRequested = mutableSetOf<String>()

    private const val CHANNEL_ID = "yfi_downloads"
    private const val NOTIFICATION_ID = 1

    fun init(context: Context) {
        appContext = context.applicationContext
        if (downloader == null) {
            downloader = VideoDownloader(context.applicationContext)
        }
        createNotificationChannel()
    }

    private fun ensureInit(): Boolean {
        return appContext != null && downloader != null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ctx = appContext ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active download progress"
                setShowBadge(false)
            }
            val manager = ctx.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    fun startForegroundServiceSafely() {
        val ctx = appContext ?: return
        val serviceIntent = Intent(ctx, DownloadService::class.java)
        try {
            ContextCompat.startForegroundService(ctx, serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    fun setQueueMode(mode: Int) {
        _queueMode.value = mode
        processQueue()
    }

    fun addToQueue(
        url: String,
        title: String,
        thumbnailUrl: String,
        platform: Int,
        mode: Int,
        maxHeight: Int,
        saveLocation: Int
    ): String {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            url = url,
            title = title,
            thumbnailUrl = thumbnailUrl,
            platform = platform,
            mode = mode,
            maxHeight = maxHeight,
            saveLocation = saveLocation
        )
        _queue.value = _queue.value + item
        startForegroundServiceSafely()
        processQueue()
        return item.id
    }

    fun pauseItem(id: String) {
        val item = _queue.value.find { it.id == id } ?: return
        if (item.status != DownloadStatus.DOWNLOADING) return

        pauseRequested.add(id)
        updateItem(id) { it.copy(status = DownloadStatus.PAUSED, statusLine = "Paused") }
        item.processId?.let { downloader?.cancelDownload(it) }
        updateNotification()
    }

    fun resumeItem(id: String) {
        val item = _queue.value.find { it.id == id } ?: return
        if (item.status != DownloadStatus.PAUSED) return

        pauseRequested.remove(id)
        updateItem(id) { it.copy(status = DownloadStatus.QUEUED, progress = 0f, statusLine = "Resuming...") }
        processQueue()
    }

    fun cancelItem(id: String) {
        val item = _queue.value.find { it.id == id } ?: return
        pauseRequested.remove(id)
        item.processId?.let { downloader?.cancelDownload(it) }
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        _queue.value = _queue.value.filter { it.id != id }
        updateNotification()
        processQueue()
    }

    fun clearCompleted() {
        _queue.value = _queue.value.filter {
            it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.PAUSED
        }
        updateNotification()
    }

    fun clearAll() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        _queue.value.forEach { item ->
            item.processId?.let { downloader?.cancelDownload(it) }
        }
        _queue.value = emptyList()
        updateNotification()
    }

    private fun processQueue() {
        scope.launch {
            queueMutex.withLock {
                val queued = _queue.value.filter { it.status == DownloadStatus.QUEUED }
                if (queued.isEmpty()) return@withLock

                startForegroundServiceSafely()

                if (_queueMode.value == QUEUE_MODE_ONE_BY_ONE) {
                    if (activeJobs.isNotEmpty()) return@withLock
                    startDownload(queued.first())
                } else {
                    queued.forEach { item ->
                        if (!activeJobs.containsKey(item.id)) {
                            startDownload(item)
                        }
                    }
                }
            }
        }
    }

    private fun startDownload(item: DownloadItem) {
        val dl = downloader
        if (dl == null) {
            updateItem(item.id) {
                it.copy(status = DownloadStatus.FAILED, statusLine = "Initializing…")
            }
            return
        }

        val processId = VideoDownloader.generateProcessId()
        updateItem(item.id) {
            it.copy(status = DownloadStatus.DOWNLOADING, processId = processId)
        }

        val job = scope.launch {
            try {
                dl.downloadVideo(
                    url = item.url,
                    platform = item.platform,
                    mode = item.mode,
                    maxHeight = item.maxHeight,
                    processId = processId,
                    saveLocation = item.saveLocation,
                    onProgress = { progress ->
                        if (!pauseRequested.contains(item.id)) {
                            updateItem(item.id) {
                                it.copy(
                                    progress = progress.percentage,
                                    statusLine = progress.statusLine
                                )
                            }
                            updateNotification()
                        }
                    },
                    onComplete = { filePath ->
                        if (pauseRequested.remove(item.id)) {
                            // Paused - do nothing
                        } else {
                            // ✅ FIX: Call the robust rename function
                            val renamedPath = renameDownloadedFile(filePath, item.title)

                            updateItem(item.id) {
                                it.copy(
                                    status = DownloadStatus.COMPLETED,
                                    progress = 100f,
                                    savedPath = renamedPath,
                                    statusLine = "Completed"
                                )
                            }
                        }
                        activeJobs.remove(item.id)
                        updateNotification()
                        processQueue()
                    },
                    onError = { e ->
                        if (pauseRequested.remove(item.id)) {
                            // Paused - do nothing
                        } else {
                            updateItem(item.id) {
                                it.copy(
                                    status = DownloadStatus.FAILED,
                                    statusLine = e.message ?: "Error"
                                )
                            }
                        }
                        activeJobs.remove(item.id)
                        updateNotification()
                        processQueue()
                    }
                )
            } catch (e: CancellationException) {
                activeJobs.remove(item.id)
                throw e
            } catch (e: Exception) {
                if (!pauseRequested.remove(item.id)) {
                    updateItem(item.id) {
                        it.copy(status = DownloadStatus.FAILED, statusLine = e.message ?: "Error")
                    }
                }
                activeJobs.remove(item.id)
                updateNotification()
                processQueue()
            }
        }
        activeJobs[item.id] = job
    }

    // ✅ BULLETPROOF RENAME: Retries for up to 10 seconds to handle Android file locks
    private fun renameDownloadedFile(originalPath: String, newTitle: String): String {
        val originalFile = File(originalPath)
        if (!originalFile.exists()) {
            Log.e(TAG, "Original file does not exist: $originalPath")
            return originalPath
        }

        // Sanitize title (remove illegal characters)
        var safeTitle = newTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (safeTitle.isBlank()) return originalPath

        // Truncate to avoid path length limits
        if (safeTitle.length > 80) {
            safeTitle = safeTitle.take(80).trim()
        }

        val extension = originalFile.extension
        var newFile = File(originalFile.parent, "$safeTitle.$extension")

        // Handle duplicate filenames
        var counter = 1
        while (newFile.exists()) {
            newFile = File(originalFile.parent, "$safeTitle ($counter).$extension")
            counter++
        }

        // ✅ RETRY LOOP: Try for 10 seconds to defeat Android's file lock
        var attempts = 0
        while (attempts < 20) { // 20 * 500ms = 10 seconds
            try {
                if (originalFile.renameTo(newFile)) {
                    Log.d(TAG, "Successfully renamed to: ${newFile.absolutePath}")
                    appContext?.let { ctx ->
                        MediaScannerConnection.scanFile(ctx, arrayOf(originalFile.absolutePath), null, null)
                        MediaScannerConnection.scanFile(ctx, arrayOf(newFile.absolutePath), null, null)
                    }
                    return newFile.absolutePath
                }
            } catch (e: Exception) {
                // Ignore and retry
            }
            attempts++
            try { Thread.sleep(500) } catch (_: InterruptedException) { break }
        }

        // Fallback: Copy + Delete if renameTo completely fails
        try {
            Log.w(TAG, "renameTo failed after 10s. Attempting copy+delete fallback.")
            originalFile.copyTo(newFile, overwrite = true)
            if (originalFile.delete()) {
                Log.d(TAG, "Fallback copy+delete successful: ${newFile.absolutePath}")
                appContext?.let { ctx ->
                    MediaScannerConnection.scanFile(ctx, arrayOf(newFile.absolutePath), null, null)
                }
                return newFile.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback copy+delete also failed", e)
        }

        return originalPath
    }

    private fun updateItem(id: String, transform: (DownloadItem) -> DownloadItem) {
        _queue.value = _queue.value.map {
            if (it.id == id) transform(it) else it
        }
    }

    private fun updateNotification() {
        val ctx = appContext ?: return
        if (!ensureInit()) return

        val downloading = _queue.value.filter { it.status == DownloadStatus.DOWNLOADING }
        val queued = _queue.value.filter { it.status == DownloadStatus.QUEUED }
        val manager = NotificationManagerCompat.from(ctx)

        if (downloading.isEmpty() && queued.isEmpty()) {
            val serviceIntent = Intent(ctx, DownloadService::class.java)
            ctx.stopService(serviceIntent)
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("YFI Downloader")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (downloading.size == 1) {
            val item = downloading.first()
            builder.setContentText("${item.progress.toInt()}% • ${item.statusLine.take(40)}")
            builder.setProgress(100, item.progress.toInt(), false)
        } else if (downloading.isNotEmpty()) {
            val avg = downloading.map { it.progress }.average().toInt()
            builder.setContentText("${downloading.size} downloads in progress")
            builder.setProgress(100, avg, false)
        } else {
            builder.setContentText("Waiting in queue...")
            builder.setProgress(0, 0, true)
        }

        try {
            manager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) { }
    }
}