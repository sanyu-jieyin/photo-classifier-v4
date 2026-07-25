package com.example.photoclassifier

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoClassifierApp() {
    val context = LocalContext.current
    val viewModel: PhotoClassifierViewModel = viewModel()

    val photos by viewModel.photos.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val slots by viewModel.slots.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val sourceName by viewModel.sourceName.collectAsState()
    val loadProgress by viewModel.loadProgress.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var imageCenter by remember { mutableStateOf(Offset.Zero) }
    val slotRects = remember { mutableStateListOf<Rect?>(null, null, null, null, null, null) }
    var highlightedSlot by remember { mutableIntStateOf(-1) }

    val dragScale by animateFloatAsState(if (isDragging) 0.9f else 1f, label = "scale")

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                permissionLauncher.launch(arrayOf(
                    android.Manifest.permission.READ_MEDIA_IMAGES,
                    android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ))
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES))
            }
            else -> {
                permissionLauncher.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }
    }

    val sourceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                viewModel.loadSourceFolder(uri)
            }
        }
    }

    var activeSlotIndex by remember { mutableIntStateOf(-1) }
    val slotFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && activeSlotIndex >= 0) {
            result.data?.data?.let { uri ->
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                tree?.let {
                    viewModel.setSlotFolder(
                        activeSlotIndex,
                        FolderItem(uri, it.name ?: "未命名")
                    )
                }
            }
        }
        activeSlotIndex = -1
    }

    toastMessage?.let { message ->
        LaunchedEffect(message) {
            delay(2000)
            viewModel.clearToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = sourceName ?: "未选择文件夹",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (photos.isEmpty()) "0 / 0" else "${currentIndex + 1} / ${photos.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        sourceLauncher.launch(intent)
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "选择文件夹")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (currentIndex > 0) viewModel.prevPhoto() },
                        enabled = currentIndex > 0
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "上一张")
                    }
                    IconButton(
                        onClick = { if (currentIndex < photos.size - 1) viewModel.nextPhoto() },
                        enabled = currentIndex < photos.size - 1
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "下一张")
                    }
                }
            )
        },
        bottomBar = {
            BottomSlotBar(
                slots = slots,
                isDragging = isDragging,
                highlightedSlot = highlightedSlot,
                onSlotRectChange = { idx, rect -> slotRects[idx] = rect },
                onSlotClick = { idx ->
                    val slot = slots[idx]
                    if (slot.folderItem == null) {
                        activeSlotIndex = idx
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        slotFolderLauncher.launch(intent)
                    } else {
                        viewModel.moveCurrentPhotoToSlot(idx)
                    }
                },
                onSlotLongClick = { idx ->
                    activeSlotIndex = idx
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    slotFolderLauncher.launch(intent)
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "正在扫描图片...",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (loadProgress > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "已找到 $loadProgress 张",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                photos.isEmpty() -> {
                    EmptyState(onSelectFolder = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        sourceLauncher.launch(intent)
                    })
                }
                else -> {
                    PhotoGallery(
                        photos = photos,
                        currentIndex = currentIndex,
                        isDragging = isDragging,
                        dragOffset = dragOffset,
                        dragScale = dragScale,
                        imageCenter = imageCenter,
                        onImageCenterChange = { imageCenter = it },
                        onDragStart = {
                            isDragging = true
                            dragOffset = Offset.Zero
                        },
                        onDrag = { offset ->
                            dragOffset = offset
                            val dropPos = imageCenter + offset
                            highlightedSlot = slotRects.indexOfFirst {
                                it != null && it.contains(dropPos)
                            }
                        },
                        onDragEnd = {
                            val dropPos = imageCenter + dragOffset
                            slotRects.forEachIndexed { idx, rect ->
                                if (rect != null && rect.contains(dropPos)) {
                                    viewModel.moveCurrentPhotoToSlot(idx)
                                }
                            }
                            isDragging = false
                            dragOffset = Offset.Zero
                            highlightedSlot = -1
                        },
                        onSwipeLeft = { viewModel.nextPhoto() },
                        onSwipeRight = { viewModel.prevPhoto() }
                    )
                }
            }

            toastMessage?.let { message ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    tonalElevation = 6.dp
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoGallery(
    photos: List<PhotoItem>,
    currentIndex: Int,
    isDragging: Boolean,
    dragOffset: Offset,
    dragScale: Float,
    imageCenter: Offset,
    onImageCenterChange: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
) {
    val context = LocalContext.current
    var localDragOffset by remember { mutableStateOf(Offset.Zero) }

    Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(0.16f)
                .fillMaxHeight()
                .padding(start = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentIndex > 0) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photos[currentIndex - 1].uri)
                        .size(width = 200, height = 300)
                        .crossfade(false)
                        .build(),
                    contentDescription = "上一张",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(10.dp))
                        .alpha(0.35f),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(0.68f)
                .fillMaxHeight()
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(photos[currentIndex].uri)
                    .crossfade(true)
                    .build(),
                contentDescription = "当前图片",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .alpha(if (isDragging) 0.25f else 1f)
                    .scale(if (isDragging) 0.95f else 1f)
                    .onGloballyPositioned { coordinates: LayoutCoordinates ->
                        val pos = coordinates.positionInRoot()
                        val size = coordinates.size
                        onImageCenterChange(
                            Offset(
                                pos.x + size.width / 2f,
                                pos.y + size.height / 2f
                            )
                        )
                    }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val startPos = down.position
                            var totalX = 0f
                            var totalY = 0f
                            var directionSet = false
                            var isHorizontal = false

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (!change.pressed) break

                                val pos = change.position
                                totalX = pos.x - startPos.x
                                totalY = pos.y - startPos.y

                                if (!directionSet) {
                                    val dist = hypot(totalX, totalY)
                                    if (dist > 20f) {
                                        directionSet = true
                                        isHorizontal = abs(totalX) > abs(totalY)
                                        if (!isHorizontal) {
                                            onDragStart()
                                        }
                                    }
                                }

                                if (directionSet && !isHorizontal) {
                                    change.consume()
                                    localDragOffset = Offset(totalX, totalY)
                                    onDrag(localDragOffset)
                                }
                            }

                            if (directionSet) {
                                if (isHorizontal) {
                                    if (totalX > 80f) onSwipeRight()
                                    else if (totalX < -80f) onSwipeLeft()
                                } else {
                                    onDragEnd()
                                    localDragOffset = Offset.Zero
                                }
                            }
                        }
                    },
                contentScale = ContentScale.Fit
            )

            if (isDragging) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photos[currentIndex].uri)
                        .size(300, 400)
                        .crossfade(false)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .offset {
                            IntOffset(dragOffset.x.toInt(), dragOffset.y.toInt())
                        }
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(0.16f)
                .fillMaxHeight()
                .padding(end = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentIndex < photos.size - 1) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(photos[currentIndex + 1].uri)
                        .size(width = 200, height = 300)
                        .crossfade(false)
                        .build(),
                    contentDescription = "下一张",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(10.dp))
                        .alpha(0.35f),
                    contentScale = ContentScale.Crop
                )
            }
        }
    }
}

