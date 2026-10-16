package tv.mediawahid.camerapro.flip;

import org.junit.Test;
import static org.junit.Assert.*;

public class FlipVideoSpecTest {
    @Test public void fullHdAllowsBothSensorOrientations() {
        assertTrue(FlipVideoSpec.fullHd(1920, 1080));
        assertTrue(FlipVideoSpec.fullHd(1080, 1920));
    }
    @Test public void rejectsLowerResolutionAndCroppedFullHd() {
        assertFalse(FlipVideoSpec.fullHd(1280, 720));
        assertFalse(FlipVideoSpec.fullHd(1920, 1078));
        assertFalse(FlipVideoSpec.fullHd(0, 0));
    }
    @Test public void brightnessUsesExposureValueAcrossDifferentCameraSteps() {
        assertEquals(3, FlipVideoSpec.exposureIndex(1f, 1f / 3f, -6, 6));
        assertEquals(-2, FlipVideoSpec.exposureIndex(-1f, .5f, -6, 6));
    }
    @Test public void brightnessIsClampedToTargetCameraRange() {
        assertEquals(2, FlipVideoSpec.exposureIndex(3f, .5f, -2, 2));
        assertEquals(-2, FlipVideoSpec.exposureIndex(-3f, .5f, -2, 2));
    }
    @Test public void unsupportedExposureStepsUseNeutralValue() {
        assertEquals(0, FlipVideoSpec.exposureIndex(1f, 0f, -2, 2));
        assertEquals(0, FlipVideoSpec.exposureIndex(Float.NaN, .5f, -2, 2));
    }
}
