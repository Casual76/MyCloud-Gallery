package com.mycloudgallery.core.network

private const val HTTPS_SCHEME = "https://"
private const val HTTP_SCHEME = "http://"
private const val API_PATH = "api/2.1"
private const val WEBDAV_PATH = "webdav"

fun normalizeServerAddress(value: String): String = value
    .trim()
    .removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')
    .substringBefore("/$API_PATH")
    .substringBefore("/$WEBDAV_PATH")
    .substringBefore('/')
    .trim()

fun buildApiBaseUrl(serverAddress: String): String =
    "${extractSchemeOrDefault(serverAddress)}${normalizeServerAddress(serverAddress)}/$API_PATH/"

fun buildApiUrl(serverAddress: String, endpoint: String): String =
    buildApiBaseUrl(serverAddress) + endpoint.trimStart('/')

fun buildWebDavBaseUrl(serverAddress: String): String =
    "${extractSchemeOrDefault(serverAddress)}${normalizeServerAddress(serverAddress)}/$WEBDAV_PATH/"

private fun extractSchemeOrDefault(serverAddress: String): String =
    when {
        serverAddress.trim().startsWith(HTTP_SCHEME, ignoreCase = true) -> HTTP_SCHEME
        serverAddress.trim().startsWith(HTTPS_SCHEME, ignoreCase = true) -> HTTPS_SCHEME
        else -> HTTPS_SCHEME
    }
