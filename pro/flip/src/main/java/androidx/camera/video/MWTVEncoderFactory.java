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
        // Reduce compression of fine detail at the original FHD/30 FPS. CameraX
        // clamps this preference to the selected device encoder's supported range.
        // Resolution, frame rate, audio, colour profile and muxer are unchanged.
        builder.setTargetVideoEncodingBitRate(24_000_000);
        builder.setVideoEncoderFactory((executor,config,sessionType) -> {
            VideoEncoderConfig video=(VideoEncoderConfig)config;
            Log.i("MWTV.ImageQuality","Encoder resolution="+video.getResolution()+" bitrate="+video.getBitrate()
                    +" captureFps="+video.getCaptureFrameRate()+" encodeFps="+video.getEncodeFrameRate()
                    +" mime="+video.getMimeType()+" profile="+video.getProfile()+" dataSpace="+video.getDataSpace());
            return new MuxerCadenceEncoder(executor,new MWTVRepeatingVideoConfig(video),sessionType);
        });
        return builder.build();
    }
}
