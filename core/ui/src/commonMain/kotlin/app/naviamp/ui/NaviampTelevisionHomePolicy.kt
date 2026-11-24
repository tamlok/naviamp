package app.naviamp.ui


private const val TelevisionHomeMaximumItemsPerRail = 30

/**
 * Adapts every available shared Home section to the ten-foot carousel presentation.
 *
 * The input is already ordered by the user's shared Home settings. Standard Home layout choices
 * are intentionally ignored because TV consistently presents sections as horizontal rails.
 */
internal fun televisionHomeSections(
    sections: List<SharedHomeCollectionSectionUi>,
): List<SharedHomeCollectionSectionUi> {
    return sections.filter {
        it.visible && (it.items.isNotEmpty() || it.favoriteAlbumsFailed)
    }.map { section ->
        section.copy(
            items = section.items.take(
                minOf(
                    section.homeItemLimit ?: TelevisionHomeMaximumItemsPerRail,
                    TelevisionHomeMaximumItemsPerRail,
                ),
            ),
        )
    }
}


internal fun televisionHomeMoveScrollAnchor(
    movingIndex: Int,
    visibleIndices: List<Int>,
): Int? {
    if (movingIndex < 0 || visibleIndices.isEmpty()) return null
    val visibleRange = visibleIndices.first()..visibleIndices.last()
    return when {
        movingIndex in visibleRange -> null
        movingIndex < visibleRange.first -> movingIndex
        else -> (movingIndex - visibleIndices.size + 1).coerceAtLeast(0)
    }
}
