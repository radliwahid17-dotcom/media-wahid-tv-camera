package tv.mediawahid.camerapro.flip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Rational;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraState;
import androidx.camera.core.DynamicRange;
import androidx.camera.core.ExposureState;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.Quality;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Size;
import android.util.Range;

/** Adds a recording-only path; original idle/photo binding and the entire UI remain intact. */
public final class PersistentFlip {
    private static final String TAG = "MWTV.PersistentFlip";
    private static final ExecutorService PREFLIGHT = Executors.newSingleThreadExecutor(r -> new Thread(r, "MWTV-Capabilities"));
    private static final Map<Object, PersistentFlip> INSTANCES = new WeakHashMap<>();
    private final WeakReference<Object> owner;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FlipGate gate = new FlipGate();
    private Camera bound;
    private Observer<CameraState> cameraObserver;
    private Observer<PreviewView.StreamState> streamObserver;
    private Runnable timeout;
    private boolean previousFront;
    private boolean reverting;
    private boolean opened;
    private boolean videoReady;
    private boolean bridged;
    private int bindingSequence;
    private String failure;

    private PersistentFlip(Object service) { owner = new WeakReference<>(service); }
    private static PersistentFlip instance(Object service) {
        PersistentFlip result = INSTANCES.get(service);
        if (result == null) {
            result = new PersistentFlip(service);
            INSTANCES.put(service, result);
        }
        return result;
    }

    /** Called only for the original Flip button, leaving all other recording locks unchanged. */
    public static boolean canFlip(Object state, boolean connected) {
        try {
            return connected && FlipGate.allowed(OriginalAccess.call(state, "getPhase").toString(),
                    OriginalAccess.bool(state, "getReady"), OriginalAccess.bool(state, "getCanSwitchCamera"),
                    OriginalAccess.bool(state, "getPhotoBusy"), OriginalAccess.bool(state, "getAssetsBusy"));
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Cannot read original flip state", e);
            return false;
        }
    }

    public static boolean canFlipActivity(Object activity, Object state) {
        try {
            return canFlip(state, (Boolean) OriginalAccess.field(activity, "connected"))
                    && !(Boolean) OriginalAccess.field(activity, "importInProgress");
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Cannot read activity connection", e);
            return false;
        }
    }

