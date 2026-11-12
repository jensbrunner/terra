package terra.ui

import terra.world.TerrainGenerationSettings

/** Editing has no world side effects; commit returns a change at most once. */
internal class TerrainSettingsDraft(initial: TerrainGenerationSettings) {
    var current = initial
    private var applied = initial
    val dirty get() = current != applied
    fun discard() { current = applied }
    fun commit(): TerrainGenerationSettings? {
        if (!dirty) return null
        applied = current
        return applied
    }
}
