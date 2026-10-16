package tv.mediawahid.camerapro.flip;

/** A wall-clock cadence, independent of either camera's timestamp epoch. */
final class FrameCadence {
    static final long NOT_DUE = Long.MIN_VALUE;
    private final long period;
    private long anchorClock, anchorPts, lastSlot = -1;
    private boolean anchored;
    FrameCadence(int fps) {
        if (fps < 1 || fps > 120) throw new IllegalArgumentException("fps");
        period = 1_000_000_000L / fps;
    }
    void reset() { anchored = false; lastSlot = -1; }
    void anchor(long now, long capturePts) {
        anchorClock = now; anchorPts = capturePts; anchored = true; lastSlot = -1;
    }
    long presentation(long now) {
        if (!anchored || now < anchorClock) return NOT_DUE;
        long slot = (now - anchorClock) / period;
        if (slot <= lastSlot) return NOT_DUE;
        lastSlot = slot;
        return anchorPts + slot * period;
    }
    long delayMillis(long now) {
        long next = anchorClock + (lastSlot + 1) * period;
        return Math.max(1L, (next - now + 999_999L) / 1_000_000L);
    }
}
