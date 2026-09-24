package com.yfi.downloader

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkMode by remember { mutableStateOf(isSystemDark(applicationContext)) }

            LaunchedEffect(Unit) {
                val prefs = getSharedPreferences(MyApp.PREFS_NAME, MODE_PRIVATE)
                val savedTheme = prefs.getInt(MyApp.KEY_THEME, MyApp.THEME_SYSTEM)
                isDarkMode = when (savedTheme) {
                    MyApp.THEME_DARK -> true
                    MyApp.THEME_LIGHT -> false
                    else -> isSystemDark(applicationContext)
                }
            }

            YfiDownloaderTheme(isDarkMode = isDarkMode) {
                MainScreen(
                    isDarkMode = isDarkMode,
                    onToggleTheme = {
                        isDarkMode = !isDarkMode
                        val prefs = getSharedPreferences(MyApp.PREFS_NAME, MODE_PRIVATE)
                        val newMode = if (isDarkMode) MyApp.THEME_DARK else MyApp.THEME_LIGHT
                        prefs.edit { putInt(MyApp.KEY_THEME, newMode) }
                    }
                )
            }
        }
    }
}

@Composable
fun CascadeItem(
    index: Int,
    offsetY: Float = 40f,
    delayPerIndex: Long = 60L,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current.density
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay((index * delayPerIndex).milliseconds)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f)
        )
    }

    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = -(offsetY * density) * (1f - progress.value)
        }
    ) {
        content()
    }
}

