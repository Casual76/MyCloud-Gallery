package com.mycloudgallery.core.network

import android.webkit.MimeTypeMap
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.mycloudgallery.core.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmbClientImpl @Inject constructor(
    private val networkDetector: NetworkDetector,
    private val tokenManager: TokenManager,
) : WebDavClient {

    private val client = SMBClient()

    private fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /**
     * Rappresenta una sessione SMB aperta che può essere riutilizzata.
     */
    inner class PersistentSmbSession(
        private val connection: Connection,
        private val session: Session
    ) : AutoCloseable {
        
        fun list(path: String): List<WebDavResource> {
            val cleanPath = path.trim('/').replace("/", "\\")
            val parts = cleanPath.split("\\", limit = 2)
            val shareName = parts.getOrNull(0) ?: return emptyList()
            val relativePath = parts.getOrNull(1) ?: ""

            val share = session.connectShare(shareName) as DiskShare
            return try {
                val list = try {
                    share.list(relativePath)
                } catch (e: Exception) {
                    if (relativePath.isEmpty()) share.list(".") else throw e
                }

                list.filter { info ->
                    info.fileName != "." && info.fileName != ".." && !info.fileName.startsWith(".")
                }.map { info ->
                    val cleanBasePath = path.trimEnd('/')
                    val fullPath = "$cleanBasePath/${info.fileName}"

                    WebDavResource(
                        path = if (fullPath.startsWith("/")) fullPath else "/$fullPath",
                        displayName = info.fileName,
                        isDirectory = (info.fileAttributes and 0x10L) != 0L,
                        contentType = getMimeType(info.fileName),
                        contentLength = info.endOfFile,
                        lastModified = info.changeTime.toEpochMillis(),
                        etag = null
                    )
                }
            } finally {
                share.close()
            }
        }

        override fun close() {
            session.close()
            connection.close()
        }
    }

    suspend fun <T> withPersistentSession(block: suspend (PersistentSmbSession) -> T): T = withContext(Dispatchers.IO) {
        val host = networkDetector.nasLocalIp ?: throw SmbException("NAS IP not found")
        val username = tokenManager.username ?: ""
        val password = tokenManager.password ?: ""

        val connection = client.connect(host)
        try {
            val auth = AuthenticationContext(username, password.toCharArray(), null)
            val session = connection.authenticate(auth)
            val persistentSession = PersistentSmbSession(connection, session)
            try {
                block(persistentSession)
            } finally {
                persistentSession.close()
            }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    private suspend fun <T> useShare(path: String, block: (DiskShare, String) -> T): T = withContext(Dispatchers.IO) {
        val host = networkDetector.nasLocalIp ?: throw SmbException("NAS IP not found")
        val username = tokenManager.username ?: ""
        val password = tokenManager.password ?: ""

        val connection: Connection = client.connect(host)
        try {
            val auth = AuthenticationContext(username, password.toCharArray(), null)
            val session: Session = connection.authenticate(auth)
            
            val cleanPath = path.trim('/').replace("/", "\\")
            val parts = cleanPath.split("\\", limit = 2)
            val shareName = parts.getOrNull(0)
            val relativePath = parts.getOrNull(1) ?: ""

            if (shareName.isNullOrBlank()) {
                throw SmbException("Invalid share name in path: $path")
            }

            val share = session.connectShare(shareName) as DiskShare
            try {
                block(share, relativePath)
            } finally {
                share.close()
            }
        } finally {
            connection.close()
        }
    }

    override suspend fun propFind(path: String, depth: String): List<WebDavResource> = try {
        useShare(path) { share, relPath ->
            val list = try {
                share.list(relPath)
            } catch (e: Exception) {
                if (relPath.isEmpty()) share.list(".") else throw e
            }
            
            list.filter { info -> 
                info.fileName != "." && info.fileName != ".." && !info.fileName.startsWith(".")
            }.map { info ->
                val cleanBasePath = path.trimEnd('/')
                val fullPath = "$cleanBasePath/${info.fileName}"
                
                WebDavResource(
                    path = if (fullPath.startsWith("/")) fullPath else "/$fullPath",
                    displayName = info.fileName,
                    isDirectory = (info.fileAttributes and 0x10L) != 0L,
                    contentType = getMimeType(info.fileName),
                    contentLength = info.endOfFile,
                    lastModified = info.changeTime.toEpochMillis(),
                    etag = null
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    override suspend fun get(path: String): InputStream = withContext(Dispatchers.IO) {
        val host = networkDetector.nasLocalIp ?: throw SmbException("NAS IP not found")
        val username = tokenManager.username ?: ""
        val password = tokenManager.password ?: ""

        val connection = client.connect(host)
        try {
            val auth = AuthenticationContext(username, password.toCharArray(), null)
            val session = connection.authenticate(auth)
            
            val cleanPath = path.trim('/').replace("/", "\\")
            val parts = cleanPath.split("\\", limit = 2)
            val shareName = parts.getOrNull(0) ?: throw SmbException("Invalid path: $path")
            val relativePath = parts.getOrNull(1) ?: ""

            val share = session.connectShare(shareName) as DiskShare
            val file = share.openFile(
                relativePath,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            
            object : InputStream() {
                private val wrapped = file.inputStream
                private var closed = false

                override fun read(): Int = wrapped.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)
                
                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        wrapped.close()
                        file.close()
                        share.close()
                        session.close()
                        connection.close()
                    } catch (_: Exception) {}
                }
                override fun available(): Int = wrapped.available()
            }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override suspend fun getRange(path: String, start: Long, end: Long): InputStream = withContext(Dispatchers.IO) {
        // B2: Do NOT use useShare() here — it closes the connection in its finally block, which
        // would invalidate the InputStream before the caller has read it. Open resources manually
        // and close them inside the returned InputStream.close(), matching the pattern in get().
        val host = networkDetector.nasLocalIp ?: throw SmbException("NAS IP not found")
        val username = tokenManager.username ?: ""
        val password = tokenManager.password ?: ""

        val connection = client.connect(host)
        try {
            val auth = AuthenticationContext(username, password.toCharArray(), null)
            val session = connection.authenticate(auth)

            val cleanPath = path.trim('/').replace("/", "\\")
            val parts = cleanPath.split("\\", limit = 2)
            val shareName = parts.getOrNull(0) ?: throw SmbException("Invalid path: $path")
            val relativePath = parts.getOrNull(1) ?: ""

            val share = session.connectShare(shareName) as DiskShare
            val file = share.openFile(
                relativePath,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )

            // Return a direct stream — no readBytes() buffering (was OOM for large files).
            // skip() to the requested byte offset without loading bytes into memory.
            object : InputStream() {
                private val wrapped = file.inputStream
                private var closed = false

                init {
                    if (start > 0) wrapped.skip(start)
                }

                override fun read(): Int = wrapped.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = wrapped.read(b, off, len)

                override fun close() {
                    if (closed) return
                    closed = true
                    try {
                        wrapped.close()
                        file.close()
                        share.close()
                        session.close()
                        connection.close()
                    } catch (_: Exception) {}
                }

                override fun available(): Int = wrapped.available()
            }
        } catch (e: Exception) {
            connection.close()
            throw e
        }
    }

    override suspend fun put(path: String, inputStream: InputStream, contentType: String, contentLength: Long) {
        useShare(path) { share, relPath ->
            val file = share.openFile(
                relPath,
                EnumSet.of(com.hierynomus.msdtyp.AccessMask.GENERIC_WRITE),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            file.use { f ->
                inputStream.use { i ->
                    i.copyTo(f.outputStream)
                }
            }
        }
    }

    override suspend fun delete(path: String) {
        useShare(path) { share, relPath ->
            share.rm(relPath)
        }
    }

    override suspend fun mkcol(path: String) {
        useShare(path) { share, relPath ->
            share.mkdir(relPath)
        }
    }

    override suspend fun copy(sourcePath: String, destinationPath: String) {
        throw UnsupportedOperationException("SMB copy not implemented - use put/get")
    }

    override suspend fun move(sourcePath: String, destinationPath: String) {
        withContext(Dispatchers.IO) {
            val host = networkDetector.nasLocalIp ?: throw SmbException("NAS IP not found")
            val username = tokenManager.username ?: ""
            val password = tokenManager.password ?: ""

            // Both paths must be on the same share for an SMB rename to work.
            val cleanSrc = sourcePath.trim('/').replace("/", "\\")
            val srcParts = cleanSrc.split("\\", limit = 2)
            val shareName = srcParts.getOrNull(0) ?: throw SmbException("Invalid source path: $sourcePath")
            val srcRelPath = srcParts.getOrNull(1) ?: throw SmbException("Missing relative path in: $sourcePath")

            val cleanDst = destinationPath.trim('/').replace("/", "\\")
            val dstParts = cleanDst.split("\\", limit = 2)
            val dstRelPath = dstParts.getOrNull(1) ?: throw SmbException("Missing relative path in: $destinationPath")

            val connection = client.connect(host)
            try {
                val auth = AuthenticationContext(username, password.toCharArray(), null)
                val session = connection.authenticate(auth)
                val share = session.connectShare(shareName) as DiskShare
                share.use {
                    val file = share.openFile(
                        srcRelPath,
                        // GENERIC_READ + DELETE are required for a rename operation.
                        EnumSet.of(
                            com.hierynomus.msdtyp.AccessMask.GENERIC_READ,
                            com.hierynomus.msdtyp.AccessMask.DELETE
                        ),
                        null,
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_DELETE),
                        SMB2CreateDisposition.FILE_OPEN,
                        null
                    )
                    file.use { f ->
                        f.rename(dstRelPath)
                    }
                }
                session.close()
            } finally {
                connection.close()
            }
        }
    }
}

class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)
