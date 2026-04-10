package com.mycloudgallery.core.network

import java.io.InputStream

/**
 * Interfaccia client WebDAV — astrae l'implementazione concreta (OkHttp).
 * Permette sostituzione futura se necessario.
 */
interface WebDavClient {

    /**
     * PROPFIND ricorsivo: elenca file e cartelle in [path].
     * @param depth "0" per solo la risorsa, "1" per figli diretti, "infinity" per tutto
     */
    suspend fun propFind(path: String, depth: String = "1"): List<WebDavResource>

    /** Scarica il contenuto del file al [path] */
    suspend fun get(path: String): InputStream

    /** Scarica un range di byte del file (per streaming video) */
    suspend fun getRange(path: String, start: Long, end: Long): InputStream

    /** Carica [inputStream] al [path] specificato */
    suspend fun put(path: String, inputStream: InputStream, contentType: String, contentLength: Long)

    /** Elimina la risorsa al [path] */
    suspend fun delete(path: String)

    /** Crea cartella al [path] */
    suspend fun mkcol(path: String)

    /** Copia risorsa da [sourcePath] a [destinationPath] */
    suspend fun copy(sourcePath: String, destinationPath: String)

    /** Sposta risorsa da [sourcePath] a [destinationPath] */
    suspend fun move(sourcePath: String, destinationPath: String)
}

data class WebDavResource(
    val path: String,
    val displayName: String,
    val isDirectory: Boolean,
    val contentType: String?,
    val contentLength: Long,
    val lastModified: Long,
    val etag: String?,
)
