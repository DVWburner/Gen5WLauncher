using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Windows.Media;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

public sealed record ThemeLibraryEntry(string Id, string Name, string Directory, bool IsBuiltInStarter)
{
    public override string ToString() => $"{Name} ({Id})";
}

public sealed class PackWorkspaceService
{
    public string PacksRootDir { get; }
    public string ActivePackDir { get; private set; } = "";
    public bool IsSingleThemeMode { get; private set; }
    public string WorkspaceDir { get; private set; } = "";
    public string? ExternalLinkedDir { get; set; }

    public bool HasActiveTheme =>
        !string.IsNullOrWhiteSpace(WorkspaceDir)
        && File.Exists(Path.Combine(WorkspaceDir, "layout.json"));

    public bool HasActivePack =>
        !string.IsNullOrWhiteSpace(ActivePackDir)
        && Directory.Exists(ActivePackDir);

    public PackWorkspaceService(string? customAppDataRoot = null)
    {
        string baseRoot = string.IsNullOrWhiteSpace(customAppDataRoot)
            ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Gen5WStudio")
            : customAppDataRoot;

        PacksRootDir = Path.Combine(baseRoot, "packs");
        Directory.CreateDirectory(PacksRootDir);
    }

    public void CloseWorkspace()
    {
        ActivePackDir = "";
        WorkspaceDir = "";
        IsSingleThemeMode = false;
        ExternalLinkedDir = null;
        fontCache.Clear();
    }

    public string? LocateDefaultPackDir()
    {
        string? cursor = AppContext.BaseDirectory;
        for (int i = 0; i < 6 && !string.IsNullOrEmpty(cursor); i++)
        {
            string candidate = Path.Combine(cursor, "app", "assets", "packs", "default");
            if (Directory.Exists(candidate) && File.Exists(Path.Combine(candidate, "layout.json")))
            {
                return candidate;
            }
            cursor = Directory.GetParent(cursor)?.FullName;
        }
        return EnsureEmbeddedDefaultPackExtracted();
    }