@Composable
fun QueueItemAnimated(
    item: DownloadItem,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit
) {
    val density = LocalDensity.current.density
    val progress = remember(item.id) { Animatable(0f) }
    var isRemoving by remember(item.id) { mutableStateOf(false) }

    val slowSpring = spring<Float>(dampingRatio = 0.75f, stiffness = 180f)

    LaunchedEffect(item.id) {
        if (!isRemoving) {
            progress.animateTo(targetValue = 1f, animationSpec = slowSpring)
        }
    }

    LaunchedEffect(isRemoving) {
        if (isRemoving) {
            progress.animateTo(targetValue = 0f, animationSpec = slowSpring)
            onCancel()
        }
    }

    Box(
        modifier = Modifier.graphicsLayer {
            val p = progress.value
            alpha = p
            val scale = 0.85f + (0.15f * p)
            scaleX = scale
            scaleY = scale
            translationY = -(30f * density) * (1f - p)
        }
    ) {
        DownloadQueueItem(
            item = item,
            onPauseResume = onPauseResume,
            onCancel = { if (!isRemoving) isRemoving = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isDarkMode: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var modeIndex by remember { mutableIntStateOf(0) }
    var resolutionIndex by remember { mutableIntStateOf(3) }
    var saveLocationIndex by remember { mutableIntStateOf(0) }
    var queueModeIndex by remember { mutableIntStateOf(0) }

    var isAdding by remember { mutableStateOf(false) }
    var showBanner by remember { mutableStateOf(false) }
    var bannerText by remember { mutableStateOf("") }

    val queue by DownloadQueueManager.queue.collectAsStateWithLifecycle()

    val modeLabels = listOf("Video", "Music (MP3)")
    val resolutionLabels = listOf("360p", "480p", "720p (HD)", "1080p (Full HD)", "1440p (2K)", "2160p (4K)", "Best Available")
    val resolutionHeights = listOf(360, 480, 720, 1080, 1440, 2160, 0)
    val saveLocationLabels = listOf("Downloads (Default)", "Movies", "Music", "Instagram", "Facebook")
    val saveLocationValues = listOf(
        VideoDownloader.SAVE_DEFAULT, VideoDownloader.SAVE_MOVIES,
        VideoDownloader.SAVE_MUSIC, VideoDownloader.SAVE_INSTAGRAM, VideoDownloader.SAVE_FACEBOOK
    )
    val queueModeLabels = listOf("One by One", "Download All at Once")

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = perms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    val resolutionVisible = modeIndex == 0

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item { CascadeItem(0) { HeaderSection(isDarkMode, onToggleTheme) } }

            item {
                CascadeItem(1) {
                    InputCard(url, { url = it }, {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        url = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    })
                }
            }

            item { CascadeItem(2) { AppleDropdown("🎬  Mode", modeLabels, modeIndex) { modeIndex = it } } }

            if (resolutionVisible) {
                item {
                    CascadeItem(3) {
                        AppleDropdown("📺  Resolution", resolutionLabels, resolutionIndex) { resolutionIndex = it }
                    }
                }
            }

            item {
                CascadeItem(if (resolutionVisible) 4 else 3) {
                    AppleDropdown("📁  Save To", saveLocationLabels, saveLocationIndex) { saveLocationIndex = it }
                }
            }

            item {
                CascadeItem(if (resolutionVisible) 5 else 4) {
                    AppleDropdown("⚙️  Queue Mode", queueModeLabels, queueModeIndex) {
                        queueModeIndex = it
                        DownloadQueueManager.setQueueMode(if (it == 0) DownloadQueueManager.QUEUE_MODE_ONE_BY_ONE else DownloadQueueManager.QUEUE_MODE_ALL_AT_ONCE)
                    }
                }
            }

            item {
                CascadeItem(if (resolutionVisible) 6 else 5) {
                    SpringyDownloadButton(
                        text = if (isAdding) "Adding..." else "Download",
                        enabled = !isAdding && url.isNotBlank(),
                        onClick = {
                            if (url.isBlank() || isAdding) return@SpringyDownloadButton

                            isAdding = true
                            bannerText = "Adding to queue..."
                            showBanner = true

                            scope.launch {
                                try {
                                    val mode = modeIndex
                                    val maxHeight = if (mode == VideoDownloader.DOWNLOAD_MODE_MUSIC) 0 else resolutionHeights[resolutionIndex]
                                    val saveLocation = saveLocationValues[saveLocationIndex]

                                    DownloadQueueManager.startForegroundServiceSafely()

                                    var title = ""
                                    var thumb = ""
                                    try {
                                        val info = withContext(Dispatchers.IO) { YoutubeDL.getInstance().getInfo(url) }
                                        var rawTitle = info.title ?: ""
                                        if (url.contains("instagram.com", true) || url.contains("facebook.com", true)) {
                                            val desc = info.description ?: ""
                                            if (rawTitle.startsWith("Video by", true) && desc.isNotBlank()) rawTitle = desc
                                            else if (rawTitle.isBlank() && desc.isNotBlank()) rawTitle = desc
                                        }
                                        title = if (rawTitle.length > 80) rawTitle.take(80).trim() + "..." else rawTitle
                                        thumb = info.thumbnail ?: ""
                                    } catch (_: Exception) {}

                                    DownloadQueueManager.addToQueue(
                                        url = url, title = title, thumbnailUrl = thumb,
                                        platform = VideoDownloader.PLATFORM_YOUTUBE,
                                        mode = mode, maxHeight = maxHeight, saveLocation = saveLocation
                                    )
                                    url = ""
                                    bannerText = "Added to queue!"

                                    delay(1500.milliseconds)
                                    showBanner = false
                                } catch (_: Exception) {
                                    bannerText = "Error adding to queue"
                                    delay(1500.milliseconds)
                                    showBanner = false
                                } finally {
                                    isAdding = false
                                }
                            }
                        }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Downloads Queue", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground)
                    Button(
                        onClick = { DownloadQueueManager.clearCompleted() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (queue.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("No downloads yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(queue, key = { it.id }) { item ->
                    QueueItemAnimated(
                        item = item,
                        onPauseResume = {
                            if (item.status == DownloadStatus.DOWNLOADING) DownloadQueueManager.pauseItem(item.id)
                            else if (item.status == DownloadStatus.PAUSED) DownloadQueueManager.resumeItem(item.id)
                        },
                        onCancel = { DownloadQueueManager.cancelItem(item.id) }
                    )
                }
            }

            item {
                Text(
                    "Developed by SilentStrangerX",
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Light
                )
            }
        }

        AnimatedVisibility(
            visible = showBanner,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
            ) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(300)) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF9C7BE5),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = bannerText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// --- COMPONENTS ---

@Composable
fun HeaderSection(isDarkMode: Boolean, onToggleTheme: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF7E57C2), Color(0xFF512DA8))))
            .padding(start = 24.dp, top = 48.dp, end = 16.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("YFI Downloader", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("YouTube • Facebook • Instagram", color = Color(0xFFE0D5F5), fontSize = 13.sp)
            }
            IconButton(onClick = onToggleTheme) {
                Icon(if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode, "Toggle Theme", tint = Color.White)
            }
        }
    }
}

@Composable
fun InputCard(url: String, onUrlChange: (String) -> Unit, onPaste: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("🔗  Video URL", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Paste a link", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onPaste,
                    modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.ContentPaste, "Paste", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * ✅ FIXED DROPDOWN
 *
 * 1. Uses `dismissOnOutsideClick` (new API, requires Compose BOM 2025.01.00+).
 *    Receives click position + anchor bounds → dismisses ONLY when the tap is
 *    outside the anchor. Tapping the anchor does NOT dismiss, so the toggle works.
 *
 * 2. Animation: tween(350ms, FastOutSlowInEasing) — slightly faster, still smooth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleDropdown(title: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }

    val progress = remember { Animatable(0f) }

    // ✅ Slightly faster, still smooth — 350ms instead of 450ms
    val revealSpec = tween<Float>(durationMillis = 350, easing = FastOutSlowInEasing)

    val popupPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                return IntOffset(
                    x = anchorBounds.left,
                    y = anchorBounds.bottom
                )
            }
        }
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            menuVisible = true
            progress.animateTo(1f, animationSpec = revealSpec)
        } else if (menuVisible) {
            progress.animateTo(0f, animationSpec = revealSpec)
            menuVisible = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f).height(20.dp)) {
                        AnimatedContent(
                            targetState = selectedIndex,
                            transitionSpec = {
                                if (targetState > initialState) {
                                    (slideInVertically { it } + fadeIn(tween(200))) togetherWith
                                            (slideOutVertically { -it } + fadeOut(tween(200)))
                                } else {
                                    (slideInVertically { -it } + fadeIn(tween(200))) togetherWith
                                            (slideOutVertically { it } + fadeOut(tween(200)))
                                }
                            },
                            label = "selectedText"
                        ) { index ->
                            Text(
                                options[index],
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(Icons.Default.ArrowDropDown, "Expand", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (menuVisible) {
                    Popup(
                        popupPositionProvider = popupPositionProvider,
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnClickOutside = true
                        )
                    ) {
                        Surface(
                            modifier = Modifier.width(240.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shadowElevation = 12.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .layout { measurable, constraints ->
                                        val placeable = measurable.measure(
                                            constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
                                        )
                                        val revealed = (placeable.height * progress.value.coerceIn(0f, 1f))
                                            .roundToInt()
                                        layout(placeable.width, revealed) {
                                            placeable.placeRelative(0, 0)
                                        }
                                    }
                            ) {
                                options.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                option,
                                                color = if (index == selectedIndex) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface,
                                                fontWeight = if (index == selectedIndex) FontWeight.Bold
                                                else FontWeight.Normal
                                            )
                                        },
                                        onClick = {
                                            onSelect(index)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpringyDownloadButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scaleX by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "scaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "scaleY"
    )
    val dim by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "dim"
    )

    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(56.dp)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
                alpha = if (enabled) dim else 0.5f
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
fun DownloadQueueItem(item: DownloadItem, onPauseResume: () -> Unit, onCancel: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title.ifBlank { item.url },
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    when (item.status) {
                        DownloadStatus.QUEUED -> "Queued"
                        DownloadStatus.DOWNLOADING -> "${item.progress.toInt()}% • ${item.statusLine.take(30)}"
                        DownloadStatus.PAUSED -> "Paused"
                        DownloadStatus.COMPLETED -> "Completed ✓"
                        DownloadStatus.FAILED -> "Failed: ${item.statusLine.take(30)}"
                        else -> ""
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED) {
                    IconButton(onClick = onPauseResume) {
                        Icon(
                            if (item.status == DownloadStatus.DOWNLOADING) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Pause/Resume",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun YfiDownloaderTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val colorTween = tween<Color>(
        durationMillis = 700,
        easing = FastOutSlowInEasing
    )

    val animatedBackground by animateColorAsState(
        targetValue = if (isDarkMode) Color(0xFF000000) else Color(0xFFF2F2F7),
        animationSpec = colorTween, label = "background"
    )
    val animatedSurface by animateColorAsState(
        targetValue = if (isDarkMode) Color(0xFF1C1C1E) else Color.White,
        animationSpec = colorTween, label = "surface"
    )
    val animatedSurfaceVariant by animateColorAsState(
        targetValue = if (isDarkMode) Color(0xFF2C2C2E) else Color(0xFFE5E5EA),
        animationSpec = colorTween, label = "surfaceVariant"
    )
    val animatedPrimary by animateColorAsState(
        targetValue = if (isDarkMode) Color(0xFF9C7BE5) else Color(0xFF7E57C2),
        animationSpec = colorTween, label = "primary"
    )

    val onBackgroundInstant = if (isDarkMode) Color.White else Color(0xFF1C1C1E)
    val onSurfaceVariantInstant = if (isDarkMode) Color(0xFFAEAEB2) else Color(0xFF8E8E93)
    val errorInstant = if (isDarkMode) Color(0xFFFF453A) else Color(0xFFB3261E)

    val colorScheme = if (isDarkMode) {
        darkColorScheme(
            primary = animatedPrimary,
            onPrimary = Color.White,
            background = animatedBackground,
            surface = animatedSurface,
            onBackground = onBackgroundInstant,
            onSurface = onBackgroundInstant,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = onSurfaceVariantInstant,
            error = errorInstant
        )
    } else {
        lightColorScheme(
            primary = animatedPrimary,
            onPrimary = Color.White,
            background = animatedBackground,
            surface = animatedSurface,
            onBackground = onBackgroundInstant,
            onSurface = onBackgroundInstant,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = onSurfaceVariantInstant,
            error = errorInstant
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(animatedBackground)) {
            content()
        }
    }
}

fun isSystemDark(context: Context): Boolean {
    return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}