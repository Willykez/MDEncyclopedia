package com.willykez.md

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.willykez.md.model.MdFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Owns every read/write of markdown content.
 *
 * The library now lives in the app's own local storage — NOT in assets/ —
 * so it can be edited, renamed, imported into and deleted at runtime:
 *
 *   getExternalFilesDir("md")   e.g. /Android/data/com.willykez.md/files/md
 *   falls back to               filesDir/md  when external storage is unavailable
 *
 * Both locations are app-specific: no runtime storage permission is needed
 * on any supported API level (24+). On first launch only, the bundled
 * assets/md/*.md files are copied in once to seed the library, exactly like
 * unzipping a starter pack — after that, assets/ is never touched again.
 */
object MarkdownStore {

    private lateinit var appContext: Context
    private lateinit var dir: File

    fun init(context: Context) {
        appContext = context.applicationContext
        dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "md")
        if (!dir.exists()) dir.mkdirs()
        seedFromAssetsIfEmpty()
    }

    val storageDirPath: String get() = dir.absolutePath

    // ── Seed ──────────────────────────────────────────────────────────
    private fun seedFromAssetsIfEmpty() {
        if (dir.listFiles { f -> f.extension == "md" }?.isNotEmpty() == true) return
        val prefs = appContext.getSharedPreferences("enc_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("seeded_once", false)) return
        try {
            val assetFiles = appContext.assets.list("md") ?: emptyArray()
            for ((i, name) in assetFiles.withIndex()) {
                if (!name.endsWith(".md")) continue
                appContext.assets.open("md/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
        } catch (_: Exception) {
            // No bundled assets shipped with this build — that's fine, the
            // library simply starts empty and the user creates/imports files.
        } finally {
            prefs.edit().putBoolean("seeded_once", true).apply()
        }
    }

    // ── Listing ───────────────────────────────────────────────────────
    fun listFiles(): List<MdFile> {
        val files = dir.listFiles { f -> f.isFile && f.extension.equals("md", true) }
            ?.sortedBy { it.name } ?: emptyList()
        return files.mapIndexed { idx, f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            val title = firstHeading(text) ?: f.nameWithoutExtension.replace('_', ' ')
            val badge = badgeFor(f.nameWithoutExtension, text)
            MdFile(f, title, badge, idx)
        }
    }

    private fun firstHeading(text: String): String? =
        text.lineSequence().firstOrNull { it.trimStart().startsWith("#") }
            ?.trimStart()?.trimStart('#')?.trim()?.takeIf { it.isNotEmpty() }

    private fun badgeFor(name: String, text: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains("index") -> "START"
            lower.contains("material2") -> "MD2"
            lower.contains("material3") -> "MD3"
            lower.contains("androidx") -> "X"
            else -> {
                val words = text.split(Regex("\\s+")).size
                "${(words / 200).coerceAtLeast(1)} MIN"
            }
        }
    }

    // ── Read / write ─────────────────────────────────────────────────
    fun read(file: File): String = runCatching { file.readText(StandardCharsets.UTF_8) }
        .getOrElse { "# ⚠ Load Error\n\n> Could not read `${file.name}`\n\n${it.message}" }

    fun write(file: File, content: String) {
        file.writeText(content, StandardCharsets.UTF_8)
    }

    fun createNew(baseName: String): File {
        var candidate = File(dir, "${sanitize(baseName)}.md")
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "${sanitize(baseName)}_$n.md")
            n++
        }
        candidate.writeText("# ${baseName.trim()}\n\n", StandardCharsets.UTF_8)
        return candidate
    }

    fun rename(file: File, newBaseName: String): File {
        val target = File(dir, "${sanitize(newBaseName)}.md")
        if (target.exists() && target != file) return file
        file.renameTo(target)
        return target
    }

    fun delete(file: File): Boolean = file.delete()

    private fun sanitize(name: String): String =
        name.trim().ifEmpty { "untitled" }.replace(Regex("[^A-Za-z0-9._ -]"), "").replace(' ', '_')

    // ── Import from anywhere via Storage Access Framework ───────────────
    fun importFrom(resolver: ContentResolver, uri: Uri): File? {
        val name = queryDisplayName(resolver, uri) ?: "imported_${System.currentTimeMillis()}.md"
        val safeName = if (name.endsWith(".md")) name else "$name.md"
        var target = File(dir, safeName)
        var n = 1
        while (target.exists()) {
            target = File(dir, safeName.removeSuffix(".md") + "_$n.md")
            n++
        }
        return try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Export / share a copy out via FileProvider ──────────────────────
    fun exportUri(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
}
