package tv.mediawahid.camerapro.flip;

/** Main-thread transition gate; phase names are the original APK's enum names. */
public final class FlipGate {
    private boolean busy;
    private long generation;

    public static boolean allowed(String phase, boolean ready, boolean otherCamera,
                                  boolean photoBusy, boolean assetsBusy) {
        return ready && otherCamera && !photoBusy && !assetsBusy
                && ("IDLE".equals(phase) || "RECORDING".equals(phase) || "PAUSED".equals(phase));
    }

    public long begin() {
        if (busy) return -1;
        busy = true;
        return ++generation;
    }

    public boolean current(long token) { return busy && token == generation; }
    public boolean busy() { return busy; }
    public long token() { return generation; }
    public void finish(long token) { if (current(token)) busy = false; }
    public void cancel() { busy = false; ++generation; }
}
