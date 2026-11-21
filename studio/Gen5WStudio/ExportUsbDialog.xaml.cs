using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using Gen5WStudio.Models;
using Gen5WStudio.Services;
using Microsoft.Win32;

namespace Gen5WStudio;

public sealed class PackThemeRowItem : INotifyPropertyChanged
{
    private bool _isChecked;
    public ThemeLibraryEntry Entry { get; }
    public string Title => Entry.Name;
    public string Subtitle => Entry.IsBuiltInStarter && Entry.Id == "default"
        ? $"id: {Entry.Id} (Bundled inside Launcher APK)"
        : $"id: {Entry.Id}";
    public string Badge => Entry.IsBuiltInStarter ? "STARTER" : "THEME";
    public Visibility DeleteVisibility { get; }

    public bool IsChecked
    {
        get => _isChecked;
        set
        {
            if (_isChecked != value)
            {
                _isChecked = value;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(IsChecked)));
            }
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public PackThemeRowItem(ThemeLibraryEntry entry, bool initialChecked, bool canDelete)
    {
        Entry = entry;
        _isChecked = initialChecked;
        DeleteVisibility = canDelete ? Visibility.Visible : Visibility.Collapsed;
    }
}

public partial class ExportUsbDialog : Window
{
    private readonly PackWorkspaceService workspace;
    private readonly ThemePackModel? activePack;
    private readonly ObservableCollection<PackThemeRowItem> rows = new();
    private InstallerBundleInfo? activeInstaller;

    public bool PackWorkspaceChanged { get; private set; }
    public string? ResultMessage { get; private set; }

    public ExportUsbDialog(PackWorkspaceService workspace, ThemePackModel? activePack)
    {
        this.workspace = workspace;
        this.activePack = activePack;
        InitializeComponent();

        ThemeRowsList.ItemsSource = rows;

        bool hasThemes = workspace.HasActiveTheme && activePack != null;
        IncludeThemesCheck.IsChecked = hasThemes;
        IncludeNemesisInstallerCheck.IsChecked = !hasThemes;

        ReloadRows();
        LoadDefaultInstaller();
        RefreshUsbDrives();
        UpdateActionButtonsState();
    }

    private void LoadDefaultInstaller()
    {
        activeInstaller = NemesisInstallerService.ResolveDefaultInstaller();
        ResetInstallerBtn.Visibility = Visibility.Collapsed;
        UpdateInstallerLabel();
    }

