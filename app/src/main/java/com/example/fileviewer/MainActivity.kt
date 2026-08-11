package com.example.fileviewer

import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                // Call without arguments to default to root Downloads,
                // or pass a subfolder name like: FileBrowserScreen("Invoices")
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
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    
    // Resolve starting path dynamically based on optional folderName
    val rootDirectory = remember(folderName) {
        if (!folderName.isNullOrEmpty()) File(downloadsDir, folderName) else downloadsDir
    }

    // Single reactive state driving directory updates without stack bloating
    var currentDirectory by remember { mutableStateOf(rootDirectory) }

    // Read directory items sorted with folders first
    val files = remember(currentDirectory) {
        currentDirectory.listFiles()?.toList()?.sortedWith(
            compareBy({ !it.isDirectory }, { it.name.lowercase() })
        ) ?: emptyList()
    }

    // Intercept system Back button to move UP one folder level
    BackHandler(enabled = currentDirectory != rootDirectory && currentDirectory.parentFile != null) {
        currentDirectory = currentDirectory.parentFile!!
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = currentDirectory.name.ifEmpty { "Downloads" }) }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            items(files) { file ->
                FolderCard(
                    file = file,
                    onClick = {
                        if (file.isDirectory) {
                            // Update state to trigger smooth UI refresh
                            currentDirectory = file
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FolderCard(
    file: File,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (file.isDirectory) "📁 ${file.name}" else "📄 ${file.name}",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
