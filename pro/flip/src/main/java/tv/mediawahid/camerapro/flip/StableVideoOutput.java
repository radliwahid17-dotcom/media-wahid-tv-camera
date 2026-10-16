package tv.mediawahid.camerapro.flip;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.Observable;
import androidx.camera.core.impl.MutableStateObservable;
import androidx.camera.core.impl.Timebase;
import androidx.camera.video.EncoderProfilesResolver;
import androidx.camera.video.MediaSpec;
import androidx.camera.video.Recorder;
import androidx.camera.video.StreamInfo;
import androidx.camera.video.VideoCapabilities;
import androidx.camera.video.VideoOutput;
import androidx.camera.video.VideoRecordEvent;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** Keeps Recorder's surface session separate from camera-owned, replaceable surfaces.
 * CameraX 1.6.2 is pinned: its public library-group SurfaceRequest API is used explicitly.
 * No reflection into CameraX/Android internals and no audio/muxer replacement.
 */
@SuppressLint("RestrictedApi")
public final class StableVideoOutput implements VideoOutput {
    private static final String TAG = "MWTV.FrameBridge";
    private static final Map<Object, WeakReference<StableVideoOutput>> OWNERS = new WeakHashMap<>();
    private final Recorder recorder;
    private final WeakReference<Object> owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StableFrameRenderer renderer;
    private final MutableStateObservable<StreamInfo> stream;
    private final StreamInfo idleStream;
    private StreamInfo recorderStream;
    private boolean refreshing;
    private final Observable.Observer<StreamInfo> streamObserver = new Observable.Observer<>() {
        @Override public void onNewData(StreamInfo info) {
            if (info == null || closed) return;
            recorderStream = info;
            if (!refreshing) stream.setState(info);
        }
        @Override public void onError(Throwable failure) { error(failure); }
    };
    private volatile boolean active, stopping, closed;
    private volatile SourceState source = SourceState.INACTIVE;
    private SurfaceRequest encoderRequest;

