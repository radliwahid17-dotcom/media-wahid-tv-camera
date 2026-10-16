package tv.mediawahid.camerapro.flip;

import android.util.Log;
import androidx.camera.video.VideoRecordEvent;

/** Observability only: does not control recording or change UI. */
public final class RecordingTrace {
    private RecordingTrace() { }
    public static void event(Object service, Object incoming) {
        VideoRecordEvent event = (VideoRecordEvent) incoming;
        StableVideoOutput.event(service, event);
        if (event instanceof VideoRecordEvent.Status) return;
        try {
            String detail = event instanceof VideoRecordEvent.Finalize
                    ? " error=" + ((VideoRecordEvent.Finalize) event).getError()
                        + " output=" + ((VideoRecordEvent.Finalize) event).getOutputResults().getOutputUri()
                    : "";
            Log.i("MWTV.Recording", event.getClass().getSimpleName()
                    + " session=" + OriginalAccess.field(service, "sessionNumber")
                    + " recording=" + System.identityHashCode(OriginalAccess.field(service, "recording"))
                    + " durationNs=" + event.getRecordingStats().getRecordedDurationNanos()
                    + " bytes=" + event.getRecordingStats().getNumBytesRecorded()
                    + " audioState=" + event.getRecordingStats().getAudioStats().getAudioState() + detail);
        } catch (ReflectiveOperationException e) { Log.e("MWTV.Recording", "Trace unavailable", e); }
    }
}
