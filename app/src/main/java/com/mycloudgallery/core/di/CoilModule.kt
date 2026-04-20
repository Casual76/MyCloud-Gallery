package com.mycloudgallery.core.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mycloudgallery.core.network.TokenAuthenticator
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.network.coil.SmbFetcher
import com.mycloudgallery.core.security.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Path.Companion.toPath
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CoilOkHttp

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    /**
     * OkHttpClient dedicato a Coil: aggiunge l'header Authorization con il token WD.
     * Timeout più alti rispetto al client REST per supportare file più grandi.
     * Accetta certificati self-signed per il NAS locale.
     */
    @Provides
    @Singleton
    @CoilOkHttp
    fun provideCoilOkHttpClient(
        tokenManager: TokenManager,
        tokenAuthenticator: TokenAuthenticator
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(WebDavAuthInterceptor(tokenManager))
            .authenticator(tokenAuthenticator)
        
        // Accetta certificati self-signed del NAS locale (essenziale per HTTPS in LAN)
        trustAllCertificates(builder)
        
        return builder.build()
    }

    private fun trustAllCertificates(builder: OkHttpClient.Builder) {
        try {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            android.util.Log.e("CoilModule", "Error setting trustAllCertificates", e)
        }
    }

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        @CoilOkHttp coilHttpClient: OkHttpClient,
        webDavClient: WebDavClient,
    ): ImageLoader {
        android.util.Log.d("CoilModule", "Providing high-performance ImageLoader for Coil 3")
        
        return ImageLoader.Builder(context)
            // Aggressive Memory Cache (35% of available heap)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.35)
                    .strongReferencesEnabled(true)
                    .build()
            }
            // Aggressive Disk Cache (2GB) for massive libraries
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(2048L * 1024 * 1024) // 2 GB
                    .cleanupDispatcher(Dispatchers.IO)
                    .build()
            }
            // Performance & Network Optimizations
            .crossfade(true)
            .components {
                // components
            }
            .build()
    }

}

/** Aggiunge Bearer token alle richieste Coil verso il NAS WebDAV. */
private class WebDavAuthInterceptor(
    private val tokenManager: TokenManager,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val token = tokenManager.accessToken
        val request = if (token != null) {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else original
        return chain.proceed(request)
    }
}
