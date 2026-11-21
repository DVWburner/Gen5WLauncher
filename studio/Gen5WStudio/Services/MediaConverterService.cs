using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Xml.Linq;

namespace Gen5WStudio.Services;

public sealed record ConversionResult(string RelativeRef, int FrameCount, int Fps, int Width, int Height, long TotalBytes, string Method);

/// <summary>
/// Zero-dependency Windows media converter preparing external SVGs, static wallpapers, GIFs,
/// and video files (.mp4/.mov/.wmv/.m4v/.avi/.webm) into KitKat-compatible ThemePack v2 assets.
/// Decodes videos natively via Windows Media Foundation IMFSourceReader (with optional ffmpeg fallback).
/// </summary>
public sealed class MediaConverterService
{
    public static string ImportSvg(string srcFilePath, string workspaceDir)
    {
        byte[] raw = File.ReadAllBytes(srcFilePath);
        byte[] clean = ThemePackageService.PrepareAssetBytesForPackage("icons/import.svg", raw);
        if (!ThemePackageService.ValidateFileContentSafety("icons/import.svg", clean, out string svgErr))
            throw new InvalidOperationException("Selected file is not a valid safe SVG document: " + svgErr);

        string iconsDir = Path.Combine(workspaceDir, "icons");
        Directory.CreateDirectory(iconsDir);
        string safeName = PackWorkspaceService.SanitizeId(Path.GetFileNameWithoutExtension(srcFilePath)) + ".svg";
        File.WriteAllBytes(Path.Combine(iconsDir, safeName), clean);
        return "icons/" + safeName;
    }

