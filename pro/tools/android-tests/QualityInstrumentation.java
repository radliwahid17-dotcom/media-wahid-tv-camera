package tv.mediawahid.camerapro.logotest;

import android.app.Instrumentation;
import android.opengl.*;
import android.os.Bundle;
import java.lang.reflect.Method;
import java.nio.*;
import org.json.*;

/** Tests the installed shader with one-pixel FHD detail, not a UI screenshot.
 * Synthetic uncompressed input: does not establish camera/codec/Samsung quality.
 */
public final class QualityInstrumentation extends Instrumentation {
    private int checks;
    // GLES client arrays keep native pointers until draw; retain their direct
    // buffers across all cases, as the production renderer already does.
    private final FloatBuffer vertices=floats(-1,-1,1,-1,-1,1,1,1);
    private final FloatBuffer coordinates=floats(0,0,1,0,0,1,1,1);
    private final JSONArray cases=new JSONArray();
    private void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static FloatBuffer floats(float... values){
        FloatBuffer out=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        out.put(values).position(0);return out;
    }
    private JSONObject exercise() throws Exception {
        EGLDisplay display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGLContext context=EGL14.EGL_NO_CONTEXT;EGLSurface surface=EGL14.EGL_NO_SURFACE;
        int program=0,texture=0;
        try {
            int[] count=new int[2];check(EGL14.eglInitialize(display,count,0,count,1),"EGL initialize");
            EGLConfig[] config=new EGLConfig[1];
            check(EGL14.eglChooseConfig(display,new int[]{EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,1,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_NONE},0,config,0,1,count,0)&&count[0]>0,"RGBA8 config");
            context=EGL14.eglCreateContext(display,config[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            surface=EGL14.eglCreatePbufferSurface(display,config[0],new int[]{EGL14.EGL_WIDTH,1920,EGL14.EGL_HEIGHT,1920,EGL14.EGL_NONE},0);
            check(EGL14.eglMakeCurrent(display,surface,surface,context),"EGL current");
            JSONObject report=new JSONObject().put("renderer",GLES20.glGetString(GLES20.GL_RENDERER));
            int[] range=new int[2],bits=new int[1];
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_MEDIUM_FLOAT,range,0,bits,0);report.put("mediump_bits",bits[0]);
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,bits,0);report.put("highp_bits",bits[0]);
            Class<?> renderer=Class.forName("tv.mediawahid.camerapro.flip.StableFrameRenderer",true,getTargetContext().getClassLoader());
            Method factory=renderer.getDeclaredMethod("program",boolean.class);factory.setAccessible(true);
            int external=(Integer)factory.invoke(null,true);check(external!=0,"Actual OES shader links");GLES20.glDeleteProgram(external);
            program=(Integer)factory.invoke(null,false);check(program!=0,"Actual RGBA shader links");GLES20.glUseProgram(program);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program,"alpha"),1f);
            GLES20.glEnableVertexAttribArray(0);GLES20.glEnableVertexAttribArray(1);
            GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);
            GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,0,coordinates);
            int w=1920,h=1080;ByteBuffer source=ByteBuffer.allocateDirect(w*h*4);
            // Alternating one-pixel horizontal/vertical detail exposes a hidden
            // downscale/upscale or imprecise texture coordinates immediately.
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                source.put((byte)((x&1)*255)).put((byte)((y&1)*255));
                source.put((byte)((x*37+y*17)&255)).put((byte)255);
            }
            source.rewind();int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);texture=ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            for(int p:new int[]{GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_TEXTURE_MAG_FILTER})GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,p,GLES20.GL_LINEAR);
            for(int p:new int[]{GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_TEXTURE_WRAP_T})GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,p,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,source);
            GLES20.glDisable(GLES20.GL_DITHER);
            for(int rotation:new int[]{0,90,180,270}){
                int ow=rotation%180==0?w:h,oh=rotation%180==0?h:w;
                float[] matrix=new float[16];Matrix.setIdentityM(matrix,0);
                Matrix.translateM(matrix,0,.5f,.5f,0f);Matrix.rotateM(matrix,0,rotation,0f,0f,1f);Matrix.translateM(matrix,0,-.5f,-.5f,0f);
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,matrix,0);
                GLES20.glViewport(0,0,ow,oh);GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
                ByteBuffer actual=ByteBuffer.allocateDirect(ow*oh*4);
                GLES20.glReadPixels(0,0,ow,oh,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,actual);check(GLES20.glGetError()==0,"Render/read "+rotation);
                long error=0;int max=0;
                for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){
                    int sx=rotation==0?x:rotation==90?w-1-y:rotation==180?w-1-x:y;
                    int sy=rotation==0?y:rotation==90?x:rotation==180?h-1-y:h-1-x;
                    for(int channel=0;channel<3;channel++){
                        int delta=Math.abs((source.get((sy*w+sx)*4+channel)&255)-(actual.get((y*ow+x)*4+channel)&255));
                        error+=delta;max=Math.max(max,delta);
                    }
                }
                double mean=error/(double)(ow*oh*3);
                cases.put(new JSONObject().put("width",ow).put("height",oh).put("rotation",rotation).put("mean_channel_error",mean).put("max_channel_error",max));
                check(mean<2.5&&max<=12,"FHD pixel detail preserved: rotation="+rotation+" mean="+mean+" max="+max);
            }
            // Diagnostic comparison only: exact pre-1.0.5 shader precision.
            // Never substitute it for the installed production shader above.
            Method compile=renderer.getDeclaredMethod("shader",int.class,String.class);compile.setAccessible(true);
            int reference=GLES20.glCreateProgram();
            int vs=(Integer)compile.invoke(null,GLES20.GL_VERTEX_SHADER,"attribute vec4 position;attribute vec4 uv;uniform mat4 transform;varying vec2 coord;void main(){gl_Position=position;coord=(transform*uv).xy;}");
            int fs=(Integer)compile.invoke(null,GLES20.GL_FRAGMENT_SHADER,"precision mediump float;uniform sampler2D image;uniform float alpha;varying vec2 coord;void main(){vec4 c=texture2D(image,coord);gl_FragColor=vec4(c.rgb,c.a*alpha);}");
            try {
                GLES20.glAttachShader(reference,vs);GLES20.glAttachShader(reference,fs);
                GLES20.glBindAttribLocation(reference,0,"position");GLES20.glBindAttribLocation(reference,1,"uv");GLES20.glLinkProgram(reference);
                int[] linked=new int[1];GLES20.glGetProgramiv(reference,GLES20.GL_LINK_STATUS,linked,0);check(linked[0]!=0,"Reference shader links");
                JSONObject cost=new JSONObject();
                for(int candidate:new int[]{reference,program}){
                    GLES20.glUseProgram(candidate);float[] identity=new float[16];Matrix.setIdentityM(identity,0);
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(candidate,"transform"),1,false,identity,0);
                    GLES20.glUniform1f(GLES20.glGetUniformLocation(candidate,"alpha"),1f);GLES20.glUniform1i(GLES20.glGetUniformLocation(candidate,"image"),0);
                    GLES20.glViewport(0,0,w,h);
                    for(int warm=0;warm<2;warm++){GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();}
                    long start=System.nanoTime();for(int sample=0;sample<5;sample++){GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GLES20.glFinish();}
                    cost.put(candidate==reference?"reference_mediump_mean_ns":"production_mean_ns",(System.nanoTime()-start)/5);
                    if(candidate==reference){
                        ByteBuffer pixels=ByteBuffer.allocateDirect(w*h*4);GLES20.glReadPixels(0,0,w,h,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,pixels);
                        long error=0;int maximum=0;
                        for(int i=0;i<w*h;i++)for(int channel=0;channel<3;channel++){
                            int delta=Math.abs((source.get(i*4+channel)&255)-(pixels.get(i*4+channel)&255));error+=delta;maximum=Math.max(maximum,delta);
                        }
                        report.put("reference_mediump_detail",new JSONObject().put("mean_channel_error",error/(double)(w*h*3)).put("max_channel_error",maximum));
                    }
                }
                check(GLES20.glGetError()==0,"Shader diagnostic GL state");report.put("synthetic_shader_cost",cost);
            } finally {GLES20.glDeleteProgram(reference);GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);}
            return report.put("result","passed").put("assertions",checks).put("cases",cases)
                .put("scope","Installed production OES/RGBA shader compilation and uncompressed one-pixel Full HD RGBA detail at four rotations. Not camera OES pixels, encoder compression, speech sync, or physical Samsung/photo equivalence.");
        } finally {
            if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);
            if(program!=0)GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglReleaseThread();EGL14.eglTerminate(display);
        }
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {result.putString("report",exercise().toString());result.putString("stream","VIDEO_DETAIL_PASS\n");finish(-1,result);}
        catch(Throwable failure){result.putString("cases",cases.toString());result.putString("stream",android.util.Log.getStackTraceString(failure));finish(1,result);}
    }
}
