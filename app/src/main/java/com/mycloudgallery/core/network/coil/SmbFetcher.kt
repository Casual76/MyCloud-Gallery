package com.mycloudgallery.core.network.coil

import android.webkit.MimeTypeMap
import android.util.Log
import coil3.ImageLoader
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.mycloudgallery.core.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import okio.FileSystem
import okio.buffer
import okio.source
import coil3.decode.DataSource
import coil3.decode.ImageSource

class SmbFetcher(
    private val path: String,
    private val options: Options,
    private val webDavClient: WebDavClient,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("SmbFetcher", "Fetching from path: $path")
                val inputStream = webDavClient.get(path)
                
                // Use a larger buffer (128KB) for network streams to mitigate latency
                val bufferedStream = BufferedInputStream(inputStream, 128 * 1024)
                val source = bufferedStream.source().buffer()

                val extension = path.substringAfterLast('.', "").lowercase()
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

                SourceFetchResult(
                    source = coil3.decode.ImageSource(
                        source = source,
                        fileSystem = options.fileSystem
                    ),
                    mimeType = mimeType,
                    dataSource = coil3.decode.DataSource.NETWORK
                )
            } catch (e: Exception) {
                android.util.Log.e("SmbFetcher", "Error fetching from $path: ${e.message}", e)
                null
            }
        }
    }

    class Factory(private val webDavClient: WebDavClient) : Fetcher.Factory<Any> {
        override fun create(data: Any, options: Options, imageLoader: ImageLoader): Fetcher? {
            val stringData = data.toString()
            
            if (stringData.startsWith("smb://")) {
                val path = stringData.substringAfter("smb://")
                return SmbFetcher(path, options, webDavClient)
            }
            return null
        }
    }
}