    public static string ImportStaticBitmap(string srcFilePath, string workspaceDir, int targetW, int targetH, int jpegQuality = 88)
    {
        string mediaDir = Path.Combine(workspaceDir, "media");
        Directory.CreateDirectory(mediaDir);
        var decoder = BitmapDecoder.Create(new Uri(srcFilePath), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        BitmapSource src = decoder.Frames[0];
        src.Freeze();

        string safeBase = PackWorkspaceService.SanitizeId(Path.GetFileNameWithoutExtension(srcFilePath));
        string outFile = Path.Combine(mediaDir, safeBase + ".jpg");
        CropAndEncodeJpeg(src, targetW, targetH, jpegQuality, outFile);
        return "media/" + safeBase + ".jpg";
    }

    public static string ImportElementImage(string srcFilePath, string workspaceDir, int maxDim = 960)
    {
        string imgDir = Path.Combine(workspaceDir, "img");
        Directory.CreateDirectory(imgDir);
        string ext = Path.GetExtension(srcFilePath).ToLowerInvariant();
        string safeBase = PackWorkspaceService.SanitizeId(Path.GetFileNameWithoutExtension(srcFilePath));

        var decoder = BitmapDecoder.Create(new Uri(srcFilePath), BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        BitmapSource src = decoder.Frames[0];
        if (src.PixelWidth > maxDim || src.PixelHeight > maxDim)
        {
            double scale = Math.Min(maxDim / (double)src.PixelWidth, maxDim / (double)src.PixelHeight);
            src = new TransformedBitmap(src, new ScaleTransform(scale, scale));
        }
        src.Freeze();

        bool keepPng = ext is ".png" or ".webp" or ".gif" or ".bmp";
        string outExt = keepPng ? ".png" : ".jpg";
        string outPath = Path.Combine(imgDir, safeBase + outExt);
        BitmapEncoder encoder = keepPng ? new PngBitmapEncoder() : new JpegBitmapEncoder { QualityLevel = 90 };
        encoder.Frames.Add(BitmapFrame.Create(src));
        using var fs = File.Create(outPath);
        encoder.Save(fs);
        return "img/" + safeBase + outExt;
    }

    public static string ImportFont(string srcFilePath, string workspaceDir)
    {
        string ext = Path.GetExtension(srcFilePath).ToLowerInvariant();
        if (ext is not (".ttf" or ".otf"))
            throw new InvalidOperationException("Please select a .ttf or .otf font file.");
        byte[] fontBytes = File.ReadAllBytes(srcFilePath);
        string hash = ThemePackageService.ComputeSha256Hex(fontBytes)[..16];
        string tempFontDir = Path.Combine(Path.GetTempPath(), "Gen5WStudioFontCache", hash);
        Directory.CreateDirectory(tempFontDir);
        string probePath = Path.Combine(tempFontDir, Path.GetFileName(srcFilePath));
        if (!File.Exists(probePath))
        {
            File.WriteAllBytes(probePath, fontBytes);
        }
        // Validate readable font from temp cache copy so WPF never locks srcFilePath or workspace font
        _ = new GlyphTypeface(new Uri(probePath));
        string fontsDir = Path.Combine(workspaceDir, "fonts");
        Directory.CreateDirectory(fontsDir);
        string safeName = PackWorkspaceService.SanitizeId(Path.GetFileNameWithoutExtension(srcFilePath)) + ext;
        File.WriteAllBytes(Path.Combine(fontsDir, safeName), fontBytes);
        return "fonts/" + safeName;
    }

    public static Task<ConversionResult> ConvertAnimatedMediaToFramesAsync(
        string srcPath,
        string workspaceDir,
        string slotName,
        int targetW,
        int targetH,
        int targetFps,
        int maxSeconds,
        int jpegQuality,
        IProgress<string>? progress = null)
    {
        return Task.Run(() =>
        {
            string ext = Path.GetExtension(srcPath).ToLowerInvariant();
            string safeSlot = PackWorkspaceService.SanitizeId(slotName);
            string relDir = "anim/" + safeSlot;
            string outDir = Path.Combine(workspaceDir, "anim", safeSlot);
            if (Directory.Exists(outDir)) Directory.Delete(outDir, recursive: true);
            Directory.CreateDirectory(outDir);

            if (ext == ".gif")
            {
                return ConvertGif(srcPath, outDir, relDir, targetW, targetH, targetFps, maxSeconds, jpegQuality, progress);
            }

            try
            {
                return ConvertVideoWithMediaFoundation(srcPath, outDir, relDir, targetW, targetH, targetFps, maxSeconds, jpegQuality, progress);
            }
            catch (Exception mfEx)
            {
                string? ffmpeg = FindOnPath("ffmpeg.exe");
                if (ffmpeg != null)
                {
                    progress?.Report($"Media Foundation fallback ({mfEx.Message}) -> trying ffmpeg...");
                    return ConvertWithFfmpeg(ffmpeg, srcPath, outDir, relDir, targetW, targetH, targetFps, maxSeconds, jpegQuality);
                }
                throw new InvalidOperationException(
                    $"Unable to decode '{Path.GetFileName(srcPath)}' via Windows Media Foundation ({mfEx.Message}). " +
                    "Convert to standard H.264 .mp4/.mov/.gif or place ffmpeg.exe on PATH.", mfEx);
            }
        });
    }

    private static ConversionResult ConvertGif(
        string srcPath, string outDir, string relDir,
        int targetW, int targetH, int targetFps, int maxSeconds, int jpegQuality, IProgress<string>? progress)
    {
        using var fs = File.OpenRead(srcPath);
        var decoder = new GifBitmapDecoder(fs, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
        int maxFrames = Math.Max(1, targetFps * maxSeconds);
        int totalGif = decoder.Frames.Count;
        var frameNames = new List<string>();
        int step = Math.Max(1, totalGif / maxFrames);

        int outIdx = 0;
        for (int i = 0; i < totalGif && frameNames.Count < maxFrames; i += step)
        {
            outIdx++;
            string fname = $"f_{outIdx:D4}.jpg";
            CropAndEncodeJpeg(decoder.Frames[i], targetW, targetH, jpegQuality, Path.Combine(outDir, fname));
            frameNames.Add(fname);
            if (outIdx % 5 == 0) progress?.Report($"Extracting GIF frames: {outIdx}/{Math.Min(maxFrames, totalGif)}...");
        }
        return FinalizeFramesManifest(outDir, relDir, frameNames, targetFps, targetW, targetH, "GIF Decoder");
    }

    private static unsafe ConversionResult ConvertVideoWithMediaFoundation(
        string srcPath, string outDir, string relDir,
        int targetW, int targetH, int targetFps, int maxSeconds, int jpegQuality, IProgress<string>? progress)
    {
        CheckHr(NativeMf.MFStartup(NativeMf.MF_VERSION, 0), "MFStartup");
        IntPtr attrs = IntPtr.Zero;
        IntPtr reader = IntPtr.Zero;
        IntPtr reqType = IntPtr.Zero;
        IntPtr curType = IntPtr.Zero;

        try
        {
            CheckHr(NativeMf.MFCreateAttributes(out attrs, 1), "MFCreateAttributes");
            Guid enableProc = NativeMf.MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING;
            CheckHr(NativeMf.SetUINT32(attrs, &enableProc, 1), "EnableVideoProcessing");
            CheckHr(NativeMf.MFCreateSourceReaderFromURL(srcPath, attrs, out reader), "MFCreateSourceReaderFromURL");

            CheckHr(NativeMf.MFCreateMediaType(out reqType), "MFCreateMediaType");
            Guid majorKey = NativeMf.MF_MT_MAJOR_TYPE;
            Guid majorVideo = NativeMf.MFMediaType_Video;
            Guid subKey = NativeMf.MF_MT_SUBTYPE;
            Guid subRgb32 = NativeMf.MFVideoFormat_RGB32;
            CheckHr(NativeMf.SetGUID(reqType, &majorKey, &majorVideo), "SetMajorVideo");
            CheckHr(NativeMf.SetGUID(reqType, &subKey, &subRgb32), "SetSubtypeRGB32");
            CheckHr(NativeMf.ReaderSetCurrentMediaType(reader, NativeMf.MF_SOURCE_READER_FIRST_VIDEO_STREAM, IntPtr.Zero, reqType),
                "SetCurrentMediaType(RGB32)");

            CheckHr(NativeMf.ReaderGetCurrentMediaType(reader, NativeMf.MF_SOURCE_READER_FIRST_VIDEO_STREAM, out curType),
                "GetCurrentMediaType");

            Guid frameSizeKey = NativeMf.MF_MT_FRAME_SIZE;
            ulong packedSize = 0;
            CheckHr(NativeMf.GetUINT64(curType, &frameSizeKey, out packedSize), "GetFrameSize");
            int srcW = (int)(packedSize >> 32);
            int srcH = (int)(packedSize & 0xFFFFFFFF);
            if (srcW <= 0 || srcH <= 0) throw new InvalidOperationException("Invalid video frame size.");

            int stride = srcW * 4;
            Guid strideKey = NativeMf.MF_MT_DEFAULT_STRIDE;
            if (NativeMf.GetUINT32(curType, &strideKey, out uint rawStride) == 0 && rawStride != 0)
            {
                stride = unchecked((int)rawStride);
            }

            long intervalHns = 10_000_000L / Math.Max(1, targetFps);
            long maxHns = Math.Max(1, maxSeconds) * 10_000_000L;
            long nextTargetHns = 0L;
            int maxFrames = Math.Max(1, targetFps * maxSeconds);
            var frameNames = new List<string>();
            int absStride = Math.Abs(stride);
            byte[] pixelBuf = new byte[srcH * srcW * 4];

            while (frameNames.Count < maxFrames)
            {
                IntPtr sample = IntPtr.Zero;
                int hr = NativeMf.ReaderReadSample(
                    reader,
                    NativeMf.MF_SOURCE_READER_FIRST_VIDEO_STREAM,
                    0,
                    out _,
                    out uint flags,
                    out long timestampHns,
                    out sample);
                if (hr < 0) CheckHr(hr, "ReadSample");

                try
                {
                    if ((flags & NativeMf.MF_SOURCE_READERF_ENDOFSTREAM) != 0)
                        break;
                    if (sample == IntPtr.Zero)
                        continue;
                    if (timestampHns < nextTargetHns)
                        continue;
                    if (timestampHns > maxHns && frameNames.Count > 0)
                        break;

                    nextTargetHns = timestampHns + intervalHns;

                    IntPtr buffer = IntPtr.Zero;
                    CheckHr(NativeMf.SampleConvertToContiguousBuffer(sample, out buffer), "ConvertToContiguousBuffer");
                    try
                    {
                        CheckHr(NativeMf.BufferLock(buffer, out byte* lockedPtr, out _, out uint curLen), "BufferLock");
                        try
                        {
                            int copyStride = srcW * 4;
                            for (int y = 0; y < srcH; y++)
                            {
                                int srcRow = stride >= 0 ? y : (srcH - 1 - y);
                                int rowOffset = srcRow * absStride;
                                if (rowOffset + copyStride <= curLen)
                                {
                                    Marshal.Copy((IntPtr)(lockedPtr + rowOffset), pixelBuf, y * copyStride, copyStride);
                                }
                            }
                            // Force opaque alpha byte so RGB32 XRGB never becomes transparent
                            for (int p = 3; p < pixelBuf.Length; p += 4) pixelBuf[p] = 255;

                            var bmp = BitmapSource.Create(srcW, srcH, 96, 96, PixelFormats.Bgr32, null, pixelBuf, copyStride);
                            bmp.Freeze();
                            string fname = $"f_{frameNames.Count + 1:D4}.jpg";
                            CropAndEncodeJpeg(bmp, targetW, targetH, jpegQuality, Path.Combine(outDir, fname));
                            frameNames.Add(fname);
                            if (frameNames.Count % 4 == 0)
                            {
                                progress?.Report($"Converting video via Media Foundation: {frameNames.Count} / ~{maxFrames} frames...");
                            }
                        }
                        finally
                        {
                            NativeMf.BufferUnlock(buffer);
                        }
                    }
                    finally
                    {
                        NativeMf.Release(buffer);
                    }
                }
                finally
                {
                    if (sample != IntPtr.Zero) NativeMf.Release(sample);
                }
            }

            if (frameNames.Count == 0) throw new InvalidOperationException("No video frames decoded.");
            return FinalizeFramesManifest(outDir, relDir, frameNames, targetFps, targetW, targetH, "Windows Media Foundation");
        }
        finally
        {
            if (curType != IntPtr.Zero) NativeMf.Release(curType);
            if (reqType != IntPtr.Zero) NativeMf.Release(reqType);
            if (reader != IntPtr.Zero) NativeMf.Release(reader);
            if (attrs != IntPtr.Zero) NativeMf.Release(attrs);
            NativeMf.MFShutdown();
        }
    }

    private static ConversionResult ConvertWithFfmpeg(
        string ffmpegExe, string srcPath, string outDir, string relDir,
        int targetW, int targetH, int targetFps, int maxSeconds, int jpegQuality)
    {
        int q = Math.Max(2, Math.Min(12, (100 - jpegQuality) / 8 + 2));
        string pattern = Path.Combine(outDir, "f_%04d.jpg");
        var psi = new ProcessStartInfo(ffmpegExe)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true
        };
        psi.ArgumentList.Add("-y");
        psi.ArgumentList.Add("-i");
        psi.ArgumentList.Add(srcPath);
        psi.ArgumentList.Add("-t");
        psi.ArgumentList.Add(maxSeconds.ToString());
        psi.ArgumentList.Add("-an");
        psi.ArgumentList.Add("-vf");
        psi.ArgumentList.Add($"fps={targetFps},scale={targetW}:{targetH}:force_original_aspect_ratio=increase,crop={targetW}:{targetH}");
        psi.ArgumentList.Add("-q:v");
        psi.ArgumentList.Add(q.ToString());
        psi.ArgumentList.Add(pattern);

        using var proc = Process.Start(psi) ?? throw new InvalidOperationException("Failed starting ffmpeg");
        string stderr = proc.StandardError.ReadToEnd();
        proc.WaitForExit();
        if (proc.ExitCode != 0) throw new InvalidOperationException($"ffmpeg exited {proc.ExitCode}: {stderr}");

        var files = new List<string>();
        foreach (string f in Directory.GetFiles(outDir, "f_*.jpg")) files.Add(Path.GetFileName(f));
        files.Sort(StringComparer.OrdinalIgnoreCase);
        return FinalizeFramesManifest(outDir, relDir, files, targetFps, targetW, targetH, "FFmpeg");
    }

    private static ConversionResult FinalizeFramesManifest(
        string outDir, string relDir, List<string> frameNames, int fps, int width, int height, string method)
    {
        var manifest = JsonSerializer.Serialize(new
        {
            fps,
            loop = true,
            frames = frameNames
        }, new JsonSerializerOptions { WriteIndented = true }).Replace("\r\n", "\n") + "\n";
        File.WriteAllText(Path.Combine(outDir, "frames.json"), manifest, new UTF8Encoding(false));

        long totalBytes = 0;
        foreach (var f in Directory.GetFiles(outDir)) totalBytes += new FileInfo(f).Length;
        return new ConversionResult(relDir, frameNames.Count, fps, width, height, totalBytes, method);
    }

    private static void CropAndEncodeJpeg(BitmapSource src, int outW, int outH, int quality, string outPath)
    {
        double scale = Math.Max((double)outW / Math.Max(1, src.PixelWidth), (double)outH / Math.Max(1, src.PixelHeight));
        double drawW = src.PixelWidth * scale;
        double drawH = src.PixelHeight * scale;
        double dx = (outW - drawW) * 0.5;
        double dy = (outH - drawH) * 0.5;

        var dv = new DrawingVisual();
        using (DrawingContext dc = dv.RenderOpen())
        {
            dc.DrawRectangle(Brushes.Black, null, new Rect(0, 0, outW, outH));
            dc.DrawImage(src, new Rect(dx, dy, drawW, drawH));
        }
        var rtb = new RenderTargetBitmap(outW, outH, 96, 96, PixelFormats.Pbgra32);
        rtb.Render(dv);

        var encoder = new JpegBitmapEncoder { QualityLevel = Math.Max(45, Math.Min(95, quality)) };
        encoder.Frames.Add(BitmapFrame.Create(rtb));
        using var fs = File.Create(outPath);
        encoder.Save(fs);
    }

    private static string? FindOnPath(string exeName)
    {
        string local = Path.Combine(AppContext.BaseDirectory, exeName);
        if (File.Exists(local)) return local;
        string? pathVar = Environment.GetEnvironmentVariable("PATH");
        if (string.IsNullOrEmpty(pathVar)) return null;
        foreach (string dir in pathVar.Split(Path.PathSeparator))
        {
            if (string.IsNullOrWhiteSpace(dir)) continue;
            string p = Path.Combine(dir.Trim(), exeName);
            if (File.Exists(p)) return p;
        }
        return null;
    }

    private static void CheckHr(int hr, string step)
    {
        if (hr < 0)
        {
            throw new ExternalException($"{step} failed with HRESULT 0x{hr:X8}", hr);
        }
    }

    private static unsafe class NativeMf
    {
        public const uint MF_VERSION = 0x00020070;
        public const uint MF_SOURCE_READER_FIRST_VIDEO_STREAM = 0xFFFFFFFC;
        public const uint MF_SOURCE_READERF_ENDOFSTREAM = 0x2;

        // Verified against Windows SDK mfapi.h / mfreadwrite.h / d3d9types.h
        public static readonly Guid MFMediaType_Video = new(0x73646976, 0x0000, 0x0010, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71);
        public static readonly Guid MFVideoFormat_RGB32 = new(22, 0x0000, 0x0010, 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71);
        public static readonly Guid MF_MT_MAJOR_TYPE = new(0x48eba18e, 0xf8c9, 0x4687, 0xbf, 0x11, 0x0a, 0x74, 0xc9, 0xf9, 0x6a, 0x8f);
        public static readonly Guid MF_MT_SUBTYPE = new(0xf7e34c9a, 0x42e8, 0x4714, 0xb7, 0x4b, 0xcb, 0x29, 0xd7, 0x2c, 0x35, 0xe5);
        public static readonly Guid MF_MT_FRAME_SIZE = new(0x1652c33d, 0xd6b2, 0x4012, 0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, 0xa3, 0x7d);
        public static readonly Guid MF_MT_DEFAULT_STRIDE = new(0x644b4e48, 0x1e02, 0x4516, 0xb0, 0xeb, 0xc0, 0x1c, 0xa9, 0xd4, 0x9a, 0xc6);
        public static readonly Guid MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING = new(0xfb394f3d, 0xccf1, 0x42ee, 0xbb, 0xb3, 0xf9, 0xb8, 0x45, 0xd5, 0x68, 0x1d);

        [DllImport("mfplat.dll")]
        public static extern int MFStartup(uint version, uint dwFlags);

        [DllImport("mfplat.dll")]
        public static extern int MFShutdown();

        [DllImport("mfplat.dll")]
        public static extern int MFCreateAttributes(out IntPtr ppMFAttributes, uint cInitialSize);

        [DllImport("mfplat.dll")]
        public static extern int MFCreateMediaType(out IntPtr ppMFType);

        [DllImport("mfreadwrite.dll", CharSet = CharSet.Unicode)]
        public static extern int MFCreateSourceReaderFromURL(string pwszURL, IntPtr pAttributes, out IntPtr ppSourceReader);

        private static IntPtr Vtbl(IntPtr obj, int slot) => *(*(IntPtr**)obj + slot);

        public static uint Release(IntPtr obj)
        {
            if (obj == IntPtr.Zero) return 0;
            return ((delegate* unmanaged[Stdcall]<IntPtr, uint>)Vtbl(obj, 2))(obj);
        }

        public static int GetUINT32(IntPtr attrs, Guid* key, out uint val)
        {
            uint v = 0;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, Guid*, uint*, int>)Vtbl(attrs, 7))(attrs, key, &v);
            val = v;
            return hr;
        }

        public static int GetUINT64(IntPtr attrs, Guid* key, out ulong val)
        {
            ulong v = 0;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, Guid*, ulong*, int>)Vtbl(attrs, 8))(attrs, key, &v);
            val = v;
            return hr;
        }

        public static int SetUINT32(IntPtr attrs, Guid* key, uint val) =>
            ((delegate* unmanaged[Stdcall]<IntPtr, Guid*, uint, int>)Vtbl(attrs, 21))(attrs, key, val);

        public static int SetGUID(IntPtr attrs, Guid* key, Guid* val) =>
            ((delegate* unmanaged[Stdcall]<IntPtr, Guid*, Guid*, int>)Vtbl(attrs, 24))(attrs, key, val);

        public static int ReaderGetCurrentMediaType(IntPtr reader, uint streamIdx, out IntPtr pType)
        {
            IntPtr t = IntPtr.Zero;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr*, int>)Vtbl(reader, 6))(reader, streamIdx, &t);
            pType = t;
            return hr;
        }

        public static int ReaderSetCurrentMediaType(IntPtr reader, uint streamIdx, IntPtr reserved, IntPtr pType) =>
            ((delegate* unmanaged[Stdcall]<IntPtr, uint, IntPtr, IntPtr, int>)Vtbl(reader, 7))(reader, streamIdx, reserved, pType);

        public static int ReaderReadSample(IntPtr reader, uint streamIdx, uint flags,
            out uint actualStream, out uint streamFlags, out long timestamp, out IntPtr pSample)
        {
            uint a = 0, sf = 0;
            long ts = 0;
            IntPtr s = IntPtr.Zero;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, uint, uint, uint*, uint*, long*, IntPtr*, int>)Vtbl(reader, 9))(
                reader, streamIdx, flags, &a, &sf, &ts, &s);
            actualStream = a; streamFlags = sf; timestamp = ts; pSample = s;
            return hr;
        }

        public static int SampleConvertToContiguousBuffer(IntPtr sample, out IntPtr pBuffer)
        {
            IntPtr b = IntPtr.Zero;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, IntPtr*, int>)Vtbl(sample, 41))(sample, &b);
            pBuffer = b;
            return hr;
        }

        public static int BufferLock(IntPtr buffer, out byte* pbBuffer, out uint maxLen, out uint curLen)
        {
            byte* p = null;
            uint m = 0, c = 0;
            int hr = ((delegate* unmanaged[Stdcall]<IntPtr, byte**, uint*, uint*, int>)Vtbl(buffer, 3))(buffer, &p, &m, &c);
            pbBuffer = p; maxLen = m; curLen = c;
            return hr;
        }

        public static int BufferUnlock(IntPtr buffer) =>
            ((delegate* unmanaged[Stdcall]<IntPtr, int>)Vtbl(buffer, 4))(buffer);
    }
}
