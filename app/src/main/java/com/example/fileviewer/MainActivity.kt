package com.example.fileviewer

import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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

// Immutable UI Data Holders to avoid I/O in Composables
data class FolderUiState(
    val file: File,
    val name: String,
    val itemCount: Int
)

data class FileUiState(
    val file: File,
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
        FileBrowserScreen()
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    folderName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("file_browser_prefs", Context.MODE_PRIVATE) }
    val imageLoader = rememberSvgImageLoader(context)

    val availableAssets = remember(context) {
        context.assets.list("icons/square-o")?.toSet() ?: emptySet()
    }

    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val rootDirectory = remember(folderName) {
        if (!folderName.isNullOrEmpty()) File(downloadsDir, folderName) else downloadsDir
    }

    var currentDirectory by remember { mutableStateOf(rootDirectory) }
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
    var selectedFileForOptions by remember { mutableStateOf<File?>(null) }

    // 1. Fixed: Standard mutableStateOf / mutableIntStateOf initialization
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    val directoryContentState by produceState<DirectoryContent>(
        initialValue = DirectoryContent(emptyList(), emptyList()),
        currentDirectory,
        searchQuery,
        sortOption,
        showHiddenFiles,
        refreshTrigger
    ) {
        value = withContext(Dispatchers.IO) {
            val allContent = currentDirectory.listFiles()?.toList() ?: emptyList()

            val visibleContent = if (showHiddenFiles) {
                allContent
            } else {
                allContent.filter { !it.isHidden && !it.name.startsWith(".") }
            }

            val filtered = if (searchQuery.isBlank()) {
                visibleContent
            } else {
                visibleContent.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }

            val dirs = filtered.filter { it.isDirectory }
            val nonDirs = filtered.filter { it.isFile }

            val sortedDirs = when (sortOption) {
                SortOption.NAME_ASC -> dirs.sortedBy { it.name.lowercase() }
                SortOption.NAME_DESC -> dirs.sortedByDescending { it.name.lowercase() }
                SortOption.DATE_NEWEST -> dirs.sortedByDescending { it.lastModified() }
                SortOption.DATE_OLDEST -> dirs.sortedBy { it.lastModified() }
                SortOption.SIZE_LARGEST -> dirs.sortedByDescending { it.listFiles()?.size ?: 0 }
                SortOption.SIZE_SMALLEST -> dirs.sortedBy { it.listFiles()?.size ?: 0 }
            }

            val sortedFiles = when (sortOption) {
                SortOption.NAME_ASC -> nonDirs.sortedBy { it.name.lowercase() }
                SortOption.NAME_DESC -> nonDirs.sortedByDescending { it.name.lowercase() }
                SortOption.DATE_NEWEST -> nonDirs.sortedByDescending { it.lastModified() }
                SortOption.DATE_OLDEST -> nonDirs.sortedBy { it.lastModified() }
                SortOption.SIZE_LARGEST -> nonDirs.sortedByDescending { it.length() }
                SortOption.SIZE_SMALLEST -> nonDirs.sortedBy { it.length() }
            }

            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

            val folderStates = sortedDirs.map { folder ->
                FolderUiState(
                    file = folder,
                    name = folder.name,
                    itemCount = folder.listFiles()?.size ?: 0
                )
            }

            val fileStates = sortedFiles.map { file ->
                FileUiState(
                    file = file,
                    name = file.name,
                    extension = file.extension,
                    formattedSize = formatFileSize(file.length()),
                    formattedDate = dateFormat.format(Date(file.lastModified()))
                )
            }

            DirectoryContent(folderStates, fileStates)
        }

        isRefreshing = false
    }

    val (subfolders, files) = directoryContentState

    BackHandler(enabled = currentDirectory != rootDirectory && currentDirectory.parentFile != null) {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
        } else {
            currentDirectory = currentDirectory.parentFile!!
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
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
                                text = currentDirectory.name.ifEmpty { "Downloads" },
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
                                        Checkbox(
                                            checked = showHiddenFiles,
                                            onCheckedChange = null
                                        )
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
                    rootDirectory = rootDirectory,
                    currentDirectory = currentDirectory,
                    onDirectoryClick = { selectedDir ->
                        searchQuery = ""
                        isSearchActive = false
                        currentDirectory = selectedDir
                    }
                )
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                // 2. Fixed: Avoid '++' operator ambiguity on delegated Compose state properties
                refreshTrigger += 1
            },
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = subfolders,
                    key = { folderState -> folderState.file.absolutePath }
                ) { folderState ->
                    FolderCard(
                        folderState = folderState,
                        availableAssets = availableAssets,
                        imageLoader = imageLoader,
                        onClick = {
                            searchQuery = ""
                            isSearchActive = false
                            currentDirectory = folderState.file
                        }
                    )
                }

                items(
                    items = files,
                    key = { fileState -> fileState.file.absolutePath }
                ) { fileState ->
                    FileRowItem(
                        fileState = fileState,
                        availableAssets = availableAssets,
                        imageLoader = imageLoader,
                        onLongClick = { selectedFileForOptions = fileState.file }
                    )
                }
            }
        }

        selectedFileForOptions?.let { file ->
            AlertDialog(
                onDismissRequest = { selectedFileForOptions = null },
                title = { Text(text = file.name, style = MaterialTheme.typography.titleMedium) },
                text = { Text("Select an action for this file.", style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            openFile(context, file)
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
fun rememberSvgImageLoader(context: Context): ImageLoader {
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .crossfade(false) // Disabling crossfade prevents animation overhead during rapid scrolling
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

@Composable
fun BreadcrumbBar(
    rootDirectory: File,
    currentDirectory: File,
    onDirectoryClick: (File) -> Unit
) {
    val pathSegments = remember(currentDirectory, rootDirectory) {
        val segments = mutableListOf<File>()
        var curr: File? = currentDirectory
        while (curr != null) {
            segments.add(0, curr)
            if (curr == rootDirectory) break
            curr = curr.parentFile
        }
        segments
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        pathSegments.forEachIndexed { index, file ->
            val isLast = index == pathSegments.lastIndex
            val displayName = if (file == rootDirectory && file.name.equals("Download", ignoreCase = true)) {
                "Downloads"
            } else {
                file.name
            }

            Text(
                text = displayName,
                style = MaterialTheme.typography.labelMedium,
                color = if (isLast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable(!isLast) { onDirectoryClick(file) }
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

private fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun openFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val extension = file.extension.lowercase(Locale.getDefault())
        var mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

        if (mimeType == null) {
            mimeType = when (extension) {
                "pdf" -> "application/pdf"
                "txt", "log", "conf" -> "text/plain"
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
        Toast.makeText(context, "No app available to open ${file.name}", Toast.LENGTH_SHORT).show()
    }
}
