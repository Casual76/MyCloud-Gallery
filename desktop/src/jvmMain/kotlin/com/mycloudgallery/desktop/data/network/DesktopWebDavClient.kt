package com.mycloudgallery.desktop.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Client WebDAV per JVM — usa OkHttp (stessa libreria dell'app Android).
 * Implementa le operazioni fondamentali per la versione Desktop.
 */
class DesktopWebDavClient(
    private val baseUrl: String,
    username: String,
    password: String,
) {
    private val credentials = Credentials.basic(username, password)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", credentials)
                    .build()
            )
        }
        .build()

    /** Esegue PROPFIND per elencare le risorse in [path]. */
    suspend fun propFind(path: String, depth: String = "1"): String = withContext(Dispatchers.IO) {
        val body = """<?xml version="1.0"?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:getcontentlength/>
                    <D:getcontenttype/>
                    <D:getlastmodified/>
                    <D:resourcetype/>
                </D:prop>
            </D:propfind>""".trimIndent()

        val request = Request.Builder()
            .url("$baseUrl$path")
            .method("PROPFIND", body.toRequestBody("application/xml".toMediaType()))
            .header("Depth", depth)
            .build()

        httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    /** Scarica il contenuto di [path] come InputStream. */
    suspend fun get(path: String): InputStream = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        response.body?.byteStream() ?: InputStream.nullInputStream()
    }

    /** Carica [bytes] al path WebDAV [path]. */
    suspend fun put(path: String, bytes: ByteArray, contentType: String = "application/octet-stream") {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .put(bytes.toRequestBody(contentType.toMediaType()))
                .build()
            httpClient.newCall(request).execute().close()
        }
    }

    /** Elimina la risorsa al [path]. */
    suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl$path")
                .delete()
                .build()
            httpClient.newCall(request).execute().close()
        }
    }
}
