package tv.mediawahid.camerapro.flip;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.opengl.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.impl.Timebase;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Fixed encoder endpoint; camera-owned producer endpoints can be replaced independently.
 * Two fixed RGBA camera buffers plus a cached original-artwork texture
 * permit a 120 ms blend once the new sensor supplies real frames. Held frames while a
 * sensor is unavailable are measured/logged, never represented as fresh camera frames.
 */
@SuppressLint("RestrictedApi")
final class StableFrameRenderer {
    private static final String TAG = "MWTV.FrameBridge";
    private static final long BLEND_NS = 120_000_000L;
    private static final long HEARTBEAT_NS = 100_000_000L;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private final HandlerThread thread = new HandlerThread("MWTV-EncoderBridge");
    private final Handler handler;
    private final Executor executor;
    private final Consumer<Throwable> error;
    interface Artwork {Bitmap render(int width,int height,int rotation) throws Exception;}
    private final Artwork artwork;
    private final FrameCadence cadence = new FrameCadence(30);
    private final Set<Input> inputs = new HashSet<>();
    private final FloatBuffer vertices = floats(-1,-1, 1,-1, -1,1, 1,1);
    private final FloatBuffer coords = floats(0,0, 1,0, 0,1, 1,1);
    private final float[] identity = new float[16], transform = new float[16], rotationMatrix = new float[16], combined = new float[16];
    private final float[] bitmapTransform=new float[16];
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface scratch = EGL14.EGL_NO_SURFACE, window = EGL14.EGL_NO_SURFACE;
    private EGLSurface currentSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig config;
    private int oesProgram, imageProgram, width, height, latestTexture, frozenTexture, fbo;
    private int oesTransform, oesAlpha, imageAlpha, imageTransform, logoTexture;
    private boolean orientationReady, logoDirty=true;
    private int initialRotation, requestId;
    private SurfaceRequest persistent;
    private Surface encoderSurface;
    private Input current;
    private Input liveInput;
    private boolean closing, disposed, pendingEncoder, prepared, paused, fresh, hasFrame;
    private boolean waiting, expectedFront, transitioning;
    private int minimumRequest;
    private long transitionStart, handoffStart, lastLiveCapture, gapStartCapture, firstPts, lastPts, maxPtsGap;
    private long presented, repeated, lastPresentedSerial = -1, serial;
    private long lastSwapClock;
    private long tickDue,maxTickDelay,maxInputWork,maxSwapWork;
    private Runnable ready;
    private boolean tickPending;
    private final Runnable tick = () -> {
        tickPending=false;maxTickDelay=Math.max(maxTickDelay,Math.max(0,System.nanoTime()-tickDue));pump();
    };

