package com.gen5w.launcher.render;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared animated media playback view for BOTH full-screen home backdrops and individual
 * animated tiles/panels.
 *
 * Supports:
 *  - "frames": JPEG/WebP/PNG frame sequence via 3-bitmap inBitmap ring (zero VPU/AudioFocus use!)
 *  - "gif":    Native Android Movie animated GIF rasterized via software canvas (safe on KitKat GLES20Canvas)
 *  - "video":  Silent muted MP4/WebM TextureView playback with automatic fallback to poster/frames
 */
public final class AnimatedMediaLayer extends FrameLayout {
    public enum Kind {
        NONE,
        FRAMES,
        GIF,
        VIDEO
    }

    private Kind kind = Kind.NONE;
    private AssetResolver assets;
    private int dimAlpha = 0;
    private float cornerRadiusPx = 0f;
    private boolean externalPaused = false;

    // Frame sequence state (3-buffer ring off UI thread so GLES20DisplayList never races inBitmap)
    private final List<String> frameRefs = new ArrayList<String>();
    private int frameFps = 16;
    private boolean preferRgb565 = true;
    private volatile boolean workerRunning = false;
    private Thread decodeThread;
    private volatile Bitmap frontBitmap;
    private final Object bitmapLock = new Object();
    private final Paint drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dstRect = new Rect();
    private final RectF boundsRect = new RectF();
    private final Matrix shaderMatrix = new Matrix();
    private Bitmap shaderSourceBmp;
    private BitmapShader cachedBmpShader;

    // Native Movie (GIF) state — drawn through software Bitmap to prevent KitKat GLES20Canvas native crash
    private Movie gifMovie;
    private long gifStartMs = 0L;
    private Bitmap gifSoftBitmap;
    private Canvas gifSoftCanvas;

    // Optional Video (silent TextureView) state
    private String videoRef = "";
    private Bitmap posterFallback;
    private TextureView textureView;
    private MediaPlayer mediaPlayer;
    private Surface videoSurface;
    private boolean videoPrepared = false;
    private int videoWidth = 0;
    private int videoHeight = 0;

