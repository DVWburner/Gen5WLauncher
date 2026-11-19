using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text.Json.Serialization;

namespace Gen5WStudio.Models;

public sealed class ThemeManifestFileEntry
{
    [JsonPropertyName("path")]
    public string Path { get; set; } = "";

    [JsonPropertyName("size")]
    public long Size { get; set; }

    [JsonPropertyName("sha256")]
    public string Sha256 { get; set; } = "";
}

public sealed class ThemeManifestMetadataModel
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("author")]
    public string Author { get; set; } = "";

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0.0";

    [JsonPropertyName("revision")]
    public int Revision { get; set; } = 1;

    [JsonPropertyName("category")]
    public string Category { get; set; } = "EV & Telemetry";

    [JsonPropertyName("description")]
    public string Description { get; set; } = "";

    [JsonPropertyName("minAppVersion")]
    public string MinAppVersion { get; set; } = "0.3.4";

    [JsonPropertyName("compatibleVersions")]
    public List<string> CompatibleVersions { get; set; } = new() { "0.3.4+" };

    [JsonPropertyName("formatVersion")]
    public int FormatVersion { get; set; } = 2;

    [JsonPropertyName("showSystemBar")]
    public bool ShowSystemBar { get; set; } = true;

    [JsonPropertyName("speedUnit")]
    public string SpeedUnit { get; set; } = "kmh";

    [JsonPropertyName("tempUnit")]
    public string TempUnit { get; set; } = "F";

    [JsonPropertyName("createdAt")]
    public string CreatedAt { get; set; } = "";

    [JsonPropertyName("updatedAt")]
    public string UpdatedAt { get; set; } = "";

    [JsonPropertyName("thumbnail")]
    public string Thumbnail { get; set; } = "thumbnail.png";

    [JsonPropertyName("fileCount")]
    public int FileCount { get; set; }

    [JsonPropertyName("totalBytes")]
    public long TotalBytes { get; set; }

    [JsonPropertyName("studioVersion")]
    public string StudioVersion { get; set; } = "0.3.4";

    [JsonPropertyName("packageSha256")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? PackageSha256 { get; set; }
}

public sealed class ThemeManifestModel
{
    [JsonPropertyName("manifestVersion")]
    public int ManifestVersion { get; set; } = 1;

    [JsonPropertyName("singleTheme")]
    public bool SingleTheme { get; set; } = true;

    [JsonPropertyName("theme")]
    public ThemeManifestMetadataModel Theme { get; set; } = new();

    [JsonPropertyName("files")]
    public List<ThemeManifestFileEntry> Files { get; set; } = new();
}

public sealed class ThemeSecurityAuditResult
{
    public bool IsSingleTheme { get; set; } = true;
    public List<string> Errors { get; } = new();
    public List<string> Warnings { get; } = new();
    public List<ThemeManifestFileEntry> IncludedFiles { get; } = new();
    public List<string> RejectedFiles { get; } = new();
    public long TotalBytes { get; set; }
    public bool IsSafe => IsSingleTheme && Errors.Count == 0 && RejectedFiles.Count == 0;
}