@Composable
fun BottomSlotBar(
    slots: List<FolderSlot>,
    isDragging: Boolean,
    highlightedSlot: Int,
    onSlotRectChange: (Int, Rect?) -> Unit,
    onSlotClick: (Int) -> Unit,
    onSlotLongClick: (Int) -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp)
        ) {
            Text(
                text = if (isDragging) "拖到文件夹上松开" else "左右滑动切换 · 按住图片直接拖拽 · 点击文件夹移动",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                textAlign = TextAlign.Center
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                itemsIndexed(slots, key = { _, slot -> slot.index }) { idx, slot ->
                    SlotCard(
                        slot = slot,
                        isHighlighted = isDragging && highlightedSlot == idx,
                        modifier = Modifier.onGloballyPositioned { coordinates: LayoutCoordinates ->
                            val pos = coordinates.positionInRoot()
                            val size = coordinates.size
                            onSlotRectChange(
                                idx,
                                Rect(
                                    pos.x,
                                    pos.y,
                                    pos.x + size.width,
                                    pos.y + size.height
                                )
                            )
                        },
                        onClick = { onSlotClick(idx) },
                        onLongClick = { onSlotLongClick(idx) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotCard(
    slot: FolderSlot,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val borderWidth = if (isHighlighted) 3.dp else 1.dp
    val borderColor = when {
        isHighlighted -> MaterialTheme.colorScheme.primary
        slot.folderItem != null -> Color.Transparent
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    val bgColor = when {
        isHighlighted -> MaterialTheme.colorScheme.primaryContainer
        slot.folderItem != null -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(96.dp)
            .height(76.dp)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isHighlighted) 6.dp else 1.dp
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (slot.folderItem == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "选择",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = slot.folderItem.name,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(onSelectFolder: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "选择文件夹开始整理图片",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "支持 JPG、PNG、GIF、WebP、HEIC 等格式",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onSelectFolder,
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("选择文件夹", fontSize = 14.sp)
        }
    }
}
