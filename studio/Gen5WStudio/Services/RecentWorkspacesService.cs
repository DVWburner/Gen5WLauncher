using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gen5WStudio.Services;

public sealed class RecentWorkspaceEntry
{
    [JsonPropertyName("path")]
    public string Path { get; set; } = "";

    [JsonPropertyName("displayName")]
    public string DisplayName { get; set; } = "";

    [JsonPropertyName("kind")]
    public string Kind { get; set; } = "Theme";

    [JsonPropertyName("lastOpenedUtc")]
    public string LastOpenedUtc { get; set; } = "";

    [JsonIgnore]
    public bool ExistsOnDisk => !string.IsNullOrWhiteSpace(Path) && Directory.Exists(Path);

    [JsonIgnore]
    public string FriendlyDateLabel
    {
        get
        {
            if (DateTimeOffset.TryParse(
                    LastOpenedUtc,
                    CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeUniversal,
                    out var dt))
            {
                return dt.ToLocalTime().ToString("yyyy-MM-dd HH:mm");
            }
            return "";
        }
    }
}

/// <summary>
/// Persists recently opened Theme / Pack folders in JSON under LocalAppData.
/// </summary>
public static class RecentWorkspacesService
{
    public const int MaxEntries = 12;
    private static readonly object FileLock = new();
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true
    };

    public static string DefaultStoragePath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "Gen5WStudio",
        "recent_workspaces.json");

    public static List<RecentWorkspaceEntry> LoadRecent(string? storageFilePath = null)
    {
        string path = ResolvePath(storageFilePath);
        lock (FileLock)
        {
            return ReadListNoThrow(path);
        }
    }

    public static void RecordOpened(
        string folderPath,
        string displayName,
        bool isSingleTheme,
        string? storageFilePath = null)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return;
        string cleanPath;
        try
        {
            cleanPath = System.IO.Path.GetFullPath(folderPath.Trim()).TrimEnd('\\', '/');
        }
        catch
        {
            cleanPath = folderPath.Trim().TrimEnd('\\', '/');
        }
        if (string.IsNullOrWhiteSpace(cleanPath)) return;

        string name = string.IsNullOrWhiteSpace(displayName)
            ? (System.IO.Path.GetFileName(cleanPath) ?? cleanPath)
            : displayName.Trim();

        string path = ResolvePath(storageFilePath);
        lock (FileLock)
        {
            var list = ReadListNoThrow(path);
            list.RemoveAll(e => string.Equals(NormalizeKey(e.Path), cleanPath, StringComparison.OrdinalIgnoreCase));
            list.Insert(0, new RecentWorkspaceEntry
            {
                Path = cleanPath,
                DisplayName = name,
                Kind = isSingleTheme ? "Theme" : "Pack",
                LastOpenedUtc = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ")
            });

            if (list.Count > MaxEntries)
                list.RemoveRange(MaxEntries, list.Count - MaxEntries);

            WriteListAtomic(path, list);
        }
    }

    public static void RemoveRecent(string folderPath, string? storageFilePath = null)
    {
        if (string.IsNullOrWhiteSpace(folderPath)) return;
        string target = NormalizeKey(folderPath);
        string path = ResolvePath(storageFilePath);

        lock (FileLock)
        {
            if (!File.Exists(path)) return;
            var list = ReadListNoThrow(path);
            int removed = list.RemoveAll(e => string.Equals(NormalizeKey(e.Path), target, StringComparison.OrdinalIgnoreCase));
            if (removed == 0) return;

            if (list.Count == 0)
            {
                try { File.Delete(path); } catch { }
            }
            else
            {
                WriteListAtomic(path, list);
            }
        }
    }

    public static void ClearRecent(string? storageFilePath = null)
    {
        string path = ResolvePath(storageFilePath);
        lock (FileLock)
        {
            if (File.Exists(path))
            {
                try { File.Delete(path); } catch { }
            }
        }
    }

    private static string ResolvePath(string? overridePath) =>
        string.IsNullOrWhiteSpace(overridePath) ? DefaultStoragePath : overridePath.Trim();

    private static string NormalizeKey(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        try { return System.IO.Path.GetFullPath(raw.Trim()).TrimEnd('\\', '/'); }
        catch { return raw.Trim().TrimEnd('\\', '/'); }
    }

    private static List<RecentWorkspaceEntry> ReadListNoThrow(string path)
    {
        try
        {
            if (!File.Exists(path)) return new List<RecentWorkspaceEntry>();
            string json = File.ReadAllText(path);
            if (string.IsNullOrWhiteSpace(json)) return new List<RecentWorkspaceEntry>();
            var parsed = JsonSerializer.Deserialize<List<RecentWorkspaceEntry>>(json, JsonOpts);
            if (parsed == null) return new List<RecentWorkspaceEntry>();

            var deduped = new List<RecentWorkspaceEntry>();
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var e in parsed)
            {
                if (e == null || string.IsNullOrWhiteSpace(e.Path)) continue;
                string key = NormalizeKey(e.Path);
                if (seen.Add(key))
                    deduped.Add(e);
            }
            return deduped.Take(MaxEntries).ToList();
        }
        catch
        {
            return new List<RecentWorkspaceEntry>();
        }
    }

    private static void WriteListAtomic(string path, List<RecentWorkspaceEntry> list)
    {
        string? dir = System.IO.Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(dir)) Directory.CreateDirectory(dir);
        string json = JsonSerializer.Serialize(list, JsonOpts);
        string tempPath = path + ".tmp_" + Guid.NewGuid().ToString("N")[..6];
        try
        {
            File.WriteAllText(tempPath, json, new System.Text.UTF8Encoding(false));
            File.Move(tempPath, path, overwrite: true);
        }
        finally
        {
            if (File.Exists(tempPath))
            {
                try { File.Delete(tempPath); } catch { }
            }
        }
    }
}