public sealed class StoreThemeListing
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("author")]
    public string Author { get; set; } = "";

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0.0";

    [JsonPropertyName("revision")]
    public int Revision { get; set; } = 1;

    [JsonPropertyName("category")]
    public string Category { get; set; } = "Other";

    [JsonPropertyName("description")]
    public string? Description { get; set; }

    [JsonPropertyName("compatibleVersions")]
    public List<string>? CompatibleVersions { get; set; }

    [JsonPropertyName("minAppVersion")]
    public string? MinAppVersion { get; set; }

    [JsonPropertyName("formatVersion")]
    public int FormatVersion { get; set; } = 2;

    [JsonPropertyName("showSystemBar")]
    public bool? ShowSystemBar { get; set; }

    [JsonPropertyName("createdAt")]
    public string? CreatedAt { get; set; }

    [JsonPropertyName("updatedAt")]
    public string? UpdatedAt { get; set; }

    [JsonPropertyName("downloadUrl")]
    public string? DownloadUrl { get; set; }

    [JsonPropertyName("thumbnailUrl")]
    public string? ThumbnailUrl { get; set; }

    [JsonPropertyName("fileSizeBytes")]
    public long? FileSizeBytes { get; set; }

    [JsonPropertyName("sha256")]
    public string? Sha256 { get; set; }

    [JsonPropertyName("downloads")]
    public int? Downloads { get; set; }

    [JsonPropertyName("tags")]
    public List<string>? Tags { get; set; }

    [JsonPropertyName("isOwner")]
    public bool? IsOwner { get; set; }

    [JsonIgnore]
    public bool IsOwnedByCurrentUser => IsOwner == true;

    public DateTimeOffset ResolveSortDate()
    {
        if (DateTimeOffset.TryParse(UpdatedAt, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var upd))
            return upd;
        if (DateTimeOffset.TryParse(CreatedAt, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var cre))
            return cre;
        return DateTimeOffset.MinValue;
    }

    public string FriendlyDateLabel
    {
        get
        {
            var dt = ResolveSortDate();
            return dt == DateTimeOffset.MinValue ? "Unknown date" : dt.ToLocalTime().ToString("yyyy-MM-dd");
        }
    }

    public string FriendlyCompatLabel
    {
        get
        {
            if (CompatibleVersions != null && CompatibleVersions.Count > 0)
                return string.Join(", ", CompatibleVersions);
            if (!string.IsNullOrWhiteSpace(MinAppVersion))
                return $"{MinAppVersion}+";
            return $"Format v{Math.Max(1, FormatVersion)}";
        }
    }

    public string FriendlySizeLabel
    {
        get
        {
            long b = FileSizeBytes ?? 0;
            if (b <= 0) return "";
            if (b < 1024) return $"{b} B";
            if (b < 1024 * 1024) return $"{b / 1024.0:0.#} KB";
            return $"{b / (1024.0 * 1024.0):0.##} MB";
        }
    }
}

public sealed class StoreCatalogResponse
{
    [JsonPropertyName("apiVersion")]
    public int ApiVersion { get; set; } = 1;

    [JsonPropertyName("generatedAt")]
    public string? GeneratedAt { get; set; }

    [JsonPropertyName("categories")]
    public List<string>? Categories { get; set; }

    [JsonPropertyName("total")]
    public int? Total { get; set; }

    [JsonPropertyName("themes")]
    public List<StoreThemeListing>? Themes { get; set; }

    [JsonPropertyName("items")]
    public List<StoreThemeListing>? Items { get; set; }

    [JsonPropertyName("data")]
    public List<StoreThemeListing>? Data { get; set; }
}

public sealed class StoreUploadResponse
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    [JsonPropertyName("theme")]
    public StoreThemeListing? Theme { get; set; }
}

public sealed class StoreAccountInfo
{
    [JsonPropertyName("username")]
    public string Username { get; set; } = "";

    [JsonPropertyName("createdAt")]
    public string? CreatedAt { get; set; }
}

public sealed class StoreAuthRequest
{
    [JsonPropertyName("username")]
    public string Username { get; set; } = "";

    [JsonPropertyName("password")]
    public string Password { get; set; } = "";
}

public sealed class StoreAuthResponse
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    [JsonPropertyName("token")]
    public string? Token { get; set; }

    [JsonPropertyName("tokenType")]
    public string? TokenType { get; set; }

    [JsonPropertyName("expiresAt")]
    public string? ExpiresAt { get; set; }

    [JsonPropertyName("account")]
    public StoreAccountInfo? Account { get; set; }
}

public sealed class StoreMeResponse
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }

    [JsonPropertyName("account")]
    public StoreAccountInfo? Account { get; set; }
}

public sealed class StoreDeleteResponse
{
    [JsonPropertyName("ok")]
    public bool Ok { get; set; }

    [JsonPropertyName("message")]
    public string? Message { get; set; }

    [JsonPropertyName("error")]
    public string? Error { get; set; }
}

public sealed class StoredStoreSession
{
    [JsonPropertyName("baseOrigin")]
    public string BaseOrigin { get; set; } = "";

    [JsonPropertyName("username")]
    public string Username { get; set; } = "";

    [JsonPropertyName("createdAt")]
    public string? CreatedAt { get; set; }

    [JsonPropertyName("token")]
    public string Token { get; set; } = "";

    [JsonPropertyName("expiresAt")]
    public string? ExpiresAt { get; set; }

    public bool IsExpired(DateTimeOffset? now = null)
    {
        if (string.IsNullOrWhiteSpace(ExpiresAt)) return false;
        if (!DateTimeOffset.TryParse(
                ExpiresAt,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal,
                out var exp))
        {
            return false;
        }
        return exp <= (now ?? DateTimeOffset.UtcNow).AddMinutes(1);
    }
}
