using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Gen5WStudio.Models;

namespace Gen5WStudio.Services;

public sealed class StoreCatalogQueryResult
{
    public string EndpointUsed { get; init; } = "";
    public List<string> Categories { get; init; } = new();
    public List<StoreThemeListing> Themes { get; init; } = new();
}

public sealed class ThemeStoreApiException : Exception
{
    public HttpStatusCode StatusCode { get; }
    public string? ServerError { get; }
    public int? RetryAfterSeconds { get; }

    public ThemeStoreApiException(
        HttpStatusCode statusCode,
        string message,
        string? serverError = null,
        int? retryAfterSeconds = null) : base(message)
    {
        StatusCode = statusCode;
        ServerError = serverError;
        RetryAfterSeconds = retryAfterSeconds;
    }

    public string FormatFriendlyMessage()
    {
        if (RetryAfterSeconds is int secs && secs > 0)
        {
            string waitStr = secs >= 120
                ? $"~{Math.Ceiling(secs / 60.0)} minute(s)"
                : $"{secs} second(s)";
            return $"{Message}\nPlease try again in {waitStr}.";
        }
        return Message;
    }
}

/// <summary>
/// HTTP/JSON client talking to https://market.ioniqthemes.com/ for browsing, authentication,
/// theme deletion, and uploading single-theme packages.
/// </summary>
public static class ThemeStoreClient
{
    public const string DefaultBaseUrl = "https://market.ioniqthemes.com/";
    private const int MaxThumbnailBytes = 8 * 1024 * 1024;

