package terra.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import terra.world.TerrainGenerationSettings

class TerrainSettingsDraftTest {
    @Test
    fun sliderEditsCommitAsOneChangeAndCannotRebuildTwice() {
        val draft = TerrainSettingsDraft(TerrainGenerationSettings())
        assertNull(draft.commit())
        draft.current = draft.current.copy(seaLevel = 0.4)
        draft.current = draft.current.copy(erosionStrength = 0.7)
        assertTrue(draft.dirty)
        val applied = draft.commit()!!
        assertEquals(0.4, applied.seaLevel)
        assertEquals(0.7, applied.erosionStrength)
        assertFalse(draft.dirty)
        assertNull(draft.commit())
    }

    @Test
    fun discardRestoresTheLastAppliedSettingsAcrossTabs() {
        val draft = TerrainSettingsDraft(TerrainGenerationSettings())
        draft.current = draft.current.copy(equatorTemperatureC = 20.0)
        val applied = draft.commit()
        draft.current = TerrainGenerationSettings()
        draft.current = draft.current.copy(baseElevation = 0.5)
        draft.discard()
        assertEquals(applied, draft.current)
        assertFalse(draft.dirty)
    }
}
