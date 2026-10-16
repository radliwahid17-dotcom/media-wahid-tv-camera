package tv.mediawahid.camerapro.flip;
import org.junit.Test;
import static org.junit.Assert.*;
public class MuxerTimestampTest {
    @Test public void ordinaryCameraTimestampsAreUnchanged(){
        MuxerTimestamp clock=new MuxerTimestamp();
        for(long value=1_000_000;value<181_000_000;value+=33_333)assertEquals(value,clock.normalize(value));
    }
    @Test public void nativeRepeatCollisionStaysDistinctInMp4TimescaleWithoutDrift(){
        MuxerTimestamp clock=new MuxerTimestamp();
        long first=clock.normalize(33_333),second=clock.normalize(33_334);
        assertTrue(first*90_000/1_000_000<second*90_000/1_000_000);
        assertEquals(34_333,second);assertEquals(66_666,clock.normalize(66_666));
    }
    @Test(expected=IllegalArgumentException.class) public void actualClockDiscontinuityIsRejected(){
        MuxerTimestamp clock=new MuxerTimestamp();clock.normalize(100_000);clock.normalize(10_000);
    }
    @Test public void newSessionCanStartAtAnEarlierEpoch(){
        MuxerTimestamp clock=new MuxerTimestamp();clock.normalize(1_000_000);clock.reset();assertEquals(0,clock.normalize(0));
    }
}
