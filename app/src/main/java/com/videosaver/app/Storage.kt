package com.videosaver.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

object Storage {
    fun prefs(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** Copia o arquivo baixado para a pasta escolhida (ou para Downloads/Video Saver). */
    fun save(ctx: Context, src: File): String {
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(src.extension.lowercase()) ?: "application/octet-stream"

        val tree = prefs(ctx).getString("tree", null)
        if (tree != null) {
            val dir = DocumentFile.fromTreeUri(ctx, Uri.parse(tree))
            if (dir != null && dir.canWrite()) {
                val dst = dir.createFile(mime, src.name)
                    ?: throw IOException("Não foi possível criar o arquivo na pasta escolhida")
                ctx.contentResolver.openOutputStream(dst.uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IOException("Não foi possível gravar na pasta escolhida")
                return dst.name ?: src.name
            }
        }

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, src.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Video Saver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Não foi possível criar o arquivo em Downloads")
        resolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("Não foi possível gravar em Downloads")
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Downloads/Video Saver/" + src.name
    }
}
