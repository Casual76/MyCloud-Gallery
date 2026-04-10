package com.mycloudgallery.core.network

import com.mycloudgallery.core.network.model.AuthTokenRequest
import com.mycloudgallery.core.network.model.AuthTokenResponse
import com.mycloudgallery.core.network.model.DeviceListResponse
import com.mycloudgallery.core.network.model.RefreshTokenRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * WD REST API v2.1 — servizio Retrofit per autenticazione e discovery dispositivi.
 */
interface WdRestApiService {

    @POST("auth/token")
    suspend fun login(@Body request: AuthTokenRequest): Response<AuthTokenResponse>

    @POST("auth/token")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): Response<AuthTokenResponse>

    @GET("device/list")
    suspend fun getDeviceList(): Response<DeviceListResponse>
}
