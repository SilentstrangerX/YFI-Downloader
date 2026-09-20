package com.yfi.downloader

data class DownloadProgress(
    val percentage: Float,
    val etaInSeconds: Long,
    val statusLine: String
)