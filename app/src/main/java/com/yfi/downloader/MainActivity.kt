package com.yfi.downloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var tvUrlLabel: TextView
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var btnPaste: Button
    private lateinit var spinnerMode: Spinner
    private lateinit var spinnerResolution: Spinner
    private lateinit var spinnerSaveLocation: Spinner
    private lateinit var spinnerQueueMode: Spinner
    private lateinit var tvResolutionLabel: TextView
    private lateinit var btnAddToQueue: Button
    private lateinit var btnClearCompleted: Button
    private lateinit var recyclerQueue: RecyclerView
    private lateinit var adapter: DownloadAdapter

    private var loadingJob: Job? = null

    private val modeLabels = listOf("Video", "Music (MP3)")

    private val resolutionLabels = listOf(
        "360p", "480p", "720p (HD)", "1080p (Full HD)",
        "1440p (2K)", "2160p (4K)",
        "Best Available (Recommended for IG & FB)"
    )
    private val resolutionHeights = listOf(360, 480, 720, 1080, 1440, 2160, 0)

    private val saveLocationLabels = listOf(
        "Downloads (Default)",
        "Downloads / Movies",
        "Downloads / Music",
        "Downloads / Instagram",
        "Downloads / Facebook"
    )
    private val saveLocationValues = listOf(
        VideoDownloader.SAVE_DEFAULT,
        VideoDownloader.SAVE_MOVIES,
        VideoDownloader.SAVE_MUSIC,
        VideoDownloader.SAVE_INSTAGRAM,
        VideoDownloader.SAVE_FACEBOOK
    )

    private val queueModeLabels = listOf("One by One", "Download All at Once")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        tvUrlLabel = findViewById(R.id.tvUrlLabel)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        btnPaste = findViewById(R.id.btnPaste)
        spinnerMode = findViewById(R.id.spinnerMode)
        spinnerResolution = findViewById(R.id.spinnerResolution)
        spinnerSaveLocation = findViewById(R.id.spinnerSaveLocation)
        spinnerQueueMode = findViewById(R.id.spinnerQueueMode)
        tvResolutionLabel = findViewById(R.id.tvResolutionLabel)
        btnAddToQueue = findViewById(R.id.btnAddToQueue)
        btnClearCompleted = findViewById(R.id.btnClearCompleted)
        recyclerQueue = findViewById(R.id.recyclerQueue)

        adapter = DownloadAdapter(
            onPauseResume = { item ->
                if (item.status == DownloadStatus.DOWNLOADING) {
                    DownloadQueueManager.pauseItem(item.id)
                } else if (item.status == DownloadStatus.PAUSED) {
                    DownloadQueueManager.resumeItem(item.id)
                }
            },
            onCancel = { item -> DownloadQueueManager.cancelItem(item.id) }
        )
        recyclerQueue.layoutManager = LinearLayoutManager(this)
        recyclerQueue.adapter = adapter

        lifecycleScope.launch {
            DownloadQueueManager.queue.collect { items -> adapter.submitList(items) }
        }

        updateThemeIcon()
        btnThemeToggle.setOnClickListener {
            val prefs = getSharedPreferences(MyApp.PREFS_NAME, MODE_PRIVATE)
            val current = prefs.getInt(MyApp.KEY_THEME, MyApp.THEME_SYSTEM)
            val isCurrentlyDark = when (current) {
                MyApp.THEME_DARK -> true
                MyApp.THEME_LIGHT -> false
                else -> isSystemDark()
            }
            val newMode = if (isCurrentlyDark) MyApp.THEME_LIGHT else MyApp.THEME_DARK
            prefs.edit().putInt(MyApp.KEY_THEME, newMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (newMode == MyApp.THEME_DARK) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        btnPaste.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                if (text.isNotEmpty()) {
                    etUrl.setText(text)
                    etUrl.setSelection(text.length)
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        val modeAdapter = ArrayAdapter(this, R.layout.spinner_item, modeLabels)
        modeAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerMode.adapter = modeAdapter
        spinnerMode.setSelection(0)

        val resAdapter = ArrayAdapter(this, R.layout.spinner_item, resolutionLabels)
        resAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerResolution.adapter = resAdapter
        spinnerResolution.setSelection(3)

        val saveAdapter = ArrayAdapter(this, R.layout.spinner_item, saveLocationLabels)
        saveAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerSaveLocation.adapter = saveAdapter
        spinnerSaveLocation.setSelection(0)

        val queueAdapter = ArrayAdapter(this, R.layout.spinner_item, queueModeLabels)
        queueAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinnerQueueMode.adapter = queueAdapter
        spinnerQueueMode.setSelection(0)
        spinnerQueueMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                DownloadQueueManager.setQueueMode(
                    if (position == 0) DownloadQueueManager.QUEUE_MODE_ONE_BY_ONE
                    else DownloadQueueManager.QUEUE_MODE_ALL_AT_ONCE
                )
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == VideoDownloader.DOWNLOAD_MODE_MUSIC) {
                    tvResolutionLabel.visibility = View.GONE
                    spinnerResolution.visibility = View.GONE
                } else {
                    tvResolutionLabel.visibility = View.VISIBLE
                    spinnerResolution.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        requestPermissionsIfNeeded()

        btnAddToQueue.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isEmpty()) {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addToQueue(url)
        }

        btnClearCompleted.setOnClickListener {
            DownloadQueueManager.clearCompleted()
            Toast.makeText(this, "Cleared completed downloads", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isSystemDark(): Boolean {
        return (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun updateThemeIcon() {
        val prefs = getSharedPreferences(MyApp.PREFS_NAME, MODE_PRIVATE)
        val mode = prefs.getInt(MyApp.KEY_THEME, MyApp.THEME_SYSTEM)
        val isDark = when (mode) {
            MyApp.THEME_DARK -> true
            MyApp.THEME_LIGHT -> false
            else -> isSystemDark()
        }
        btnThemeToggle.setImageResource(
            if (isDark) R.drawable.ic_sun else R.drawable.ic_moon
        )
    }

    private fun startAddingAnimation() {
        loadingJob?.cancel()
        loadingJob = lifecycleScope.launch {
            var dots = 0
            while (true) {
                val suffix = ".".repeat(dots)
                tvUrlLabel.text = "🔗  Video URL (adding to queue$suffix)"
                dots = (dots + 1) % 4
                delay(400)
            }
        }
    }

    private fun stopAddingAnimation() {
        loadingJob?.cancel()
        loadingJob = null
        tvUrlLabel.text = "🔗  Video URL"
    }

    private fun addToQueue(url: String) {
        val mode = spinnerMode.selectedItemPosition
        val maxHeight = if (mode == VideoDownloader.DOWNLOAD_MODE_MUSIC) 0
        else resolutionHeights[spinnerResolution.selectedItemPosition]
        val saveLocation = saveLocationValues[spinnerSaveLocation.selectedItemPosition]

        btnAddToQueue.isEnabled = false
        startAddingAnimation()

        lifecycleScope.launch {
            var title = ""
            var thumb = ""
            try {
                val info = withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().getInfo(url)
                }
                title = info.title ?: ""
                thumb = info.thumbnail ?: ""
            } catch (_: Exception) {
            }

            DownloadQueueManager.addToQueue(
                url = url,
                title = title,
                thumbnailUrl = thumb,
                platform = VideoDownloader.PLATFORM_YOUTUBE,
                mode = mode,
                maxHeight = maxHeight,
                saveLocation = saveLocation
            )

            etUrl.setText("")
            stopAddingAnimation()
            btnAddToQueue.isEnabled = true
            Toast.makeText(this@MainActivity, "Added to queue", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_CODE_PERMS)
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMS = 1001
    }
}