    private StableVideoOutput(Object service, Recorder recorder) {
        this.recorder = recorder;
        owner = new WeakReference<>(service);
        try {
            // Recorder publishes its initial idle state synchronously; do not synthesize
            // package-private StreamInfo values or reflect into CameraX internals.
            var initial = recorder.getStreamInfo().fetchData();
            if (!initial.isDone()) throw new IllegalStateException("Initial Recorder stream unavailable");
            recorderStream = initial.get();
        } catch (Exception failure) { throw new IllegalStateException("Initial Recorder stream", failure); }
        idleStream = recorderStream;
        stream = MutableStateObservable.withInitialState(recorderStream);
        renderer = new StableFrameRenderer(this::error);
        recorder.getStreamInfo().addObserver(command -> main.post(command), streamObserver);
    }
    public static VideoOutput wrap(Object service, Recorder recorder) {
        StableVideoOutput bridge = new StableVideoOutput(service, recorder);
        OWNERS.put(service, new WeakReference<>(bridge));
        return bridge;
    }
    public static Recorder unwrap(VideoOutput output) {
        return output instanceof StableVideoOutput ? ((StableVideoOutput) output).recorder : (Recorder) output;
    }
    private static StableVideoOutput find(Object service) {
        WeakReference<StableVideoOutput> reference = OWNERS.get(service);
        return reference == null ? null : reference.get();
    }
    public static void prepare(Object service) {
        StableVideoOutput bridge = find(service);
        if (bridge != null) { bridge.stopping = false; bridge.active = true; bridge.renderer.prepare(); }
    }
    public static void stopping(Object service) {
        StableVideoOutput bridge = find(service);
        if (bridge != null) { bridge.stopping = true; bridge.renderer.stopPumping(); }
    }
    public static void released(Object service) {
        StableVideoOutput bridge = find(service);
        if (bridge == null) return;
        bridge.active = false; bridge.stopping = false; bridge.renderer.stopPumping();
        bridge.recorder.onSourceStateChanged(bridge.source);
        if (bridge.source == SourceState.INACTIVE) bridge.close();
    }
    public static void event(Object service, VideoRecordEvent event) {
        StableVideoOutput bridge = find(service);
        if (bridge == null) return;
        if (event instanceof VideoRecordEvent.Pause) bridge.renderer.pause(true);
        else if (event instanceof VideoRecordEvent.Resume) bridge.renderer.pause(false);
        else if (event instanceof VideoRecordEvent.Finalize) {
            bridge.active = false;
            bridge.stopping = false;
            bridge.renderer.stopPumping();
            if (bridge.source == SourceState.INACTIVE) bridge.close();
        }
    }
    /** Readiness is a frame from the requested camera, not stale PreviewView STREAMING. */
    static boolean handoff(Object service, boolean front, Runnable firstFrame) {
        StableVideoOutput bridge = find(service);
        if (bridge == null || bridge.closed) return false;
        bridge.renderer.handoff(front, () -> bridge.main.post(firstFrame));
        return true;
    }
    static void cancelHandoff(Object service) {
        StableVideoOutput bridge = find(service);
        if (bridge != null) bridge.renderer.cancelHandoff();
    }
    /** StreamSharing may cache the detached child's closed repeating surface while
     * rebinding an ACTIVE use case. Refresh only its camera-side repeating graph;
     * Recorder, encoder pumping and microphone remain active throughout. Dispatch
     * the second update on the next main turn so both graph states are observed.
     */
    static void refreshCameraStream(Object service) {
        StableVideoOutput bridge = find(service);
        if (bridge == null || bridge.closed) return;
        bridge.refreshing = true;
        bridge.stream.setState(bridge.idleStream);
        bridge.main.post(() -> {
            bridge.refreshing = false;
            if (!bridge.closed) bridge.stream.setState(bridge.recorderStream);
        });
        Log.i(TAG, "Refresh camera repeating surface; Recorder remains active");
    }
    @Override public void onSurfaceRequested(SurfaceRequest request) {
        onSurfaceRequested(request, Timebase.UPTIME, true);
    }
    @Override public void onSurfaceRequested(SurfaceRequest request, Timebase timebase, boolean glProcessing) {
        Log.i(TAG, "Camera surface requested size=" + request.getResolution() + " lens="
                + request.getCamera().getCameraInfo().getLensFacing() + " closed=" + closed);
        if (closed) { request.willNotProvideSurface(); return; }
        // Only the first request reaches Recorder. Subsequent camera requests replace GPU inputs.
        boolean first = encoderRequest == null;
        if (first) {
            encoderRequest = new SurfaceRequest(request.getResolution(), request.getCamera(), request.isPrimary(),
                    request.getDynamicRange(), request.getSessionType(), request.getExpectedFrameRate(),
                    () -> main.post(() -> error(new IllegalStateException("Encoder requested surface recreation"))));
            SurfaceRequest persistent = encoderRequest;
            renderer.encoder(persistent);
            recorder.onSurfaceRequested(persistent, Timebase.UPTIME, true);
        }
        SurfaceRequest persistent = encoderRequest;
        renderer.input(request, timebase, first ? info -> {
            persistent.updateTransformationInfo(info);
            renderer.initialRotation(info.getRotationDegrees());
        } : null);
    }
    @Override public void onSourceStateChanged(SourceState state) {
        source = state;
        // The encoder still receives composed frames while the physical camera is absent.
        if (active && !stopping && state != SourceState.ACTIVE_STREAMING) {
            Log.d(TAG, "Camera source " + state + "; encoder surface retained");
            return;
        }
        recorder.onSourceStateChanged(state);
        if (state == SourceState.INACTIVE && !active) close();
    }
    private void close() {
        if (closed) return;
        closed = true;
        recorder.getStreamInfo().removeObserver(streamObserver);
        renderer.close();
        Log.i(TAG, "Bridge closed recorder=" + System.identityHashCode(recorder));
    }
    private void error(Throwable error) {
        Log.e(TAG, "Frame bridge error", error);
        main.post(() -> {
            Object service = owner.get();
            if (service == null || closed) return;
            try { OriginalAccess.publish(service, Map.of(23, "Pipeline video terganggu: " + error.getMessage())); }
            catch (ReflectiveOperationException e) { Log.e(TAG, "Cannot report renderer failure", e); }
        });
    }
    @Override public Observable<StreamInfo> getStreamInfo() { return stream; }
    @Override public Observable<MediaSpec> getMediaSpec() { return recorder.getMediaSpec(); }
    @Override public Observable<Boolean> isSourceStreamRequired() { return recorder.isSourceStreamRequired(); }
    @Override public VideoCapabilities getMediaCapabilities(CameraInfo info, int type) { return recorder.getMediaCapabilities(info, type); }
    @Override public EncoderProfilesResolver getEncoderProfilesResolver(CameraInfo info, int type) { return recorder.getEncoderProfilesResolver(info, type); }
    @Override public boolean isQualitySelectorDefault() { return recorder.isQualitySelectorDefault(); }
}
