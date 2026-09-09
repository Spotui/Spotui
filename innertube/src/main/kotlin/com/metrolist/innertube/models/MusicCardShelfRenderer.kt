package com.metrolist.innertube.models

import kotlinx.serialization.Serializable

@Serializable
data class MusicCardShelfRenderer(
    val title: Runs? = null,
    val subtitle: Runs? = null,
    val thumbnail: ThumbnailRenderer? = null,
    val header: Header? = null,
    val contents: List<Content>? = null,
    val buttons: List<Button>? = null,
    val onTap: NavigationEndpoint? = null,
    val subtitleBadges: List<Badges>? = null,
) {
    @Serializable
    data class Header(
        val musicCardShelfHeaderBasicRenderer: MusicCardShelfHeaderBasicRenderer,
    ) {
        @Serializable
        data class MusicCardShelfHeaderBasicRenderer(
            val title: Runs,
        )
    }

    @Serializable
    data class Content(
        val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer?,
    )
}
