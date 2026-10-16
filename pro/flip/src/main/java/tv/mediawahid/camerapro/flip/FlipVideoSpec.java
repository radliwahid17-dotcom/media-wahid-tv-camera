package tv.mediawahid.camerapro.flip;

/** Camera-independent quality and exposure rules shared by both lens directions. */
final class FlipVideoSpec {
    private FlipVideoSpec() { }

    static boolean fullHd(int width, int height) {
        return (width == 1920 && height == 1080) || (width == 1080 && height == 1920);
    }

    static int exposureIndex(float ev, float step, int min, int max) {
        if (!Float.isFinite(ev) || !Float.isFinite(step) || step <= 0f) return Math.max(min, Math.min(max, 0));
        return Math.max(min, Math.min(max, Math.round(ev / step)));
    }
}
