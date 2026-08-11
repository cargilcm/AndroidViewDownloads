package com.example.fileviewer

import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White
                ) {
                    FileBrowserScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkAndRequestStoragePermission()
    }

    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
}

@Composable
fun FileBrowserScreen() {
    val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    var items by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(Unit) {
        if (downloadDir.exists() && downloadDir.isDirectory) {
            items = downloadDir.listFiles()?.toList() ?: emptyList()
        }
    }

    val folders = items.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
    val files = items.filter { it.isFile }.sortedBy { it.name.lowercase() }
    val folderRows = folders.chunked(4)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Top Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .border(1.dp, Color.LightGray)
                    .background(Color.White)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(text = "/", fontSize = 16.sp)
            }
            Box(
                modifier = Modifier
                    .width(60.dp)
                    .height(24.dp)
                    .border(1.dp, Color.LightGray)
            )
        }

        // Combined Scrollable Body (Grid of folders on top, plain list of files below)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Folders Grid Container (Red Outline Style)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, Color.Red)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    folderRows.forEachIndexed { rowIndex, rowFolders ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (i in 0 until 4) {
                                if (i < rowFolders.size) {
                                    val folder = rowFolders[i]
                                    val isHighlight = (rowIndex == 0 && i == 0)
                                    FolderCard(
                                        name = folder.name,
                                        isHighlighted = isHighlight,
                                        modifier = Modifier.weight(1f)
                                        onClick = {
                                                        Toast.makeText(
                                                            context,
                                                            "Clicked ${folder.name}",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                    )
                                } else {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // Plain Files List
            items(files) { file ->
                FileRowItem(file = file)
            }
        }
    }
}
@Composable
fun FolderCard(
    name: String,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
    
) {
	val context = LocalContext.current
    val bgColor =
        if (isHighlighted) Color(0xFFA0CBE8)
        else Color(0xFFFAF2DA)

    Column(
        modifier = modifier
            .aspectRatio(0.9f)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color.Black,
            lineHeight = 13.sp,
            modifier = Modifier.fillMaxWidth()
        )

        Icon(
            imageVector = Icons.Default.Folder,
            contentDescription = null,
            tint = Color(0xFFE9C893),
            modifier = Modifier.size(42.dp)
        )
    }
}

@Composable
fun FileRowItem(file: File) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                clipboardManager.setText(AnnotatedString(file.name))
                Toast.makeText(context, "Copied file name", Toast.LENGTH_SHORT).show()
            },
            shape = RoundedCornerShape(2.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFEFEF)),
            modifier = Modifier.height(26.dp)
        ) {
            Text(text = "copy", color = Color.Black, fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = file.name,
            fontSize = 13.sp,
            color = Color.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
