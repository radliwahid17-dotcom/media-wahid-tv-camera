package tv.mediawahid.camerapro.test;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import java.lang.reflect.*;
import java.util.concurrent.Callable;
import org.json.*;

/** Runs inside the signed final APK's process, using its real service and UI controls. */
public final class RecordingHarness extends Instrumentation {
    private Object service;
    private volatile Activity activity;
    private final JSONArray observations = new JSONArray();
    private final JSONArray videos = new JSONArray();
    private int assertions;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public boolean onException(Object object, Throwable error) {
        android.util.Log.e("MWTV.DeviceTest", "Uncaught test-process exception", error);
        return super.onException(object, error);
    }
    @Override public void callActivityOnResume(Activity resumed) {
        super.callActivityOnResume(resumed);
        if (resumed.getClass().getName().equals("tv.mediawahid.camerapro.MainActivity")) activity = resumed;
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = new Intent().setClassName("tv.mediawahid.camerapro", "tv.mediawahid.camerapro.MainActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Camera preview continuously schedules frames. Waiting for global UI idle
            // can block startActivitySync on CPU-only emulators, so observe lifecycle.
            getTargetContext().startActivity(launch);
            await(() -> activity != null, "activity resume");
            await(() -> { service = field(activity, "cameraService"); return service != null; }, "service connection");
            snapshot("service-connected");
            await(() -> Boolean.TRUE.equals(state("getReady")), "initial camera readiness");
            for (String name : new String[]{"MEDIA", "MOSQUE"}) {
                Object slot = Class.forName("tv.mediawahid.camerapro.LogoSlot").getField(name).get(null);
                Object store = main(() -> call(service, "getWatermarkStore"));
                check((Boolean) main(() -> call(store, "hasLogo", slot)), "permanent logo present: " + name);
                android.graphics.Bitmap logo = main(() -> (android.graphics.Bitmap) call(store, "loadLogo", slot));
                check(logo.getWidth() == 303 && logo.getHeight() == 174, "permanent logo decodes: " + name);
                logo.recycle();
            }
            for (int template = 1; template <= 2; template++) {
                final int choice = template;
                main(() -> { call(service, "setTemplate", choice); return null; });
                await(() -> Boolean.TRUE.equals(state("getReady")), "template readiness");
                Object recording = startVideo();
                flip(recording); flip(recording);
                main(() -> { call(service, "pauseOrResume"); return null; });
                await(() -> "PAUSED".equals(state("getPhase").toString()), "pause");
                flip(recording);
                check("PAUSED".equals(state("getPhase").toString()), "flip retains pause");
                main(() -> { call(service, "pauseOrResume"); return null; });
                await(() -> "RECORDING".equals(state("getPhase").toString()), "resume");
                flip(recording);
                if (choice == 2) {
                    main(() -> { call(service, "pauseOrResume"); return null; });
                    await(() -> "PAUSED".equals(state("getPhase").toString()), "pause before queued stop");
                }
                // The same real UI button must reject a second rapid tap while the gate is busy.
                main(() -> {
                    ((View) field(activity, "flipButton")).performClick();
                    ((View) field(activity, "flipButton")).performClick();
                    call(service, "stopRecording");
                    return null;
                });
                await(() -> "IDLE".equals(state("getPhase").toString()), "finalize");
                check(main(() -> field(service, "recording")) == null, "recording released");
                Object uri = state("getLastSavedUri");
                check(uri != null, "saved output URI");
                videos.put(new JSONObject().put("template", choice).put("uri", uri.toString()));
                await(() -> Boolean.TRUE.equals(state("getReady")), "idle camera restored");
                snapshot("saved-template-" + choice);
            }
            result.putString("result", "passed");
            result.putInt("assertions", assertions);
            result.putString("videos", videos.toString());
            result.putString("observations", observations.toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "failed");
            result.putString("error", error.toString());
            result.putString("observations", observations.toString());
            android.util.Log.e("MWTV.DeviceTest", "Recording harness failed", error);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private Object startVideo() throws Exception {
        main(() -> { ((View) field(activity, "recordButton")).performClick(); return null; });
        await(() -> "RECORDING".equals(state("getPhase").toString()), "record start");
        await(() -> (Long) state("getElapsedNanos") > 2000000000L, "encoded frames");
        snapshot("recording");
        return main(() -> field(service, "recording"));
    }
    private void flip(Object recording) throws Exception {
        boolean before = (Boolean) state("getFrontCamera");
        long duration = (Long) state("getElapsedNanos");
        Object settings = main(() -> call(call(service, "getWatermarkStore"), "load"));
        main(() -> { ((View) field(activity, "flipButton")).performClick(); return null; });
        await(() -> (Boolean) state("getFrontCamera") != before && Boolean.TRUE.equals(state("getCanSwitchCamera")), "camera flip");
        check(main(() -> field(service, "recording")) == recording, "same Recording object");
        check((Long) state("getElapsedNanos") >= duration, "timer never reset");
        check((Boolean) state("getConfigurationLocked"), "configuration remains locked");
        check(settings.equals(main(() -> call(call(service, "getWatermarkStore"), "load"))), "watermark settings unchanged");
        check(state("getError") == null, "no flip error: " + state("getError"));
        snapshot("flip");
        if ("RECORDING".equals(state("getPhase").toString())) {
            long bytes = (Long) state("getBytesRecorded");
            await(() -> (Long) state("getElapsedNanos") > duration + 500000000L
                    && (Long) state("getBytesRecorded") > bytes, "encoded output continues after flip");
            snapshot("frames-after-flip");
        }
        Thread.sleep(1500);
    }
    private void snapshot(String label) throws Exception {
        JSONObject item = new JSONObject().put("step", label);
        for (String name : new String[]{"getPhase", "getFrontCamera", "getElapsedNanos", "getBytesRecorded", "getAudioWarning", "getMessage", "getError"})
            item.put(name, String.valueOf(state(name)));
        observations.put(item);
        android.util.Log.i("MWTV.DeviceTest", item.toString());
    }
    private void check(boolean valid, String description) {
        assertions++;
        if (!valid) throw new AssertionError(description);
    }
    private Object state(String name) throws Exception { return main(() -> call(call(service, "getSnapshot"), name)); }
    private void await(Callable<Boolean> condition, String description) throws Exception {
        long deadline = System.currentTimeMillis() + 180000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.call()) return;
            Thread.sleep(500);
        }
        if (service != null) snapshot("timeout-" + description);
        throw new AssertionError("Timeout: " + description);
    }
    private <T> T main(Callable<T> action) throws Exception {
        final Object[] value = new Object[1]; final Exception[] error = new Exception[1];
        runOnMainSync(() -> { try { value[0] = action.call(); } catch (Exception e) { error[0] = e; } });
        if (error[0] != null) throw error[0];
        return (T) value[0];
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    private static Object call(Object owner, String name, Object... args) throws Exception {
        for (Method method : owner.getClass().getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterCount() == args.length) {
                method.setAccessible(true); return method.invoke(owner, args);
            }
        throw new NoSuchMethodException(name);
    }
}
