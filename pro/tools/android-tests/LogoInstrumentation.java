package tv.mediawahid.camerapro.logotest;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContentValues;
import android.graphics.*;
import android.os.Bundle;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;
import androidx.camera.effects.Frame;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Runs against the installed final Pro APK, not a replacement camera implementation. */
public final class LogoInstrumentation extends Instrumentation {
    private int checks, frames;
    private ClassLoader loader;
    private Object unit;
    private File output;
    private final JSONArray cases = new JSONArray();
    private void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, loader); }
    private Object call(Object obj, String name, Object... args) throws Exception {
        for (Method m : obj.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                m.setAccessible(true);
                return m.invoke(obj, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    private Object callback(String name) throws Exception {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type(name)}, (p,m,a) -> unit);
    }
    public static final class TestFrame extends Frame {
        final Bitmap bitmap;
        final int rotation;
        final boolean mirrored;
        TestFrame(int w, int h, int r, boolean m) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            rotation = r; mirrored = m;
        }
        public Canvas getOverlayCanvas() { return new Canvas(bitmap); }
        public Rect getCropRect() { return new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()); }
        public int getRotationDegrees() { return rotation; }
        public Matrix getSensorToBufferTransform() { return new Matrix(); }
        public Size getSize() { return new Size(bitmap.getWidth(), bitmap.getHeight()); }
        public long getTimestampNanos() { return 1L; }
        public boolean isMirroring() { return mirrored; }
    }
    private Bitmap bundled(Context context, String name) throws Exception {
        try (InputStream stream = context.getAssets().open("watermarks/" + name)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            check(bitmap != null && bitmap.getWidth()==303 && bitmap.getHeight()==174, "Asset decode " + name);
            return bitmap;
        }
    }
    private void drawExpected(Canvas c, Bitmap b, int w, int h, boolean right) {
        float width=w*.25f, height=width*b.getHeight()/b.getWidth();
        float left=right ? w*.97f-width : w*.03f;
        c.drawBitmap(b, null, new RectF(left,h*.03f,left+width,h*.03f+height), new Paint(3));
    }
    private void saveBitmap(Bitmap bitmap, String name) throws Exception {
        Context ctx=getTargetContext();
        ContentValues values=new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME,name);
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/MediaWahidLogoValidation");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=ctx.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
        check(uri!=null,"Validation image MediaStore entry");
        try {
            try(OutputStream stream=ctx.getContentResolver().openOutputStream(uri)) {
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream),"Validation image export");
            }
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
            ctx.getContentResolver().update(uri,values,null,null);
        } catch(Exception error) {ctx.getContentResolver().delete(uri,null,null);throw error;}
    }
    private void exercise() throws Exception {
        Context ctx = getTargetContext();
        loader = ctx.getClassLoader();
        unit = type("kotlin.Unit").getField("INSTANCE").get(null);
        output = new File(ctx.getExternalFilesDir(null), "logo-evidence");
        check(output.mkdirs() || output.isDirectory(), "Evidence directory");
        Class<?> storeType=type("tv.mediawahid.camerapro.WatermarkStore");
        Class<?> slotType=type("tv.mediawahid.camerapro.LogoSlot");
        Object mediaSlot=slotType.getField("MEDIA").get(null), mosqueSlot=slotType.getField("MOSQUE").get(null);
        Bitmap media=bundled(ctx,"logo_media_wahid_tv.png"), mosque=bundled(ctx,"logo_masjid.png");
        // An upgrade may still contain corrupt or customized imported artwork. It must never win.
        File directory=new File(ctx.getFilesDir(),"watermark-logos"); directory.mkdirs();
        Map<File,byte[]> saved=new HashMap<>();
        try {
            for(String name:new String[]{"media.png","mosque.png"}) {
                File file=new File(directory,name);
                saved.put(file,file.exists()?java.nio.file.Files.readAllBytes(file.toPath()):null);
                try(FileOutputStream stream=new FileOutputStream(file)) { stream.write(new byte[]{1,2,3}); }
            }
            for(int reopen=0;reopen<3;reopen++) {
                Object store=storeType.getConstructor(Context.class).newInstance(ctx);
                check((Boolean)call(store,"hasLogo",mediaSlot) && (Boolean)call(store,"hasLogo",mosqueSlot),"Both logos available on reopen");
                Bitmap m=(Bitmap)call(store,"loadLogo",mediaSlot), s=(Bitmap)call(store,"loadLogo",mosqueSlot);
                check(m.sameAs(media) && s.sameAs(mosque),"Bundled artwork overrides stale imports");
                m.recycle();s.recycle();
            }
            Object store=storeType.getConstructor(Context.class).newInstance(ctx);
            Object initial=call(store,"loadTemplate",1);
            Class<?> pipelineType=type("tv.mediawahid.camerapro.WatermarkPipeline");
            Object pipeline=pipelineType.getConstructor(storeType,initial.getClass()).newInstance(store,initial);
            Method render=Arrays.stream(pipelineType.getDeclaredMethods()).filter(m -> m.getName().equals("createEffect$lambda$5")).findFirst().get();
            render.setAccessible(true);
            for(int[] size:new int[][]{{640,480},{1920,1080}}) {
                for(int template:new int[]{1,2}) {
                    call(pipeline,"setSettings",call(store,"loadTemplate",template));
                    for(int rotation:new int[]{0,90,180,270}) for(boolean mirror:new boolean[]{false,true}) {
                        int bw=size[0],bh=size[1],uw=rotation%180==0?bw:bh,uh=rotation%180==0?bh:bw;
                        TestFrame frame=new TestFrame(bw,bh,rotation,mirror);
                        Object previous=type("kotlin.jvm.internal.Ref$ObjectRef").getConstructor().newInstance();
                        Object ready=type("kotlin.jvm.internal.Ref$BooleanRef").getConstructor().newInstance();
                        Object[] args={pipeline,previous,new Matrix(),new Paint(3),callback("kotlin.jvm.functions.Function1"),ready,callback("kotlin.jvm.functions.Function0"),
                            Proxy.newProxyInstance(loader,new Class<?>[]{type("kotlin.jvm.functions.Function1")},(p,m,a)->{throw new AssertionError("Renderer error",(Throwable)a[0]);}),frame};
                        check(Boolean.TRUE.equals(render.invoke(null,args)),"Real OverlayEffect frame callback");
                        Bitmap expected=Bitmap.createBitmap(uw,uh,Bitmap.Config.ARGB_8888);
                        Canvas canvas=new Canvas(expected);
                        drawExpected(canvas,media,uw,uh,true);
                        if(template==1) drawExpected(canvas,mosque,uw,uh,false);
                        int[] want=new int[uw*uh],got=new int[bw*bh];
                        expected.getPixels(want,0,uw,0,0,uw,uh);frame.bitmap.getPixels(got,0,bw,0,0,bw,bh);
                        int different=0, significant=0, maxDelta=0;
                        for(int y=0;y<uh;y++) for(int x=0;x<uw;x++) {
                            int bx,by;
                            switch(rotation) {
                                case 90: bx=y;by=bh-1-x;break;
                                case 180: bx=bw-1-x;by=bh-1-y;break;
                                case 270: bx=bw-1-y;by=x;break;
                                default: bx=x;by=y;
                            }
                            int actual=got[by*bw+bx], target=want[y*uw+x], delta=0;
                            if(target!=actual) {
                                different++;
                                for(int shift=0;shift<=24;shift+=8) delta=Math.max(delta,Math.abs(((actual>>>shift)&255)-((target>>>shift)&255)));
                                maxDelta=Math.max(maxDelta,delta);
                                if(delta>8) significant++;
                            }
                        }
                        // Android Canvas interpolation differs slightly after a quarter turn.
                        // Ignore channel changes <=8/255; reject misplaced, missing or mirrored artwork.
                        if(significant >= want.length*.003) {
                            saveBitmap(frame.bitmap,"debug-actual.png");
                            saveBitmap(expected,"debug-reference.png");
                        }
                        check(significant < want.length*.003,"Logo pixels: differences="+different+" significant="+significant+" maxDelta="+maxDelta+" size="+bw+"x"+bh+" rotation="+rotation+" template="+template+" settings="+call(store,"loadTemplate",template));
                        // The cached overlay must remain intact on the next frame.
                        check(Boolean.TRUE.equals(render.invoke(null,args)),"Cached overlay redraw");
                        int[] again=new int[got.length];frame.bitmap.getPixels(again,0,bw,0,0,bw,bh);
                        check(Arrays.equals(got,again),"No disappearing or duplicate watermark on repeat frame");
                        String name="template-"+template+"-"+bw+"x"+bh+"-r"+rotation+"-"+(mirror?"front":"rear")+".png";
                        if(bw==1920 && (rotation==0||rotation==90) && !mirror) {
                            saveBitmap(frame.bitmap,name);
                        }
                        cases.put(new JSONObject().put("template",template).put("width",bw).put("height",bh).put("rotation",rotation).put("front_mirrored_input",mirror).put("different_pixels",different).put("significant_pixels",significant).put("max_channel_delta",maxDelta));
                        frames++;expected.recycle();frame.bitmap.recycle();
                    }
                }
            }
        } finally {
            for(Map.Entry<File,byte[]> e:saved.entrySet()) {
                if(e.getValue()==null) e.getKey().delete();
                else java.nio.file.Files.write(e.getKey().toPath(),e.getValue());
            }
        }
    }
    @Override public void onCreate(Bundle ignored) { super.onCreate(ignored); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            exercise();
            JSONObject report=new JSONObject().put("result","passed").put("assertions",checks).put("frames",frames).put("cases",cases)
                .put("scope","Installed final APK asset loading and original OverlayEffect callback with Android Bitmap/Canvas; synthetic frames, not camera video/audio or physical Samsung");
            try(FileWriter writer=new FileWriter(new File(output,"logo-device.json"))) { writer.write(report.toString(2)); }
            result.putString("report",report.toString());
            result.putString("stream","LOGO_RENDER_PASS "+checks+" assertions; "+frames+" frames\n");finish(-1,result);
        } catch(Throwable error) {
            StringWriter text=new StringWriter();error.printStackTrace(new PrintWriter(text));result.putString("stream",text.toString());finish(1,result);
        }
    }
}
