package com.gen5w.launcher.store;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.gen5w.launcher.render.SafeThemeZipExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Memory-safe, bounded disk + RAM thumbnail cache and decoder for KitKat (API 19).
 * Uses RGB_565 + BitmapFactory inSampleSize so grid thumbnails and enlarged modal previews
 * load briskly without exhausting the automotive head-unit heap.
 */
public final class StoreThumbnailLoader {
    private static final int MEM_CACHE_KB = 6 * 1024;
    private static final long MAX_DISK_CACHE_BYTES = 16L * 1024L * 1024L;

    private final File diskCacheDir;
    private final ThemeStoreClient client;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memCache;

    public StoreThumbnailLoader(Context context, ThemeStoreClient client) {
        this.diskCacheDir = new File(context.getCacheDir(), "store_thumbs");
        if (!this.diskCacheDir.exists()) {
            this.diskCacheDir.mkdirs();
        }
        this.client = client;
        this.memCache = new LruCache<String, Bitmap>(MEM_CACHE_KB) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void loadRemoteThumbnail(
            final ImageView target,
            final String thumbnailUrl,
            final int reqW,
            final int reqH) {
        if (target == null) return;
        if (thumbnailUrl == null || thumbnailUrl.trim().isEmpty()) {
            target.setTag(null);
            target.setImageBitmap(null);
            return;
        }
        final String key = thumbnailUrl + "#" + reqW + "x" + reqH;
        target.setTag(key);

        Bitmap cached = memCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageBitmap(null);

        final WeakReference<ImageView> ref = new WeakReference<ImageView>(target);
        pool.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    File diskFile = getDiskFile(thumbnailUrl);
                    Bitmap bmp = null;
                    if (diskFile.isFile() && diskFile.length() > 8L) {
                        bmp = decodeSampledFile(diskFile, reqW, reqH);
                        if (bmp == null) {
                            diskFile.delete();
                        }
                    }
                    if (bmp == null) {
                        byte[] bytes = client.downloadThumbnailBytes(thumbnailUrl, ThemeStoreClient.MAX_THUMBNAIL_BYTES);
                        if (bytes != null && bytes.length > 8) {
                            bmp = decodeSampledBytes(bytes, reqW, reqH);
                            if (bmp != null) {
                                writeDiskSafe(diskFile, bytes);
                            }
                        }
                    }
                    if (bmp != null) {
                        memCache.put(key, bmp);
                        final Bitmap finalBmp = bmp;
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                ImageView iv = ref.get();
                                if (iv != null && key.equals(iv.getTag())) {
                                    iv.setImageBitmap(finalBmp);
                                }
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void loadLocalThumbnail(
            final ImageView target,
            final File localFile,
            final File zipFile,
            final byte[] fallbackBytes,
            final int reqW,
            final int reqH) {
        if (target == null) return;
        final boolean hasLocal = localFile != null && localFile.isFile();
        final boolean hasZip = !hasLocal && zipFile != null && zipFile.isFile();
        final boolean hasBytes = !hasLocal && !hasZip && fallbackBytes != null && fallbackBytes.length > 0;
        if (!hasLocal && !hasZip && !hasBytes) {
            target.setTag(null);
            target.setImageBitmap(null);
            return;
        }
        final String id = hasLocal
                ? ("local:" + localFile.getAbsolutePath() + "@" + localFile.lastModified() + "#" + reqW + "x" + reqH)
                : (hasZip
                        ? ("zip:" + zipFile.getAbsolutePath() + "@" + zipFile.lastModified() + "#" + reqW + "x" + reqH)
                        : ("bytes:" + Arrays.hashCode(fallbackBytes) + "#" + reqW + "x" + reqH));
        target.setTag(id);

        Bitmap cached = memCache.get(id);
        if (cached != null && !cached.isRecycled()) {
            target.setImageBitmap(cached);
            return;
        }
        target.setImageBitmap(null);

        final WeakReference<ImageView> ref = new WeakReference<ImageView>(target);
        pool.submit(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    if (hasLocal) {
                        bmp = decodeSampledFile(localFile, reqW, reqH);
                    } else if (hasZip) {
                        byte[] zb = SafeThemeZipExtractor.readZipThumbnailBytes(zipFile, 4 * 1024 * 1024);
                        if (zb != null && zb.length > 0) {
                            bmp = decodeSampledBytes(zb, reqW, reqH);
                        }
                    } else if (hasBytes) {
                        bmp = decodeSampledBytes(fallbackBytes, reqW, reqH);
                    }
                } catch (Throwable ignored) {
                }
                if (bmp != null) {
                    memCache.put(id, bmp);
                    final Bitmap finalBmp = bmp;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            ImageView iv = ref.get();
                            if (iv != null && id.equals(iv.getTag())) {
                                iv.setImageBitmap(finalBmp);
                            }
                        }
                    });
                }
            }
        });
    }

    public void shutdown() {
        pool.shutdownNow();
        memCache.evictAll();
    }

    private File getDiskFile(String url) {
        String hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < d.length; i++) {
                sb.append(Integer.toHexString((d[i] & 0xFF) | 0x100).substring(1));
            }
            hash = sb.toString();
        } catch (Throwable t) {
            hash = Integer.toHexString(url.hashCode());
        }
        return new File(diskCacheDir, hash + ".img");
    }

    private void writeDiskSafe(File target, byte[] data) {
        try {
            File tmp = new File(diskCacheDir, target.getName() + ".tmp");
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
            }
            trimDiskCacheIfNeeded();
        } catch (IOException ignored) {
        }
    }

    private void trimDiskCacheIfNeeded() {
        File[] files = diskCacheDir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0L;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK_CACHE_BYTES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : files) {
            long sz = f.length();
            if (f.delete()) {
                total -= sz;
            }
            if (total <= (MAX_DISK_CACHE_BYTES * 3L) / 4L) {
                break;
            }
        }
    }

    private static Bitmap decodeSampledFile(File f, int reqW, int reqH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
    }

    private static Bitmap decodeSampledBytes(byte[] bytes, int reqW, int reqH) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH);
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
    }

    private static int computeInSampleSize(int w, int h, int reqW, int reqH) {
        int inSample = 1;
        if (reqW > 0 && reqH > 0 && (h > reqH || w > reqW)) {
            int halfH = h / 2;
            int halfW = w / 2;
            while ((halfH / inSample) >= reqH && (halfW / inSample) >= reqW) {
                inSample *= 2;
            }
        }
        return Math.max(1, inSample);
    }
}
