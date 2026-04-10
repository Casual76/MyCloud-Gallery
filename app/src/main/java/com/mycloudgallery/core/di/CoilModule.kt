package com.mycloudgallery.core.di

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.mycloudgallery.core.security.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    /**
     * OkHttpClient dedicato a Coil: aggiunge l'header Authorization con il token WD.
     * Timeout più alti rispetto al client REST per supportare file più grandi.
     */
    @Provides
    @Singleton
    fun provideCoilOkHttpClient(tokenManager: TokenManager): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(WebDavAuthInterceptor(tokenManager))
            .build()

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        coilHttpClient: OkHttpClient,
    ): ImageLoader =
        ImageLoader.Builder(context)
            .crossfade(true)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { coilHttpClient }))
            }
            .build()
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
