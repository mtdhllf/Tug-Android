package com.github.mtdhllf.tug.ui.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.github.mtdhllf.tug.R
import java.io.File
import java.util.Locale

object FileOps {
    /** 打开已完成文件：同时支持 `content://`（MediaStore）与普通文件路径。 */
    fun openPath(context: Context, path: String) {
        if (path.startsWith("content://")) {
            val mime = guessMime(path.substringAfterLast('/'))
            openUri(context, Uri.parse(path), mime)
        } else {
            openFile(context, File(path))
        }
    }

    fun openFile(context: Context, file: File) {
        if (!file.exists()) return
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return
        openUri(context, uri, guessMime(file.name))
    }

    private fun openUri(context: Context, uri: Uri, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(fallback) }
        }
    }

    fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    /** 分享已完成文件（支持 `content://` 与私有/公共文件路径）。 */
    fun sharePath(context: Context, path: String) {
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    File(path),
                )
            }.getOrNull() ?: return
        }
        val mime = guessMime(path.substringAfterLast('/'))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(
                context.contentResolver,
                context.getString(R.string.clipboard_download_link),
                uri,
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(send, context.getString(R.string.share_file))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 从剪贴板提取 http(s)/magnet 链接。 */
    fun readLinkFromClipboard(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return null
        val item = clipboard.primaryClip?.getItemAt(0) ?: return null
        val text = item.coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val first = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            first.startsWith("http://", ignoreCase = true) ||
                first.startsWith("https://", ignoreCase = true) ||
                first.startsWith("magnet:", ignoreCase = true) -> first
            else -> null
        }
    }

    fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }
}
