package com.example.fileviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Navigation & Storage Abstractions ---

sealed class AppScreen {
    object Home : AppScreen()
    data class Browser(val rootNode: StorageNode) : AppScreen()
}

sealed class StorageNode {
    abstract val name: String
    abstract val key: String
    abstract val isDirectory: Boolean
    
    data class LocalFileNode(val file: File) : StorageNode() {
        override val name: String get() = file.name.ifEmpty { "Downloads" }
        override val key: String get() = file.absolutePath
        override val isDirectory: Boolean get() = file.isDirectory
    }

    data class SafNode(val document: DocumentFile, val customName: String? = null) : StorageNode() {
        override val name: String get() = customName ?: document.name ?: "Termux"
        override val key: String get() = document.uri.toString()
        override val isDirectory: Boolean get() = document.isDirectory
    }
}

data class FolderUiState(
    val node: StorageNode,
    val name: String,
    val itemCount: Int
)

data class FileUiState(
    val node: StorageNode,
    val name: String,
    val extension: String,
    val formattedSize: String,
    val formattedDate: String
)

data class DirectoryContent(
    val folders: List<FolderUiState>,
    val files: List<FileUiState>
)

enum class SortOption(val label: String) {
    NAME_ASC("Name (A-Z)"),
    NAME_DESC("Name (Z-A)"),
    DATE_NEWEST("Date (Newest First)"),
    DATE_OLDEST("Date (Oldest First)"),
    SIZE_LARGEST("Size (Largest First)"),
    SIZE_SMALLEST("Size (Smallest First)")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasStoragePermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    hasStoragePermission = Environment.isExternalStorageManager()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasStoragePermission) {
        PermissionRequestScreen(
            onRequestPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        context.startActivity(intent)
                    }
                }
            }
        )
    } else {
        AppNavigation()
    }
}

