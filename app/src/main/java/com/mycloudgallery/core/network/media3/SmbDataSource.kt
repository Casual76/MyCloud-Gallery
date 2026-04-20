package com.mycloudgallery.core.network.media3

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.mycloudgallery.core.network.WebDavClient
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.BufferedInputStream

class SmbDataSource(
    private val webDavClient: WebDavClient,
) : BaseDataSource(true) {
    private var inputStream: InputStream? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.path ?: throw SmbDataSourceException(message = "Invalid path")

        try {
            // B1: runBlocking with Dispatchers.IO prevents deadlock — Media3 calls open() on a
            // non-coroutine background thread, so runBlocking is required here.
            // B3: when length is unset AND position is 0, use get() to avoid sending an unbounded
            // range to the SMB server. When position > 0 but length is unset, skip() via get().
            val rawStream = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val hasPosition = dataSpec.position != 0L
                val hasLength = dataSpec.length != C.LENGTH_UNSET.toLong()

                when {
                    hasLength -> {
                        // Known range: use getRange with exact byte boundaries.
                        val endByte = dataSpec.position + dataSpec.length - 1
                        webDavClient.getRange(path, dataSpec.position, endByte)
                    }
                    hasPosition -> {
                        // Seek without a known end: get the full stream and skip to position.
                        // Avoids sending Long.MAX_VALUE as range end (B3).
                        webDavClient.get(path).also { stream ->
                            stream.skip(dataSpec.position)
                        }
                    }
                    else -> {
                        // No position, no length: plain sequential read.
                        webDavClient.get(path)
                    }
                }
            }
            
            // Wrap in a large buffer (1MB) to minimize small network reads over high-latency NAS.
            inputStream = BufferedInputStream(rawStream, 1024 * 1024)

            opened = true
            transferStarted(dataSpec)
            return dataSpec.length
        } catch (e: Exception) {
            throw SmbDataSourceException(e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val bytesRead = try {
            stream.read(buffer, offset, length)
        } catch (e: Exception) {
            throw SmbDataSourceException(e)
        }

        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = null

    override fun close() {
        try {
            inputStream?.close()
        } finally {
            inputStream = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}

class SmbDataSourceException(cause: Throwable? = null, message: String? = null) : Exception(message, cause) {
    constructor(cause: Throwable) : this(cause, cause.message)
}