    StableFrameRenderer(Consumer<Throwable> error,Artwork artwork) {
        this.error = error;this.artwork=artwork;
        Matrix.setIdentityM(identity,0);
        Matrix.setIdentityM(bitmapTransform,0);bitmapTransform[5]=-1f;bitmapTransform[13]=1f;
        thread.start(); handler = new Handler(thread.getLooper());
        executor = command -> { if (!handler.post(command)) error.accept(new IllegalStateException("Renderer thread closed")); };
        post(this::initialize);
    }
    private interface Work { void run() throws Exception; }
    private void post(Work work) {
        executor.execute(() -> {
            if (disposed) return;
            try { work.run(); }
            catch (Exception e) { error.accept(e); }
        });
    }
    void initialRotation(int degrees) { // GL thread; zoom/crop updates do not rebuild fixed artwork.
        if(!orientationReady||initialRotation!=degrees)logoDirty=true;
        initialRotation=degrees;orientationReady=true;
    }
    void encoder(SurfaceRequest request) {
        post(() -> {
            persistent = request; width=request.getResolution().getWidth(); height=request.getResolution().getHeight();
            makeCurrent(scratch); allocateBuffers(); pendingEncoder=true;
            request.getDeferrableSurface().getSurface().addListener(() -> {
                pendingEncoder=false;
                try {
                    Surface surface=request.getDeferrableSurface().getSurface().get();
                    if (!closing) {
                        encoderSurface=surface;
                        window=EGL14.eglCreateWindowSurface(display,config,surface,new int[]{EGL14.EGL_NONE},0);
                        require(window!=EGL14.EGL_NO_SURFACE,"Encoder EGL surface");
                        schedule();
                    }
                } catch (Exception e) { if (!closing) error.accept(e); }
                disposeWhenReturned();
            },executor);
        });
    }
    void input(SurfaceRequest request, Timebase timebase, Consumer<SurfaceRequest.TransformationInfo> initial) {
        post(() -> {
            if (closing) { request.willNotProvideSurface(); return; }
            int iw=request.getResolution().getWidth(), ih=request.getResolution().getHeight();
            if (!((iw==width&&ih==height)||(iw==height&&ih==width))) {
                request.willNotProvideSurface();
                throw new IllegalStateException("Camera input size changed during a fixed encoder session: "+request.getResolution());
            }
            makeCurrent(window!=EGL14.EGL_NO_SURFACE?window:scratch);
            Input input=new Input(++requestId,request,timebase);
            inputs.add(input); current=input;
            Log.i(TAG,"Input provided generation="+input.generation+" front="+input.front+" outstanding="+inputs.size());
            request.setTransformationInfoListener(executor, info -> {
                if(input.returned)return;
                input.rotation=info.getRotationDegrees(); input.transformed=true;
                Log.i(TAG,"Input transform generation="+input.generation+" rotation="+input.rotation);
                if (initial!=null) initial.accept(info);
            });
            input.texture.setOnFrameAvailableListener(ignored -> frame(input),handler);
            request.provideSurface(input.surface,executor, result -> {
                if(liveInput==input) {
                    if(prepared&&!closing&&!waiting&&!transitioning&&hasFrame) {
                        makeCurrent(window!=EGL14.EGL_NO_SURFACE?window:scratch);
                        attach(latestTexture);draw(input.id,true,input.matrix,1f);GLES20.glFlush();
                    }
                    liveInput=null;
                }
                input.returned=true;
                input.texture.setOnFrameAvailableListener(null);
                input.texture.release(); input.surface.release();
                makeCurrent(window!=EGL14.EGL_NO_SURFACE?window:scratch); GLES20.glDeleteTextures(1,new int[]{input.id},0);
                inputs.remove(input);
                if (current==input) current=null;
                disposeWhenReturned();
            });
        });
    }
    void prepare() { post(() -> {
        prepared=true; paused=false; fresh=false; logoDirty=true; cadence.reset();
        presented=0; repeated=0; firstPts=0; lastPts=0; maxPtsGap=0; lastPresentedSerial=-1;
        lastSwapClock=0;
        maxTickDelay=0;maxInputWork=0;maxSwapWork=0;
    }); }
    void stopPumping() { post(() -> {
        prepared=false; handler.removeCallbacks(tick); tickPending=false; waiting=false; transitioning=false; ready=null;
        Log.i(TAG,"Session frames="+presented+" heldFrames="+repeated+" maxPtsGapNs="+maxPtsGap+" firstPtsNs="+firstPts+" lastPtsNs="+lastPts
                +" maxTickDelayNs="+maxTickDelay+" maxInputWorkNs="+maxInputWork+" maxSwapWorkNs="+maxSwapWork);
    }); }
    void pause(boolean value) { post(() -> {
        paused=value;
        // EncoderImpl owns manual pause ranges and needs input crossing the pause
        // boundary to suspend/drop buffers correctly. Continue composed input;
        // the PAUSED Recorder still excludes those video/audio samples.
        if (value&&transitioning) finishHandoff();
        schedule();
    }); }
    void handoff(boolean front, Runnable callback) { post(() -> {
        if (hasFrame&& !((waiting||transitioning)&&liveInput==null)) {
            makeCurrent(window!=EGL14.EGL_NO_SURFACE?window:scratch); attach(frozenTexture); drawLive(1f);GLES20.glFlush();
        }
        waiting=true; transitioning=false; expectedFront=front; minimumRequest=requestId+1; ready=callback;
        handoffStart=System.nanoTime(); gapStartCapture=lastLiveCapture;
        Log.i(TAG,"Handoff begin targetFront="+front+" requestFence="+minimumRequest);
    }); }
    void cancelHandoff() { post(() -> { waiting=false; transitioning=false; ready=null; }); }