@Composable
fun AppNavigation() {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    val downloadsDir = remember {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    val termuxTreeUri = remember {
        // Termux DocumentsProvider authority authority URI
        Uri.parse("content://com.termux.documents/tree/home")
    }

    when (val screen = currentScreen) {
        is AppScreen.Home -> {
            HomeScreen(
                onSelectDownloads = {
                    currentScreen = AppScreen.Browser(StorageNode.LocalFileNode(downloadsDir))
                },
                onSelectTermux = {
                    val termuxDoc = DocumentFile.fromTreeUri(context, termuxTreeUri)
                    if (termuxDoc != null && termuxDoc.exists()) {
                        currentScreen = AppScreen.Browser(StorageNode.SafNode(termuxDoc, "Termux Home"))
                    } else {
                        Toast.makeText(context, "Termux DocumentsProvider unavailable or not installed.", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }
        is AppScreen.Browser -> {
            FileBrowserScreen(
                initialNode = screen.rootNode,
                onGoHome = { currentScreen = AppScreen.Home }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSelectDownloads: () -> Unit,
    onSelectTermux: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Explorer Home", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Storage Location",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                onClick = onSelectDownloads,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Downloads",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Downloads", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Public external downloads storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Card(
                onClick = onSelectTermux,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Termux",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Termux Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("com.termux.documents provider filesystem", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    initialNode: StorageNode,
    onGoHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("file_browser_prefs", Context.MODE_PRIVATE) }
    val imageLoader = rememberSvgImageLoader(context)

    val availableAssets = remember(context) {
        context.assets.list("icons/square-o")?.toSet() ?: emptySet()
    }

    val rootNodeHistory = remember { mutableStateListOf(initialNode) }
    val currentNode = rootNodeHistory.last()

    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    var sortOption by remember {
        val savedOption = sharedPrefs.getString("sort_option", SortOption.NAME_ASC.name)
        mutableStateOf(runCatching { SortOption.valueOf(savedOption!!) }.getOrDefault(SortOption.NAME_ASC))
    }

    var showHiddenFiles by remember {
        mutableStateOf(sharedPrefs.getBoolean("show_hidden_files", false))
    }

    var showOptionsMenu by remember { mutableStateOf(false) }
    var selectedFileForOptions by remember { mutableStateOf<StorageNode?>(null) }

    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Offload item gathering for standard File AND SAF DocumentFile to Dispatchers.IO
    val directoryContentState by produceState<DirectoryContent>(
        initialValue = DirectoryContent(emptyList(), emptyList()),
        currentNode,
        searchQuery,
        sortOption,
        showHiddenFiles,
        refreshTrigger
    ) {
        value = withContext(Dispatchers.IO) {
            val children: List<StorageNode> = when (currentNode) {
                is StorageNode.LocalFileNode -> {
                    currentNode.file.listFiles()?.map { StorageNode.LocalFileNode(it) } ?: emptyList()
                }
                is StorageNode.SafNode -> {
                    currentNode.document.listFiles().map { StorageNode.SafNode(it) }
                }
            }

            val visibleContent = if (showHiddenFiles) {
                children
            } else {
                children.filter { !it.name.startsWith(".") }
            }

            val filtered = if (searchQuery.isBlank()) {
                visibleContent
            } else {
                visibleContent.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            val dirs = filtered.filter { it.isDirectory }
            val files = filtered.filter { !it.isDirectory }

            val sortedDirs = when (sortOption) {
                SortOption.NAME_ASC -> dirs.sortedBy { it.name.lowercase() }
                SortOption.NAME_DESC -> dirs.sortedByDescending { it.name.lowercase() }
                else -> dirs.sortedBy { it.name.lowercase() }
            }

            val sortedFiles = when (sortOption) {
                SortOption.NAME_ASC -> files.sortedBy { it.name.lowercase() }
                SortOption.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
                else -> files.sortedBy { it.name.lowercase() }
            }

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

            val folderStates = sortedDirs.map { dirNode ->
                val count = when (dirNode) {
                    is StorageNode.LocalFileNode -> dirNode.file.listFiles()?.size ?: 0
                    is StorageNode.SafNode -> dirNode.document.listFiles().size
                }
                FolderUiState(dirNode, dirNode.name, count)
            }

            val fileStates = sortedFiles.map { fileNode ->
                val extension = fileNode.name.substringAfterLast('.', "")
                val size = when (fileNode) {
                    is StorageNode.LocalFileNode -> fileNode.file.length()
                    is StorageNode.SafNode -> fileNode.document.length()
                }
                val lastMod = when (fileNode) {
                    is StorageNode.LocalFileNode -> fileNode.file.lastModified()
                    is StorageNode.SafNode -> fileNode.document.lastModified()
                }

                FileUiState(
                    node = fileNode,
                    name = fileNode.name,
                    extension = extension,
                    formattedSize = formatFileSize(size),
                    formattedDate = dateFormat.format(Date(lastMod))
                )
            }

            DirectoryContent(folderStates, fileStates)
        }
        isRefreshing = false
    }

    val (subfolders, files) = directoryContentState

    BackHandler(enabled = true) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else if (rootNodeHistory.size > 1) {
            rootNodeHistory.removeAt(rootNodeHistory.lastIndex)
        } else {
            onGoHome()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onGoHome) {
                            Icon(imageVector = Icons.Default.Home, contentDescription = "Home")
                        }
                    },
                    title = {
                        if (isSearchActive) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search files...", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = currentNode.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search"
                            )
                        }
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options Menu")
                        }
                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Show hidden files", style = MaterialTheme.typography.bodyMedium)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Checkbox(checked = showHiddenFiles, onCheckedChange = null)
                                    }
                                },
                                onClick = {
                                    val newValue = !showHiddenFiles
                                    showHiddenFiles = newValue
                                    sharedPrefs.edit().putBoolean("show_hidden_files", newValue).apply()
                                }
                            )

                            HorizontalDivider()

                            SortOption.values().forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, style = MaterialTheme.typography.bodyMedium) },
                                    onClick = {
                                        sortOption = option
                                        sharedPrefs.edit().putString("sort_option", option.name).apply()
                                        showOptionsMenu = false
                                    }
                                )
                            }
                        }
                    }
                )
                BreadcrumbBar(
                    nodeHistory = rootNodeHistory,
                    onNodeClick = { targetIndex ->
                        searchQuery = ""
                        isSearchActive = false
                        while (rootNodeHistory.size > targetIndex + 1) {
                            rootNodeHistory.removeAt(rootNodeHistory.lastIndex)
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                refreshTrigger += 1
            },
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = subfolders,
                    key = { folderState -> folderState.node.key }
                ) { folderState ->
                    FolderCard(
                        folderState = folderState,
                        availableAssets = availableAssets,
                        imageLoader = imageLoader,
                        onClick = {
                            searchQuery = ""
                            isSearchActive = false
                            rootNodeHistory.add(folderState.node)
                        }
                    )
                }

                items(
                    items = files,
                    key = { fileState -> fileState.node.key }
                ) { fileState ->
                    FileRowItem(
                        fileState = fileState,
                        availableAssets = availableAssets,
                        imageLoader = imageLoader,
                        onLongClick = { selectedFileForOptions = fileState.node }
                    )
                }
            }
        }

        selectedFileForOptions?.let { node ->
            AlertDialog(
                onDismissRequest = { selectedFileForOptions = null },
                title = { Text(text = node.name, style = MaterialTheme.typography.titleMedium) },
                text = { Text("Select an action for this file.", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            openStorageNode(context, node)
                            selectedFileForOptions = null
                        }
                    ) {
                        Text("Open External Viewer")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { selectedFileForOptions = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun BreadcrumbBar(
    nodeHistory: List<StorageNode>,
    onNodeClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        nodeHistory.forEachIndexed { index, node ->
            val isLast = index == nodeHistory.lastIndex

            Text(
                text = node.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable(!isLast) { onNodeClick(index) }
            )

            if (!isLast) {
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

@Composable
fun rememberSvgImageLoader(context: Context): ImageLoader {
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(false)
            .build()
    }
}

@Composable
fun FileIcon(
    extension: String,
    availableAssets: Set<String>,
    imageLoader: ImageLoader,
    modifier: Modifier = Modifier.size(32.dp)
) {
    val context = LocalContext.current
    val ext = extension.lowercase(Locale.getDefault()).ifEmpty { "page" }

    val iconPath = if (availableAssets.contains("$ext.svg")) {
        "file:///android_asset/icons/square-o/$ext.svg"
    } else {
        "file:///android_asset/icons/square-o/page.svg"
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(iconPath)
            .build(),
        contentDescription = null,
        imageLoader = imageLoader,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folderState: FolderUiState,
    availableAssets: Set<String>,
    imageLoader: ImageLoader,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileIcon(
                extension = "folder",
                availableAssets = availableAssets,
                imageLoader = imageLoader
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folderState.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${folderState.itemCount} items",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRowItem(
    fileState: FileUiState,
    availableAssets: Set<String>,
    imageLoader: ImageLoader,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .combinedClickable(
                onClick = { /* Short click action */ },
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FileIcon(
                extension = fileState.extension,
                availableAssets = availableAssets,
                imageLoader = imageLoader
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileState.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${fileState.formattedSize} • ${fileState.formattedDate}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun PermissionRequestScreen(onRequestPermission: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Storage Permission Required",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "All Files Access is required to read and list files from public external storage directories.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission in Settings")
            }
        }
    }
}

private fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun openStorageNode(context: Context, node: StorageNode) {
    try {
        val (uri, extension) = when (node) {
            is StorageNode.LocalFileNode -> Pair(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", node.file),
                node.file.extension
            )
            is StorageNode.SafNode -> Pair(
                node.document.uri,
                node.name.substringAfterLast('.', "")
            )
        }

        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.getDefault()))
        if (mimeType == null) {
            mimeType = when (extension.lowercase(Locale.getDefault())) {
                "pdf" -> "application/pdf"
                "txt", "log", "conf", "sh", "py" -> "text/plain"
                "json" -> "application/json"
                "zip", "rar", "7z", "tar", "gz" -> "application/zip"
                "apk" -> "application/vnd.android.package-archive"
                else -> "*/*"
            }
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooserIntent = Intent.createChooser(intent, "Open file with...")
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooserIntent)
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(context, "No app available to open ${node.name}", Toast.LENGTH_SHORT).show()
    }
}
