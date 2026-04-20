package com.mycloudgallery.core.network.media3

import androidx.media3.datasource.DataSource
import com.mycloudgallery.core.network.WebDavClient

class SmbDataSourceFactory(
    private val webDavClient: WebDavClient
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SmbDataSource(webDavClient)
    }
}