    private string? EnsureEmbeddedDefaultPackExtracted()
    {
        try
        {
            const string prefix = "Gen5WStudio.Assets.DefaultPack.";
            var asm = Assembly.GetExecutingAssembly();
            string[] names = asm.GetManifestResourceNames()
                .Where(n => n.StartsWith(prefix, StringComparison.Ordinal))
                .ToArray();
            if (names.Length == 0) return null;

            string appDataRoot = Path.GetDirectoryName(PacksRootDir) ?? PacksRootDir;
            string cacheDir = Path.Combine(appDataRoot, "bundled_default");
            Directory.CreateDirectory(cacheDir);

            foreach (string resName in names)
            {
                string tail = resName.Substring(prefix.Length);
                string destPath;
                if (tail.StartsWith("icons.", StringComparison.Ordinal))
                {
                    destPath = Path.Combine(cacheDir, "icons", tail.Substring("icons.".Length));
                }
                else if (tail.StartsWith("anim.wave.", StringComparison.Ordinal))
                {
                    destPath = Path.Combine(cacheDir, "anim", "wave", tail.Substring("anim.wave.".Length));
                }
                else
                {
                    destPath = Path.Combine(cacheDir, tail);
                }
                string? parent = Path.GetDirectoryName(destPath);
                if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);
                using Stream? src = asm.GetManifestResourceStream(resName);
                if (src == null) continue;
                if (!File.Exists(destPath) || new FileInfo(destPath).Length != src.Length)
                {
                    using var dst = File.Create(destPath);
                    src.CopyTo(dst);
                }
            }
            return File.Exists(Path.Combine(cacheDir, "layout.json")) ? cacheDir : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Lists ONLY the themes present inside the currently open Pack (or single theme folder).
    /// Returns an empty list when no workspace is open.
    /// </summary>
    public List<ThemeLibraryEntry> ListActivePackThemes()
    {
        var list = new List<ThemeLibraryEntry>();
        if (!HasActivePack) return list;

        if (IsSingleThemeMode)
        {
            var entry = TryReadThemeEntry(ActivePackDir, isBuiltIn: false);
            if (entry != null) list.Add(entry);
            return list;
        }

        foreach (string dir in Directory.GetDirectories(ActivePackDir))
        {
            var entry = TryReadThemeEntry(dir, isBuiltIn: false);
            if (entry != null) list.Add(entry);
        }

        return list
            .OrderBy(e => e.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static ThemeLibraryEntry? TryReadThemeEntry(string dir, bool isBuiltIn)
    {
        string layoutPath = Path.Combine(dir, "layout.json");
        if (!File.Exists(layoutPath)) return null;
        string folderId = Path.GetFileName(dir.TrimEnd('\\', '/'));
        try
        {
            var pack = ThemePackModel.FromJson(File.ReadAllText(layoutPath));
            string id = string.IsNullOrWhiteSpace(pack.Id) ? folderId : pack.Id;
            string name = string.IsNullOrWhiteSpace(pack.Name) ? folderId : pack.Name;
            return new ThemeLibraryEntry(id, name, dir, isBuiltIn);
        }
        catch
        {
            return null;
        }
    }

    public ThemeLibraryEntry ImportSingleThemeZipIntoCurrentPack(byte[] zipBytes, string? expectedSha256 = null)
        => ImportSingleThemeZipInternal(zipBytes, expectedSha256, switchActiveWorkspace: true);

    public ThemeLibraryEntry StageSingleThemeZipIntoCurrentPack(byte[] zipBytes, string? expectedSha256 = null)
        => ImportSingleThemeZipInternal(zipBytes, expectedSha256, switchActiveWorkspace: false);

    private ThemeLibraryEntry ImportSingleThemeZipInternal(byte[] zipBytes, string? expectedSha256, bool switchActiveWorkspace)
    {
        string tempDir = Path.Combine(Path.GetTempPath(), "gen5w_zip_" + Guid.NewGuid().ToString("N")[..10]);
        try
        {
            ThemePackageService.ExtractSingleThemeZipSafely(zipBytes, tempDir, expectedSha256);
            return switchActiveWorkspace
                ? ImportThemeFolderIntoCurrentPack(tempDir)
                : StageThemeFolderIntoCurrentPack(tempDir);
        }
        finally
        {
            if (Directory.Exists(tempDir))
            {
                try { Directory.Delete(tempDir, recursive: true); } catch { }
            }
        }
    }

    public ThemePackModel SwitchToThemeDirectory(string dir)
    {
        string layoutPath = Path.Combine(dir, "layout.json");
        if (!File.Exists(layoutPath))
            throw new FileNotFoundException("layout.json not found in selected theme folder", layoutPath);

        WorkspaceDir = dir;
        fontCache.Clear();
        SeedDefaultIconsIfMissing(WorkspaceDir);
        return ThemePackModel.FromJson(File.ReadAllText(layoutPath));
    }

    public ThemeLibraryEntry CreateNewCleanPackWithTheme(ThemePackModel pack)
    {
        pack.Normalize();
        string safeId = SanitizeId(pack.Id);
        string packName = safeId;
        string packContainer = Path.Combine(PacksRootDir, packName, "packs");
        int n = 2;
        while (Directory.Exists(Path.Combine(PacksRootDir, packName)))
        {
            packName = $"{safeId}-{n++}";
            packContainer = Path.Combine(PacksRootDir, packName, "packs");
        }
        Directory.CreateDirectory(packContainer);
        ActivePackDir = packContainer;
        IsSingleThemeMode = false;
        ExternalLinkedDir = Path.Combine(PacksRootDir, packName);
        return AddThemeToCurrentPack(pack);
    }

    public ThemeLibraryEntry AddThemeToCurrentPack(ThemePackModel pack)
    {
        pack.Normalize();
        if (!HasActivePack || IsSingleThemeMode)
        {
            return CreateNewCleanPackWithTheme(pack);
        }

        Directory.CreateDirectory(ActivePackDir);
        string baseId = SanitizeId(pack.Id);
        string candidateId = baseId;
        string targetDir = Path.Combine(ActivePackDir, candidateId);
        int suffix = 2;
        while (Directory.Exists(targetDir) && File.Exists(Path.Combine(targetDir, "layout.json")))
        {
            candidateId = $"{baseId}-{suffix++}";
            targetDir = Path.Combine(ActivePackDir, candidateId);
        }
        pack.Id = candidateId;

        if (Directory.Exists(targetDir)) Directory.Delete(targetDir, recursive: true);
        Directory.CreateDirectory(targetDir);
        SeedDefaultIconsIfMissing(targetDir);
        File.WriteAllText(Path.Combine(targetDir, "layout.json"), pack.ToJsonString(), new System.Text.UTF8Encoding(false));

        WorkspaceDir = targetDir;
        fontCache.Clear();
        return new ThemeLibraryEntry(pack.Id, pack.Name, targetDir, IsBuiltInStarter: false);
    }

    public ThemeLibraryEntry ImportThemeFolderIntoCurrentPack(string srcThemeDir)
        => ImportThemeFolderInternal(srcThemeDir, switchActiveWorkspace: true);

    /// <summary>
    /// Stages another theme into the current pack WITHOUT switching <see cref="WorkspaceDir"/> away
    /// from the editor's currently open theme (preventing SaveCurrentTheme from overwriting the import).
    /// </summary>
    public ThemeLibraryEntry StageThemeFolderIntoCurrentPack(string srcThemeDir)
        => ImportThemeFolderInternal(srcThemeDir, switchActiveWorkspace: false);

    private ThemeLibraryEntry ImportThemeFolderInternal(string srcThemeDir, bool switchActiveWorkspace)
    {
        var parsed = ThemePackModel.FromJson(File.ReadAllText(Path.Combine(srcThemeDir, "layout.json")));
        if (!HasActivePack)
        {
            CreateNewCleanPackWithTheme(parsed);
            CopyDirectory(srcThemeDir, WorkspaceDir, overwrite: true);
            SeedDefaultIconsIfMissing(WorkspaceDir);
            File.WriteAllText(Path.Combine(WorkspaceDir, "layout.json"), parsed.ToJsonString(), new System.Text.UTF8Encoding(false));
            return new ThemeLibraryEntry(parsed.Id, parsed.Name, WorkspaceDir, IsBuiltInStarter: false);
        }

        if (IsSingleThemeMode)
        {
            if (switchActiveWorkspace)
            {
                CreateNewCleanPackWithTheme(parsed);
                CopyDirectory(srcThemeDir, WorkspaceDir, overwrite: true);
                SeedDefaultIconsIfMissing(WorkspaceDir);
                File.WriteAllText(Path.Combine(WorkspaceDir, "layout.json"), parsed.ToJsonString(), new System.Text.UTF8Encoding(false));
                return new ThemeLibraryEntry(parsed.Id, parsed.Name, WorkspaceDir, IsBuiltInStarter: false);
            }

            // Promote single-theme workspace into a multi-theme pack container while keeping WorkspaceDir on the active theme.
            string prevSingleDir = WorkspaceDir;
            var existingEntry = TryReadThemeEntry(prevSingleDir, isBuiltIn: false);
            string basePackSlug = SanitizeId(existingEntry?.Id ?? Path.GetFileName(prevSingleDir.TrimEnd('\\', '/')));
            string packName = basePackSlug;
            int pIdx = 2;
            while (Directory.Exists(Path.Combine(PacksRootDir, packName)))
            {
                packName = $"{basePackSlug}-{pIdx++}";
            }
            string packContainer = Path.Combine(PacksRootDir, packName, "packs");
            string migratedActiveDir = Path.Combine(packContainer, basePackSlug);
            Directory.CreateDirectory(migratedActiveDir);
            CopyDirectory(prevSingleDir, migratedActiveDir, overwrite: true);
            ActivePackDir = packContainer;
            IsSingleThemeMode = false;
            ExternalLinkedDir = Path.Combine(PacksRootDir, packName);
            WorkspaceDir = migratedActiveDir;
        }

        string safeId = SanitizeId(string.IsNullOrWhiteSpace(parsed.Id) ? Path.GetFileName(srcThemeDir) : parsed.Id);
        string dst = Path.Combine(ActivePackDir, safeId);
        int s = 2;
        while (Directory.Exists(dst) && File.Exists(Path.Combine(dst, "layout.json")))
        {
            safeId = $"{SanitizeId(parsed.Id)}-{s++}";
            dst = Path.Combine(ActivePackDir, safeId);
        }
        CopyDirectory(srcThemeDir, dst, overwrite: true);
        SeedDefaultIconsIfMissing(dst);
        parsed.Id = safeId;
        File.WriteAllText(Path.Combine(dst, "layout.json"), parsed.ToJsonString(), new System.Text.UTF8Encoding(false));
        if (switchActiveWorkspace)
        {
            WorkspaceDir = dst;
            fontCache.Clear();
        }
        return new ThemeLibraryEntry(parsed.Id, parsed.Name, dst, IsBuiltInStarter: false);
    }

    public void SaveCurrentTheme(ThemePackModel pack)
    {
        if (string.IsNullOrWhiteSpace(WorkspaceDir)) return;
        Directory.CreateDirectory(WorkspaceDir);
        File.WriteAllText(Path.Combine(WorkspaceDir, "layout.json"), pack.ToJsonString(), new System.Text.UTF8Encoding(false));
    }

    /// <summary>
    /// Opens EITHER a single theme folder (contains layout.json) OR a multi-theme Pack folder
    /// (contains packs/<id>/layout.json or <id>/layout.json) directly as the active Pack,
    /// NEVER mixing in built-in starter themes.
    /// </summary>
    public List<ThemeLibraryEntry> OpenPackOrThemeFolder(string folderPath)
    {
        if (!Directory.Exists(folderPath))
            throw new DirectoryNotFoundException("Folder not found: " + folderPath);

        string clean = folderPath.TrimEnd('\\', '/');

        // Case 1: Single theme folder (contains layout.json directly)
        if (File.Exists(Path.Combine(clean, "layout.json")))
        {
            ActivePackDir = clean;
            IsSingleThemeMode = true;
            ExternalLinkedDir = clean;
            var entry = TryReadThemeEntry(clean, isBuiltIn: false)
                        ?? throw new InvalidDataException("Invalid layout.json in " + clean);
            WorkspaceDir = clean;
            fontCache.Clear();
            SeedDefaultIconsIfMissing(clean);
            return new List<ThemeLibraryEntry> { entry };
        }

        // Case 2: Multi-theme Pack folder (has <folder>/packs/*/layout.json or <folder>/*/layout.json)
        string packsSub = Path.Combine(clean, "packs");
        string candidateRoot = Directory.Exists(packsSub) ? packsSub : clean;
        var found = new List<ThemeLibraryEntry>();
        foreach (string sub in Directory.GetDirectories(candidateRoot).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
        {
            var e = TryReadThemeEntry(sub, isBuiltIn: false);
            if (e != null) found.Add(e);
        }

        if (found.Count == 0 && Directory.Exists(packsSub))
        {
            // Also check direct subfolders just in case packs/ was empty
            foreach (string sub in Directory.GetDirectories(clean).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                var e = TryReadThemeEntry(sub, isBuiltIn: false);
                if (e != null) found.Add(e);
            }
            if (found.Count > 0) candidateRoot = clean;
        }

        if (found.Count == 0)
            throw new FileNotFoundException("No themes (layout.json) found inside " + clean + " or its subfolders.", Path.Combine(clean, "layout.json"));

        ActivePackDir = candidateRoot;
        IsSingleThemeMode = false;
        ExternalLinkedDir = clean;
        WorkspaceDir = found[0].Directory;
        fontCache.Clear();
        SeedDefaultIconsIfMissing(WorkspaceDir);
        return found;
    }

    public bool DeleteThemeFromActivePack(ThemeLibraryEntry entry)
    {
        if (IsSingleThemeMode || !HasActivePack) return false;
        if (!Directory.Exists(entry.Directory)) return false;
        string targetFull = Path.GetFullPath(entry.Directory);
        string activeFull = Path.GetFullPath(ActivePackDir);
        if (!targetFull.StartsWith(activeFull, StringComparison.OrdinalIgnoreCase)) return false;
        Directory.Delete(targetFull, recursive: true);
        return true;
    }

    public void SeedDefaultIconsIfMissing(string targetDir)
    {
        string? repoDefault = LocateDefaultPackDir();
        if (repoDefault == null) return;
        string srcIcons = Path.Combine(repoDefault, "icons");
        string dstIcons = Path.Combine(targetDir, "icons");
        if (Directory.Exists(srcIcons))
        {
            CopyDirectory(srcIcons, dstIcons, overwrite: false);
        }
    }

    public ThemePackModel LoadRepoDefaultStarter()
    {
        string? repoDefault = LocateDefaultPackDir();
        if (repoDefault != null)
        {
            return ThemePackModel.FromJson(File.ReadAllText(Path.Combine(repoDefault, "layout.json")));
        }
        return PresetCatalog.CreateFreeformStarterPack();
    }

    public void SaveToDirectory(ThemePackModel pack, string targetDir)
    {
        Directory.CreateDirectory(targetDir);
        if (!string.Equals(Path.GetFullPath(targetDir), Path.GetFullPath(WorkspaceDir), StringComparison.OrdinalIgnoreCase))
        {
            CopyDirectory(WorkspaceDir, targetDir, overwrite: true);
        }
        string normalizedJson = pack.ToJsonString();
        File.WriteAllText(Path.Combine(targetDir, "layout.json"), normalizedJson, new System.Text.UTF8Encoding(false));
    }

    public void ResetWorkspace()
    {
        if (Directory.Exists(WorkspaceDir))
        {
            Directory.Delete(WorkspaceDir, recursive: true);
        }
        Directory.CreateDirectory(WorkspaceDir);
        SeedDefaultIconsIfMissing(WorkspaceDir);
        fontCache.Clear();
    }

    public static List<DriveInfo> DetectRemovableUsbDrives()
    {
        return DriveInfo.GetDrives()
            .Where(d => d.DriveType == DriveType.Removable && d.IsReady)
            .OrderBy(d => d.Name)
            .ToList();
    }

    public string ExportPackToUsb(ThemePackModel pack, DriveInfo drive, bool asSinglePreviewPack = false)
    {
        SaveCurrentTheme(pack);
        string safeId = SanitizeId(pack.Id);
        string sub = asSinglePreviewPack ? Path.Combine("Gen5WLauncher", "pack") : Path.Combine("Gen5WLauncher", "packs", safeId);
        string destDir = Path.Combine(drive.RootDirectory.FullName, sub);
        if (Directory.Exists(destDir))
        {
            Directory.Delete(destDir, recursive: true);
        }
        SaveToDirectory(pack, destDir);
        return destDir;
    }

    public int ExportSelectedThemesToDirectory(
        ThemePackModel activePack,
        IEnumerable<ThemeLibraryEntry> selectedThemes,
        string packsRootDir,
        bool pruneUnselected)
    {
        SaveCurrentTheme(activePack);
        Directory.CreateDirectory(packsRootDir);

        var selectedList = selectedThemes.ToList();
        var keepIds = new HashSet<string>(
            selectedList.Select(e => SanitizeId(e.Id)),
            StringComparer.OrdinalIgnoreCase);

        if (pruneUnselected && Directory.Exists(packsRootDir))
        {
            foreach (string existingDir in Directory.GetDirectories(packsRootDir))
            {
                if (!File.Exists(Path.Combine(existingDir, "layout.json"))) continue;
                string folderId = SanitizeId(Path.GetFileName(existingDir));
                if (!keepIds.Contains(folderId))
                {
                    Directory.Delete(existingDir, recursive: true);
                }
            }
        }

        int count = 0;
        foreach (var entry in selectedList)
        {
            if (!Directory.Exists(entry.Directory)) continue;
            string dst = Path.Combine(packsRootDir, SanitizeId(entry.Id));
            if (!string.Equals(Path.GetFullPath(entry.Directory), Path.GetFullPath(dst), StringComparison.OrdinalIgnoreCase))
            {
                if (Directory.Exists(dst)) Directory.Delete(dst, recursive: true);
                CopyDirectory(entry.Directory, dst, overwrite: true);
            }
            count++;
        }
        return count;
    }

    public List<string> ListAvailableIcons()
    {
        var set = new SortedSet<string>(PresetCatalog.BuiltInIcons, StringComparer.OrdinalIgnoreCase);
        if (string.IsNullOrWhiteSpace(WorkspaceDir)) return set.ToList();
        foreach (string sub in new[] { "icons", "img" })
        {
            string dir = Path.Combine(WorkspaceDir, sub);
            if (!Directory.Exists(dir)) continue;
            foreach (string f in Directory.GetFiles(dir))
            {
                string ext = Path.GetExtension(f).ToLowerInvariant();
                if (ext is ".svg" or ".png" or ".jpg" or ".jpeg" or ".webp")
                {
                    set.Add(sub + "/" + Path.GetFileName(f));
                }
            }
        }
        return set.ToList();
    }

    public List<string> ListAvailableFonts()
    {
        var list = new List<string>(PresetCatalog.FontPresets);
        if (string.IsNullOrWhiteSpace(WorkspaceDir)) return list;
        string fontsDir = Path.Combine(WorkspaceDir, "fonts");
        if (Directory.Exists(fontsDir))
        {
            foreach (string f in Directory.GetFiles(fontsDir).OrderBy(x => x, StringComparer.OrdinalIgnoreCase))
            {
                string ext = Path.GetExtension(f).ToLowerInvariant();
                if (ext is ".ttf" or ".otf")
                {
                    string rel = "fonts/" + Path.GetFileName(f);
                    if (!list.Contains(rel, StringComparer.OrdinalIgnoreCase))
                    {
                        list.Add(rel);
                    }
                }
            }
        }
        return list;
    }

    private readonly Dictionary<string, FontFamily> fontCache = new(StringComparer.OrdinalIgnoreCase);

    public FontFamily ResolveFontFamily(string? fontRef)
    {
        string key = fontRef?.Trim() ?? "";
        if (string.IsNullOrEmpty(key) || string.Equals(key, "default", StringComparison.OrdinalIgnoreCase))
        {
            return new FontFamily("Segoe UI");
        }
        if (fontCache.TryGetValue(key, out FontFamily? cached)) return cached;

        FontFamily resolved = key.ToLowerInvariant() switch
        {
            "sans-serif" => new FontFamily("Segoe UI"),
            "sans-serif-light" => new FontFamily("Segoe UI Light"),
            "sans-serif-thin" => new FontFamily("Segoe UI Semilight"),
            "sans-serif-condensed" => new FontFamily("Bahnschrift Condensed, Arial Narrow, Segoe UI"),
            "serif" => new FontFamily("Georgia, Times New Roman"),
            "monospace" => new FontFamily("Consolas, Courier New"),
            _ => new FontFamily("Segoe UI")
        };

        if (key.EndsWith(".ttf", StringComparison.OrdinalIgnoreCase) || key.EndsWith(".otf", StringComparison.OrdinalIgnoreCase))
        {
            string full = ResolveAssetFullPath(key);
            if (File.Exists(full))
            {
                try
                {
                    // Stage a content-addressed copy in %TEMP% before passing to WPF GlyphTypeface/FontFamily
                    // so WPF's unmanaged font cache never holds a file lock on the workspace's fonts/*.ttf!
                    byte[] fontBytes = File.ReadAllBytes(full);
                    string ext = Path.GetExtension(full).ToLowerInvariant();
                    string hash = ThemePackageService.ComputeSha256Hex(fontBytes)[..16];
                    string tempFontDir = Path.Combine(Path.GetTempPath(), "Gen5WStudioFontCache", hash);
                    Directory.CreateDirectory(tempFontDir);
                    string cachedFontPath = Path.Combine(tempFontDir, Path.GetFileName(full));
                    if (!File.Exists(cachedFontPath))
                    {
                        File.WriteAllBytes(cachedFontPath, fontBytes);
                    }
                    var gt = new GlyphTypeface(new Uri(cachedFontPath));
                    string? famName = gt.Win32FamilyNames.Values.FirstOrDefault() ?? gt.FamilyNames.Values.FirstOrDefault();
                    string dirUri = new Uri(tempFontDir.TrimEnd('\\') + "\\").AbsoluteUri;
                    resolved = string.IsNullOrWhiteSpace(famName)
                        ? new FontFamily(cachedFontPath)
                        : new FontFamily(new Uri(dirUri), "./#" + famName);
                }
                catch
                {
                    resolved = new FontFamily("Segoe UI");
                }
            }
        }
        else if (!key.StartsWith("sans-serif", StringComparison.OrdinalIgnoreCase)
                 && !string.Equals(key, "serif", StringComparison.OrdinalIgnoreCase)
                 && !string.Equals(key, "monospace", StringComparison.OrdinalIgnoreCase))
        {
            try { resolved = new FontFamily(key); } catch { }
        }

        fontCache[key] = resolved;
        return resolved;
    }

    public string ResolveAssetFullPath(string? relativeRef)
    {
        if (string.IsNullOrWhiteSpace(relativeRef) || relativeRef.Contains("..")) return "";
        string rel = relativeRef.Trim().Replace('/', Path.DirectorySeparatorChar);
        if (!string.IsNullOrWhiteSpace(WorkspaceDir))
        {
            string inWs = Path.Combine(WorkspaceDir, rel);
            if (File.Exists(inWs) || Directory.Exists(inWs)) return inWs;
        }
        string? repoDefault = LocateDefaultPackDir();
        if (repoDefault != null)
        {
            string inRepo = Path.Combine(repoDefault, rel);
            if (File.Exists(inRepo) || Directory.Exists(inRepo)) return inRepo;
        }
        return string.IsNullOrWhiteSpace(WorkspaceDir) ? "" : Path.Combine(WorkspaceDir, rel);
    }

    public static string SanitizeId(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "custom_theme";
        var chars = raw.Trim().ToLowerInvariant().Select(c =>
            (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_').ToArray();
        string s = new string(chars).Trim('_');
        return string.IsNullOrEmpty(s) ? "custom_theme" : (s.Length > 48 ? s[..48] : s);
    }

    private static void CopyDirectory(string src, string dst, bool overwrite)
    {
        Directory.CreateDirectory(dst);
        foreach (string file in Directory.GetFiles(src))
        {
            string target = Path.Combine(dst, Path.GetFileName(file));
            if (overwrite || !File.Exists(target))
            {
                File.Copy(file, target, overwrite: true);
            }
        }
        foreach (string sub in Directory.GetDirectories(src))
        {
            CopyDirectory(sub, Path.Combine(dst, Path.GetFileName(sub)), overwrite);
        }
    }
}
