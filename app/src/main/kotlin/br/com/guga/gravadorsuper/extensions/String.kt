package br.com.guga.gravadorsuper.extensions

fun String?.isAudioMimeType(): Boolean {
    return this?.startsWith("audio") == true
}
