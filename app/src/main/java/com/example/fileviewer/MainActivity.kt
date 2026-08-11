package com.example.fileviewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
                FileBrowserScreen()
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
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    
    val rootDirectory = remember(folderName) {
        if (!folderName.isNullOrEmpty()) File(downloadsDir, folderName) else downloadsDir
    }

    var currentDirectory by remember { mutableStateOf(rootDirectory) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(SortOption.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    
    // File selected for the long-press options dialog
    var selectedFileForOptions by remember { mutableStateOf<File?>(null) }

    // Filter by search query and sort items dynamically
    val (subfolders, files) = remember(currentDirectory, searchQuery, sortOption) {
        val allContent = currentDirectory.listFiles()?.toList() ?: emptyList()
        
        // 1. Filter by search query (File name matching)
        val filtered = if (searchQuery.isBlank()) {
            allContent
        } else {
            allContent.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        // 2. Separate into subfolders and files
        val dirs = filtered.filter { it.isDirectory }
        val nonDirs = filtered.filter { it.isFile }

        // 3. Apply sorting rule
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

        Pair(sortedDirs, sortedFiles)
    }

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
            TopAppBar(
                title = {
                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search files...") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(text = currentDirectory.name.ifEmpty { "Downloads" })
                    }
                },
                actions = {
                    // Search Toggle Action
                    IconButton(onClick = {
                        isSearchActive = !isSearchActive
                        if (!isSearchActive) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search"
                        )
                    }

                    // Sort Menu Action
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Sort Options")
                    }

                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortOption.values().forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sortOption = option
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Render Subdirectories
            items(subfolders) { folder ->
                FolderCard(
                    folder = folder,
                    onClick = {
                        searchQuery = ""
                        isSearchActive = false
                        currentDirectory = folder
                    }
                )
            }

            // 2. Render File Items
            items(files) { file ->
                FileRowItem(
                    file = file,
                    onLongClick = { selectedFileForOptions = file }
                )
            }
        }

        // Long-Press Action Dialog
        selectedFileForOptions?.let { file ->
            AlertDialog(
                onDismissRequest = { selectedFileForOptions = null },
                title = { Text(text = file.name) },
                text = { Text("Select an action for this file.") },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folder: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemCount = folder.listFiles()?.size ?: 0
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "📁", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(text = folder.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "$itemCount items",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRowItem(
    file: File,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedSize = remember(file) { formatFileSize(file.length()) }
    val formattedDate = remember(file) {
        SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { /* Short click can be assigned to a preview or remain neutral */ },
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = getFileIcon(file), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Text(
                    text = "$formattedSize • $formattedDate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Helpers
private fun formatFileSize(sizeInBytes: Long): String {
    if (sizeInBytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(sizeInBytes.toDouble()) / Math.log10(1024.0)).toInt()
    return String.format(Locale.getDefault(), "%.1f %s", sizeInBytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun getFileIcon(file: File): String {
    return when (file.extension.lowercase()) {
        "pdf" -> "📕"
        "png", "jpg", "jpeg", "webp" -> "🖼️"
        "mp3", "wav", "ogg" -> "🎵"
        "mp4", "mkv" -> "🎬"
        "zip", "tar", "gz" -> "📦"
        "txt", "md" -> "📝"
        else -> "📄"
    }
}

private fun openFile(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val extension = file.extension.lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open ${file.name}", Toast.LENGTH_SHORT).show()
    }
}
