package tv.mediawahid.camerapro.flip;

import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoOutput;

/** Non-mutating compatibility helper. The production Pro 1.0.1
 * VideoCapture.Builder.build() call is left unchanged in patch_baseline.py.
 * Never force capture template, AF, AE, AWB or another imaging configuration.
 */
public final class PhotoVideoProfile {
    private PhotoVideoProfile() { }
    public static <T extends VideoOutput> VideoCapture<T> build(VideoCapture.Builder<T> builder) {
        return builder.build();
    }
}