    private final class Input {
        final int generation,id;
        final SurfaceTexture texture;
        final Surface surface;
        final Timebase timebase;
        final boolean front;
        final float[] matrix=new float[16];
        boolean transformed, seen, returned;
        int rotation;
        long lastTimestamp = Long.MIN_VALUE;
        Input(int generation,SurfaceRequest request,Timebase timebase) {
            this.generation=generation;this.timebase=timebase;
            front=request.getCamera().getCameraInfo().getLensFacing()==CameraSelector.LENS_FACING_FRONT;
            id=texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            texture=new SurfaceTexture(id);
            texture.setDefaultBufferSize(request.getResolution().getWidth(),request.getResolution().getHeight());
            surface=new Surface(texture);
        }
    }
    private void frame(Input input) {
        long workStarted=System.nanoTime();
        try {
            if (input.returned) return; // A queued callback can outlive its producer's return.
            makeCurrent(window!=EGL14.EGL_NO_SURFACE?window:scratch);
            input.texture.updateTexImage();
            if (closing || current!=input) return;
            long timestamp=input.texture.getTimestamp();
            if (timestamp==input.lastTimestamp) return; // Already latched by an earlier queued callback.
            input.lastTimestamp=timestamp;
            if (!input.seen) {input.seen=true;Log.i(TAG,"Input first frame generation="+input.generation+" front="+input.front+" transformed="+input.transformed);}
            if (!input.transformed) return;
            input.texture.getTransformMatrix(transform);
            // Normalize raw camera buffers to the initial encoder orientation.
            // Original artwork is composed once after normalization/blending.
            Matrix.setIdentityM(rotationMatrix,0);
            Matrix.translateM(rotationMatrix,0,.5f,.5f,0f);
            Matrix.rotateM(rotationMatrix,0,input.rotation-initialRotation,0f,0f,1f);
            Matrix.translateM(rotationMatrix,0,-.5f,-.5f,0f);
            Matrix.multiplyMM(combined,0,transform,0,rotationMatrix,0);
            System.arraycopy(combined,0,input.matrix,0,16);
            // Keep a fallback for an unexpected producer return. Normal live
            // frames go directly from OES to the encoder, avoiding a full-frame
            // RGBA copy and its GPU fence on every camera callback.
            if(!hasFrame) {attach(latestTexture);draw(input.id,true,input.matrix,1f);checkGl();}
            liveInput=input;
            hasFrame=true; serial++;
            long now=System.nanoTime(), captured=timestamp;
            if (input.timebase==Timebase.REALTIME) captured-=SystemClock.elapsedRealtimeNanos()-now;
            if (captured<=0 || captured>now || now-captured>1_000_000_000L) captured=now;
            lastLiveCapture=captured;
            // This is a composed source, with its own held/blended frames. Use the
            // same monotonic clock as Recorder's microphone and pause boundaries.
            // Anchoring to a delayed first sensor frame would preserve that startup
            // lag for the entire session, including after later camera handoffs.
            if (prepared&&!fresh) { cadence.anchor(now,now); fresh=true; }
            if (waiting && input.generation>=minimumRequest && input.front==expectedFront) {
                waiting=false; transitioning=true; transitionStart=now;
                Log.i(TAG,"New camera frame targetFront="+input.front+" handoffNs="+(now-handoffStart)
                        +" sensorGapNs="+Math.max(0,captured-gapStartCapture));
                if (paused) finishHandoff();
            }
            schedule();
        } catch (Exception e) { error.accept(e); }
        finally {maxInputWork=Math.max(maxInputWork,System.nanoTime()-workStarted);}
    }
    private void schedule() {
        if (closing || !prepared || !fresh || !hasFrame || !orientationReady || window==EGL14.EGL_NO_SURFACE) return;
        queueTick(0);
    }
    private void queueTick(long delay) {
        // Never move an already scheduled output behind newly queued camera
        // callbacks. Repeated remove/post can starve the encoder under load.
        if(tickPending)return;
        tickPending=true;
        tickDue=System.nanoTime()+delay*1_000_000L;
        if(delay==0)handler.postAtFrontOfQueue(tick);
        else handler.postDelayed(tick,delay);
    }
    private void pump() {
        if (closing || !prepared || !fresh || !hasFrame || !orientationReady || window==EGL14.EGL_NO_SURFACE) return;
        long now=System.nanoTime();
        // Surface-source repetition has a finite repeat count on some codecs.
        // Refresh held input every 100 ms so a long sensor handoff cannot exhaust
        // that budget. Normal live 30 FPS inputs need no extra duplicate uploads.
        if ((waiting || (!transitioning && lastPresentedSerial==serial))
                && lastSwapClock!=0 && now-lastSwapClock<HEARTBEAT_NS) {
            queueTick(Math.max(1L,(HEARTBEAT_NS-(now-lastSwapClock)+999_999L)/1_000_000L));
            return;
        }
        long pts=cadence.presentation(now);
        try {
            if (pts!=FrameCadence.NOT_DUE) {
                makeCurrent(window);
                if(logoDirty) {
                    Bitmap bitmap=artwork.render(width,height,initialRotation);
                    try {
                        if(logoTexture==0)logoTexture=texture(GLES20.GL_TEXTURE_2D);
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,logoTexture);
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);logoDirty=false;
                    } finally {bitmap.recycle();}
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,0);
                GLES20.glViewport(0,0,width,height);
                if (waiting) draw(frozenTexture,false,identity,1f);
                else if (transitioning) {
                    float mix=Math.min(1f,(now-transitionStart)/(float)BLEND_NS);
                    draw(frozenTexture,false,identity,1f);
                    GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
                    drawLive(mix);GLES20.glDisable(GLES20.GL_BLEND);
                    if (mix>=1f) finishHandoff();
                } else drawLive(1f);
                // Canvas bitmaps contain premultiplied alpha. One overlay after
                // the camera blend keeps both logos stationary and unduplicated.
                GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
                draw(logoTexture,false,bitmapTransform,1f);GLES20.glDisable(GLES20.GL_BLEND);
                checkGl();
                // Stamp after rendering, so a slow GPU upload cannot arrive behind
                // buffers repeated by the encoder's native surface source.
                long updated=cadence.presentation(System.nanoTime());
                if (updated!=FrameCadence.NOT_DUE) pts=updated;
                require(EGLExt.eglPresentationTimeANDROID(display,window,pts),"Encoder presentation timestamp");
                long swapStarted=System.nanoTime();
                require(EGL14.eglSwapBuffers(display,window),"Encoder swap");
                lastSwapClock=System.nanoTime();
                maxSwapWork=Math.max(maxSwapWork,lastSwapClock-swapStarted);
                if (presented==0) firstPts=pts;
                else maxPtsGap=Math.max(maxPtsGap,pts-lastPts);
                lastPts=pts;presented++;
                if (waiting || lastPresentedSerial==serial) repeated++;
                lastPresentedSerial=serial;
            }
            queueTick(cadence.delayMillis(System.nanoTime()));
        } catch (Exception e) { prepared=false; error.accept(e); }
    }
    private void finishHandoff() {
        transitioning=false;waiting=false;
        Runnable callback=ready;ready=null;
        Log.i(TAG,"Handoff composited durationNs="+(System.nanoTime()-handoffStart)+" heldFrames="+repeated);
        if (callback!=null) callback.run();
    }
    private void drawLive(float alpha) {
        if(liveInput!=null&&!liveInput.returned)draw(liveInput.id,true,liveInput.matrix,alpha);
        else draw(latestTexture,false,identity,alpha);
    }
    void close() { post(() -> {
        closing=true;prepared=false;ready=null;handler.removeCallbacks(tick);tickPending=false;
        makeCurrent(scratch);
        if(window!=EGL14.EGL_NO_SURFACE) {EGL14.eglDestroySurface(display,window);window=EGL14.EGL_NO_SURFACE;}
        // Let CameraX return every producer before releasing its texture or this EGL context.
        if(persistent!=null) {persistent.willNotProvideSurface();persistent.getDeferrableSurface().close();}
        disposeWhenReturned();
    }); }
    private void disposeWhenReturned() {
        if (!closing||disposed||!inputs.isEmpty()||pendingEncoder) return;
        makeCurrent(scratch);
        if(latestTexture!=0) GLES20.glDeleteTextures(3,new int[]{latestTexture,frozenTexture,logoTexture},0);
        if(fbo!=0) GLES20.glDeleteFramebuffers(1,new int[]{fbo},0);
        if(oesProgram!=0) GLES20.glDeleteProgram(oesProgram);
        if(imageProgram!=0) GLES20.glDeleteProgram(imageProgram);
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display,scratch);EGL14.eglDestroyContext(display,context);
        EGL14.eglReleaseThread();EGL14.eglTerminate(display);
        encoderSurface=null;disposed=true;thread.quitSafely();
        Log.i(TAG,"GPU resources returned");
    }
    private void initialize() {
        display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);require(display!=EGL14.EGL_NO_DISPLAY,"EGL display");
        int[] versions=new int[2];require(EGL14.eglInitialize(display,versions,0,versions,1),"EGL initialize");
        EGLConfig[] configs=new EGLConfig[1];int[] count=new int[1];
        int[] attrs={EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT|EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
        require(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0)&&count[0]>0,"Recordable EGL config");config=configs[0];
        context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
        require(context!=EGL14.EGL_NO_CONTEXT,"EGL context");
        scratch=EGL14.eglCreatePbufferSurface(display,config,new int[]{EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE},0);
        makeCurrent(scratch);oesProgram=program(true);imageProgram=program(false);
        oesTransform=GLES20.glGetUniformLocation(oesProgram,"transform");
        oesAlpha=GLES20.glGetUniformLocation(oesProgram,"alpha");
        imageAlpha=GLES20.glGetUniformLocation(imageProgram,"alpha");
        imageTransform=GLES20.glGetUniformLocation(imageProgram,"transform");
        for(int program:new int[]{oesProgram,imageProgram}) {
            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"transform"),1,false,identity,0);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0);
        }
        vertices.position(0);coords.position(0);
        GLES20.glEnableVertexAttribArray(0);GLES20.glEnableVertexAttribArray(1);
        GLES20.glVertexAttribPointer(0,2,GLES20.GL_FLOAT,false,0,vertices);
        GLES20.glVertexAttribPointer(1,2,GLES20.GL_FLOAT,false,0,coords);
    }
    private void allocateBuffers() {
        latestTexture=texture(GLES20.GL_TEXTURE_2D);frozenTexture=texture(GLES20.GL_TEXTURE_2D);
        for(int id:new int[]{latestTexture,frozenTexture}) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,width,height,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
        }
        int[] ids=new int[1];GLES20.glGenFramebuffers(1,ids,0);fbo=ids[0];
    }
    private void attach(int texture) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);
        require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)==GLES20.GL_FRAMEBUFFER_COMPLETE,"Frame buffer");
        GLES20.glViewport(0,0,width,height);
    }
    private void makeCurrent(EGLSurface surface) {
        if (surface.equals(currentSurface)) return;
        require(EGL14.eglMakeCurrent(display,surface,surface,context),"EGL current");currentSurface=surface;
    }
    private void draw(int texture,boolean oes,float[] matrix,float alpha) {
        int program=oes?oesProgram:imageProgram,target=oes?GLES11Ext.GL_TEXTURE_EXTERNAL_OES:GLES20.GL_TEXTURE_2D;
        GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(target,texture);
        GLES20.glUniformMatrix4fv(oes?oesTransform:imageTransform,1,false,matrix,0);
        GLES20.glUniform1f(oes?oesAlpha:imageAlpha,alpha);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    private static int texture(int target) {
        int[] ids=new int[1];GLES20.glGenTextures(1,ids,0);GLES20.glBindTexture(target,ids[0]);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);return ids[0];
    }
    private static FloatBuffer floats(float... values) {FloatBuffer result=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();result.put(values).position(0);return result;}
    private static int program(boolean oes) {
        String vertex="attribute vec4 position;attribute vec4 uv;uniform mat4 transform;varying vec2 coord;void main(){gl_Position=position;coord=(transform*uv).xy;}";
        String fragment=(oes?"#extension GL_OES_EGL_image_external : require\n":"")+"precision mediump float;uniform "+(oes?"samplerExternalOES":"sampler2D")+" image;uniform float alpha;varying vec2 coord;void main(){vec4 c=texture2D(image,coord);gl_FragColor=vec4(c.rgb,c.a*alpha);}";
        int result=GLES20.glCreateProgram(),vs=shader(GLES20.GL_VERTEX_SHADER,vertex),fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);
        GLES20.glAttachShader(result,vs);GLES20.glAttachShader(result,fs);
        GLES20.glBindAttribLocation(result,0,"position");GLES20.glBindAttribLocation(result,1,"uv");GLES20.glLinkProgram(result);
        int[] valid=new int[1];GLES20.glGetProgramiv(result,GLES20.GL_LINK_STATUS,valid,0);
        require(valid[0]!=0,"GL link: "+GLES20.glGetProgramInfoLog(result));GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs);return result;
    }
    private static int shader(int type,String source) {int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);int[] valid=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,valid,0);require(valid[0]!=0,"GL shader: "+GLES20.glGetShaderInfoLog(shader));return shader;}
    private static void checkGl() {int code=GLES20.glGetError();require(code==GLES20.GL_NO_ERROR,"GL error "+code);}
    private static void require(boolean value,String message) {if(!value)throw new IllegalStateException(message);}
}
