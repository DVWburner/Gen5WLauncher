using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Gen5WStudio.Services;

public sealed class InstallerBundleInfo
{
    public string DisplaySource { get; }
    public string Version { get; }
    public string? ZipPath { get; }
    private readonly Func<Stream> openStream;

    public InstallerBundleInfo(string displaySource, string version, string? zipPath, Func<Stream> openStream)
    {
        DisplaySource = displaySource;
        Version = version;
        ZipPath = zipPath;
        this.openStream = openStream;
    }

    public Stream OpenRead() => openStream();
}

/// <summary>
/// Locates, validates, and safely extracts the Gen5W Launcher NEMESIS USB installer
/// (NEMESIS.data + packages/gen5w.launcher.zip + README.txt) onto a USB root.
/// </summary>
public static class NemesisInstallerService
{
    private const string EmbeddedResourceSuffix = "gen5w-launcher-nemesis.zip";
    private const string ExpectedPackageZipEntry = "packages/gen5w.launcher.zip";

    public static InstallerBundleInfo? ResolveDefaultInstaller()
    {
        InstallerBundleInfo? bestFile = null;
        Version? bestFileVer = null;
        DateTime bestFileUtc = DateTime.MinValue;

        foreach (string candidateZip in EnumerateCandidateDiskZips())
        {
            try
            {
                using var fs = File.OpenRead(candidateZip);
                if (!ValidateInstallerZip(fs, out string verStr, out _)) continue;
                Version? parsed = TryParseSemVer(verStr);
                DateTime mtimeUtc = File.GetLastWriteTimeUtc(candidateZip);
                int cmp = CompareVersions(parsed, bestFileVer);
                if (bestFile == null || cmp > 0 || (cmp == 0 && mtimeUtc > bestFileUtc))
                {
                    string capturedPath = candidateZip;
                    bestFile = new InstallerBundleInfo(
                        $"Workspace build ({Path.GetFileName(capturedPath)})",
                        string.IsNullOrWhiteSpace(verStr) ? "unknown" : verStr,
                        capturedPath,
                        () => File.OpenRead(capturedPath));
                    bestFileVer = parsed;
                    bestFileUtc = mtimeUtc;
                }
            }
            catch
            {
            }
        }

        InstallerBundleInfo? embedded = ResolveEmbeddedInstaller();
        if (embedded != null)
        {
            Version? embVer = TryParseSemVer(embedded.Version);
            if (bestFile == null)
                return embedded;

            int cmpEmb = CompareVersions(embVer, bestFileVer);
            if (cmpEmb > 0)
                return embedded;

            if (cmpEmb == 0)
            {
                string? asmLoc = Assembly.GetExecutingAssembly().Location;
                DateTime studioUtc = (!string.IsNullOrEmpty(asmLoc) && File.Exists(asmLoc))
                    ? File.GetLastWriteTimeUtc(asmLoc)
                    : DateTime.MaxValue;
                if (studioUtc >= bestFileUtc)
                    return embedded;
            }
        }

        return bestFile ?? embedded;
    }

    public static InstallerBundleInfo LoadCustomInstaller(string zipPath)
    {
        if (string.IsNullOrWhiteSpace(zipPath) || !File.Exists(zipPath))
            throw new FileNotFoundException("Selected installer archive does not exist.", zipPath);

        using (var fs = File.OpenRead(zipPath))
        {
            if (!ValidateInstallerZip(fs, out string ver, out string err))
                throw new InvalidDataException(err);

            string captured = Path.GetFullPath(zipPath);
            return new InstallerBundleInfo(
                $"Custom ({Path.GetFileName(captured)})",
                string.IsNullOrWhiteSpace(ver) ? "custom" : ver,
                captured,
                () => File.OpenRead(captured));
        }
    }

