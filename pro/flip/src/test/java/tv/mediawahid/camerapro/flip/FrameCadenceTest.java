package tv.mediawahid.camerapro.flip;
import org.junit.Test;
import static org.junit.Assert.*;
public class FrameCadenceTest {
    @Test public void oneSlotOnlyAndMonotonicDuringCameraAbsence() {
        FrameCadence clock=new FrameCadence(30);clock.anchor(10_000_000_000L,9_950_000_000L);
        long last=-1;
        for(int i=0;i<5400;i++) {
            long now=10_000_000_000L+i*33_333_333L;
            long pts=clock.presentation(now);assertTrue(pts>last);last=pts;
            assertEquals(FrameCadence.NOT_DUE,clock.presentation(now));
        }
        assertEquals(9_950_000_000L+5399*33_333_333L,last);
    }
    @Test public void delayedWorkerSkipsSlotsWithoutBurstOrTimestampReversal() {
        FrameCadence clock=new FrameCadence(30);clock.anchor(1_000_000_000L,900_000_000L);
        assertEquals(900_000_000L,clock.presentation(1_000_000_000L));
        assertEquals(900_000_000L+30*33_333_333L,clock.presentation(2_000_000_000L));
        assertEquals(FrameCadence.NOT_DUE,clock.presentation(2_000_000_001L));
    }
    @Test public void manualPauseKeepsWallClockEpochForRecorderToRemovePause() {
        FrameCadence clock=new FrameCadence(30);clock.anchor(1_000_000_000L,1_000_000_000L);
        long before=clock.presentation(1_000_000_000L),after=clock.presentation(11_000_000_000L);
        assertTrue(after-before>=9_999_999_000L);
        clock.reset();assertEquals(FrameCadence.NOT_DUE,clock.presentation(12_000_000_000L));
    }
    @Test public void nextTickWaitIsPositiveAndBounded() {
        FrameCadence clock=new FrameCadence(30);clock.anchor(1_000_000_000L,1_000_000_000L);
        clock.presentation(1_000_000_000L);
        assertEquals(34,clock.delayMillis(1_000_000_000L));
        assertEquals(1,clock.delayMillis(1_040_000_000L));
    }
}
