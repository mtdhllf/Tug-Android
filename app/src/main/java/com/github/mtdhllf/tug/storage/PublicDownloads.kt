package com.github.mtdhllf.tug.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.github.mtdhllf.tug.R
import java.io.File
import java.io.IOException
import java.util.Locale

/** 发布结果。 */
sealed interface PublishResult {
    data class Success(val location: String, val fileName: String) : PublishResult

    data class Failure(val message: String) : PublishResult
}

/**
 * 把下载完成的文件发布到用户可见的公共下载目录：`Download/Tug`。
 *
 * - Android 10+：写 `MediaStore.Downloads`，`RELATIVE_PATH = Download/Tug`，
 *   先以 `IS_PENDING=1` 写入，完成后再置 0，避免半成品被扫描；
 * - Android 9 及以下：直接写入公共下载目录（需要 `WRITE_EXTERNAL_STORAGE`）。
 *
 * 引擎本身只写私有暂存区，这里负责跨存储边界的搬运（S0 过渡方案；
 * S2 引擎重做后引入 Sink，可直接按 FD 写入目标）。
 */
object PublicDownloads {
    private const val FOLDER = "Tug"
    private const val COPY_BUFFER = 64 * 1024

    /** 用户可见目录（用于设置页展示）。 */
    val displayDir: String
        get() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"

    fun publish(context: Context, source: File, preferredName: String): PublishResult {
        if (!source.exists()) {
            return publishFailure(context, R.string.publish_source_missing, source.name)
        }
        val mime = mimeOf(preferredName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishViaMediaStore(context, source, preferredName, mime)
        } else {
            publishToLegacyDir(context, source, preferredName)
        }
    }

    /**
     * 发布 BitTorrent 多文件目录：`Download/Tug/<种子名>/...`。
     *
     * 目录发布后返回第一个文件的 URI（供"打开文件"使用）。
     */
    fun publishDirectory(context: Context, sourceDir: File, preferredName: String): PublishResult {
        if (!sourceDir.isDirectory) {
            return publishFailure(context, R.string.publish_dir_missing, sourceDir.name)
        }
        val files = sourceDir.walkTopDown().filter { it.isFile }.toList()
        if (files.isEmpty()) {
            return publishFailure(context, R.string.publish_dir_empty, sourceDir.name)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishDirViaMediaStore(context, sourceDir, files, preferredName)
        } else {
            publishDirToLegacy(context, sourceDir, preferredName)
        }
    }

    private fun publishDirViaMediaStore(
        context: Context,
        sourceDir: File,
        files: List<File>,
        preferredName: String,
    ): PublishResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val root = uniqueRelativeDir(resolver, collection, preferredName)
        var firstUri: Uri? = null
        var firstName: String? = null
        return try {
            for (file in files) {
                val sub = file.relativeTo(sourceDir).parentFile?.path
                    ?.replace('\\', '/')
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "$it/" }
                    ?: ""
                val relativePath = root + sub
                val finalName = uniqueMediaStoreName(resolver, collection, relativePath, file.name)
                val mime = mimeOf(finalName)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values)
                    ?: return publishFailure(context, R.string.publish_create_entry_failed, file.name)
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, COPY_BUFFER) }
                } ?: throw IOException(
                    context.getString(R.string.publish_open_output_failed, file.name),
                )
                resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                file.delete()
                if (firstUri == null) {
                    firstUri = uri
                    firstName = finalName
                }
            }
            sourceDir.deleteRecursively()
            if (firstUri == null || firstName == null) {
                publishFailure(context, R.string.publish_dir_failed)
            } else {
                PublishResult.Success(firstUri.toString(), firstName)
            }
        } catch (t: Throwable) {
            publishFailure(
                context,
                R.string.publish_save_dir_failed,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun publishDirToLegacy(
        context: Context,
        sourceDir: File,
        preferredName: String,
    ): PublishResult {
        @Suppress("DEPRECATION")
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(baseDir, FOLDER)
        if (!folder.exists() && !folder.mkdirs()) {
            return publishFailure(context, R.string.publish_create_dir_failed, folder.absolutePath)
        }
        val (base, _) = splitName(preferredName)
        var target = File(folder, base)
        var index = 1
        while (target.exists() && index <= 999) {
            target = File(folder, "$base ($index)")
            index++
        }
        return try {
            sourceDir.copyRecursively(target, overwrite = false)
            sourceDir.deleteRecursively()
            val first = target.walkTopDown().firstOrNull { it.isFile }
            PublishResult.Success(target.absolutePath, first?.name ?: target.name)
        } catch (t: Throwable) {
            runCatching { target.deleteRecursively() }
            publishFailure(
                context,
                R.string.publish_move_dir_failed,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun uniqueRelativeDir(
        resolver: android.content.ContentResolver,
        collection: Uri,
        preferredName: String,
    ): String {
        val base = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${preferredName.replace('/', '_')}"
        var candidate = "$base/"
        var index = 1
        while (mediaStoreDirExists(resolver, collection, candidate) && index <= 999) {
            candidate = "$base ($index)/"
            index++
        }
        return candidate
    }

    private fun mediaStoreDirExists(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
    ): Boolean {
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        ) ?: return false
        return cursor.use { it.count > 0 }
    }

    private fun publishViaMediaStore(
        context: Context,
        source: File,
        preferredName: String,
        mime: String,
    ): PublishResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
        val finalName = uniqueMediaStoreName(resolver, collection, relativePath, preferredName)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, finalName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: return publishFailure(context, R.string.publish_create_entry_generic_failed)

        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input ->
                    input.copyTo(output, COPY_BUFFER)
                }
            } ?: throw IOException(context.getString(R.string.publish_open_output_generic_failed))

            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            source.delete()
            PublishResult.Success(uri.toString(), finalName)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            publishFailure(
                context,
                R.string.publish_save_failed,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun uniqueMediaStoreName(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        preferredName: String,
    ): String {
        val (base, ext) = splitName(preferredName)
        var candidate = preferredName
        var index = 1
        while (mediaStoreNameExists(resolver, collection, relativePath, candidate) && index <= 999) {
            candidate = "$base ($index)$ext"
            index++
        }
        return candidate
    }

    private fun mediaStoreNameExists(
        resolver: android.content.ContentResolver,
        collection: Uri,
        relativePath: String,
        name: String,
    ): Boolean {
        val cursor = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, relativePath),
            null,
        ) ?: return false
        return cursor.use { it.count > 0 }
    }

    private fun publishToLegacyDir(
        context: Context,
        source: File,
        preferredName: String,
    ): PublishResult {
        @Suppress("DEPRECATION")
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(baseDir, FOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            return publishFailure(context, R.string.publish_create_dir_failed, dir.absolutePath)
        }
        val (base, ext) = splitName(preferredName)
        var target = File(dir, preferredName)
        var index = 1
        while (target.exists() && index <= 999) {
            target = File(dir, "$base ($index)$ext")
            index++
        }
        return try {
            if (!source.renameTo(target)) {
                source.copyTo(target, overwrite = false)
                source.delete()
            }
            PublishResult.Success(target.absolutePath, target.name)
        } catch (t: Throwable) {
            runCatching { if (target.exists()) target.delete() }
            publishFailure(
                context,
                R.string.publish_move_failed,
                t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun publishFailure(
        context: Context,
        resId: Int,
        vararg args: Any,
    ): PublishResult.Failure = PublishResult.Failure(context.getString(resId, *args))

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun splitName(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    }
}