    public static bool ValidateInstallerZip(Stream zipStream, out string detectedVersion, out string error)
    {
        detectedVersion = "";
        error = "";
        if (zipStream == null)
        {
            error = "Installer stream is null.";
            return false;
        }

        try
        {
            using var za = new ZipArchive(zipStream, ZipArchiveMode.Read, leaveOpen: true);
            bool hasNemesisData = false;
            string expectedPkgEntry = ExpectedPackageZipEntry;
            ZipArchiveEntry? pkgEntry = null;

            foreach (var entry in za.Entries)
            {
                string norm = NormalizeSafeRelativeEntry(entry.FullName);
                if (string.IsNullOrEmpty(norm))
                {
                    if (!string.IsNullOrEmpty(entry.FullName) && !entry.FullName.EndsWith("/"))
                    {
                        error = $"Unsafe ZIP entry path '{entry.FullName}' in NEMESIS installer.";
                        return false;
                    }
                    continue;
                }

                if (norm.Equals("NEMESIS.data", StringComparison.OrdinalIgnoreCase))
                {
                    using var sr = new StreamReader(entry.Open(), Encoding.UTF8);
                    string text = sr.ReadToEnd();
                    if (!text.Contains("type=install", StringComparison.OrdinalIgnoreCase))
                    {
                        error = "NEMESIS.data is missing 'type=install'.";
                        return false;
                    }
                    var pathMatch = Regex.Match(text, @"(?m)^path\s*=\s*(.+)$");
                    if (!pathMatch.Success)
                    {
                        error = "NEMESIS.data is missing 'path=...'.";
                        return false;
                    }
                    expectedPkgEntry = pathMatch.Groups[1].Value.Trim().Replace('\\', '/');
                    hasNemesisData = true;
                }
            }

            if (!hasNemesisData)
            {
                error = "Archive does not contain NEMESIS.data.";
                return false;
            }

            pkgEntry = za.Entries.FirstOrDefault(e =>
                string.Equals(NormalizeSafeRelativeEntry(e.FullName), expectedPkgEntry, StringComparison.OrdinalIgnoreCase));
            if (pkgEntry == null || pkgEntry.Length < 1024)
            {
                error = $"Missing or invalid inner package entry '{expectedPkgEntry}'.";
                return false;
            }

            // Inspect inner package for mod.json version cleanly
            using var innerMs = new MemoryStream();
            using (var es = pkgEntry.Open()) es.CopyTo(innerMs);
            byte[] innerBytes = innerMs.ToArray();
            if (innerBytes.Length < 4 || innerBytes[0] != (byte)'P' || innerBytes[1] != (byte)'K')
            {
                error = $"Inner package '{expectedPkgEntry}' is not a valid ZIP archive.";
                return false;
            }

            innerMs.Position = 0;
            using (var innerZa = new ZipArchive(innerMs, ZipArchiveMode.Read))
            {
                string modVer = ExtractModJsonVersion(innerZa);
                string pkgVer = ExtractNemesisPackageVersion(innerZa);
                detectedVersion = !string.IsNullOrWhiteSpace(modVer) ? modVer : pkgVer;
            }

            return true;
        }
        catch (Exception ex)
        {
            error = "Invalid NEMESIS installer ZIP: " + ex.Message;
            return false;
        }
    }

    public static bool HasUnrelatedNemesisInstallOnUsb(string usbRootDir, out string summary)
    {
        summary = "";
        string dataFile = Path.Combine(usbRootDir, "NEMESIS.data");
        if (!File.Exists(dataFile)) return false;
        try
        {
            string text = File.ReadAllText(dataFile, Encoding.UTF8);
            if (text.Contains("gen5w.launcher", StringComparison.OrdinalIgnoreCase)
                || text.Contains("hmgapi.gen5w-launcher", StringComparison.OrdinalIgnoreCase))
                return false;
            summary = text.Trim();
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static (string Version, List<string> WrittenFiles) ExtractInstallerToUsbRoot(InstallerBundleInfo installer, string targetRootDir)
    {
        using var checkStream = installer.OpenRead();
        if (!ValidateInstallerZip(checkStream, out string version, out string err))
            throw new InvalidDataException(err);

        string canonRoot = Path.GetFullPath(targetRootDir).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            + Path.DirectorySeparatorChar;
        Directory.CreateDirectory(canonRoot);

        var written = new List<string>();
        using var extractStream = installer.OpenRead();
        using var za = new ZipArchive(extractStream, ZipArchiveMode.Read);
        foreach (var entry in za.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name) && entry.FullName.EndsWith("/"))
                continue;

            string rel = NormalizeSafeRelativeEntry(entry.FullName);
            if (string.IsNullOrEmpty(rel))
                throw new InvalidDataException($"Refusing unsafe installer path '{entry.FullName}'.");

            string destPath = Path.GetFullPath(Path.Combine(canonRoot, rel.Replace('/', Path.DirectorySeparatorChar)));
            if (!destPath.StartsWith(canonRoot, StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException($"Path escapes target USB root: {entry.FullName}");

            string? parent = Path.GetDirectoryName(destPath);
            if (!string.IsNullOrEmpty(parent)) Directory.CreateDirectory(parent);

            using (var src = entry.Open())
            using (var dst = File.Create(destPath))
            {
                src.CopyTo(dst);
            }
            written.Add(rel);
        }

        return (string.IsNullOrWhiteSpace(version) ? installer.Version : version, written);
    }

    private static InstallerBundleInfo? ResolveEmbeddedInstaller()
    {
        var asm = Assembly.GetExecutingAssembly();
        string? resName = asm.GetManifestResourceNames()
            .FirstOrDefault(n => n.EndsWith(EmbeddedResourceSuffix, StringComparison.OrdinalIgnoreCase));
        if (string.IsNullOrEmpty(resName)) return null;

        using Stream? s = asm.GetManifestResourceStream(resName);
        if (s == null) return null;
        if (!ValidateInstallerZip(s, out string ver, out _)) return null;

        return new InstallerBundleInfo(
            "Bundled with Gen5W Studio",
            string.IsNullOrWhiteSpace(ver) ? "bundled" : ver,
            null,
            () => asm.GetManifestResourceStream(resName)
                ?? throw new InvalidOperationException("Failed to open embedded NEMESIS installer resource."));
    }

    private static IEnumerable<string> EnumerateCandidateDiskZips()
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string start in new[] { AppContext.BaseDirectory, Directory.GetCurrentDirectory() })
        {
            if (string.IsNullOrWhiteSpace(start) || !Directory.Exists(start)) continue;

            foreach (string z in SafeGetNemesisZips(start))
                if (seen.Add(Path.GetFullPath(z))) yield return z;

            string? cursor = start;
            for (int depth = 0; depth < 6 && !string.IsNullOrEmpty(cursor); depth++)
            {
                string distDir = Path.Combine(cursor, "dist");
                foreach (string z in SafeGetNemesisZips(distDir))
                    if (seen.Add(Path.GetFullPath(z))) yield return z;
                cursor = Directory.GetParent(cursor)?.FullName;
            }
        }
    }

