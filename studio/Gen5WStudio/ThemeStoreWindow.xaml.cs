using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Gen5WStudio.Models;
using Gen5WStudio.Services;
using Microsoft.Win32;

namespace Gen5WStudio;

public partial class ThemeStoreWindow : Window
{
    private readonly PackWorkspaceService _workspace;
    private readonly Func<bool> _openUploadFlow;
    private readonly List<StoreThemeListing> _allLoadedThemes = new();
    private readonly ConcurrentDictionary<string, byte[]> _thumbnailBytesCache = new(StringComparer.OrdinalIgnoreCase);
    private CancellationTokenSource? _fetchCts;
    private bool _suppressCategoryChange;
    private StoredStoreSession? _activeSession;
    private string? _loadedOrigin;

    public string? InstalledThemeDir { get; private set; }
    public bool RequestOpenUpload { get; private set; }

    public ThemeStoreWindow(PackWorkspaceService workspace, Func<bool>? openUploadFlow = null, string? initialBaseUrl = null, bool canUploadActiveTheme = true)
    {
        InitializeComponent();
        _workspace = workspace;
        _openUploadFlow = openUploadFlow ?? (() => false);

        UploadActiveBtn.IsEnabled = canUploadActiveTheme;
        if (!canUploadActiveTheme)
            UploadActiveBtn.ToolTip = "Open or create a theme first before uploading to the Theme Store";

        StoreUrlBox.Text = string.IsNullOrWhiteSpace(initialBaseUrl)
            ? ThemeStoreClient.DefaultBaseUrl
            : initialBaseUrl.Trim();

        PopulateCategories(ThemeStoreClient.LastServerCategories ?? PresetCatalog.ThemeCategories);
        LoadLocalSessionOnly();
        Loaded += async (_, _) => await InitializeStoreAsync();
    }

    private async Task InitializeStoreAsync()
    {
        await Task.WhenAll(ValidateLoadedSessionAsync(), FetchFromServerAsync());
    }

    private string? CurrentValidTokenOrNull() =>
        _activeSession != null && !_activeSession.IsExpired() ? _activeSession.Token : null;

