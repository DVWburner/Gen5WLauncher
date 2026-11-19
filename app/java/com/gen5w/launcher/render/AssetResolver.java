package com.gen5w.launcher.render;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves icons and bitmaps from external ThemePack folders (USB or internal storage)
 * or bundled APK assets, dispatching .svg files through MiniSvgDrawable.
 */
public final class AssetResolver {
    private static final String DEFAULT_ASSET_BASE = "packs/default";

    private final Context context;
    private final String packBaseDir;
    private final Map<String, Drawable> drawableCache = new HashMap<String, Drawable>();
    private final Map<String, Bitmap> bitmapCache = new HashMap<String, Bitmap>();
    private final Map<String, Typeface> typefaceCache = new HashMap<String, Typeface>();

    public AssetResolver(Context context, String packBaseDir) {
        this.context = context.getApplicationContext();
        this.packBaseDir = packBaseDir == null ? "" : packBaseDir;
    }

    public Drawable loadDrawable(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return null;
        }
        String key = ref.trim();
        if (drawableCache.containsKey(key)) {
            Drawable cached = drawableCache.get(key);
            return cached == null || cached.getConstantState() == null
                    ? cached
                    : cached.getConstantState().newDrawable(context.getResources());
        }
        Drawable result = null;
        try {
            byte[] data = readAssetBytes(key);
            if (data != null && data.length > 0) {
                if (key.toLowerCase(Locale.US).endsWith(".svg")) {
                    result = MiniSvgDrawable.parse(new String(data, "UTF-8"));
                } else {
                    Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bmp != null) {
                        result = new BitmapDrawable(context.getResources(), bmp);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[Gen5WLauncher] failed loading drawable " + key + ": " + t);
        }
        drawableCache.put(key, result);
        return result;
    }

    public Bitmap loadBitmap(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return null;
        }
        String key = ref.trim();
        if (bitmapCache.containsKey(key)) {
            return bitmapCache.get(key);
        }
        Bitmap bmp = null;
        try {
            byte[] data = readAssetBytes(key);
            if (data != null && data.length > 0) {
                bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Throwable ignored) {
        }
        bitmapCache.put(key, bmp);
        return bmp;
    }

    public Typeface loadTypeface(String fontFamily, boolean bold, boolean italic) {
        int style = bold && italic ? Typeface.BOLD_ITALIC
                  : (bold ? Typeface.BOLD : (italic ? Typeface.ITALIC : Typeface.NORMAL));
        String fam = fontFamily == null ? "" : fontFamily.trim();
        if (fam.isEmpty() || "default".equalsIgnoreCase(fam)) {
            return Typeface.create(Typeface.SANS_SERIF, style);
        }
        String low = fam.toLowerCase(Locale.US);
        String cacheKey = fam + "|" + style;
        if (typefaceCache.containsKey(cacheKey)) {
            Typeface cached = typefaceCache.get(cacheKey);
            return cached != null ? cached : Typeface.create(Typeface.SANS_SERIF, style);
        }

        Typeface result = null;
        if ((low.endsWith(".ttf") || low.endsWith(".otf")) && !fam.contains("..")) {
            try {
                File ext = resolveExternalFile(fam);
                Typeface base = null;
                if (ext != null && ext.isFile()) {
                    base = Typeface.createFromFile(ext);
                } else {
                    String assetPath = fam.startsWith("packs/") ? fam : (DEFAULT_ASSET_BASE + "/" + fam);
                    base = Typeface.createFromAsset(context.getAssets(), assetPath);
                }
                if (base != null) {
                    result = style == Typeface.NORMAL ? base : Typeface.create(base, style);
                }
            } catch (Throwable ignored) {
            }
        }

        if (result == null) {
            if ("serif".equals(low) || "georgia".equals(low) || "times".equals(low)) {
                result = Typeface.create(Typeface.SERIF, style);
            } else if ("monospace".equals(low) || "consolas".equals(low) || "courier".equals(low)) {
                result = Typeface.create(Typeface.MONOSPACE, style);
            } else if (low.contains("condensed")) {
                result = Typeface.create("sans-serif-condensed", style);
            } else if (low.contains("thin")) {
                result = Typeface.create("sans-serif-thin", style);
            } else if (low.contains("light")) {
                result = Typeface.create("sans-serif-light", style);
            } else {
                result = Typeface.create(fam, style);
            }
        }
        typefaceCache.put(cacheKey, result);
        return result != null ? result : Typeface.create(Typeface.SANS_SERIF, style);
    }

    public String readTextAsset(String ref) throws IOException {
        byte[] data = readAssetBytes(ref);
        return data == null ? null : new String(data, "UTF-8");
    }

    public File resolveExternalFile(String ref) {
        if (ref == null || ref.trim().isEmpty() || ref.contains("..") || packBaseDir.isEmpty()) {
            return null;
        }
        File ext = new File(packBaseDir, ref.trim());
        return ext.isFile() ? ext : null;
    }

    public AssetFileDescriptor openAssetFd(String ref) throws IOException {
        if (ref == null || ref.trim().isEmpty() || ref.contains("..")) {
            return null;
        }
        String clean = ref.trim();
        String assetPath = clean.startsWith("packs/") ? clean : (DEFAULT_ASSET_BASE + "/" + clean);
        return context.getAssets().openFd(assetPath);
    }

    public InputStream openStream(String ref) throws IOException {
        if (ref == null || ref.trim().isEmpty() || ref.contains("..")) {
            return null;
        }
        String clean = ref.trim();
        if (!packBaseDir.isEmpty()) {
            File ext = new File(packBaseDir, clean);
            if (ext.isFile()) {
                return new FileInputStream(ext);
            }
        }
        String assetPath = clean.startsWith("packs/") ? clean : (DEFAULT_ASSET_BASE + "/" + clean);
        return context.getAssets().open(assetPath);
    }

    public List<String> listImageFrames(String dirRef) {
        List<String> out = new ArrayList<String>();
        if (dirRef == null || dirRef.trim().isEmpty() || dirRef.contains("..")) {
            return out;
        }
        String cleanDir = dirRef.trim().replaceAll("^/+|/+$", "");
        if (!packBaseDir.isEmpty()) {
            File extDir = new File(packBaseDir, cleanDir);
            if (extDir.isDirectory()) {
                String[] names = extDir.list();
                if (names != null) {
                    Arrays.sort(names);
                    for (String n : names) {
                        if (isFrameImage(n)) {
                            out.add(cleanDir + "/" + n);
                        }
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            }
        }
        try {
            String assetDir = cleanDir.startsWith("packs/") ? cleanDir : (DEFAULT_ASSET_BASE + "/" + cleanDir);
            String[] names = context.getAssets().list(assetDir);
            if (names != null) {
                Arrays.sort(names);
                for (String n : names) {
                    if (isFrameImage(n)) {
                        out.add(cleanDir + "/" + n);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(out);
        return out;
    }

    private static boolean isFrameImage(String name) {
        String low = name.toLowerCase(Locale.US);
        return low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".webp") || low.endsWith(".png");
    }

    public byte[] readAssetBytes(String ref) throws IOException {
        InputStream in = openStream(ref);
        return in == null ? null : readAll(in);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
