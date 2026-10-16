package tv.mediawahid.camerapro.logotest;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.*;
import android.view.Surface;
import androidx.camera.core.*;
import androidx.camera.core.Camera;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.lifecycle.LifecycleOwner;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Camera test fixture only. Injects emulator-supported HD use cases into the actual final
 * Pro service. Production's FHD configuration/guards are unchanged. All recording, pause,
 * watermark, flip, audio, MediaStore and finalization operations use the installed APK.
 */
public final class VideoInstrumentation extends Instrumentation {
    private ClassLoader loader;
    private Activity activity;
    private Object service;
    private boolean quick;
    private int checks;
    private final JSONArray sessions=new JSONArray();
    private final JSONArray exports=new JSONArray();
    private interface Work<T> {T run() throws Exception;}
    private <T> T main(Work<T> work) throws Exception {
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{result.set(work.run());}catch(Throwable e){error.set(e);}});
        if(error.get()!=null) throw new RuntimeException(error.get());return result.get();
    }
    private Class<?> type(String name) throws Exception{return Class.forName(name,true,loader);}
    private Object field(Object x,String name) throws Exception {Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(x);}
    private void field(Object x,String name,Object value) throws Exception {Field f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value);}
    private Object call(Object x,String name,Object...args) throws Exception {
        for(Method m:x.getClass().getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){m.setAccessible(true);return m.invoke(x,args);}
        throw new NoSuchMethodException(name);
    }
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private Object state() throws Exception{return call(service,"getSnapshot");}
    private Object value(String method) throws Exception{return main(()->call(state(),method));}
    private void publish(Map<Integer,Object> changes) throws Exception {
        Object state=state();Object[] args=new Object[30];
        for(int i=0;i<30;i++)args[i]=call(state,"component"+(i+1));
        for(Map.Entry<Integer,Object> change:changes.entrySet())args[change.getKey()-1]=change.getValue();
        call(service,"publish",call(state,"copy",args));
    }
    private void waitFor(String label,Work<Boolean> condition,long timeout) throws Exception {
        long limit=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<limit){if(condition.run())return;Thread.sleep(50);}
        throw new AssertionError("Timeout "+label+" state="+main(this::state));
    }
    private void prepare(int template) throws Exception {
        main(()->{
            ProcessCameraProvider provider=(ProcessCameraProvider)field(service,"provider");
            provider.unbindAll();
            Object old=field(service,"overlay");if(old!=null)((OverlayEffect)old).close();
            field(service,"bindGeneration",(Integer)field(service,"bindGeneration")+1);
            field(service,"previewView",new WeakReference<PreviewView>(null)); // suppress idle FHD rebind in this fixture
            field(service,"rotation",0);
            Map<Integer,Object> values=new HashMap<>();
            values.put(1,false);values.put(2,type("tv.mediawahid.camerapro.CaptureMode").getField("VIDEO").get(null));
            values.put(3,type("tv.mediawahid.camerapro.RecordingPhase").getField("IDLE").get(null));
            values.put(4,false);values.put(5,true);values.put(23,null);values.put(24,null);values.put(26,null);values.put(28,false);values.put(29,false);values.put(30,0);
            publish(values);call(service,"setTemplate",template);
            Object pipeline=field(service,"pipeline");Object unit=type("kotlin.Unit").getField("INSTANCE").get(null);
            Class<?> f1=type("kotlin.jvm.functions.Function1"),f0=type("kotlin.jvm.functions.Function0");
            Object onError=Proxy.newProxyInstance(loader,new Class<?>[]{f1},(p,m,a)->{throw new AssertionError("Original watermark callback",(Throwable)a[0]);});
            Object noop1=Proxy.newProxyInstance(loader,new Class<?>[]{f1},(p,m,a)->unit);
            Object noop0=Proxy.newProxyInstance(loader,new Class<?>[]{f0},(p,m,a)->unit);
            Handler overlayHandler=new Handler(((HandlerThread)field(service,"overlayThread")).getLooper());
            OverlayEffect effect=(OverlayEffect)call(pipeline,"createEffect",overlayHandler,onError,noop0,noop1);
            Preview preview=new Preview.Builder().setTargetRotation(Surface.ROTATION_0).setMirrorMode(0)
                .setResolutionSelector(new ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(new ResolutionStrategy(new Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build()).build();
            PreviewView view=(PreviewView)field(activity,"preview");preview.setSurfaceProvider(view.getSurfaceProvider());
            Recorder recorder=new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).setAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetVideoEncodingBitRate(16000000).build();
            VideoOutput output=(VideoOutput)type("tv.mediawahid.camerapro.flip.StableVideoOutput").getMethod("wrap",Object.class,Recorder.class).invoke(null,service,recorder);
            VideoCapture<VideoOutput> video=new VideoCapture.Builder<>(output).setTargetRotation(Surface.ROTATION_0)
                .setTargetFrameRate(new Range<>(30,30)).setDynamicRange(DynamicRange.SDR).setMirrorMode(0).build();
            ViewPort port=new ViewPort.Builder(new Rational(9,16),Surface.ROTATION_0).setScaleType(ViewPort.FILL_CENTER).build();
            UseCaseGroup group=new UseCaseGroup.Builder().setViewPort(port).addUseCase(preview).addUseCase(video).addEffect(effect).build();
            field(service,"preview",preview);field(service,"video",video);field(service,"image",null);field(service,"overlay",effect);
            Camera camera=provider.bindToLifecycle((LifecycleOwner)service,CameraSelector.DEFAULT_BACK_CAMERA,group);
            field(service,"camera",camera);
            publish(Map.of(1,true,5,true));return null;
        });
        waitFor("rear camera open",()->main(()->((Camera)field(service,"camera")).getCameraInfo().getCameraState().getValue().getType()==CameraState.Type.OPEN),20000);
        Thread.sleep(500);
    }
    private void flip(Object recording,JSONArray transitions,long started) throws Exception {
        boolean previous=(Boolean)value("getFrontCamera");long before=(Long)value("getElapsedNanos"),begin=SystemClock.elapsedRealtime();
        main(()->{call(service,"switchCamera");call(service,"switchCamera");return null;}); // second tap must be gated
        waitFor("flip new camera frame",()->!previous==(Boolean)value("getFrontCamera")&&(Boolean)value("getCanSwitchCamera"),15000);
        check(main(()->field(service,"recording"))==recording,"Same Recording after flip");
        check((Long)value("getElapsedNanos")>=before,"Timer never resets at flip");
        String phase=value("getPhase").toString();check(phase.equals("RECORDING")||phase.equals("PAUSED"),"Phase retained at flip: "+phase);
        transitions.put(new JSONObject().put("at_wall_ms",begin-started).put("ready_ms",SystemClock.elapsedRealtime()-begin).put("front",!previous));
    }
    private void record(String name,int template,int duration,int count,boolean manualPause) throws Exception {
        prepare(template);
        main(()->{call(service,"startRecording");return null;});
        waitFor("recording started",()->value("getPhase").toString().equals("RECORDING"),20000);
        Object recording=main(()->field(service,"recording"));
        Object capture=main(()->field(service,"video"));Object output=call(capture,"getOutput");
        Object recorder=type("tv.mediawahid.camerapro.flip.StableVideoOutput").getMethod("unwrap",VideoOutput.class).invoke(null,output);
        long started=SystemClock.elapsedRealtime();JSONArray transitions=new JSONArray();
        for(int i=0;i<count;i++) {
            long due=started+(long)(i+1)*duration*1000/(count+1);
            while(SystemClock.elapsedRealtime()<due) {
                check(value("getPhase").toString().equals("RECORDING"),"Recording continuous before flip");Thread.sleep(250);
            }
            flip(recording,transitions,started);
        }
        if(manualPause) {
            main(()->{call(service,"pauseOrResume");return null;});
            waitFor("manual pause",()->value("getPhase").toString().equals("PAUSED"),5000);
            flip(recording,transitions,started);Thread.sleep(1000);
            main(()->{call(service,"pauseOrResume");return null;});
            waitFor("manual resume",()->value("getPhase").toString().equals("RECORDING"),5000);
            Thread.sleep(3000);
        }
        while(SystemClock.elapsedRealtime()<started+duration*1000L) {
            check(value("getPhase").toString().equals("RECORDING"),"Recording continuous");Thread.sleep(250);
        }
        check(main(()->field(service,"video"))==capture,"Same VideoCapture throughout session");
        check(type("tv.mediawahid.camerapro.flip.StableVideoOutput").getMethod("unwrap",VideoOutput.class).invoke(null,call(capture,"getOutput"))==recorder,"Same Recorder throughout session");
        main(()->{call(service,"stopRecording");return null;});
        waitFor("final media URI",()->value("getPhase").toString().equals("IDLE")&&value("getLastSavedUri")!=null,20000);
        check(value("getError")==null,"No recording finalization error: "+value("getError"));
        Uri uri=(Uri)value("getLastSavedUri");String path;
        try(Cursor cursor=getTargetContext().getContentResolver().query(uri,new String[]{MediaStore.Video.Media.DATA},null,null,null)) {
            check(cursor!=null&&cursor.moveToFirst(),"Video in MediaStore");path=cursor.getString(0);
        }
        JSONObject analysis=analyze(uri,name,template);
        analysis.put("name",name).put("template",template).put("flips",transitions).put("wall_duration_ms",SystemClock.elapsedRealtime()-started)
            .put("manual_pause",manualPause).put("path",path);
        sessions.put(analysis);exports.put(path);
    }
    private JSONObject analyze(Uri uri,String name,int template) throws Exception {
        MediaExtractor extractor=new MediaExtractor();extractor.setDataSource(getTargetContext(),uri,null);
        JSONObject report=new JSONObject();long videoDuration=0,audioDuration=0;
        for(int track=0;track<extractor.getTrackCount();track++) {
            MediaFormat format=extractor.getTrackFormat(track);String mime=format.getString(MediaFormat.KEY_MIME);
            if(!mime.startsWith("video/")&&!mime.startsWith("audio/"))continue;
            extractor.selectTrack(track);extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            ArrayList<Long> pts=new ArrayList<>();ByteBuffer bytes=ByteBuffer.allocateDirect(4*1024*1024);
            while(extractor.readSampleData(bytes,0)>=0){pts.add(extractor.getSampleTime());extractor.advance();bytes.clear();}
            extractor.unselectTrack(track);Collections.sort(pts);
            check(pts.size()>10,"Encoded samples present "+mime);long maxGap=0;
            for(int i=1;i<pts.size();i++){check(pts.get(i)>pts.get(i-1),"Unique forward PTS "+mime);maxGap=Math.max(maxGap,pts.get(i)-pts.get(i-1));}
            long span=pts.get(pts.size()-1)-pts.get(0);
            JSONObject info=new JSONObject().put("mime",mime).put("samples",pts.size()).put("duration_us",span).put("max_pts_gap_us",maxGap);
            if(mime.startsWith("video/")){videoDuration=span;info.put("width",format.getInteger(MediaFormat.KEY_WIDTH)).put("height",format.getInteger(MediaFormat.KEY_HEIGHT));check(maxGap<250000,"No large encoder timestamp gap: "+maxGap);report.put("video",info);}
            else {audioDuration=span;check(maxGap<100000,"No large encoded microphone packet gap: "+maxGap);report.put("audio",info);}
        }
        extractor.release();check(videoDuration>0&&audioDuration>0,"Both video and microphone tracks");
        check(Math.abs(videoDuration-audioDuration)<500000,"A/V duration agreement");
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();retriever.setDataSource(getTargetContext(),uri);
        JSONArray frames=new JSONArray();
        for(int i=1;i<=3;i++) {
            long at=videoDuration*i/4;Bitmap frame=retriever.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST);
            check(frame!=null,"Video playback decode "+at);
            int[] pixels=new int[frame.getWidth()*frame.getHeight()];frame.getPixels(pixels,0,frame.getWidth(),0,0,frame.getWidth(),frame.getHeight());
            int lit=0;for(int p:pixels)if(((p>>16)&255)+((p>>8)&255)+(p&255)>60)lit++;
            check(lit>pixels.length/3,"Decoded camera image is not black");
            String filename=name+"-frame-"+i+".jpg";ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,filename);
            values.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MediaWahidVideoValidation");
            Uri image=getTargetContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            try(OutputStream stream=getTargetContext().getContentResolver().openOutputStream(image)){check(frame.compress(Bitmap.CompressFormat.JPEG,95,stream),"Playback frame export");}
            frames.put(filename);frame.recycle();
        }
        retriever.release();report.put("playback_frames",frames);return report;
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);quick="true".equals(args.getString("quick"));start();}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();loader=context.getClassLoader();
            activity=startActivitySync(new Intent().setClassName(context,"tv.mediawahid.camerapro.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitFor("bound original service",()->{service=main(()->field(activity,"cameraService"));return service!=null&&main(()->field(service,"provider"))!=null;},30000);
            record("basic-template-a",1,quick?12:35,2,false);
            record("stress-template-b",2,quick?15:180,quick?2:20,false);
            record("manual-pause",1,quick?8:12,1,true);
            JSONObject report=new JSONObject().put("result","passed").put("assertions",checks).put("quick",quick).put("sessions",sessions).put("exports",exports)
                .put("scope","Real emulator camera and microphone tracks through original Pro service/Recorder/OverlayEffect/PersistentFlip; HD fixture only because emulator FHD profile is unavailable. Production FHD unchanged. Silent emulator microphone does not validate physical speech or Samsung audio sync. 90 minutes not tested.");
            result.putString("report",report.toString());result.putString("stream","VIDEO_CONTINUITY_PASS\n");finish(-1,result);
        } catch(Throwable e) {
            StringWriter text=new StringWriter();e.printStackTrace(new PrintWriter(text));result.putString("stream",text.toString());
            result.putString("partial",new JSONObject().toString());finish(1,result);
        }
    }
}
