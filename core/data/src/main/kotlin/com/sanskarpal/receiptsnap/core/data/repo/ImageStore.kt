package com.sanskarpal.receiptsnap.core.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * App-private receipt images. The full-size file is written (and fsynced)
 * before any database row points at it, and deleted only after the server
 * confirms the upload. A small thumbnail stays for the UI.
 */
@Singleton
class ImageStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "receipts").apply { mkdirs() }

    data class Saved(val imagePath: String, val thumbnailPath: String)

    fun save(id: String, source: File): Saved = save(id, BitmapFactory.decodeFile(source.path) ?: error("Unreadable image"))

    fun save(id: String, bytes: ByteArray): Saved =
        save(id, BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Unreadable image"))

    private fun save(id: String, bitmap: Bitmap): Saved {
        val full = File(dir, "$id.jpg")
        val thumb = File(dir, "$id.thumb.jpg")
        write(scaled(bitmap, LONG_EDGE), full, 88)
        write(scaled(bitmap, THUMB_EDGE), thumb, 80)
        return Saved(full.path, thumb.path)
    }

    fun delete(path: String?) {
        if (path != null) File(path).delete()
    }

    private fun scaled(b: Bitmap, longEdge: Int): Bitmap {
        val edge = max(b.width, b.height)
        if (edge <= longEdge) return b
        val ratio = longEdge.toFloat() / edge
        return Bitmap.createScaledBitmap(b, (b.width * ratio).toInt(), (b.height * ratio).toInt(), true)
    }

    private fun write(b: Bitmap, to: File, quality: Int) {
        val tmp = File(to.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            b.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.fd.sync() // on disk before the row that references it exists
        }
        check(tmp.renameTo(to)) { "Could not move ${tmp.name} into place" }
    }

    companion object {
        const val LONG_EDGE = 1600
        const val THUMB_EDGE = 400
    }
}