    private static readonly HttpClient Http = CreateHttpClient();
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true
    };
    private static readonly object CategoriesLock = new();
    private static IReadOnlyList<string>? _lastServerCategories;

    public static IReadOnlyList<string>? LastServerCategories
    {
        get
        {
            lock (CategoriesLock)
                return _lastServerCategories;
        }
    }

    public static async Task<List<string>> FetchCategoriesAsync(
        string? baseUrl,
        CancellationToken ct = default)
    {
        var res = await FetchCatalogAsync(baseUrl, null, null, null, ct).ConfigureAwait(false);
        return res.Categories;
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new HttpClientHandler
        {
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate
        };
        var client = new HttpClient(handler)
        {
            Timeout = TimeSpan.FromSeconds(45)
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd($"Gen5WStudio/{PresetCatalog.StudioVersion}");
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        return client;
    }

    public static bool ValidateUsername(string? username, out string error)
    {
        string u = username ?? "";
        if (u.Length < 3 || u.Length > 32)
        {
            error = "Username must be between 3 and 32 characters.";
            return false;
        }
        foreach (char c in u)
        {
            bool ok = (c >= 'a' && c <= 'z')
                      || (c >= 'A' && c <= 'Z')
                      || (c >= '0' && c <= '9')
                      || c == '_'
                      || c == '-';
            if (!ok)
            {
                error = "Username may contain only letters, digits, underscores (_), and hyphens (-).";
                return false;
            }
        }
        error = "";
        return true;
    }

    public static bool ValidatePassword(string? password, out string error)
    {
        string p = password ?? "";
        if (p.Length < 8 || p.Length > 128)
        {
            error = "Password must be between 8 and 128 characters.";
            return false;
        }
        error = "";
        return true;
    }

    public static async Task<StoreCatalogQueryResult> FetchCatalogAsync(
        string? baseUrl,
        string? category,
        string? searchQuery,
        string? bearerToken = null,
        CancellationToken ct = default)
    {
        Uri root = NormalizeAndValidateBaseUri(baseUrl);
        string qs = BuildQueryString(category, searchQuery);
        string apiEndpoint = new Uri(root, "api/v1/themes" + qs).ToString();
        string rootEndpoint = new Uri(root, qs).ToString();

        string jsonText;
        string usedEndpoint = apiEndpoint;
        using (var apiReq = CreateJsonRequest(HttpMethod.Get, apiEndpoint, bearerToken))
        using (var resp = await Http.SendAsync(apiReq, ct).ConfigureAwait(false))
        {
            if (resp.StatusCode == HttpStatusCode.NotFound)
            {
                usedEndpoint = rootEndpoint;
                using var rootReq = CreateJsonRequest(HttpMethod.Get, rootEndpoint, bearerToken);
                using var fallbackResp = await Http.SendAsync(rootReq, ct).ConfigureAwait(false);
                fallbackResp.EnsureSuccessStatusCode();
                jsonText = await fallbackResp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            }
            else
            {
                resp.EnsureSuccessStatusCode();
                jsonText = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            }
        }

        return ParseAndPrepareCatalog(jsonText, root, usedEndpoint, category, searchQuery);
    }

    public static StoreCatalogQueryResult ParseAndPrepareCatalog(
        string jsonText,
        Uri baseUri,
        string endpointUsed,
        string? categoryFilter = null,
        string? searchFilter = null)
    {
        var (rawThemes, serverCategories) = ParseCatalogJson(jsonText);

        var prepared = new List<StoreThemeListing>();
        foreach (var t in rawThemes)
        {
            t.DownloadUrl = ResolveSafeMediaOrDownloadUrl(t.DownloadUrl, baseUri);
            t.ThumbnailUrl = ResolveSafeMediaOrDownloadUrl(t.ThumbnailUrl, baseUri);
            prepared.Add(t);
        }

        // Server categories are authoritative; fall back to theme categories / defaults only if empty.
        var cats = new List<string>();
        var catSeen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        void AddCat(string? c)
        {
            if (string.IsNullOrWhiteSpace(c)) return;
            string clean = c.Trim();
            if (clean.Equals("All Categories", StringComparison.OrdinalIgnoreCase) || clean.Equals("all", StringComparison.OrdinalIgnoreCase)) return;
            if (catSeen.Add(clean)) cats.Add(clean);
        }

        foreach (var c in serverCategories) AddCat(c);
        foreach (var t in prepared) AddCat(t.Category);

        if (serverCategories.Count > 0)
        {
            var authCats = new List<string>();
            var authSeen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var c in serverCategories)
            {
                if (string.IsNullOrWhiteSpace(c)) continue;
                string clean = c.Trim();
                if (clean.Equals("All Categories", StringComparison.OrdinalIgnoreCase) || clean.Equals("all", StringComparison.OrdinalIgnoreCase)) continue;
                if (authSeen.Add(clean)) authCats.Add(clean);
            }
            if (authCats.Count > 0)
            {
                lock (CategoriesLock)
                    _lastServerCategories = authCats.AsReadOnly();
            }
        }

        if (cats.Count == 0)
        {
            foreach (var c in PresetCatalog.ThemeCategories) AddCat(c);
        }

        IEnumerable<StoreThemeListing> filtered = prepared;
        if (!string.IsNullOrWhiteSpace(categoryFilter)
            && !categoryFilter.Equals("All Categories", StringComparison.OrdinalIgnoreCase)
            && !categoryFilter.Equals("all", StringComparison.OrdinalIgnoreCase))
        {
            filtered = filtered.Where(t => string.Equals(t.Category?.Trim(), categoryFilter.Trim(), StringComparison.OrdinalIgnoreCase));
        }

        if (!string.IsNullOrWhiteSpace(searchFilter))
        {
            string q = searchFilter.Trim();
            filtered = filtered.Where(t => MatchesQuery(t, q));
        }

        // Always sort newest-first (UpdatedAt desc -> CreatedAt desc -> Revision desc)
        var sorted = filtered
            .OrderByDescending(t => t.ResolveSortDate())
            .ThenByDescending(t => t.Revision)
            .ThenBy(t => t.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        return new StoreCatalogQueryResult
        {
            EndpointUsed = endpointUsed,
            Categories = cats,
            Themes = sorted
        };
    }

    public static async Task<StoreAuthResponse> RegisterAsync(
        string? baseUrl,
        string username,
        string password,
        CancellationToken ct = default)
    {
        string trimmedUser = (username ?? "").Trim();
        if (!ValidateUsername(trimmedUser, out string userErr))
            throw new ThemeStoreApiException(HttpStatusCode.BadRequest, userErr);
        if (!ValidatePassword(password, out string passErr))
            throw new ThemeStoreApiException(HttpStatusCode.BadRequest, passErr);

        return await PostAuthAsync(baseUrl, "api/v1/auth/register", trimmedUser, password, "Registration failed", ct)
            .ConfigureAwait(false);
    }

    public static async Task<StoreAuthResponse> LoginAsync(
        string? baseUrl,
        string username,
        string password,
        CancellationToken ct = default)
    {
        string trimmedUser = (username ?? "").Trim();
        if (string.IsNullOrWhiteSpace(trimmedUser))
            throw new ThemeStoreApiException(HttpStatusCode.BadRequest, "Username is required.");
        if (string.IsNullOrEmpty(password))
            throw new ThemeStoreApiException(HttpStatusCode.BadRequest, "Password is required.");

        return await PostAuthAsync(baseUrl, "api/v1/auth/login", trimmedUser, password, "Sign in failed", ct)
            .ConfigureAwait(false);
    }

    private static async Task<StoreAuthResponse> PostAuthAsync(
        string? baseUrl,
        string relativePath,
        string username,
        string password,
        string errorPrefix,
        CancellationToken ct)
    {
        Uri root = NormalizeAndValidateBaseUri(baseUrl);
        Uri endpoint = new Uri(root, relativePath);
        string payload = JsonSerializer.Serialize(new StoreAuthRequest { Username = username, Password = password });

        using var req = CreateJsonRequest(HttpMethod.Post, endpoint.ToString(), bearerToken: null);
        req.Content = new StringContent(payload, Encoding.UTF8, "application/json");
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);

        var parsed = await ReadApiResponseAsync(
            resp,
            (StoreAuthResponse? r) => r != null && r.Ok && !string.IsNullOrWhiteSpace(r.Token),
            r => r?.Error,
            r => r?.Message,
            errorPrefix,
            ct).ConfigureAwait(false);

        return parsed;
    }

    public static async Task<StoreMeResponse> ValidateSessionAsync(
        string? baseUrl,
        string bearerToken,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(bearerToken))
            throw new ThemeStoreApiException(HttpStatusCode.Unauthorized, "No authentication token saved.");

        Uri root = NormalizeAndValidateBaseUri(baseUrl);
        Uri endpoint = new Uri(root, "api/v1/auth/me");

        using var req = CreateJsonRequest(HttpMethod.Get, endpoint.ToString(), bearerToken);
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);

        return await ReadApiResponseAsync(
            resp,
            (StoreMeResponse? r) => r != null && r.Ok && r.Account != null,
            r => r?.Error,
            _ => null,
            "Session validation failed",
            ct).ConfigureAwait(false);
    }

    public static async Task<StoreUploadResponse> UploadThemeAsync(
        string? baseUrl,
        string zipPath,
        byte[] thumbnailPng,
        ThemeManifestModel manifest,
        string bearerToken,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(bearerToken))
            throw new ThemeStoreApiException(HttpStatusCode.Unauthorized, "Please sign in to upload themes.");

        Uri root = NormalizeAndValidateBaseUri(baseUrl);
        Uri uploadEndpoint = new Uri(root, "api/v1/themes");

        byte[] zipBytes = await File.ReadAllBytesAsync(zipPath, ct).ConfigureAwait(false);
        string metadataJson = JsonSerializer.Serialize(manifest.Theme, new JsonSerializerOptions { WriteIndented = false });

        using var form = new MultipartFormDataContent();
        var pkgContent = new ByteArrayContent(zipBytes);
        pkgContent.Headers.ContentType = new MediaTypeHeaderValue("application/zip");
        form.Add(pkgContent, "package", Path.GetFileName(zipPath));

        var thumbContent = new ByteArrayContent(thumbnailPng);
        thumbContent.Headers.ContentType = new MediaTypeHeaderValue("image/png");
        form.Add(thumbContent, "thumbnail", "thumbnail.png");

        var metaContent = new StringContent(metadataJson, Encoding.UTF8, "application/json");
        form.Add(metaContent, "metadata");

        using var req = new HttpRequestMessage(HttpMethod.Post, uploadEndpoint) { Content = form };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearerToken.Trim());
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);

        return await ReadApiResponseAsync(
            resp,
            (StoreUploadResponse? r) => r == null || r.Ok || string.IsNullOrWhiteSpace(r.Error),
            r => r?.Error,
            r => r?.Message,
            "Upload rejected",
            ct,
            allowEmptySuccessBody: true,
            fallbackSuccessFactory: () => new StoreUploadResponse
            {
                Ok = true,
                Message = "Uploaded to Theme Store successfully."
            }).ConfigureAwait(false);
    }

    public static async Task<StoreDeleteResponse> DeleteThemeAsync(
        string? baseUrl,
        string themeId,
        string bearerToken,
        CancellationToken ct = default)
    {
        if (string.IsNullOrWhiteSpace(themeId))
            throw new ThemeStoreApiException(HttpStatusCode.BadRequest, "Theme ID is required.");
        if (string.IsNullOrWhiteSpace(bearerToken))
            throw new ThemeStoreApiException(HttpStatusCode.Unauthorized, "Please sign in to delete themes.");

        Uri root = NormalizeAndValidateBaseUri(baseUrl);
        Uri endpoint = new Uri(root, $"api/v1/themes/{Uri.EscapeDataString(themeId.Trim())}");

        using var req = CreateJsonRequest(HttpMethod.Delete, endpoint.ToString(), bearerToken);
        using var resp = await Http.SendAsync(req, ct).ConfigureAwait(false);

        return await ReadApiResponseAsync(
            resp,
            (StoreDeleteResponse? r) => r == null || r.Ok || string.IsNullOrWhiteSpace(r.Error),
            r => r?.Error,
            r => r?.Message,
            "Delete failed",
            ct,
            allowEmptySuccessBody: true,
            fallbackSuccessFactory: () => new StoreDeleteResponse
            {
                Ok = true,
                Message = "Theme deleted successfully."
            }).ConfigureAwait(false);
    }

    private static HttpRequestMessage CreateJsonRequest(HttpMethod method, string url, string? bearerToken)
    {
        var req = new HttpRequestMessage(method, url);
        if (!string.IsNullOrWhiteSpace(bearerToken))
            req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearerToken.Trim());
        return req;
    }

    private static async Task<T> ReadApiResponseAsync<T>(
        HttpResponseMessage resp,
        Func<T?, bool> isOk,
        Func<T?, string?> getError,
        Func<T?, string?> getMessage,
        string errorPrefix,
        CancellationToken ct,
        bool allowEmptySuccessBody = false,
        Func<T>? fallbackSuccessFactory = null) where T : class
    {
        string body = await resp.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
        T? parsed = null;
        if (!string.IsNullOrWhiteSpace(body))
        {
            try { parsed = JsonSerializer.Deserialize<T>(body, JsonOpts); } catch { }
        }

        if (!resp.IsSuccessStatusCode || !isOk(parsed))
        {
            string? serverErr = getError(parsed);
            string detail = !string.IsNullOrWhiteSpace(serverErr)
                ? serverErr!
                : (!string.IsNullOrWhiteSpace(getMessage(parsed))
                    ? getMessage(parsed)!
                    : $"HTTP {(int)resp.StatusCode} ({resp.ReasonPhrase})");

            string msg = string.Equals(detail, serverErr, StringComparison.Ordinal) && !string.IsNullOrWhiteSpace(serverErr)
                ? detail
                : $"{errorPrefix}: {detail}";

            throw new ThemeStoreApiException(resp.StatusCode, msg, serverErr, ParseRetryAfterSeconds(resp));
        }

        if (parsed != null) return parsed;
        if (allowEmptySuccessBody && fallbackSuccessFactory != null) return fallbackSuccessFactory();
        throw new ThemeStoreApiException(resp.StatusCode, $"{errorPrefix}: Empty response from server.");
    }

    private static int? ParseRetryAfterSeconds(HttpResponseMessage resp)
    {
        if (resp.Headers.RetryAfter?.Delta is TimeSpan delta && delta.TotalSeconds > 0)
            return (int)Math.Ceiling(delta.TotalSeconds);
        if (resp.Headers.RetryAfter?.Date is DateTimeOffset date)
        {
            double secs = (date - DateTimeOffset.UtcNow).TotalSeconds;
            if (secs > 0) return (int)Math.Ceiling(secs);
        }
        if (resp.Headers.TryGetValues("Retry-After", out var vals))
        {
            string? raw = vals.FirstOrDefault()?.Trim();
            if (int.TryParse(raw, out int s) && s > 0) return s;
        }
        return null;
    }

    public static Task<byte[]> DownloadThumbnailAsync(string url, CancellationToken ct = default)
        => DownloadBytesAsync(url, MaxThumbnailBytes, ct);

    public static Task<byte[]> DownloadPackageAsync(string url, CancellationToken ct = default)
        => DownloadBytesAsync(url, (int)ThemePackageService.MaxTotalUncompressedBytes, ct);

    public static async Task<byte[]> DownloadBytesAsync(string url, int maxBytes, CancellationToken ct = default)
    {
        if (!TryValidateRemoteUri(url, out Uri? uri) || uri == null)
            throw new InvalidOperationException("Blocked unsafe or non-HTTPS download URL: " + url);

        using var resp = await Http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false);
        resp.EnsureSuccessStatusCode();
        if (resp.Content.Headers.ContentLength is long len && len > maxBytes)
            throw new InvalidOperationException($"Download exceeds size cap ({maxBytes / (1024 * 1024)} MB).");

        using var stream = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
        using var ms = new MemoryStream();
        byte[] buf = new byte[65536];
        int read;
        while ((read = await stream.ReadAsync(buf, 0, buf.Length, ct).ConfigureAwait(false)) > 0)
        {
            if (ms.Length + read > maxBytes)
                throw new InvalidOperationException($"Downloaded payload exceeded {maxBytes / (1024 * 1024)} MB safety limit.");
            ms.Write(buf, 0, read);
        }
        return ms.ToArray();
    }

    private static (List<StoreThemeListing> Themes, List<string> Categories) ParseCatalogJson(string jsonText)
    {
        string trimmed = (jsonText ?? "").Trim();
        if (trimmed.StartsWith("[", StringComparison.Ordinal))
        {
            var arr = JsonSerializer.Deserialize<List<StoreThemeListing>>(trimmed, JsonOpts) ?? new();
            return (arr, new List<string>());
        }

        var catResp = JsonSerializer.Deserialize<StoreCatalogResponse>(trimmed, JsonOpts)
                      ?? throw new InvalidDataException("Empty JSON catalog response.");
        var items = catResp.Themes ?? catResp.Items ?? catResp.Data ?? new List<StoreThemeListing>();
        return (items, catResp.Categories ?? new List<string>());
    }

    private static bool MatchesQuery(StoreThemeListing t, string q)
    {
        return ContainsCI(t.Name, q)
               || ContainsCI(t.Author, q)
               || ContainsCI(t.Id, q)
               || ContainsCI(t.Category, q)
               || ContainsCI(t.Description, q)
               || ContainsCI(t.FriendlyCompatLabel, q)
               || (t.Tags != null && t.Tags.Any(tag => ContainsCI(tag, q)));
    }

    private static bool ContainsCI(string? haystack, string needle) =>
        !string.IsNullOrEmpty(haystack) && haystack.Contains(needle, StringComparison.OrdinalIgnoreCase);

    private static string BuildQueryString(string? category, string? searchQuery)
    {
        var parts = new List<string> { "sort=newest" };
        if (!string.IsNullOrWhiteSpace(category)
            && !category.Equals("All Categories", StringComparison.OrdinalIgnoreCase)
            && !category.Equals("all", StringComparison.OrdinalIgnoreCase))
        {
            parts.Add("category=" + Uri.EscapeDataString(category.Trim()));
        }
        if (!string.IsNullOrWhiteSpace(searchQuery))
        {
            parts.Add("q=" + Uri.EscapeDataString(searchQuery.Trim()));
        }
        return "?" + string.Join("&", parts);
    }

    public static Uri NormalizeAndValidateBaseUri(string? baseUrl)
    {
        string raw = string.IsNullOrWhiteSpace(baseUrl) ? DefaultBaseUrl : baseUrl.Trim();
        if (!raw.EndsWith("/", StringComparison.Ordinal)) raw += "/";
        if (!TryValidateRemoteUri(raw, out Uri? uri) || uri == null)
            throw new InvalidOperationException("Marketplace URL must use HTTPS (e.g. https://market.ioniqthemes.com/).");
        return uri;
    }

    private static string? ResolveSafeMediaOrDownloadUrl(string? raw, Uri baseUri)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        string trimmed = raw.Trim();
        if (Uri.TryCreate(trimmed, UriKind.Absolute, out Uri? abs))
            return TryValidateRemoteUri(abs.ToString(), out _) ? abs.ToString() : null;
        if (Uri.TryCreate(baseUri, trimmed, out Uri? rel))
            return TryValidateRemoteUri(rel.ToString(), out _) ? rel.ToString() : null;
        return null;
    }

    private static bool TryValidateRemoteUri(string? raw, out Uri? uri)
    {
        uri = null;
        if (string.IsNullOrWhiteSpace(raw)) return false;
        if (!Uri.TryCreate(raw.Trim(), UriKind.Absolute, out var parsed)) return false;
        if (parsed.Scheme.Equals("https", StringComparison.OrdinalIgnoreCase))
        {
            uri = parsed;
            return true;
        }
        if (parsed.Scheme.Equals("http", StringComparison.OrdinalIgnoreCase) && parsed.IsLoopback)
        {
            uri = parsed;
            return true;
        }
        return false;
    }
}
