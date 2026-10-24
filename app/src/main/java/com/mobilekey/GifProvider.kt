package com.mobilekey

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Serves your saved GIFs and stickers to the app you're typing in, only when you send one.
 * Not exported: apps get one-time read access through the keyboard's commitContent grant.
 */
class GifProvider : ContentProvider() {
    private fun file(uri: Uri): File? {
        val online = uri.pathSegments.firstOrNull() == "online"
        val dir = if (online) File(context!!.cacheDir, "online_gifs") else File(context!!.filesDir, "gifs")
        val f = File(dir, uri.lastPathSegment ?: return null)
        return f.takeIf { it.exists() && it.parentFile?.canonicalPath == dir.canonicalPath }
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String = typeOf(uri.lastPathSegment ?: "")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri) ?: throw java.io.FileNotFoundException(), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            if (f != null) addRow(arrayOf(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.mobilekey.gifs"
        fun uri(f: File): Uri =
            if (f.parentFile?.name == "online_gifs") Uri.parse("content://$AUTHORITY/online/${f.name}")
            else Uri.parse("content://$AUTHORITY/${f.name}")
        fun typeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "image/gif"
        }
    }
}
