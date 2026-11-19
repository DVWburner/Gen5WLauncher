using System;
using System.Globalization;
using System.IO;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Gen5WStudio.Models;
using Gen5WStudio.Services;

namespace Gen5WStudio;

public partial class ThemeDetailDialog : Window
{
    private readonly StoreThemeListing _listing;
    private readonly byte[]? _cachedThumbnailBytes;
    private readonly Func<StoreThemeListing, Button, Task<bool>> _onInstallAsync;
    private readonly Func<StoreThemeListing, Button, Task> _onSaveZipAsync;
    private readonly Func<StoreThemeListing, Button, Task<bool>>? _onDeleteAsync;
    private bool _thumbnailLoaded;

    public bool InstalledFromDetail { get; private set; }
    public bool DeletedFromDetail { get; private set; }

    public ThemeDetailDialog(
        StoreThemeListing listing,
        byte[]? cachedThumbnailBytes,
        Func<StoreThemeListing, Button, Task<bool>> onInstallAsync,
        Func<StoreThemeListing, Button, Task> onSaveZipAsync,
        Func<StoreThemeListing, Button, Task<bool>>? onDeleteAsync = null)
    {
        InitializeComponent();

        _listing = listing;
        _cachedThumbnailBytes = cachedThumbnailBytes;
        _onInstallAsync = onInstallAsync;
        _onSaveZipAsync = onSaveZipAsync;
        _onDeleteAsync = onDeleteAsync;

        BindDetails();
        if (_cachedThumbnailBytes != null && _cachedThumbnailBytes.Length > 0)
            DisplayNativeThumbnail(_cachedThumbnailBytes);
        else if (string.IsNullOrWhiteSpace(_listing.ThumbnailUrl))
            PreviewPlaceholderText.Text = "No preview screenshot provided for this theme.";

        Loaded += async (_, _) => await EnsureThumbnailLoadedAsync();
    }

