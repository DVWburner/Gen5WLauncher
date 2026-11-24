using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Gen5WStudio.Models;
using Gen5WStudio.Services;
using Microsoft.Win32;

namespace Gen5WStudio;

public partial class UploadThemeDialog : Window
{
    private readonly ThemePackModel _pack;
    private readonly string _workspaceDir;
    private readonly Func<byte[]> _recaptureThumbnail;
    private readonly Func<ThemePackModel, bool>? _saveWorkspaceBeforePackage;
    private byte[] _thumbnailPng;
    private ThemeSecurityAuditResult? _lastAudit;
    private StoredStoreSession? _activeSession;
    private string? _loadedOrigin;

    public bool MetadataModified { get; private set; }

    public UploadThemeDialog(
        ThemePackModel currentPack,
        string themeWorkspaceDir,
        byte[] initialThumbnailPng,
        Func<byte[]> recaptureThumbnail,
        Func<ThemePackModel, bool>? saveWorkspaceBeforePackage = null,
        string? storeBaseUrl = null)
    {
        InitializeComponent();

        _pack = currentPack;
        _workspaceDir = themeWorkspaceDir;
        _recaptureThumbnail = recaptureThumbnail;
        _saveWorkspaceBeforePackage = saveWorkspaceBeforePackage;
        _thumbnailPng = initialThumbnailPng ?? Array.Empty<byte>();

        CategoryCombo.ItemsSource = ThemeStoreClient.LastServerCategories ?? PresetCatalog.ThemeCategories;
        StoreUrlBox.Text = string.IsNullOrWhiteSpace(storeBaseUrl) ? ThemeStoreClient.DefaultBaseUrl : storeBaseUrl.Trim();

        PopulateFields();
        DisplayThumbnail(_thumbnailPng);
        RunAudit();
        LoadLocalSessionOnly();

        Loaded += async (_, _) => await Task.WhenAll(ValidateLoadedSessionAsync(), RefreshServerCategoriesAsync());
    }

    private void PopulateFields()
    {
        NameBox.Text = _pack.Name ?? "Custom Theme";
        IdBox.Text = PackWorkspaceService.SanitizeId(_pack.Id);
        AuthorBox.Text = string.IsNullOrWhiteSpace(_pack.Author) ? "Gen5WStudio" : _pack.Author;
        VersionBox.Text = string.IsNullOrWhiteSpace(_pack.Version) ? "1.0.0" : _pack.Version;
        RevisionBox.Text = Math.Max(1, _pack.Revision).ToString();

        string cat = string.IsNullOrWhiteSpace(_pack.Category) ? "EV & Telemetry" : _pack.Category.Trim();
        ApplyCategoriesList(ThemeStoreClient.LastServerCategories ?? PresetCatalog.ThemeCategories, cat);

        string minVer = string.IsNullOrWhiteSpace(_pack.MinAppVersion)
            ? PresetCatalog.DefaultMinAppVersion
            : _pack.MinAppVersion.Trim();
        MinAppVersionBox.Text = minVer;

        CompatVersionsBox.Text = (_pack.CompatibleVersions != null && _pack.CompatibleVersions.Count > 0)
            ? string.Join(", ", _pack.CompatibleVersions)
            : $"{minVer}+";

        DescriptionBox.Text = _pack.Description ?? "";
        string nowIso = DateTime.UtcNow.ToString("yyyy-MM-ddTHH:mm:ssZ");
        DatePreviewBox.Text = string.IsNullOrWhiteSpace(_pack.UpdatedAt) ? nowIso + " (stamped on export)" : _pack.UpdatedAt;
    }

