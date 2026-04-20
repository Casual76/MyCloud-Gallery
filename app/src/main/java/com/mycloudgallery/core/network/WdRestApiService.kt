package com.mycloudgallery.core.network

import com.mycloudgallery.core.network.model.AuthTokenRequest
import com.mycloudgallery.core.network.model.AuthTokenResponse
import com.mycloudgallery.core.network.model.DeviceListResponse
import com.mycloudgallery.core.network.model.RefreshTokenRequest
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Field

/**
 * WD REST API v2.1 — servizio Retrofit per autenticazione e discovery dispositivi.
 */
interface WdRestApiService {

    @POST
    suspend fun login(
        @Url url: String,
        @Body request: AuthTokenRequest,
    ): Response<AuthTokenResponse>

    @POST
    suspend fun loginOs5(
        @Url url: String,
        @Body request: AuthTokenRequest,
    ): Response<AuthTokenResponse>

    @POST
    suspend fun refreshToken(
        @Url url: String,
        @Body request: RefreshTokenRequest,
    ): Response<AuthTokenResponse>

    @GET
    suspend fun getDeviceList(@Url url: String): Response<DeviceListResponse>
}
