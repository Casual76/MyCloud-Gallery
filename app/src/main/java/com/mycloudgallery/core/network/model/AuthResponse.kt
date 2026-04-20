package com.mycloudgallery.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    // Campi per OS 5
    val error: String? = null,
    @SerialName("eula_status") val eulaStatus: String? = null,
)

@Serializable
data class AuthTokenRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
data class DeviceListResponse(
    val devices: List<WdDevice> = emptyList(),
)

@Serializable
data class WdDevice(
    val id: String = "",
    val name: String = "",
    @SerialName("network") val network: DeviceNetwork? = null,
)

@Serializable
data class DeviceNetwork(
    @SerialName("local_ip") val localIp: String? = null,
    @SerialName("external_ip") val externalIp: String? = null,
)
