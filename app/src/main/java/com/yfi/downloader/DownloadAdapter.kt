package com.yfi.downloader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class DownloadAdapter(
    private val onPauseResume: (DownloadItem) -> Unit,
    private val onCancel: (DownloadItem) -> Unit
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumbnail: ImageView = itemView.findViewById(R.id.ivItemThumbnail)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvItemTitle)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvItemStatus)
        private val progress: ProgressBar = itemView.findViewById(R.id.itemProgress)
        private val btnPause: ImageButton = itemView.findViewById(R.id.btnItemPause)
        private val btnCancel: ImageButton = itemView.findViewById(R.id.btnItemCancel)

        fun bind(item: DownloadItem) {
            tvTitle.text = item.title.ifBlank { item.url }

            if (!item.thumbnailUrl.isNullOrBlank()) {
                Glide.with(itemView.context)
                    .load(item.thumbnailUrl)
                    .placeholder(R.drawable.ic_video_placeholder)
                    .error(R.drawable.ic_video_placeholder)
                    .into(ivThumbnail)
            } else {
                ivThumbnail.setImageResource(R.drawable.ic_video_placeholder)
            }

            tvStatus.text = when (item.status) {
                DownloadStatus.QUEUED -> "Queued"
                DownloadStatus.DOWNLOADING -> "${item.progress.toInt()}% • ${item.statusLine.take(40)}"
                DownloadStatus.PAUSED -> "Paused"
                DownloadStatus.COMPLETED -> "Completed ✓"
                DownloadStatus.FAILED -> "Failed: ${item.statusLine.take(40)}"
                DownloadStatus.CANCELLED -> "Cancelled"
            }
            progress.progress = item.progress.toInt()

            when (item.status) {
                DownloadStatus.DOWNLOADING -> {
                    btnPause.visibility = View.VISIBLE
                    btnPause.isEnabled = true
                    btnPause.setImageResource(android.R.drawable.ic_media_pause)
                    btnPause.setOnClickListener { onPauseResume(item) }
                }
                DownloadStatus.PAUSED -> {
                    btnPause.visibility = View.VISIBLE
                    btnPause.isEnabled = true
                    btnPause.setImageResource(android.R.drawable.ic_media_play)
                    btnPause.setOnClickListener { onPauseResume(item) }
                }
                DownloadStatus.QUEUED -> {
                    btnPause.visibility = View.INVISIBLE
                    btnPause.isEnabled = false
                    btnPause.setOnClickListener(null)
                }
                else -> {
                    btnPause.visibility = View.INVISIBLE
                    btnPause.isEnabled = false
                    btnPause.setOnClickListener(null)
                }
            }
            btnCancel.setOnClickListener { onCancel(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem) =
                oldItem == newItem
        }
    }
}