package com.yfi.downloader

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
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
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
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
fun MainScreen(isDarkMode: Boolean, onToggleTheme: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { 2 })

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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CascadeItem(0) { HeaderSection(isDarkMode, onToggleTheme) }
        CascadeItem(1) {
            TabSwitcher(pagerState) { newTab ->
                scope.launch { pagerState.animateScrollToPage(newTab) }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            if (page == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    item {
                        CascadeItem(2) {
                            UrlCard(url, { url = it }, {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                url = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            })
                        }
                    }
                    item {
                        CascadeItem(3) {
                            IconDropdownCard(
                                icon = Icons.Rounded.Movie,
                                title = "Mode",
                                subtitle = "Select download mode",
                                options = modeLabels,
                                selectedIndex = modeIndex,
                                onSelect = { modeIndex = it }
                            )
                        }
                    }
                    if (modeIndex == 0) {
                        item {
                            CascadeItem(4) {
                                IconDropdownCard(
                                    icon = Icons.Rounded.Hd,
                                    title = "Resolution",
                                    subtitle = "Select video quality",
                                    options = resolutionLabels,
                                    selectedIndex = resolutionIndex,
                                    onSelect = { resolutionIndex = it }
                                )
                            }
                        }
                    }
                    item {
                        CascadeItem(if (modeIndex == 0) 5 else 4) {
                            IconDropdownCard(
                                icon = Icons.Rounded.Folder,
                                title = "Save To",
                                subtitle = "Choose download location",
                                options = saveLocationLabels,
                                selectedIndex = saveLocationIndex,
                                onSelect = { saveLocationIndex = it }
                            )
                        }
                    }
                    item {
                        CascadeItem(if (modeIndex == 0) 6 else 5) {
                            IconDropdownCard(
                                icon = Icons.Rounded.Layers,
                                title = "Queue Mode",
                                subtitle = "Select how to add to queue",
                                options = queueModeLabels,
                                selectedIndex = queueModeIndex,
                                onSelect = {
                                    queueModeIndex = it
                                    DownloadQueueManager.setQueueMode(
                                        if (it == 0) DownloadQueueManager.QUEUE_MODE_ONE_BY_ONE
                                        else DownloadQueueManager.QUEUE_MODE_ALL_AT_ONCE
                                    )
                                }
                            )
                        }
                    }
                    item {
                        CascadeItem(if (modeIndex == 0) 7 else 6) {
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
                                            val maxHeight = if (mode == VideoDownloader.DOWNLOAD_MODE_MUSIC) 0
                                            else resolutionHeights[resolutionIndex]
                                            val saveLocation = saveLocationValues[saveLocationIndex]

                                            DownloadQueueManager.startForegroundServiceSafely()

                                            var title = ""
                                            var thumb = ""
                                            try {
                                                val info = withContext(Dispatchers.IO) {
                                                    YoutubeDL.getInstance().getInfo(url)
                                                }
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
                                                url = url,
                                                title = title,
                                                thumbnailUrl = thumb,
                                                platform = VideoDownloader.PLATFORM_YOUTUBE,
                                                mode = mode,
                                                maxHeight = maxHeight,
                                                saveLocation = saveLocation
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

                    if (queue.isNotEmpty()) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Downloads Queue",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    "Clear",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { DownloadQueueManager.clearCompleted() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
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
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Light
                        )
                    }
                }
            } else {
                FolderTab()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showBanner,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
            ) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }, animationSpec = tween(300)) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp)
        ) {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color(0xFF9C7BE5),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = bannerText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderSection(isDarkMode: Boolean, onToggleTheme: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF7E57C2), Color(0xFF512DA8))
                )
            )
            .padding(start = 24.dp, top = 32.dp, end = 12.dp, bottom = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "YFI Downloader",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "YouTube • Facebook • Instagram",
                    color = Color(0xFFE0D5F5),
                    fontSize = 12.sp
                )
            }
            IconButton(onClick = onToggleTheme) {
                Icon(
                    if (isDarkMode) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    "Toggle Theme",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
fun TabSwitcher(pagerState: PagerState, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    var containerWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(3.dp)
            .height(38.dp)
            .onSizeChanged { containerWidthPx = it.width }
    ) {
        val halfPx = containerWidthPx / 2f
        val halfDp = with(density) { halfPx.toDp() }

        val offsetFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val pillOffsetPx = halfPx * offsetFraction

        Box(
            modifier = Modifier
                .width(halfDp)
                .fillMaxHeight()
                .offset { IntOffset(pillOffsetPx.roundToInt(), 0) }
                .clip(RoundedCornerShape(19.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabLabel(
                text = "Main",
                icon = Icons.Rounded.Home,
                selected = pagerState.currentPage == 0,
                modifier = Modifier.weight(1f)
            ) { onSelect(0) }
            TabLabel(
                text = "Folder",
                icon = Icons.Rounded.Folder,
                selected = pagerState.currentPage == 1,
                modifier = Modifier.weight(1f)
            ) { onSelect(1) }
        }
    }
}

@Composable
fun TabLabel(
    text: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val contentColor by animateColorAsState(
        if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "tabContent"
    )
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = contentColor, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(7.dp))
        Text(text, color = contentColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun IconBox(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(23.dp))
    }
}

@Composable
fun UrlCard(url: String, onUrlChange: (String) -> Unit, onPaste: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(Icons.Rounded.Link)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    "Video URL",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "Paste a link from YouTube, Facebook or Instagram",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                if (url.isEmpty()) {
                    Text(
                        "Paste a link...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onPaste),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.ContentPaste,
                    contentDescription = "Paste",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconDropdownCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val revealSpec = tween<Float>(durationMillis = 350, easing = FastOutSlowInEasing)

    val popupPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.bottom)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(icon)
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(title, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
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
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
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
                        modifier = Modifier.width(220.dp),
                        shape = RoundedCornerShape(14.dp),
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
                                    val revealed = (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
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
                                            fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.sp
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

@Composable
fun SpringyDownloadButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scaleX by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "scaleX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = 500f),
        label = "scaleY"
    )
    val dim by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "dim"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .height(46.dp)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
                alpha = if (enabled) dim else 0.5f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
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

@Composable
fun DownloadQueueItem(item: DownloadItem, onPauseResume: () -> Unit, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .animateContentSize()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
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
                Icon(Icons.Rounded.Download, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { item.url },
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(5.dp))
            LinearProgressIndicator(
                progress = { item.progress / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                when (item.status) {
                    DownloadStatus.QUEUED -> "Queued"
                    DownloadStatus.DOWNLOADING -> "${item.progress.toInt()}% • ${item.statusLine.take(30)}"
                    DownloadStatus.PAUSED -> "Paused"
                    DownloadStatus.COMPLETED -> "Completed ✓"
                    DownloadStatus.FAILED -> "Failed: ${item.statusLine.take(30)}"
                    else -> ""
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column {
            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.PAUSED) {
                IconButton(onClick = onPauseResume, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (item.status == DownloadStatus.DOWNLOADING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        "Pause/Resume",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Cancel", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ============================================================================
// FOLDER TAB
// ============================================================================

data class FolderEntry(val name: String, val file: File, val icon: ImageVector)
data class FileEntry(val file: File, val name: String, val size: Long, val modified: Long)

object ThumbCache {
    private val map = mutableMapOf<String, Bitmap?>()
    fun get(path: String): Bitmap? = map[path]
    fun put(path: String, bmp: Bitmap?) { map[path] = bmp }
}

// ✅ Recent scan — video + audio, only 5 newest
fun scanRecent(folders: List<FolderEntry>): List<FileEntry> {
    val mediaExtensions = listOf("mp4", "webm", "mkv", "mp3", "m4a", "aac")
    return folders.flatMap { folder ->
        folder.file.listFiles()?.filter {
            it.isFile && it.extension.lowercase() in mediaExtensions
        }?.toList() ?: emptyList()
    }
        .sortedByDescending { it.lastModified() }
        .take(5)
        .map { FileEntry(it, it.name, it.length(), it.lastModified()) }
}

@Composable
fun SelectionHeader(count: Int, subtitle: String, onExit: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Exit selection",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$count selected",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun FolderTab() {
    val context = LocalContext.current
    var selectedFolder by remember { mutableStateOf<FolderEntry?>(null) }
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(checkStoragePermission(context)) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val downloadsRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val folders = remember {
        listOf(
            FolderEntry("Downloads", downloadsRoot, Icons.Rounded.Download),
            FolderEntry("Movies", File(downloadsRoot, "Movies"), Icons.Rounded.Movie),
            FolderEntry("Music", File(downloadsRoot, "Music"), Icons.Rounded.MusicNote),
            FolderEntry("Instagram", File(downloadsRoot, "Instagram"), Icons.Rounded.PhotoCamera),
            FolderEntry("Facebook", File(downloadsRoot, "Facebook"), Icons.Rounded.ThumbUp)
        )
    }

    var recentFiles by remember { mutableStateOf<List<FileEntry>>(emptyList()) }

    LaunchedEffect(selectedFolder, hasPermission) {
        if (hasPermission) {
            recentFiles = withContext(Dispatchers.IO) { scanRecent(folders) }
        }
    }

    BackHandler(enabled = selectionMode || selectedFolder != null) {
        if (selectionMode) {
            selectionMode = false
            selectedPaths = emptySet()
        } else {
            selectedFolder = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { hasPermission = checkStoragePermission(context) }

    LaunchedEffect(selectedFolder) {
        if (selectedFolder != null) {
            loading = true
            files = withContext(Dispatchers.IO) { scanFolder(selectedFolder!!.file) }
            loading = false
        } else {
            selectionMode = false
            selectedPaths = emptySet()
        }
    }

    if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Storage permission needed",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Grant access to view your downloaded files",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(18.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:${context.packageName}")
                            permissionLauncher.launch(intent)
                        }
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text("Grant Permission", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 110.dp)
        ) {
            if (selectedFolder == null) {
                if (selectionMode) {
                    item {
                        SelectionHeader(
                            count = selectedPaths.size,
                            subtitle = "Recent"
                        ) {
                            selectionMode = false
                            selectedPaths = emptySet()
                        }
                    }
                }

                if (recentFiles.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Recent",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    items(recentFiles, key = { "recent_${it.file.absolutePath}" }) { file ->
                        val path = file.file.absolutePath
                        val selected = path in selectedPaths
                        FileItemRow(
                            file = file,
                            selected = selected,
                            selectionMode = selectionMode,
                            onLongPress = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedPaths = setOf(path)
                                } else {
                                    selectedPaths = if (selected) selectedPaths - path else selectedPaths + path
                                    if (selectedPaths.isEmpty()) selectionMode = false
                                }
                            },
                            onClick = {
                                if (selectionMode) {
                                    selectedPaths = if (selected) selectedPaths - path else selectedPaths + path
                                    if (selectedPaths.isEmpty()) selectionMode = false
                                } else {
                                    openFileWithSystem(context, file.file)
                                }
                            }
                        )
                    }
                }

                item {
                    Text(
                        "Your Downloads",
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(folders.size) { index ->
                    val folder = folders[index]
                    val count = remember(folder.file) {
                        folder.file.listFiles()?.count {
                            it.isFile && it.extension.lowercase() in listOf("mp4", "mp3", "webm", "m4a", "mkv", "aac")
                        } ?: 0
                    }
                    CascadeItem(index) {
                        FolderCard(folder, count) { selectedFolder = folder }
                    }
                }
            } else {
                item {
                    if (selectionMode) {
                        SelectionHeader(
                            count = selectedPaths.size,
                            subtitle = selectedFolder!!.name
                        ) {
                            selectionMode = false
                            selectedPaths = emptySet()
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { selectedFolder = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.ArrowBack,
                                    contentDescription = "Back",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    selectedFolder!!.name,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "${files.size} ${if (files.size == 1) "file" else "files"}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                if (loading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (files.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Inbox,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    "No downloads here yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    items(files, key = { it.file.absolutePath }) { file ->
                        val path = file.file.absolutePath
                        val selected = path in selectedPaths
                        FileItemRow(
                            file = file,
                            selected = selected,
                            selectionMode = selectionMode,
                            onLongPress = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedPaths = setOf(path)
                                } else {
                                    selectedPaths = if (selected) selectedPaths - path else selectedPaths + path
                                    if (selectedPaths.isEmpty()) selectionMode = false
                                }
                            },
                            onClick = {
                                if (selectionMode) {
                                    selectedPaths = if (selected) selectedPaths - path else selectedPaths + path
                                    if (selectedPaths.isEmpty()) selectionMode = false
                                } else {
                                    openFileWithSystem(context, file.file)
                                }
                            }
                        )
                    }
                }
            }
        }

        if (selectionMode && selectedPaths.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp)
                    .size(72.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable { showDeleteDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "${selectedPaths.size}",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 17.sp
                    )
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete selected",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selectedPaths.size} file(s)?", fontWeight = FontWeight.Bold) },
            text = { Text("These files will be permanently deleted from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val toDelete = selectedPaths
                    selectedPaths = emptySet()
                    selectionMode = false
                    Thread {
                        var deleted = 0
                        toDelete.forEach { p ->
                            val f = File(p)
                            if (f.exists() && f.delete()) {
                                deleted++
                                try {
                                    android.media.MediaScannerConnection.scanFile(
                                        context, arrayOf(f.absolutePath), null, null
                                    )
                                } catch (_: Exception) {}
                            }
                        }
                        val finalDeleted = deleted

                        val newRecent = scanRecent(folders)
                        val newFiles = selectedFolder?.let { scanFolder(it.file) }

                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            recentFiles = newRecent
                            newFiles?.let { files = it }
                            Toast.makeText(
                                context,
                                "Deleted $finalDeleted file(s)",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }.start()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun FolderCard(folder: FolderEntry, fileCount: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBox(folder.icon)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                folder.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                "$fileCount ${if (fileCount == 1) "file" else "files"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemRow(
    file: FileEntry,
    selected: Boolean,
    selectionMode: Boolean,
    onLongPress: () -> Unit,
    onClick: () -> Unit
) {
    val isVideo = file.file.extension.lowercase() in listOf("mp4", "webm", "mkv")

    val thumbnail by produceState<Bitmap?>(
        initialValue = ThumbCache.get(file.file.absolutePath),
        file.file.absolutePath
    ) {
        if (isVideo && value == null) {
            value = withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    val bmp = android.media.ThumbnailUtils.createVideoThumbnail(
                        file.file.absolutePath,
                        MediaStore.Video.Thumbnails.MINI_KIND
                    )
                    ThumbCache.put(file.file.absolutePath, bmp)
                    bmp
                } catch (_: Exception) {
                    ThumbCache.put(file.file.absolutePath, null)
                    null
                }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (isVideo && thumbnail != null) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    if (isVideo) Icons.Rounded.PlayCircle else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "${formatFileSize(file.size)} • ${formatDate(file.modified)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
        if (selectionMode) {
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

fun scanFolder(folder: File): List<FileEntry> {
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val mediaExtensions = listOf("mp4", "mp3", "webm", "m4a", "mkv", "aac")
    return folder.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in mediaExtensions }
        ?.sortedByDescending { it.lastModified() }
        ?.map { FileEntry(it, it.name, it.length(), it.lastModified()) }
        ?: emptyList()
}

fun openFileWithSystem(context: Context, file: File) {
    val ext = file.extension.lowercase()
    val mime = when (ext) {
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        else -> "*/*"
    }
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroup = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", bytes / 1024.0.pow(digitGroup.toDouble()), units[digitGroup])
}

fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun YfiDownloaderTheme(isDarkMode: Boolean, content: @Composable () -> Unit) {
    val colorTween = tween<Color>(durationMillis = 700, easing = FastOutSlowInEasing)

    val darkBackground = Color(0xFF0D0D17)
    val darkSurface = Color(0xFF17171F)
    val darkSurfaceVariant = Color(0xFF23232D)
    val darkPrimary = Color(0xFF7C5CFF)
    val darkOnBg = Color(0xFFFFFFFF)
    val darkOnSurfaceVariant = Color(0xFF8E8E9E)

    val lightBackground = Color(0xFFF5F5FA)
    val lightSurface = Color(0xFFFFFFFF)
    val lightSurfaceVariant = Color(0xFFEEEEF5)
    val lightPrimary = Color(0xFF6C4CE0)
    val lightOnBg = Color(0xFF0A0A14)
    val lightOnSurfaceVariant = Color(0xFF6E6E80)

    val animatedBackground by animateColorAsState(
        if (isDarkMode) darkBackground else lightBackground, colorTween, label = "bg"
    )
    val animatedSurface by animateColorAsState(
        if (isDarkMode) darkSurface else lightSurface, colorTween, label = "surface"
    )
    val animatedSurfaceVariant by animateColorAsState(
        if (isDarkMode) darkSurfaceVariant else lightSurfaceVariant, colorTween, label = "surfaceVariant"
    )
    val animatedPrimary by animateColorAsState(
        if (isDarkMode) darkPrimary else lightPrimary, colorTween, label = "primary"
    )

    val onBg = if (isDarkMode) darkOnBg else lightOnBg
    val onSurfaceVariant = if (isDarkMode) darkOnSurfaceVariant else lightOnSurfaceVariant
    val errorColor = if (isDarkMode) Color(0xFFFF453A) else Color(0xFFB3261E)

    val colorScheme = if (isDarkMode) {
        darkColorScheme(
            primary = animatedPrimary,
            onPrimary = Color.White,
            background = animatedBackground,
            surface = animatedSurface,
            onBackground = onBg,
            onSurface = onBg,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            error = errorColor
        )
    } else {
        lightColorScheme(
            primary = animatedPrimary,
            onPrimary = Color.White,
            background = animatedBackground,
            surface = animatedSurface,
            onBackground = onBg,
            onSurface = onBg,
            surfaceVariant = animatedSurfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            error = errorColor
        )
    }

    MaterialTheme(colorScheme = colorScheme) {
        Box(modifier = Modifier.fillMaxSize().background(animatedBackground)) {
            content()
        }
    }
}

fun isSystemDark(context: Context): Boolean {
    return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}