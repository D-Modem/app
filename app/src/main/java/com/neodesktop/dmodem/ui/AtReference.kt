// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * D-Modem's AT command reference (the A5 manual, D-Modem-RE's
 * docs/at_commands.pdf), in the app's assets: out of them into the cache
 * for PdfRenderer and PDF apps, which want a file.
 */
object AtReference {
    /** the PDF in the app's assets */
    const val ASSET = "docs/at_commands.pdf"

    /** the PDF as a file in the cache (copied from the assets the first time, and again after an update) */
    fun file(context: Context): File {
        val f = File(context.cacheDir, ASSET)
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (!f.exists() || f.length() == 0L || f.lastModified() < installed) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            context.assets.open(ASSET).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        }
        return f
    }

    /** the PDF opened in the phone's PDF app (a toast if there's none) */
    fun openExternally(context: Context) {
        runCatching {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file(context))
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure { Toast.makeText(context, "No app to open PDFs", Toast.LENGTH_LONG).show() }
    }
}

/** the reference's pages, rendered one at a time (PdfRenderer opens one page at once) */
private class PdfPages(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf = PdfRenderer(fd)
    private val lock = Mutex()
    private var closed = false

    /** how many pages */
    val count: Int = pdf.pageCount

    /** each page's height over its width */
    val aspects: List<Float> = (0 until count).map { i -> pdf.openPage(i).use { it.height.toFloat() / it.width } }

    /** page [i] rendered [width] pixels wide, on white (null: closed) */
    suspend fun render(i: Int, width: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            pdf.openPage(i).use { pg ->
                val bmp = Bitmap.createBitmap(width, (width * pg.height.toFloat() / pg.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    /** the renderer and its file closed (after a page being rendered) */
    suspend fun close() = lock.withLock {
        closed = true
        pdf.close()
        fd.close()
    }
}

/**
 * The AT command reference: its pages one under another, the screen's
 * width (zoomed in, wider, scrolling sideways too); the page shown, the
 * zoom, and open in a PDF app (for its search and its contents' links).
 */
@Composable
fun AtReferenceViewer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<PdfPages?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { PdfPages(AtReference.file(context.applicationContext)) } }
            .onSuccess { pages = it }
            .onFailure { failed = it.message ?: it.toString() }
    }
    DisposableEffect(Unit) {
        onDispose { pages?.let { p -> CoroutineScope(Dispatchers.IO).launch { p.close() } } }
    }
    var zoom by remember { mutableStateOf(1f) }
    val list = rememberLazyListState()
    val shown by remember { derivedStateOf { list.firstVisibleItemIndex + 1 } }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                pages?.let { "Page $shown of ${it.count}" } ?: "",
                Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("−") }
            Text("${(zoom * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(3.375f) }, enabled = zoom < 3.3f) { Text("+") }
            TextButton(onClick = { AtReference.openExternally(context) }) { Text("Open in PDF app") }
        }
        val p = pages
        when {
            failed != null -> Text("Can't open the reference: $failed", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                val w = maxWidth * zoom
                // (pages rendered at the pixels they're shown at, up to a size a bitmap can be)
                val px = with(LocalDensity.current) { (w - 16.dp).roundToPx() }.coerceIn(1, 2000)
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState(), enabled = zoom > 1f)) {
                    LazyColumn(
                        Modifier.width(w).fillMaxSize(),
                        state = list,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    ) {
                        items(p.count, key = { it }) { i -> PdfPage(p, i, px) }
                    }
                }
            }
        }
    }
}

/** page [i] of [pages], [px] pixels wide, white until it's rendered */
@Composable
private fun PdfPage(pages: PdfPages, i: Int, px: Int) {
    val bmp by produceState<Bitmap?>(null, pages, i, px) { value = pages.render(i, px) }
    val ratio = 1f / pages.aspects[i]
    Surface(Modifier.fillMaxWidth().aspectRatio(ratio), color = androidx.compose.ui.graphics.Color.White, shadowElevation = 1.dp) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Page ${i + 1}", Modifier.fillMaxSize()) }
    }
}

/** the reference full screen, over the app (the terminal's menu opens it); [onDismiss] closes it */
@Composable
fun AtReferenceDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text("AT commands", style = MaterialTheme.typography.titleLarge)
                }
                AtReferenceViewer(Modifier.weight(1f))
            }
        }
    }
}
