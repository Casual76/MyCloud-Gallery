package dev.pampa.mycloudgallery

/**
 * Release: nessun sink installato, quindi `GalleryLog` non scrive nulla. Non c'e' codice che possa
 * scrivere su logcat in questa build (l'implementazione esiste solo in src/debug).
 */
internal fun installLogging() = Unit
