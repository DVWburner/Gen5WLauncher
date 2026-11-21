using System;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Gen5WStudio.Models;
using Gen5WStudio.Services;

namespace Gen5WStudio;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        if (e.Args.Any(a => a == "--self-test"))
        {
            ShutdownMode = ShutdownMode.OnExplicitShutdown;
            int rc = RunSelfTest();
            Shutdown(rc);
            return;
        }
        base.OnStartup(e);
        new MainWindow().Show();
    }

    private static int RunSelfTest()
    {
        try
        {
            string testRoot = Path.Combine(Path.GetTempPath(), "gen5w_studio_selftest_" + Guid.NewGuid().ToString("N")[..6]);
            Directory.CreateDirectory(testRoot);
            try
            {
                var ws = new PackWorkspaceService(Path.Combine(testRoot, "themes"));
                if (ws.HasActiveTheme || ws.ListActivePackThemes().Count != 0)
                    throw new Exception("PackWorkspaceService must start with an empty workspace and zero default themes.");

                var pack = ws.LoadRepoDefaultStarter();
                string json = pack.ToJsonString();
                if (json.Contains('\r')) throw new Exception("CR found in serialized ThemePack JSON");
                var roundTrip = ThemePackModel.FromJson(json);
                if (roundTrip.Root.Children == null || roundTrip.Root.Children.Count == 0)
                    throw new Exception("Round-trip root children empty");

                // Test MainWindow construction starts empty + Canvas rendering + Tree/NewTheme/Recent/Store/Auth checks
                string recentTestFile = Path.Combine(testRoot, "recent_workspaces.json");
                var win = new MainWindow(ws, recentTestFile);
                if (win.HasActiveTheme)
                    throw new Exception("MainWindow must start with no active theme loaded.");
                win.SelfTestTreeStateAndBlankPacks();
                win.Close();
                var newThemeDlg = new NewThemeDialog();
                newThemeDlg.Close();

                // Ensure a test workspace directory exists for SVG & font import checks after CloseActiveWorkspace
                var svgFontPack = PresetCatalog.CreateNewPack("svg-font-test", "SVG Font Test", "Test", "blank_grid", true);
                ws.CreateNewCleanPackWithTheme(svgFontPack);

                // Test all built-in SVGs
                foreach (string rel in PresetCatalog.BuiltInIcons)
                {
                    string p = ws.ResolveAssetFullPath(rel);
                    if (SvgPreviewRenderer.RenderSvgFile(p, Colors.Cyan) == null)
                        throw new Exception("SVG render failed for " + rel);
                }

                // Test GIF -> frames conversion cleanly using generated 4-frame GIF
                string testGif = Path.Combine(testRoot, "test.gif");
                var enc = new GifBitmapEncoder();
                for (int i = 0; i < 4; i++)
                {
                    var wb = new WriteableBitmap(64, 64, 96, 96, PixelFormats.Bgra32, null);
                    enc.Frames.Add(BitmapFrame.Create(wb));
                }
                using (var fs = File.Create(testGif)) enc.Save(fs);

                var res = MediaConverterService.ConvertAnimatedMediaToFramesAsync(
                    testGif, testRoot, "gif_test", 480, 180, 12, 2, 80).GetAwaiter().GetResult();
                if (res.FrameCount != 4 || !File.Exists(Path.Combine(testRoot, "anim", "gif_test", "frames.json")))
                    throw new Exception("GIF frame conversion failed");

                string testAvi = Path.Combine(testRoot, "mf_smoke.avi");
                WriteSyntheticUncompressedAvi(testAvi, 160, 90, 12, 12);
                var vRes = MediaConverterService.ConvertAnimatedMediaToFramesAsync(
                    testAvi, testRoot, "mf_test", 480, 180, 12, 2, 78).GetAwaiter().GetResult();
                if (vRes.FrameCount < 6) throw new Exception("MediaFoundation returned too few frames: " + vRes.FrameCount);

                // Test .ttf font import & GlyphTypeface resolution if a standard TTF exists
                string sysSegoe = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Fonts), "segoeui.ttf");
                string fontChecked = "skipped";
                if (File.Exists(sysSegoe))
                {
                    string importedFontRel = MediaConverterService.ImportFont(sysSegoe, ws.WorkspaceDir);
                    var resolvedFam = ws.ResolveFontFamily(importedFontRel);
                    if (resolvedFam == null) throw new Exception("Failed resolving imported TTF FontFamily");
                    fontChecked = importedFontRel;
                    string testCopy = Path.Combine(ws.WorkspaceDir, importedFontRel.Replace('/', Path.DirectorySeparatorChar));
                    if (File.Exists(testCopy)) File.Delete(testCopy);
                }

                string summary = $"OK  Gen5WStudio self-test passed (SVGs={PresetCatalog.BuiltInIcons.Count}, sensors={PresetCatalog.TelemetrySensors.Count(s => !s.IsHeader)}, font={fontChecked}, gifFrames={res.FrameCount}, mfFrames={vRes.FrameCount}, mfMethod={vRes.Method}, mfBytes={vRes.TotalBytes})";
                File.WriteAllText(Path.Combine(Path.GetTempPath(), "gen5w_studio_selftest.log"), summary + Environment.NewLine);
                Console.WriteLine(summary);
                return 0;
            }
            finally
            {
                Directory.Delete(testRoot, true);
            }
        }
        catch (Exception ex)
        {
            File.WriteAllText(Path.Combine(Path.GetTempPath(), "gen5w_studio_selftest.log"), "FAILED: " + ex);
            Console.Error.WriteLine("SELF-TEST FAILED: " + ex);
            return 1;
        }
    }

    private static void WriteSyntheticUncompressedAvi(string path, int w, int h, int fps, int frames)
    {
        int rowStride = (w * 3 + 3) & ~3;
        int imgSize = rowStride * h;
        int moviDataSize = 4 + frames * (8 + imgSize);
        int hdrlSize = 4 + (8 + 56) + (4 + 8 + (8 + 56) + (8 + 40));
        int riffSize = 4 + (8 + hdrlSize) + (8 + moviDataSize);

        using var bw = new BinaryWriter(File.Create(path));
        void Tag(string t) => bw.Write(System.Text.Encoding.ASCII.GetBytes(t));

        Tag("RIFF"); bw.Write(riffSize); Tag("AVI ");
        Tag("LIST"); bw.Write(hdrlSize); Tag("hdrl");
        Tag("avih"); bw.Write(56);
        bw.Write(1_000_000 / fps); bw.Write(imgSize * fps); bw.Write(0); bw.Write(0x10);
        bw.Write(frames); bw.Write(0); bw.Write(1); bw.Write(imgSize);
        bw.Write(w); bw.Write(h); bw.Write(0); bw.Write(0); bw.Write(0); bw.Write(0);

        int strlSize = 4 + (8 + 56) + (8 + 40);
        Tag("LIST"); bw.Write(strlSize); Tag("strl");
        Tag("strh"); bw.Write(56);
        Tag("vids"); Tag("DIB "); bw.Write(0); bw.Write((ushort)0); bw.Write((ushort)0); bw.Write(0);
        bw.Write(1); bw.Write(fps); bw.Write(0); bw.Write(frames); bw.Write(imgSize); bw.Write(-1); bw.Write(0);
        bw.Write((short)0); bw.Write((short)0); bw.Write((short)w); bw.Write((short)h);

        Tag("strf"); bw.Write(40);
        bw.Write(40); bw.Write(w); bw.Write(h); bw.Write((ushort)1); bw.Write((ushort)24);
        bw.Write(0); bw.Write(imgSize); bw.Write(0); bw.Write(0); bw.Write(0); bw.Write(0);

        Tag("LIST"); bw.Write(moviDataSize); Tag("movi");
        byte[] frameBuf = new byte[imgSize];
        for (int f = 0; f < frames; f++)
        {
            for (int y = 0; y < h; y++)
            {
                for (int x = 0; x < w; x++)
                {
                    int off = y * rowStride + x * 3;
                    frameBuf[off] = (byte)((f * 20) & 255);
                    frameBuf[off + 1] = (byte)((y * 2) & 255);
                    frameBuf[off + 2] = (byte)((x * 2) & 255);
                }
            }
            Tag("00dc"); bw.Write(imgSize); bw.Write(frameBuf);
        }
    }
}