    private bool ApplyFieldsToPack()
    {
        string name = (NameBox.Text ?? "").Trim();
        string author = (AuthorBox.Text ?? "").Trim();
        string version = (VersionBox.Text ?? "").Trim();
        if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(author) || string.IsNullOrWhiteSpace(version))
        {
            MessageBox.Show(this, "Theme Name, Author, and Version are required.", "Missing Metadata",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return false;
        }

        if (!int.TryParse((RevisionBox.Text ?? "").Trim(), out int rev) || rev < 1)
        {
            MessageBox.Show(this, "Revision must be a positive whole number (1 or higher).", "Invalid Revision",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return false;
        }

        string cleanId = PackWorkspaceService.SanitizeId(IdBox.Text);
        IdBox.Text = cleanId;

        string minVer = string.IsNullOrWhiteSpace(MinAppVersionBox.Text)
            ? PresetCatalog.DefaultMinAppVersion
            : MinAppVersionBox.Text.Trim();

        var compatList = (CompatVersionsBox.Text ?? "")
            .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (compatList.Count == 0)
            compatList.Add($"{minVer}+");

        _pack.Id = cleanId;
        _pack.Name = name;
        _pack.Author = author;
        _pack.Version = version;
        _pack.Revision = rev;
        _pack.Category = CategoryCombo.SelectedItem?.ToString() ?? "EV & Telemetry";
        _pack.MinAppVersion = minVer;
        _pack.CompatibleVersions = compatList;
        _pack.Description = (DescriptionBox.Text ?? "").Trim();
        ThemePackageService.StampPackDatesForRelease(_pack);
        DatePreviewBox.Text = _pack.UpdatedAt ?? "";
        MetadataModified = true;

        _saveWorkspaceBeforePackage?.Invoke(_pack);
        return true;
    }

    private void DisplayThumbnail(byte[] pngBytes)
    {
        if (pngBytes == null || pngBytes.Length == 0)
        {
            ThumbnailPreviewImage.Source = null;
            ThumbnailInfoText.Text = "No thumbnail captured.";
            return;
        }

        try
        {
            using var ms = new MemoryStream(pngBytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();
            ThumbnailPreviewImage.Source = bmp;
            ThumbnailInfoText.Text = $"{bmp.PixelWidth} x {bmp.PixelHeight} PNG ({pngBytes.Length / 1024.0:0.#} KB)";
        }
        catch (Exception ex)
        {
            ThumbnailPreviewImage.Source = null;
            ThumbnailInfoText.Text = "Failed to decode thumbnail: " + ex.Message;
        }
    }

    private void ApplyCategoriesList(IEnumerable<string> categories, string? preferred = null)
    {
        string? wanted = preferred ?? CategoryCombo.SelectedItem?.ToString() ?? _pack.Category;
        var list = categories
            .Where(c => !string.IsNullOrWhiteSpace(c))
            .Select(c => c.Trim())
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (list.Count == 0)
            list.AddRange(PresetCatalog.ThemeCategories);

        CategoryCombo.ItemsSource = list;
        CategoryCombo.SelectedItem = list.FirstOrDefault(
            c => !string.IsNullOrWhiteSpace(wanted) && c.Equals(wanted.Trim(), StringComparison.OrdinalIgnoreCase))
            ?? list[0];
    }

    private async Task RefreshServerCategoriesAsync()
    {
        string url = StoreUrlBox.Text;
        try
        {
            var serverCats = await ThemeStoreClient.FetchCategoriesAsync(url);
            if (serverCats.Count > 0)
            {
                await Dispatcher.InvokeAsync(() =>
                {
                    ApplyCategoriesList(serverCats);
                    StatusText.Text = $"Synced {serverCats.Count} category option(s) from Theme Store.";
                });
            }
        }
        catch
        {
            // Keep existing / cached categories when offline
        }
    }

    private void RunAudit()
    {
        var clone = _pack.DeepClone();
        _lastAudit = ThemePackageService.AuditWorkspaceTheme(clone, _workspaceDir);

        var sb = new StringBuilder();
        if (_lastAudit.Errors.Count > 0)
        {
            sb.AppendLine("ERRORS (blocking):");
            foreach (var e in _lastAudit.Errors) sb.AppendLine("  [x] " + e);
            sb.AppendLine();
        }
        if (_lastAudit.RejectedFiles.Count > 0)
        {
            sb.AppendLine("REJECTED FILES (unsafe/disallowed):");
            foreach (var r in _lastAudit.RejectedFiles) sb.AppendLine("  [!] " + r);
            sb.AppendLine();
        }
        if (_lastAudit.Warnings.Count > 0)
        {
            sb.AppendLine("WARNINGS:");
            foreach (var w in _lastAudit.Warnings) sb.AppendLine("  [*] " + w);
            sb.AppendLine();
        }

        sb.AppendLine($"PACKAGED REFERENCED ASSETS ({_lastAudit.IncludedFiles.Count} files, {_lastAudit.TotalBytes / 1024.0:0.#} KB):");
        foreach (var f in _lastAudit.IncludedFiles.Take(40))
            sb.AppendLine($"  + {f.Path} ({f.Size / 1024.0:0.#} KB)");
        if (_lastAudit.IncludedFiles.Count > 40)
            sb.AppendLine($"  ... and {_lastAudit.IncludedFiles.Count - 40} more files.");

        if (_lastAudit.ExcludedUnusedFiles.Count > 0)
        {
            sb.AppendLine();
            sb.AppendLine($"SKIPPED UNUSED WORKSPACE FILES ({_lastAudit.ExcludedUnusedFiles.Count} files, {_lastAudit.ExcludedUnusedBytes / 1024.0:0.#} KB — NOT packaged in .zip):");
            foreach (var u in _lastAudit.ExcludedUnusedFiles.Take(20))
                sb.AppendLine($"  - {u}");
            if (_lastAudit.ExcludedUnusedFiles.Count > 20)
                sb.AppendLine($"  ... and {_lastAudit.ExcludedUnusedFiles.Count - 20} more unused files.");
        }

        var builtInIcons = new HashSet<string>(PresetCatalog.BuiltInIcons, StringComparer.OrdinalIgnoreCase);
        bool hasCleanableCustomUnused = _lastAudit.ExcludedUnusedFiles.Any(p => !builtInIcons.Contains(p));
        CleanUnusedBtn.Visibility = hasCleanableCustomUnused ? Visibility.Visible : Visibility.Collapsed;

        AuditDetailsBox.Text = sb.ToString();

        if (_lastAudit.IsSafe)
        {
            AuditBadgeBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF064E3B"));
            AuditBadgeBorder.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF10B981"));
            AuditSummaryText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFD1FAE5"));
            string skippedTag = _lastAudit.ExcludedUnusedFiles.Count > 0
                ? $" ({_lastAudit.ExcludedUnusedFiles.Count} unused workspace file(s) automatically excluded)"
                : "";
            AuditSummaryText.Text = $"SAFE: Single theme verified ({_lastAudit.IncludedFiles.Count} referenced assets + layout.json + thumbnail.png + manifest.json){skippedTag}.";
            SaveZipBtn.IsEnabled = true;
            UploadStoreBtn.IsEnabled = true;
        }
        else
        {
            AuditBadgeBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF7F1D1D"));
            AuditBadgeBorder.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFEF4444"));
            AuditSummaryText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFFECACA"));
            AuditSummaryText.Text = $"BLOCKED: {_lastAudit.Errors.Count} error(s), {_lastAudit.RejectedFiles.Count} disallowed file(s). Only verified safe assets may be packaged.";
            SaveZipBtn.IsEnabled = false;
            UploadStoreBtn.IsEnabled = false;
        }
    }

    private void OnCleanUnusedClick(object sender, RoutedEventArgs e)
    {
        if (MessageBox.Show(
                this,
                "Delete unused custom wallpapers, converted video frame folders, fonts, and custom icons from your local workspace folder?\n\n" +
                "(Note: Unused files are already excluded automatically when saving a .zip or exporting to USB.)",
                "Clean Unused Workspace Files",
                MessageBoxButton.YesNo,
                MessageBoxImage.Question) != MessageBoxResult.Yes)
        {
            return;
        }

        var (deleted, freedBytes) = PackWorkspaceService.CleanUnusedWorkspaceAssets(_pack, _workspaceDir);
        RunAudit();
        StatusText.Text = deleted > 0
            ? $"Cleaned {deleted} unused workspace file(s) ({freedBytes / 1024.0:0.#} KB freed)."
            : "No unused custom workspace files to delete.";
    }

    private void OnBumpRevisionClick(object sender, RoutedEventArgs e)
    {
        if (int.TryParse((RevisionBox.Text ?? "").Trim(), out int rev) && rev >= 1)
            RevisionBox.Text = (rev + 1).ToString();
        else
            RevisionBox.Text = "2";
    }

    private void OnRecaptureThumbnailClick(object sender, RoutedEventArgs e)
    {
        try
        {
            _thumbnailPng = _recaptureThumbnail();
            DisplayThumbnail(_thumbnailPng);
            StatusText.Text = "Re-captured live 960x360 thumbnail from canvas.";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Failed to recapture thumbnail:\n" + ex.Message, "Capture Error",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnRescanClick(object sender, RoutedEventArgs e) => RunAudit();

    private void OnSaveZipClick(object sender, RoutedEventArgs e)
    {
        if (!ApplyFieldsToPack()) return;
        RunAudit();
        if (_lastAudit == null || !_lastAudit.IsSafe) return;

        var dlg = new SaveFileDialog
        {
            Title = "Save Safe Single-Theme Package (.zip)",
            Filter = "Gen5W Theme Package (*.zip)|*.zip",
            FileName = $"{_pack.Id}_v{_pack.Version}_r{_pack.Revision}.zip"
        };
        if (dlg.ShowDialog(this) != true) return;

        try
        {
            var pkg = ThemePackageService.BuildSingleThemeZip(
                _pack,
                _workspaceDir,
                _thumbnailPng,
                dlg.FileName);

            StatusText.Text = $"Saved single-theme ZIP ({pkg.Manifest.Theme.FileCount} files, SHA-256: {pkg.PackageSha256[..12]}...).";
            MessageBox.Show(this,
                $"Single-theme package created safely!\n\n" +
                $"Theme: {_pack.Name} v{_pack.Version} (rev {_pack.Revision})\n" +
                $"Files: {pkg.Manifest.Theme.FileCount} (layout.json, manifest.json, thumbnail.png + assets)\n" +
                $"SHA-256: {pkg.PackageSha256}\n\nSaved to:\n{pkg.ZipPath}",
                "Theme Packaged", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Failed to build safe theme ZIP:\n\n" + ex.Message, "Package Error",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void LoadLocalSessionOnly(string? customExpiredNotice = null)
    {
        string? origin = null;
        try { origin = StoreAuthStorage.NormalizeOrigin(StoreUrlBox.Text); } catch { }
        _loadedOrigin = origin;

        var saved = StoreAuthStorage.LoadSession(StoreUrlBox.Text);
        if (saved != null && saved.IsExpired())
        {
            StoreAuthStorage.ClearSession(StoreUrlBox.Text);
            _activeSession = null;
            UpdateAccountBar("Saved session expired — please sign in again to upload.");
            return;
        }

        _activeSession = saved;
        UpdateAccountBar(customExpiredNotice);
    }

    private async Task ValidateLoadedSessionAsync()
    {
        var session = _activeSession;
        string url = StoreUrlBox.Text;
        if (session == null) return;

        try
        {
            var me = await ThemeStoreClient.ValidateSessionAsync(url, session.Token);
            if (_activeSession != session) return;
            if (me.Account != null && !string.IsNullOrWhiteSpace(me.Account.Username))
            {
                session.Username = me.Account.Username;
                session.CreatedAt = me.Account.CreatedAt;
                StoreAuthStorage.SaveSession(url, session);
                UpdateAccountBar();
            }
        }
        catch (ThemeStoreApiException apiEx) when (apiEx.StatusCode == HttpStatusCode.Unauthorized)
        {
            StoreAuthStorage.ClearSession(url);
            if (_activeSession == session)
            {
                _activeSession = null;
                UpdateAccountBar("Your saved session expired — please sign in again to upload.");
            }
        }
        catch
        {
            // Keep cached session untouched when offline or server is unreachable
        }
    }

    private void UpdateAccountBar(string? signedOutOverride = null)
    {
        if (_activeSession != null && !_activeSession.IsExpired())
        {
            AccountStatusText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF34D399"));
            AccountStatusText.Text = $"Signed in as @{_activeSession.Username} (private account; public Author is shown on Store)";
            AccountSignInBtn.Content = "Switch Account";
            AccountSignOutBtn.Visibility = Visibility.Visible;
        }
        else
        {
            AccountStatusText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(
                string.IsNullOrWhiteSpace(signedOutOverride) ? "#FFCBD5E1" : "#FFFBBF24"));
            AccountStatusText.Text = string.IsNullOrWhiteSpace(signedOutOverride)
                ? "Not signed in — sign in or create an account to upload (local .zip export works anytime)."
                : signedOutOverride;
            AccountSignInBtn.Content = "Sign In / Register...";
            AccountSignOutBtn.Visibility = Visibility.Collapsed;
        }
    }

    private bool PromptSignInIfNeeded(string? banner = null)
    {
        if (_activeSession != null && !_activeSession.IsExpired() && string.IsNullOrWhiteSpace(banner))
            return true;

        var authDlg = new StoreAuthDialog(StoreUrlBox.Text, bannerMessage: banner) { Owner = this };
        if (authDlg.ShowDialog() == true && authDlg.AuthenticatedSession != null)
        {
            _activeSession = authDlg.AuthenticatedSession;
            UpdateAccountBar();
            StatusText.Text = $"Signed in as @{_activeSession.Username}.";
            return true;
        }
        return false;
    }

    private void OnAccountSignInClick(object sender, RoutedEventArgs e)
    {
        var authDlg = new StoreAuthDialog(StoreUrlBox.Text) { Owner = this };
        if (authDlg.ShowDialog() == true && authDlg.AuthenticatedSession != null)
        {
            _activeSession = authDlg.AuthenticatedSession;
            UpdateAccountBar();
            StatusText.Text = $"Signed in as @{_activeSession.Username}.";
        }
    }

    private void OnAccountSignOutClick(object sender, RoutedEventArgs e)
    {
        StoreAuthStorage.ClearSession(StoreUrlBox.Text);
        _activeSession = null;
        UpdateAccountBar();
        StatusText.Text = "Signed out from Theme Store.";
    }

    private async void OnStoreUrlLostFocus(object sender, RoutedEventArgs e)
    {
        string? newOrigin = null;
        try { newOrigin = StoreAuthStorage.NormalizeOrigin(StoreUrlBox.Text); } catch { }
        if (!string.Equals(newOrigin, _loadedOrigin, StringComparison.OrdinalIgnoreCase))
        {
            LoadLocalSessionOnly();
            await Task.WhenAll(ValidateLoadedSessionAsync(), RefreshServerCategoriesAsync());
        }
    }

    private async void OnUploadClick(object sender, RoutedEventArgs e)
    {
        if (!ApplyFieldsToPack()) return;
        RunAudit();
        if (_lastAudit == null || !_lastAudit.IsSafe) return;

        if (!PromptSignInIfNeeded())
        {
            StatusText.Text = "Upload cancelled — sign in is required to upload to Theme Store.";
            return;
        }

        string tempZip = Path.Combine(
            Path.GetTempPath(),
            $"gen5w_upload_{_pack.Id}_{Guid.NewGuid():N}.zip");

        UploadStoreBtn.IsEnabled = false;
        SaveZipBtn.IsEnabled = false;
        StatusText.Text = "Building verified single-theme ZIP & uploading to Theme Store...";

        BuiltThemePackageResult? pkg = null;
        try
        {
            pkg = ThemePackageService.BuildSingleThemeZip(
                _pack,
                _workspaceDir,
                _thumbnailPng,
                tempZip);

            var resp = await ThemeStoreClient.UploadThemeAsync(
                StoreUrlBox.Text,
                pkg.ZipPath,
                _thumbnailPng,
                pkg.Manifest,
                _activeSession!.Token);

            StatusText.Text = resp.Message ?? "Uploaded single theme to Theme Store!";
            MessageBox.Show(this,
                $"Theme uploaded to Theme Store!\n\n" +
                $"Theme: {pkg.Manifest.Theme.Name} v{pkg.Manifest.Theme.Version} (r{pkg.Manifest.Theme.Revision})\n" +
                $"Category: {pkg.Manifest.Theme.Category}\n" +
                $"SHA-256: {pkg.PackageSha256}\n\n{resp.Message}",
                "Upload Complete", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (ThemeStoreApiException apiEx)
        {
            string headline;
            if (apiEx.StatusCode == HttpStatusCode.Unauthorized)
            {
                StoreAuthStorage.ClearSession(StoreUrlBox.Text);
                _activeSession = null;
                UpdateAccountBar("Session expired — please sign in again.");
                StatusText.Text = "Authentication required — session cleared.";
                var relogin = MessageBox.Show(this,
                    $"Session rejected by server:\n{apiEx.FormatFriendlyMessage()}\n\nWould you like to sign in again now?",
                    "Sign In Required", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (relogin == MessageBoxResult.Yes)
                    PromptSignInIfNeeded("Your previous session expired. Sign in to upload.");
                return;
            }
            else if (apiEx.StatusCode == HttpStatusCode.Forbidden)
            {
                headline = $"Theme ID '{_pack.Id}' already belongs to another creator account.\n" +
                           $"Change the Theme ID slug (or sign in with the owning account) and try again.\n\nServer details: {apiEx.FormatFriendlyMessage()}";
            }
            else if (apiEx.StatusCode == HttpStatusCode.Conflict)
            {
                headline = $"{apiEx.FormatFriendlyMessage()}\n\nTip: If updating your theme, click '+1' on Revision or bump Version. If the ID is unavailable, choose a different Theme ID.";
            }
            else
            {
                headline = apiEx.FormatFriendlyMessage();
            }

            StatusText.Text = "Upload rejected — you can adjust metadata or save the verified .zip locally.";
            OfferSaveLocalZipAfterFailure(headline, pkg);
        }
        catch (Exception ex)
        {
            StatusText.Text = "Upload failed — you can still save the verified .zip locally.";
            OfferSaveLocalZipAfterFailure($"Could not upload to '{StoreUrlBox.Text}':\n{ex.Message}", pkg);
        }
        finally
        {
            if (File.Exists(tempZip))
            {
                try { File.Delete(tempZip); } catch { }
            }
            UploadStoreBtn.IsEnabled = _lastAudit?.IsSafe == true;
            SaveZipBtn.IsEnabled = _lastAudit?.IsSafe == true;
        }
    }

    private void OfferSaveLocalZipAfterFailure(string detail, BuiltThemePackageResult? pkg)
    {
        var choice = MessageBox.Show(this,
            detail + "\n\n" +
            (pkg != null
                ? "Would you like to save the already-built safe .zip file locally?"
                : "Please verify your Store endpoint URL and settings."),
            "Theme Store Upload Failed",
            pkg != null ? MessageBoxButton.YesNo : MessageBoxButton.OK,
            MessageBoxImage.Warning);

        if (choice == MessageBoxResult.Yes && pkg != null && File.Exists(pkg.ZipPath))
        {
            var sfd = new SaveFileDialog
            {
                Title = "Save Built Theme Package (.zip)",
                Filter = "Gen5W Theme Package (*.zip)|*.zip",
                FileName = $"{_pack.Id}_v{_pack.Version}_r{_pack.Revision}.zip"
            };
            if (sfd.ShowDialog(this) == true)
            {
                File.Copy(pkg.ZipPath, sfd.FileName, overwrite: true);
                StatusText.Text = "Saved safe .zip locally: " + Path.GetFileName(sfd.FileName);
            }
        }
    }

    private void OnCloseClick(object sender, RoutedEventArgs e)
    {
        DialogResult = MetadataModified;
        Close();
    }
}
