package com.metrolist.innertube.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SectionListRenderer(
    val header: Header?,
    val contents: List<Content>?,
    val continuations: List<Continuation>?,
) {
    @Serializable
    data class Header(
        val chipCloudRenderer: ChipCloudRenderer?,
    ) {
        @Serializable
        data class ChipCloudRenderer(
            val chips: List<Chip>,
        ) {
            @Serializable
            data class Chip(
                val chipCloudChipRenderer: ChipCloudChipRenderer,
            ) {
                @Serializable
                data class ChipCloudChipRenderer(
                    val isSelected: Boolean = false,
                    val navigationEndpoint: NavigationEndpoint? = null,
                    val onDeselectedCommand: NavigationEndpoint? = null,
                    // The close button doesn't have the following two fields
                    val text: Runs?,
                    val uniqueId: String?,
                )
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Content(
        @JsonNames("musicImmersiveCarouselShelfRenderer")
        val musicCarouselShelfRenderer: MusicCarouselShelfRenderer? = null,
        val musicShelfRenderer: MusicShelfRenderer? = null,
        val musicCardShelfRenderer: MusicCardShelfRenderer? = null,
        val musicPlaylistShelfRenderer: MusicPlaylistShelfRenderer? = null,
        val gridRenderer: GridRenderer? = null,
        val itemSectionRenderer: ItemSectionRenderer? = null,
    )

    @Serializable
    data class ItemSectionRenderer(
        val contents: List<ItemSectionContent>? = null,
    ) {
        @Serializable
        data class ItemSectionContent(
            val musicResponsiveListItemRenderer: MusicResponsiveListItemRenderer? = null,
            val musicShelfRenderer: MusicShelfRenderer? = null,
        )
    }
}
