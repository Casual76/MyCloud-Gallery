package com.mycloudgallery.core.network

import com.mycloudgallery.core.security.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Implementazione WebDAV custom su OkHttp.
 * Scelta rispetto a Sardine per migliore compatibilità Android e controllo.
 */
@Singleton
class WebDavClientImpl @Inject constructor(
    @com.mycloudgallery.core.di.WebDavOkHttp private val httpClient: OkHttpClient,
    private val networkDetector: NetworkDetector,
    private val tokenManager: TokenManager,
) : WebDavClient {

    private fun buildUrl(path: String): String {
        val base = networkDetector.getWebDavBaseUrl()
        val cleanPath = path.removePrefix("/")
        
        // Usa HttpUrl.Builder per gestire correttamente l'encoding di spazi e caratteri speciali
        val baseUrl = base.toHttpUrlOrNull() ?: return "$base$cleanPath"
        return baseUrl.newBuilder()
            .addPathSegments(cleanPath)
            .build()
            .toString()
    }

    private fun authHeaders(builder: Request.Builder): Request.Builder {
        tokenManager.accessToken?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    override suspend fun propFind(path: String, depth: String): List<WebDavResource> =
        withContext(Dispatchers.IO) {
            val propFindXml = """
                <?xml version="1.0" encoding="utf-8"?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:displayname/>
                        <D:getcontenttype/>
                        <D:getcontentlength/>
                        <D:getlastmodified/>
                        <D:getetag/>
                        <D:resourcetype/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(buildUrl(path))
                .method("PROPFIND", propFindXml.toRequestBody("application/xml".toMediaType()))
                .header("Depth", depth)
                .let { authHeaders(it) }
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            parsePropFindResponse(body)
        }

    override suspend fun get(path: String): InputStream = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .get()
            .let { authHeaders(it) }
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw WebDavException("GET failed: ${response.code} ${response.message}")
        }
        response.body?.byteStream() ?: throw WebDavException("Empty response body")
    }

    override suspend fun getRange(path: String, start: Long, end: Long): InputStream =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(path))
                .get()
                .header("Range", "bytes=$start-$end")
                .let { authHeaders(it) }
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 206) {
                throw WebDavException("GET range failed: ${response.code}")
            }
            response.body?.byteStream() ?: throw WebDavException("Empty response body")
        }

    override suspend fun put(
        path: String,
        inputStream: InputStream,
        contentType: String,
        contentLength: Long,
    ) = withContext(Dispatchers.IO) {
        val bytes = inputStream.readBytes()
        val request = Request.Builder()
            .url(buildUrl(path))
            .put(bytes.toRequestBody(contentType.toMediaType()))
            .let { authHeaders(it) }
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw WebDavException("PUT failed: ${response.code} ${response.message}")
        }
        response.close()
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .delete()
            .let { authHeaders(it) }
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw WebDavException("DELETE failed: ${response.code} ${response.message}")
        }
        response.close()
    }

    override suspend fun mkcol(path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .method("MKCOL", null)
            .let { authHeaders(it) }
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful && response.code != 405) { // 405 = already exists
            throw WebDavException("MKCOL failed: ${response.code}")
        }
        response.close()
    }

    override suspend fun copy(sourcePath: String, destinationPath: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(sourcePath))
                .method("COPY", null)
                .header("Destination", buildUrl(destinationPath))
                .header("Overwrite", "F")
                .let { authHeaders(it) }
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw WebDavException("COPY failed: ${response.code}")
            }
            response.close()
        }

    override suspend fun move(sourcePath: String, destinationPath: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(sourcePath))
                .method("MOVE", null)
                .header("Destination", buildUrl(destinationPath))
                .header("Overwrite", "F")
                .let { authHeaders(it) }
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw WebDavException("MOVE failed: ${response.code}")
            }
            response.close()
        }

    // --- XML parsing del PROPFIND ---

    private fun parsePropFindResponse(xml: String): List<WebDavResource> {
        val resources = mutableListOf<WebDavResource>()
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            val responses = doc.getElementsByTagNameNS("DAV:", "response")

            for (i in 0 until responses.length) {
                val responseNode = responses.item(i)
                val href = getTextContent(responseNode, "DAV:", "href") ?: continue
                val displayName = getTextContent(responseNode, "DAV:", "displayname") ?: href.split("/").last()
                val contentType = getTextContent(responseNode, "DAV:", "getcontenttype")
                val contentLength = getTextContent(responseNode, "DAV:", "getcontentlength")?.toLongOrNull() ?: 0L
                val lastModifiedStr = getTextContent(responseNode, "DAV:", "getlastmodified")
                val etag = getTextContent(responseNode, "DAV:", "getetag")?.trim('"')

                val resourceTypeNodes = responseNode.let { node ->
                    val propStats = (node as org.w3c.dom.Element).getElementsByTagNameNS("DAV:", "resourcetype")
                    if (propStats.length > 0) {
                        val rt = propStats.item(0) as org.w3c.dom.Element
                        rt.getElementsByTagNameNS("DAV:", "collection").length > 0
                    } else false
                }

                resources.add(
                    WebDavResource(
                        path = href,
                        displayName = displayName,
                        isDirectory = resourceTypeNodes,
                        contentType = contentType,
                        contentLength = contentLength,
                        lastModified = parseHttpDate(lastModifiedStr),
                        etag = etag,
                    ),
                )
            }
        } catch (_: Exception) {
            // Parsing XML fallito — restituisci lista vuota
        }
        return resources
    }

    private fun getTextContent(parent: org.w3c.dom.Node, ns: String, localName: String): String? {
        val el = parent as? org.w3c.dom.Element ?: return null
        val nodes = el.getElementsByTagNameNS(ns, localName)
        return if (nodes.length > 0) nodes.item(0).textContent else null
    }

    private fun parseHttpDate(dateStr: String?): Long {
        if (dateStr == null) return 0L
        return try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            sdf.parse(dateStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}

class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause)
