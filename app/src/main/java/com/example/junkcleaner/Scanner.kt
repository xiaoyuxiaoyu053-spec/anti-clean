package com.example.junkcleaner

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

enum class Category(val label: String, val defaultSelected: Boolean) {
    CACHE("缓存文件", true),
    TEMP("临时 / 日志文件", true),
    EMPTY("空文件 / 空文件夹", true),
    APK("安装包 (apk)", true),
    ARCHIVE("压缩包 (zip / 7z / rar…)", false),
    TEXT("文本文件 (txt)", false)
}

class JunkItem(val file: File, val size: Long, val category: Category) {
    var selected by mutableStateOf(category.defaultSelected)
}

fun hasStoragePermission(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}

object JunkScanner {
    private val tempExt = setOf("tmp", "temp", "log", "bak", "old", "thumbdata", "dmp", "chk", "crdownload", "part")
    private val archiveExt = setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "tgz")
    private val keepEmptyTop = setOf(
        "DCIM", "Download", "Pictures", "Music", "Movies", "Documents",
        "Alarms", "Notifications", "Podcasts", "Ringtones", "Android"
    )

    suspend fun scan(ctx: Context, onProgress: (Int) -> Unit): List<JunkItem> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<JunkItem>()
            val root = Environment.getExternalStorageDirectory()
            var count = 0

            // 1) 缓存：本应用缓存 + Android/data/*/cache（Android 11+ 系统限制，可能读不到其他应用）
            val cacheDirs = LinkedHashMap<String, File>()
            ctx.cacheDir?.let { cacheDirs[it.absolutePath] = it }
            ctx.externalCacheDir?.let { cacheDirs[it.absolutePath] = it }
            File(root, "Android/data").listFiles()?.forEach { app ->
                val c = File(app, "cache")
                if (c.isDirectory) cacheDirs[c.absolutePath] = c
            }
            for (c in cacheDirs.values) {
                coroutineContext.ensureActive()
                val s = dirSize(c)
                if (s > 0) out += JunkItem(c, s, Category.CACHE)
            }

            // 2) 遍历存储（跳过 Android 目录与隐藏目录，不触碰系统分区）
            fun walk(dir: File) {
                val children = dir.listFiles() ?: return
                if (children.isEmpty()) {
                    val topLevel = dir.parentFile == root
                    if (dir != root && !(topLevel && dir.name in keepEmptyTop)) {
                        out += JunkItem(dir, 0, Category.EMPTY)
                    }
                    return
                }
                for (f in children) {
                    if (f.isDirectory) {
                        if (f.canonicalPath != f.absolutePath) continue // 跳过符号链接
                        val name = f.name
                        if (name == "Android") continue
                        if (name.startsWith(".")) {
                            if (name == ".thumbnails" || name == ".cache") {
                                val s = dirSize(f)
                                if (s > 0) out += JunkItem(f, s, Category.CACHE)
                            }
                            continue
                        }
                        walk(f)
                    } else {
                        count++
                        if (count % 50 == 0) onProgress(count)
                        val ext = f.extension.lowercase()
                        val len = f.length()
                        val cat = when {
                            len == 0L -> Category.EMPTY
                            ext in tempExt -> Category.TEMP
                            ext in archiveExt -> Category.ARCHIVE
                            ext == "txt" -> Category.TEXT
                            ext == "apk" -> Category.APK
                            else -> null
                        }
                        if (cat != null) out += JunkItem(f, len, cat)
                    }
                }
            }
            walk(root)
            onProgress(count)
            out
        }

    private fun dirSize(d: File): Long =
        d.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun delete(item: JunkItem): Boolean = try {
        val f = item.file
        when {
            item.category == Category.CACHE && f.isDirectory -> {
                f.listFiles()?.forEach { it.deleteRecursively() }
                true
            }
            f.isDirectory -> f.deleteRecursively()
            else -> f.delete()
        }
    } catch (e: Exception) {
        false
    }
}
