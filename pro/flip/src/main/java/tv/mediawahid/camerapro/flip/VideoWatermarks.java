package tv.mediawahid.camerapro.flip;

import android.graphics.*;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Uses the original Pro artwork, settings, geometry and Canvas drawing code.
 * Video logos are composed after camera normalization, independently of the
 * preview's StreamSharing transform. Photo/preview retain the original effect.
 */
public final class VideoWatermarks {
    private static final Map<Object,Integer> TARGETS=Collections.synchronizedMap(new WeakHashMap<>());
    private VideoWatermarks() {}
    public static void select(Object service) {
        try {
            Object pipeline=OriginalAccess.field(service,"pipeline");
            if(pipeline!=null) TARGETS.put(pipeline,"VIDEO".equals(OriginalAccess.call(OriginalAccess.state(service),"getMode").toString())?1:7);
        } catch(ReflectiveOperationException e) {throw new IllegalStateException("Watermark target selection",e);}
    }
    public static int targets(Object pipeline) {return TARGETS.getOrDefault(pipeline,7);}
    static Bitmap render(Object service,int width,int height,int rotation) throws Exception {
        Object pipeline=OriginalAccess.field(service,"pipeline");
        Object snapshot=((AtomicReference<?>)OriginalAccess.field(pipeline,"state")).get();
        Object settings=OriginalAccess.call(snapshot,"getSettings"),artwork=OriginalAccess.call(snapshot,"getArtwork");
        ClassLoader loader=service.getClass().getClassLoader();
        Class<?> geometryType=Class.forName("tv.mediawahid.camerapro.WatermarkGeometry",true,loader);
        Class<?> slotType=Class.forName("tv.mediawahid.camerapro.LogoSlot",true,loader);
        Class<?> styleType=Class.forName("tv.mediawahid.camerapro.LogoStyle",true,loader);
        Object geometry=geometryType.getConstructor(float.class,float.class,float.class,float.class,int.class)
                .newInstance(0f,0f,(float)width,(float)height,rotation);
        Method draw=pipeline.getClass().getDeclaredMethod("drawLogo",Canvas.class,Bitmap.class,slotType,styleType,geometryType,Paint.class);
        draw.setAccessible(true);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);Matrix matrix=new Matrix();
        matrix.setValues((float[])OriginalAccess.call(geometry,"canvasMatrixValues"));canvas.concat(matrix);
        canvas.clipRect(0f,0f,((Number)OriginalAccess.call(geometry,"getUprightWidth")).floatValue(),
                ((Number)OriginalAccess.call(geometry,"getUprightHeight")).floatValue());
        Paint paint=new Paint(3);
        Bitmap media=(Bitmap)OriginalAccess.call(artwork,"getMedia");
        if(media==null)throw new IllegalStateException("Bundled TV artwork unavailable");
        draw.invoke(pipeline,canvas,media,slotType.getField("MEDIA").get(null),OriginalAccess.call(settings,"getMedia"),geometry,paint);
        if(((Number)OriginalAccess.call(settings,"getTemplate")).intValue()==1) {
            Bitmap mosque=(Bitmap)OriginalAccess.call(artwork,"getMosque");
            if(mosque==null)throw new IllegalStateException("Bundled mosque artwork unavailable");
            draw.invoke(pipeline,canvas,mosque,slotType.getField("MOSQUE").get(null),OriginalAccess.call(settings,"getMosque"),geometry,paint);
        }
        return bitmap;
    }
}
