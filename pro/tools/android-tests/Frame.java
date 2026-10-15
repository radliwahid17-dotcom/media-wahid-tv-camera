// Compile-only signature. Never included in the test APK; runtime class comes from Pro.
package androidx.camera.effects;
import android.graphics.*;
import android.util.Size;
public abstract class Frame {
    public abstract Rect getCropRect();
    public abstract int getRotationDegrees();
    public abstract Matrix getSensorToBufferTransform();
    public abstract Size getSize();
    public abstract long getTimestampNanos();
    public abstract boolean isMirroring();
    public Canvas getOverlayCanvas() { throw new UnsupportedOperationException(); }
}
