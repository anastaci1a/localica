package io.github.anastaci1a.localica.ui

import android.content.Context

internal data class ActionSlot(val row: Int, val column: Int) {
    val key get() = "$row:$column"
    val label get() = "Row ${row + 1}, slot ${column + 1}"
}

internal data class PlayerActionSettings(
    val slots: Map<ActionSlot, PlayerAction> = mapOf(ActionSlot(0, 0) to PlayerAction.Info,
        ActionSlot(0, 1) to PlayerAction.Playlist, ActionSlot(0, 7) to PlayerAction.Repeat, ActionSlot(0, 8) to PlayerAction.Shuffle),
    val centered: Boolean = true, val twoRows: Boolean = false, val small: Boolean = false,
) {
    val rows get() = if (twoRows) 2 else 1
    val columns: List<Int> get() = if (centered) {
        if (small) listOf(0, 1, 2, 4, 6, 7, 8) else listOf(0, 1, 4, 7, 8)
    } else (0..maxOf(8, (slots.keys.maxOfOrNull { it.column } ?: 0) + 1).coerceAtMost(31)).toList()
    val visibleSlots get() = (0 until rows).flatMap { row -> columns.map { ActionSlot(row, it) } }
    /** The editor retains empty destinations; playback ends at its last visible assignment. */
    val playbackColumns get() = if (centered) columns else
        (0..(slots.keys.filter { it.row < rows }.maxOfOrNull { it.column } ?: 0)).toList()
    val hiddenCount get() = slots.keys.count { it !in visibleSlots }

    /** Moving an assigned action swaps occupants; a new palette action replaces the target. */
    fun place(action: PlayerAction, target: ActionSlot): PlayerActionSettings {
        require(target.row in 0..1 && target.column in 0..31)
        val source = slots.entries.firstOrNull { it.value == action }?.key
        if (source == target) return this
        val displaced = slots[target]
        val next = slots.toMutableMap()
        if (source != null) {
            next.remove(source)
            if (displaced != null) next[source] = displaced
        }
        next[target] = action
        return copy(slots = next)
    }
    fun remove(action: PlayerAction) = copy(slots = slots.filterValues { it != action })
}

internal class PlayerActionPreferences(context: Context) {
    private val store = context.getSharedPreferences("player-actions", Context.MODE_PRIVATE)
    fun read(): PlayerActionSettings {
        val defaults = PlayerActionSettings()
        val centered = store.getBoolean("centered", defaults.centered)
        val twoRows = store.getBoolean("two-rows", false)
        val small = store.getBoolean("small", false)
        val encoded = store.getString("slots", null)
        val slots = if (encoded != null) {
            val seen = mutableSetOf<PlayerAction>()
            buildMap {
                encoded.split(';').forEach { value ->
                    val parts = value.split(':')
                    val row = parts.getOrNull(0)?.toIntOrNull()
                    val column = parts.getOrNull(1)?.toIntOrNull()
                    val action = PlayerAction.entries.firstOrNull { it.name == parts.getOrNull(2) }
                    if (row != null && row in 0..1 && column != null && column in 0..31 && action != null && seen.add(action))
                        put(ActionSlot(row, column), action)
                }
            }
        } else store.getString("actions", null)?.split(',')?.mapNotNull { name -> PlayerAction.entries.firstOrNull { it.name == name } }
            ?.distinct()?.let { actions ->
                // Preserve every legacy assignment, including layouts larger than a centered row.
                val columns = if (centered) listOf(0, 1, 4, 7, 8, 2, 6, 3, 5) else (0..31).toList()
                actions.mapIndexed { index, action -> ActionSlot(if (twoRows) index % 2 else 0,
                    columns[if (twoRows) index / 2 else index % columns.size] + if (centered && index >= columns.size && !twoRows) 9 else 0) to action }.toMap()
            } ?: defaults.slots
        return PlayerActionSettings(slots, centered, twoRows, small)
    }
    fun write(value: PlayerActionSettings) {
        store.edit().putString("slots", value.slots.entries.sortedWith(compareBy({ it.key.row }, { it.key.column }))
            .joinToString(";") { "${it.key.key}:${it.value.name}" })
            .putBoolean("centered", value.centered).putBoolean("two-rows", value.twoRows).putBoolean("small", value.small).apply()
    }
}
