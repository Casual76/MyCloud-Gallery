package com.mycloudgallery.core.di

import com.mycloudgallery.core.network.media3.SmbDataSourceFactory
import com.mycloudgallery.core.network.AuthInterceptor
import com.mycloudgallery.core.network.NetworkDetector
import com.mycloudgallery.core.network.SmbClientImpl
import com.mycloudgallery.core.network.WdRestApiService
import com.mycloudgallery.core.network.WebDavClient
import com.mycloudgallery.core.network.WebDavClientImpl
import com.mycloudgallery.core.security.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
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
annotation class WebDavOkHttp

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultOkHttp

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    @DefaultOkHttp
    fun provideOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val cookieJar = object : okhttp3.CookieJar {
            private val cookieStore = mutableMapOf<String, List<okhttp3.Cookie>>()
            override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .cookieJar(cookieJar)
            .addInterceptor(authInterceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (body != null && body.contentType()?.type == "application" && body.contentType()?.subtype == "json") {
                    val newBody = object : okhttp3.RequestBody() {
                        override fun contentType() = "application/json".toMediaType()
                        override fun contentLength() = body.contentLength()
                        override fun writeTo(sink: okio.BufferedSink) {
                            body.writeTo(sink)
                        }
                    }
                    val newRequest = request.newBuilder()
                        .method(request.method, newBody)
                        .build()
                    return@addInterceptor chain.proceed(newRequest)
                }
                chain.proceed(request)
            }

        // Logging solo in debug
        if (com.mycloudgallery.BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                },
            )
        }

        // Accetta certificati self-signed del NAS locale
        trustAllCertificates(builder)

        return builder.build()
    }

    @Provides
    @Singleton
    @WebDavOkHttp
    fun provideWebDavOkHttpClient(authInterceptor: AuthInterceptor): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        trustAllCertificates(builder)

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(
        @DefaultOkHttp okHttpClient: OkHttpClient,
        json: Json,
    ): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            // Runtime requests provide the full NAS URL via @Url.
            .baseUrl("https://localhost/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideWdRestApiService(retrofit: Retrofit): WdRestApiService =
        retrofit.create(WdRestApiService::class.java)

    @Provides
    @Singleton
    fun provideWebDavClient(
        tripleModeClient: com.mycloudgallery.core.network.TripleModeClient
    ): WebDavClient = tripleModeClient

    @Provides
    @Singleton
    fun provideSmbDataSourceFactory(
        webDavClient: WebDavClient
    ): SmbDataSourceFactory = SmbDataSourceFactory(webDavClient)

    /**
     * Il NAS WD MyCloud usa spesso certificati self-signed sulla rete locale.
     * Per un'app privata familiare, accettiamo tutti i certificati.
     */
    private fun trustAllCertificates(builder: OkHttpClient.Builder) {
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
    }
}
