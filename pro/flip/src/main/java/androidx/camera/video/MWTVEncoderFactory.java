package androidx.camera.video;

import android.annotation.SuppressLint;
import android.util.Log;
import androidx.camera.video.internal.encoder.MWTVRepeatingVideoConfig;
import androidx.camera.video.internal.encoder.VideoEncoderConfig;
import tv.mediawahid.camerapro.flip.MuxerCadenceEncoder;

/** Enables the documented MediaCodec surface repeat option through CameraX's
 * package-access factory extension. No reflection or original CameraX DEX edits.
 * CameraX 1.6.2 is pinned and validated with the final APK on Android.
 */
@SuppressLint("RestrictedApi")
public final class MWTVEncoderFactory {
    private MWTVEncoderFactory() {}
    /** Keep the camera-side consumer alive while the user-paused Recorder stays paused. */
    public static StreamInfo cameraActive(StreamInfo info) {
        if(info.getId()==StreamInfo.STREAM_ID_ERROR)return info;
        return StreamInfo.of(info.getId(),StreamInfo.StreamState.ACTIVE,info.getInProgressTransformationInfo());
    }
    public static Recorder build(Recorder.Builder builder) {
        builder.setVideoEncoderFactory((executor,config,sessionType) -> {
            VideoEncoderConfig video=(VideoEncoderConfig)config;
            Log.i("MWTV.FrameBridge","Configure encoder native frame repeat fps="+video.getEncodeFrameRate());
            return new MuxerCadenceEncoder(executor,new MWTVRepeatingVideoConfig(video),sessionType);
        });
        return builder.build();
    }
    /** Instrumentation fixture only: the emulator's HD CamcorderProfile chooses
     * AMR, whereas the Samsung FHD target normally uses MP4/AAC. Production calls
     * build(), preserving its original profile-selected audio and container.
     */
    public static Recorder buildValidation(Recorder.Builder builder) {
        Recorder recorder=build(builder);
        try {
            MediaSpec spec=recorder.mMediaSpec.fetchData().get();
            recorder.mMediaSpec.setState(spec.toBuilder().setOutputFormat(MediaSpec.OUTPUT_FORMAT_MPEG_4).build());
        } catch(Exception e) {throw new IllegalStateException("Initialize HD/AAC validation fixture",e);}
        return recorder;
    }
}
