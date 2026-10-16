package androidx.camera.video.internal.encoder;

import android.annotation.SuppressLint;
import android.media.MediaFormat;
import android.util.Size;
import androidx.camera.core.impl.Timebase;

/** Narrow package-access adapter for the pinned CameraX 1.6.2 encoder config.
 * All original quality/colour/timing values pass through unchanged. The Android
 * surface source can repeat the most recent buffer without another GPU upload.
 */
@SuppressLint("RestrictedApi")
public final class MWTVRepeatingVideoConfig extends VideoEncoderConfig {
    private final VideoEncoderConfig original;
    public MWTVRepeatingVideoConfig(VideoEncoderConfig original) { this.original=original; }
    @Override public int getBitrate() { return original.getBitrate(); }
    @Override public int getCaptureFrameRate() { return original.getCaptureFrameRate(); }
    @Override public int getColorFormat() { return original.getColorFormat(); }
    @Override public VideoEncoderDataSpace getDataSpace() { return original.getDataSpace(); }
    @Override public int getEncodeFrameRate() { return original.getEncodeFrameRate(); }
    @Override public int getIFrameInterval() { return original.getIFrameInterval(); }
    @Override public Timebase getInputTimebase() { return original.getInputTimebase(); }
    @Override public String getMimeType() { return original.getMimeType(); }
    @Override public int getProfile() { return original.getProfile(); }
    @Override public Size getResolution() { return original.getResolution(); }
    @Override public Builder toBuilder() { return original.toBuilder(); }
    @Override public MediaFormat toMediaFormat() {
        MediaFormat format=original.toMediaFormat();
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER,1_000_000L/getEncodeFrameRate());
        format.setInteger(MediaFormat.KEY_PRIORITY,0);
        return format;
    }
}
