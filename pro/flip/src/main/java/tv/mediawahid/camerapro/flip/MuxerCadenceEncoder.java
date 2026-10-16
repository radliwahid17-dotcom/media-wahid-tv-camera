package tv.mediawahid.camerapro.flip;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.util.Log;
import androidx.camera.video.internal.encoder.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/** Retains the original EncoderImpl lifecycle. No colliding AVC frame is discarded:
 * close timestamps from native repeat/live-buffer collisions are spaced by 1 ms
 * before entering the original muxer, retaining the AVC reference-frame chain.
 */
@SuppressLint("RestrictedApi")
public final class MuxerCadenceEncoder extends EncoderImpl {
    public MuxerCadenceEncoder(Executor executor,EncoderConfig config,int sessionType) throws InvalidConfigException {
        super(executor,config,sessionType);
    }
    @Override public void setEncoderCallback(EncoderCallback callback,Executor executor) {
        super.setEncoderCallback(new EncoderCallback() {
            private final MuxerTimestamp clock=new MuxerTimestamp();
            private long adjusted,maxAdjustment;
            @Override public void onEncodeStart(){clock.reset();adjusted=0;maxAdjustment=0;callback.onEncodeStart();}
            @Override public void onEncodeStop(){
                Log.i("MWTV.FrameBridge","Muxer timestamps adjusted="+adjusted+" maxAdjustmentUs="+maxAdjustment);
                callback.onEncodeStop();
            }
            @Override public void onEncodePaused(){callback.onEncodePaused();}
            @Override public void onEncodeError(EncodeException error){callback.onEncodeError(error);}
            @Override public void onOutputConfigUpdate(OutputConfig config){callback.onOutputConfigUpdate(config);}
            @Override public void onEncodedData(EncodedData data) {
                long pts=data.getPresentationTimeUs(),safe;
                try {safe=clock.normalize(pts);}
                catch(IllegalArgumentException error) {
                    data.close();callback.onEncodeError(new EncodeException(EncodeException.ERROR_UNKNOWN,error.getMessage(),error));return;
                }
                if(safe==pts){callback.onEncodedData(data);return;}
                adjusted++;maxAdjustment=Math.max(maxAdjustment,safe-pts);
                callback.onEncodedData(new TimestampedData(data,safe));
            }
        },executor);
    }
    private static final class TimestampedData implements EncodedData {
        private final EncodedData original;private final long pts;
        TimestampedData(EncodedData original,long pts){this.original=original;this.pts=pts;}
        @Override public void close(){original.close();}
        @Override public ByteBuffer getByteBuffer(){return original.getByteBuffer();}
        @Override public ListenableFuture<Void> getClosedFuture(){return original.getClosedFuture();}
        @Override public long getPresentationTimeUs(){return pts;}
        @Override public boolean isKeyFrame(){return original.isKeyFrame();}
        @Override public long size(){return original.size();}
        @Override public MediaCodec.BufferInfo getBufferInfo(){
            MediaCodec.BufferInfo source=original.getBufferInfo(),result=new MediaCodec.BufferInfo();
            result.set(source.offset,source.size,pts,source.flags);return result;
        }
    }
}
