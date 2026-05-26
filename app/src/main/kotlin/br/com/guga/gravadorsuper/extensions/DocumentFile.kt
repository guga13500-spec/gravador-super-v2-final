package br.com.guga.gravadorsuper.extensions

import androidx.documentfile.provider.DocumentFile

import java.util.Locale

fun DocumentFile.isAudioRecording(): Boolean {
    val name = name?.lowercase(Locale.ROOT) ?: return false
    if (name.startsWith(".") || name.isEmpty()) return false
    return type?.startsWith("audio") == true || name.endsWith(".wav") || name.endsWith(".m4a")
}

fun DocumentFile.isTrashedMediaStoreRecording(): Boolean {
    return name?.startsWith(".trashed") == true
}
