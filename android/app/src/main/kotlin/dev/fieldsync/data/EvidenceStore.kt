package dev.fieldsync.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.fieldsync.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest

/** App-private photos survive restarts. Operations contain metadata, never image bytes. */
class EvidenceStore(
    private val context: Context,
    private val repository: TaskRepository,
    private val directory: File = File(context.filesDir, "photos"),
) {
    init { directory.mkdirs() }
    fun file(hash: String): File {
        require(photoHash.matches(hash))
        return File(directory, hash)
    }

    suspend fun attach(task: String, uri: Uri, caption: String = "") = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            input.readBytesBounded(MAX_PHOTO_BYTES)
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Choose a supported image" }
        options.inJustDecodeBounds = false
        while (maxOf(options.outWidth, options.outHeight) / maxOf(1, options.inSampleSize) > 2048) {
            options.inSampleSize = maxOf(1, options.inSampleSize) * 2
        }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
        bitmap.recycle()
        val jpeg = output.toByteArray()
        val hash = sha256(jpeg)
        receive(hash, jpeg)
        repository.edit(task, "photo:$hash", wireJson.encodeToString(Photo(hash, jpeg.size.toLong(), caption)))
    }

    suspend fun receive(hash: String, bytes: ByteArray, fromCloud: Boolean = false) = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_PHOTO_BYTES && sha256(bytes) == hash) { "Incomplete or corrupt photo" }
        val destination = file(hash)
        if (!destination.exists()) {
            val temp = File.createTempFile("receive-", ".tmp", directory)
            try {
                temp.writeBytes(bytes)
                check(temp.renameTo(destination) || destination.exists()) { "Cannot save photo" }
            } finally { temp.delete() }
        }
        repository.putMedia(hash, bytes.size.toLong())
        if (fromCloud) repository.acknowledgeMedia(hash)
    }

    suspend fun references(): List<Photo> = repository.tasks.first().flatMap { it.visiblePhotos }.distinctBy { it.hash }
    suspend fun available(): List<Photo> = references().filter { file(it.hash).isFile }
    suspend fun missing(): List<Photo> = references().filterNot { file(it.hash).isFile }
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

fun java.io.InputStream.readBytesBounded(limit: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "Transfer is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
