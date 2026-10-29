package io.github.abooodbah.leanrows

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** A file read with pread, which is safe from several threads at once. */
class DescriptorSource(private val descriptor: ParcelFileDescriptor, override val size: Long) : ByteSource, Closeable {
    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return try {
            Os.pread(descriptor.fileDescriptor, into, offset, length, position)
        } catch (error: ErrnoException) {
            throw IOException(error)
        }
    }

    override fun close() = descriptor.close()
}

class OpenedFile(val source: DescriptorSource, val name: String, val copied: Boolean)

class NotEnoughSpace : IOException("not enough free space for a copy")

/**
 * Opens a content URI for random access. Most providers hand over a real
 * file. Some only give a pipe (mail apps, archives, cloud apps), which cannot
 * be read out of order, so that stream is copied into the app's cache first.
 * The copy is unlinked as soon as it is open, so nothing is left behind.
 */
object Sources {
    /** Space to leave free on the device when copying a stream. */
    const val RESERVE_BYTES = 64L * 1024 * 1024

    fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (error: RuntimeException) {
        // Some providers throw for columns they do not know.
        null
    }

    fun open(
        resolver: ContentResolver,
        uri: Uri,
        cacheDir: File,
        cancelled: () -> Boolean,
        copying: (Long) -> Unit,
    ): OpenedFile {
        val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "file"
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw IOException("no file descriptor")
        val regular = try {
            OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)
        } catch (error: ErrnoException) {
            false
        }
        if (regular) {
            return OpenedFile(DescriptorSource(descriptor, descriptor.statSize), name, copied = false)
        }
        return OpenedFile(copy(descriptor, cacheDir, cancelled, copying), name, copied = true)
    }

    private fun copy(
        descriptor: ParcelFileDescriptor,
        cacheDir: File,
        cancelled: () -> Boolean,
        copying: (Long) -> Unit,
    ): DescriptorSource {
        val folder = File(cacheDir, "streams").apply { mkdirs() }
        val file = File.createTempFile("open", ".bin", folder)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total % (8L * 1024 * 1024) < read) {
                            if (folder.usableSpace < RESERVE_BYTES) throw NotEnoughSpace()
                            copying(total)
                        }
                    }
                }
            }
            val copy = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return DescriptorSource(copy, copy.statSize)
        } finally {
            file.delete()
        }
    }

    /**
     * Removes copies left by a process that was killed while copying. Only
     * old ones: another window of the app may be copying right now.
     */
    fun clean(cacheDir: File, now: Long = System.currentTimeMillis()) {
        File(cacheDir, "streams").listFiles()?.forEach { file ->
            if (now - file.lastModified() > 60 * 60 * 1000L) file.delete()
        }
    }
}