    public static void switchCamera(Object service) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> switchCamera(service));
            return;
        }
        try {
            Object state = OriginalAccess.state(service);
            if (!canFlip(state, true)) return;
            PersistentFlip flip = instance(service);
            if (flip.gate.busy()) return;
            if ("IDLE".equals(OriginalAccess.call(state, "getPhase").toString())) {
                flip.clearObservers();
                OriginalAccess.call(service, "switchCameraOriginal");
                return;
            }
            flip.start(service, state);
        } catch (Exception e) {
            Log.e(TAG, "Flip refused safely; recording retained", e);
        }
    }

    /** Injected before original Finalize handling and destruction to cancel stale callbacks. */
    public static void release(Object service) {
        PersistentFlip flip = INSTANCES.remove(service);
        if (flip != null) {
            flip.gate.cancel();
            flip.clearObservers();
            // After Finalize restore the original idle observers and capability/readiness checks.
            // This runs after the original event handler; never rebind a live recording.
            flip.main.post(() -> {
                try {
                    if (!(Boolean) OriginalAccess.field(service, "destroyed")
                            && OriginalAccess.field(service, "recording") == null
                            && "IDLE".equals(OriginalAccess.call(OriginalAccess.state(service), "getPhase").toString())
                            && ((WeakReference<?>) OriginalAccess.field(service, "previewView")).get() != null) {
                        OriginalAccess.call(service, "bindCamera");
                    }
                } catch (ReflectiveOperationException e) { Log.e(TAG, "Restore idle camera", e); }
            });
        }
    }

    private void start(Object service, Object state) throws Exception {
        if (OriginalAccess.field(service, "recording") == null) return;
        ProcessCameraProvider provider = (ProcessCameraProvider) OriginalAccess.field(service, "provider");
        previousFront = OriginalAccess.bool(state, "getFrontCamera");
        CameraSelector selector = selector(!previousFront);
        if (OriginalAccess.field(service, "video") == null
                || OriginalAccess.field(service, "preview") == null
                || OriginalAccess.field(service, "overlay") == null) {
            report(service, "Pipeline kamera belum siap untuk flip.");
            return;
        }
        Object recording = OriginalAccess.field(service, "recording");
        VideoCapture<?> capture = (VideoCapture<?>) OriginalAccess.field(service, "video");
        if (capture.getResolutionInfo() == null) { report(service, "Resolusi kamera belum siap."); return; }
        Size size = capture.getResolutionInfo().getCropRect().width() >= capture.getResolutionInfo().getCropRect().height()
                ? new Size(capture.getResolutionInfo().getCropRect().width(), capture.getResolutionInfo().getCropRect().height())
                : new Size(capture.getResolutionInfo().getCropRect().height(), capture.getResolutionInfo().getCropRect().width());
        Range<Integer> fps = capture.getTargetFrameRate();
        long token = gate.begin();
        if (token < 0) return;
        reverting = false;
        failure = null;
        Map<Integer, Object> changes = new HashMap<>();
        changes.put(5, false); // canSwitchCamera, without unlocking templates or resetting timer
        changes.put(22, "Switching camera…");
        changes.put(23, null);
        OriginalAccess.publish(service, changes);
        Log.i(TAG, "Flip preflight token=" + token + " targetFront=" + !previousFront
                + " recording=" + System.identityHashCode(recording) + " size=" + size + " fps=" + fps);
        // Query potentially expensive encoder/camera capabilities off the UI thread. CameraX's
        // bind/unbind API itself is main-thread-only and opens the camera asynchronously.
        PREFLIGHT.execute(() -> {
            String reason = null;
            try {
                if (provider == null || !provider.hasCamera(selector)) reason = "Kamera tujuan tidak tersedia";
                else {
                    CameraInfo target = selector.filter(provider.getAvailableCameraInfos()).get(0);
                    Quality quality = size.equals(new Size(1920,1080)) ? Quality.FHD
                            : size.equals(new Size(1280,720)) ? Quality.HD
                            : size.equals(new Size(3840,2160)) ? Quality.UHD : Quality.SD;
                    if (!Recorder.getVideoCapabilities(target).getSupportedQualities(DynamicRange.SDR).contains(quality))
                        reason = "Kamera tujuan tidak mendukung kualitas rekaman aktif " + quality;
                    else if (target.getSupportedFrameRateRanges().stream().noneMatch(range ->
                            range.contains(fps.getLower()) && range.contains(fps.getUpper())))
                        reason = "Kamera tujuan tidak mendukung frame rate aktif " + fps;
                }
            } catch (Exception error) { reason = "Pemeriksaan kamera gagal: " + error.getMessage(); }
            String rejected = reason;
            main.post(() -> {
                if (!gate.current(token)) return;
                try {
                    if (OriginalAccess.field(service, "recording") != recording
                            || "STOPPING".equals(OriginalAccess.call(OriginalAccess.state(service), "getPhase").toString())) {
                        gate.cancel(); return;
                    }
                    if (rejected != null) {
                        gate.finish(token); update(service, Map.of(5, true));
                        report(service, rejected + "; kamera aktif dan rekaman dipertahankan."); return;
                    }
                    bind(service, !previousFront, token);
                } catch (Exception error) { rollback(service, token, error); }
            });
        });
    }

    @SuppressWarnings("unchecked")
    private void bind(Object service, boolean front, long token) throws Exception {
        clearObservers();
        if (!gate.current(token) || OriginalAccess.field(service, "recording") == null) return;
        LifecycleOwner lifecycle = (LifecycleOwner) service;
        ProcessCameraProvider provider = (ProcessCameraProvider) OriginalAccess.field(service, "provider");
        Preview preview = (Preview) OriginalAccess.field(service, "preview");
        VideoCapture<Recorder> video = (VideoCapture<Recorder>) OriginalAccess.field(service, "video");
        OverlayEffect effect = (OverlayEffect) OriginalAccess.field(service, "overlay");
        Camera old = (Camera) OriginalAccess.field(service, "camera");
        if (old != null) {
            // Original error observer stops recording; detach it during an intentional camera close.
            old.getCameraInfo().getCameraState().removeObservers(lifecycle);
            old.getCameraInfo().getZoomState().removeObservers(lifecycle);
            old.getCameraInfo().getTorchState().removeObservers(lifecycle);
        }
        int rotation = (Integer) OriginalAccess.field(service, "rotation");
        boolean portrait = rotation == 0 || rotation == 2;
        ViewPort viewPort = new ViewPort.Builder(portrait ? new Rational(9, 16) : new Rational(16, 9), rotation)
                .setScaleType(ViewPort.FILL_CENTER).build();
        UseCaseGroup group = new UseCaseGroup.Builder().setViewPort(viewPort)
                .addUseCase(preview).addUseCase(video).addEffect(effect).build();
        // Arm before unbind: a fast new producer must not outrun the readiness fence.
        int sequence = ++bindingSequence;
        videoReady = false;
        PreviewView view = ((WeakReference<PreviewView>) OriginalAccess.field(service, "previewView")).get();
        WeakReference<PreviewView> viewReference = new WeakReference<>(view);
        bridged = StableVideoOutput.handoff(service, front, () -> {
            if (!gate.current(token) || bindingSequence != sequence) return;
            videoReady = true;
            tryComplete(service, front, token, viewReference.get());
        });
        // Recorder and its encoder SurfaceRequest are retained by StableVideoOutput.
        provider.unbind(preview, video);
        opened = false;
        bound = provider.bindToLifecycle(lifecycle, selector(front), group);
        OriginalAccess.field(service, "camera", bound);
        Camera current = bound;
        timeout = () -> {
            if (gate.current(token)) rollback(service, token, new IllegalStateException("Camera/preview readiness timeout"));
        };
        main.postDelayed(timeout, 8000);
        cameraObserver = cameraState -> {
            try {
                if (bound != current || OriginalAccess.field(service, "recording") == null) return;
                if (cameraState.getError() != null) {
                    if (gate.current(token)) rollback(service, token,
                            new IllegalStateException("Camera error " + cameraState.getError().getCode()));
                    else report(service, "Kamera terganggu; tekan STOP untuk menyimpan klip. Kode "
                            + cameraState.getError().getCode());
                    return;
                }
                opened = cameraState.getType() == CameraState.Type.OPEN;
                PreviewView currentView = viewReference.get();
                tryComplete(service, front, token, currentView);
            } catch (Exception e) { rollback(service, token, e); }
        };
        streamObserver = stream -> {
            if (opened && stream == PreviewView.StreamState.STREAMING && gate.current(token)) {
                tryComplete(service, front, token, viewReference.get());
            }
        };
        if (view != null) view.getPreviewStreamState().observe(lifecycle, streamObserver);
        current.getCameraInfo().getCameraState().observe(lifecycle, cameraObserver);
        current.getCameraInfo().getZoomState().observe(lifecycle, zoom -> {
            if (bound != current || zoom == null) return;
            Map<Integer, Object> changes = new HashMap<>();
            changes.put(8, zoom.getMinZoomRatio());
            changes.put(9, zoom.getMaxZoomRatio());
            changes.put(10, zoom.getZoomRatio());
            update(service, changes);
        });
        current.getCameraInfo().getTorchState().observe(lifecycle, torch -> {
            if (bound == current) update(service, Map.of(7, torch != null && torch == 1));
        });
    }

    private void tryComplete(Object service, boolean front, long token, PreviewView view) {
        boolean streamReady = bridged ? videoReady : view == null
                || view.getPreviewStreamState().getValue() == PreviewView.StreamState.STREAMING;
        if (!opened || !streamReady || !gate.current(token)) return;
        try { complete(service, front, token, view); }
        catch (Exception error) { rollback(service, token, error); }
    }

    private void complete(Object service, boolean front, long token, PreviewView view) throws Exception {
        if (!gate.current(token)) return;
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        if (view != null && streamObserver != null) view.getPreviewStreamState().removeObserver(streamObserver);
        streamObserver = null;
        // Keep the original pre-Android-13 preview correction and non-mirrored recording.
        if (view != null) view.setScaleX(android.os.Build.VERSION.SDK_INT >= 33 || !front ? 1f : -1f);
        ExposureState exposure = bound.getCameraInfo().getExposureState();
        Map<Integer, Object> changes = new HashMap<>();
        changes.put(4, front);
        changes.put(5, true);
        changes.put(6, bound.getCameraInfo().hasFlashUnit());
        changes.put(11, exposure.isExposureCompensationSupported());
        changes.put(12, exposure.getExposureCompensationRange().getLower());
        changes.put(13, exposure.getExposureCompensationRange().getUpper());
        changes.put(14, exposure.getExposureCompensationIndex());
        changes.put(15, exposure.getExposureCompensationStep().floatValue());
        Object state = OriginalAccess.state(service);
        String phase = OriginalAccess.call(state, "getPhase").toString();
        String message = (String) OriginalAccess.call(state, "getMessage");
        if ("PAUSED".equals(phase)) message = reverting ? "Camera restored; paused" : "Paused";
        if ("RECORDING".equals(phase)) message = reverting ? "Camera restored; recording continues" : "Recording";
        changes.put(22, message);
        changes.put(23, failure);
        gate.finish(token);
        OriginalAccess.publish(service, changes);
        Log.i(TAG, "Flip complete token=" + token + " front=" + front + " restored=" + reverting
                + " recording=" + System.identityHashCode(OriginalAccess.field(service, "recording")));
    }

    private void rollback(Object service, long token, Exception cause) {
        if (!gate.current(token)) return;
        Log.e(TAG, "Flip failed; preserving output and attempting original camera", cause);
        try {
            Object state = OriginalAccess.state(service);
            String phase = OriginalAccess.call(state, "getPhase").toString();
            if ("IDLE".equals(phase) || "STOPPING".equals(phase)
                    || OriginalAccess.field(service, "recording") == null) {
                gate.cancel();
                clearObservers();
                return;
            }
            if (!reverting) {
                reverting = true;
                failure = "Flip gagal; kamera sebelumnya dipulihkan. " + cause.getMessage();
                bind(service, previousFront, token);
                return;
            }
        } catch (Exception e) { Log.e(TAG, "Rollback unavailable", e); }
        gate.cancel();
        clearObservers();
        update(service, Map.of(5, false));
        report(service, "Kamera gagal dipulihkan. Rekaman belum dihentikan; tekan STOP untuk menyimpan. " + cause.getMessage());
    }

    private void clearObservers() {
        Object service = owner.get();
        if (service != null) StableVideoOutput.cancelHandoff(service);
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
        if (bound != null) {
            if (cameraObserver != null) bound.getCameraInfo().getCameraState().removeObserver(cameraObserver);
            if (service instanceof LifecycleOwner) {
                bound.getCameraInfo().getZoomState().removeObservers((LifecycleOwner) service);
                bound.getCameraInfo().getTorchState().removeObservers((LifecycleOwner) service);
            }
        }
        if (service != null && streamObserver != null) {
            try {
                Object reference = OriginalAccess.field(service, "previewView");
                Object view = ((WeakReference<?>) reference).get();
                if (view instanceof PreviewView) ((PreviewView) view).getPreviewStreamState().removeObserver(streamObserver);
            } catch (ReflectiveOperationException e) { Log.e(TAG, "Preview observer cleanup", e); }
        }
        cameraObserver = null;
        streamObserver = null;
        bound = null;
    }

    private static CameraSelector selector(boolean front) {
        return front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
    }
    private static void report(Object service, String message) {
        Log.w(TAG, message);
        update(service, Map.of(23, message));
    }
    private static void update(Object service, Map<Integer, Object> changes) {
        try { OriginalAccess.publish(service, changes); }
        catch (ReflectiveOperationException e) { Log.e(TAG, "Original state update failed", e); }
    }
}
