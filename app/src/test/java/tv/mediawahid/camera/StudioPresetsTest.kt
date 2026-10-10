package tv.mediawahid.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** No emulator needed: guards contract for all six available effects and templates. */
class StudioPresetsTest {
    @Test fun defaultIsFirstAndNeverRemoved() {
        assertEquals(StudioEffect.DEFAULT, StudioEffect.entries.first())
        assertEquals("Default", StudioEffect.entries.first().title)
        assertEquals(6, StudioEffect.entries.size)
    }

    @Test fun fiveExtraLooksAreUnique() {
        val looks = StudioEffect.entries.drop(1)
        assertEquals(5, looks.size)
        assertEquals(5, looks.map { it.title }.toSet().size)
        assertTrue(looks.all { it.description.isNotBlank() })
    }

    @Test fun existingWatermarkOptionsRemainExactlyTwo() {
        assertEquals(2, WatermarkTemplate.entries.size)
        assertEquals(WatermarkTemplate.DUAL, WatermarkTemplate.fromStorage("dual"))
        assertEquals(WatermarkTemplate.MEDIA_ONLY, WatermarkTemplate.fromStorage("media_only"))
        assertEquals(WatermarkTemplate.DUAL, WatermarkTemplate.fromStorage("unexpected"))
        assertFalse(WatermarkTemplate.DUAL.displayName.isBlank())
    }
}
