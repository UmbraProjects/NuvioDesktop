package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        defaultRequestHeaders: Map<String, String>,
        defaultResponseHeaders: Map<String, String>,
        useYoutubeChunkedPlayback: Boolean,
    ): DataSource.Factory {
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(defaultRequestHeaders)
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}