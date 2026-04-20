package com.mycloudgallery.core.network

import com.mycloudgallery.domain.model.NetworkMode
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementazione di [WebDavClient] che instrada le richieste al client corretto
 * in base alla modalità di rete rilevata da [NetworkDetector].
 *
 * - LOCAL -> [SmbClientImpl] (SMBJ per velocità estrema in LAN)
 * - RELAY -> [WebDavClientImpl] (OkHttp per accesso remoto via relay WD)
 * - OFFLINE -> Lancia [OfflineException]
 */
@Singleton
class TripleModeClient @Inject constructor(
    private val networkDetector: NetworkDetector,
    private val smbClient: SmbClientImpl,
    private val webDavClient: WebDavClientImpl,
) : WebDavClient {

    private val activeClient: WebDavClient
        get() = when (networkDetector.networkMode.value) {
            NetworkMode.LOCAL -> smbClient
            NetworkMode.RELAY -> webDavClient
            NetworkMode.OFFLINE -> throw OfflineException("Nessuna connessione al NAS disponibile")
        }

    override suspend fun propFind(path: String, depth: String): List<WebDavResource> =
        activeClient.propFind(path, depth)

    override suspend fun get(path: String): InputStream =
        activeClient.get(path)

    override suspend fun getRange(path: String, start: Long, end: Long): InputStream =
        activeClient.getRange(path, start, end)

    override suspend fun put(path: String, inputStream: InputStream, contentType: String, contentLength: Long) =
        activeClient.put(path, inputStream, contentType, contentLength)

    override suspend fun delete(path: String) =
        activeClient.delete(path)

    override suspend fun mkcol(path: String) =
        activeClient.mkcol(path)

    override suspend fun copy(sourcePath: String, destinationPath: String) =
        activeClient.copy(sourcePath, destinationPath)

    override suspend fun move(sourcePath: String, destinationPath: String) =
        activeClient.move(sourcePath, destinationPath)
}

class OfflineException(message: String) : Exception(message)
