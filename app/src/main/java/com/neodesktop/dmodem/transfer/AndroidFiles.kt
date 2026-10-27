// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package com.neodesktop.dmodem.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.IOException
import com.neodesktop.dmodem.io.Sink
import com.neodesktop.dmodem.io.asSink
import com.neodesktop.dmodem.io.asSource

/**
 * Received files into Downloads/D-Modem (MediaStore's, from Android 10;
 * before, the app's own Downloads folder, which needs no permission).
 */
class DownloadsIncoming(context: Context) : Incoming {
    private val ctx = context.applicationContext
    private val pending = HashMap<String, Uri>()
    private val folder: FolderIncoming? =
        if (Build.VERSION.SDK_INT < 29) FolderIncoming(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir) else null

    // (the stream is the caller's to close)
    @android.annotation.SuppressLint("Recycle")
    override fun create(name: String, size: Long): Pair<Sink, String> {
        if (Build.VERSION.SDK_INT < 29) return folder!!.create(name, size)
        val safe = FolderIncoming.safeName(name)
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
            ?: throw IOException("can't make $safe in Downloads")
        val out = ctx.contentResolver.openOutputStream(uri) ?: throw IOException("can't write $safe")
        // the name it got (MediaStore makes it "name (1).ext" when it's taken)
        val actual = ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: safe
        synchronized(pending) { pending[actual] = uri }
        return out.asSink() to actual
    }

    /** [name] finished: no longer pending in Downloads, its time set if known */
    override fun done(name: String, mtime: Long) {
        if (Build.VERSION.SDK_INT < 29) return folder!!.done(name, mtime)
        val uri = synchronized(pending) { pending.remove(name) } ?: return
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (mtime > 0) put(MediaStore.MediaColumns.DATE_MODIFIED, mtime)
        }
        runCatching { ctx.contentResolver.update(uri, v, null, null) }
    }

    /** [name]'s unfinished file deleted */
    override fun abandon(name: String) {
        if (Build.VERSION.SDK_INT < 29) return folder!!.abandon(name)
        val uri = synchronized(pending) { pending.remove(name) } ?: return
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    companion object {
        const val FOLDER = "D-Modem"

        /** where files go, for saying so */
        fun where(context: Context): String =
            if (Build.VERSION.SDK_INT >= 29) "Download/$FOLDER"
            else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.path ?: "the app's files"
    }
}

/** a document picked (the system's file picker's) to send */
fun outgoingOf(context: Context, uri: Uri): Outgoing {
    val r = context.contentResolver
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = -1L
    var mtime = 0L
    runCatching {
        r.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { i -> c.getString(i)?.let { name = it } }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { i -> size = c.getLong(i) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { i -> mtime = c.getLong(i) / 1000 }
            }
        }
    }
    return Outgoing(name, size, mtime) { (r.openInputStream(uri) ?: throw IOException("can't read $name")).asSource() }
}