    public AnimatedMediaLayer(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void setCornerRadiusPx(float radiusPx) {
        float clamped = Math.max(0f, radiusPx);
        if (Math.abs(this.cornerRadiusPx - clamped) > 0.1f) {
            this.cornerRadiusPx = clamped;
            invalidate();
        }
    }

    public void setExternalPaused(boolean paused) {
        if (this.externalPaused == paused) {
            return;
        }
        this.externalPaused = paused;
        syncPlaybackState();
    }

    public void clear() {
        stopPlayback();
        kind = Kind.NONE;
        frameRefs.clear();
        gifMovie = null;
        gifSoftCanvas = null;
        gifSoftBitmap = null;
        videoRef = "";
        posterFallback = null;
        shaderSourceBmp = null;
        cachedBmpShader = null;
        shaderPaint.setShader(null);
        synchronized (bitmapLock) {
            frontBitmap = null;
        }
        removeAllViews();
        invalidate();
    }

    public void setFrames(AssetResolver resolver, String refOrDir, int fpsOverride, boolean opaqueRgb565, int dimAlpha) {
        clear();
        if (resolver == null || refOrDir == null || refOrDir.trim().isEmpty()) {
            return;
        }
        this.assets = resolver;
        this.preferRgb565 = opaqueRgb565;
        this.dimAlpha = Math.max(0, Math.min(240, dimAlpha));
        this.dimPaint.setColor((this.dimAlpha << 24) | 0x060B12);

        String clean = refOrDir.trim().replaceAll("^/+|/+$", "");
        int fps = fpsOverride > 0 ? fpsOverride : 16;

        try {
            String manifestRef = clean.endsWith(".json") ? clean : (clean + "/frames.json");
            String baseDir = clean.endsWith(".json") ? clean.substring(0, Math.max(0, clean.lastIndexOf('/'))) : clean;
            String manifest = resolver.readTextAsset(manifestRef);
            if (manifest != null) {
                JSONObject j = new JSONObject(manifest);
                fps = Math.max(1, Math.min(30, j.optInt("fps", fps)));
                JSONArray arr = j.optJSONArray("frames");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String f = arr.optString(i, "").trim();
                        if (!f.isEmpty()) {
                            frameRefs.add(baseDir.isEmpty() ? f : (baseDir + "/" + f));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (frameRefs.isEmpty()) {
            frameRefs.addAll(resolver.listImageFrames(clean));
        }
        if (frameRefs.isEmpty()) {
            return;
        }
        this.frameFps = Math.max(1, Math.min(30, fps));
        this.kind = Kind.FRAMES;
        syncPlaybackState();
    }

    public void setGif(AssetResolver resolver, String gifAsset, int dimAlpha) {
        clear();
        if (resolver == null || gifAsset == null || gifAsset.trim().isEmpty()) {
            return;
        }
        this.assets = resolver;
        this.dimAlpha = Math.max(0, Math.min(240, dimAlpha));
        this.dimPaint.setColor((this.dimAlpha << 24) | 0x060B12);
        try {
            byte[] data = resolver.readAssetBytes(gifAsset.trim());
            if (data != null && data.length > 0) {
                Movie m = Movie.decodeByteArray(data, 0, data.length);
                if (m != null && m.width() > 0 && m.height() > 0
                        && m.width() <= 1920 && m.height() <= 1080) {
                    this.gifMovie = m;
                    this.gifStartMs = SystemClock.uptimeMillis();
                    this.kind = Kind.GIF;
                    syncPlaybackState();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public void setVideo(AssetResolver resolver, String videoAsset, String posterAsset, int dimAlpha) {
        clear();
        if (resolver == null || videoAsset == null || videoAsset.trim().isEmpty()) {
            return;
        }
        this.assets = resolver;
        this.videoRef = videoAsset.trim();
        this.posterFallback = (posterAsset != null && !posterAsset.isEmpty()) ? resolver.loadBitmap(posterAsset) : null;
        this.dimAlpha = Math.max(0, Math.min(240, dimAlpha));
        this.dimPaint.setColor((this.dimAlpha << 24) | 0x060B12);
        this.kind = Kind.VIDEO;

        textureView = new TextureView(getContext());
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                videoSurface = new Surface(surface);
                startMediaPlayer();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                applyVideoCropTransform(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                releaseMediaPlayer();
                videoSurface = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
        addView(textureView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        syncPlaybackState();
    }

    public static boolean isGifAsset(String ref) {
        return ref != null && ref.toLowerCase(Locale.US).trim().endsWith(".gif");
    }

    public static boolean isVideoAsset(String ref) {
        if (ref == null) return false;
        String low = ref.toLowerCase(Locale.US).trim();
        return low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".webm");
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        syncPlaybackState(visibility == VISIBLE && getWindowToken() != null && getVisibility() == VISIBLE);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncPlaybackState();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPlayback();
        super.onDetachedFromWindow();
    }

    private void syncPlaybackState() {
        boolean visible = !externalPaused
                && getWindowVisibility() == VISIBLE
                && getVisibility() == VISIBLE
                && getWindowToken() != null;
        syncPlaybackState(visible);
    }

    private void syncPlaybackState(boolean visible) {
        if (!visible || externalPaused || kind == Kind.NONE) {
            stopPlayback();
            return;
        }
        if (kind == Kind.FRAMES) {
            startFrameWorker();
        } else if (kind == Kind.GIF) {
            postInvalidateOnAnimation();
        } else if (kind == Kind.VIDEO && videoSurface != null && mediaPlayer == null) {
            startMediaPlayer();
        }
    }

    private void stopPlayback() {
        workerRunning = false;
        if (decodeThread != null) {
            decodeThread.interrupt();
            decodeThread = null;
        }
        releaseMediaPlayer();
    }

    private void startFrameWorker() {
        if (workerRunning || frameRefs.isEmpty()) {
            return;
        }
        workerRunning = true;
        decodeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap[] pool = new Bitmap[3];
                int poolSlot = 0;
                byte[] tempStorage = new byte[16 * 1024];
                int index = 0;
                long frameDelayMs = Math.max(25L, 1000L / frameFps);
                while (workerRunning && !Thread.currentThread().isInterrupted()) {
                    long start = SystemClock.uptimeMillis();
                    String ref = frameRefs.get(index % frameRefs.size());
                    Bitmap candidate = pool[poolSlot];
                    synchronized (bitmapLock) {
                        if (candidate == frontBitmap) {
                            candidate = null;
                        }
                    }
                    Bitmap decoded = decodeFrame(ref, candidate, tempStorage);
                    if (decoded != null) {
                        pool[poolSlot] = decoded;
                        poolSlot = (poolSlot + 1) % pool.length;
                        synchronized (bitmapLock) {
                            frontBitmap = decoded;
                        }
                        postInvalidate();
                    }
                    index = (index + 1) % frameRefs.size();
                    long elapsed = SystemClock.uptimeMillis() - start;
                    long sleep = Math.max(8L, frameDelayMs - elapsed);
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "gen5w-anim-frames");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private Bitmap decodeFrame(String ref, Bitmap reusable, byte[] tempStorage) {
        if (assets == null) {
            return null;
        }
        try {
            byte[] bytes = assets.readAssetBytes(ref);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inMutable = true;
            opts.inSampleSize = 1;
            opts.inTempStorage = tempStorage;
            opts.inPreferredConfig = preferRgb565 ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
            if (reusable != null && !reusable.isRecycled()) {
                opts.inBitmap = reusable;
                try {
                    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
                } catch (IllegalArgumentException ignored) {
                    opts.inBitmap = null;
                }
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startMediaPlayer() {
        if (mediaPlayer != null || videoSurface == null || videoRef.isEmpty() || assets == null) {
            return;
        }
        try {
            final MediaPlayer mp = new MediaPlayer();
            mp.setSurface(videoSurface);
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            File ext = assets.resolveExternalFile(videoRef);
            if (ext != null) {
                mp.setDataSource(ext.getAbsolutePath());
            } else {
                AssetFileDescriptor afd = assets.openAssetFd(videoRef);
                if (afd == null) {
                    mp.release();
                    return;
                }
                try {
                    mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                } finally {
                    afd.close();
                }
            }
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer ready) {
                    videoPrepared = true;
                    videoWidth = ready.getVideoWidth();
                    videoHeight = ready.getVideoHeight();
                    if (textureView != null) {
                        applyVideoCropTransform(textureView.getWidth(), textureView.getHeight());
                    }
                    ready.start();
                }
            });
            mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                @Override
                public void onVideoSizeChanged(MediaPlayer m, int w, int h) {
                    videoWidth = w;
                    videoHeight = h;
                    if (textureView != null) {
                        applyVideoCropTransform(textureView.getWidth(), textureView.getHeight());
                    }
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    releaseMediaPlayer();
                    invalidate();
                    return true;
                }
            });
            mediaPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable ignored) {
            releaseMediaPlayer();
        }
    }

    private void releaseMediaPlayer() {
        videoPrepared = false;
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try {
                if (mp.isPlaying()) mp.stop();
            } catch (Throwable ignored) {
            }
            try {
                mp.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private void applyVideoCropTransform(int vw, int vh) {
        if (textureView == null || vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        float sx = (float) vw / videoWidth;
        float sy = (float) vh / videoHeight;
        float maxScale = Math.max(sx, sy);
        float scaleX = (videoWidth * maxScale) / vw;
        float scaleY = (videoHeight * maxScale) / vh;
        Matrix m = new Matrix();
        m.setScale(scaleX, scaleY, vw * 0.5f, vh * 0.5f);
        textureView.setTransform(m);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(canvas);
            return;
        }
        if (kind == Kind.FRAMES) {
            Bitmap bmp;
            synchronized (bitmapLock) {
                bmp = frontBitmap;
            }
            if (bmp != null && !bmp.isRecycled()) {
                drawCenterCrop(canvas, w, h, bmp, false);
            }
        } else if (kind == Kind.GIF && gifMovie != null) {
            int mw = gifMovie.width();
            int mh = gifMovie.height();
            if (mw > 0 && mh > 0) {
                if (gifSoftBitmap == null || gifSoftBitmap.getWidth() != mw || gifSoftBitmap.getHeight() != mh) {
                    gifSoftBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
                    gifSoftCanvas = new Canvas(gifSoftBitmap);
                }
                int dur = gifMovie.duration();
                if (dur <= 0) dur = 1000;
                int relTime = (int) ((SystemClock.uptimeMillis() - gifStartMs) % dur);
                gifMovie.setTime(relTime);
                gifSoftCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
                gifMovie.draw(gifSoftCanvas, 0f, 0f);
                drawCenterCrop(canvas, w, h, gifSoftBitmap, true);
                if (!externalPaused && getWindowVisibility() == VISIBLE && getVisibility() == VISIBLE) {
                    postInvalidateOnAnimation();
                }
            }
        } else if (kind == Kind.VIDEO && !videoPrepared && posterFallback != null) {
            drawCenterCrop(canvas, w, h, posterFallback, false);
        }

        super.dispatchDraw(canvas);

        if (dimAlpha > 0) {
            boundsRect.set(0f, 0f, w, h);
            if (cornerRadiusPx > 0.5f) {
                canvas.drawRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, dimPaint);
            } else {
                canvas.drawRect(boundsRect, dimPaint);
            }
        }
    }

    private void drawCenterCrop(Canvas canvas, int vw, int vh, Bitmap bmp, boolean mutableInPlace) {
        float scale = Math.max((float) vw / bmp.getWidth(), (float) vh / bmp.getHeight());
        float dw = bmp.getWidth() * scale;
        float dh = bmp.getHeight() * scale;
        float left = (vw - dw) * 0.5f;
        float top = (vh - dh) * 0.5f;
        if (cornerRadiusPx > 0.5f) {
            if (mutableInPlace || shaderSourceBmp != bmp || cachedBmpShader == null) {
                shaderSourceBmp = bmp;
                cachedBmpShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            }
            shaderMatrix.setScale(scale, scale);
            shaderMatrix.postTranslate(left, top);
            cachedBmpShader.setLocalMatrix(shaderMatrix);
            shaderPaint.setShader(cachedBmpShader);
            boundsRect.set(0f, 0f, vw, vh);
            canvas.drawRoundRect(boundsRect, cornerRadiusPx, cornerRadiusPx, shaderPaint);
            shaderPaint.setShader(null);
        } else {
            dstRect.set(Math.round(left), Math.round(top), Math.round(left + dw), Math.round(top + dh));
            canvas.drawBitmap(bmp, null, dstRect, drawPaint);
        }
    }
}
