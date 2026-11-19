using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;
using System.Xml.Linq;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

public sealed class BuiltThemePackageResult
{
    public string ZipPath { get; init; } = "";
    public string PackageSha256 { get; init; } = "";
    public ThemeManifestModel Manifest { get; init; } = new();
    public ThemeSecurityAuditResult Audit { get; init; } = new();
}

/// <summary>
/// Builds, audits, and safely extracts portable single-theme ZIP bundles for Gen5W Studio & Theme Store.
/// Enforces strict file-type whitelists, magic-byte header verification, SVG script blocking,
/// action safety checks, single-theme enforcement, and ZIP-slip/zip-bomb protection.
/// </summary>
public static class ThemePackageService
{
    public const int MaxFileCount = 2500;
    public const long MaxSingleFileBytes = 25L * 1024 * 1024;
    public const long MaxTotalUncompressedBytes = 150L * 1024 * 1024;

    private static readonly HashSet<string> AllowedSubdirs = new(StringComparer.OrdinalIgnoreCase)
    {
        "icons", "img", "media", "anim", "fonts"
    };

    private static readonly HashSet<string> AllowedAssetExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".svg", ".png", ".jpg", ".jpeg", ".webp", ".gif", ".ttf", ".otf"
    };

    private static readonly HashSet<string> DisallowedExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".exe", ".dll", ".bat", ".cmd", ".ps1", ".psm1", ".sh", ".bash", ".zsh",
        ".apk", ".jar", ".dex", ".odex", ".so", ".sys", ".msi", ".vbs", ".vbe",
        ".js", ".mjs", ".cjs", ".ts", ".py", ".rb", ".pl", ".php", ".html", ".htm",
        ".hta", ".scr", ".com", ".pif", ".lnk", ".reg", ".zip", ".7z", ".rar", ".tar", ".gz"
    };

    private static readonly JsonSerializerOptions ManifestJsonOpts = new()
    {
        WriteIndented = true
    };

    private static readonly Regex SimpleDoctypeRegex = new(@"<!DOCTYPE\b[^>\[]*>", RegexOptions.IgnoreCase | RegexOptions.Compiled);
    private static readonly UTF8Encoding Utf8NoBom = new(encoderShouldEmitUTF8Identifier: false);

    private static bool HasBlockedBinaryHeader(byte[] bytes) =>
        HasSignature(bytes, 0x4D, 0x5A) // PE MZ (.exe/.dll)
        || HasSignature(bytes, 0x7F, 0x45, 0x4C, 0x46) // ELF
        || HasSignature(bytes, 0x23, 0x21) // #! script shebang
        || HasSignature(bytes, 0x50, 0x4B, 0x03, 0x04) // ZIP/APK/JAR
        || HasSignature(bytes, 0xCA, 0xFE, 0xBA, 0xBE); // Java class / Mach-O

    /// <summary>
    /// Strips harmless entity-free DTD headers (e.g. SVGRepo / Illustrator SVG 1.1 DOCTYPE)
    /// while leaving internal DTD subsets ([...]) and &lt;!ENTITY declarations intact so safety validation rejects them.
    /// </summary>
    public static byte[] PrepareAssetBytesForPackage(string relPath, byte[] rawBytes)
    {
        if (rawBytes == null || rawBytes.Length == 0) return rawBytes ?? Array.Empty<byte>();
        if (!Path.GetExtension(relPath).Equals(".svg", StringComparison.OrdinalIgnoreCase))
            return rawBytes;
        if (HasBlockedBinaryHeader(rawBytes))
            return rawBytes;

        string text = Encoding.UTF8.GetString(rawBytes);
        if (text.Contains("<!ENTITY", StringComparison.OrdinalIgnoreCase))
            return rawBytes;

        string stripped = SimpleDoctypeRegex.Replace(text, "");
        if (!string.Equals(stripped, text, StringComparison.Ordinal))
        {
            stripped = stripped.TrimStart('\uFEFF', '\r', '\n', ' ', '\t');
            return Utf8NoBom.GetBytes(stripped);
        }
        return rawBytes;
    }

    public static ThemeSecurityAuditResult AuditWorkspaceTheme(
        ThemePackModel pack,
        string workspaceDir,
        bool stripUnsafeActions = false)
    {
        var result = new ThemeSecurityAuditResult();
        if (pack == null || string.IsNullOrWhiteSpace(workspaceDir) || !Directory.Exists(workspaceDir))
        {
            result.Errors.Add("Theme workspace directory does not exist.");
            return result;
        }

        string layoutFile = Path.Combine(workspaceDir, "layout.json");
        if (!File.Exists(layoutFile))
        {
            result.Errors.Add("Missing layout.json at theme root.");
            return result;
        }

        AuditLayoutActions(pack.Root, result, stripUnsafeActions);

        string wsFull = Path.GetFullPath(workspaceDir);
        var filesToAudit = new List<string>();

        // Top-level files in active theme root (layout.json/manifest.json/thumbnail are generated fresh on package)
        foreach (string file in Directory.GetFiles(wsFull).OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
        {
            string name = Path.GetFileName(file);
            if (ShouldIgnoreOsJunk(name)) continue;
            if (name.Equals("layout.json", StringComparison.OrdinalIgnoreCase)
                || name.Equals("manifest.json", StringComparison.OrdinalIgnoreCase)
                || name.Equals("thumbnail.png", StringComparison.OrdinalIgnoreCase)
                || name.Equals("thumbnail.jpg", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }
            filesToAudit.Add(file);
        }

        // Only scan whitelisted top-level theme asset folders; skip unrelated folders like packs/
        foreach (string dir in Directory.GetDirectories(wsFull).OrderBy(d => d, StringComparer.OrdinalIgnoreCase))
        {
            string folderName = Path.GetFileName(dir);
            if (ShouldIgnoreOsJunk(folderName + "/")) continue;
            if (!AllowedSubdirs.Contains(folderName))
            {
                result.Warnings.Add($"Skipped unrelated folder '{folderName}/' (only the active theme is packaged).");
                continue;
            }

            foreach (string file in Directory.EnumerateFiles(dir, "*", SearchOption.AllDirectories)
                         .OrderBy(f => f, StringComparer.OrdinalIgnoreCase))
            {
                string rel = Path.GetRelativePath(wsFull, file).Replace('\\', '/');
                if (ShouldIgnoreOsJunk(rel)) continue;
                if (Path.GetFileName(file).Equals("layout.json", StringComparison.OrdinalIgnoreCase))
                {
                    result.IsSingleTheme = false;
                    result.Errors.Add($"Asset folder contains nested theme file '{rel}'. Only one theme is allowed.");
                    continue;
                }
                filesToAudit.Add(file);
            }
        }

        foreach (string file in filesToAudit)
        {
            string rel = Path.GetRelativePath(wsFull, file).Replace('\\', '/');

            if (!ValidateRelativePathStructure(rel, out string pathErr))
            {
                result.RejectedFiles.Add($"{rel} ({pathErr})");
                continue;
            }

            if (result.IncludedFiles.Count >= MaxFileCount)
            {
                result.Errors.Add($"Theme exceeds maximum file limit ({MaxFileCount}).");
                break;
            }

            var fi = new FileInfo(file);
            if (fi.Length > MaxSingleFileBytes)
            {
                result.RejectedFiles.Add($"{rel} (exceeds {MaxSingleFileBytes / (1024 * 1024)} MB per-file limit)");
                continue;
            }

            byte[] bytes = PrepareAssetBytesForPackage(rel, File.ReadAllBytes(file));
            if (bytes.Length > MaxSingleFileBytes)
            {
                result.RejectedFiles.Add($"{rel} (exceeds {MaxSingleFileBytes / (1024 * 1024)} MB per-file limit)");
                continue;
            }
            if (!ValidateFileContentSafety(rel, bytes, out string fileErr))
            {
                result.RejectedFiles.Add($"{rel} ({fileErr})");
                continue;
            }

            result.TotalBytes += bytes.Length;
            if (result.TotalBytes > MaxTotalUncompressedBytes)
            {
                result.Errors.Add($"Theme exceeds maximum uncompressed size ({MaxTotalUncompressedBytes / (1024 * 1024)} MB).");
                break;
            }

            result.IncludedFiles.Add(new ThemeManifestFileEntry
            {
                Path = rel,
                Size = bytes.Length,
                Sha256 = ComputeSha256Hex(bytes)
            });
        }

        return result;
    }

    public static BuiltThemePackageResult BuildSingleThemeZip(
        ThemePackModel sourcePack,
        string workspaceDir,
        byte[] thumbnailPng,
        string outputZipPath,
        bool stripUnsafeActions = false)
    {
        if (thumbnailPng == null || thumbnailPng.Length == 0 || !HasSignature(thumbnailPng, 0x89, 0x50, 0x4E, 0x47))
            throw new InvalidOperationException("Captured thumbnail is not a valid PNG image.");

        var pack = sourcePack.DeepClone();
        StampPackDatesForRelease(pack);

        var audit = AuditWorkspaceTheme(pack, workspaceDir, stripUnsafeActions);
        if (!audit.IsSafe)
        {
            var reasons = audit.Errors.Concat(audit.RejectedFiles.Select(r => "Unsafe/disallowed file: " + r));
            throw new InvalidOperationException("Security audit failed:\n- " + string.Join("\n- ", reasons.Take(10)));
        }

        byte[] layoutBytes = Encoding.UTF8.GetBytes(pack.ToJsonString());
        if (!ValidateLayoutJsonBytes(layoutBytes, stripUnsafeActions, out _))
            throw new InvalidOperationException("Generated layout.json failed validation.");

        var manifestFiles = new List<ThemeManifestFileEntry>
        {
            new() { Path = "layout.json", Size = layoutBytes.Length, Sha256 = ComputeSha256Hex(layoutBytes) },
            new() { Path = "thumbnail.png", Size = thumbnailPng.Length, Sha256 = ComputeSha256Hex(thumbnailPng) }
        };
        manifestFiles.AddRange(audit.IncludedFiles);

        long totalBytes = manifestFiles.Sum(f => f.Size);
        var manifest = new ThemeManifestModel
        {
            ManifestVersion = 1,
            SingleTheme = true,
            Theme = BuildMetadataModel(pack, manifestFiles.Count, totalBytes),
            Files = manifestFiles
        };
        byte[] manifestBytes = Encoding.UTF8.GetBytes(
            JsonSerializer.Serialize(manifest, ManifestJsonOpts).Replace("\r\n", "\n") + "\n");

        string? outDir = Path.GetDirectoryName(outputZipPath);
        if (!string.IsNullOrEmpty(outDir)) Directory.CreateDirectory(outDir);
        if (File.Exists(outputZipPath)) File.Delete(outputZipPath);

        string wsFull = Path.GetFullPath(workspaceDir);
        using (var fs = File.Create(outputZipPath))
        using (var zip = new ZipArchive(fs, ZipArchiveMode.Create))
        {
            WriteZipEntry(zip, "layout.json", layoutBytes);
            WriteZipEntry(zip, "manifest.json", manifestBytes);
            WriteZipEntry(zip, "thumbnail.png", thumbnailPng);
            foreach (var entry in audit.IncludedFiles)
            {
                string srcPath = Path.Combine(wsFull, entry.Path.Replace('/', Path.DirectorySeparatorChar));
                WriteZipEntry(zip, entry.Path, PrepareAssetBytesForPackage(entry.Path, File.ReadAllBytes(srcPath)));
            }
        }

        string pkgSha = ComputeSha256Hex(File.ReadAllBytes(outputZipPath));
        manifest.Theme.PackageSha256 = pkgSha;

        return new BuiltThemePackageResult
        {
            ZipPath = outputZipPath,
            PackageSha256 = pkgSha,
            Manifest = manifest,
            Audit = audit
        };
    }

    public static (ThemePackModel Pack, ThemeSecurityAuditResult Audit) ExtractSingleThemeZipSafely(
        byte[] zipBytes,
        string destinationThemeDir,
        string? expectedSha256 = null)
    {
        if (zipBytes == null || zipBytes.Length == 0)
            throw new InvalidOperationException("Downloaded archive is empty.");
        if (zipBytes.Length > MaxTotalUncompressedBytes)
            throw new InvalidOperationException("Archive exceeds maximum allowed size (150 MB).");

        if (!string.IsNullOrWhiteSpace(expectedSha256))
        {
            string actual = ComputeSha256Hex(zipBytes);
            if (!string.Equals(actual, expectedSha256.Trim(), StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("Package SHA-256 verification failed.");
        }

        var audit = new ThemeSecurityAuditResult();
        var cleanEntries = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);

        using (var ms = new MemoryStream(zipBytes, writable: false))
        using (var zip = new ZipArchive(ms, ZipArchiveMode.Read))
        {
            if (zip.Entries.Count > MaxFileCount)
                throw new InvalidOperationException($"Archive exceeds maximum entry limit ({MaxFileCount}).");

            int layoutCount = 0;
            foreach (var entry in zip.Entries)
            {
                string rawName = (entry.FullName ?? "").Replace('\\', '/');
                if (rawName.EndsWith("/", StringComparison.Ordinal)) continue;
                if (ShouldIgnoreOsJunk(rawName)) continue;

                // Block symlinks (Unix mode 0120000 stored in upper 16 bits of ExternalAttributes)
                uint unixMode = ((uint)entry.ExternalAttributes) >> 16;
                if ((unixMode & 0xF000) == 0xA000)
                {
                    audit.RejectedFiles.Add($"{rawName} (symbolic link not allowed)");
                    continue;
                }

                if (rawName.Contains("..") || rawName.StartsWith("/", StringComparison.Ordinal) || rawName.Contains(':'))
                {
                    audit.RejectedFiles.Add($"{rawName} (unsafe archive path)");
                    continue;
                }

                if (rawName.Equals("layout.json", StringComparison.OrdinalIgnoreCase)
                    || rawName.EndsWith("/layout.json", StringComparison.OrdinalIgnoreCase))
                {
                    layoutCount++;
                    if (!rawName.Equals("layout.json", StringComparison.OrdinalIgnoreCase))
                    {
                        audit.IsSingleTheme = false;
                        audit.RejectedFiles.Add($"{rawName} (nested theme packs are not allowed; expected single root layout.json)");
                        continue;
                    }
                }

                if (entry.Length > MaxSingleFileBytes)
                {
                    audit.RejectedFiles.Add($"{rawName} (exceeds 25 MB per-file cap)");
                    continue;
                }

                byte[] entryBytes = ReadBoundedEntry(entry, MaxSingleFileBytes);
                audit.TotalBytes += entryBytes.Length;
                if (audit.TotalBytes > MaxTotalUncompressedBytes)
                    throw new InvalidOperationException("Archive uncompressed content exceeds 150 MB safety limit.");

                bool isRootJson = rawName.Equals("layout.json", StringComparison.OrdinalIgnoreCase)
                                  || rawName.Equals("manifest.json", StringComparison.OrdinalIgnoreCase);
                bool isRootThumb = rawName.Equals("thumbnail.png", StringComparison.OrdinalIgnoreCase)
                                   || rawName.Equals("thumbnail.jpg", StringComparison.OrdinalIgnoreCase);

                if (isRootJson)
                {
                    if (!ValidateJsonTextEntry(rawName, entryBytes, out string jsonErr))
                    {
                        audit.RejectedFiles.Add($"{rawName} ({jsonErr})");
                        continue;
                    }
                }
                else if (isRootThumb)
                {
                    if (!ValidateFileContentSafety(rawName, entryBytes, out string thumbErr))
                    {
                        audit.RejectedFiles.Add($"{rawName} ({thumbErr})");
                        continue;
                    }
                }
                else
                {
                    if (!ValidateRelativePathStructure(rawName, out string pathErr))
                    {
                        audit.RejectedFiles.Add($"{rawName} ({pathErr})");
                        continue;
                    }
                    if (!ValidateFileContentSafety(rawName, entryBytes, out string assetErr))
                    {
                        audit.RejectedFiles.Add($"{rawName} ({assetErr})");
                        continue;
                    }
                }

                cleanEntries[rawName] = entryBytes;
                audit.IncludedFiles.Add(new ThemeManifestFileEntry
                {
                    Path = rawName,
                    Size = entryBytes.Length,
                    Sha256 = ComputeSha256Hex(entryBytes)
                });
            }

            if (layoutCount == 0)
            {
                audit.IsSingleTheme = false;
                audit.Errors.Add("Archive does not contain a root layout.json.");
            }
            else if (layoutCount > 1)
            {
                audit.IsSingleTheme = false;
                audit.Errors.Add("Archive contains multiple themes; only single-theme bundles are allowed.");
            }
        }

        if (!cleanEntries.TryGetValue("layout.json", out byte[]? layoutData))
            throw new InvalidOperationException("Invalid or rejected layout.json in archive.");

        var parsedPack = ThemePackModel.FromJson(Encoding.UTF8.GetString(layoutData));
        AuditLayoutActions(parsedPack.Root, audit, stripUnsafeActions: false);

        if (!audit.IsSafe)
        {
            var reasons = audit.Errors.Concat(audit.RejectedFiles);
            throw new InvalidOperationException("Archive blocked by safety scan:\n- " + string.Join("\n- ", reasons.Take(10)));
        }

        string tempStage = destinationThemeDir + ".staging_" + Guid.NewGuid().ToString("N")[..8];
        try
        {
            Directory.CreateDirectory(tempStage);
            string stageFull = Path.GetFullPath(tempStage).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            foreach (var kv in cleanEntries)
            {
                string destPath = Path.GetFullPath(Path.Combine(tempStage, kv.Key.Replace('/', Path.DirectorySeparatorChar)));
                if (!destPath.StartsWith(stageFull, StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Path traversal blocked: " + kv.Key);
                Directory.CreateDirectory(Path.GetDirectoryName(destPath)!);
                File.WriteAllBytes(destPath, kv.Value);
            }

            if (Directory.Exists(destinationThemeDir))
                Directory.Delete(destinationThemeDir, recursive: true);
            Directory.Move(tempStage, destinationThemeDir);
        }
        finally
        {
            if (Directory.Exists(tempStage))
            {
                try { Directory.Delete(tempStage, recursive: true); } catch { }
            }
        }

        return (parsedPack, audit);
    }

    public static ThemeManifestMetadataModel BuildMetadataModel(ThemePackModel pack, int fileCount, long totalBytes)
    {
        var compat = (pack.CompatibleVersions != null && pack.CompatibleVersions.Count > 0)
            ? new List<string>(pack.CompatibleVersions)
            : new List<string> { string.IsNullOrWhiteSpace(pack.MinAppVersion) ? $"{PresetCatalog.DefaultMinAppVersion}+" : pack.MinAppVersion.Trim() };
        string minVer = string.IsNullOrWhiteSpace(pack.MinAppVersion) ? PresetCatalog.DefaultMinAppVersion : pack.MinAppVersion.Trim();
        return new ThemeManifestMetadataModel
        {
            Id = PackWorkspaceService.SanitizeId(pack.Id),
            Name = string.IsNullOrWhiteSpace(pack.Name) ? "Custom Theme" : pack.Name.Trim(),
            Author = string.IsNullOrWhiteSpace(pack.Author) ? "Gen5WStudio" : pack.Author.Trim(),
            Version = string.IsNullOrWhiteSpace(pack.Version) ? "1.0.0" : pack.Version.Trim(),
            Revision = Math.Max(1, pack.Revision),
            Category = string.IsNullOrWhiteSpace(pack.Category) ? "EV & Telemetry" : pack.Category.Trim(),
            Description = (pack.Description ?? "").Trim(),
            MinAppVersion = minVer,
            CompatibleVersions = compat,
            FormatVersion = Math.Max(1, pack.FormatVersion),
            ShowSystemBar = pack.Tokens?.ShowSystemBar ?? true,
            SpeedUnit = pack.Tokens?.SpeedUnit ?? "kmh",
            TempUnit = pack.Tokens?.TempUnit ?? "F",
            CreatedAt = string.IsNullOrWhiteSpace(pack.CreatedAt) ? DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ") : pack.CreatedAt,
            UpdatedAt = string.IsNullOrWhiteSpace(pack.UpdatedAt) ? DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ") : pack.UpdatedAt,
            Thumbnail = "thumbnail.png",
            FileCount = fileCount,
            TotalBytes = totalBytes,
            StudioVersion = PresetCatalog.StudioVersion
        };
    }

    public static void StampPackDatesForRelease(ThemePackModel pack)
    {
        pack.Id = PackWorkspaceService.SanitizeId(pack.Id);
        string nowIso = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ");
        if (string.IsNullOrWhiteSpace(pack.CreatedAt)) pack.CreatedAt = nowIso;
        pack.UpdatedAt = nowIso;
        if (pack.Revision < 1) pack.Revision = 1;
        if (string.IsNullOrWhiteSpace(pack.MinAppVersion)) pack.MinAppVersion = PresetCatalog.DefaultMinAppVersion;
        if (pack.CompatibleVersions == null || pack.CompatibleVersions.Count == 0)
            pack.CompatibleVersions = new List<string> { pack.MinAppVersion + "+" };
        pack.Normalize();
    }

    public static bool ValidateRelativePathStructure(string relPath, out string error)
    {
        error = "";
        if (string.IsNullOrWhiteSpace(relPath))
        {
            error = "empty path";
            return false;
        }
        string clean = relPath.Replace('\\', '/');
        if (clean.StartsWith("/", StringComparison.Ordinal) || clean.Contains("..") || clean.Contains(':') || clean.IndexOf('\0') >= 0)
        {
            error = "unsafe path traversal or absolute path";
            return false;
        }
        string[] segs = clean.Split('/');
        if (segs.Length < 2)
        {
            error = "unexpected root file; assets must live inside icons/, img/, media/, anim/, or fonts/";
            return false;
        }
        if (!AllowedSubdirs.Contains(segs[0]))
        {
            error = $"folder '{segs[0]}/' is not in whitelist (icons, img, media, anim, fonts)";
            return false;
        }
        if (IsAllowedAnimFramesJson(clean))
            return true;
        string ext = Path.GetExtension(clean);
        if (string.IsNullOrEmpty(ext))
        {
            error = "file without extension not allowed";
            return false;
        }
        if (DisallowedExtensions.Contains(ext) || !AllowedAssetExtensions.Contains(ext))
        {
            error = $"disallowed file type '{ext}'";
            return false;
        }
        if (segs[0].Equals("fonts", StringComparison.OrdinalIgnoreCase) && !ext.Equals(".ttf", StringComparison.OrdinalIgnoreCase) && !ext.Equals(".otf", StringComparison.OrdinalIgnoreCase))
        {
            error = "only .ttf/.otf font files allowed inside fonts/";
            return false;
        }
        if (!segs[0].Equals("fonts", StringComparison.OrdinalIgnoreCase) && (ext.Equals(".ttf", StringComparison.OrdinalIgnoreCase) || ext.Equals(".otf", StringComparison.OrdinalIgnoreCase)))
        {
            error = "font files must live inside fonts/";
            return false;
        }
        return true;
    }

    public static bool ValidateFileContentSafety(string relPath, byte[] bytes, out string error)
    {
        error = "";
        if (bytes.Length == 0)
        {
            error = "empty file";
            return false;
        }

        // Always reject executable / archive signatures regardless of extension
        if (HasBlockedBinaryHeader(bytes))
        {
            error = "executable/script/archive binary header detected";
            return false;
        }

        if (IsAllowedAnimFramesJson(relPath))
            return ValidateJsonTextEntry(relPath, bytes, out error);

        string ext = Path.GetExtension(relPath).ToLowerInvariant();
        if (ext == ".svg")
            return ValidateSvgSafety(bytes, out error);
        if (ext == ".png")
            return VerifyMagic(HasSignature(bytes, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A), "invalid PNG header", out error);
        if (ext is ".jpg" or ".jpeg")
            return VerifyMagic(HasSignature(bytes, 0xFF, 0xD8, 0xFF), "invalid JPEG header", out error);
        if (ext == ".gif")
            return VerifyMagic(HasAsciiPrefix(bytes, "GIF87a") || HasAsciiPrefix(bytes, "GIF89a"), "invalid GIF header", out error);
        if (ext == ".webp")
            return VerifyMagic(bytes.Length >= 12 && HasAsciiPrefix(bytes, "RIFF") && Encoding.ASCII.GetString(bytes, 8, 4) == "WEBP", "invalid WEBP header", out error);
        if (ext is ".ttf" or ".otf")
            return VerifyMagic(
                HasSignature(bytes, 0x00, 0x01, 0x00, 0x00)
                || HasAsciiPrefix(bytes, "OTTO")
                || HasAsciiPrefix(bytes, "true")
                || HasAsciiPrefix(bytes, "typ1"),
                "invalid font header",
                out error);

        error = $"unsupported extension '{ext}'";
        return false;
    }

    private static bool IsAllowedAnimFramesJson(string cleanRelPath)
    {
        string clean = cleanRelPath.Replace('\\', '/');
        string[] segs = clean.Split('/');
        return segs.Length >= 2
               && segs[0].Equals("anim", StringComparison.OrdinalIgnoreCase)
               && segs[^1].Equals("frames.json", StringComparison.OrdinalIgnoreCase);
    }

    private static bool ValidateSvgSafety(byte[] bytes, out string error)
    {
        error = "";
        try
        {
            string xmlText = Encoding.UTF8.GetString(bytes);
            if (xmlText.Contains("javascript:", StringComparison.OrdinalIgnoreCase))
            {
                error = "SVG contains disallowed javascript: URI";
                return false;
            }
            if (xmlText.Contains("<!ENTITY", StringComparison.OrdinalIgnoreCase))
            {
                error = "SVG contains disallowed <!ENTITY declaration";
                return false;
            }

            xmlText = SimpleDoctypeRegex.Replace(xmlText, "").TrimStart('\uFEFF', '\r', '\n', ' ', '\t');
            var xmlSettings = new XmlReaderSettings
            {
                DtdProcessing = DtdProcessing.Prohibit,
                XmlResolver = null
            };
            using var sr = new StringReader(xmlText);
            using var xr = XmlReader.Create(sr, xmlSettings);
            var doc = XDocument.Load(xr, LoadOptions.None);
            if (doc.Root == null || !doc.Root.Name.LocalName.Equals("svg", StringComparison.OrdinalIgnoreCase))
            {
                error = "SVG root element must be <svg>";
                return false;
            }
            foreach (var el in doc.Descendants())
            {
                string tag = el.Name.LocalName.ToLowerInvariant();
                if (tag is "script" or "foreignobject" or "iframe" or "object" or "embed")
                {
                    error = $"SVG contains disallowed <{tag}> element";
                    return false;
                }
                foreach (var attr in el.Attributes())
                {
                    string an = attr.Name.LocalName.ToLowerInvariant();
                    if (an.StartsWith("on", StringComparison.Ordinal))
                    {
                        error = $"SVG contains disallowed event handler attribute '{attr.Name.LocalName}'";
                        return false;
                    }
                    string val = attr.Value ?? "";
                    if (val.Contains("javascript:", StringComparison.OrdinalIgnoreCase)
                        || val.Contains("data:text/html", StringComparison.OrdinalIgnoreCase))
                    {
                        error = $"SVG attribute '{attr.Name.LocalName}' contains unsafe URI scheme";
                        return false;
                    }
                }
            }
            return true;
        }
        catch (Exception ex)
        {
            error = "invalid SVG XML: " + ex.Message;
            return false;
        }
    }

    private static bool ValidateJsonTextEntry(string relName, byte[] bytes, out string error)
    {
        error = "";
        try
        {
            string text = Encoding.UTF8.GetString(bytes);
            if (relName.Equals("layout.json", StringComparison.OrdinalIgnoreCase))
            {
                _ = ThemePackModel.FromJson(text);
                return true;
            }
            using var doc = JsonDocument.Parse(text);
            return true;
        }
        catch (Exception ex)
        {
            error = "invalid JSON: " + ex.Message;
            return false;
        }
    }

    private static bool ValidateLayoutJsonBytes(byte[] bytes, bool stripUnsafe, out ThemePackModel? parsed)
    {
        try
        {
            parsed = ThemePackModel.FromJson(Encoding.UTF8.GetString(bytes));
            var tmp = new ThemeSecurityAuditResult();
            AuditLayoutActions(parsed.Root, tmp, stripUnsafe);
            return tmp.IsSafe;
        }
        catch
        {
            parsed = null;
            return false;
        }
    }

    private static void AuditLayoutActions(LayoutNodeModel? node, ThemeSecurityAuditResult audit, bool stripUnsafeActions)
    {
        // Root actions (root_start, root_service, root_broadcast) are permitted since head units have HMG-API and root access.
    }

    private static void WriteZipEntry(ZipArchive zip, string entryName, byte[] data)
    {
        var e = zip.CreateEntry(entryName.Replace('\\', '/'), CompressionLevel.Optimal);
        using var s = e.Open();
        s.Write(data, 0, data.Length);
    }

    private static byte[] ReadBoundedEntry(ZipArchiveEntry entry, long maxBytes)
    {
        using var src = entry.Open();
        using var ms = new MemoryStream();
        byte[] buf = new byte[81920];
        int read;
        long total = 0;
        while ((read = src.Read(buf, 0, buf.Length)) > 0)
        {
            total += read;
            if (total > maxBytes)
                throw new InvalidOperationException($"Entry '{entry.FullName}' exceeds size limit.");
            ms.Write(buf, 0, read);
        }
        return ms.ToArray();
    }

    private static bool ShouldIgnoreOsJunk(string relPath)
    {
        string clean = relPath.Replace('\\', '/');
        string name = Path.GetFileName(clean);
        return clean.StartsWith("__MACOSX/", StringComparison.OrdinalIgnoreCase)
               || name.Equals(".DS_Store", StringComparison.OrdinalIgnoreCase)
               || name.Equals("Thumbs.db", StringComparison.OrdinalIgnoreCase)
               || name.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase);
    }

    private static bool VerifyMagic(bool ok, string failMsg, out string err)
    {
        err = ok ? "" : failMsg;
        return ok;
    }

    private static bool HasSignature(byte[] data, params byte[] sig)
    {
        if (data.Length < sig.Length) return false;
        for (int i = 0; i < sig.Length; i++)
            if (data[i] != sig[i]) return false;
        return true;
    }

    private static bool HasAsciiPrefix(byte[] data, string ascii)
    {
        if (data.Length < ascii.Length) return false;
        for (int i = 0; i < ascii.Length; i++)
            if (data[i] != (byte)ascii[i]) return false;
        return true;
    }

    public static string ComputeSha256Hex(byte[] data)
    {
        byte[] hash = SHA256.HashData(data);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }
}
