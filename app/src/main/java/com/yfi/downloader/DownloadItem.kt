package com.yfi.downloader

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String = "",
    val thumbnailUrl: String = "",
    val platform: Int,
    val mode: Int,
    val maxHeight: Int,
    val saveLocation: Int,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val statusLine: String = "",
    val savedPath: String? = null,
    val processId: String? = null
)

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
}