package com.example.photoclassifier

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FileHelper(private val context: Context) {

    fun getPhotosFromFolder(folderUri: Uri): List<PhotoItem> {
        val tree = DocumentFile.fromTreeUri(context, folderUri) ?: return emptyList()
        return tree.listFiles()
            .asSequence()
            .filter { it.isFile && it.type?.startsWith("image/") == true }
            .map { PhotoItem(it.uri, it.name ?: "unknown", it.type ?: "image/jpeg") }
            .take(2000)
            .toList()
    }

    suspend fun movePhoto(sourceUri: Uri, targetFolderUri: Uri, fileName: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val targetTree = DocumentFile.fromTreeUri(context, targetFolderUri)
                    ?: return@withContext false

                val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"

                var newFile = targetTree.createFile(mimeType, fileName)
                var finalName = fileName

                if (newFile == null) {
                    val ext = fileName.substringAfterLast('.', "")
                    val base = fileName.substringBeforeLast('.', fileName)
                    val timestamp = System.currentTimeMillis()
                    finalName = if (ext.isEmpty()) "${base}_$timestamp" else "${base}_$timestamp.$ext"
                    newFile = targetTree.createFile(mimeType, finalName)
                        ?: return@withContext false
                }

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                } ?: return@withContext false

                DocumentFile.fromSingleUri(context, sourceUri)?.delete()

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