    private void UpdateInstallerLabel()
    {
        if (activeInstaller == null)
        {
            InstallerSourceText.Text = "No NEMESIS installer archive found — click Browse .zip... to select a gen5w-launcher-*-nemesis.zip.";
            InstallerSourceText.Foreground = System.Windows.Media.Brushes.Orange;
        }
        else
        {
            InstallerSourceText.Text = $"Ready: v{activeInstaller.Version}  ({activeInstaller.DisplaySource})";
            InstallerSourceText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(167, 243, 208));
        }
    }

    private void RefreshUsbDrives()
    {
        string? prevRoot = (UsbDriveCombo.SelectedItem as ComboBoxItem)?.Tag is DriveInfo prevD ? prevD.Name : null;
        var drives = PackWorkspaceService.DetectRemovableUsbDrives();
        UsbDriveCombo.Items.Clear();

        foreach (var d in drives)
        {
            string label = string.IsNullOrWhiteSpace(d.VolumeLabel) ? "" : $" ({d.VolumeLabel})";
            double freeGb = d.AvailableFreeSpace / (1024.0 * 1024.0 * 1024.0);
            UsbDriveCombo.Items.Add(new ComboBoxItem
            {
                Content = $"{d.Name.TrimEnd('\\')}{label} — {freeGb:0.0} GB free",
                Tag = d
            });
        }

        if (UsbDriveCombo.Items.Count > 0)
        {
            int selectIdx = 0;
            if (!string.IsNullOrEmpty(prevRoot))
            {
                for (int i = 0; i < UsbDriveCombo.Items.Count; i++)
                {
                    if (UsbDriveCombo.Items[i] is ComboBoxItem ci && ci.Tag is DriveInfo di
                        && string.Equals(di.Name, prevRoot, StringComparison.OrdinalIgnoreCase))
                    {
                        selectIdx = i;
                        break;
                    }
                }
            }
            UsbDriveCombo.SelectedIndex = selectIdx;
            var selDrive = (DriveInfo)((ComboBoxItem)UsbDriveCombo.SelectedItem).Tag;
            UsbStatusText.Text = $"Target USB ready: {selDrive.Name}";
            UsbStatusText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(52, 211, 153));
        }
        else
        {
            UsbDriveCombo.Items.Add(new ComboBoxItem { Content = "No removable USB drive detected" });
            UsbDriveCombo.SelectedIndex = 0;
            UsbStatusText.Text = "Plug in a FAT32/exFAT USB drive and click Refresh USB Drives.";
            UsbStatusText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(251, 191, 36));
        }

        UpdateActionButtonsState();
    }

    private void ReloadRows()
    {
        rows.Clear();
        PackPathSummary.Text = workspace.HasActivePack
            ? $"Active Pack Folder: {workspace.ActivePackDir}"
            : (workspace.HasActiveTheme
                ? $"Active Theme Folder: {workspace.WorkspaceDir}"
                : "No active theme/pack open.");

        var entries = workspace.HasActiveTheme ? workspace.ListActivePackThemes() : new List<ThemeLibraryEntry>();
        foreach (var e in entries)
        {
            bool canDel = !workspace.IsSingleThemeMode && entries.Count > 1;
            rows.Add(new PackThemeRowItem(e, initialChecked: true, canDelete: canDel));
        }

        NoThemesNotice.Visibility = rows.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        bool hasAnyTheme = rows.Count > 0;
        IncludeThemesCheck.IsEnabled = hasAnyTheme;
        if (!hasAnyTheme) IncludeThemesCheck.IsChecked = false;
        SelectAllBtn.IsEnabled = hasAnyTheme;
        ClearAllBtn.IsEnabled = hasAnyTheme;
        PruneCheck.IsEnabled = hasAnyTheme && IncludeThemesCheck.IsChecked == true;
        SavePackFolderBtn.IsEnabled = hasAnyTheme && activePack != null && rows.Any(r => r.IsChecked);

        UpdateCountLabel();
        UpdateActionButtonsState();
    }

    private void UpdateCountLabel()
    {
        int n = rows.Count(r => r.IsChecked);
        CountText.Text = $"{n} theme(s) selected";
        SavePackFolderBtn.IsEnabled = activePack != null && n > 0;
    }

    private void UpdateActionButtonsState()
    {
        if (!IsInitialized) return;
        DriveInfo? drive = (UsbDriveCombo.SelectedItem as ComboBoxItem)?.Tag as DriveInfo;
        bool hasUsb = drive != null;
        bool wantThemes = IncludeThemesCheck.IsChecked == true && rows.Any(r => r.IsChecked) && activePack != null;
        bool wantInstaller = IncludeNemesisInstallerCheck.IsChecked == true && activeInstaller != null;

        PruneCheck.IsEnabled = IncludeThemesCheck.IsChecked == true && rows.Count > 0;
        if (drive != null)
        {
            ExportUsbBtn.Content = $"Export to USB ({drive.Name.TrimEnd('\\')})";
        }
        else
        {
            ExportUsbBtn.Content = "Export to USB";
        }
        ExportUsbBtn.IsEnabled = hasUsb && (wantThemes || wantInstaller);
    }

    public (int ExportedThemes, string? InstallerVersion, List<string> InstallerFiles) ExportToDirectory(
        string targetRootDir,
        bool includeThemes,
        bool includeInstaller)
    {
        int themeCount = 0;
        string? installedVer = null;
        var installerFiles = new List<string>();

        if (includeThemes)
        {
            if (activePack == null) throw new InvalidOperationException("No active theme pack is loaded.");
            var chosen = rows.Where(r => r.IsChecked).Select(r => r.Entry).ToList();
            if (chosen.Count == 0) throw new InvalidOperationException("Select at least one theme to export.");
            string packsRoot = Path.Combine(targetRootDir, "Gen5WLauncher", "packs");
            themeCount = workspace.ExportSelectedThemesToDirectory(activePack, chosen, packsRoot, PruneCheck.IsChecked == true);
        }

        if (includeInstaller)
        {
            if (activeInstaller == null)
                throw new InvalidOperationException("No valid Gen5W Launcher NEMESIS installer ZIP is selected.");
            var (ver, files) = NemesisInstallerService.ExtractInstallerToUsbRoot(activeInstaller, targetRootDir);
            installedVer = ver;
            installerFiles.AddRange(files);
        }

        return (themeCount, installedVer, installerFiles);
    }

    private void OnUsbSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (UsbDriveCombo.SelectedItem is ComboBoxItem ci && ci.Tag is DriveInfo di)
        {
            UsbStatusText.Text = $"Target USB ready: {di.Name}";
            UsbStatusText.Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(52, 211, 153));
        }
        UpdateActionButtonsState();
    }

    private void OnRefreshUsbClick(object sender, RoutedEventArgs e) => RefreshUsbDrives();

    private void OnExportModeChanged(object sender, RoutedEventArgs e) => UpdateActionButtonsState();

    private void OnSelectAllClick(object sender, RoutedEventArgs e)
    {
        foreach (var r in rows) r.IsChecked = true;
        UpdateCountLabel();
        UpdateActionButtonsState();
    }

    private void OnClearAllClick(object sender, RoutedEventArgs e)
    {
        foreach (var r in rows) r.IsChecked = false;
        UpdateCountLabel();
        UpdateActionButtonsState();
    }

    private void OnRowCheckChanged(object sender, RoutedEventArgs e)
    {
        UpdateCountLabel();
        UpdateActionButtonsState();
    }

    private void OnImportThemeIntoPackClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFolderDialog { Title = "Select an Existing Theme Folder (contains layout.json) to Add" };
        if (dlg.ShowDialog(this) != true) return;
        if (!File.Exists(Path.Combine(dlg.FolderName, "layout.json")))
        {
            MessageBox.Show(this, "Selected folder does not contain layout.json.", "Import Theme");
            return;
        }
        if (!workspace.HasActiveTheme)
        {
            MessageBox.Show(this, "Open or create a theme/pack first before importing additional themes into it.", "No Active Pack");
            return;
        }
        try
        {
            if (activePack != null && workspace.HasActiveTheme)
                workspace.SaveCurrentTheme(activePack);
            var added = workspace.StageThemeFolderIntoCurrentPack(dlg.FolderName);
            PackWorkspaceChanged = true;
            ReloadRows();
            IncludeThemesCheck.IsChecked = true;
            ResultMessage = $"Added theme '{added.Name}' into current pack";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Import Theme Error");
        }
    }

    private void OnImportThemeZipClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select a Verified Single-Theme Package (.zip) to Add",
            Filter = "Gen5W Theme Package (*.zip)|*.zip"
        };
        if (dlg.ShowDialog(this) != true) return;
        if (!workspace.HasActiveTheme)
        {
            MessageBox.Show(this, "Open or create a theme/pack first before importing themes into it.", "No Active Pack");
            return;
        }
        try
        {
            if (activePack != null && workspace.HasActiveTheme)
                workspace.SaveCurrentTheme(activePack);
            byte[] zipBytes = File.ReadAllBytes(dlg.FileName);
            var added = workspace.StageSingleThemeZipIntoCurrentPack(zipBytes);
            PackWorkspaceChanged = true;
            ReloadRows();
            IncludeThemesCheck.IsChecked = true;
            ResultMessage = $"Verified & added theme '{added.Name}' from .zip into current pack";
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Blocked or invalid theme .zip:\n\n" + ex.Message, "Import .zip Error",
                MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnDeleteRowClick(object sender, RoutedEventArgs e)
    {
        if (sender is not Button btn || btn.Tag is not PackThemeRowItem item) return;
        var res = MessageBox.Show(
            this,
            $"Delete theme '{item.Title}' ({item.Entry.Id}) from the active pack folder?\n\n{item.Entry.Directory}",
            "Remove Theme",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);
        if (res != MessageBoxResult.Yes) return;

        if (workspace.DeleteThemeFromActivePack(item.Entry))
        {
            PackWorkspaceChanged = true;
            ReloadRows();
        }
    }

    private void OnBrowseInstallerZipClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog
        {
            Title = "Select Gen5W Launcher NEMESIS USB Installer (.zip)",
            Filter = "NEMESIS USB Installer (*.zip)|*.zip"
        };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            activeInstaller = NemesisInstallerService.LoadCustomInstaller(dlg.FileName);
            ResetInstallerBtn.Visibility = Visibility.Visible;
            IncludeNemesisInstallerCheck.IsChecked = true;
            UpdateInstallerLabel();
            UpdateActionButtonsState();
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Invalid NEMESIS Installer", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private void OnResetInstallerClick(object sender, RoutedEventArgs e)
    {
        LoadDefaultInstaller();
        UpdateActionButtonsState();
    }

    private void OnSavePackFolderClick(object sender, RoutedEventArgs e)
    {
        if (activePack == null) return;
        var chosen = rows.Where(r => r.IsChecked).Select(r => r.Entry).ToList();
        if (chosen.Count == 0)
        {
            MessageBox.Show(this, "Check at least one theme to include in your pack.", "Save Pack");
            return;
        }
        var dlg = new OpenFolderDialog { Title = "Select Destination Folder for Your Multi-Theme Pack" };
        if (dlg.ShowDialog(this) != true) return;

        try
        {
            string leaf = Path.GetFileName(dlg.FolderName.TrimEnd('\\', '/'));
            string packsTarget = string.Equals(leaf, "packs", StringComparison.OrdinalIgnoreCase)
                ? dlg.FolderName
                : Path.Combine(dlg.FolderName, "packs");
            int n = workspace.ExportSelectedThemesToDirectory(activePack, chosen, packsTarget, PruneCheck.IsChecked == true);
            ResultMessage = $"Saved {n} theme(s) into pack -> {dlg.FolderName}";
            MessageBox.Show(this, $"Saved {n} selected theme(s) cleanly into:\n{packsTarget}\n\nNext time you click Open... on '{dlg.FolderName}', ONLY those {n} theme(s) will load.", "Pack Saved");
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Save Pack Error");
        }
    }

    private void OnExportToUsbClick(object sender, RoutedEventArgs e)
    {
        if (UsbDriveCombo.SelectedItem is not ComboBoxItem item || item.Tag is not DriveInfo drive)
        {
            RefreshUsbDrives();
            MessageBox.Show(this, "Plug in a USB drive first, then click Refresh USB Drives.", "No USB Drive");
            return;
        }

        bool wantThemes = IncludeThemesCheck.IsChecked == true;
        bool wantInstaller = IncludeNemesisInstallerCheck.IsChecked == true;
        if (!wantThemes && !wantInstaller)
        {
            MessageBox.Show(this, "Select themes to export and/or check the NEMESIS installer option.", "Nothing Selected");
            return;
        }

        string usbRoot = drive.RootDirectory.FullName;
        if (wantInstaller && NemesisInstallerService.HasUnrelatedNemesisInstallOnUsb(usbRoot, out string existingData))
        {
            var overwrite = MessageBox.Show(
                this,
                $"Another NEMESIS.data file was found at {usbRoot}NEMESIS.data:\n\n{existingData}\n\nReplace it with the Gen5W Custom Launcher installer?",
                "Replace Existing NEMESIS.data?",
                MessageBoxButton.YesNo,
                MessageBoxImage.Warning);
            if (overwrite != MessageBoxResult.Yes) return;
        }

        try
        {
            var (themeCount, ver, instFiles) = ExportToDirectory(usbRoot, wantThemes, wantInstaller);
            var lines = new List<string>();
            if (themeCount > 0)
            {
                string packsRoot = Path.Combine(usbRoot, "Gen5WLauncher", "packs");
                lines.Add($"Themes exported ({themeCount}):\n  {packsRoot}");
            }
            if (ver != null)
            {
                lines.Add($"NEMESIS Launcher Installer (v{ver}) staged at USB root:\n  " + string.Join("\n  ", instFiles));
            }
            string summary = string.Join("\n\n", lines);
            ResultMessage = $"USB ({drive.Name.TrimEnd('\\')}) updated" +
                (themeCount > 0 ? $": {themeCount} theme(s)" : "") +
                (ver != null ? $" + NEMESIS installer v{ver}" : "");

            MessageBox.Show(
                this,
                $"{summary}\n\nNext steps on the car:\n" +
                (ver != null ? "• Plug USB into the car to install/update Gen5W Launcher via NEMESIS.\n" : "") +
                (themeCount > 0 ? "• Open ThemePacks / Theme Store -> Local / USB -> Import & Apply." : ""),
                "USB Ready");
            DialogResult = true;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "USB Export Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }
}
