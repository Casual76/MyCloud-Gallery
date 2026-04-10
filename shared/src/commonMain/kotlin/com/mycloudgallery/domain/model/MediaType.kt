package com.mycloudgallery.domain.model

enum class MediaType {
    IMAGE,
    VIDEO,
    RAW;

    companion object {
        fun fromMimeType(mimeType: String): MediaType = when {
            mimeType.startsWith("video/") -> VIDEO
            mimeType.contains("raw") || mimeType.contains("dng") ||
                mimeType.contains("cr2") || mimeType.contains("nef") ||
                mimeType.contains("arw") -> RAW
            else -> IMAGE
        }
    }
}
