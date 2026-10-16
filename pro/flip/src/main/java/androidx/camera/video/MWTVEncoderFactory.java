package androidx.camera.video;

import android.annotation.SuppressLint;
import android.util.Log;
import androidx.camera.video.internal.encoder.EncoderImpl;
import androidx.camera.video.internal.encoder.MWTVRepeatingVideoConfig;
import androidx.camera.video.internal.encoder.VideoEncoderConfig;

/** Enables the documented MediaCodec surface repeat option through CameraX's
 * package-access factory extension. No reflection or original CameraX DEX edits.
 * CameraX 1.6.2 is pinned and validated with the final APK on Android.
 */
@SuppressLint("RestrictedApi")
public final class MWTVEncoderFactory {
    private MWTVEncoderFactory() {}
    public static Recorder build(Recorder.Builder builder) {
        builder.setVideoEncoderFactory((executor,config,sessionType) -> {
            VideoEncoderConfig video=(VideoEncoderConfig)config;
            Log.i("MWTV.FrameBridge","Configure encoder native frame repeat fps="+video.getEncodeFrameRate());
            return new EncoderImpl(executor,new MWTVRepeatingVideoConfig(video),sessionType);
        });
        return builder.build();
    }
}