    private static IEnumerable<string> SafeGetNemesisZips(string dir)
    {
        if (!Directory.Exists(dir)) return Array.Empty<string>();
        try { return Directory.GetFiles(dir, "gen5w-launcher-*-nemesis.zip"); }
        catch { return Array.Empty<string>(); }
    }

    private static string ExtractModJsonVersion(ZipArchive innerZa)
    {
        var pkgZipEntry = innerZa.Entries.FirstOrDefault(e =>
            NormalizeSafeRelativeEntry(e.FullName).Equals("mod/package.zip", StringComparison.OrdinalIgnoreCase));
        if (pkgZipEntry != null)
        {
            using var pkgMs = new MemoryStream();
            using (var s = pkgZipEntry.Open()) s.CopyTo(pkgMs);
            pkgMs.Position = 0;
            using var pkgZa = new ZipArchive(pkgMs, ZipArchiveMode.Read);
            string v = ReadVersionFromModJsonEntry(pkgZa.Entries.FirstOrDefault(e =>
                NormalizeSafeRelativeEntry(e.FullName).Equals("mod.json", StringComparison.OrdinalIgnoreCase)));
            if (!string.IsNullOrWhiteSpace(v)) return v;
        }

        return ReadVersionFromModJsonEntry(innerZa.Entries.FirstOrDefault(e =>
            e.FullName.EndsWith("mod.json", StringComparison.OrdinalIgnoreCase)));
    }

    private static string ReadVersionFromModJsonEntry(ZipArchiveEntry? modEntry)
    {
        if (modEntry == null) return "";
        try
        {
            using var msJson = new MemoryStream();
            using var mjStream = modEntry.Open();
            mjStream.CopyTo(msJson);
            using var doc = JsonDocument.Parse(msJson.ToArray());
            if (doc.RootElement.TryGetProperty("version", out var vEl) && vEl.ValueKind == JsonValueKind.String)
                return (vEl.GetString() ?? "").Trim();
        }
        catch
        {
        }
        return "";
    }

    private static string ExtractNemesisPackageVersion(ZipArchive innerZa)
    {
        var metaEntry = innerZa.Entries.FirstOrDefault(e =>
            NormalizeSafeRelativeEntry(e.FullName).Equals("NEMESIS.data", StringComparison.OrdinalIgnoreCase));
        if (metaEntry == null) return "";
        try
        {
            using var sr = new StreamReader(metaEntry.Open(), Encoding.UTF8);
            string text = sr.ReadToEnd();
            var m = Regex.Match(text, @"(?m)^package-version\s*=\s*([^\r\n#]+)");
            if (m.Success)
                return m.Groups[1].Value.Trim().Trim('"', '\'');
        }
        catch
        {
        }
        return "";
    }

    private static string NormalizeSafeRelativeEntry(string rawEntry)
    {
        if (string.IsNullOrWhiteSpace(rawEntry) || rawEntry.Contains('\\') || rawEntry.Contains(':'))
            return "";
        string trimmed = rawEntry.Trim();
        if (trimmed.StartsWith("/")) return "";
        if (trimmed.EndsWith("/")) return "";
        string[] segs = trimmed.Split('/');
        foreach (string s in segs)
        {
            if (string.IsNullOrEmpty(s) || s == "." || s == "..") return "";
        }
        return trimmed;
    }

    private static Version? TryParseSemVer(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        string clean = Regex.Match(raw.Trim(), @"^\d+\.\d+(?:\.\d+)?(?:\.\d+)?").Value;
        return Version.TryParse(clean, out Version? v) ? v : null;
    }

    private static int CompareVersions(Version? a, Version? b)
    {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.CompareTo(b);
    }
}
