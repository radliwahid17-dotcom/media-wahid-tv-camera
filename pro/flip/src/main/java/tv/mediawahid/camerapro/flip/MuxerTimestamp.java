package tv.mediawahid.camerapro.flip;

/** Keep near-equal codec timestamps distinct after MP4 timescale rounding.
 * Preserve all compressed frames and reject an actual clock discontinuity.
 */
final class MuxerTimestamp {
    private long last=Long.MIN_VALUE;
    void reset() {last=Long.MIN_VALUE;}
    long normalize(long pts) {
        long result=last==Long.MIN_VALUE?pts:Math.max(pts,last+1000);
        if(result-pts>5000) throw new IllegalArgumentException("Video clock discontinuity: "+(result-pts)+" us");
        last=result;return result;
    }
}