    private void LoadLocalSessionOnly()
    {
        string? origin = null;
        try { origin = StoreAuthStorage.NormalizeOrigin(StoreUrlBox.Text); } catch { }
        _loadedOrigin = origin;

        var saved = StoreAuthStorage.LoadSession(StoreUrlBox.Text);
        if (saved != null && saved.IsExpired())
        {
            StoreAuthStorage.ClearSession(StoreUrlBox.Text);
            _activeSession = null;
            UpdateAccountUi(sessionExpired: true);
            return;
        }

        _activeSession = saved;
        UpdateAccountUi(sessionExpired: false);
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
                UpdateAccountUi(sessionExpired: false);
            }
        }
        catch (ThemeStoreApiException apiEx) when (apiEx.StatusCode == HttpStatusCode.Unauthorized)
        {
            StoreAuthStorage.ClearSession(url);
            if (_activeSession == session)
            {
                _activeSession = null;
                MyThemesCheck.IsChecked = false;
                UpdateAccountUi(sessionExpired: true);
                await FetchFromServerAsync();
            }
        }
        catch
        {
            // Offline or unreachable server: keep cached session silently
        }
    }

    private void UpdateAccountUi(bool sessionExpired)
    {
        if (_activeSession != null && !_activeSession.IsExpired())
        {
            AccountBadgeBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF064E3B"));
            AccountBadgeBorder.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF10B981"));
            AccountBadgeText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFD1FAE5"));
            AccountBadgeText.Text = "@" + _activeSession.Username;
            SignInBtn.Content = "Switch Account";
            SignOutBtn.Visibility = Visibility.Visible;
            MyThemesCheck.Visibility = Visibility.Visible;
        }
        else
        {
            AccountBadgeBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF1E293B"));
            AccountBadgeBorder.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(
                sessionExpired ? "#FFF59E0B" : "#FF334155"));
            AccountBadgeText.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(
                sessionExpired ? "#FFFDE68A" : "#FF94A3B8"));
            AccountBadgeText.Text = sessionExpired ? "Session expired" : "Not signed in";
            SignInBtn.Content = "Sign In / Register";
            SignOutBtn.Visibility = Visibility.Collapsed;
            MyThemesCheck.Visibility = Visibility.Collapsed;
            MyThemesCheck.IsChecked = false;
        }
    }

    private void PopulateCategories(IEnumerable<string> categories)
    {
        string? prev = CategoryCombo.SelectedItem?.ToString();
        _suppressCategoryChange = true;
        try
        {
            var items = new List<string> { "All Categories" };
            var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { "All Categories" };
            foreach (var c in categories)
            {
                if (!string.IsNullOrWhiteSpace(c) && seen.Add(c.Trim()))
                    items.Add(c.Trim());
            }
            CategoryCombo.ItemsSource = items;
            CategoryCombo.SelectedItem = (prev != null && items.Contains(prev)) ? prev : "All Categories";
        }
        finally
        {
            _suppressCategoryChange = false;
        }
    }

    private string? SelectedCategoryOrNull()
    {
        string? val = CategoryCombo.SelectedItem?.ToString();
        if (string.IsNullOrWhiteSpace(val) || val.Equals("All Categories", StringComparison.OrdinalIgnoreCase))
            return null;
        return val;
    }

    private async Task FetchFromServerAsync()
    {
        _fetchCts?.Cancel();
        _fetchCts?.Dispose();
        _fetchCts = new CancellationTokenSource();
        CancellationToken ct = _fetchCts.Token;

        BannerBorder.Visibility = Visibility.Collapsed;
        StatusText.Text = $"Fetching themes from {StoreUrlBox.Text} (newest first)...";
        CardsHost.Children.Clear();
        EmptyStateText.Visibility = Visibility.Collapsed;

        try
        {
            var res = await ThemeStoreClient.FetchCatalogAsync(
                StoreUrlBox.Text,
                SelectedCategoryOrNull(),
                SearchBox.Text,
                CurrentValidTokenOrNull(),
                ct);

            if (ct.IsCancellationRequested) return;

            _allLoadedThemes.Clear();
            _allLoadedThemes.AddRange(res.Themes);
            PopulateCategories(res.Categories);

            var displayed = FilterOwnedIfChecked(res.Themes);
            RenderThemes(displayed);
            StatusText.Text = MyThemesCheck.IsChecked == true
                ? $"Loaded {displayed.Count} of your theme(s) from {res.EndpointUsed} (sorted newest first)."
                : $"Loaded {res.Themes.Count} theme(s) from {res.EndpointUsed} (sorted newest first).";
        }
        catch (OperationCanceledException)
        {
            // Superseded by newer query
        }
        catch (Exception ex)
        {
            _allLoadedThemes.Clear();
            CardsHost.Children.Clear();
            EmptyStateText.Visibility = Visibility.Visible;
            EmptyStateText.Text = "Unable to connect to Theme Store.";

            BannerBorder.Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF1E293B"));
            BannerBorder.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFF59E0B"));
            BannerTitleText.Text = $"Unable to reach {StoreUrlBox.Text}";
            BannerDetailText.Text = $"{ex.Message}\nCheck your internet connection or server status, then click 'Retry Store'.";
            BannerBorder.Visibility = Visibility.Visible;
            StatusText.Text = "Unable to connect to Theme Store.";
        }
    }

    private List<StoreThemeListing> FilterOwnedIfChecked(IEnumerable<StoreThemeListing> items)
    {
        if (MyThemesCheck.IsChecked == true)
            return items.Where(t => t.IsOwnedByCurrentUser).ToList();
        return items.ToList();
    }

    private void ApplyClientFilterAndSort()
    {
        string? cat = SelectedCategoryOrNull();
        string q = (SearchBox.Text ?? "").Trim();

        IEnumerable<StoreThemeListing> query = _allLoadedThemes;
        if (MyThemesCheck.IsChecked == true)
            query = query.Where(t => t.IsOwnedByCurrentUser);

        if (!string.IsNullOrWhiteSpace(cat))
            query = query.Where(t => string.Equals(t.Category?.Trim(), cat, StringComparison.OrdinalIgnoreCase));

        if (!string.IsNullOrWhiteSpace(q))
        {
            query = query.Where(t =>
                ContainsCI(t.Name, q)
                || ContainsCI(t.Author, q)
                || ContainsCI(t.Id, q)
                || ContainsCI(t.Category, q)
                || ContainsCI(t.Description, q)
                || ContainsCI(t.FriendlyCompatLabel, q)
                || (t.Tags != null && t.Tags.Any(tag => ContainsCI(tag, q))));
        }

        // Always sorted newest-first
        var sorted = query
            .OrderByDescending(t => t.ResolveSortDate())
            .ThenByDescending(t => t.Revision)
            .ThenBy(t => t.Name, StringComparer.OrdinalIgnoreCase)
            .ToList();

        RenderThemes(sorted);
        StatusText.Text = $"Showing {sorted.Count} theme(s) sorted newest first.";
    }

    private static bool ContainsCI(string? haystack, string needle) =>
        !string.IsNullOrEmpty(haystack) && haystack.Contains(needle, StringComparison.OrdinalIgnoreCase);

    private void RenderThemes(List<StoreThemeListing> themes)
    {
        CardsHost.Children.Clear();
        if (themes.Count == 0)
        {
            EmptyStateText.Visibility = Visibility.Visible;
            EmptyStateText.Text = "No themes match this category / search filter.";
            return;
        }

        EmptyStateText.Visibility = Visibility.Collapsed;
        foreach (var listing in themes)
            CardsHost.Children.Add(BuildThemeCard(listing));
    }

    private UIElement BuildThemeCard(StoreThemeListing t)
    {
        var card = new Border
        {
            Width = 342,
            Margin = new Thickness(6),
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF1E293B")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF334155")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(10),
            Padding = new Thickness(12)
        };

        var root = new StackPanel();
        var clickableBody = new StackPanel
        {
            Background = Brushes.Transparent,
            Cursor = Cursors.Hand,
            ToolTip = "Click to view enlarged thumbnail & full theme details"
        };

        // Thumbnail box (960:360 aspect -> ~316x118)
        var thumbGrid = new Grid
        {
            Height = 122,
            Margin = new Thickness(0, 0, 0, 8),
            ClipToBounds = true
        };
        var thumbBg = new Border
        {
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF020617")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF334155")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(6)
        };
        thumbGrid.Children.Add(thumbBg);

        var placeholder = new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(t.Name) ? "THEME" : t.Name,
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF475569")),
            FontWeight = FontWeights.Bold,
            FontSize = 14,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis,
            MaxWidth = 280
        };
        thumbGrid.Children.Add(placeholder);

        var thumbImg = new Image { Stretch = Stretch.UniformToFill };
        thumbGrid.Children.Add(thumbImg);
        if (!string.IsNullOrWhiteSpace(t.ThumbnailUrl))
            _ = LoadCardThumbnailAsync(t.ThumbnailUrl, thumbImg, placeholder);

        // Top-right Date pill
        var datePill = new Border
        {
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#CC0F172A")),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(6, 2, 6, 2),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(6)
        };
        datePill.Child = new TextBlock
        {
            Text = t.FriendlyDateLabel,
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF93C5FD")),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        thumbGrid.Children.Add(datePill);

        if (t.IsOwnedByCurrentUser)
        {
            var ownerPill = new Border
            {
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#CC1D4ED8")),
                CornerRadius = new CornerRadius(4),
                Padding = new Thickness(6, 2, 6, 2),
                HorizontalAlignment = HorizontalAlignment.Left,
                VerticalAlignment = VerticalAlignment.Bottom,
                Margin = new Thickness(6)
            };
            ownerPill.Child = new TextBlock
            {
                Text = "YOUR THEME",
                Foreground = Brushes.White,
                FontSize = 10,
                FontWeight = FontWeights.Bold
            };
            thumbGrid.Children.Add(ownerPill);
        }

        var enlargePill = new Border
        {
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#CC0F172A")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF38BDF8")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(6, 2, 6, 2),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Margin = new Thickness(6),
            Visibility = Visibility.Collapsed
        };
        enlargePill.Child = new TextBlock
        {
            Text = "Click to enlarge",
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFE0F2FE")),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        thumbGrid.Children.Add(enlargePill);

        clickableBody.Children.Add(thumbGrid);

        // Title + Version/Rev
        var titleRow = new DockPanel { LastChildFill = true, Margin = new Thickness(0, 0, 0, 2) };
        var verBadge = new Border
        {
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF0F172A")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF334155")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(5, 1, 5, 1),
            Margin = new Thickness(6, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center
        };
        verBadge.Child = new TextBlock
        {
            Text = $"v{t.Version} · r{Math.Max(1, t.Revision)}",
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF38BDF8")),
            FontSize = 10,
            FontWeight = FontWeights.SemiBold
        };
        DockPanel.SetDock(verBadge, Dock.Right);
        titleRow.Children.Add(verBadge);

        titleRow.Children.Add(new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(t.Name) ? t.Id : t.Name,
            Foreground = Brushes.White,
            FontSize = 15,
            FontWeight = FontWeights.Bold,
            TextTrimming = TextTrimming.CharacterEllipsis
        });
        clickableBody.Children.Add(titleRow);

        // Author + Downloads + Size
        string authorLine = $"by {(string.IsNullOrWhiteSpace(t.Author) ? "Unknown" : t.Author)}";
        if (t.Downloads is > 0) authorLine += $" · {t.Downloads} downloads";
        if (!string.IsNullOrWhiteSpace(t.FriendlySizeLabel)) authorLine += $" · {t.FriendlySizeLabel}";
        clickableBody.Children.Add(new TextBlock
        {
            Text = authorLine,
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF94A3B8")),
            FontSize = 11,
            Margin = new Thickness(0, 0, 0, 5)
        });

        // Prominent Category Pill + Compatibility Info row
        var metaRow = new WrapPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 0, 0, 6) };
        var catPill = new Border
        {
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF0F766E")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF14B8A6")),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(6, 1, 6, 1),
            Margin = new Thickness(0, 0, 7, 2),
            VerticalAlignment = VerticalAlignment.Center
        };
        catPill.Child = new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(t.Category) ? "Other" : t.Category.Trim(),
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFECFEFF")),
            FontSize = 10.5,
            FontWeight = FontWeights.Bold
        };
        metaRow.Children.Add(catPill);

        string compatText = t.FriendlyCompatLabel;
        if (t.ShowSystemBar.HasValue)
            compatText += t.ShowSystemBar.Value ? " · 90px Bar" : " · Fullscreen";
        metaRow.Children.Add(new TextBlock
        {
            Text = compatText,
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF34D399")),
            FontSize = 11,
            VerticalAlignment = VerticalAlignment.Center
        });
        clickableBody.Children.Add(metaRow);

        // Description
        var descBlock = new TextBlock
        {
            Text = string.IsNullOrWhiteSpace(t.Description) ? "No description provided." : t.Description.Trim(),
            Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFCBD5E1")),
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap,
            Height = 46,
            TextTrimming = TextTrimming.CharacterEllipsis,
            ToolTip = string.IsNullOrWhiteSpace(t.Description) ? null : t.Description,
            Margin = new Thickness(0, 0, 0, 10)
        };
        clickableBody.Children.Add(descBlock);

        clickableBody.MouseEnter += (_, _) =>
        {
            card.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF38BDF8"));
            enlargePill.Visibility = Visibility.Visible;
        };
        clickableBody.MouseLeave += (_, _) =>
        {
            card.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF334155"));
            enlargePill.Visibility = Visibility.Collapsed;
        };
        clickableBody.MouseLeftButtonUp += (_, _) => OpenThemeDetail(t);

        root.Children.Add(clickableBody);

        // Actions
        var btnGrid = new Grid();
        btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1.3, GridUnitType.Star) });
        btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(6) });
        btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.95, GridUnitType.Star) });

        var installBtn = new Button
        {
            Content = "Download & Install",
            Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF065F46")),
            BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF10B981")),
            IsEnabled = !string.IsNullOrWhiteSpace(t.DownloadUrl)
        };
        installBtn.Click += async (_, _) => await DownloadAndInstallAsync(t, installBtn);
        Grid.SetColumn(installBtn, 0);
        btnGrid.Children.Add(installBtn);

        var saveZipBtn = new Button
        {
            Content = "Save .zip...",
            IsEnabled = !string.IsNullOrWhiteSpace(t.DownloadUrl)
        };
        saveZipBtn.Click += async (_, _) => await DownloadAndSaveZipAsync(t, saveZipBtn);
        Grid.SetColumn(saveZipBtn, 2);
        btnGrid.Children.Add(saveZipBtn);

        if (t.IsOwnedByCurrentUser)
        {
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(6) });
            btnGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.75, GridUnitType.Star) });

            var deleteBtn = new Button
            {
                Content = "Delete",
                Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF7F1D1D")),
                BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FFEF4444"))
            };
            deleteBtn.Click += async (_, _) => await DeleteThemeAsync(t, deleteBtn);
            Grid.SetColumn(deleteBtn, 4);
            btnGrid.Children.Add(deleteBtn);
        }

        root.Children.Add(btnGrid);
        card.Child = root;
        return card;
    }

    private void OpenThemeDetail(StoreThemeListing listing)
    {
        byte[]? cachedBytes = null;
        if (!string.IsNullOrWhiteSpace(listing.ThumbnailUrl))
            _thumbnailBytesCache.TryGetValue(listing.ThumbnailUrl, out cachedBytes);

        var detailDlg = new ThemeDetailDialog(
            listing,
            cachedBytes,
            (item, btn) => DownloadAndInstallCoreAsync(item, btn, closeStoreWindowOnSuccess: false),
            DownloadAndSaveZipAsync,
            listing.IsOwnedByCurrentUser ? DeleteThemeAsync : null)
        {
            Owner = this
        };

        detailDlg.ShowDialog();
        if (detailDlg.InstalledFromDetail && !string.IsNullOrWhiteSpace(InstalledThemeDir))
        {
            DialogResult = true;
            Close();
        }
    }

    private async Task LoadCardThumbnailAsync(string url, Image targetImage, TextBlock placeholder)
    {
        try
        {
            if (!_thumbnailBytesCache.TryGetValue(url, out byte[]? bytes))
            {
                bytes = await ThemeStoreClient.DownloadThumbnailAsync(url);
                _thumbnailBytesCache[url] = bytes;
            }
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.DecodePixelWidth = 480;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();

            await targetImage.Dispatcher.InvokeAsync(() =>
            {
                targetImage.Source = bmp;
                placeholder.Visibility = Visibility.Collapsed;
            });
        }
        catch
        {
            // Keep placeholder if thumbnail fails or offline
        }
    }

    private Task DownloadAndInstallAsync(StoreThemeListing listing, Button btn) =>
        DownloadAndInstallCoreAsync(listing, btn, closeStoreWindowOnSuccess: true);

    private async Task<bool> DownloadAndInstallCoreAsync(StoreThemeListing listing, Button btn, bool closeStoreWindowOnSuccess)
    {
        if (string.IsNullOrWhiteSpace(listing.DownloadUrl)) return false;
        btn.IsEnabled = false;
        StatusText.Text = $"Downloading '{listing.Name}' and running security checks...";
        try
        {
            byte[] zipBytes = await ThemeStoreClient.DownloadPackageAsync(listing.DownloadUrl);
            var added = _workspace.ImportSingleThemeZipIntoCurrentPack(zipBytes, listing.Sha256);
            InstalledThemeDir = added.Directory;
            StatusText.Text = $"Verified and installed '{added.Name}' ({added.Id})!";
            MessageBox.Show(this,
                $"Safely verified & installed theme '{added.Name}'!\n\nFolder:\n{added.Directory}",
                "Theme Installed", MessageBoxButton.OK, MessageBoxImage.Information);
            if (closeStoreWindowOnSuccess)
            {
                DialogResult = true;
                Close();
            }
            return true;
        }
        catch (Exception ex)
        {
            StatusText.Text = "Install blocked / failed.";
            MessageBox.Show(this,
                $"Could not install '{listing.Name}':\n\n{ex.Message}",
                "Store Install Failed", MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
        finally
        {
            btn.IsEnabled = true;
        }
    }

    private async Task DownloadAndSaveZipAsync(StoreThemeListing listing, Button btn)
    {
        if (string.IsNullOrWhiteSpace(listing.DownloadUrl)) return;
        var sfd = new SaveFileDialog
        {
            Title = "Save Verified Theme Package (.zip)",
            Filter = "Gen5W Theme Package (*.zip)|*.zip",
            FileName = $"{PackWorkspaceService.SanitizeId(listing.Id)}_v{listing.Version}_r{Math.Max(1, listing.Revision)}.zip"
        };
        if (sfd.ShowDialog(this) != true) return;

        btn.IsEnabled = false;
        StatusText.Text = $"Downloading & verifying '{listing.Name}'...";
        string verifyTemp = Path.Combine(Path.GetTempPath(), "gen5w_verify_" + Guid.NewGuid().ToString("N")[..10]);
        try
        {
            byte[] zipBytes = await ThemeStoreClient.DownloadPackageAsync(listing.DownloadUrl);
            // Verify ZIP safety, single-theme guarantee, magic bytes, and actions before writing to user disk
            ThemePackageService.ExtractSingleThemeZipSafely(zipBytes, verifyTemp, listing.Sha256);
            await File.WriteAllBytesAsync(sfd.FileName, zipBytes);
            StatusText.Text = $"Saved verified theme ZIP to {sfd.FileName}";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Blocked / failed while verifying download:\n\n{ex.Message}",
                "Safety Verification Failed", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            if (Directory.Exists(verifyTemp))
            {
                try { Directory.Delete(verifyTemp, recursive: true); } catch { }
            }
            btn.IsEnabled = true;
        }
    }

    private async void OnImportLocalZipClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Import Single-Theme Package (.zip)",
            Filter = "Gen5W Theme Package (*.zip)|*.zip"
        };
        if (dlg.ShowDialog(this) != true) return;

        try
        {
            byte[] zipBytes = await File.ReadAllBytesAsync(dlg.FileName);
            var added = _workspace.ImportSingleThemeZipIntoCurrentPack(zipBytes);
            InstalledThemeDir = added.Directory;
            MessageBox.Show(this,
                $"Safety checks passed! Imported theme '{added.Name}' ({added.Id}).",
                "Imported Theme .zip", MessageBoxButton.OK, MessageBoxImage.Information);
            DialogResult = true;
            Close();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Rejected unsafe or invalid theme .zip:\n\n{ex.Message}",
                "Import Blocked", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnOpenUploadDialogClick(object sender, RoutedEventArgs e)
    {
        bool uploadedOrSaved = _openUploadFlow();
        LoadLocalSessionOnly();
        if (uploadedOrSaved)
            _ = FetchFromServerAsync();
    }

    private async Task<bool> DeleteThemeAsync(StoreThemeListing listing, Button btn)
    {
        string themeTitle = string.IsNullOrWhiteSpace(listing.Name) ? listing.Id : listing.Name;
        var confirm = MessageBox.Show(this,
            $"Delete '{themeTitle}' ({listing.Id}) from the Theme Store?\n\nThis will permanently remove the theme from the Theme Store.",
            "Confirm Theme Deletion",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (confirm != MessageBoxResult.Yes) return false;

        if (_activeSession == null || _activeSession.IsExpired())
        {
            var authDlg = new StoreAuthDialog(StoreUrlBox.Text, bannerMessage: "Please sign in to delete your theme.") { Owner = this };
            if (authDlg.ShowDialog() != true || authDlg.AuthenticatedSession == null) return false;
            _activeSession = authDlg.AuthenticatedSession;
            UpdateAccountUi(sessionExpired: false);
        }

        btn.IsEnabled = false;
        StatusText.Text = $"Deleting '{themeTitle}' from Theme Store...";
        try
        {
            var resp = await ThemeStoreClient.DeleteThemeAsync(StoreUrlBox.Text, listing.Id, _activeSession.Token);
            _allLoadedThemes.RemoveAll(t => string.Equals(t.Id, listing.Id, StringComparison.OrdinalIgnoreCase));
            ApplyClientFilterAndSort();
            StatusText.Text = resp.Message ?? $"Deleted '{themeTitle}' from Theme Store.";
            MessageBox.Show(this, resp.Message ?? $"Deleted '{themeTitle}' from the Theme Store.",
                "Theme Deleted", MessageBoxButton.OK, MessageBoxImage.Information);
            await FetchFromServerAsync();
            return true;
        }
        catch (ThemeStoreApiException apiEx)
        {
            if (apiEx.StatusCode == HttpStatusCode.Unauthorized)
            {
                StoreAuthStorage.ClearSession(StoreUrlBox.Text);
                _activeSession = null;
                UpdateAccountUi(sessionExpired: true);
                StatusText.Text = "Session expired — sign in again to delete themes.";
                var relogin = MessageBox.Show(this,
                    $"{apiEx.FormatFriendlyMessage()}\n\nSign in again now?",
                    "Sign In Required", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (relogin == MessageBoxResult.Yes)
                    OnSignInClick(this, new RoutedEventArgs());
            }
            else if (apiEx.StatusCode == HttpStatusCode.NotFound)
            {
                _allLoadedThemes.RemoveAll(t => string.Equals(t.Id, listing.Id, StringComparison.OrdinalIgnoreCase));
                ApplyClientFilterAndSort();
                StatusText.Text = $"'{themeTitle}' was already removed from the Theme Store.";
                return true;
            }
            else
            {
                StatusText.Text = "Delete failed.";
                MessageBox.Show(this, apiEx.FormatFriendlyMessage(), "Delete Theme Failed",
                    MessageBoxButton.OK, MessageBoxImage.Error);
            }
            return false;
        }
        catch (Exception ex)
        {
            StatusText.Text = "Delete failed.";
            MessageBox.Show(this, $"Could not delete '{themeTitle}':\n\n{ex.Message}",
                "Delete Theme Error", MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
        finally
        {
            btn.IsEnabled = true;
        }
    }

    private async void OnSignInClick(object sender, RoutedEventArgs e)
    {
        var authDlg = new StoreAuthDialog(StoreUrlBox.Text) { Owner = this };
        if (authDlg.ShowDialog() == true && authDlg.AuthenticatedSession != null)
        {
            _activeSession = authDlg.AuthenticatedSession;
            UpdateAccountUi(sessionExpired: false);
            StatusText.Text = $"Signed in as @{_activeSession.Username}.";
            await FetchFromServerAsync();
        }
    }

    private async void OnSignOutClick(object sender, RoutedEventArgs e)
    {
        StoreAuthStorage.ClearSession(StoreUrlBox.Text);
        _activeSession = null;
        MyThemesCheck.IsChecked = false;
        UpdateAccountUi(sessionExpired: false);
        StatusText.Text = "Signed out from Theme Store.";
        await FetchFromServerAsync();
    }

    private void OnMyThemesFilterChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        ApplyClientFilterAndSort();
    }

    private async void OnStoreUrlLostFocus(object sender, RoutedEventArgs e)
    {
        string? newOrigin = null;
        try { newOrigin = StoreAuthStorage.NormalizeOrigin(StoreUrlBox.Text); } catch { }
        if (!string.Equals(newOrigin, _loadedOrigin, StringComparison.OrdinalIgnoreCase))
        {
            LoadLocalSessionOnly();
            await InitializeStoreAsync();
        }
    }

    private async void OnCategoryChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_suppressCategoryChange || !IsLoaded) return;
        await FetchFromServerAsync();
    }

    private void OnSearchTextChanged(object sender, TextChangedEventArgs e)
    {
        if (!IsLoaded) return;
        if (_allLoadedThemes.Count > 0)
            ApplyClientFilterAndSort();
    }

    private async void OnSearchBoxKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key != Key.Enter) return;
        await FetchFromServerAsync();
    }

    private async void OnSearchClick(object sender, RoutedEventArgs e)
    {
        await FetchFromServerAsync();
    }

    private async void OnRefreshClick(object sender, RoutedEventArgs e)
    {
        await FetchFromServerAsync();
    }

    private async void OnResetUrlClick(object sender, RoutedEventArgs e)
    {
        StoreUrlBox.Text = ThemeStoreClient.DefaultBaseUrl;
        LoadLocalSessionOnly();
        await InitializeStoreAsync();
    }

    private void OnCloseClick(object sender, RoutedEventArgs e)
    {
        DialogResult = !string.IsNullOrWhiteSpace(InstalledThemeDir);
        Close();
    }
}
