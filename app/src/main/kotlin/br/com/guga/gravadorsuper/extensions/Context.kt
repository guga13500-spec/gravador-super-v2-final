package br.com.guga.gravadorsuper.extensions

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.graphics.createBitmap
import androidx.documentfile.provider.DocumentFile
import org.fossify.commons.extensions.createFirstParentTreeUri
import org.fossify.commons.extensions.createSAFDirectorySdk30
import org.fossify.commons.extensions.getDocumentSdk30
import org.fossify.commons.extensions.getDoesFilePathExistSdk30
import org.fossify.commons.extensions.getDuration
import org.fossify.commons.extensions.getFilenameFromPath
import org.fossify.commons.extensions.getMimeType
import org.fossify.commons.extensions.getParentPath
import org.fossify.commons.extensions.getSAFDocumentId
import org.fossify.commons.extensions.internalStoragePath
import org.fossify.commons.extensions.isAudioFast
import android.os.Handler
import android.os.Looper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isRPlus
import org.fossify.commons.helpers.isTiramisuPlus
import br.com.guga.gravadorsuper.R
import br.com.guga.gravadorsuper.helpers.Config
import br.com.guga.gravadorsuper.helpers.EXTRA_RECORDING
import br.com.guga.gravadorsuper.helpers.getAudioMimeType
import br.com.guga.gravadorsuper.models.Recording
import java.io.File
import java.util.*
import kotlin.collections.ArrayList

val Context.config: Config get() = Config.newInstance(this)

val Context.trashFolder: String get() = "${config.saveRecordingsFolder}/.trash"

fun Context.getAllRecordings(trashed: Boolean = false): ArrayList<Recording> {
    return if (isRPlus()) {
        val recordings = arrayListOf<Recording>()
        recordings.addAll(getRecordingsInternal(trashed))
        if (trashed) {
            // Return recordings trashed using MediaStore, this won't be needed in the future
            @Suppress("DEPRECATION")
            recordings.addAll(getMediaStoreTrashedRecordingsInternal())
        }

        recordings
    } else {
        getLegacyRecordingsInternal(trashed)
    }
}

private fun Context.getRecordingsInternal(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) trashFolder else config.saveRecordingsFolder
    val files = getDocumentSdk30(folder)?.listFiles() ?: return recordings
    files.forEach { file ->
        if (file.isAudioRecording()) {
            recordings.add(
                readRecordingFromFile(file)
            )
        }
    }

    return recordings
}

@Deprecated(
    message = "Use getRecordingsInternal instead. This method is only here for backward compatibility.",
    replaceWith = ReplaceWith("getRecordingsInternal(trashed = true)")
)
private fun Context.getMediaStoreTrashedRecordingsInternal(): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = config.saveRecordingsFolder
    val documentFiles = getDocumentSdk30(folder)?.listFiles() ?: return recordings
    documentFiles.forEach { file ->
        if (file.isTrashedMediaStoreRecording()) {
            val recording = readRecordingFromFile(file)
            recordings.add(
                recording.copy(
                    title = "^\\.trashed-\\d+-".toRegex().replace(file.name!!, "")
                )
            )
        }
    }

    return recordings
}

private fun Context.getLegacyRecordingsInternal(trashed: Boolean = false): ArrayList<Recording> {
    val recordings = ArrayList<Recording>()
    val folder = if (trashed) {
        trashFolder
    } else {
        config.saveRecordingsFolder
    }
    val files = File(folder).listFiles() ?: return recordings

    files.filter { it.isAudioFast() }.forEach {
        val id = it.hashCode()
        val title = it.name
        val path = it.absolutePath
        val timestamp = it.lastModified()
        val duration = getDuration(it.absolutePath) ?: 0
        val size = it.length().toInt()
        recordings.add(
            Recording(
                id = id,
                title = title,
                path = path,
                timestamp = timestamp,
                duration = duration,
                size = size
            )
        )
    }
    return recordings
}

private fun DocumentFile.isAudioRecording(): Boolean {
    val name = name?.lowercase(Locale.ROOT) ?: return false
    return name.endsWith(".wav") || name.endsWith(".m4a")
}

private fun DocumentFile.isTrashedMediaStoreRecording(): Boolean {
    return name?.startsWith(".trashed") ?: false
}

private fun Context.readRecordingFromFile(file: DocumentFile): Recording {
    val id = file.uri.hashCode()
    val title = file.name ?: ""
    val path = file.uri.toString()
    val timestamp = file.lastModified()
    val duration = getDuration(file.uri) ?: 0
    val size = file.length().toInt()
    return Recording(id, title, path, timestamp, duration, size)
}

fun Context.getDuration(uri: Uri): Int? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(this, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt()?.div(1000)
    } catch (e: Exception) {
        null
    } finally {
        retriever.release()
    }
}

// move to commons in the future
fun Context.getFormattedFilename(): String {
    val pattern = config.filenamePattern
    val calendar = Calendar.getInstance()

    val year = calendar.get(Calendar.YEAR).toString()
    val month = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MONTH) + 1)
    val day = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.DAY_OF_MONTH))
    val hour = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.HOUR_OF_DAY))
    val minute = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.MINUTE))
    val second = String.format(Locale.ROOT, "%02d", calendar.get(Calendar.SECOND))

    return pattern
        .replace("%Y", year, false)
        .replace("%M", month, false)
        .replace("%D", day, false)
        .replace("%h", hour, false)
        .replace("%m", minute, false)
        .replace("%s", second, false)
}

fun Context.getRecordings(callback: (ArrayList<Recording>) -> Unit) {
    ensureBackgroundThread {
        val recordings = getAllRecordings().apply { sortByDescending { it.timestamp } }
        Handler(Looper.getMainLooper()).post {
            callback(recordings)
        }
    }
}
