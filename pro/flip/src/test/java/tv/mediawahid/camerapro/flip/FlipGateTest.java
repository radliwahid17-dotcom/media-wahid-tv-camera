package tv.mediawahid.camerapro.flip;

import org.junit.Test;
import static org.junit.Assert.*;

public class FlipGateTest {
    @Test public void normalRecordingAllowsFlip() { assertTrue(allowed("RECORDING")); }
    @Test public void pausedRecordingAllowsFlipWithoutResume() { assertTrue(allowed("PAUSED")); }
    @Test public void idleCameraAllowsOriginalSwitch() { assertTrue(allowed("IDLE")); }
    @Test public void startingAndStoppingRejectFlip() {
        assertFalse(allowed("STARTING")); assertFalse(allowed("STOPPING"));
    }
    @Test public void missingCameraOrUnreadyPipelineRejectsFlip() {
        assertFalse(FlipGate.allowed("RECORDING", true, false, false, false));
        assertFalse(FlipGate.allowed("RECORDING", false, true, false, false));
    }
    @Test public void logoImportAndPhotoCaptureRejectFlip() {
        assertFalse(FlipGate.allowed("IDLE", true, true, true, false));
        assertFalse(FlipGate.allowed("RECORDING", true, true, false, true));
    }
    @Test public void repeatedTapDoesNotCreateAnotherTransition() {
        FlipGate g = new FlipGate(); long t = g.begin();
        assertTrue(g.current(t)); assertEquals(-1, g.begin());
    }
    @Test public void staleCompletionCannotUnlockNewTransition() {
        FlipGate g = new FlipGate(); long first = g.begin(); g.finish(first);
        long second = g.begin(); g.finish(first);
        assertTrue(g.busy()); assertTrue(g.current(second));
    }
    @Test public void finalizeInvalidatesDelayedCameraCallbacks() {
        FlipGate g = new FlipGate(); long t = g.begin(); g.cancel();
        assertFalse(g.current(t)); assertFalse(g.busy());
    }
    @Test public void tenAlternatingSwitchesDoNotKeepGateLocked() {
        FlipGate g = new FlipGate();
        for (int i = 0; i < 10; i++) { long t = g.begin(); assertTrue(t > 0); g.finish(t); }
        assertFalse(g.busy());
    }
    private boolean allowed(String phase) { return FlipGate.allowed(phase, true, true, false, false); }
}