    private void BindDetails()
    {
        string displayName = string.IsNullOrWhiteSpace(_listing.Name) ? _listing.Id : _listing.Name;
        string category = string.IsNullOrWhiteSpace(_listing.Category) ? "Other" : _listing.Category.Trim();
        string author = string.IsNullOrWhiteSpace(_listing.Author) ? "Unknown" : _listing.Author.Trim();
        int rev = Math.Max(1, _listing.Revision);

        Title = $"{displayName} — Theme Preview";
        TitleText.Text = displayName;
        SubtitleAuthorText.Text = $"by {author}";
        CategoryBadgeText.Text = category;
        VersionBadgeText.Text = $"v{_listing.Version} · rev {rev}";
        OwnerBadgeBorder.Visibility = _listing.IsOwnedByCurrentUser ? Visibility.Visible : Visibility.Collapsed;

        CategoryDetailText.Text = category;
        AuthorDetailText.Text = author;
        IdDetailText.Text = string.IsNullOrWhiteSpace(_listing.Id) ? "—" : _listing.Id;
        VersionDetailText.Text = $"v{_listing.Version} (revision {rev})";
        CompatDetailText.Text = $"{_listing.FriendlyCompatLabel} (Format v{Math.Max(1, _listing.FormatVersion)})";
        SystemBarDetailText.Text = _listing.ShowSystemBar switch
        {
            true => "90px System Bar",
            false => "Fullscreen (No Bar)",
            null => "Not specified"
        };

        string updated = FormatFullTimestamp(_listing.UpdatedAt);
        string created = FormatFullTimestamp(_listing.CreatedAt);
        DatesDetailText.Text = !string.IsNullOrEmpty(created) && created != updated
            ? $"Updated: {updated}\nCreated: {created}"
            : (!string.IsNullOrEmpty(updated) ? updated : _listing.FriendlyDateLabel);

        string sizeLabel = string.IsNullOrWhiteSpace(_listing.FriendlySizeLabel) ? "Unknown size" : _listing.FriendlySizeLabel;
        string dlLabel = _listing.Downloads.HasValue ? $"{_listing.Downloads.Value} download(s)" : "Downloads N/A";
        SizeDownloadsDetailText.Text = $"{sizeLabel} · {dlLabel}";

        if (!string.IsNullOrWhiteSpace(_listing.Sha256))
        {
            string sha = _listing.Sha256.Trim();
            Sha256DetailText.Text = sha.Length > 18 ? sha[..18] + "…" : sha;
            Sha256DetailText.ToolTip = sha;
        }
        else
        {
            Sha256DetailText.Text = "Not provided";
        }

        if (_listing.Tags != null && _listing.Tags.Count > 0)
        {
            TagsHost.Children.Clear();
            foreach (var tag in _listing.Tags)
            {
                if (string.IsNullOrWhiteSpace(tag)) continue;
                var chip = new Border
                {
                    Background = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF1E293B")),
                    BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF334155")),
                    BorderThickness = new Thickness(1),
                    CornerRadius = new CornerRadius(4),
                    Padding = new Thickness(8, 3, 8, 3),
                    Margin = new Thickness(0, 0, 6, 6)
                };
                chip.Child = new TextBlock
                {
                    Text = "#" + tag.Trim(),
                    Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString("#FF93C5FD")),
                    FontSize = 11
                };
                TagsHost.Children.Add(chip);
            }
            TagsSection.Visibility = TagsHost.Children.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        DescriptionText.Text = string.IsNullOrWhiteSpace(_listing.Description)
            ? "No description provided."
            : _listing.Description.Trim();

        bool canDownload = !string.IsNullOrWhiteSpace(_listing.DownloadUrl);
        InstallBtn.IsEnabled = canDownload;
        SaveZipBtn.IsEnabled = canDownload;
        DeleteBtn.Visibility = (_listing.IsOwnedByCurrentUser && _onDeleteAsync != null)
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private static string FormatFullTimestamp(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return "";
        if (DateTimeOffset.TryParse(raw, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var dt))
            return dt.ToLocalTime().ToString("yyyy-MM-dd HH:mm");
        return raw.Trim();
    }

    private void DisplayNativeThumbnail(byte[] bytes)
    {
        try
        {
            using var ms = new MemoryStream(bytes);
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.StreamSource = ms;
            bmp.EndInit();
            bmp.Freeze();

            PreviewImage.Source = bmp;
            PreviewPlaceholderText.Visibility = Visibility.Collapsed;
            PreviewDimText.Text = $"{bmp.PixelWidth} × {bmp.PixelHeight} px";
            _thumbnailLoaded = true;
        }
        catch
        {
            PreviewPlaceholderText.Text = "Preview image could not be decoded.";
        }
    }

    private async Task EnsureThumbnailLoadedAsync()
    {
        if (_thumbnailLoaded || string.IsNullOrWhiteSpace(_listing.ThumbnailUrl)) return;
        try
        {
            byte[] bytes = await ThemeStoreClient.DownloadThumbnailAsync(_listing.ThumbnailUrl);
            if (!IsLoaded) return;
            await Dispatcher.InvokeAsync(() => DisplayNativeThumbnail(bytes));
        }
        catch
        {
            if (IsLoaded)
                await Dispatcher.InvokeAsync(() => PreviewPlaceholderText.Text = "Could not load preview screenshot.");
        }
    }

    private async void OnInstallClick(object sender, RoutedEventArgs e)
    {
        bool installed = await _onInstallAsync(_listing, InstallBtn);
        if (installed)
        {
            InstalledFromDetail = true;
            DialogResult = true;
            Close();
        }
    }

    private async void OnSaveZipClick(object sender, RoutedEventArgs e)
    {
        await _onSaveZipAsync(_listing, SaveZipBtn);
    }

    private async void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        if (_onDeleteAsync == null) return;
        bool deleted = await _onDeleteAsync(_listing, DeleteBtn);
        if (deleted)
        {
            DeletedFromDetail = true;
            Close();
        }
    }
}
