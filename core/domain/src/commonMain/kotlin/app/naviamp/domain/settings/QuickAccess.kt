package app.naviamp.domain.settings

import kotlinx.serialization.Serializable

@Serializable
enum class QuickAccessKind { Playlist, Album, Artist }

/** A navigation shortcut scoped to the account and library that own its provider item ID. */
@Serializable
data class QuickAccessPin(
    val sourceId: String,
    val kind: QuickAccessKind,
    val itemId: String,
    val title: String,
) {
    fun normalized(): QuickAccessPin? {
        val source = sourceId.trim().takeIf(String::isNotEmpty) ?: return null
        val item = itemId.trim().takeIf(String::isNotEmpty) ?: return null
        val label = title.trim().takeIf(String::isNotEmpty) ?: return null
        return if (source == sourceId && item == itemId && label == title) this else
            copy(sourceId = source, itemId = item, title = label)
    }

    fun sameTarget(other: QuickAccessPin): Boolean =
        sourceId == other.sourceId && kind == other.kind && itemId == other.itemId
}

fun InterfaceSettings.withQuickAccessPin(pin: QuickAccessPin): InterfaceSettings {
    val target = pin.normalized() ?: return this
    val settings = normalized()
    val index = settings.quickAccess.indexOfFirst { it.sameTarget(target) }
    if (index >= 0 && settings.quickAccess[index] == target) return settings
    val pins = settings.quickAccess.toMutableList()
    if (index >= 0) pins[index] = target else pins.add(target)
    return settings.copy(quickAccess = pins)
}

fun InterfaceSettings.withoutQuickAccessPin(pin: QuickAccessPin): InterfaceSettings {
    val source = pin.sourceId.trim()
    val item = pin.itemId.trim()
    return copy(
        quickAccess = quickAccess.filterNot {
            it.sourceId.trim() == source && it.kind == pin.kind && it.itemId.trim() == item
        },
    ).normalized()
}
