package io.github.qwwqeras1145.mgq

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Mirrors the user-selected game folder onto app-private storage.
 *
 * ## Why a mirror instead of reading the tree in place
 *
 * `nscript.dat` and the `.nsa` archives are read with **random access**. The Storage Access
 * Framework exposes streams, not seekable files, and the `.nsa` reader needs to seek to arbitrary
 * payload offsets (which can be hundreds of megabytes into a 1.7 GB archive). Mirroring once is both
 * simpler and far faster than re-opening a `ContentResolver` stream per asset.
 *
 * The mirror is written only for the files the engine actually needs:
 *  - `nscript.dat` (10 MB) and the `*.nsa` archives (payload),
 *  - the loose `system\` folder (46 MB of window graphics and fonts).
 *
 * Audio and background images inside the archives are streamed directly out of the mirrored `.nsa`
 * via the engine's own random-access reader, so they are not copied twice.
 *
 * A `.complete` marker records a finished copy so subsequent launches skip the work entirely.
 */
class UriGameStorage(private val context: Context, private val tree: Uri) {

    private val root: File = File(context.filesDir, "game")

    /**
     * Ensure the mirror is complete and return the directory the engine should use.
     *
     * @param progress called with human-readable status while copying, on the calling thread.
     */
    fun materialise(progress: (String) -> Unit): File {
        val marker = File(root, ".complete")
        if (marker.isFile) {
            progress("使用已缓存的数据：${root.absolutePath}")
            return root
        }
        root.mkdirs()

        val doc = DocumentFile.fromTreeUri(context, tree)
            ?: throw IllegalStateException("无法打开所选目录")

        val wanted = mutableListOf<DocumentFile>()
        doc.listFiles().forEach { f ->
            val n = f.name?.lowercase() ?: return@forEach
            when {
                n == "nscript.dat" -> wanted.add(f)
                n.endsWith(".nsa") -> wanted.add(f)
                n == "system" -> wanted.add(f)
            }
        }
        if (wanted.none { it.name?.lowercase() == "nscript.dat" }) {
            throw IllegalStateException("所选目录里没有 nscript.dat，请选择游戏根目录")
        }

        var copied = 0
        for (f in wanted) {
            val target = File(root, f.name ?: continue)
            if (f.isDirectory) {
                copyTree(f, target) { copied++ }
            } else {
                copyFile(f, target)
                copied++
            }
            progress("已复制 $copied 项：${f.name}")
        }
        marker.writeText("ok")
        progress("数据已就绪：${root.absolutePath}")
        return root
    }

    private fun copyTree(src: DocumentFile, dst: File, onFile: () -> Unit) {
        dst.mkdirs()
        src.listFiles().forEach { child ->
            val target = File(dst, child.name ?: return@forEach)
            if (child.isDirectory) {
                copyTree(child, target, onFile)
            } else {
                copyFile(child, target)
                onFile()
            }
        }
    }

    private fun copyFile(src: DocumentFile, dst: File) {
        if (dst.isFile && dst.length() == src.length()) return   // already mirrored
        dst.parentFile?.mkdirs()
        context.contentResolver.openInputStream(src.uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        } ?: throw IllegalStateException("无法读取 ${src.name}")
    }

    /** Free space check so a large game does not silently fill the device. */
    fun requiredBytes(): Long = root.usableSpace

    /** Remove the mirror, e.g. when the user switches to a different game folder. */
    fun clear() {
        root.deleteRecursively()
    }
}
