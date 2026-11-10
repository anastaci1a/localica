package io.github.anastaci1a.localica.editing

/** Original copies, two per-file working copies, bounded tag growth, artwork and free headroom. */
internal object TagSaveSpace {
    private const val Margin = 64L * 1024 * 1024
    private const val Growth = 32L * 1024 * 1024
    fun required(total: Long, largest: Long, artwork: Long, undo: Boolean): Long {
        require(total >= largest && largest >= 0 && artwork >= 0)
        return Math.addExact(if (undo) total else 0, Math.addExact(Math.multiplyExact(largest, 2),
            Math.addExact(Math.multiplyExact(artwork, 3), Margin + Growth)))
    }
}
