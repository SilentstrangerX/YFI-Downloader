# YFI Downloader

A native Android app to download videos from **YouTube**, **Facebook**, and **Instagram** — up to 4K resolution with merged audio.

## Features

- 🎥 YouTube, Facebook, and Instagram support
- 📺 Resolution picker (360p → 4K)
- 🎵 MP3 music extraction mode
- ⚡ Queue system (one-by-one or all-at-once)
- ⏸️ Pause / Resume / Cancel per download
- 🌙 Dark and Light mode
- 📁 Save to Downloads, Movies, Music, Instagram, or Facebook
- 🔔 Progress notifications in status bar
- 🖼️ Auto thumbnail & title preview
- 🔄 Auto-updating yt-dlp engine
- 📱 Mobile-friendly user agent (bypasses IG/FB blocks)

## Tech Stack

- **Language:** Kotlin
- **Downloader:** [youtubedl-android](https://github.com/yausername/youtubedl-android) (yt-dlp wrapper)
- **Media merging:** FFmpeg
- **Image loading:** Glide
- **UI:** Material Design Components, CardView, RecyclerView

## Build Requirements

- Android Studio Hedgehog (2023.1) or newer
- JDK 17
- Android SDK 34

## Getting Started

```bash
git clone https://github.com/SilentstrangerX/YFI-Downloader.git
cd YFI-Downloader
