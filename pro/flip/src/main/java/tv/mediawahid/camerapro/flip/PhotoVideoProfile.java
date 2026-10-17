package tv.mediawahid.camerapro.flip;

import android.annotation.SuppressLint;
import android.hardware.camera2.CameraDevice;
import android.util.Log;
import androidx.camera.core.impl.CaptureConfig;
import androidx.camera.core.impl.SessionConfig;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoOutput;

/** Uses the same continuous camera request template as the original photo preview.
 * CameraX still controls supported autofocus, AE/AWB, touch metering, exposure and
 * device quirks. Never submit STILL_CAPTURE repeatedly or force unsupported ISP modes.
 */
@SuppressLint("RestrictedApi")
public final class PhotoVideoProfile {
    private PhotoVideoProfile() { }
    public static <T extends VideoOutput> VideoCapture<T> build(VideoCapture.Builder<T> builder) {
        // StreamSharing propagates children's DEFAULT session templates, not just
        // Camera2Interop options. Set both defaults so its parent keeps PREVIEW too.
        builder.setDefaultSessionConfig(new SessionConfig.Builder()
                .setTemplateType(CameraDevice.TEMPLATE_PREVIEW).build());
        CaptureConfig.Builder capture=new CaptureConfig.Builder();
        capture.setTemplateType(CameraDevice.TEMPLATE_PREVIEW);
        builder.setDefaultCaptureConfig(capture.build());
        Log.i("MWTV.ImageQuality", "Video camera template=PREVIEW, matching original photo preview; CameraX owns 3A");
        return builder.build();
    }
}
