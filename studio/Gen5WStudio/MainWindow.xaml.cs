using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media.Imaging;
using Gen5WStudio.Controls;
using Gen5WStudio.Models;
using Gen5WStudio.Services;
using Microsoft.Win32;

namespace Gen5WStudio;

public partial class MainWindow : Window
{
    private readonly PackWorkspaceService workspace;
    private readonly TelemetrySimState sim = new();
    private ThemePackModel currentPack = new();
    private LayoutNodeModel? selectedNode;
    private bool hasActiveTheme = false;
    private readonly string? recentStoragePath;
    private bool suppressEvents = false;
    private bool suppressThemeSelect = false;

    internal bool HasActiveTheme => hasActiveTheme && workspace.HasActiveTheme;
    private Point treeDragStart;
    private LayoutNodeModel? treeDragNode;

    private string inspectorAutoW = "";
    private string inspectorAutoH = "";
    private string inspectorLastWText = "";
    private string inspectorLastHText = "";
    private string inspectorLastFlexText = "";
    private string inspectorLastColSpanText = "";
    private string inspectorLastRowSpanText = "";
    private string inspectorLastMarginText = "";
    private string inspectorLastBoundsWText = "";
    private string inspectorLastBoundsHText = "";
    private string inspectorLastTextColorText = "";
    private string inspectorLastSubColorText = "";
    private string inspectorLastBgColorText = "";
    private string inspectorLastBorderColorText = "";
    private string inspectorLastAccentColorText = "";
    private string inspectorLastRadiusText = "";
    private const int MaxUndoSteps = 50;
    private readonly List<string> undoStack = new();
    private readonly List<string> redoStack = new();
    private string? lastSavedUndoJson;
    private static readonly System.Windows.Media.Brush ExplicitValueBrush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(241, 245, 249));
    private static readonly System.Windows.Media.Brush AutoValueBrush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(148, 163, 184));

    public MainWindow() : this(new PackWorkspaceService(), null)
    {
    }

    internal MainWindow(PackWorkspaceService workspaceService, string? recentFilePath = null)
    {
        workspace = workspaceService;
        recentStoragePath = recentFilePath;
        InitializeComponent();
        InitDropdowns();
        StudioCanvas.NodeSelected += OnCanvasNodeSelected;
        StudioCanvas.PackMutated += OnCanvasPackMutated;
        PreviewKeyDown += OnMainWindowPreviewKeyDown;
        Closing += (_, _) =>
        {
            if (HasActiveTheme)
                try { workspace.SaveCurrentTheme(currentPack); } catch { }
        };

        var themes = workspace.HasActiveTheme ? workspace.ListActivePackThemes() : new List<ThemeLibraryEntry>();
        if (workspace.HasActiveTheme && themes.Count > 0)
        {
            currentPack = workspace.SwitchToThemeDirectory(themes[0].Directory);
            currentPack.Normalize();
            hasActiveTheme = true;
            ResetUndoHistory();
            ApplyWorkspaceActiveState();
            BindWholePack(currentPack.Root);
            RefreshThemeSelectorCombo(workspace.WorkspaceDir);
            WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
        }
        else
        {
            hasActiveTheme = false;
            ApplyWorkspaceActiveState();
        }
    }

    private void InitDropdowns()
    {
        TelemetryKeyCombo.ItemsSource = PresetCatalog.TelemetrySensors;
        PresetCombo.ItemsSource = PresetCatalog.ActionPresets;
        IdleEffectCombo.ItemsSource = PresetCatalog.IdleEffects;
        BgModeCombo.ItemsSource = PresetCatalog.BackgroundTypes;
        PackCategoryCombo.ItemsSource = PresetCatalog.ThemeCategories;
        RefreshIconsDropdown();
        RefreshFontsDropdown();
        InitColorPickers();
    }

    private void InitColorPickers()
    {
        Action applyNode = CommitInspectorToSelectedNode;
        Action applyMeta = () => OnThemeMetaApplied(this, new RoutedEventArgs());

        AttachColorPicker(TextColorBox, "#FFF1F5F9", applyNode);
        AttachColorPicker(SubColorBox, "#FF94A3B8", applyNode);
        AttachColorPicker(IconTintBox, "#FFFFFFFF", applyNode);
        AttachColorPicker(IndActiveTextBox, "#FFFFFFFF", applyNode);
        AttachColorPicker(IndActiveFillBox, "#6438BDF8", applyNode);
        AttachColorPicker(IndActiveBorderBox, "#FF38BDF8", applyNode);
        AttachColorPicker(IndInactiveTextBox, "#FFCBD5E1", applyNode);
        AttachColorPicker(IndInactiveFillBox, "#3C1E293B", applyNode);
        AttachColorPicker(IndInactiveBorderBox, "#5A64748B", applyNode);
        AttachColorPicker(BgColorBox, "#D4111D2D", applyNode);
        AttachColorPicker(BgEndBox, "#D4162840", applyNode);
        AttachColorPicker(BorderColorBox, "#4438BDF8", applyNode);
        AttachColorPicker(AccentColorBox, "#FF38BDF8", applyNode);

        AttachColorPicker(BgPrimaryBox, "#FF060B12", applyMeta);
        AttachColorPicker(BgSecondaryBox, "#FF0D1E34", applyMeta);
        AttachColorPicker(BgAccentBox, "#FF195F91", applyMeta);
        AttachColorPicker(BgDimBox, "#48060B12", applyMeta);
    }

    private void AttachColorPicker(TextBox box, string defaultSeedHex, Action applyEdit)
    {
        box.TextChanged += (_, _) => UpdateColorSwatchVisual(box);
        box.Loaded += (_, _) =>
        {
            UpdateColorSwatchVisual(box);
            if (box.Template?.FindName("PART_SwatchBtn", box) is Button btn && btn.Tag is not string)
            {
                btn.Tag = "hooked";
                btn.Click += (_, _) => OpenColorPickerForBox(box, defaultSeedHex, applyEdit);
            }
        };
    }

    private static void UpdateColorSwatchVisual(TextBox box)
    {
        if (box.Template?.FindName("PART_SwatchBtn", box) is not Button btn) return;
        btn.Background = ColorPickerDialog.TryParseColor(box.Text, out System.Windows.Media.Color c)
            ? new System.Windows.Media.SolidColorBrush(c)
            : System.Windows.Media.Brushes.Transparent;
    }

    private void OpenColorPickerForBox(TextBox box, string defaultSeedHex, Action applyEdit)
    {
        string original = box.Text;
        string seed = ColorPickerDialog.TryParseColor(original, out _) ? original : defaultSeedHex;
        bool changed = false;

        var dlg = new ColorPickerDialog(seed, previewHex =>
        {
            changed = true;
            box.Text = previewHex;
            applyEdit();
        })
        {
            Owner = this
        };

        if (dlg.ShowDialog() == true)
        {
            box.Text = dlg.IsCleared ? (ReferenceEquals(box, BgDimBox) ? "#00000000" : "") : dlg.SelectedHex;
            applyEdit();
        }
        else if (changed)
        {
            box.Text = original;
            applyEdit();
        }
    }

    private void ResetUndoHistory()
    {
        undoStack.Clear();
        redoStack.Clear();
        lastSavedUndoJson = HasActiveTheme ? currentPack.ToJsonString() : null;
    }

    private void SaveAndRecordHistory()
    {
        if (!HasActiveTheme) return;
        string snap = currentPack.ToJsonString();
        if (lastSavedUndoJson != null && !string.Equals(snap, lastSavedUndoJson, StringComparison.Ordinal))
        {
            undoStack.Add(lastSavedUndoJson);
            if (undoStack.Count > MaxUndoSteps)
                undoStack.RemoveAt(0);
            redoStack.Clear();
        }
        lastSavedUndoJson = snap;
        workspace.SaveCurrentTheme(currentPack);
    }

    private void PerformUndo()
    {
        if (!HasActiveTheme || undoStack.Count == 0) return;
        string prevJson = undoStack[^1];
        undoStack.RemoveAt(undoStack.Count - 1);
        if (lastSavedUndoJson != null)
            redoStack.Add(lastSavedUndoJson);
        string? prevSelId = selectedNode?.Id;
        currentPack = ThemePackModel.FromJson(prevJson);
        lastSavedUndoJson = currentPack.ToJsonString();
        workspace.SaveCurrentTheme(currentPack);
        BindWholePack(FindNodeById(currentPack.Root, prevSelId) ?? currentPack.Root);
        StatusFooterText.Text = $"Undo ({undoStack.Count} step(s) remaining)";
    }

    private void PerformRedo()
    {
        if (!HasActiveTheme || redoStack.Count == 0) return;
        string nextJson = redoStack[^1];
        redoStack.RemoveAt(redoStack.Count - 1);
        if (lastSavedUndoJson != null)
            undoStack.Add(lastSavedUndoJson);
        string? prevSelId = selectedNode?.Id;
        currentPack = ThemePackModel.FromJson(nextJson);
        lastSavedUndoJson = currentPack.ToJsonString();
        workspace.SaveCurrentTheme(currentPack);
        BindWholePack(FindNodeById(currentPack.Root, prevSelId) ?? currentPack.Root);
        StatusFooterText.Text = $"Redo ({redoStack.Count} step(s) remaining)";
    }

    private static LayoutNodeModel? FindNodeById(LayoutNodeModel root, string? id)
    {
        if (string.IsNullOrWhiteSpace(id)) return null;
        if (string.Equals(root.Id, id, StringComparison.Ordinal)) return root;
        if (root.Children == null) return null;
        foreach (var ch in root.Children)
        {
            var found = FindNodeById(ch, id);
            if (found != null) return found;
        }
        return null;
    }

    private void OnMainWindowPreviewKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (!HasActiveTheme) return;
        var mods = System.Windows.Input.Keyboard.Modifiers;
        bool inTextInput = System.Windows.Input.Keyboard.FocusedElement is System.Windows.Controls.Primitives.TextBoxBase
                           || System.Windows.Input.Keyboard.FocusedElement is ComboBox;
        if ((mods & System.Windows.Input.ModifierKeys.Control) == System.Windows.Input.ModifierKeys.Control)
        {
            if (e.Key == System.Windows.Input.Key.S)
            {
                e.Handled = true;
                OnSaveCurrentClick(this, new RoutedEventArgs());
                return;
            }
            if (!inTextInput && e.Key == System.Windows.Input.Key.Z)
            {
                e.Handled = true;
                PerformUndo();
                return;
            }
            if (!inTextInput && e.Key == System.Windows.Input.Key.Y)
            {
                e.Handled = true;
                PerformRedo();
                return;
            }
            if (!inTextInput && e.Key == System.Windows.Input.Key.D)
            {
                e.Handled = true;
                OnDuplicateClick(this, new RoutedEventArgs());
                return;
            }
        }
        else if (mods == System.Windows.Input.ModifierKeys.None && !inTextInput && e.Key == System.Windows.Input.Key.Delete)
        {
            if (selectedNode != null && selectedNode != currentPack.Root)
            {
                e.Handled = true;
                OnDeleteClick(this, new RoutedEventArgs());
            }
        }
    }

    private void RefreshIconsDropdown()
    {
        IconCombo.ItemsSource = workspace.ListAvailableIcons();
    }

    private void RefreshFontsDropdown()
    {
        var fonts = workspace.ListAvailableFonts();
        FontCombo.ItemsSource = fonts;
        if (IndFontCombo != null) IndFontCombo.ItemsSource = fonts;
    }

    private void RefreshThemeSelectorCombo(string? activeDir = null)
    {
        suppressThemeSelect = true;
        try
        {
            var list = workspace.ListActivePackThemes();
            ThemeSelectorCombo.ItemsSource = list;
            if (list.Count == 0 || string.IsNullOrWhiteSpace(activeDir ?? workspace.WorkspaceDir))
            {
                ThemeSelectorCombo.SelectedItem = null;
                return;
            }
            string targetDir = Path.GetFullPath(activeDir ?? workspace.WorkspaceDir);
            var match = list.FirstOrDefault(e => string.Equals(Path.GetFullPath(e.Directory), targetDir, StringComparison.OrdinalIgnoreCase))
                        ?? list.FirstOrDefault();
            ThemeSelectorCombo.SelectedItem = match;
        }
        finally
        {
            suppressThemeSelect = false;
        }
    }

    private void ApplyWorkspaceActiveState()
    {
        bool active = HasActiveTheme;
        EmptyWorkspaceOverlay.Visibility = active ? Visibility.Collapsed : Visibility.Visible;
        StudioCanvas.Visibility = active ? Visibility.Visible : Visibility.Collapsed;

        LeftSidebarPanel.IsEnabled = active;
        RightInspectorPanel.IsEnabled = active;
        SaveBtn.IsEnabled = active;
        SaveAsBtn.IsEnabled = active;
        UploadStoreBtn.IsEnabled = active;
        ThemeSelectorCombo.IsEnabled = active;
        TopBarCheck.IsEnabled = active;
        ExportUsbBtn.IsEnabled = true;
        AiContextBtn.IsEnabled = true;

        if (!active)
        {
            suppressEvents = true;
            try
            {
                HierarchyTree.ItemsSource = null;
                ThemeSelectorCombo.ItemsSource = null;
                selectedNode = null;
                PackHeaderText.Text = "Welcome — No Theme Open";
                WorkspacePathText.Text = "Workspace: Empty";
                SelectionSummaryText.Text = "Create a new theme, open a recent folder, or browse the Theme Store";
                NodeInspectorPanel.IsEnabled = false;
            }
            finally
            {
                suppressEvents = false;
            }
            RefreshRecentListUi();
        }
    }

    private void RefreshRecentListUi()
    {
        if (RecentListHost == null) return;
        RecentListHost.Children.Clear();

        var recents = RecentWorkspacesService.LoadRecent(recentStoragePath);
        bool hasAny = recents.Count > 0;
        NoRecentText.Visibility = hasAny ? Visibility.Collapsed : Visibility.Visible;
        ClearRecentBtn.IsEnabled = hasAny;

        foreach (var entry in recents)
        {
            var rowGrid = new Grid { Margin = new Thickness(0, 0, 0, 6) };
            rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            rowGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            bool exists = entry.ExistsOnDisk;
            var openBtn = new Button
            {
                HorizontalContentAlignment = HorizontalAlignment.Stretch,
                Padding = new Thickness(10, 7, 10, 7),
                Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x13, 0x1E, 0x30)),
                BorderBrush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x24, 0x37, 0x52)),
                ToolTip = exists ? entry.Path : "Folder not found on disk (click to clean up)",
                Opacity = exists ? 1.0 : 0.6
            };

            var content = new DockPanel { LastChildFill = true };
            var badge = new Border
            {
                Background = new System.Windows.Media.SolidColorBrush(entry.Kind == "Theme"
                    ? System.Windows.Media.Color.FromRgb(0x0F, 0x76, 0x6E)
                    : System.Windows.Media.Color.FromRgb(0x1D, 0x4E, 0xD8)),
                CornerRadius = new CornerRadius(4),
                Padding = new Thickness(6, 2, 6, 2),
                Margin = new Thickness(0, 0, 10, 0),
                VerticalAlignment = VerticalAlignment.Center
            };
            badge.Child = new TextBlock
            {
                Text = entry.Kind.ToUpperInvariant(),
                Foreground = System.Windows.Media.Brushes.White,
                FontSize = 10,
                FontWeight = FontWeights.Bold
            };
            DockPanel.SetDock(badge, Dock.Left);
            content.Children.Add(badge);

            if (!string.IsNullOrWhiteSpace(entry.FriendlyDateLabel))
            {
                var dateTb = new TextBlock
                {
                    Text = entry.FriendlyDateLabel,
                    Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x64, 0x74, 0x8B)),
                    FontSize = 11,
                    VerticalAlignment = VerticalAlignment.Center,
                    Margin = new Thickness(10, 0, 0, 0)
                };
                DockPanel.SetDock(dateTb, Dock.Right);
                content.Children.Add(dateTb);
            }

            var textStack = new StackPanel();
            textStack.Children.Add(new TextBlock
            {
                Text = entry.DisplayName + (exists ? "" : " (Missing)"),
                Foreground = System.Windows.Media.Brushes.White,
                FontWeight = FontWeights.SemiBold,
                FontSize = 13,
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            textStack.Children.Add(new TextBlock
            {
                Text = entry.Path,
                Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x94, 0xA3, 0xB8)),
                FontSize = 11,
                TextTrimming = TextTrimming.CharacterEllipsis
            });
            content.Children.Add(textStack);

            openBtn.Content = content;
            string capturedPath = entry.Path;
            openBtn.Click += (_, _) => OpenWorkspaceFolderPath(capturedPath);
            Grid.SetColumn(openBtn, 0);
            rowGrid.Children.Add(openBtn);

            var removeBtn = new Button
            {
                Content = "✕",
                Padding = new Thickness(8, 6, 8, 6),
                Margin = new Thickness(6, 0, 0, 0),
                ToolTip = "Remove from Recently Opened",
                Foreground = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(0x94, 0xA3, 0xB8))
            };
            removeBtn.Click += (_, _) =>
            {
                RecentWorkspacesService.RemoveRecent(capturedPath, recentStoragePath);
                RefreshRecentListUi();
            };
            Grid.SetColumn(removeBtn, 1);
            rowGrid.Children.Add(removeBtn);

            RecentListHost.Children.Add(rowGrid);
        }
    }

    private void OnClearRecentClick(object sender, RoutedEventArgs e)
    {
        RecentWorkspacesService.ClearRecent(recentStoragePath);
        RefreshRecentListUi();
        StatusFooterText.Text = "Cleared recently opened history.";
    }

    private void OnOpenAiContextClick(object sender, RoutedEventArgs e)
    {
        var dlg = new AiContextDialog(HasActiveTheme ? currentPack : null)
        {
            Owner = this
        };
        dlg.ShowDialog();
    }

    private void OnRecentDropdownClick(object sender, RoutedEventArgs e)
    {
        var menu = new ContextMenu();
        var recents = RecentWorkspacesService.LoadRecent(recentStoragePath);
        if (recents.Count == 0)
        {
            menu.Items.Add(new MenuItem { Header = "(No recently opened themes or packs)", IsEnabled = false });
        }
        else
        {
            foreach (var entry in recents)
            {
                string path = entry.Path;
                var mi = new MenuItem { Header = $"[{entry.Kind}] {entry.DisplayName} — {entry.Path}" };
                mi.Click += (_, _) => OpenWorkspaceFolderPath(path);
                menu.Items.Add(mi);
            }
            menu.Items.Add(new Separator());
            var clearMi = new MenuItem { Header = "Clear Recently Opened" };
            clearMi.Click += (_, _) => OnClearRecentClick(sender, e);
            menu.Items.Add(clearMi);
        }

        if (HasActiveTheme)
        {
            menu.Items.Add(new Separator());
            var closeMi = new MenuItem { Header = "Close Active Workspace" };
            closeMi.Click += (_, _) => CloseActiveWorkspace();
            menu.Items.Add(closeMi);
        }

        menu.PlacementTarget = RecentBtn;
        menu.Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom;
        menu.IsOpen = true;
    }

    private void CloseActiveWorkspace()
    {
        if (HasActiveTheme)
        {
            try { workspace.SaveCurrentTheme(currentPack); } catch { }
        }
            workspace.CloseWorkspace();
            hasActiveTheme = false;
            currentPack = new ThemePackModel();
            ResetUndoHistory();
            ApplyWorkspaceActiveState();
            StatusFooterText.Text = "Closed workspace.";
        }

    private void OpenWorkspaceFolderPath(string folderPath)
    {
        if (string.IsNullOrWhiteSpace(folderPath) || !Directory.Exists(folderPath))
        {
            if (!string.IsNullOrWhiteSpace(folderPath))
            {
                RecentWorkspacesService.RemoveRecent(folderPath, recentStoragePath);
                RefreshRecentListUi();
            }
            MessageBox.Show(this,
                $"Folder no longer exists on disk and was removed from Recently Opened:\n{folderPath}",
                "Folder Not Found", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            if (HasActiveTheme)
                workspace.SaveCurrentTheme(currentPack);

            var entries = workspace.OpenPackOrThemeFolder(folderPath);
            currentPack = workspace.SwitchToThemeDirectory(entries[0].Directory);
            currentPack.Normalize();
            hasActiveTheme = true;
            ResetUndoHistory();
            ApplyWorkspaceActiveState();
            RefreshIconsDropdown();
            RefreshFontsDropdown();
            RefreshThemeSelectorCombo(entries[0].Directory);
            BindWholePack(currentPack.Root);
            WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
            StatusFooterText.Text = entries.Count == 1
                ? $"Opened theme '{currentPack.Name}'"
                : $"Opened pack with {entries.Count} theme(s) -> editing '{currentPack.Name}'";

            string displayName = entries.Count == 1
                ? currentPack.Name
                : (Path.GetFileName(folderPath.TrimEnd('\\', '/')) ?? currentPack.Name);
            RecentWorkspacesService.RecordOpened(folderPath, displayName, workspace.IsSingleThemeMode, recentStoragePath);
            RefreshRecentListUi();
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Open Theme/Pack Error");
        }
    }

    private void BindWholePack(LayoutNodeModel? nextSelection)
    {
        FlushInspectorFocusBeforeSelectionChange(nextSelection);
        LayoutNodeModel target = nextSelection ?? currentPack.Root;
        var expandedSnapshot = new Dictionary<LayoutNodeModel, bool>(ReferenceEqualityComparer.Instance);
        CaptureExpandedStates(currentPack.Root, expandedSnapshot);

        suppressEvents = true;
        try
        {
            TopBarCheck.IsChecked = currentPack.Tokens.ShowSystemBar;
            PackIdBox.Text = currentPack.Id;
            PackNameBox.Text = currentPack.Name;
            PackAuthorBox.Text = currentPack.Author;
            PackVersionBox.Text = currentPack.Version;
            PackRevisionBox.Text = Math.Max(1, currentPack.Revision).ToString();
            RefreshPackCategoryDropdown();
            string minVer = string.IsNullOrWhiteSpace(currentPack.MinAppVersion) ? PresetCatalog.DefaultMinAppVersion : currentPack.MinAppVersion.Trim();
            PackCompatBox.Text = (currentPack.CompatibleVersions != null && currentPack.CompatibleVersions.Count > 0)
                ? string.Join(", ", currentPack.CompatibleVersions)
                : $"{minVer}+";
            PackUpdatedText.Text = string.IsNullOrWhiteSpace(currentPack.UpdatedAt) ? "(auto on save/export)" : currentPack.UpdatedAt;
            PackDescBox.Text = currentPack.Description ?? "";
            SelectComboByTag(ThemeSpeedUnitCombo, currentPack.Tokens.SpeedUnit);
            SelectComboByTag(ThemeTempUnitCombo, currentPack.Tokens.TempUnit);

            BgModeCombo.SelectedItem = currentPack.Background.Type;
            BgPrimaryBox.Text = currentPack.Background.PrimaryColor;
            BgSecondaryBox.Text = currentPack.Background.SecondaryColor;
            BgAccentBox.Text = currentPack.Background.AccentColor;
            BgDimBox.Text = currentPack.Background.DimColor ?? "#48060B12";

            HierarchyTree.ItemsSource = null;
            RestoreExpandedStates(currentPack.Root, expandedSnapshot);
            EnsureAncestorsExpanded(currentPack.Root, target);
            SetTreeSelectionRecursive(currentPack.Root, target);
            HierarchyTree.ItemsSource = new[] { currentPack.Root };
            UpdatePackHeaderLabel();
        }
        finally
        {
            suppressEvents = false;
        }
        selectedNode = target;
        UpdateSimReadouts();
        StudioCanvas.Bind(currentPack, workspace, sim, selectedNode);
        PopulateNodeInspector(selectedNode, forceRefreshFocusedFields: true);
        ScrollTreeToNode(target);
    }

    private static void SelectComboByTag(ComboBox combo, string? tag)
    {
        foreach (var item in combo.Items)
        {
            if (item is ComboBoxItem cbi && string.Equals(cbi.Tag?.ToString(), tag, StringComparison.OrdinalIgnoreCase))
            {
                combo.SelectedItem = cbi;
                return;
            }
        }
        if (combo.Items.Count > 0) combo.SelectedIndex = 0;
    }

    private static void CaptureExpandedStates(LayoutNodeModel node, Dictionary<LayoutNodeModel, bool> map)
    {
        map[node] = node.IsExpanded;
        if (node.Children == null) return;
        foreach (var child in node.Children) CaptureExpandedStates(child, map);
    }

    private static void RestoreExpandedStates(LayoutNodeModel node, Dictionary<LayoutNodeModel, bool> map)
    {
        if (map.TryGetValue(node, out bool wasExpanded)) node.IsExpanded = wasExpanded;
        if (node.Children == null) return;
        foreach (var child in node.Children) RestoreExpandedStates(child, map);
    }

    private static bool EnsureAncestorsExpanded(LayoutNodeModel current, LayoutNodeModel target)
    {
        if (current == target) return true;
        var children = current.TreeChildren;
        if (children == null) return false;
        foreach (var child in children)
        {
            if (EnsureAncestorsExpanded(child, target))
            {
                current.IsExpanded = true;
                return true;
            }
        }
        return false;
    }

    private static void SetTreeSelectionRecursive(LayoutNodeModel node, LayoutNodeModel? target)
    {
        node.IsSelected = ReferenceEquals(node, target);
        var children = node.TreeChildren;
        if (children == null) return;
        foreach (var child in children) SetTreeSelectionRecursive(child, target);
    }

    private static bool BuildTreePathRecursive(LayoutNodeModel current, LayoutNodeModel target, List<LayoutNodeModel> path)
    {
        path.Add(current);
        if (ReferenceEquals(current, target)) return true;
        var children = current.TreeChildren;
        if (children != null)
        {
            foreach (var child in children)
            {
                if (BuildTreePathRecursive(child, target, path)) return true;
            }
        }
        path.RemoveAt(path.Count - 1);
        return false;
    }

    private TreeViewItem? RealizeTreeContainer(LayoutNodeModel target)
    {
        var path = new List<LayoutNodeModel>();
        if (!BuildTreePathRecursive(currentPack.Root, target, path)) return null;

        ItemsControl currentContainer = HierarchyTree;
        TreeViewItem? resolved = null;
        for (int i = 0; i < path.Count; i++)
        {
            currentContainer.ApplyTemplate();
            var item = path[i];
            TreeViewItem? tvi = currentContainer.ItemContainerGenerator.ContainerFromItem(item) as TreeViewItem;
            if (tvi == null)
            {
                currentContainer.UpdateLayout();
                tvi = currentContainer.ItemContainerGenerator.ContainerFromItem(item) as TreeViewItem;
            }
            if (tvi == null) return null;
            if (i < path.Count - 1)
            {
                tvi.IsExpanded = true;
                tvi.ApplyTemplate();
                tvi.UpdateLayout();
                currentContainer = tvi;
            }
            else
            {
                resolved = tvi;
            }
        }
        return resolved;
    }

    private void ScrollTreeToNode(LayoutNodeModel target)
    {
        RealizeTreeContainer(target)?.BringIntoView();
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() =>
        {
            if (ReferenceEquals(selectedNode, target))
            {
                RealizeTreeContainer(target)?.BringIntoView();
            }
        }));
    }

    private void SelectNodeInTree(LayoutNodeModel? target)
    {
        bool prev = suppressEvents;
        suppressEvents = true;
        try
        {
            if (target != null) EnsureAncestorsExpanded(currentPack.Root, target);
            SetTreeSelectionRecursive(currentPack.Root, target);
        }
        finally
        {
            suppressEvents = prev;
        }
        if (target != null)
        {
            ScrollTreeToNode(target);
        }
    }

    private void PopulateNodeInspector(LayoutNodeModel? node, bool forceRefreshFocusedFields = false)
    {
        suppressEvents = true;
        try
        {
            selectedNode = node;
            if (node == null)
            {
                NodeInspectorPanel.IsEnabled = false;
                SelectionSummaryText.Text = "No element selected";
                return;
            }
            NodeInspectorPanel.IsEnabled = true;
            SelectionSummaryText.Text = $"Selected: {node.DisplayLabel}";
            NodeIdBox.Text = node.Id;

            if (node.VirtualOwnerPager is LayoutNodeModel ownerPager)
            {
                SelectParentBtn.IsEnabled = true;
                BreakApartBtn.Visibility = Visibility.Collapsed;
                NodeIdBox.IsEnabled = false;
                NodeTypeCombo.IsEnabled = false;
                NodeTypeCombo.ItemsSource = new[] { "page_indicator" };
                NodeTypeCombo.SelectedItem = "page_indicator";
                WidgetFieldsGroup.Visibility = Visibility.Collapsed;
                PanelFieldsGroup.Visibility = Visibility.Collapsed;
                ActionFieldsGroup.Visibility = Visibility.Collapsed;
                PagerIndicatorFieldsGroup.Visibility = Visibility.Visible;
                DisableIndBarBtn.Visibility = Visibility.Visible;
                DisableIndBarBtn.Content = "Disable Built-in Page Bar";
                PopulateIndicatorInspector(ownerPager);
                UpdateInspectorFieldVisibility(node);
                return;
            }

            NodeIdBox.IsEnabled = true;
            NodeTypeCombo.IsEnabled = true;
            bool isRoot = ReferenceEquals(node, currentPack.Root);
            SelectParentBtn.IsEnabled = !isRoot;

            string kind = (node.Kind ?? "tile").ToLowerInvariant();
            bool isLegacyBreakable = !node.IsPanel && kind is "ev_hero" or "tile" or "action_pill" or "soc_ring" or "telemetry_gauge";
            BreakApartBtn.Visibility = isLegacyBreakable ? Visibility.Visible : Visibility.Collapsed;

            bool isPageIndWidget = !node.IsPanel && string.Equals(kind, "page_indicator", StringComparison.OrdinalIgnoreCase);
            bool isPagerPanel = node.IsPanel && string.Equals(node.Layout, "pager", StringComparison.OrdinalIgnoreCase);

            if (node.IsPanel)
            {
                NodeTypeCombo.ItemsSource = new[] { "flex_col", "flex_row", "grid", "freeform", "pager" };
                NodeTypeCombo.SelectedItem = node.Layout ?? "flex_col";
                WidgetFieldsGroup.Visibility = Visibility.Collapsed;
                PanelFieldsGroup.Visibility = Visibility.Visible;
                PageTitleBox.Text = node.PageTitle ?? "";
                PaddingBox.Text = FormatInsets(node.PaddingPx ?? node.Style?.PaddingPx);
                ColsBox.Text = (node.Columns ?? 3).ToString();
                RowsBox.Text = (node.Rows ?? 2).ToString();
                GapBox.Text = (node.GapPx ?? 16).ToString();
            }
            else
            {
                NodeTypeCombo.ItemsSource = PresetCatalog.WidgetKinds;
                NodeTypeCombo.SelectedItem = node.Kind ?? "tile";
                WidgetFieldsGroup.Visibility = isPageIndWidget ? Visibility.Collapsed : Visibility.Visible;
                PanelFieldsGroup.Visibility = Visibility.Collapsed;

                if (!isPageIndWidget)
                {
                    TitleBox.Text = node.Title ?? "";
                    SubtitleBox.Text = node.Subtitle ?? "";
                    BadgeBox.Text = node.BadgeText ?? "";
                    TelemetryKeyCombo.Text = node.TelemetryKey ?? "";
                    UnitBox.Text = node.Unit ?? "";
                    MinValBox.Text = node.MinValue?.ToString(CultureInfo.InvariantCulture) ?? "";
                    MaxValBox.Text = node.MaxValue?.ToString(CultureInfo.InvariantCulture) ?? "";
                    SelectMatchingComboItem(OrientationCombo, node.Orientation ?? "horizontal");
                    ShowTextCheck.IsChecked = node.ShowText == true;
                    WrapTextCheck.IsChecked = node.WrapText == true;

                    FontCombo.Text = node.Style?.FontFamily ?? "";
                    TitleSizeBox.Text = node.Style?.TitleSizePx?.ToString(CultureInfo.InvariantCulture) ?? "";
                    SubSizeBox.Text = node.Style?.SubtitleSizePx?.ToString(CultureInfo.InvariantCulture) ?? "";
                    bool isBold = string.Equals(node.Style?.FontWeight, "bold", StringComparison.OrdinalIgnoreCase)
                                  || (!string.Equals(node.Style?.FontWeight, "normal", StringComparison.OrdinalIgnoreCase)
                                      && kind is "tile" or "action_pill" or "ev_hero" or "switcher_status" or "clock"
                                              or "soc_ring" or "ring_gauge" or "telemetry_gauge"
                                              or "text" or "sensor" or "label" or "telemetry_badge" or "progress_bar");
                    BoldCheck.IsChecked = isBold;
                    ItalicCheck.IsChecked = node.Style?.FontItalic == true;
                    TextColorBox.Text = node.Style?.TextColor ?? "#FFF1F5F9";
                    TextColorBox.Foreground = !string.IsNullOrWhiteSpace(node.Style?.TextColor) ? ExplicitValueBrush : AutoValueBrush;
                    SubColorBox.Text = node.Style?.SubTextColor ?? "#FF94A3B8";
                    SubColorBox.Foreground = !string.IsNullOrWhiteSpace(node.Style?.SubTextColor) ? ExplicitValueBrush : AutoValueBrush;
                    inspectorLastTextColorText = TextColorBox.Text.Trim();
                    inspectorLastSubColorText = SubColorBox.Text.Trim();
                    SelectMatchingComboItem(AlignCombo, node.Style?.TextAlign ?? "left");
                    SelectMatchingComboItem(VAlignCombo, node.Style?.VerticalAlign ?? "center");

                    IconCombo.Text = !string.IsNullOrWhiteSpace(node.BitmapAsset) ? node.BitmapAsset : (node.Icon ?? "");
                    IconSizeBox.Text = node.Style?.IconSizePx?.ToString() ?? "";
                    IconTintBox.Text = node.Style?.IconTint ?? "";
                    SelectMatchingComboItem(ScaleTypeCombo, node.ScaleType ?? "fit");
                }
            }

            PagerIndicatorFieldsGroup.Visibility = (isPagerPanel || isPageIndWidget) ? Visibility.Visible : Visibility.Collapsed;
            if (isPagerPanel || isPageIndWidget)
            {
                DisableIndBarBtn.Visibility = isPageIndWidget || node.HasVisibleBuiltinIndicator
                    ? Visibility.Visible
                    : Visibility.Collapsed;
                DisableIndBarBtn.Content = isPageIndWidget ? "Delete Page Indicator" : "Disable Built-in Page Bar";
                PopulateIndicatorInspector(node);
            }

            bool hideAction = isRoot || (node.IsPanel && node.Layout == "pager") || (!node.IsPanel && kind is "divider" or "spacer" or "page_indicator");
            ActionFieldsGroup.Visibility = hideAction ? Visibility.Collapsed : Visibility.Visible;
            if (!hideAction)
            {
                var act = node.Action ?? new ActionSpecModel();
                ActionTypeBox.Text = act.Type;
                ActionCmdBox.Text = act.Cmd ?? "";
                ActionPkgBox.Text = act.Package ?? "";
                ActionClsBox.Text = !string.IsNullOrEmpty(act.Class) ? act.Class : (act.IntentAction ?? "");
                SelectMatchingPreset(act);

                var clickableAncestor = FindClickableAncestor(currentPack.Root, node);
                if (clickableAncestor != null)
                {
                    ActionScopeHint.Text = $"\u24D8 Parent panel '{clickableAncestor.Id}' handles taps ({clickableAncestor.Action?.Type}). Leave Action = none here so the entire parent panel acts as the button.";
                    ActionScopeHint.Foreground = System.Windows.Media.Brushes.LightSkyBlue;
                }
                else if (node.IsPanel)
                {
                    ActionScopeHint.Text = "Whole-Panel Tap Selector: tapping anywhere inside this panel (on any text, bar, or image inside it) runs this action.";
                    ActionScopeHint.Foreground = System.Windows.Media.Brushes.LightGreen;
                }
                else
                {
                    ActionScopeHint.Text = "Element Tap Action (for standalone widgets, or leave 'none' and set action on enclosing Panel).";
                    ActionScopeHint.Foreground = System.Windows.Media.Brushes.SlateGray;
                }
            }

            PopulateGeometryInspector(node, forceRefreshFocusedFields);

            string tokenAccent = !string.IsNullOrWhiteSpace(currentPack.Tokens?.AccentColor)
                ? currentPack.Tokens!.AccentColor
                : "#FF38BDF8";
            int defaultRadius = (!node.IsPanel && string.Equals(kind, "action_pill", StringComparison.OrdinalIgnoreCase))
                ? 999
                : (currentPack.Tokens?.CornerRadiusPx ?? 20);

            BgColorBox.Text = node.Style?.BgColor ?? "#D4111D2D";
            BgColorBox.Foreground = !string.IsNullOrWhiteSpace(node.Style?.BgColor) ? ExplicitValueBrush : AutoValueBrush;
            BgEndBox.Text = node.Style?.BgEndColor ?? "";
            BorderColorBox.Text = node.Style?.BorderColor ?? "#4438BDF8";
            BorderColorBox.Foreground = !string.IsNullOrWhiteSpace(node.Style?.BorderColor) ? ExplicitValueBrush : AutoValueBrush;
            BorderWidthBox.Text = node.Style?.BorderWidthPx?.ToString() ?? "";
            AccentColorBox.Text = node.Style?.AccentColor ?? tokenAccent;
            AccentColorBox.Foreground = !string.IsNullOrWhiteSpace(node.Style?.AccentColor) ? ExplicitValueBrush : AutoValueBrush;
            RadiusBox.Text = (node.Style?.CornerRadiusPx ?? defaultRadius).ToString(CultureInfo.InvariantCulture);
            RadiusBox.Foreground = node.Style?.CornerRadiusPx.HasValue == true ? ExplicitValueBrush : AutoValueBrush;
            OpacityBox.Text = node.Style?.Opacity?.ToString(CultureInfo.InvariantCulture) ?? "";
            TileAnimBox.Text = node.Style?.BgAnimAsset ?? node.Style?.BgBitmapAsset ?? "";
            IdleEffectCombo.SelectedItem = node.Animation?.IdleEffect ?? "none";
            TransparentCheck.IsChecked = node.Style?.Transparent == true;

            inspectorLastBgColorText = BgColorBox.Text.Trim();
            inspectorLastBorderColorText = BorderColorBox.Text.Trim();
            inspectorLastAccentColorText = AccentColorBox.Text.Trim();
            inspectorLastRadiusText = RadiusBox.Text.Trim();

            UpdateInspectorFieldVisibility(node);
        }
        finally
        {
            suppressEvents = false;
        }
    }

    private static void SetGridColumnVisible(ColumnDefinition col, UIElement cell, bool visible, double starWeight = 1.0)
    {
        col.Width = visible ? new GridLength(starWeight, GridUnitType.Star) : new GridLength(0);
        cell.Visibility = visible ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateInspectorFieldVisibility(LayoutNodeModel node)
    {
        bool isVirtualBar = node.VirtualOwnerPager != null;
        if (isVirtualBar)
        {
            GeometrySectionGroup.Visibility = Visibility.Collapsed;
            StyleSectionGroup.Visibility = Visibility.Collapsed;
            return;
        }

        bool isRoot = ReferenceEquals(node, currentPack.Root);
        var parent = isRoot ? null : FindParent(currentPack.Root, node);
        string parentLayout = (parent?.Layout ?? "").ToLowerInvariant();
        bool inFreeform = parentLayout == "freeform";
        bool inGrid = parentLayout == "grid";
        bool inFlex = parentLayout is "flex_row" or "flex_col";
        bool inPager = parentLayout == "pager";
        string kind = (node.Kind ?? "tile").ToLowerInvariant();
        bool isSpacer = !node.IsPanel && kind == "spacer";
        bool isDivider = !node.IsPanel && kind == "divider";
        bool isPageInd = !node.IsPanel && kind == "page_indicator";

        // 1. Panel Container Settings
        if (node.IsPanel)
        {
            string panelLayout = (node.Layout ?? "flex_col").ToLowerInvariant();
            bool hasMetrics = panelLayout is "grid" or "flex_col" or "flex_row";

            SetGridColumnVisible(PageTitleCol, PageTitleCell, inPager, 1.0);
            SetGridColumnVisible(PaddingCol, PaddingCell, true, 1.0);

            PanelGridMetricsRow.Visibility = hasMetrics ? Visibility.Visible : Visibility.Collapsed;
            SetGridColumnVisible(ColsCol, ColsCell, hasMetrics, 1.0);
            SetGridColumnVisible(RowsCol, RowsCell, hasMetrics, 1.0);
            SetGridColumnVisible(GapCol, GapCell, hasMetrics, 1.0);
        }

        // 2. Widget Content, Typography & Icon Sub-fields
        if (!node.IsPanel)
        {
            WidgetFieldsGroup.Visibility = (isSpacer || isDivider || isPageInd) ? Visibility.Collapsed : Visibility.Visible;
        }
        if (!node.IsPanel && WidgetFieldsGroup.Visibility == Visibility.Visible)
        {
            bool isText = kind is "text" or "sensor" or "label";
            bool isBar = kind == "progress_bar";
            bool isRing = kind is "soc_ring" or "ring_gauge" or "telemetry_gauge";
            bool isBadge = kind == "telemetry_badge";
            bool isClock = kind == "clock";
            bool isMedia = kind is "icon" or "image";
            bool isSwitcher = kind == "switcher_status";
            bool isTile = kind == "tile";
            bool isPill = kind == "action_pill";
            bool isHero = kind == "ev_hero";

            bool showContentSection = !(isMedia || isClock || isSwitcher);
            ContentSensorSection.Visibility = showContentSection ? Visibility.Visible : Visibility.Collapsed;
            if (showContentSection)
            {
                TitleFieldGroup.Visibility = Visibility.Visible;

                bool showSub = isText || isRing || isTile || isPill || isHero || isBadge;
                bool showChip = isTile || isBadge;
                SubBadgeRow.Visibility = (showSub || showChip) ? Visibility.Visible : Visibility.Collapsed;
                SetGridColumnVisible(SubtitleCol, SubtitleCell, showSub, 2.0);
                SetGridColumnVisible(BadgeCol, BadgeCell, showChip, 1.0);

                bool showSensor = isText || isBar || isRing || isBadge || isTile || isPill || isHero;
                SensorBindGroup.Visibility = showSensor ? Visibility.Visible : Visibility.Collapsed;

                bool showUnit = showSensor;
                bool showRange = isBar || isRing;
                bool showDir = isBar;
                SensorUnitRangeRow.Visibility = (showUnit || showRange || showDir) ? Visibility.Visible : Visibility.Collapsed;
                SetGridColumnVisible(UnitCol, UnitCell, showUnit, 1.0);
                SetGridColumnVisible(MinValCol, MinValCell, showRange, 1.0);
                SetGridColumnVisible(MaxValCol, MaxValCell, showRange, 1.0);
                SetGridColumnVisible(BarDirCol, BarDirCell, showDir, 1.0);

                bool showBarTextToggle = isBar;
                bool showWrapToggle = isText || isTile || isBadge;
                ShowTextCheck.Visibility = showBarTextToggle ? Visibility.Visible : Visibility.Collapsed;
                WrapTextCheck.Visibility = showWrapToggle ? Visibility.Visible : Visibility.Collapsed;
                SensorOptionsRow.Visibility = (showBarTextToggle || showWrapToggle) ? Visibility.Visible : Visibility.Collapsed;
            }

            // Typography & Alignment
            bool showFont = !isMedia;
            bool showAlign = true;
            TypographySection.Visibility = (showFont || showAlign) ? Visibility.Visible : Visibility.Collapsed;
            TypographyHeaderText.Text = (!showFont && showAlign) ? "Alignment" : "Typography (Independent per Element)";
            FontRow.Visibility = showFont ? Visibility.Visible : Visibility.Collapsed;
            FontSizesRow.Visibility = showFont ? Visibility.Visible : Visibility.Collapsed;
            if (showFont)
            {
                SetGridColumnVisible(TitleSizeCol, TitleSizeCell, true, 1.0);
                SetGridColumnVisible(SubSizeCol, SubSizeCell, !isBar, 1.0);
            }
            TextColorsAlignRow.Visibility = Visibility.Visible;
            SetGridColumnVisible(TextColorCol, TextColorCell, showFont, 1.0);
            SetGridColumnVisible(SubColorCol, SubColorCell, showFont && !isBar, 1.0);
            SetGridColumnVisible(AlignHCol, AlignHCell, true, 1.0);
            SetGridColumnVisible(AlignVCol, AlignVCell, true, 1.0);

            // Icon / Ring Size / Image Asset
            bool showIconAsset = isMedia || isTile || isPill || isHero;
            bool showIconProps = isMedia || isTile || isPill || isRing || isHero;
            IconImageSection.Visibility = (showIconAsset || showIconProps) ? Visibility.Visible : Visibility.Collapsed;
            IconHeaderText.Text = isRing && !showIconAsset ? "Gauge Dimensions" : "Icon SVG / Picture Image";
            IconAssetRow.Visibility = showIconAsset ? Visibility.Visible : Visibility.Collapsed;
            IconPropsRow.Visibility = showIconProps ? Visibility.Visible : Visibility.Collapsed;
            if (showIconProps)
            {
                IconSizeLabel.Text = isRing ? "Ring Diameter (px)" : "Icon Size (px)";
                SetGridColumnVisible(IconSizeCol, IconSizeCell, true, 1.0);
                SetGridColumnVisible(IconTintCol, IconTintCell, isMedia || isTile || isPill || isHero, 1.0);
                SetGridColumnVisible(ScaleTypeCol, ScaleTypeCell, isMedia, 1.0);
            }
        }

        // 3. Geometry, Alignment & Freeform Bounds (depends cleanly on parent container layout)
        if (isRoot)
        {
            GeometrySectionGroup.Visibility = Visibility.Collapsed;
        }
        else
        {
            GeometrySectionGroup.Visibility = Visibility.Visible;
            bool showWH = !inPager;
            bool showFlex = inFlex;
            SizeRow.Visibility = (showWH || showFlex) ? Visibility.Visible : Visibility.Collapsed;
            SetGridColumnVisible(WidthCol, WidthCell, showWH, 1.0);
            SetGridColumnVisible(HeightCol, HeightCell, showWH, 1.0);
            SetGridColumnVisible(FlexCol, FlexCell, showFlex, 1.0);

            bool showGridSpan = inGrid;
            bool showNodeAlignH = inGrid || inFlex;
            GridSpanRow.Visibility = Visibility.Visible;
            SetGridColumnVisible(ColSpanCol, ColSpanCell, showGridSpan, 1.0);
            SetGridColumnVisible(RowSpanCol, RowSpanCell, showGridSpan, 1.0);
            SetGridColumnVisible(NodeHAlignCol, NodeHAlignCell, showNodeAlignH, 1.0);
            SetGridColumnVisible(MarginCol, MarginCell, true, 1.5);

            BoundsRow.Visibility = inFreeform ? Visibility.Visible : Visibility.Collapsed;
        }

        // 4. Colors, Borders & Animation
        if (isSpacer || isPageInd)
        {
            StyleSectionGroup.Visibility = Visibility.Collapsed;
        }
        else if (isDivider)
        {
            StyleSectionGroup.Visibility = Visibility.Visible;
            BgGradientRow.Visibility = Visibility.Collapsed;
            BorderAccentRadiusRow.Visibility = Visibility.Visible;
            BorderColorLabel.Text = "Divider Color";
            SetGridColumnVisible(BorderColorCol, BorderColorCell, true, 1.0);
            SetGridColumnVisible(AccentColorCol, AccentColorCell, false, 1.0);
            SetGridColumnVisible(RadiusCol, RadiusCell, false, 1.0);

            BorderOpacityIdleRow.Visibility = Visibility.Visible;
            SetGridColumnVisible(BorderWidthCol, BorderWidthCell, false, 1.0);
            SetGridColumnVisible(OpacityCol, OpacityCell, true, 1.0);
            SetGridColumnVisible(IdleEffectCol, IdleEffectCell, false, 1.0);

            TileAnimRow.Visibility = Visibility.Collapsed;
            TransparentCheck.Visibility = Visibility.Collapsed;
        }
        else
        {
            StyleSectionGroup.Visibility = Visibility.Visible;
            BgGradientRow.Visibility = Visibility.Visible;
            BorderAccentRadiusRow.Visibility = Visibility.Visible;
            BorderColorLabel.Text = "Border Color";
            SetGridColumnVisible(BorderColorCol, BorderColorCell, true, 1.0);
            SetGridColumnVisible(AccentColorCol, AccentColorCell, true, 1.0);
            SetGridColumnVisible(RadiusCol, RadiusCell, true, 1.0);

            BorderOpacityIdleRow.Visibility = Visibility.Visible;
            SetGridColumnVisible(BorderWidthCol, BorderWidthCell, true, 1.0);
            SetGridColumnVisible(OpacityCol, OpacityCell, true, 1.0);
            SetGridColumnVisible(IdleEffectCol, IdleEffectCell, true, 1.0);

            TileAnimRow.Visibility = (!node.IsPanel && kind == "progress_bar") ? Visibility.Collapsed : Visibility.Visible;
            TransparentCheck.Visibility = Visibility.Visible;
        }
    }

    private void CommitInspectorToSelectedNode()
    {
        if (suppressEvents || !HasActiveTheme || selectedNode == null) return;

        if (selectedNode.VirtualOwnerPager is LayoutNodeModel ownerPager)
        {
            CommitIndicatorInspector(ownerPager);
            if (ownerPager.ShowDots == false)
            {
                BindWholePack(ownerPager);
                StatusFooterText.Text = $"Disabled built-in page bar on '{ownerPager.Id}'";
            }
            else
            {
                StudioCanvas.RebuildScene();
                selectedNode.NotifyLabelChanged();
                SelectionSummaryText.Text = $"Selected: {selectedNode.DisplayLabel}";
                workspace.SaveCurrentTheme(currentPack);
            }
            return;
        }

        selectedNode.Id = string.IsNullOrWhiteSpace(NodeIdBox.Text) ? selectedNode.Id : NodeIdBox.Text.Trim();
        selectedNode.Style ??= new WidgetStyleModel();

        if (selectedNode.IsPanel)
        {
            string prevLayout = selectedNode.Layout ?? "flex_col";
            string nextLayout = NodeTypeCombo.SelectedItem?.ToString() ?? prevLayout;
            if (!string.Equals(prevLayout, "freeform", StringComparison.OrdinalIgnoreCase)
                && string.Equals(nextLayout, "freeform", StringComparison.OrdinalIgnoreCase)
                && selectedNode.Children != null)
            {
                foreach (var child in selectedNode.Children)
                {
                    if (child.Bounds is not { Length: >= 4 } && StudioCanvas.TryGetNodeLocalRefRect(child, out Rect localRect))
                    {
                        child.Bounds = new[]
                        {
                            Math.Max(0, Math.Round(localRect.X)),
                            Math.Max(0, Math.Round(localRect.Y)),
                            Math.Max(20, Math.Round(localRect.Width)),
                            Math.Max(20, Math.Round(localRect.Height))
                        };
                    }
                }
            }
            selectedNode.Layout = nextLayout;
            if (PageTitleCell.Visibility == Visibility.Visible)
                selectedNode.PageTitle = NullIfEmpty(PageTitleBox.Text);
            selectedNode.PaddingPx = ParseInsets(PaddingBox.Text);
            if (ColsCell.Visibility == Visibility.Visible)
                selectedNode.Columns = ParseOptInt(ColsBox.Text) ?? 3;
            if (RowsCell.Visibility == Visibility.Visible)
                selectedNode.Rows = ParseOptInt(RowsBox.Text) ?? 2;
            if (GapCell.Visibility == Visibility.Visible)
                selectedNode.GapPx = ParseOptInt(GapBox.Text) ?? 16;
        }
        else if (WidgetFieldsGroup.Visibility == Visibility.Visible)
        {
            selectedNode.Kind = NodeTypeCombo.SelectedItem?.ToString() ?? selectedNode.Kind;
            if (ContentSensorSection.Visibility == Visibility.Visible)
            {
                if (TitleFieldGroup.Visibility == Visibility.Visible)
                    selectedNode.Title = TitleBox.Text ?? "";
                if (SubtitleCell.Visibility == Visibility.Visible)
                    selectedNode.Subtitle = NullIfEmpty(SubtitleBox.Text);
                if (BadgeCell.Visibility == Visibility.Visible)
                    selectedNode.BadgeText = NullIfEmpty(BadgeBox.Text);
                if (SensorBindGroup.Visibility == Visibility.Visible)
                    selectedNode.TelemetryKey = NullIfEmpty(ExtractSensorKey(TelemetryKeyCombo));
                if (UnitCell.Visibility == Visibility.Visible)
                    selectedNode.Unit = NullIfEmpty(UnitBox.Text);
                if (MinValCell.Visibility == Visibility.Visible)
                    selectedNode.MinValue = ParseOptDouble(MinValBox.Text);
                if (MaxValCell.Visibility == Visibility.Visible)
                    selectedNode.MaxValue = ParseOptDouble(MaxValBox.Text);
                if (BarDirCell.Visibility == Visibility.Visible)
                    selectedNode.Orientation = NullIfEmpty((OrientationCombo.SelectedItem as ComboBoxItem)?.Content?.ToString());
                if (ShowTextCheck.Visibility == Visibility.Visible)
                    selectedNode.ShowText = ShowTextCheck.IsChecked == true ? true : null;
                if (WrapTextCheck.Visibility == Visibility.Visible)
                {
                    selectedNode.WrapText = WrapTextCheck.IsChecked == true ? true : null;
                    selectedNode.MaxLines = WrapTextCheck.IsChecked == true ? 4 : null;
                }
            }

            if (IconAssetRow.Visibility == Visibility.Visible)
            {
                string? chosenAsset = NullIfEmpty(IconCombo.Text);
                if ((selectedNode.Kind is "image" or "icon") && chosenAsset != null && !chosenAsset.EndsWith(".svg", StringComparison.OrdinalIgnoreCase))
                {
                    selectedNode.BitmapAsset = chosenAsset;
                    selectedNode.Icon = chosenAsset;
                }
                else
                {
                    selectedNode.BitmapAsset = null;
                    selectedNode.Icon = chosenAsset;
                }
            }
            if (ScaleTypeCell.Visibility == Visibility.Visible)
                selectedNode.ScaleType = NullIfEmpty((ScaleTypeCombo.SelectedItem as ComboBoxItem)?.Content?.ToString());
            if (IconSizeCell.Visibility == Visibility.Visible)
                selectedNode.Style.IconSizePx = ParseOptInt(IconSizeBox.Text);
            if (IconTintCell.Visibility == Visibility.Visible)
                selectedNode.Style.IconTint = NullIfEmpty(IconTintBox.Text);

            if (FontRow.Visibility == Visibility.Visible)
            {
                selectedNode.Style.FontFamily = NullIfEmpty(FontCombo.Text);
                selectedNode.Style.TitleSizePx = ParseOptDouble(TitleSizeBox.Text);
                if (SubSizeCell.Visibility == Visibility.Visible)
                    selectedNode.Style.SubtitleSizePx = ParseOptDouble(SubSizeBox.Text);
                selectedNode.Style.FontWeight = BoldCheck.IsChecked == true ? "bold" : "normal";
                selectedNode.Style.FontItalic = ItalicCheck.IsChecked == true ? true : null;
                string txtColorTrim = TextColorBox.Text.Trim();
                if (!string.Equals(txtColorTrim, inspectorLastTextColorText, StringComparison.Ordinal) || !string.IsNullOrWhiteSpace(selectedNode.Style.TextColor))
                    selectedNode.Style.TextColor = NullIfEmpty(txtColorTrim);
                if (SubColorCell.Visibility == Visibility.Visible)
                {
                    string subColorTrim = SubColorBox.Text.Trim();
                    if (!string.Equals(subColorTrim, inspectorLastSubColorText, StringComparison.Ordinal) || !string.IsNullOrWhiteSpace(selectedNode.Style.SubTextColor))
                        selectedNode.Style.SubTextColor = NullIfEmpty(subColorTrim);
                }
            }
            if (AlignHCell.Visibility == Visibility.Visible)
                selectedNode.Style.TextAlign = (AlignCombo.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "left";
            if (AlignVCell.Visibility == Visibility.Visible)
                selectedNode.Style.VerticalAlign = NullIfEmpty((VAlignCombo.SelectedItem as ComboBoxItem)?.Content?.ToString());
        }
        else
        {
            selectedNode.Kind = NodeTypeCombo.SelectedItem?.ToString() ?? selectedNode.Kind;
        }

        if (PagerIndicatorFieldsGroup.Visibility == Visibility.Visible)
        {
            CommitIndicatorInspector(selectedNode);
        }

        if (ActionFieldsGroup.Visibility == Visibility.Visible)
        {
            string actType = string.IsNullOrWhiteSpace(ActionTypeBox.Text) ? "none" : ActionTypeBox.Text.Trim();
            if ("none".Equals(actType, StringComparison.OrdinalIgnoreCase))
            {
                selectedNode.Action = selectedNode.IsPanel ? null : new ActionSpecModel { Type = "none" };
            }
            else
            {
                selectedNode.Action ??= new ActionSpecModel();
                selectedNode.Action.Type = actType;
                selectedNode.Action.Cmd = NullIfEmpty(ActionCmdBox.Text);
                selectedNode.Action.Package = NullIfEmpty(ActionPkgBox.Text);
                string cls = ActionClsBox.Text.Trim();
                bool isBroadcastType = actType.Equals("broadcast", StringComparison.OrdinalIgnoreCase)
                                       || actType.Equals("root_broadcast", StringComparison.OrdinalIgnoreCase);
                bool looksLikeIntentAction = isBroadcastType
                                             || cls.StartsWith("android.intent.action.", StringComparison.OrdinalIgnoreCase)
                                             || cls.StartsWith("android.settings.", StringComparison.OrdinalIgnoreCase)
                                             || cls.Contains(".intent.action.", StringComparison.OrdinalIgnoreCase)
                                             || cls.Contains("com.hkmc.intent.", StringComparison.OrdinalIgnoreCase);
                if (looksLikeIntentAction)
                {
                    selectedNode.Action.IntentAction = NullIfEmpty(cls);
                    selectedNode.Action.Class = null;
                }
                else
                {
                    selectedNode.Action.Class = NullIfEmpty(cls);
                    selectedNode.Action.IntentAction = null;
                }
            }
        }

        if (GeometrySectionGroup.Visibility == Visibility.Visible)
            CommitGeometryInspector(selectedNode);

        if (StyleSectionGroup.Visibility == Visibility.Visible)
        {
            if (BgGradientRow.Visibility == Visibility.Visible)
            {
                string bgTrim = BgColorBox.Text.Trim();
                if (!string.Equals(bgTrim, inspectorLastBgColorText, StringComparison.Ordinal) || !string.IsNullOrWhiteSpace(selectedNode.Style.BgColor))
                    selectedNode.Style.BgColor = NullIfEmpty(bgTrim);
                selectedNode.Style.BgEndColor = NullIfEmpty(BgEndBox.Text);
            }
            if (BorderColorCell.Visibility == Visibility.Visible)
            {
                string borderTrim = BorderColorBox.Text.Trim();
                if (!string.Equals(borderTrim, inspectorLastBorderColorText, StringComparison.Ordinal) || !string.IsNullOrWhiteSpace(selectedNode.Style.BorderColor))
                    selectedNode.Style.BorderColor = NullIfEmpty(borderTrim);
            }
            if (BorderWidthCell.Visibility == Visibility.Visible)
                selectedNode.Style.BorderWidthPx = ParseOptInt(BorderWidthBox.Text);
            if (AccentColorCell.Visibility == Visibility.Visible)
            {
                string accTrim = AccentColorBox.Text.Trim();
                if (!string.Equals(accTrim, inspectorLastAccentColorText, StringComparison.Ordinal) || !string.IsNullOrWhiteSpace(selectedNode.Style.AccentColor))
                    selectedNode.Style.AccentColor = NullIfEmpty(accTrim);
            }
            if (RadiusCell.Visibility == Visibility.Visible)
            {
                string radTrim = RadiusBox.Text.Trim();
                if (!string.Equals(radTrim, inspectorLastRadiusText, StringComparison.Ordinal) || selectedNode.Style.CornerRadiusPx.HasValue)
                    selectedNode.Style.CornerRadiusPx = ParseOptInt(radTrim);
            }
            if (OpacityCell.Visibility == Visibility.Visible)
                selectedNode.Style.Opacity = ParseOptDouble(OpacityBox.Text);
            if (TransparentCheck.Visibility == Visibility.Visible)
            {
                bool isTrans = TransparentCheck.IsChecked == true;
                selectedNode.Style.Transparent = isTrans ? true : (selectedNode.Style.Transparent.HasValue ? false : null);
            }

            if (TileAnimRow.Visibility == Visibility.Visible)
            {
                string mediaRef = TileAnimBox.Text.Trim();
                if (mediaRef.StartsWith("anim/", StringComparison.OrdinalIgnoreCase) || mediaRef.EndsWith(".gif", StringComparison.OrdinalIgnoreCase))
                {
                    selectedNode.Style.BgAnimAsset = mediaRef;
                    selectedNode.Style.BgBitmapAsset = null;
                }
                else if (!string.IsNullOrEmpty(mediaRef))
                {
                    selectedNode.Style.BgBitmapAsset = mediaRef;
                    selectedNode.Style.BgAnimAsset = null;
                }
                else
                {
                    selectedNode.Style.BgAnimAsset = null;
                    selectedNode.Style.BgBitmapAsset = null;
                }
            }

            if (IdleEffectCell.Visibility == Visibility.Visible)
            {
                string fx = IdleEffectCombo.SelectedItem?.ToString() ?? "none";
                if (fx == "none")
                {
                    if (selectedNode.Animation != null) selectedNode.Animation.IdleEffect = null;
                }
                else
                {
                    selectedNode.Animation ??= new AnimationSpecModel();
                    selectedNode.Animation.IdleEffect = fx;
                }
            }
        }

        StudioCanvas.RebuildScene();
        suppressEvents = true;
        try { PopulateGeometryInspector(selectedNode); }
        finally { suppressEvents = false; }
        SaveAndRecordHistory();
        selectedNode.NotifyLabelChanged();
        SelectionSummaryText.Text = $"Selected: {selectedNode.DisplayLabel}";
    }

    private void PopulateGeometryInspector(LayoutNodeModel node, bool forceRefreshFocusedFields = false)
    {
        bool hasRect = StudioCanvas.TryGetNodeRefRect(node, out Rect liveRect);
        var parent = FindParent(currentPack.Root, node);
        bool inFreeform = parent?.Layout == "freeform";
        bool hasExplicitBounds = inFreeform && node.Bounds is { Length: >= 4 };

        string liveW = hasRect ? Math.Round(liveRect.Width).ToString(CultureInfo.InvariantCulture) : "";
        string liveH = hasRect ? Math.Round(liveRect.Height).ToString(CultureInfo.InvariantCulture) : "";
        inspectorAutoW = liveW;
        inspectorAutoH = liveH;

        int? effectiveW = node.Width ?? (hasExplicitBounds ? (int?)Math.Round(node.Bounds![2]) : null);
        int? effectiveH = node.Height ?? (hasExplicitBounds ? (int?)Math.Round(node.Bounds![3]) : null);

        if (forceRefreshFocusedFields || !WidthBox.IsKeyboardFocused)
            WidthBox.Text = effectiveW?.ToString(CultureInfo.InvariantCulture) ?? liveW;
        WidthBox.Foreground = effectiveW.HasValue ? ExplicitValueBrush : AutoValueBrush;
        WidthBox.ToolTip = effectiveW.HasValue
            ? "Custom fixed width in px (clear to revert to Auto)"
            : $"Auto width (currently {liveW} px) — type a custom px width";

        if (forceRefreshFocusedFields || !HeightBox.IsKeyboardFocused)
            HeightBox.Text = effectiveH?.ToString(CultureInfo.InvariantCulture) ?? liveH;
        HeightBox.Foreground = effectiveH.HasValue ? ExplicitValueBrush : AutoValueBrush;
        HeightBox.ToolTip = effectiveH.HasValue
            ? "Custom fixed height in px (clear to revert to Auto)"
            : $"Auto height (currently {liveH} px) — type a custom px height";

        if (forceRefreshFocusedFields || !FlexBox.IsKeyboardFocused)
            FlexBox.Text = node.FlexWeight?.ToString(CultureInfo.InvariantCulture) ?? "0";
        FlexBox.Foreground = node.FlexWeight.HasValue ? ExplicitValueBrush : AutoValueBrush;

        if (forceRefreshFocusedFields || !ColSpanBox.IsKeyboardFocused)
            ColSpanBox.Text = (node.ColSpan ?? 1).ToString(CultureInfo.InvariantCulture);
        ColSpanBox.Foreground = node.ColSpan.HasValue ? ExplicitValueBrush : AutoValueBrush;

        if (forceRefreshFocusedFields || !RowSpanBox.IsKeyboardFocused)
            RowSpanBox.Text = (node.RowSpan ?? 1).ToString(CultureInfo.InvariantCulture);
        RowSpanBox.Foreground = node.RowSpan.HasValue ? ExplicitValueBrush : AutoValueBrush;

        SelectComboByTag(NodeHAlignCombo, node.HorizontalAlign ?? "");

        string marginStr = node.MarginPx is { Length: >= 4 } ? FormatInsets(node.MarginPx) : "0, 0, 0, 0";
        if (forceRefreshFocusedFields || !MarginBox.IsKeyboardFocused)
            MarginBox.Text = marginStr;
        MarginBox.Foreground = node.MarginPx != null ? ExplicitValueBrush : AutoValueBrush;

        if (node.Bounds is { Length: >= 4 })
        {
            if (forceRefreshFocusedFields || !BoundsXBox.IsKeyboardFocused) BoundsXBox.Text = Math.Round(node.Bounds[0]).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsYBox.IsKeyboardFocused) BoundsYBox.Text = Math.Round(node.Bounds[1]).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsWBox.IsKeyboardFocused) BoundsWBox.Text = Math.Round(node.Bounds[2]).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsHBox.IsKeyboardFocused) BoundsHBox.Text = Math.Round(node.Bounds[3]).ToString(CultureInfo.InvariantCulture);
        }
        else if (inFreeform && StudioCanvas.TryGetNodeLocalRefRect(node, out Rect localRef))
        {
            if (forceRefreshFocusedFields || !BoundsXBox.IsKeyboardFocused) BoundsXBox.Text = Math.Round(localRef.X).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsYBox.IsKeyboardFocused) BoundsYBox.Text = Math.Round(localRef.Y).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsWBox.IsKeyboardFocused) BoundsWBox.Text = Math.Round(localRef.Width).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsHBox.IsKeyboardFocused) BoundsHBox.Text = Math.Round(localRef.Height).ToString(CultureInfo.InvariantCulture);
        }
        else if (hasRect)
        {
            if (forceRefreshFocusedFields || !BoundsXBox.IsKeyboardFocused) BoundsXBox.Text = Math.Round(liveRect.X).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsYBox.IsKeyboardFocused) BoundsYBox.Text = Math.Round(liveRect.Y).ToString(CultureInfo.InvariantCulture);
            if (forceRefreshFocusedFields || !BoundsWBox.IsKeyboardFocused) BoundsWBox.Text = liveW;
            if (forceRefreshFocusedFields || !BoundsHBox.IsKeyboardFocused) BoundsHBox.Text = liveH;
        }
        else
        {
            BoundsXBox.Text = BoundsYBox.Text = BoundsWBox.Text = BoundsHBox.Text = "";
        }

        BoundsXBox.IsReadOnly = BoundsYBox.IsReadOnly = BoundsWBox.IsReadOnly = BoundsHBox.IsReadOnly = !inFreeform;
        var boundsBrush = inFreeform ? ExplicitValueBrush : AutoValueBrush;
        BoundsXBox.Foreground = BoundsYBox.Foreground = BoundsWBox.Foreground = BoundsHBox.Foreground = boundsBrush;
        string boundsTip = inFreeform
            ? "Freeform X/Y/W/H in reference 1920x720 px"
            : "Computed canvas bounds (read-only unless parent panel Layout is 'freeform')";
        BoundsXBox.ToolTip = BoundsYBox.ToolTip = BoundsWBox.ToolTip = BoundsHBox.ToolTip = boundsTip;

        inspectorLastWText = WidthBox.Text.Trim();
        inspectorLastHText = HeightBox.Text.Trim();
        inspectorLastFlexText = FlexBox.Text.Trim();
        inspectorLastColSpanText = ColSpanBox.Text.Trim();
        inspectorLastRowSpanText = RowSpanBox.Text.Trim();
        inspectorLastMarginText = MarginBox.Text.Trim();
        inspectorLastBoundsWText = BoundsWBox.Text.Trim();
        inspectorLastBoundsHText = BoundsHBox.Text.Trim();
    }

    private void CommitGeometryInspector(LayoutNodeModel node)
    {
        var parent = FindParent(currentPack.Root, node);
        bool inFreeform = parent?.Layout == "freeform";
        bool inFlexRow = parent?.Layout == "flex_row";
        bool inFlexCol = parent?.Layout == "flex_col";

        string wTxt = WidthBox.Text.Trim();
        string hTxt = HeightBox.Text.Trim();
        string flexTxt = FlexBox.Text.Trim();
        string csTxt = ColSpanBox.Text.Trim();
        string rsTxt = RowSpanBox.Text.Trim();
        string margTxt = MarginBox.Text.Trim();
        string bwTxt = BoundsWBox.Text.Trim();
        string bhTxt = BoundsHBox.Text.Trim();

        bool wEdited = !string.Equals(wTxt, inspectorLastWText, StringComparison.Ordinal);
        bool hEdited = !string.Equals(hTxt, inspectorLastHText, StringComparison.Ordinal);
        bool flexEdited = !string.Equals(flexTxt, inspectorLastFlexText, StringComparison.Ordinal);
        bool bwEdited = inFreeform && !string.Equals(bwTxt, inspectorLastBoundsWText, StringComparison.Ordinal);
        bool bhEdited = inFreeform && !string.Equals(bhTxt, inspectorLastBoundsHText, StringComparison.Ordinal);

        int? parsedW = ParseOptInt(wTxt);
        int? parsedH = ParseOptInt(hTxt);

        if (wEdited && WidthCell.Visibility == Visibility.Visible)
            node.Width = parsedW is > 0 ? parsedW : null;
        else if (WidthCell.Visibility == Visibility.Visible && node.Width == null && string.Equals(wTxt, inspectorAutoW, StringComparison.Ordinal))
            node.Width = null;

        if (hEdited && HeightCell.Visibility == Visibility.Visible)
            node.Height = parsedH is > 0 ? parsedH : null;
        else if (HeightCell.Visibility == Visibility.Visible && node.Height == null && string.Equals(hTxt, inspectorAutoH, StringComparison.Ordinal))
            node.Height = null;

        double? parsedFlex = ParseOptDouble(flexTxt);
        if (flexEdited && FlexCell.Visibility == Visibility.Visible)
            node.FlexWeight = parsedFlex is > 0 ? parsedFlex : null;
        else if (wEdited && inFlexRow && node.Width is > 0 && node.FlexWeight is > 0)
            node.FlexWeight = null;
        else if (hEdited && inFlexCol && node.Height is > 0 && node.FlexWeight is > 0)
            node.FlexWeight = null;

        if (ColSpanCell.Visibility == Visibility.Visible && (!string.Equals(csTxt, inspectorLastColSpanText, StringComparison.Ordinal) || node.ColSpan.HasValue))
        {
            int? cs = ParseOptInt(csTxt);
            node.ColSpan = cs is > 1 ? cs : null;
        }
        if (RowSpanCell.Visibility == Visibility.Visible && (!string.Equals(rsTxt, inspectorLastRowSpanText, StringComparison.Ordinal) || node.RowSpan.HasValue))
        {
            int? rs = ParseOptInt(rsTxt);
            node.RowSpan = rs is > 1 ? rs : null;
        }

        if (NodeHAlignCell.Visibility == Visibility.Visible)
            node.HorizontalAlign = NullIfEmpty((NodeHAlignCombo.SelectedItem as ComboBoxItem)?.Tag?.ToString());

        if (!string.Equals(margTxt, inspectorLastMarginText, StringComparison.Ordinal) || node.MarginPx != null)
        {
            int[]? parsedMarg = ParseInsets(margTxt);
            node.MarginPx = (parsedMarg != null && parsedMarg.Any(v => v != 0)) ? parsedMarg : null;
        }

        if (inFreeform)
        {
            double? bx = ParseOptDouble(BoundsXBox.Text);
            double? by = ParseOptDouble(BoundsYBox.Text);
            double? bw = ParseOptDouble(bwTxt);
            double? bh = ParseOptDouble(bhTxt);
            if (wEdited && !bwEdited && node.Width is > 0) bw = node.Width.Value;
            else if (bwEdited && bw is > 0) node.Width = (int)Math.Round(bw.Value);
            if (hEdited && !bhEdited && node.Height is > 0) bh = node.Height.Value;
            else if (bhEdited && bh is > 0) node.Height = (int)Math.Round(bh.Value);
            if (bx.HasValue && by.HasValue && bw.HasValue && bh.HasValue)
                node.Bounds = new[] { bx.Value, by.Value, Math.Max(20, bw.Value), Math.Max(20, bh.Value) };
        }
    }

    private void OnTelemetrySensorChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressEvents || selectedNode == null) return;
        PresetCatalog.TelemetrySensorOption? opt =
            e.AddedItems.OfType<PresetCatalog.TelemetrySensorOption>().FirstOrDefault()
            ?? (TelemetryKeyCombo.SelectedItem as PresetCatalog.TelemetrySensorOption);
        if (opt == null) return;
        if (opt.IsHeader)
        {
            suppressEvents = true;
            try { TelemetryKeyCombo.Text = selectedNode.TelemetryKey ?? ""; }
            finally { suppressEvents = false; }
            return;
        }

        string chosenKey = opt.Key;
        suppressEvents = true;
        try
        {
            TelemetryKeyCombo.Text = chosenKey;
            if (!string.IsNullOrEmpty(chosenKey))
            {
                if (selectedNode.Kind is "progress_bar" or "soc_ring" or "ring_gauge")
                {
                    UnitBox.Text = opt.DefaultUnit;
                    MinValBox.Text = opt.DefaultMin.ToString(CultureInfo.InvariantCulture);
                    MaxValBox.Text = opt.DefaultMax.ToString(CultureInfo.InvariantCulture);
                }
                else if (string.IsNullOrWhiteSpace(TitleBox.Text))
                {
                    TitleBox.Text = "{value}";
                }
                else if (selectedNode.Kind is "tile" or "action_pill"
                         && !TitleBox.Text.Contains('{')
                         && string.IsNullOrWhiteSpace(SubtitleBox.Text))
                {
                    SubtitleBox.Text = "{value}";
                }
            }
        }
        finally
        {
            suppressEvents = false;
        }
        CommitInspectorToSelectedNode();

        // Safeguard: ensure WPF's post-selection ComboBox sync does not clear the editable text box.
        var targetNode = selectedNode;
        Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Input, new Action(() =>
        {
            if (!ReferenceEquals(selectedNode, targetNode)) return;
            if (!string.Equals(TelemetryKeyCombo.Text, chosenKey, StringComparison.Ordinal))
            {
                suppressEvents = true;
                try { TelemetryKeyCombo.Text = chosenKey; }
                finally { suppressEvents = false; }
            }
        }));
    }

    private void OnPresetSelected(object sender, SelectionChangedEventArgs e)
    {
        if (suppressEvents || selectedNode == null || PresetCombo.SelectedItem is not ActionPresetOption opt) return;
        if (opt.IsHeader)
        {
            suppressEvents = true;
            try { SelectMatchingPreset(selectedNode.Action ?? new ActionSpecModel()); }
            finally { suppressEvents = false; }
            return;
        }
        if (selectedNode.Action != null)
        {
            selectedNode.Action.ExtraKey = null;
            selectedNode.Action.ExtraType = null;
            selectedNode.Action.ExtraVal = null;
            selectedNode.Action.ExtraIntKey = null;
            selectedNode.Action.ExtraIntValue = null;
            selectedNode.Action.FallbackPackage = null;
            selectedNode.Action.FallbackClass = null;
        }
        suppressEvents = true;
        try
        {
            ActionTypeBox.Text = opt.ActionType;
            ActionCmdBox.Text = opt.Cmd;
            ActionPkgBox.Text = "";
            ActionClsBox.Text = "";
            if (!selectedNode.IsPanel)
            {
                if (string.IsNullOrWhiteSpace(TitleBox.Text) || TitleBox.Text is "New Tile" or "Text Label")
                    TitleBox.Text = opt.DefaultTitle;
                if (string.IsNullOrWhiteSpace(SubtitleBox.Text))
                    SubtitleBox.Text = opt.DefaultSubtitle;
                if (string.IsNullOrWhiteSpace(IconCombo.Text))
                    IconCombo.Text = opt.DefaultIcon;
                if (!string.IsNullOrEmpty(opt.DefaultSensorKey) && string.IsNullOrWhiteSpace(TelemetryKeyCombo.Text))
                {
                    TelemetryKeyCombo.Text = opt.DefaultSensorKey;
                    if (selectedNode.Kind is "tile" or "action_pill"
                        && (string.IsNullOrWhiteSpace(SubtitleBox.Text) || SubtitleBox.Text == opt.DefaultSubtitle))
                    {
                        SubtitleBox.Text = "{value}";
                    }
                }
            }
        }
        finally
        {
            suppressEvents = false;
        }
        CommitInspectorToSelectedNode();
    }

    private void OnClearActionClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null) return;
        suppressEvents = true;
        try
        {
            ActionTypeBox.Text = "none";
            ActionCmdBox.Text = "";
            ActionPkgBox.Text = "";
            ActionClsBox.Text = "";
            PresetCombo.SelectedIndex = 0;
        }
        finally
        {
            suppressEvents = false;
        }
        CommitInspectorToSelectedNode();
    }

    private void FlushInspectorFocusBeforeSelectionChange(LayoutNodeModel? nextNode)
    {
        if (suppressEvents || selectedNode == null || ReferenceEquals(selectedNode, nextNode)) return;
        if (!NodeInspectorPanel.IsKeyboardFocusWithin) return;

        // Commit any active edit to the old node before selection moves so deferred LostFocus
        // cannot overwrite nextNode and focused boxes immediately refresh to nextNode's values.
        if (System.Windows.Input.Keyboard.FocusedElement is TextBox)
        {
            CommitInspectorToSelectedNode();
        }
        suppressEvents = true;
        try
        {
            System.Windows.Input.Keyboard.ClearFocus();
        }
        finally
        {
            suppressEvents = false;
        }
    }

    private void OnSelectParentClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root) return;
        var parent = selectedNode.VirtualOwnerPager ?? FindParent(currentPack.Root, selectedNode) ?? currentPack.Root;
        FlushInspectorFocusBeforeSelectionChange(parent);
        bool switching = !ReferenceEquals(selectedNode, parent);
        SelectNodeInTree(parent);
        StudioCanvas.SetSelected(parent);
        PopulateNodeInspector(parent, forceRefreshFocusedFields: switching);
    }

    private void OnBreakApartClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode.IsPanel) return;
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent?.Children == null) return;
        int idx = parent.Children.IndexOf(selectedNode);
        if (idx < 0) return;

        var converted = PresetCatalog.BreakApartLegacyWidget(selectedNode);
        converted.IsExpanded = true;
        parent.Children[idx] = converted;
        parent.IsExpanded = true;
        BindWholePack(converted);
        SaveAndRecordHistory();
        StatusFooterText.Text = $"Converted '{converted.Id}' into editable panel with individual child elements";
    }

    private void OnCanvasNodeSelected(LayoutNodeModel? node)
    {
        FlushInspectorFocusBeforeSelectionChange(node);
        bool switching = !ReferenceEquals(selectedNode, node);
        SelectNodeInTree(node);
        PopulateNodeInspector(node, forceRefreshFocusedFields: switching);
    }

    private void OnCanvasPackMutated()
    {
        PopulateNodeInspector(selectedNode);
        SaveAndRecordHistory();
        StatusFooterText.Text = "Updated bounds";
    }

    private void OnTreeSelectedChanged(object sender, RoutedPropertyChangedEventArgs<object> e)
    {
        if (suppressEvents || e.NewValue is not LayoutNodeModel node) return;
        FlushInspectorFocusBeforeSelectionChange(node);
        bool switching = !ReferenceEquals(selectedNode, node);
        StudioCanvas.SetSelected(node);
        PopulateNodeInspector(node, forceRefreshFocusedFields: switching);
    }

    private void UpdateSimReadouts()
    {
        if (HvReadout == null) return;
        bool useMph = string.Equals(currentPack.Tokens?.SpeedUnit, "mph", StringComparison.OrdinalIgnoreCase);
        bool useC = string.Equals(currentPack.Tokens?.TempUnit, "C", StringComparison.OrdinalIgnoreCase);

        HvReadout.Text = $"{sim.HvSoc:0}%";
        if (TempReadout != null)
        {
            double extC = (sim.OutsideTempF - 32.0) * 5.0 / 9.0;
            TempReadout.Text = useC ? $"{extC:0}\u00B0C" : $"{sim.OutsideTempF:0}\u00B0F";
            TempReadout.ToolTip = $"{sim.OutsideTempF:0}\u00B0F / {extC:0}\u00B0C";
        }
        if (Aux12Readout != null) Aux12Readout.Text = $"{sim.Aux12vSoc:0}%";
        if (PackTempReadout != null)
        {
            double packF = sim.HvPackTempC * 9.0 / 5.0 + 32.0;
            PackTempReadout.Text = useC
                ? $"{sim.HvPackTempC:0}\u00B0C ({packF:0}\u00B0F)"
                : $"{packF:0}\u00B0F ({sim.HvPackTempC:0}\u00B0C)";
        }
        if (SpeedReadout != null)
        {
            double mph = sim.SpeedKmh * 0.621371;
            SpeedReadout.Text = useMph
                ? $"{mph:0} mph ({sim.SpeedKmh} km/h)"
                : $"{sim.SpeedKmh} km/h ({mph:0} mph)";
        }
    }

    private void OnSimChanged(object sender, RoutedEventArgs e)
    {
        if (!IsLoaded) return;
        sim.HvSoc = HvSlider.Value;
        sim.OutsideTempF = TempSlider.Value;
        if (Aux12Slider != null) sim.Aux12vSoc = Aux12Slider.Value;
        if (PackTempSlider != null) sim.HvPackTempC = PackTempSlider.Value;
        if (SpeedSlider != null) sim.SpeedKmh = (int)Math.Round(SpeedSlider.Value);
        sim.Charging = ChargingCheck.IsChecked == true;
        sim.InPark = ParkCheck.IsChecked == true;
        if (!sim.InPark && sim.SpeedKmh == 0) sim.SpeedKmh = 45;
        UpdateSimReadouts();
        if (!HasActiveTheme) return;

        currentPack.Tokens.ShowSystemBar = TopBarCheck.IsChecked == true;
        UpdatePackHeaderLabel();
        StudioCanvas.RebuildScene();
        workspace.SaveCurrentTheme(currentPack);
        RefreshThemeSelectorCombo(workspace.WorkspaceDir);
    }

    private void UpdatePackHeaderLabel()
    {
        bool bar = currentPack.Tokens.ShowSystemBar;
        string vp = bar ? "1920x630 (90px OEM Bar, scaleY=0.875)" : "1920x720 (Fullscreen 1.0x)";
        string spdU = string.Equals(currentPack.Tokens.SpeedUnit, "mph", StringComparison.OrdinalIgnoreCase) ? "MPH" : "km/h";
        string tmpU = string.Equals(currentPack.Tokens.TempUnit, "C", StringComparison.OrdinalIgnoreCase) ? "\u00B0C" : "\u00B0F";
        PackHeaderText.Text = $"{currentPack.Name} \u2022 {vp} \u2022 {spdU} / {tmpU} \u2022 format v{currentPack.FormatVersion}";
    }

    private void PopulateIndicatorInspector(LayoutNodeModel node)
    {
        var ind = node.Indicator;
        IndModeNoneItem.Visibility = node.IsPanel ? Visibility.Visible : Visibility.Collapsed;

        string mode = (node.IsPanel && node.ShowDots == false)
            ? "none"
            : (!string.IsNullOrWhiteSpace(ind?.Mode) ? ind!.Mode! : "pills");
        SelectComboByTag(IndModeCombo, mode);

        string align = !string.IsNullOrWhiteSpace(ind?.Align) ? ind!.Align!
                     : (!string.IsNullOrWhiteSpace(node.Style?.TextAlign) ? node.Style!.TextAlign! : "center");
        SelectMatchingComboItem(IndAlignCombo, align);

        IndFontCombo.Text = ind?.FontFamily ?? "";
        IndTextSizeBox.Text = ind?.TextSizePx?.ToString(CultureInfo.InvariantCulture) ?? "";
        IndBoldCheck.IsChecked = !string.Equals(ind?.FontWeight, "normal", StringComparison.OrdinalIgnoreCase);
        IndItalicCheck.IsChecked = ind?.FontItalic == true;

        IndActiveTextBox.Text = ind?.ActiveTextColor ?? "";
        IndActiveFillBox.Text = ind?.ActiveFillColor ?? "";
        IndActiveBorderBox.Text = ind?.ActiveBorderColor ?? "";
        IndInactiveTextBox.Text = ind?.InactiveTextColor ?? "";
        IndInactiveFillBox.Text = ind?.InactiveFillColor ?? "";
        IndInactiveBorderBox.Text = ind?.InactiveBorderColor ?? "";

        IndRadiusBox.Text = ind?.CornerRadiusPx?.ToString() ?? "";
        IndBorderWidthBox.Text = ind?.BorderWidthPx?.ToString() ?? "";
        IndGapBox.Text = ind?.GapPx?.ToString() ?? "";
        IndPadXBox.Text = ind?.PadXPx?.ToString() ?? "";
        IndPadYBox.Text = ind?.PadYPx?.ToString() ?? "";
        IndDotWBox.Text = ind?.DotWidthPx?.ToString() ?? "";
        IndDotHBox.Text = ind?.DotHeightPx?.ToString() ?? "";
        IndActiveDotWBox.Text = ind?.ActiveDotWidthPx?.ToString() ?? "";
    }

    private void CommitIndicatorInspector(LayoutNodeModel node)
    {
        bool wasVisible = node.HasVisibleBuiltinIndicator;
        string mode = (IndModeCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "pills";
        if (node.IsPanel)
        {
            node.ShowDots = !string.Equals(mode, "none", StringComparison.OrdinalIgnoreCase);
        }

        node.Indicator ??= new IndicatorStyleModel();
        node.Indicator.Mode = mode;
        node.Indicator.Align = NullIfEmpty((IndAlignCombo.SelectedItem as ComboBoxItem)?.Content?.ToString());
        node.Indicator.FontFamily = NullIfEmpty(IndFontCombo.Text);
        node.Indicator.TextSizePx = ParseOptDouble(IndTextSizeBox.Text);
        node.Indicator.FontWeight = IndBoldCheck.IsChecked == true ? "bold" : "normal";
        node.Indicator.FontItalic = IndItalicCheck.IsChecked == true ? true : null;

        node.Indicator.ActiveTextColor = NullIfEmpty(IndActiveTextBox.Text);
        node.Indicator.ActiveFillColor = NullIfEmpty(IndActiveFillBox.Text);
        node.Indicator.ActiveBorderColor = NullIfEmpty(IndActiveBorderBox.Text);
        node.Indicator.InactiveTextColor = NullIfEmpty(IndInactiveTextBox.Text);
        node.Indicator.InactiveFillColor = NullIfEmpty(IndInactiveFillBox.Text);
        node.Indicator.InactiveBorderColor = NullIfEmpty(IndInactiveBorderBox.Text);

        node.Indicator.CornerRadiusPx = ParseOptInt(IndRadiusBox.Text);
        node.Indicator.BorderWidthPx = ParseOptInt(IndBorderWidthBox.Text);
        node.Indicator.GapPx = ParseOptInt(IndGapBox.Text);
        node.Indicator.PadXPx = ParseOptInt(IndPadXBox.Text);
        node.Indicator.PadYPx = ParseOptInt(IndPadYBox.Text);
        node.Indicator.DotWidthPx = ParseOptInt(IndDotWBox.Text);
        node.Indicator.DotHeightPx = ParseOptInt(IndDotHBox.Text);
        node.Indicator.ActiveDotWidthPx = ParseOptInt(IndActiveDotWBox.Text);

        node.GetOrCreateVirtualIndicatorNode()?.NotifyLabelChanged();
        if (node.IsPanel)
        {
            DisableIndBarBtn.Visibility = node.HasVisibleBuiltinIndicator ? Visibility.Visible : Visibility.Collapsed;
            if (wasVisible != node.HasVisibleBuiltinIndicator)
            {
                node.NotifyChanged(nameof(LayoutNodeModel.TreeChildren));
            }
        }
    }

    private void OnNodeTypeChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressEvents || selectedNode == null) return;
        CommitInspectorToSelectedNode();
        PopulateNodeInspector(selectedNode);
    }
    private void OnInspectorPanelKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key == System.Windows.Input.Key.Enter && e.OriginalSource is TextBox tb && !tb.IsReadOnly)
        {
            e.Handled = true;
            CommitInspectorToSelectedNode();
        }
    }
    private void OnInspectorComboChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressEvents) return;
        if (sender is ComboBox cb && cb.IsEditable && e.AddedItems.Count > 0 && e.AddedItems[0] != null)
        {
            suppressEvents = true;
            try { cb.Text = e.AddedItems[0]?.ToString() ?? ""; }
            finally { suppressEvents = false; }
        }
        CommitInspectorToSelectedNode();
    }
    private void OnInspectorCheckChanged(object sender, RoutedEventArgs e)
    {
        if (suppressEvents || selectedNode == null) return;
        CommitInspectorToSelectedNode();
        if (ReferenceEquals(sender, ShowTextCheck) && selectedNode != null)
        {
            UpdateInspectorFieldVisibility(selectedNode);
        }
    }
    private void OnInspectorTextApplied(object sender, RoutedEventArgs e) => CommitInspectorToSelectedNode();
    private void OnApplyInspectorButtonClick(object sender, RoutedEventArgs e) => CommitInspectorToSelectedNode();

    private void OnBgModeChanged(object sender, SelectionChangedEventArgs e) => OnThemeMetaApplied(sender, e);

    private void OnPackMetaComboChanged(object sender, SelectionChangedEventArgs e) => OnThemeMetaApplied(sender, e);

    private void OnBumpRevisionMetaClick(object sender, RoutedEventArgs e)
    {
        int next = (int.TryParse((PackRevisionBox.Text ?? "").Trim(), out int rev) && rev >= 1) ? rev + 1 : 2;
        PackRevisionBox.Text = next.ToString();
        OnThemeMetaApplied(sender, e);
    }

    private void OnThemeMetaApplied(object sender, RoutedEventArgs e)
    {
        if (suppressEvents || !IsLoaded || !HasActiveTheme) return;
        currentPack.Id = PackWorkspaceService.SanitizeId(PackIdBox.Text);
        currentPack.Name = string.IsNullOrWhiteSpace(PackNameBox.Text) ? "Custom Theme" : PackNameBox.Text.Trim();
        currentPack.Author = string.IsNullOrWhiteSpace(PackAuthorBox.Text) ? "Gen5WStudio" : PackAuthorBox.Text.Trim();
        currentPack.Version = string.IsNullOrWhiteSpace(PackVersionBox.Text) ? "1.0.0" : PackVersionBox.Text.Trim();
        if (int.TryParse((PackRevisionBox.Text ?? "").Trim(), out int rev) && rev >= 1)
            currentPack.Revision = rev;
        if (PackCategoryCombo.SelectedItem is string cat && !string.IsNullOrWhiteSpace(cat))
            currentPack.Category = cat.Trim();
        var compat = (PackCompatBox.Text ?? "")
            .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(s => !string.IsNullOrWhiteSpace(s))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (compat.Count > 0)
        {
            currentPack.CompatibleVersions = compat;
            if (string.IsNullOrWhiteSpace(currentPack.MinAppVersion))
                currentPack.MinAppVersion = PresetCatalog.DefaultMinAppVersion;
        }
        currentPack.Description = string.IsNullOrWhiteSpace(PackDescBox.Text) ? null : PackDescBox.Text.Trim();
        ThemePackageService.StampPackDatesForRelease(currentPack);
        PackUpdatedText.Text = currentPack.UpdatedAt ?? "";
        currentPack.Tokens ??= new ThemeTokensModel();
        currentPack.Tokens.SpeedUnit = (ThemeSpeedUnitCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "kmh";
        currentPack.Tokens.TempUnit = (ThemeTempUnitCombo.SelectedItem as ComboBoxItem)?.Tag as string ?? "F";

        currentPack.Background.Type = BgModeCombo.SelectedItem?.ToString() ?? "animated_aurora";
        currentPack.Background.PrimaryColor = BgPrimaryBox.Text.Trim();
        currentPack.Background.SecondaryColor = BgSecondaryBox.Text.Trim();
        currentPack.Background.AccentColor = BgAccentBox.Text.Trim();
        string dimTrim = BgDimBox.Text.Trim();
        currentPack.Background.DimColor = string.IsNullOrEmpty(dimTrim) ? "#00000000" : dimTrim;

        UpdateSimReadouts();
        UpdatePackHeaderLabel();
        StudioCanvas.RebuildScene();
        SaveAndRecordHistory();
        RefreshThemeSelectorCombo(workspace.WorkspaceDir);
    }

    private LayoutNodeModel ResolveTargetPanel()
    {
        if (selectedNode == null) return currentPack.Root;
        if (selectedNode.IsPanel) return selectedNode;
        return FindParent(currentPack.Root, selectedNode) ?? currentPack.Root;
    }

    private void AddWidgetToTargetPanel(LayoutNodeModel widget)
    {
        var panel = ResolveTargetPanel();
        if (panel.Layout == "pager")
        {
            var page = panel.Children?.ElementAtOrDefault(StudioCanvas.GetPagerPageIndex(panel.Id));
            if (page != null && page.IsPanel) panel = page;
        }
        panel.Children ??= new List<LayoutNodeModel>();
        if (panel.Layout == "freeform" && widget.Bounds == null)
        {
            double defW = widget.Kind == "progress_bar" ? 320
                        : widget.Kind == "page_indicator" ? 360
                        : (widget.Kind is "icon" or "image" ? 96 : (widget.Layout == "pager" ? 900 : (widget.IsPanel ? 380 : 320)));
            double defH = widget.Kind == "progress_bar" ? 28
                        : widget.Kind == "page_indicator" ? 44
                        : (widget.Kind is "icon" or "image" ? 96 : (widget.Layout == "pager" ? 420 : (widget.IsPanel ? 240 : 120)));
            double pInnerW = StudioCanvas.TryGetPanelInnerRefRect(panel, out Rect pInner) ? pInner.Width : (currentPack.ReferenceWidth);
            double pInnerH = StudioCanvas.TryGetPanelInnerRefRect(panel, out pInner) ? pInner.Height : (currentPack.ReferenceHeight);
            defW = Math.Max(40, Math.Min(defW, Math.Max(40, pInnerW - 24)));
            defH = Math.Max(28, Math.Min(defH, Math.Max(28, pInnerH - 24)));
            double baseX = ReferenceEquals(panel, currentPack.Root) ? 80 : 24;
            double baseY = ReferenceEquals(panel, currentPack.Root) ? 140 : 24;
            double x = Math.Clamp(baseX + panel.Children.Count * 20, 0, Math.Max(0, pInnerW - defW));
            double y = Math.Clamp(baseY + panel.Children.Count * 16, 0, Math.Max(0, pInnerH - defH));
            widget.Bounds = new double[] { Math.Round(x), Math.Round(y), Math.Round(defW), Math.Round(defH) };
        }
        panel.Children.Add(widget);
        panel.IsExpanded = true;
        BindWholePack(widget);
        SaveAndRecordHistory();
    }

    private void OnAddTextClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "txt_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "text",
        Title = "Custom Text",
        Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 24, FontWeight = "bold", TextColor = "#FFF1F5F9" }
    });

    private void OnAddProgressBarClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "bar_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "progress_bar",
        TelemetryKey = "hv.soc",
        MinValue = 0,
        MaxValue = 100,
        Orientation = "horizontal",
        Height = 22,
        Style = new WidgetStyleModel { BgColor = "#55293B52", AccentColor = "#FF38BDF8", BgEndColor = "#FF10B981", CornerRadiusPx = 11 }
    });

    private void OnAddRingClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "ring_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "soc_ring",
        TelemetryKey = "hv.soc",
        MinValue = 0,
        MaxValue = 100,
        Title = "Battery",
        Style = new WidgetStyleModel { Transparent = true, AccentColor = "#FF38BDF8", IconSizePx = 96 }
    });

    private void OnAddImageClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "img_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "icon",
        Icon = "icons/ev.svg",
        Width = 64,
        Height = 64,
        ScaleType = "fit",
        Style = new WidgetStyleModel { Transparent = true, IconSizePx = 56 }
    });

    private void OnAddClockClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "clock_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "clock",
        Width = 220,
        Style = new WidgetStyleModel { Transparent = true, TitleSizePx = 30, SubtitleSizePx = 14 }
    });

    private void OnAddSpacerClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "spacer_" + Guid.NewGuid().ToString("N")[..5],
        Kind = "spacer",
        FlexWeight = 1
    });

    private void OnAddPanelClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(new LayoutNodeModel
    {
        Id = "panel_" + Guid.NewGuid().ToString("N")[..5],
        Type = "panel",
        Layout = "flex_col",
        GapPx = 10,
        FlexWeight = 1,
        Style = new WidgetStyleModel { BgColor = "#B8111D2D", BorderColor = "#4438BDF8", BorderWidthPx = 2, CornerRadiusPx = 20, PaddingPx = new[] { 18, 16, 18, 16 } },
        Children = new List<LayoutNodeModel>()
    });

    private void OnAddPresetEvHeroClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(PresetCatalog.CreatePresetEvHeroPanel());
    private void OnAddPresetTileClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(PresetCatalog.CreatePresetActionTilePanel());
    private void OnAddPresetPillClick(object sender, RoutedEventArgs e) => AddWidgetToTargetPanel(PresetCatalog.CreatePresetActionPillPanel());

    private void OnAddPageClick(object sender, RoutedEventArgs e)
    {
        var pager = FindPager(currentPack.Root);
        if (pager == null)
        {
            MessageBox.Show("Current root layout does not contain a [pager] container.", "Gen5W Studio");
            return;
        }
        pager.Children ??= new List<LayoutNodeModel>();
        var page = new LayoutNodeModel
        {
            Id = "page_" + (pager.Children.Count + 1),
            PageTitle = "Page " + (pager.Children.Count + 1),
            Type = "panel",
            Layout = "grid",
            Columns = 3,
            Rows = 2,
            GapPx = 16,
            Style = new WidgetStyleModel { Transparent = true },
            Children = new List<LayoutNodeModel>()
        };
        pager.Children.Add(page);
        StudioCanvas.SetPagerPageIndex(pager.Id, pager.Children.Count - 1);
        BindWholePack(page);
        SaveAndRecordHistory();
    }

    private void OnAddPagerClick(object sender, RoutedEventArgs e)
    {
        var pager = new LayoutNodeModel
        {
            Id = "pager_" + Guid.NewGuid().ToString("N")[..5],
            Type = "panel",
            Layout = "pager",
            FlexWeight = 1,
            ShowDots = true,
            Indicator = new IndicatorStyleModel { Mode = "pills", Align = "center" },
            Style = new WidgetStyleModel { Transparent = true },
            Children = new List<LayoutNodeModel>
            {
                new LayoutNodeModel
                {
                    Id = "page_1",
                    PageTitle = "Page 1",
                    Type = "panel",
                    Layout = "grid",
                    Columns = 3,
                    Rows = 2,
                    GapPx = 16,
                    Style = new WidgetStyleModel { Transparent = true },
                    Children = new List<LayoutNodeModel>()
                },
                new LayoutNodeModel
                {
                    Id = "page_2",
                    PageTitle = "Page 2",
                    Type = "panel",
                    Layout = "grid",
                    Columns = 3,
                    Rows = 2,
                    GapPx = 16,
                    Style = new WidgetStyleModel { Transparent = true },
                    Children = new List<LayoutNodeModel>()
                }
            }
        };
        AddWidgetToTargetPanel(pager);
    }

    private LayoutNodeModel? AddStandalonePageIndicatorOutsidePages(LayoutNodeModel? preferredNode, out string? blockReason)
    {
        blockReason = null;
        LayoutNodeModel cursor = preferredNode ?? currentPack.Root;
        if (cursor.VirtualOwnerPager != null) cursor = cursor.VirtualOwnerPager;
        if (!cursor.IsPanel) cursor = FindParent(currentPack.Root, cursor) ?? currentPack.Root;

        LayoutNodeModel? afterNode = null;
        while (cursor != null)
        {
            var parent = FindParent(currentPack.Root, cursor);
            if (string.Equals(cursor.Layout, "pager", StringComparison.OrdinalIgnoreCase))
            {
                if (parent == null)
                {
                    blockReason = "Root container itself is a [pager]. Wrap the pager inside a [flex_col] or [freeform] panel to place a standalone page bar outside it, or customize the pager's built-in [indicator] Page Bar.";
                    return null;
                }
                afterNode = cursor;
                cursor = parent;
                continue;
            }
            if (parent != null && string.Equals(parent.Layout, "pager", StringComparison.OrdinalIgnoreCase))
            {
                cursor = parent;
                continue;
            }
            break;
        }

        var container = cursor ?? currentPack.Root;
        container.Children ??= new List<LayoutNodeModel>();

        var widget = new LayoutNodeModel
        {
            Id = "pind_" + Guid.NewGuid().ToString("N")[..5],
            Kind = "page_indicator",
            Indicator = new IndicatorStyleModel { Mode = "pills", Align = "center" },
            Style = new WidgetStyleModel { Transparent = true }
        };

        if (container.Layout == "freeform")
        {
            if (afterNode?.Bounds is { Length: >= 4 } pb)
                widget.Bounds = new double[] { Math.Round(pb[0] + Math.Max(0, (pb[2] - 380) * 0.5)), Math.Round(Math.Min(660, pb[1] + pb[3] + 8)), 380, 42 };
            else
                widget.Bounds = new double[] { 770, 620, 380, 42 };
        }
        else if (container.Layout == "flex_row")
        {
            widget.Width = 260;
        }
        else
        {
            widget.Height = 42;
        }

        int idx = afterNode != null ? container.Children.IndexOf(afterNode) : -1;
        if (idx >= 0) container.Children.Insert(idx + 1, widget);
        else container.Children.Add(widget);
        container.IsExpanded = true;
        return widget;
    }

    private void OnAddPageIndicatorClick(object sender, RoutedEventArgs e)
    {
        var widget = AddStandalonePageIndicatorOutsidePages(selectedNode, out string? blockReason);
        if (widget == null)
        {
            MessageBox.Show(this, blockReason!, "Add Page Bar", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        BindWholePack(widget);
        workspace.SaveCurrentTheme(currentPack);
        StatusFooterText.Text = $"Added standalone page bar '{widget.Id}' outside individual pages (shared across all pages)";
    }

    private void OnDisableIndicatorBarClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null) return;
        if (selectedNode.VirtualOwnerPager != null || (selectedNode.IsPanel && string.Equals(selectedNode.Layout, "pager", StringComparison.OrdinalIgnoreCase)))
        {
            var pager = selectedNode.VirtualOwnerPager ?? selectedNode;
            DisablePagerBuiltinIndicator(pager);
            return;
        }
        if (!selectedNode.IsPanel && string.Equals(selectedNode.Kind, "page_indicator", StringComparison.OrdinalIgnoreCase))
        {
            OnDeleteClick(sender, e);
        }
    }

    private void DisablePagerBuiltinIndicator(LayoutNodeModel pager)
    {
        pager.ShowDots = false;
        pager.Indicator ??= new IndicatorStyleModel();
        pager.Indicator.Mode = "none";
        BindWholePack(pager);
        workspace.SaveCurrentTheme(currentPack);
        StatusFooterText.Text = $"Removed built-in page indicator bar on '{pager.Id}'";
    }

    private void OnMoveUpClick(object sender, RoutedEventArgs e) => MoveSelectedSibling(-1);
    private void OnMoveDownClick(object sender, RoutedEventArgs e) => MoveSelectedSibling(+1);

    private static bool IsDescendant(LayoutNodeModel root, LayoutNodeModel candidate)
    {
        if (root.Children == null) return false;
        foreach (var ch in root.Children)
        {
            if (ReferenceEquals(ch, candidate) || IsDescendant(ch, candidate)) return true;
        }
        return false;
    }

    private bool CanMoveNodeInto(LayoutNodeModel? node, LayoutNodeModel? targetPanel, out string? reason)
    {
        reason = null;
        if (node == null || node == currentPack.Root)
        {
            reason = "Root container cannot be moved.";
            return false;
        }
        if (node.VirtualOwnerPager != null)
        {
            reason = "Built-in indicator bar belongs to its [pager] container.";
            return false;
        }
        if (targetPanel == null || !targetPanel.IsPanel || targetPanel.VirtualOwnerPager != null)
        {
            reason = "Destination must be a panel container.";
            return false;
        }
        if (ReferenceEquals(node, targetPanel) || IsDescendant(node, targetPanel))
        {
            reason = "Cannot move a panel inside itself.";
            return false;
        }
        if (string.Equals(targetPanel.Layout, "pager", StringComparison.OrdinalIgnoreCase) && !node.IsPanel)
        {
            reason = "Only panel pages belong directly inside a [pager] (move into a page panel inside the pager instead).";
            return false;
        }
        return true;
    }

    private bool ReparentNode(LayoutNodeModel node, LayoutNodeModel targetPanel, int? insertIndex = null)
    {
        if (!CanMoveNodeInto(node, targetPanel, out string? reason))
        {
            StatusFooterText.Text = reason ?? "Cannot move selected element there.";
            return false;
        }
        var oldParent = FindParent(currentPack.Root, node);
        if (oldParent?.Children == null) return false;

        int oldIdx = oldParent.Children.IndexOf(node);
        if (oldIdx < 0) return false;

        targetPanel.Children ??= new List<LayoutNodeModel>();
        int dstIdx = insertIndex ?? targetPanel.Children.Count;
        if (ReferenceEquals(oldParent, targetPanel) && oldIdx < dstIdx)
        {
            dstIdx--;
        }
        dstIdx = Math.Clamp(dstIdx, 0, targetPanel.Children.Count);

        Rect? prevRefRect = StudioCanvas.TryGetNodeRefRect(node, out Rect r) ? r : null;
        oldParent.Children.RemoveAt(oldIdx);

        if (string.Equals(targetPanel.Layout, "freeform", StringComparison.OrdinalIgnoreCase))
        {
            if (!ReferenceEquals(oldParent, targetPanel))
            {
                double w = Math.Max(20, prevRefRect?.Width > 10 ? Math.Round(prevRefRect.Value.Width)
                         : (node.Bounds is { Length: >= 4 } ? Math.Round(node.Bounds[2]) : (node.Width ?? 320)));
                double h = Math.Max(20, prevRefRect?.Height > 10 ? Math.Round(prevRefRect.Value.Height)
                         : (node.Bounds is { Length: >= 4 } ? Math.Round(node.Bounds[3]) : (node.Height ?? 140)));
                if (prevRefRect.HasValue && StudioCanvas.TryGetPanelInnerRefRect(targetPanel, out Rect dstInner))
                {
                    double relX = Math.Clamp(Math.Round(prevRefRect.Value.X - dstInner.X), 0, Math.Max(0, Math.Round(dstInner.Width - w)));
                    double relY = Math.Clamp(Math.Round(prevRefRect.Value.Y - dstInner.Y), 0, Math.Max(0, Math.Round(dstInner.Height - h)));
                    node.Bounds = new[] { relX, relY, w, h };
                }
                else if (node.Bounds is not { Length: >= 4 })
                {
                    node.Bounds = new[] { 24, 24, w, h };
                }
            }
            else if (node.Bounds is not { Length: >= 4 })
            {
                double w = prevRefRect?.Width > 10 ? Math.Round(prevRefRect.Value.Width) : (node.Width ?? 320);
                double h = prevRefRect?.Height > 10 ? Math.Round(prevRefRect.Value.Height) : (node.Height ?? 140);
                node.Bounds = new[] { 24, 24, Math.Max(20, w), Math.Max(20, h) };
            }
        }
        else if (string.Equals(oldParent.Layout, "freeform", StringComparison.OrdinalIgnoreCase))
        {
            node.Bounds = null;
        }

        targetPanel.Children.Insert(Math.Clamp(dstIdx, 0, targetPanel.Children.Count), node);
        targetPanel.IsExpanded = true;
        BindWholePack(node);
        workspace.SaveCurrentTheme(currentPack);
        StatusFooterText.Text = $"Moved '{node.Id}' inside [{targetPanel.Layout ?? "panel"}] '{targetPanel.Id}'";
        return true;
    }

    private LayoutNodeModel? FindAdjacentSiblingPanel(LayoutNodeModel node, out string? blockReason)
    {
        blockReason = null;
        var parent = FindParent(currentPack.Root, node);
        if (parent?.Children == null || parent.Children.Count < 2)
        {
            blockReason = "No adjacent sibling panel to nest into. Use 'Wrap' to create a [flex_col] around this item, or 'Move To...' / Drag in Tree.";
            return null;
        }
        int idx = parent.Children.IndexOf(node);
        var order = new List<int>();
        if (idx > 0) order.Add(idx - 1);
        if (idx >= 0 && idx + 1 < parent.Children.Count) order.Add(idx + 1);
        for (int offset = 2; offset < parent.Children.Count; offset++)
        {
            if (idx - offset >= 0) order.Add(idx - offset);
            if (idx + offset < parent.Children.Count) order.Add(idx + offset);
        }

        foreach (int i in order)
        {
            var cand = parent.Children[i];
            if (cand != node && CanMoveNodeInto(node, cand, out _)) return cand;
            if (cand != node && cand.IsPanel && string.Equals(cand.Layout, "pager", StringComparison.OrdinalIgnoreCase) && !node.IsPanel)
            {
                var activePage = cand.Children?.ElementAtOrDefault(StudioCanvas.GetPagerPageIndex(cand.Id));
                if (activePage != null && CanMoveNodeInto(node, activePage, out _)) return activePage;
            }
        }
        blockReason = "No adjacent panel sibling available next to this item. Use 'Wrap' to create a [flex_col] group, or 'Move To...' / Drag in Tree.";
        return null;
    }

    private void OnNestIntoSiblingClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var target = FindAdjacentSiblingPanel(selectedNode, out string? reason);
        if (target == null)
        {
            MessageBox.Show(this, reason!, "Nest Inside Panel", MessageBoxButton.OK, MessageBoxImage.Information);
            return;
        }
        ReparentNode(selectedNode, target);
    }

    private void OnUnnestToParentClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent == null || ReferenceEquals(parent, currentPack.Root))
        {
            StatusFooterText.Text = "Already at top level under root.";
            return;
        }
        var grandParent = FindParent(currentPack.Root, parent);
        while (grandParent != null && string.Equals(grandParent.Layout, "pager", StringComparison.OrdinalIgnoreCase) && !selectedNode.IsPanel)
        {
            parent = grandParent;
            grandParent = FindParent(currentPack.Root, parent);
        }
        if (grandParent?.Children == null)
        {
            StatusFooterText.Text = "Cannot unnest above root container.";
            return;
        }
        int insertAt = grandParent.Children.IndexOf(parent) + 1;
        ReparentNode(selectedNode, grandParent, insertAt);
    }

    private void OnWrapInPanelClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent?.Children == null) return;
        int idx = parent.Children.IndexOf(selectedNode);
        if (idx < 0) return;

        var inner = selectedNode;
        const int wrapPad = 12;
        bool inFreeform = string.Equals(parent.Layout, "freeform", StringComparison.OrdinalIgnoreCase);
        bool inPager = string.Equals(parent.Layout, "pager", StringComparison.OrdinalIgnoreCase);

        int[] padArr = new[] { wrapPad, wrapPad, wrapPad, wrapPad };
        var group = new LayoutNodeModel
        {
            Id = "group_" + Guid.NewGuid().ToString("N")[..5],
            Type = "panel",
            Layout = "flex_col",
            PageTitle = inPager ? inner.PageTitle : null,
            GapPx = 10,
            PaddingPx = padArr,
            ColSpan = inner.ColSpan,
            RowSpan = inner.RowSpan,
            FlexWeight = inner.FlexWeight,
            HorizontalAlign = inner.HorizontalAlign,
            MarginPx = inner.MarginPx,
            Style = new WidgetStyleModel { Transparent = true, PaddingPx = (int[])padArr.Clone() },
            IsExpanded = true,
            Children = new List<LayoutNodeModel> { inner }
        };

        if (inFreeform)
        {
            Rect fallbackRect = StudioCanvas.TryGetNodeLocalRefRect(inner, out Rect r) ? r : new Rect(24, 24, inner.Width ?? 320, inner.Height ?? 140);
            double[] b = inner.Bounds is { Length: >= 4 } ? inner.Bounds : new[] { fallbackRect.X, fallbackRect.Y, fallbackRect.Width, fallbackRect.Height };
            group.Bounds = new[] { b[0], b[1], b[2] + wrapPad * 2, b[3] + wrapPad * 2 };
            inner.Width ??= (int)Math.Round(b[2]);
            inner.Height ??= (int)Math.Round(b[3]);
        }
        else
        {
            // Preserve explicit dimensions (+ 24px for outer wrap padding) so margins inside [grid] or [flex] never crush the new wrapper to 1px!
            if (inner.Width is > 0) group.Width = inner.Width.Value + wrapPad * 2;
            if (inner.Height is > 0) group.Height = inner.Height.Value + wrapPad * 2;

            // If wrapped child relied on parent cell/row auto-height (no fixed Height), stretch inside the new flex_col wrapper
            if (inner.Height == null)
            {
                inner.FlexWeight = (parent.Layout == "flex_col" && inner.FlexWeight is > 0) ? inner.FlexWeight : 1.0;
            }
            else if (parent.Layout != "flex_col" || inner.FlexWeight == group.FlexWeight)
            {
                inner.FlexWeight = null;
            }
        }

        if (inPager) inner.PageTitle = null;
        inner.ColSpan = null;
        inner.RowSpan = null;
        inner.HorizontalAlign = null;
        inner.Bounds = null;
        inner.MarginPx = null;

        parent.Children[idx] = group;
        parent.IsExpanded = true;
        BindWholePack(group);
        workspace.SaveCurrentTheme(currentPack);
        StatusFooterText.Text = $"Wrapped '{inner.Id}' inside new [flex_col] '{group.Id}' (12px outer frame ready for custom background)";
    }

    private static void CollectPanelsWithDepth(LayoutNodeModel node, string pathPrefix, List<(LayoutNodeModel Panel, string Display)> acc)
    {
        if (!node.IsPanel) return;
        string name = !string.IsNullOrWhiteSpace(node.PageTitle) ? node.PageTitle! : node.Id;
        string label = $"{pathPrefix}[{node.Layout ?? "panel"}] {name}";
        acc.Add((node, label));
        if (node.Children == null) return;
        foreach (var child in node.Children)
        {
            if (child.IsPanel) CollectPanelsWithDepth(child, pathPrefix + "    ", acc);
        }
    }

    private void OnMoveToPanelClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var targetItem = selectedNode;
        var currentParent = FindParent(currentPack.Root, targetItem);
        var panels = new List<(LayoutNodeModel Panel, string Display)>();
        CollectPanelsWithDepth(currentPack.Root, "", panels);

        var cm = new ContextMenu
        {
            Background = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(17, 26, 41)),
            Foreground = ExplicitValueBrush,
            BorderBrush = new System.Windows.Media.SolidColorBrush(System.Windows.Media.Color.FromRgb(51, 78, 115)),
            BorderThickness = new Thickness(1)
        };

        foreach (var (panel, display) in panels)
        {
            bool valid = CanMoveNodeInto(targetItem, panel, out _);
            bool sameParent = ReferenceEquals(panel, currentParent);
            var mi = new MenuItem
            {
                Header = sameParent ? $"{display}  (current parent)" : display,
                IsEnabled = valid && !sameParent,
                Foreground = valid && !sameParent ? ExplicitValueBrush : AutoValueBrush,
                Background = cm.Background
            };
            var dest = panel;
            mi.Click += (_, _) => ReparentNode(targetItem, dest);
            cm.Items.Add(mi);
        }

        cm.PlacementTarget = MoveToBtn;
        cm.Placement = System.Windows.Controls.Primitives.PlacementMode.Top;
        cm.IsOpen = true;
    }

    private static T? FindVisualAncestor<T>(DependencyObject? d) where T : DependencyObject
    {
        while (d != null)
        {
            if (d is T hit) return hit;
            d = System.Windows.Media.VisualTreeHelper.GetParent(d);
        }
        return null;
    }

    private (LayoutNodeModel? DestPanel, int? InsertIndex) ResolveDropDestination(LayoutNodeModel dragged, LayoutNodeModel? hoveredTarget)
    {
        if (hoveredTarget == null || hoveredTarget.VirtualOwnerPager != null) return (null, null);
        if (hoveredTarget.IsPanel)
        {
            return CanMoveNodeInto(dragged, hoveredTarget, out _) ? (hoveredTarget, null) : (null, null);
        }
        var par = FindParent(currentPack.Root, hoveredTarget);
        if (par?.Children == null || !CanMoveNodeInto(dragged, par, out _)) return (null, null);
        int idx = par.Children.IndexOf(hoveredTarget);
        return (par, idx >= 0 ? idx + 1 : null);
    }

    private void OnTreePreviewMouseDown(object sender, System.Windows.Input.MouseButtonEventArgs e)
    {
        treeDragNode = null;
        if (FindVisualAncestor<System.Windows.Controls.Primitives.ToggleButton>(e.OriginalSource as DependencyObject) != null
            || FindVisualAncestor<System.Windows.Controls.Primitives.ScrollBar>(e.OriginalSource as DependencyObject) != null)
            return;
        treeDragStart = e.GetPosition(HierarchyTree);
        var tvi = FindVisualAncestor<TreeViewItem>(e.OriginalSource as DependencyObject);
        if (tvi?.DataContext is LayoutNodeModel node && node != currentPack.Root && node.VirtualOwnerPager == null)
        {
            treeDragNode = node;
        }
    }

    private void OnTreePreviewMouseMove(object sender, System.Windows.Input.MouseEventArgs e)
    {
        if (e.LeftButton != System.Windows.Input.MouseButtonState.Pressed || treeDragNode == null) return;
        Point pt = e.GetPosition(HierarchyTree);
        if (Math.Abs(pt.X - treeDragStart.X) < SystemParameters.MinimumHorizontalDragDistance + 3
            && Math.Abs(pt.Y - treeDragStart.Y) < SystemParameters.MinimumVerticalDragDistance + 3)
            return;
        var dragged = treeDragNode;
        treeDragNode = null;
        DragDrop.DoDragDrop(HierarchyTree, dragged, DragDropEffects.Move);
    }

    private void OnTreeDragOver(object sender, DragEventArgs e)
    {
        e.Effects = DragDropEffects.None;
        e.Handled = true;
        if (!e.Data.GetDataPresent(typeof(LayoutNodeModel))) return;
        if (e.Data.GetData(typeof(LayoutNodeModel)) is not LayoutNodeModel dragged) return;
        var tvi = FindVisualAncestor<TreeViewItem>(e.OriginalSource as DependencyObject);
        var (destPanel, _) = ResolveDropDestination(dragged, tvi?.DataContext as LayoutNodeModel);
        if (destPanel != null)
        {
            e.Effects = DragDropEffects.Move;
        }
    }

    private void OnTreeDrop(object sender, DragEventArgs e)
    {
        e.Handled = true;
        if (!e.Data.GetDataPresent(typeof(LayoutNodeModel))) return;
        if (e.Data.GetData(typeof(LayoutNodeModel)) is not LayoutNodeModel dragged) return;
        var tvi = FindVisualAncestor<TreeViewItem>(e.OriginalSource as DependencyObject);
        var (destPanel, insertIdx) = ResolveDropDestination(dragged, tvi?.DataContext as LayoutNodeModel);
        if (destPanel != null)
        {
            ReparentNode(dragged, destPanel, insertIdx);
        }
    }

    private void MoveSelectedSibling(int delta)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent?.Children == null) return;
        int idx = parent.Children.IndexOf(selectedNode);
        int dst = idx + delta;
        if (idx < 0 || dst < 0 || dst >= parent.Children.Count) return;
        parent.Children.RemoveAt(idx);
        parent.Children.Insert(dst, selectedNode);
        BindWholePack(selectedNode);
        SaveAndRecordHistory();
    }

    private static void CollectAllNodeIds(LayoutNodeModel node, HashSet<string> ids)
    {
        if (!string.IsNullOrWhiteSpace(node.Id)) ids.Add(node.Id);
        if (node.Children == null) return;
        foreach (var ch in node.Children) CollectAllNodeIds(ch, ids);
    }

    private void OnDuplicateClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root || selectedNode.VirtualOwnerPager != null) return;
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent?.Children == null) return;
        var existingIds = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        CollectAllNodeIds(currentPack.Root, existingIds);
        var dup = selectedNode.DeepCloneWithFreshIds(existingIds);
        if (dup.Bounds is { Length: >= 4 }) { dup.Bounds[0] += 24; dup.Bounds[1] += 24; }
        parent.Children.Add(dup);
        BindWholePack(dup);
        SaveAndRecordHistory();
    }

    private void OnDeleteClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode == currentPack.Root) return;
        if (selectedNode.VirtualOwnerPager is LayoutNodeModel ownerPager)
        {
            DisablePagerBuiltinIndicator(ownerPager);
            return;
        }
        var parent = FindParent(currentPack.Root, selectedNode);
        if (parent?.Children == null) return;
        int removedIdx = parent.Children.IndexOf(selectedNode);
        parent.Children.Remove(selectedNode);
        LayoutNodeModel nextFocus = parent.Children.Count > 0
            ? parent.Children[Math.Clamp(removedIdx, 0, parent.Children.Count - 1)]
            : parent;
        parent.IsExpanded = true;
        BindWholePack(nextFocus);
        SaveAndRecordHistory();
    }

    private void OnImportFontClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode.IsPanel) return;
        var dlg = new OpenFileDialog { Filter = "TrueType / OpenType Fonts (*.ttf;*.otf)|*.ttf;*.otf" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            string rel = MediaConverterService.ImportFont(dlg.FileName, workspace.WorkspaceDir);
            RefreshFontsDropdown();
            FontCombo.Text = rel;
            CommitInspectorToSelectedNode();
            StatusFooterText.Text = $"Imported font: {rel}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Font Import Error"); }
    }

    private void OnImportSvgClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode.IsPanel) return;
        var dlg = new OpenFileDialog { Filter = "SVG Vector Icons (*.svg)|*.svg" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            string rel = MediaConverterService.ImportSvg(dlg.FileName, workspace.WorkspaceDir);
            RefreshIconsDropdown();
            IconCombo.Text = rel;
            CommitInspectorToSelectedNode();
            StatusFooterText.Text = $"Imported SVG: {rel}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "SVG Import Error"); }
    }

    private void OnImportImageClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null || selectedNode.IsPanel) return;
        var dlg = new OpenFileDialog { Filter = "Images (*.png;*.jpg;*.jpeg;*.webp;*.bmp)|*.png;*.jpg;*.jpeg;*.webp;*.bmp" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            string rel = MediaConverterService.ImportElementImage(dlg.FileName, workspace.WorkspaceDir);
            RefreshIconsDropdown();
            IconCombo.Text = rel;
            if (selectedNode.Kind is not ("image" or "icon"))
            {
                NodeTypeCombo.SelectedItem = "image";
            }
            CommitInspectorToSelectedNode();
            StatusFooterText.Text = $"Imported Image: {rel}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Image Import Error"); }
    }

    private void OnImportWallpaperClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Filter = "Images (*.png;*.jpg;*.jpeg;*.bmp)|*.png;*.jpg;*.jpeg;*.bmp" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            string rel = MediaConverterService.ImportStaticBitmap(dlg.FileName, workspace.WorkspaceDir, 1920, 720);
            currentPack.Background.Type = "bitmap";
            currentPack.Background.BitmapAsset = rel;
            BindWholePack(selectedNode);
            SaveAndRecordHistory();
            StatusFooterText.Text = $"Imported static wallpaper: {rel}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Wallpaper Error"); }
    }

    private async void OnImportVideoWallpaperClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFileDialog { Filter = "Video / Animated GIF (*.mp4;*.mov;*.wmv;*.m4v;*.avi;*.gif)|*.mp4;*.mov;*.wmv;*.m4v;*.avi;*.gif|All files (*.*)|*.*" };
        if (dlg.ShowDialog() != true) return;

        int w = 960, h = 360;
        if (VideoResCombo.SelectedItem is ComboBoxItem item && item.Tag is string tag)
        {
            var parts = tag.Split(',');
            if (parts.Length == 2) { int.TryParse(parts[0], out w); int.TryParse(parts[1], out h); }
        }
        int fps = Math.Clamp(ParseOptInt(VideoFpsBox.Text) ?? 16, 4, 24);
        int secs = Math.Clamp(ParseOptInt(VideoSecBox.Text) ?? 6, 1, 15);

        ConvertVideoBgBtn.IsEnabled = false;
        var prog = new Progress<string>(msg => ConversionProgressText.Text = msg);
        try
        {
            var res = await MediaConverterService.ConvertAnimatedMediaToFramesAsync(
                dlg.FileName, workspace.WorkspaceDir, "backdrop_" + DateTime.Now.ToString("HHmmss"),
                w, h, fps, secs, 82, prog);
            currentPack.Background.Type = "animated_frames";
            currentPack.Background.FramesDir = res.RelativeRef;
            currentPack.Background.Fps = res.Fps;
            BindWholePack(selectedNode);
            SaveAndRecordHistory();
            ConversionProgressText.Text = $"Converted {res.FrameCount} frames ({res.TotalBytes / 1024} KB) via {res.Method}";
            StatusFooterText.Text = $"Wallpaper animated sequence ready: {res.RelativeRef}";
        }
        catch (Exception ex)
        {
            ConversionProgressText.Text = "Conversion failed";
            MessageBox.Show(ex.Message, "Video Conversion Error");
        }
        finally
        {
            ConvertVideoBgBtn.IsEnabled = true;
        }
    }

    private async void OnImportTileAnimClick(object sender, RoutedEventArgs e)
    {
        if (selectedNode == null) return;
        var dlg = new OpenFileDialog { Filter = "Video / Animated GIF (*.mp4;*.mov;*.wmv;*.m4v;*.avi;*.gif)|*.mp4;*.mov;*.wmv;*.m4v;*.avi;*.gif|All files (*.*)|*.*" };
        if (dlg.ShowDialog() != true) return;

        var prog = new Progress<string>(msg => ConversionProgressText.Text = msg);
        try
        {
            var res = await MediaConverterService.ConvertAnimatedMediaToFramesAsync(
                dlg.FileName, workspace.WorkspaceDir, "tile_" + PackWorkspaceService.SanitizeId(selectedNode.Id),
                480, 220, 15, 5, 80, prog);
            TileAnimBox.Text = res.RelativeRef;
            CommitInspectorToSelectedNode();
            ConversionProgressText.Text = $"Card loop ready ({res.FrameCount} frames, {res.TotalBytes / 1024} KB)";
        }
        catch (Exception ex)
        {
            MessageBox.Show(ex.Message, "Animated Card Conversion Error");
        }
    }

    private void OnNewThemeClick(object sender, RoutedEventArgs e)
    {
        bool canAddToCurrentPack = HasActiveTheme && !workspace.IsSingleThemeMode;
        var dlg = new NewThemeDialog(
            defaultStartCleanPack: !canAddToCurrentPack,
            canAddToCurrentPack: canAddToCurrentPack,
            hasOpenTheme: HasActiveTheme)
        {
            Owner = this
        };
        if (dlg.ShowDialog() != true) return;

        if (HasActiveTheme)
            workspace.SaveCurrentTheme(currentPack);

        ThemePackModel? baseTemplate = null;
        if (dlg.StarterStyle == "clone_current" && HasActiveTheme)
        {
            baseTemplate = currentPack;
        }

        var newPack = PresetCatalog.CreateNewPack(
            dlg.PackId,
            dlg.ThemeName,
            dlg.AuthorName,
            dlg.StarterStyle,
            dlg.ShowSystemBar,
            baseTemplate,
            dlg.SelectedSpeedUnit,
            dlg.SelectedTempUnit);

        var entry = dlg.StartNewCleanPack
            ? workspace.CreateNewCleanPackWithTheme(newPack)
            : workspace.AddThemeToCurrentPack(newPack);
        currentPack = newPack;
        hasActiveTheme = true;
        ResetUndoHistory();
        ApplyWorkspaceActiveState();
        RefreshIconsDropdown();
        RefreshFontsDropdown();
        RefreshThemeSelectorCombo(entry.Directory);
        BindWholePack(currentPack.Root);
        WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
        StatusFooterText.Text = dlg.StartNewCleanPack
            ? $"Created clean pack with only '{currentPack.Name}' ({currentPack.Id})"
            : $"Added '{currentPack.Name}' ({currentPack.Id}) to current pack";

        string recordPath = workspace.ExternalLinkedDir ?? workspace.WorkspaceDir;
        RecentWorkspacesService.RecordOpened(
            recordPath,
            workspace.IsSingleThemeMode ? currentPack.Name : (Path.GetFileName(recordPath.TrimEnd('\\', '/')) ?? currentPack.Name),
            workspace.IsSingleThemeMode,
            recentStoragePath);
        RefreshRecentListUi();
    }

    private void OnThemeSelectorChanged(object sender, SelectionChangedEventArgs e)
    {
        if (suppressThemeSelect || !HasActiveTheme || ThemeSelectorCombo.SelectedItem is not ThemeLibraryEntry entry) return;
        try
        {
            workspace.SaveCurrentTheme(currentPack);
            currentPack = workspace.SwitchToThemeDirectory(entry.Directory);
            currentPack.Normalize();
            ResetUndoHistory();
            RefreshIconsDropdown();
            RefreshFontsDropdown();
            BindWholePack(currentPack.Root);
            WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
            StatusFooterText.Text = $"Switched to theme '{currentPack.Name}' ({currentPack.Id})";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Switch Theme Error"); }
    }

    private void OnOpenFolderClick(object sender, RoutedEventArgs e)
    {
        var dlg = new OpenFolderDialog { Title = "Open Pack Folder OR Single Theme Folder (layout.json)" };
        if (dlg.ShowDialog() != true) return;
        OpenWorkspaceFolderPath(dlg.FolderName);
    }

    private void OnSaveCurrentClick(object sender, RoutedEventArgs e)
    {
        if (!HasActiveTheme) return;
        try
        {
            ThemePackageService.StampPackDatesForRelease(currentPack);
            PackUpdatedText.Text = currentPack.UpdatedAt ?? "";
            workspace.SaveCurrentTheme(currentPack);
            RefreshThemeSelectorCombo(workspace.WorkspaceDir);
            StatusFooterText.Text = $"Saved '{currentPack.Name}' ({currentPack.Id}) -> {workspace.WorkspaceDir}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Save Error"); }
    }

    private void OnSaveFolderClick(object sender, RoutedEventArgs e)
    {
        if (!HasActiveTheme) return;
        var dlg = new OpenFolderDialog { Title = "Select Folder to Save Active Theme Copy" };
        if (dlg.ShowDialog() != true) return;
        try
        {
            workspace.SaveToDirectory(currentPack, dlg.FolderName);
            RecentWorkspacesService.RecordOpened(dlg.FolderName, currentPack.Name, isSingleTheme: true, recentStoragePath);
            RefreshRecentListUi();
            StatusFooterText.Text = $"Saved copy '{currentPack.Name}' -> {dlg.FolderName}";
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Save Error"); }
    }

    private void OnExportUsbClick(object sender, RoutedEventArgs e)
    {
        if (HasActiveTheme)
        {
            workspace.SaveCurrentTheme(currentPack);
        }
        var dlg = new ExportUsbDialog(workspace, HasActiveTheme ? currentPack : null) { Owner = this };
        dlg.ShowDialog();

        if (HasActiveTheme && dlg.PackWorkspaceChanged)
        {
            if (!Directory.Exists(workspace.WorkspaceDir) || !File.Exists(Path.Combine(workspace.WorkspaceDir, "layout.json")))
            {
                var remaining = workspace.ListActivePackThemes();
                if (remaining.Count > 0)
                {
                    currentPack = workspace.SwitchToThemeDirectory(remaining[0].Directory);
                    currentPack.Normalize();
                    BindWholePack(currentPack.Root);
                    WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
                }
                else
                {
                    CloseActiveWorkspace();
                }
            }
            if (HasActiveTheme)
                RefreshThemeSelectorCombo(workspace.WorkspaceDir);
        }

        if (!string.IsNullOrWhiteSpace(dlg.ResultMessage))
        {
            StatusFooterText.Text = dlg.ResultMessage;
        }
    }

    private void RefreshPackCategoryDropdown()
    {
        var choices = ThemeStoreClient.LastServerCategories ?? PresetCatalog.ThemeCategories;
        bool wasSuppressed = suppressEvents;
        suppressEvents = true;
        try
        {
            PackCategoryCombo.ItemsSource = choices;
            string cat = string.IsNullOrWhiteSpace(currentPack.Category)
                ? (choices.FirstOrDefault() ?? "EV & Telemetry")
                : currentPack.Category.Trim();
            PackCategoryCombo.SelectedItem = choices.FirstOrDefault(
                c => c.Equals(cat, StringComparison.OrdinalIgnoreCase)) ?? choices.FirstOrDefault();
        }
        finally
        {
            suppressEvents = wasSuppressed;
        }
    }

    private void OnOpenThemeStoreClick(object sender, RoutedEventArgs e)
    {
        if (HasActiveTheme)
            workspace.SaveCurrentTheme(currentPack);
        var dlg = new ThemeStoreWindow(workspace, OpenUploadModalInternal, canUploadActiveTheme: HasActiveTheme) { Owner = this };
        bool? dialogOk = dlg.ShowDialog();
        RefreshPackCategoryDropdown();
        if (dialogOk == true && !string.IsNullOrWhiteSpace(dlg.InstalledThemeDir))
        {
            currentPack = workspace.SwitchToThemeDirectory(dlg.InstalledThemeDir);
            currentPack.Normalize();
            hasActiveTheme = true;
            ResetUndoHistory();
            ApplyWorkspaceActiveState();
            RefreshIconsDropdown();
            RefreshFontsDropdown();
            RefreshThemeSelectorCombo(workspace.WorkspaceDir);
            BindWholePack(currentPack.Root);
            WorkspacePathText.Text = "Theme folder: " + workspace.WorkspaceDir;
            StatusFooterText.Text = $"Installed & activated Store theme '{currentPack.Name}' ({currentPack.Id})";

            string recordPath = workspace.ExternalLinkedDir ?? workspace.WorkspaceDir;
            RecentWorkspacesService.RecordOpened(
                recordPath,
                workspace.IsSingleThemeMode ? currentPack.Name : (Path.GetFileName(recordPath.TrimEnd('\\', '/')) ?? currentPack.Name),
                workspace.IsSingleThemeMode,
                recentStoragePath);
            RefreshRecentListUi();
        }
    }

    private void OnUploadThemeStoreClick(object sender, RoutedEventArgs e)
    {
        if (!HasActiveTheme) return;
        OpenUploadModalInternal();
    }

    private bool OpenUploadModalInternal()
    {
        if (!HasActiveTheme) return false;
        try
        {
            ThemePackageService.StampPackDatesForRelease(currentPack);
            workspace.SaveCurrentTheme(currentPack);
            byte[] initialThumb = StudioCanvas.CaptureThumbnailPng(960, 360);
            var dlg = new UploadThemeDialog(
                currentPack,
                workspace.WorkspaceDir,
                initialThumb,
                () => StudioCanvas.CaptureThumbnailPng(960, 360),
                p =>
                {
                    workspace.SaveCurrentTheme(p);
                    return true;
                })
            {
                Owner = this
            };
            dlg.ShowDialog();
            RefreshPackCategoryDropdown();
            if (dlg.MetadataModified)
            {
                workspace.SaveCurrentTheme(currentPack);
                RefreshThemeSelectorCombo(workspace.WorkspaceDir);
                BindWholePack(selectedNode);
                StatusFooterText.Text = $"Updated '{currentPack.Name}' metadata (rev {currentPack.Revision})";
            }
            return dlg.MetadataModified;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, "Could not open Theme Store uploader:\n\n" + ex.Message,
                "Upload Dialog Error", MessageBoxButton.OK, MessageBoxImage.Error);
            return false;
        }
    }

    private void OnExportSafeThemeZipClick(object sender, RoutedEventArgs e)
    {
        if (!HasActiveTheme) return;
        try
        {
            ThemePackageService.StampPackDatesForRelease(currentPack);
            workspace.SaveCurrentTheme(currentPack);
            var audit = ThemePackageService.AuditWorkspaceTheme(currentPack, workspace.WorkspaceDir);
            if (!audit.IsSafe)
            {
                var choice = MessageBox.Show(this,
                    "Safety audit blocked direct export:\n- " +
                    string.Join("\n- ", audit.Errors.Concat(audit.RejectedFiles).Take(6)) +
                    "\n\nOpen the Upload & Package Inspector to review details?",
                    "Safety Audit Blocked", MessageBoxButton.YesNo, MessageBoxImage.Warning);
                if (choice == MessageBoxResult.Yes)
                    OpenUploadModalInternal();
                return;
            }

            var dlg = new SaveFileDialog
            {
                Title = "Export Safe Single-Theme Package (.zip)",
                Filter = "Gen5W Theme Package (*.zip)|*.zip",
                FileName = $"{currentPack.Id}_v{currentPack.Version}_r{Math.Max(1, currentPack.Revision)}.zip"
            };
            if (dlg.ShowDialog(this) != true) return;

            byte[] thumb = StudioCanvas.CaptureThumbnailPng(960, 360);
            var pkg = ThemePackageService.BuildSingleThemeZip(currentPack, workspace.WorkspaceDir, thumb, dlg.FileName);
            BindWholePack(selectedNode);
            StatusFooterText.Text = $"Exported safe single theme .zip ({pkg.Manifest.Theme.FileCount} files) -> {pkg.ZipPath}";
            MessageBox.Show(this,
                $"Exported ONLY active theme '{currentPack.Name}' v{currentPack.Version} (rev {currentPack.Revision})!\n\n" +
                $"Includes: layout.json, manifest.json, thumbnail.png (960x360), and verified assets.\n" +
                $"SHA-256: {pkg.PackageSha256}\n\nSaved to:\n{pkg.ZipPath}",
                "Single-Theme Package Ready", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Export .zip Error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    private static LayoutNodeModel? FindClickableAncestor(LayoutNodeModel root, LayoutNodeModel target)
    {
        var cur = FindParent(root, target);
        while (cur != null)
        {
            if (cur.Action != null && !string.IsNullOrWhiteSpace(cur.Action.Type)
                && !"none".Equals(cur.Action.Type, StringComparison.OrdinalIgnoreCase))
            {
                return cur;
            }
            cur = FindParent(root, cur);
        }
        return null;
    }

    private static LayoutNodeModel? FindParent(LayoutNodeModel root, LayoutNodeModel target)
    {
        if (target.VirtualOwnerPager != null) return target.VirtualOwnerPager;
        if (root.Children == null) return null;
        foreach (var c in root.Children)
        {
            if (c == target) return root;
            var rec = FindParent(c, target);
            if (rec != null) return rec;
        }
        return null;
    }

    private static LayoutNodeModel? FindPager(LayoutNodeModel root)
    {
        if (root.Layout == "pager") return root;
        if (root.Children == null) return null;
        foreach (var c in root.Children)
        {
            var found = FindPager(c);
            if (found != null) return found;
        }
        return null;
    }

    private void SelectMatchingPreset(ActionSpecModel act)
    {
        foreach (var opt in PresetCatalog.ActionPresets)
        {
            if (opt.IsHeader) continue;
            if (opt.ActionType == act.Type && (string.IsNullOrEmpty(opt.Cmd) || opt.Cmd == act.Cmd))
            {
                PresetCombo.SelectedItem = opt;
                return;
            }
        }
        PresetCombo.SelectedIndex = 0;
    }

    private static void SelectMatchingComboItem(ComboBox box, string value)
    {
        foreach (object o in box.Items)
        {
            if (o is ComboBoxItem cbi && string.Equals(cbi.Content?.ToString(), value, StringComparison.OrdinalIgnoreCase))
            {
                box.SelectedItem = cbi;
                return;
            }
        }
        if (box.Items.Count > 0) box.SelectedIndex = 0;
    }

    private static string ExtractSensorKey(ComboBox box)
    {
        string raw = box.Text?.Trim() ?? "";
        int dash = raw.IndexOf(" \u2014 ", StringComparison.Ordinal);
        if (dash >= 0) return raw[..dash].Trim();
        if (raw.StartsWith("(None", StringComparison.OrdinalIgnoreCase)
            || raw.StartsWith("\u2500", StringComparison.Ordinal))
            return "";
        return raw;
    }

    private static string FormatInsets(int[]? insets)
        => insets is { Length: >= 4 } ? $"{insets[0]}, {insets[1]}, {insets[2]}, {insets[3]}" : "";

    private static int[]? ParseInsets(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) return null;
        var nums = raw.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
                      .Select(s => int.TryParse(s, out int v) ? (int?)Math.Max(0, v) : null)
                      .Where(v => v.HasValue)
                      .Select(v => v!.Value)
                      .ToList();
        return nums.Count switch
        {
            1 => new[] { nums[0], nums[0], nums[0], nums[0] },
            2 => new[] { nums[0], nums[1], nums[0], nums[1] },
            >= 4 => new[] { nums[0], nums[1], nums[2], nums[3] },
            _ => null
        };
    }

    private static string? NullIfEmpty(string? s) => string.IsNullOrWhiteSpace(s) ? null : s.Trim();
    private static int? ParseOptInt(string? s) => int.TryParse(s?.Trim(), out int v) ? v : null;
    private static double? ParseOptDouble(string? s) => double.TryParse(s?.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out double v) ? v : null;

    internal void SelfTestTreeStateAndBlankPacks()
    {
        if (HasActiveTheme || EmptyWorkspaceOverlay.Visibility != Visibility.Visible || LeftSidebarPanel.IsEnabled)
            throw new InvalidOperationException("Studio must launch with an empty workspace and welcome overlay visible.");

        var seedStarter = workspace.LoadRepoDefaultStarter();
        workspace.CreateNewCleanPackWithTheme(seedStarter);
        currentPack = seedStarter;
        hasActiveTheme = true;
        ApplyWorkspaceActiveState();
        BindWholePack(currentPack.Root);
        RefreshIconsDropdown();
        RefreshFontsDropdown();
        RefreshThemeSelectorCombo(workspace.WorkspaceDir);

        var topStrip = currentPack.Root.Children?[0];
        var pager = currentPack.Root.Children?[1];
        var firstPage = pager?.Children?[0];
        if (topStrip == null || pager == null || firstPage == null || firstPage.Children == null || firstPage.Children.Count < 2)
            throw new InvalidOperationException("Starter layout missing expected hierarchy for delete self-test.");

        // Test Breaking Apart legacy ev_hero widget into editable sub-panel
        var legacyHero = firstPage.Children[0];
        BindWholePack(legacyHero);
        OnBreakApartClick(this, new RoutedEventArgs());
        var brokePanel = firstPage.Children[0];
        if (!brokePanel.IsPanel || brokePanel.Action?.Type != "preset" || brokePanel.Action?.Cmd != "ev" || (brokePanel.Children?.Count ?? 0) < 3)
            throw new InvalidOperationException("BreakApartPreset did not produce editable sub-panel with children and panel action.");

        topStrip.IsExpanded = false;
        pager.IsExpanded = true;
        firstPage.IsExpanded = true;

        var toDelete = firstPage.Children[0];
        var expectedNext = firstPage.Children[1];
        BindWholePack(toDelete);
        OnDeleteClick(this, new RoutedEventArgs());

        if (topStrip.IsExpanded)
            throw new InvalidOperationException("Manually collapsed node unexpectedly expanded after Delete.");
        if (!pager.IsExpanded || !firstPage.IsExpanded)
            throw new InvalidOperationException("Expanded tree branch collapsed after Delete.");
        if (!ReferenceEquals(selectedNode, expectedNext) || !expectedNext.IsSelected)
            throw new InvalidOperationException("Next sibling was not kept selected after Delete.");

        var template = workspace.LoadRepoDefaultStarter();
        foreach (string style in new[] { "blank_multipage", "blank_grid", "blank_freeform", "cobalt_v2", "freeform_starter" })
        {
            var created = PresetCatalog.CreateNewPack("My Test Pack!", "My Test Pack", "Tester", style, showSystemBar: true, template);
            string s = created.ToJsonString();
            if (s.Contains("isExpanded", StringComparison.OrdinalIgnoreCase) || s.Contains("isSelected", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("UI tree state leaked into JSON for style " + style);
            var parsed = ThemePackModel.FromJson(s);
            if (parsed.Id != "my-test-pack")
                throw new InvalidOperationException("Sanitized pack ID mismatch: " + parsed.Id);
        }

        // Verify composable panel + progress_bar + text + custom font/margins/opacity serialization & canvas rendering
        var customPanel = PresetCatalog.CreatePresetEvHeroPanel();
        customPanel.Children!.Add(new LayoutNodeModel
        {
            Id = "temp_bar",
            Kind = "progress_bar",
            TelemetryKey = "hv.battery_temp_min",
            Unit = "°C",
            MinValue = -20,
            MaxValue = 60,
            Orientation = "horizontal",
            ShowText = true,
            Height = 20,
            MarginPx = new[] { 0, 6, 0, 0 },
            Style = new WidgetStyleModel
            {
                FontFamily = "monospace",
                FontWeight = "bold",
                FontItalic = true,
                Opacity = 0.9,
                AccentColor = "#FF10B981",
                BgEndColor = "#FFF59E0B"
            }
        });
        var customPack = PresetCatalog.CreateNewPack("comp-test", "Composable Test", "Driver", "blank_grid", true);
        customPack.Root.Children![1].Children!.Add(customPanel);
        string customJson = customPack.ToJsonString();
        var rtCustom = ThemePackModel.FromJson(customJson);
        var rtBar = rtCustom.Root.Children![1].Children![0].Children!.Last();
        if (rtBar.TelemetryKey != "hv.battery_temp_min" || rtBar.MinValue != -20 || rtBar.MaxValue != 60
            || rtBar.Style?.FontFamily != "monospace" || rtBar.Style?.FontItalic != true || Math.Abs((rtBar.Style?.Opacity ?? 0) - 0.9) > 0.01)
            throw new InvalidOperationException("Composable progress_bar / font / margin / opacity round-trip failed.");

        StudioCanvas.Bind(rtCustom, workspace, sim, rtBar);

        // Verify clean isolated pack workflow:
        // 1. Creating a fresh clean pack with ONLY Kitt contains ONLY Kitt (0 built-in starters)
        var kittPack = PresetCatalog.CreateNewPack("tapplz-themes", "Kitt", "Tapple", "blank_freeform", true);
        var kittEntry = workspace.CreateNewCleanPackWithTheme(kittPack);
        currentPack = kittPack;
        RefreshThemeSelectorCombo(kittEntry.Directory);
        BindWholePack(currentPack.Root);

        var activeThemes = workspace.ListActivePackThemes();
        if (activeThemes.Count != 1 || activeThemes[0].Id != "tapplz-themes")
            throw new InvalidOperationException("Clean pack should contain ONLY Kitt, found count=" + activeThemes.Count);
        if (ThemeSelectorCombo.SelectedItem is not ThemeLibraryEntry selEntry || selEntry.Id != "tapplz-themes")
            throw new InvalidOperationException("Newly created theme was not selected in top Theme dropdown.");

        // 2. Adding a 2nd theme into the same clean pack keeps built-in starters out
        var kittNight = PresetCatalog.CreateNewPack("kitt-night", "Kitt Night", "Tapple", "blank_grid", true);
        var nightEntry = workspace.AddThemeToCurrentPack(kittNight);
        currentPack = kittNight;
        RefreshThemeSelectorCombo(nightEntry.Directory);
        BindWholePack(currentPack.Root);
        if (workspace.ListActivePackThemes().Count != 2)
            throw new InvalidOperationException("Adding 2nd theme to custom pack failed.");

        // 3. Selective export of ONLY Kitt to a folder, then opening that folder must list ONLY Kitt!
        string savedFolder = Path.Combine(workspace.PacksRootDir, "_saved_only_kitt");
        string packsOut = Path.Combine(savedFolder, "packs");
        int wroteKitt = workspace.ExportSelectedThemesToDirectory(currentPack, new[] { kittEntry }, packsOut, pruneUnselected: true);
        if (wroteKitt != 1) throw new InvalidOperationException("Selective 1-theme export failed.");

        var opened = workspace.OpenPackOrThemeFolder(savedFolder);
        if (opened.Count != 1 || opened[0].Id != "tapplz-themes")
            throw new InvalidOperationException("Opening 1-theme saved folder leaked built-in starters or wrong themes!");
        var openedList = workspace.ListActivePackThemes();
        if (openedList.Count != 1 || openedList[0].Id != "tapplz-themes")
            throw new InvalidOperationException("Dropdown inside opened 1-theme folder still contained built-in starters!");

        // 4. Test adding & deleting inside custom multi-theme pack + ExportUsbDialog & NEMESIS USB installer staging
        var tmpPackDir = Path.Combine(workspace.PacksRootDir, "_multi_test");
        Directory.CreateDirectory(Path.Combine(tmpPackDir, "packs", "alpha"));
        Directory.CreateDirectory(Path.Combine(tmpPackDir, "packs", "beta"));
        File.WriteAllText(Path.Combine(tmpPackDir, "packs", "alpha", "layout.json"),
            PresetCatalog.CreateNewPack("alpha", "Alpha Theme", "T", "blank_grid", true).ToJsonString());
        File.WriteAllText(Path.Combine(tmpPackDir, "packs", "beta", "layout.json"),
            PresetCatalog.CreateNewPack("beta", "Beta Theme", "T", "blank_freeform", true).ToJsonString());
        var multiOpened = workspace.OpenPackOrThemeFolder(tmpPackDir);
        if (multiOpened.Count != 2) throw new InvalidOperationException("Opening multi-theme pack failed.");
        var defaultInstaller = NemesisInstallerService.ResolveDefaultInstaller()
            ?? throw new InvalidOperationException("NemesisInstallerService failed to resolve default/bundled installer.");
        if (!Version.TryParse(defaultInstaller.Version, out var resolvedVer) || resolvedVer < new Version(0, 5, 0))
            throw new InvalidOperationException($"NemesisInstallerService resolved stale/unknown version '{defaultInstaller.Version}' ({defaultInstaller.DisplaySource}).");
        var exportDlg = new ExportUsbDialog(workspace, currentPack);
        string simUsbRoot = Path.Combine(workspace.PacksRootDir, "_sim_usb");
        var (expThemes, expVer, expInstFiles) = exportDlg.ExportToDirectory(simUsbRoot, includeThemes: true, includeInstaller: true);
        exportDlg.Close();
        if (expThemes != 2
            || expVer != defaultInstaller.Version
            || !File.Exists(Path.Combine(simUsbRoot, "NEMESIS.data"))
            || !File.Exists(Path.Combine(simUsbRoot, "packages", "gen5w.launcher.zip"))
            || !expInstFiles.Contains("packages/gen5w.launcher.zip")
            || NemesisInstallerService.HasUnrelatedNemesisInstallOnUsb(simUsbRoot, out _)
            || !File.Exists(Path.Combine(simUsbRoot, "README.txt"))
            || !File.ReadAllText(Path.Combine(simUsbRoot, "README.txt")).Contains(defaultInstaller.Version, StringComparison.Ordinal)
            || !File.Exists(Path.Combine(simUsbRoot, "Gen5WLauncher", "packs", "alpha", "layout.json"))
            || !File.Exists(Path.Combine(simUsbRoot, "Gen5WLauncher", "packs", "beta", "layout.json")))
            throw new InvalidOperationException($"ExportUsbDialog failed USB staging (themes={expThemes}, ver={expVer}, files={expInstFiles.Count}).");
        // Verify ExportUsbDialog also initializes cleanly with no active theme (installer-only mode)
        var emptyWorkspaceSvc = new PackWorkspaceService(Path.Combine(workspace.PacksRootDir, "_empty_usb_test"));
        var emptyExportDlg = new ExportUsbDialog(emptyWorkspaceSvc, null);
        emptyExportDlg.Close();
        if (!workspace.DeleteThemeFromActivePack(multiOpened[1]) || workspace.ListActivePackThemes().Count != 1)
            throw new InvalidOperationException("DeleteThemeFromActivePack failed inside multi-theme custom pack.");

        // 5. Verify SpeedUnit & TempUnit round-trip and StudioCanvas rendering under both unit modes
        static void CollectTexts(DependencyObject parent, List<string> acc)
        {
            int n = System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent);
            for (int i = 0; i < n; i++)
            {
                var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, i);
                if (child is TextBlock tb && !string.IsNullOrEmpty(tb.Text)) acc.Add(tb.Text);
                CollectTexts(child, acc);
            }
        }

        int origSpeed = sim.SpeedKmh;
        double origPackTemp = sim.HvPackTempC;
        try
        {
            sim.SpeedKmh = 45;
            sim.HvPackTempC = 22.0;

            var usPack = PresetCatalog.CreateNewPack("unit-us", "US Units", "T", "blank_grid", true, null, "mph", "F");
            usPack.Root.Children![0].Children!.Add(new LayoutNodeModel { Id = "spd_auto", Kind = "text", TelemetryKey = "speed" });
            usPack.Root.Children![0].Children!.Add(new LayoutNodeModel { Id = "tmp_auto", Kind = "progress_bar", TelemetryKey = "hv.battery_temp_min", ShowText = true });
            var usRt = ThemePackModel.FromJson(usPack.ToJsonString());
            if (usRt.Tokens.SpeedUnit != "mph" || usRt.Tokens.TempUnit != "F")
                throw new InvalidOperationException("SpeedUnit/TempUnit mph/F JSON round-trip failed.");
            StudioCanvas.Bind(usRt, workspace, sim, usRt.Root);
            var usTexts = new List<string>();
            CollectTexts(StudioCanvas, usTexts);
            if (!usTexts.Contains("28 mph") || !usTexts.Contains("72\u00B0F"))
                throw new InvalidOperationException("US unit render check failed: " + string.Join(" | ", usTexts));

            var metricPack = PresetCatalog.CreateNewPack("unit-metric", "Metric Units", "T", "blank_grid", true, null, "kmh", "C");
            metricPack.Root.Children![0].Children!.Add(new LayoutNodeModel { Id = "spd_auto", Kind = "text", TelemetryKey = "speed" });
            metricPack.Root.Children![0].Children!.Add(new LayoutNodeModel { Id = "tmp_auto", Kind = "progress_bar", TelemetryKey = "hv.battery_temp_min", ShowText = true });
            var metricRt = ThemePackModel.FromJson(metricPack.ToJsonString());
            if (metricRt.Tokens.SpeedUnit != "kmh" || metricRt.Tokens.TempUnit != "C")
                throw new InvalidOperationException("SpeedUnit/TempUnit kmh/C JSON round-trip failed.");
            StudioCanvas.Bind(metricRt, workspace, sim, metricRt.Root);
            var metricTexts = new List<string>();
            CollectTexts(StudioCanvas, metricTexts);
            if (!metricTexts.Contains("45 km/h") || !metricTexts.Contains("22\u00B0C"))
                throw new InvalidOperationException("Metric unit render check failed: " + string.Join(" | ", metricTexts));

            // 5b. Verify Pager Indicator customization, virtual tree exposure, non-page placement, and center geometry
            var multiPack = PresetCatalog.CreateNewPack("ind-test", "Indicator Test", "T", "blank_multipage", true);
            currentPack = multiPack;
            var indPager = FindPager(multiPack.Root)!;
            var treeKids = indPager.TreeChildren?.ToList() ?? new List<LayoutNodeModel>();
            if (treeKids.Count != 3 || treeKids[2].VirtualOwnerPager != indPager)
                throw new InvalidOperationException("Built-in pager indicator did not appear in HierarchyTree TreeChildren.");

            // Check centered geometry on offscreen Measure/Arrange pass
            StudioCanvas.Bind(multiPack, workspace, sim, indPager);
            StudioCanvas.Measure(new Size(1920, 720));
            StudioCanvas.Arrange(new Rect(0, 0, 1920, 720));
            StudioCanvas.UpdateLayout();
            static StackPanel? FindHorizontalPillStrip(DependencyObject parent)
            {
                int n = System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent);
                for (int i = 0; i < n; i++)
                {
                    var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, i);
                    if (child is StackPanel sp && sp.Orientation == Orientation.Horizontal && sp.Children.Count == 2)
                    {
                        var txts = new List<string>();
                        CollectTexts(sp, txts);
                        if (txts.Contains("Page 1") && txts.Contains("Page 2")) return sp;
                    }
                    var rec = FindHorizontalPillStrip(child);
                    if (rec != null) return rec;
                }
                return null;
            }
            var centeredStrip = FindHorizontalPillStrip(StudioCanvas)
                ?? throw new InvalidOperationException("Failed to locate centered page indicator StackPanel.");
            double stripCenterX = centeredStrip.TransformToVisual(StudioCanvas)
                .Transform(new Point(centeredStrip.ActualWidth * 0.5, centeredStrip.ActualHeight * 0.5)).X;
            if (Math.Abs(stripCenterX - 960.0) > 20.0)
                throw new InvalidOperationException($"Centered pager indicator strip drifted off center: X={stripCenterX:0.0} (expected ~960)");

            // Adding a standalone Page Bar while a [grid] page inside pager is selected must climb out to root_shell!
            var addedBar = AddStandalonePageIndicatorOutsidePages(indPager.Children![0], out string? addErr);
            if (addedBar == null || addErr != null || indPager.Children![0].Children!.Contains(addedBar) || !multiPack.Root.Children!.Contains(addedBar))
                throw new InvalidOperationException("Standalone page bar was placed inside a page instead of enclosing root panel.");
            multiPack.Root.Children!.Remove(addedBar);

            // Removing virtual built-in indicator bar disables ShowDots and hides it from TreeChildren
            selectedNode = treeKids[2];
            OnDeleteClick(this, new RoutedEventArgs());
            if (indPager.ShowDots != false || indPager.TreeChildren!.Any(c => c.VirtualOwnerPager != null))
                throw new InvalidOperationException("Deleting built-in indicator bar from side panel failed.");

            indPager.ShowDots = true;
            indPager.Indicator = new IndicatorStyleModel
            {
                Mode = "numbers",
                Align = "left",
                ActiveFillColor = "#FF10B981",
                CornerRadiusPx = 4,
                GapPx = 18
            };
            var indRt = ThemePackModel.FromJson(multiPack.ToJsonString());
            var indPagerRt = FindPager(indRt.Root)!;
            if (indPagerRt.Indicator?.Mode != "numbers" || indPagerRt.Indicator?.ActiveFillColor != "#FF10B981")
                throw new InvalidOperationException("IndicatorStyle JSON round-trip failed.");
            StudioCanvas.Bind(indRt, workspace, sim, indRt.Root);
            var numTexts = new List<string>();
            CollectTexts(StudioCanvas, numTexts);
            if (!numTexts.Contains("1") || !numTexts.Contains("2"))
                throw new InvalidOperationException("Number mode pager indicator render check failed.");

            indPagerRt.Indicator!.Mode = "dots";
            StudioCanvas.Bind(indRt, workspace, sim, indRt.Root);
            var dotTexts = new List<string>();
            CollectTexts(StudioCanvas, dotTexts);
            if (dotTexts.Contains("Page 1") || dotTexts.Contains("Page 2"))
                throw new InvalidOperationException("Dots mode should not render page title labels.");

            indPagerRt.ShowDots = false;
            indPagerRt.Indicator.Mode = "none";
            var hiddenRt = ThemePackModel.FromJson(indRt.ToJsonString());
            if (FindPager(hiddenRt.Root)!.ShowDots != false)
                throw new InvalidOperationException("ShowDots=false JSON round-trip failed.");
            // Also remove standalone bar before testing zero labels
            hiddenRt.Root.Children!.RemoveAll(c => c.Kind == "page_indicator");
            StudioCanvas.Bind(hiddenRt, workspace, sim, hiddenRt.Root);
            var hiddenTexts = new List<string>();
            CollectTexts(StudioCanvas, hiddenTexts);
            if (hiddenTexts.Contains("Page 1") || hiddenTexts.Contains("Page 2"))
                throw new InvalidOperationException("Disabled pager indicator still rendered labels.");

            // 5c. Verify non-transparent panel card background renders at child rect (NOT 0,0 of root_shell) and honors Style.PaddingPx
            var tilePack = PresetCatalog.CreateNewPack("tile-pos-test", "Tile Pos Test", "T", "blank_grid", true);
            var tileGrid = tilePack.Root.Children![1];
            var presetTile = PresetCatalog.CreatePresetActionTilePanel("Pos Check", "Sub", "icons/apps.svg", "preset", "ev");
            tileGrid.Children!.Add(presetTile);
            StudioCanvas.Bind(tilePack, workspace, sim, presetTile);
            StudioCanvas.Measure(new Size(1920, 720));
            StudioCanvas.Arrange(new Rect(0, 0, 1920, 720));
            StudioCanvas.UpdateLayout();
            static Border? FindBorderForTag(DependencyObject parent, LayoutNodeModel target)
            {
                int n = System.Windows.Media.VisualTreeHelper.GetChildrenCount(parent);
                for (int i = 0; i < n; i++)
                {
                    var child = System.Windows.Media.VisualTreeHelper.GetChild(parent, i);
                    if (child is Border b && ReferenceEquals(b.Tag, target)) return b;
                    var rec = FindBorderForTag(child, target);
                    if (rec != null) return rec;
                }
                return null;
            }
            var cardBorder = FindBorderForTag(StudioCanvas, presetTile)
                ?? throw new InvalidOperationException("Failed to locate rendered Border for preset action tile panel.");
            double cardTop = Canvas.GetTop(cardBorder);
            if (double.IsNaN(cardTop) || cardTop < 50.0)
                throw new InvalidOperationException($"Panel background card was positioned at Y={cardTop} instead of below top_strip!");

            // Verify geometry fields start populated with initial computed values and custom Width/Height applies inside [grid]
            currentPack = tilePack;
            BindWholePack(presetTile);
            if (string.IsNullOrWhiteSpace(WidthBox.Text) || string.IsNullOrWhiteSpace(HeightBox.Text) || string.IsNullOrWhiteSpace(BoundsWBox.Text))
                throw new InvalidOperationException("Geometry/Bounds boxes were blank on initial selection.");
            WidthBox.Text = "300";
            HeightBox.Text = "140";
            CommitInspectorToSelectedNode();
            if (presetTile.Width != 300 || presetTile.Height != 140)
                throw new InvalidOperationException("Custom Width/Height did not save onto grid child.");
            if (!StudioCanvas.TryGetNodeRefRect(presetTile, out Rect resizedRect) || Math.Abs(resizedRect.Width - 300) > 2 || Math.Abs(resizedRect.Height - 140) > 2)
                throw new InvalidOperationException($"Custom Width/Height was ignored on grid tile! Rendered={resizedRect.Width:0}x{resizedRect.Height:0}");
            // Clearing WidthBox restores auto width
            WidthBox.Text = "";
            CommitInspectorToSelectedNode();
            if (presetTile.Width != null || !StudioCanvas.TryGetNodeRefRect(presetTile, out Rect restoredRect) || restoredRect.Width <= 350)
                throw new InvalidOperationException("Clearing WidthBox failed to restore auto width.");

            // Verify ColorPickerDialog HSV <-> #AARRGGBB round-trip & opacity math
            var sampleOrange = System.Windows.Media.Color.FromArgb(0xCC, 0xBD, 0x53, 0x38);
            ColorPickerDialog.RgbToHsv(sampleOrange, out double oh, out double os, out double ov);
            var roundOrange = ColorPickerDialog.HsvToColor(sampleOrange.A / 255.0, oh, os, ov);
            if (ColorPickerDialog.FormatArgbHex(roundOrange) != "#CCBD5338")
                throw new InvalidOperationException("ColorPickerDialog HSV/#AARRGGBB round-trip mismatch: " + ColorPickerDialog.FormatArgbHex(roundOrange));
            var cpDlg = new ColorPickerDialog("#CCBD5338", null);
            if (cpDlg.SelectedHex != "#CCBD5338")
                throw new InvalidOperationException("ColorPickerDialog initial hex mismatch: " + cpDlg.SelectedHex);
            cpDlg.Close();

            // Verify Nest -> / <- Out / Wrap / cycle-guard re-parenting workflow
            var nestPack = PresetCatalog.CreateNewPack("nest-test", "Nest Test", "T", "blank_grid", true);
            currentPack = nestPack;
            var hostGrid = nestPack.Root.Children![1];
            var outerCol = new LayoutNodeModel { Id = "panel_outer", Type = "panel", Layout = "flex_col", Children = new List<LayoutNodeModel>() };
            var carStats = new LayoutNodeModel
            {
                Id = "car_stats",
                Type = "panel",
                Layout = "flex_row",
                Children = new List<LayoutNodeModel>
                {
                    new() { Id = "sp_ring", Kind = "soc_ring", TelemetryKey = "speed" },
                    new() { Id = "acc_ring", Kind = "soc_ring", TelemetryKey = "hv.soc" }
                }
            };
            hostGrid.Children!.Add(outerCol);
            hostGrid.Children.Add(carStats);
            BindWholePack(carStats);

            if (CanMoveNodeInto(carStats, carStats.Children[0], out _) || CanMoveNodeInto(outerCol, outerCol, out _))
                throw new InvalidOperationException("Cycle guard allowed invalid re-parenting.");

            OnNestIntoSiblingClick(this, new RoutedEventArgs());
            if (!outerCol.Children!.Contains(carStats) || hostGrid.Children.Contains(carStats) || !outerCol.IsExpanded)
                throw new InvalidOperationException("Nest -> failed to move flex_row inside sibling flex_col.");

            OnUnnestToParentClick(this, new RoutedEventArgs());
            if (outerCol.Children.Contains(carStats) || !hostGrid.Children.Contains(carStats))
                throw new InvalidOperationException("<- Out failed to move flex_row back to outer container.");

            OnWrapInPanelClick(this, new RoutedEventArgs());
            var wrappedParent = FindParent(nestPack.Root, carStats);
            if (wrappedParent == null || wrappedParent == hostGrid || wrappedParent.Layout != "flex_col" || !wrappedParent.Children!.Contains(carStats))
                throw new InvalidOperationException("Wrap failed to enclose flex_row in a new flex_col panel.");

            // Verify Wrap protection with explicit Width=870, Height=220, MarginPx=[530,50,0,0] inside a 4-column grid:
            // wrapper must NOT collapse to 1px wide!
            var wrapStats = new LayoutNodeModel
            {
                Id = "wrap_stats",
                Type = "panel",
                Layout = "flex_row",
                Width = 870,
                Height = 220,
                MarginPx = new[] { 530, 50, 0, 0 },
                Style = new WidgetStyleModel { Transparent = false, BgColor = "#FF136089" },
                Children = new List<LayoutNodeModel> { new() { Id = "g1", Kind = "soc_ring", Width = 200, Height = 175 } }
            };
            hostGrid.Children!.Add(wrapStats);
            BindWholePack(wrapStats);
            OnWrapInPanelClick(this, new RoutedEventArgs());
            var safeGroup = FindParent(nestPack.Root, wrapStats)!;
            if (safeGroup.Width != 894 || safeGroup.Height != 244 || safeGroup.MarginPx?[0] != 530 || wrapStats.MarginPx != null)
                throw new InvalidOperationException($"Wrap protection failed to preserve outer size/margin: {safeGroup.Width}x{safeGroup.Height}");
            if (!StudioCanvas.TryGetNodeRefRect(safeGroup, out Rect sgRect) || Math.Abs(sgRect.Width - 894) > 3 || Math.Abs(sgRect.Height - 244) > 3)
                throw new InvalidOperationException($"Wrapped group collapsed or mis-sized: {sgRect.Width:0}x{sgRect.Height:0}");
            if (!StudioCanvas.TryGetNodeRefRect(wrapStats, out Rect wsRect) || Math.Abs(wsRect.Width - 870) > 3 || Math.Abs(wsRect.Height - 220) > 3)
                throw new InvalidOperationException($"Inner wrapped child mis-sized: {wsRect.Width:0}x{wsRect.Height:0}");
        }
        finally
        {
            sim.SpeedKmh = origSpeed;
            sim.HvPackTempC = origPackTemp;
        }

        // 6. Thumbnail capture, safe single-theme packaging, security guards, & Theme Store client filtering
        byte[] thumbPng = StudioCanvas.CaptureThumbnailPng(960, 360);
        if (thumbPng.Length < 100 || thumbPng[0] != 0x89 || thumbPng[1] != 0x50 || thumbPng[2] != 0x4E || thumbPng[3] != 0x47)
            throw new InvalidOperationException("CaptureThumbnailPng did not produce a valid PNG header.");
        using (var thumbStream = new MemoryStream(thumbPng))
        {
            var frame = BitmapFrame.Create(thumbStream, BitmapCreateOptions.IgnoreColorProfile, BitmapCacheOption.OnLoad);
            if (frame.PixelWidth != 960 || frame.PixelHeight != 360)
                throw new InvalidOperationException($"Thumbnail dimensions mismatch: {frame.PixelWidth}x{frame.PixelHeight}");
        }

        string storeTestRoot = Path.Combine(Path.GetTempPath(), "gen5w_selftest_" + Guid.NewGuid().ToString("N")[..8]);
        try
        {
            string pkgWsDir = Path.Combine(storeTestRoot, "ws");
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "icons"));
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "img"));
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "media"));
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "anim", "backdrop_200858"));
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "anim", "backdrop_old_140112"));
            Directory.CreateDirectory(Path.Combine(pkgWsDir, "packs", "other-theme", "icons"));
            var pkgPack = PresetCatalog.CreateNewPack("store-test", "Store Test Theme", "Tester", "blank_grid", true);
            pkgPack.Revision = 3;
            pkgPack.Category = "Minimal";
            pkgPack.MinAppVersion = "0.3.4";
            pkgPack.CompatibleVersions = new List<string> { "0.3.4+" };
            pkgPack.Description = "Self-test verified single theme";
            // Active background is animated_frames ("anim/backdrop_200858"), while BitmapAsset holds a stale previously-tried wallpaper
            pkgPack.Background.Type = "animated_frames";
            pkgPack.Background.FramesDir = "anim/backdrop_200858";
            pkgPack.Background.BitmapAsset = "media/tried_bg_1.jpg";
            pkgPack.Root.Children ??= new List<LayoutNodeModel>();
            pkgPack.Root.Children.Add(new LayoutNodeModel { Id = "used_dot", Kind = "tile", Title = "Dot", Icon = "icons/dot.svg" });
            pkgPack.Root.Children.Add(new LayoutNodeModel { Id = "used_svgrepo", Kind = "tile", Title = "Repo", Icon = "icons/svgrepo_test.svg" });
            pkgPack.Root.Children.Add(new LayoutNodeModel { Id = "text_with_stale_icon", Kind = "text", Title = "Plain Text", Icon = "icons/unused_custom.svg" });
            ThemePackageService.StampPackDatesForRelease(pkgPack);
            File.WriteAllText(Path.Combine(pkgWsDir, "layout.json"), pkgPack.ToJsonString());

            // Actively referenced assets (use a real valid 4x4 JPEG so WPF WIC decoding succeeds during tests)
            var jpgWb = new WriteableBitmap(4, 4, 96, 96, System.Windows.Media.PixelFormats.Bgr24, null);
            jpgWb.WritePixels(new Int32Rect(0, 0, 4, 4), new byte[4 * 4 * 3], 4 * 3, 0);
            var jpgEnc = new JpegBitmapEncoder { QualityLevel = 80 };
            jpgEnc.Frames.Add(BitmapFrame.Create(jpgWb));
            using var jpgMs = new MemoryStream();
            jpgEnc.Save(jpgMs);
            byte[] validJpegBytes = jpgMs.ToArray();
            File.WriteAllText(Path.Combine(pkgWsDir, "icons", "dot.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"6\" fill=\"#38BDF8\"/></svg>");
            File.WriteAllText(Path.Combine(pkgWsDir, "icons", "svgrepo_test.svg"),
                "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0\"/></svg>");
            File.WriteAllText(Path.Combine(pkgWsDir, "anim", "backdrop_200858", "frames.json"),
                "{\"fps\":12,\"frames\":[\"f_0001.jpg\"]}");
            File.WriteAllBytes(Path.Combine(pkgWsDir, "anim", "backdrop_200858", "f_0001.jpg"), validJpegBytes);

            // Unused / orphaned workspace assets (previously-tried wallpapers, old video conversions, unreferenced icons/images)
            File.WriteAllBytes(Path.Combine(pkgWsDir, "media", "tried_bg_1.jpg"), validJpegBytes);
            File.WriteAllBytes(Path.Combine(pkgWsDir, "media", "tried_bg_2.jpg"), validJpegBytes);
            File.WriteAllBytes(Path.Combine(pkgWsDir, "anim", "backdrop_200858", "extra_unlisted.jpg"), validJpegBytes);
            File.WriteAllText(Path.Combine(pkgWsDir, "anim", "backdrop_old_140112", "frames.json"),
                "{\"fps\":12,\"frames\":[\"f_0001.jpg\"]}");
            File.WriteAllBytes(Path.Combine(pkgWsDir, "anim", "backdrop_old_140112", "f_0001.jpg"), validJpegBytes);
            File.WriteAllText(Path.Combine(pkgWsDir, "icons", "unused_custom.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>");
            File.WriteAllText(Path.Combine(pkgWsDir, "icons", "music.svg"),
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>");
            File.WriteAllBytes(Path.Combine(pkgWsDir, "img", "unused_pic.jpg"), validJpegBytes);

            // Unrelated packs/ subfolder must be skipped cleanly when packaging only the active theme
            File.WriteAllText(Path.Combine(pkgWsDir, "packs", "other-theme", "layout.json"), pkgPack.ToJsonString());
            File.WriteAllText(Path.Combine(pkgWsDir, "packs", "other-theme", "icons", "x.svg"), "<svg/>");

            string outZip = Path.Combine(storeTestRoot, "single_theme.zip");
            var builtPkg = ThemePackageService.BuildSingleThemeZip(pkgPack, pkgWsDir, thumbPng, outZip);
            if (!builtPkg.Manifest.SingleTheme || builtPkg.Manifest.Theme.Revision != 3 || builtPkg.Manifest.Theme.Category != "Minimal")
                throw new InvalidOperationException("Single-theme manifest metadata mismatch.");
            if (builtPkg.Audit.IncludedFiles.Any(f => f.Path.StartsWith("packs/", StringComparison.OrdinalIgnoreCase)))
                throw new InvalidOperationException("Unrelated packs/ folder should not have been packaged.");
            if (!builtPkg.Audit.IncludedFiles.Any(f => f.Path == "anim/backdrop_200858/frames.json")
                || !builtPkg.Audit.IncludedFiles.Any(f => f.Path == "anim/backdrop_200858/f_0001.jpg"))
                throw new InvalidOperationException("Referenced anim/backdrop_200858 frames should have been included in single-theme package.");
            if (builtPkg.Audit.IncludedFiles.Any(f =>
                    f.Path.StartsWith("media/", StringComparison.OrdinalIgnoreCase)
                    || f.Path.StartsWith("anim/backdrop_old_140112/", StringComparison.OrdinalIgnoreCase)
                    || f.Path == "anim/backdrop_200858/extra_unlisted.jpg"
                    || f.Path == "icons/unused_custom.svg"
                    || f.Path == "icons/music.svg"
                    || f.Path == "img/unused_pic.jpg"))
            {
                throw new InvalidOperationException("Unused workspace assets leaked into built .zip package: "
                    + string.Join(", ", builtPkg.Audit.IncludedFiles.Select(f => f.Path)));
            }
            if (builtPkg.Audit.ExcludedUnusedFiles.Count < 7)
                throw new InvalidOperationException($"Expected at least 7 excluded unused files, got {builtPkg.Audit.ExcludedUnusedFiles.Count}.");

            using (var checkZip = ZipFile.OpenRead(outZip))
            {
                if (checkZip.GetEntry("media/tried_bg_1.jpg") != null
                    || checkZip.GetEntry("media/tried_bg_2.jpg") != null
                    || checkZip.GetEntry("anim/backdrop_old_140112/f_0001.jpg") != null
                    || checkZip.GetEntry("icons/unused_custom.svg") != null
                    || checkZip.GetEntry("icons/music.svg") != null
                    || checkZip.GetEntry("img/unused_pic.jpg") != null)
                {
                    throw new InvalidOperationException("Orphaned assets were written into output .zip archive!");
                }

                var svgRepoEntry = checkZip.GetEntry("icons/svgrepo_test.svg")
                    ?? throw new InvalidOperationException("icons/svgrepo_test.svg missing from output ZIP.");
                using var srEntry = svgRepoEntry.Open();
                using var msSvg = new MemoryStream();
                srEntry.CopyTo(msSvg);
                byte[] packagedSvgBytes = msSvg.ToArray();
                string packagedSvgText = Encoding.UTF8.GetString(packagedSvgBytes);
                if (packagedSvgText.Contains("<!DOCTYPE", StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException("Harmless DOCTYPE should have been stripped before packaging SVG.");

                var expectedManifestEntry = builtPkg.Manifest.Files.FirstOrDefault(f => f.Path == "icons/svgrepo_test.svg")
                    ?? throw new InvalidOperationException("manifest.json missing svgrepo_test.svg entry.");
                if (expectedManifestEntry.Sha256 != ThemePackageService.ComputeSha256Hex(packagedSvgBytes))
                    throw new InvalidOperationException("Manifest SHA-256 did not match packaged DOCTYPE-stripped SVG bytes.");

                var strictXmlSettings = new System.Xml.XmlReaderSettings
                {
                    DtdProcessing = System.Xml.DtdProcessing.Prohibit,
                    XmlResolver = null
                };
                using var strictReader = System.Xml.XmlReader.Create(new StringReader(packagedSvgText), strictXmlSettings);
                while (strictReader.Read()) { }
            }

            byte[] validZipBytes = File.ReadAllBytes(outZip);
            string extractedDir = Path.Combine(storeTestRoot, "extracted");
            var (extPack, extAudit) = ThemePackageService.ExtractSingleThemeZipSafely(validZipBytes, extractedDir, builtPkg.PackageSha256);
            if (!extAudit.IsSafe
                || extPack.Revision != 3
                || extPack.Background.BitmapAsset != null
                || !File.Exists(Path.Combine(extractedDir, "thumbnail.png"))
                || !File.Exists(Path.Combine(extractedDir, "icons", "dot.svg"))
                || !File.Exists(Path.Combine(extractedDir, "icons", "svgrepo_test.svg"))
                || !File.Exists(Path.Combine(extractedDir, "anim", "backdrop_200858", "frames.json"))
                || File.Exists(Path.Combine(extractedDir, "media", "tried_bg_1.jpg"))
                || File.Exists(Path.Combine(extractedDir, "anim", "backdrop_old_140112", "frames.json"))
                || File.Exists(Path.Combine(extractedDir, "icons", "unused_custom.svg"))
                || File.Exists(Path.Combine(extractedDir, "packs", "other-theme", "layout.json")))
                throw new InvalidOperationException("Safe single-theme extraction missed expected verified files or leaked unused/packs files.");

            // Verify switching Background.Type to "bitmap" ("media/tried_bg_2.jpg") excludes BOTH old animated frames AND tried_bg_1.jpg,
            // AND USB folder export (ExportSelectedThemesToDirectory) also copies ONLY referenced assets!
            var bmpSwitchPack = pkgPack.DeepClone();
            bmpSwitchPack.Background.Type = "bitmap";
            bmpSwitchPack.Background.BitmapAsset = "media/tried_bg_2.jpg"; // FramesDir still holds stale "anim/backdrop_200858" before build
            string bmpZipPath = Path.Combine(storeTestRoot, "bmp_switch.zip");
            var bmpBuilt = ThemePackageService.BuildSingleThemeZip(bmpSwitchPack, pkgWsDir, thumbPng, bmpZipPath);
            if (!bmpBuilt.Audit.IncludedFiles.Any(f => f.Path == "media/tried_bg_2.jpg")
                || bmpBuilt.Audit.IncludedFiles.Any(f => f.Path == "media/tried_bg_1.jpg" || f.Path.StartsWith("anim/", StringComparison.OrdinalIgnoreCase)))
            {
                throw new InvalidOperationException("Switching Background.Type to bitmap failed to exclude old anim/framesDir or unused bitmap.");
            }

            string usbExportRoot = Path.Combine(storeTestRoot, "usb_packs_out");
            PackWorkspaceService.CopyReferencedThemeToDirectory(bmpSwitchPack, pkgWsDir, Path.Combine(usbExportRoot, "store-test"));
            if (!File.Exists(Path.Combine(usbExportRoot, "store-test", "media", "tried_bg_2.jpg"))
                || !File.Exists(Path.Combine(usbExportRoot, "store-test", "icons", "dot.svg"))
                || File.Exists(Path.Combine(usbExportRoot, "store-test", "media", "tried_bg_1.jpg"))
                || Directory.Exists(Path.Combine(usbExportRoot, "store-test", "anim"))
                || File.Exists(Path.Combine(usbExportRoot, "store-test", "icons", "music.svg"))
                || File.Exists(Path.Combine(usbExportRoot, "store-test", "icons", "unused_custom.svg"))
                || File.Exists(Path.Combine(usbExportRoot, "store-test", "img", "unused_pic.jpg")))
            {
                throw new InvalidOperationException("CopyReferencedThemeToDirectory (USB export) leaked unreferenced assets!");
            }

            // Verify CleanUnusedWorkspaceAssets deletes orphaned custom media/anim/img/icons while keeping active assets & built-in icons
            string cleanableWs = Path.Combine(storeTestRoot, "cleanable_ws");
            Directory.CreateDirectory(cleanableWs);
            foreach (string srcFile in Directory.GetFiles(pkgWsDir, "*", SearchOption.AllDirectories))
            {
                string rel = Path.GetRelativePath(pkgWsDir, srcFile);
                if (rel.StartsWith("packs" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) continue;
                string dstFile = Path.Combine(cleanableWs, rel);
                Directory.CreateDirectory(Path.GetDirectoryName(dstFile)!);
                File.Copy(srcFile, dstFile, overwrite: true);
            }
            var (cleanedCount, _) = PackWorkspaceService.CleanUnusedWorkspaceAssets(pkgPack, cleanableWs);
            if (cleanedCount < 6
                || !File.Exists(Path.Combine(cleanableWs, "anim", "backdrop_200858", "f_0001.jpg"))
                || !File.Exists(Path.Combine(cleanableWs, "icons", "dot.svg"))
                || !File.Exists(Path.Combine(cleanableWs, "icons", "music.svg"))
                || File.Exists(Path.Combine(cleanableWs, "media", "tried_bg_1.jpg"))
                || Directory.Exists(Path.Combine(cleanableWs, "anim", "backdrop_old_140112"))
                || File.Exists(Path.Combine(cleanableWs, "icons", "unused_custom.svg"))
                || File.Exists(Path.Combine(cleanableWs, "img", "unused_pic.jpg")))
            {
                throw new InvalidOperationException("CleanUnusedWorkspaceAssets failed to prune unused custom assets cleanly.");
            }

            // SHA mismatch must fail
            bool wrongShaBlocked = false;
            try { ThemePackageService.ExtractSingleThemeZipSafely(validZipBytes, extractedDir + "_badsha", new string('0', 64)); }
            catch { wrongShaBlocked = true; }
            if (!wrongShaBlocked) throw new InvalidOperationException("SHA-256 mismatch was not rejected.");

            static byte[] BuildCustomZip(Action<ZipArchive> writer)
            {
                using var ms = new MemoryStream();
                using (var z = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
                    writer(z);
                return ms.ToArray();
            }

            static void AddZipText(ZipArchive z, string path, string text)
            {
                var e = z.CreateEntry(path);
                using var s = e.Open();
                byte[] b = Encoding.UTF8.GetBytes(text);
                s.Write(b, 0, b.Length);
            }

            static void AssertBlockedZip(string label, byte[] zipData, string rootTmp)
            {
                bool blocked = false;
                try
                {
                    ThemePackageService.ExtractSingleThemeZipSafely(zipData, Path.Combine(rootTmp, "rej_" + Guid.NewGuid().ToString("N")[..6]));
                }
                catch { blocked = true; }
                if (!blocked) throw new InvalidOperationException($"Security check failed to block: {label}");
            }

            string cleanLayoutJson = pkgPack.ToJsonString();

            AssertBlockedZip("disguised MZ executable in .png", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                var e = z.CreateEntry("img/fake.png");
                using var s = e.Open();
                s.Write(new byte[] { 0x4D, 0x5A, 0x90, 0x00, 0x03, 0x00 });
            }), storeTestRoot);

            AssertBlockedZip("disallowed script file (.ps1)", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "icons/payload.ps1", "Write-Host 'bad'");
            }), storeTestRoot);

            AssertBlockedZip("SVG containing <script>", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "icons/xss.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>");
            }), storeTestRoot);

            AssertBlockedZip("SVG containing onclick handler", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "icons/ev.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" onclick=\"alert(1)\"><circle cx=\"5\" cy=\"5\" r=\"3\"/></svg>");
            }), storeTestRoot);

            AssertBlockedZip("SVG XXE internal DTD entity", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "icons/xxe.svg", "<!DOCTYPE svg [ <!ENTITY xxe SYSTEM \"file:///etc/passwd\"> ]><svg xmlns=\"http://www.w3.org/2000/svg\">&xxe;</svg>");
            }), storeTestRoot);

            AssertBlockedZip("multi-theme pack ZIP", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "pack2/layout.json", cleanLayoutJson);
            }), storeTestRoot);

            AssertBlockedZip("ZIP-slip traversal entry", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "../../evil.png", "bad");
            }), storeTestRoot);

            AssertBlockedZip("malformed anim frames.json", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "anim/bad/frames.json", "{ not valid json");
            }), storeTestRoot);

            AssertBlockedZip("arbitrary non-frames json in anim/", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "anim/bad/other.json", "{\"a\":1}");
            }), storeTestRoot);

            AssertBlockedZip("arbitrary json in icons/", BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", cleanLayoutJson);
                AddZipText(z, "icons/evil.json", "{\"a\":1}");
            }), storeTestRoot);

            // Root actions (root_service / root_start / root_broadcast) are permitted for NEMESIS + root head units
            var rootActionPack = pkgPack.DeepClone();
            rootActionPack.Root.Children ??= new List<LayoutNodeModel>();
            rootActionPack.Root.Children.Add(new LayoutNodeModel
            {
                Id = "root_btn",
                Type = "tile",
                Kind = "shortcut",
                Action = new ActionSpecModel { Type = "root_service", Package = "com.example", Class = "com.example.Svc" }
            });
            string rootWs = Path.Combine(storeTestRoot, "root_ws");
            Directory.CreateDirectory(rootWs);
            File.WriteAllText(Path.Combine(rootWs, "layout.json"), rootActionPack.ToJsonString());
            var rootAudit = ThemePackageService.AuditWorkspaceTheme(rootActionPack.DeepClone(), rootWs);
            if (!rootAudit.IsSafe) throw new InvalidOperationException("Root actions should be permitted for Studio/Store themes.");
            var rootZipBytes = BuildCustomZip(z => AddZipText(z, "layout.json", rootActionPack.ToJsonString()));
            var (rootExtPack, rootExtAudit) = ThemePackageService.ExtractSingleThemeZipSafely(rootZipBytes, Path.Combine(storeTestRoot, "root_ext"));
            if (!rootExtAudit.IsSafe || rootExtPack.Root.Children?.Last().Action?.Type != "root_service")
                throw new InvalidOperationException("ZIP extraction failed to permit root_service action.");

            // Test ThemeStoreClient JSON parsing, HTTPS enforcement, newest-first sort, authoritative server categories, isOwner flag, category + search filters
            const string mockCatalog = """
            {
              "categories": ["Custom Cat A", "Custom Cat B", "Dark / AMOLED", "Minimal"],
              "themes": [
                { "id": "old-theme", "name": "Old Minimal", "author": "Bob", "version": "1.0.0", "revision": 1, "category": "Minimal", "updatedAt": "2026-09-01T10:00:00Z", "downloadUrl": "http://insecure.example.com/old.zip" },
                { "id": "new-amoled", "name": "Midnight EV", "author": "Tapple", "version": "1.1.0", "revision": 2, "category": "Dark / AMOLED", "updatedAt": "2026-11-18T12:00:00Z", "downloadUrl": "/downloads/midnight.zip", "thumbnailUrl": "/thumbs/midnight.png", "isOwner": true },
                { "id": "mid-amoled", "name": "Night Cluster", "author": "Alice", "version": "1.0.0", "revision": 5, "category": "Dark / AMOLED", "updatedAt": "2026-10-15T12:00:00Z", "downloadUrl": "https://market.ioniqthemes.com/downloads/night.zip" }
              ]
            }
            """;
            var storeUri = ThemeStoreClient.NormalizeAndValidateBaseUri(ThemeStoreClient.DefaultBaseUrl);
            var fullRes = ThemeStoreClient.ParseAndPrepareCatalog(mockCatalog, storeUri, "https://market.ioniqthemes.com/api/v1/themes");
            if (fullRes.Themes.Count != 3 || fullRes.Themes[0].Id != "new-amoled" || fullRes.Themes[1].Id != "mid-amoled" || fullRes.Themes[2].Id != "old-theme")
                throw new InvalidOperationException("Catalog failed newest-first date ordering.");
            if (!fullRes.Categories.SequenceEqual(new[] { "Custom Cat A", "Custom Cat B", "Dark / AMOLED", "Minimal" }))
                throw new InvalidOperationException("Catalog categories should use server list cleanly without injecting hardcoded presets: " + string.Join(", ", fullRes.Categories));
            if (ThemeStoreClient.LastServerCategories == null || !ThemeStoreClient.LastServerCategories.Contains("Custom Cat A"))
                throw new InvalidOperationException("LastServerCategories was not updated from server response.");
            if (!fullRes.Themes[0].IsOwnedByCurrentUser || fullRes.Themes[1].IsOwnedByCurrentUser)
                throw new InvalidOperationException("Catalog failed isOwner parsing.");
            if (fullRes.Themes[0].DownloadUrl != "https://market.ioniqthemes.com/downloads/midnight.zip" || fullRes.Themes[2].DownloadUrl != null)
                throw new InvalidOperationException("Catalog HTTPS URL validation/resolution failed.");

            var filteredRes = ThemeStoreClient.ParseAndPrepareCatalog(mockCatalog, storeUri, "https://market.ioniqthemes.com/api/v1/themes",
                categoryFilter: "Dark / AMOLED", searchFilter: "Tapple");
            if (filteredRes.Themes.Count != 1 || filteredRes.Themes[0].Id != "new-amoled")
                throw new InvalidOperationException("Catalog category + search filter failed.");

            // Test DPAPI auth session protection + per-origin isolation + token expiry
            byte[] dpapiRoundTrip = StoreAuthStorage.UnprotectBytes(StoreAuthStorage.ProtectBytes(Encoding.UTF8.GetBytes("jwt_secret_123")));
            if (Encoding.UTF8.GetString(dpapiRoundTrip) != "jwt_secret_123")
                throw new InvalidOperationException("DPAPI Protect/Unprotect round-trip failed.");

            string authDatPath = Path.Combine(storeTestRoot, "store_auth.dat");
            var savedSess = new StoredStoreSession
            {
                Username = "tapple_creator",
                Token = "tok_abc_secret_987",
                ExpiresAt = DateTime.UtcNow.AddDays(30).ToString("yyyy-MM-ddTHH:mm:ssZ")
            };
            StoreAuthStorage.SaveSession("https://market.ioniqthemes.com/", savedSess, authDatPath);
            byte[] rawDat = File.ReadAllBytes(authDatPath);
            if (Encoding.UTF8.GetString(rawDat).Contains("tok_abc_secret_987", StringComparison.Ordinal))
                throw new InvalidOperationException("Auth token was stored unencrypted on disk.");
            var loadedProd = StoreAuthStorage.LoadSession("https://MARKET.ioniqthemes.com/", authDatPath);
            if (loadedProd == null || loadedProd.Token != "tok_abc_secret_987" || loadedProd.IsExpired())
                throw new InvalidOperationException("Failed to load saved DPAPI auth session for matching origin.");
            if (StoreAuthStorage.LoadSession("https://evil.example.com/", authDatPath) != null)
                throw new InvalidOperationException("Auth session leaked across origins.");
            StoreAuthStorage.ClearSession("https://market.ioniqthemes.com/", authDatPath);
            if (StoreAuthStorage.LoadSession("https://market.ioniqthemes.com/", authDatPath) != null)
                throw new InvalidOperationException("ClearSession failed to remove saved session.");
            var expiredSess = new StoredStoreSession { Token = "x", ExpiresAt = DateTime.UtcNow.AddMinutes(-5).ToString("yyyy-MM-ddTHH:mm:ssZ") };
            if (!expiredSess.IsExpired())
                throw new InvalidOperationException("Expired session check returned false.");

            // Test username/password validators
            if (!ThemeStoreClient.ValidateUsername("tapple_creator-1", out _)
                || ThemeStoreClient.ValidateUsername("ab", out _)
                || ThemeStoreClient.ValidateUsername("bad user", out _)
                || ThemeStoreClient.ValidateUsername(new string('a', 33), out _))
                throw new InvalidOperationException("Username validation edge case failed.");
            if (!ThemeStoreClient.ValidatePassword("12345678", out _)
                || ThemeStoreClient.ValidatePassword("short", out _)
                || ThemeStoreClient.ValidatePassword(new string('p', 129), out _))
                throw new InvalidOperationException("Password validation edge case failed.");

            // Test canonical ID alignment between manifest metadata and extracted layout.json
            string dirtyWsDir = Path.Combine(storeTestRoot, "dirty_ws");
            Directory.CreateDirectory(dirtyWsDir);
            var dirtyPack = PresetCatalog.CreateNewPack("My Dirty Theme! 1", "Dirty ID Theme", "Tester", "blank_grid", true);
            dirtyPack.Id = "My Dirty Theme! 1";
            File.WriteAllText(Path.Combine(dirtyWsDir, "layout.json"), dirtyPack.ToJsonString());
            string dirtyZip = Path.Combine(storeTestRoot, "dirty.zip");
            var dirtyBuilt = ThemePackageService.BuildSingleThemeZip(dirtyPack, dirtyWsDir, thumbPng, dirtyZip);
            var (dirtyExtracted, _) = ThemePackageService.ExtractSingleThemeZipSafely(File.ReadAllBytes(dirtyZip), Path.Combine(storeTestRoot, "dirty_ext"));
            if (dirtyBuilt.Manifest.Theme.Id != "my_dirty_theme__1" || dirtyExtracted.Id != "my_dirty_theme__1")
                throw new InvalidOperationException($"Canonical theme ID alignment failed: manifest='{dirtyBuilt.Manifest.Theme.Id}', layout='{dirtyExtracted.Id}'");

            // End-to-end loopback HTTP integration test for register, login, /auth/me, GET isOwner, upload Bearer+409, delete+404
            int freePort;
            using (var portProbe = new TcpListener(IPAddress.Loopback, 0))
            {
                portProbe.Start();
                freePort = ((IPEndPoint)portProbe.LocalEndpoint).Port;
            }
            string mockBaseUrl = $"http://127.0.0.1:{freePort}/";
            using (var listener = new HttpListener())
            {
                listener.Prefixes.Add(mockBaseUrl);
                listener.Start();
                int uploadCount = 0;
                int deleteCount = 0;

                var serverTask = Task.Run(async () =>
                {
                    while (listener.IsListening)
                    {
                        HttpListenerContext ctx;
                        try { ctx = await listener.GetContextAsync().ConfigureAwait(false); }
                        catch { break; }

                        using (var resp = ctx.Response)
                        {
                            string path = ctx.Request.Url?.AbsolutePath ?? "/";
                            string method = ctx.Request.HttpMethod;
                            string authHdr = ctx.Request.Headers["Authorization"] ?? "";
                            string bodyText = "";
                            if (ctx.Request.HasEntityBody)
                            {
                                using var msBody = new MemoryStream();
                                await ctx.Request.InputStream.CopyToAsync(msBody).ConfigureAwait(false);
                                bodyText = Encoding.UTF8.GetString(msBody.ToArray());
                            }

                            static void WriteJson(HttpListenerResponse r, int status, string json, int? retryAfterSec = null)
                            {
                                r.StatusCode = status;
                                r.ContentType = "application/json; charset=utf-8";
                                if (retryAfterSec.HasValue)
                                    r.AddHeader("Retry-After", retryAfterSec.Value.ToString());
                                byte[] bytes = Encoding.UTF8.GetBytes(json);
                                r.ContentLength64 = bytes.Length;
                                r.OutputStream.Write(bytes, 0, bytes.Length);
                            }

                            if (method == "POST" && path == "/api/v1/auth/register")
                            {
                                if (bodyText.Contains("rate_limited", StringComparison.Ordinal))
                                    WriteJson(resp, 503, """{"ok":false,"error":"Registration temporarily disabled."}""", 900);
                                else
                                    WriteJson(resp, 201, """{"ok":true,"message":"Account created.","token":"tok_reg_1","tokenType":"Bearer","expiresAt":"2030-01-01T00:00:00Z","account":{"username":"tapple_test","createdAt":"2026-11-18T18:00:00Z"}}""");
                            }
                            else if (method == "POST" && path == "/api/v1/auth/login")
                            {
                                if (bodyText.Contains("badpass", StringComparison.Ordinal))
                                    WriteJson(resp, 401, """{"ok":false,"error":"Invalid username or password."}""");
                                else if (bodyText.Contains("throttle", StringComparison.Ordinal))
                                    WriteJson(resp, 429, """{"ok":false,"error":"Too many failed login attempts."}""", 60);
                                else
                                    WriteJson(resp, 200, """{"ok":true,"token":"tok_login_2","tokenType":"Bearer","expiresAt":"2030-01-01T00:00:00Z","account":{"username":"tapple_test","createdAt":"2026-11-18T18:00:00Z"}}""");
                            }
                            else if (method == "GET" && path == "/api/v1/auth/me")
                            {
                                if (authHdr == "Bearer tok_login_2")
                                    WriteJson(resp, 200, """{"ok":true,"account":{"username":"tapple_test","createdAt":"2026-11-18T18:00:00Z"}}""");
                                else
                                    WriteJson(resp, 401, """{"ok":false,"error":"Token expired or invalid."}""");
                            }
                            else if (method == "GET" && path == "/api/v1/themes")
                            {
                                string ownerField = authHdr == "Bearer tok_login_2" ? "\"isOwner\":true," : "";
                                WriteJson(resp, 200, $$"""{"themes":[{"id":"my-test-theme","name":"My Test","author":"Tapple","version":"1.0.0","revision":1,"category":"Minimal",{{ownerField}}"updatedAt":"2026-11-18T12:00:00Z","downloadUrl":"/downloads/my.zip"}]}""");
                            }
                            else if (method == "POST" && path == "/api/v1/themes")
                            {
                                static bool HasPart(string raw, string partName) =>
                                    raw.Contains($"name={partName}", StringComparison.OrdinalIgnoreCase)
                                    || raw.Contains($"name=\"{partName}\"", StringComparison.OrdinalIgnoreCase);

                                if (authHdr != "Bearer tok_login_2")
                                {
                                    WriteJson(resp, 401, """{"ok":false,"error":"Authentication required."}""");
                                }
                                else if (!HasPart(bodyText, "package")
                                         || !HasPart(bodyText, "thumbnail")
                                         || !HasPart(bodyText, "metadata"))
                                {
                                    WriteJson(resp, 400, """{"ok":false,"error":"Missing multipart fields."}""");
                                }
                                else
                                {
                                    uploadCount++;
                                    if (uploadCount == 1)
                                        WriteJson(resp, 201, """{"ok":true,"message":"Uploaded successfully."}""");
                                    else
                                        WriteJson(resp, 409, """{"ok":false,"error":"Duplicate revision already exists."}""");
                                }
                            }
                            else if (method == "DELETE" && path == "/api/v1/themes/my-test-theme")
                            {
                                if (authHdr != "Bearer tok_login_2")
                                {
                                    WriteJson(resp, 401, """{"ok":false,"error":"Authentication required."}""");
                                }
                                else
                                {
                                    deleteCount++;
                                    if (deleteCount == 1)
                                        WriteJson(resp, 200, """{"ok":true,"message":"Theme deleted successfully."}""");
                                    else
                                        WriteJson(resp, 404, """{"ok":false,"error":"Theme not found."}""");
                                }
                            }
                            else
                            {
                                WriteJson(resp, 404, """{"ok":false,"error":"Unknown route."}""");
                            }
                        }
                    }
                });

                try
                {
                    var regOk = ThemeStoreClient.RegisterAsync(mockBaseUrl, "tapple_test", "StrongPass123!").GetAwaiter().GetResult();
                    if (!regOk.Ok || regOk.Token != "tok_reg_1")
                        throw new InvalidOperationException("Loopback RegisterAsync failed.");

                    bool reg503Ok = false;
                    try { ThemeStoreClient.RegisterAsync(mockBaseUrl, "rate_limited", "StrongPass123!").GetAwaiter().GetResult(); }
                    catch (ThemeStoreApiException ex503) when (ex503.StatusCode == HttpStatusCode.ServiceUnavailable && ex503.RetryAfterSeconds == 900)
                    { reg503Ok = true; }
                    if (!reg503Ok) throw new InvalidOperationException("Register 503 + Retry-After handling failed.");

                    var loginOk = ThemeStoreClient.LoginAsync(mockBaseUrl, "tapple_test", "StrongPass123!").GetAwaiter().GetResult();
                    if (!loginOk.Ok || loginOk.Token != "tok_login_2")
                        throw new InvalidOperationException("Loopback LoginAsync failed.");

                    bool login429Ok = false;
                    try { ThemeStoreClient.LoginAsync(mockBaseUrl, "throttle", "x").GetAwaiter().GetResult(); }
                    catch (ThemeStoreApiException ex429) when (ex429.StatusCode == HttpStatusCode.TooManyRequests && ex429.RetryAfterSeconds == 60)
                    { login429Ok = true; }
                    if (!login429Ok) throw new InvalidOperationException("Login 429 + Retry-After handling failed.");

                    var meOk = ThemeStoreClient.ValidateSessionAsync(mockBaseUrl, "tok_login_2").GetAwaiter().GetResult();
                    if (meOk.Account?.Username != "tapple_test")
                        throw new InvalidOperationException("Loopback ValidateSessionAsync failed.");

                    bool me401Ok = false;
                    try { ThemeStoreClient.ValidateSessionAsync(mockBaseUrl, "bad_tok").GetAwaiter().GetResult(); }
                    catch (ThemeStoreApiException ex401) when (ex401.StatusCode == HttpStatusCode.Unauthorized)
                    { me401Ok = true; }
                    if (!me401Ok) throw new InvalidOperationException("ValidateSessionAsync 401 handling failed.");

                    var catOwned = ThemeStoreClient.FetchCatalogAsync(mockBaseUrl, null, null, "tok_login_2").GetAwaiter().GetResult();
                    var catAnon = ThemeStoreClient.FetchCatalogAsync(mockBaseUrl, null, null, null).GetAwaiter().GetResult();
                    if (!catOwned.Themes[0].IsOwnedByCurrentUser || catAnon.Themes[0].IsOwnedByCurrentUser)
                        throw new InvalidOperationException("Loopback FetchCatalogAsync Bearer isOwner distinction failed.");

                    var upOk = ThemeStoreClient.UploadThemeAsync(mockBaseUrl, outZip, thumbPng, builtPkg.Manifest, "tok_login_2").GetAwaiter().GetResult();
                    if (!upOk.Ok) throw new InvalidOperationException("Loopback UploadThemeAsync failed.");

                    bool up409Ok = false;
                    try { ThemeStoreClient.UploadThemeAsync(mockBaseUrl, outZip, thumbPng, builtPkg.Manifest, "tok_login_2").GetAwaiter().GetResult(); }
                    catch (ThemeStoreApiException ex409) when (ex409.StatusCode == HttpStatusCode.Conflict)
                    { up409Ok = true; }
                    if (!up409Ok) throw new InvalidOperationException("UploadThemeAsync 409 handling failed.");

                    var delOk = ThemeStoreClient.DeleteThemeAsync(mockBaseUrl, "my-test-theme", "tok_login_2").GetAwaiter().GetResult();
                    if (!delOk.Ok) throw new InvalidOperationException("Loopback DeleteThemeAsync failed.");

                    bool del404Ok = false;
                    try { ThemeStoreClient.DeleteThemeAsync(mockBaseUrl, "my-test-theme", "tok_login_2").GetAwaiter().GetResult(); }
                    catch (ThemeStoreApiException ex404) when (ex404.StatusCode == HttpStatusCode.NotFound)
                    { del404Ok = true; }
                    if (!del404Ok) throw new InvalidOperationException("DeleteThemeAsync 404 handling failed.");
                }
                finally
                {
                    try { listener.Stop(); } catch { }
                    try { serverTask.Wait(2000); } catch { }
                }
            }

            // Test workspace.ImportSingleThemeZipIntoCurrentPack + Auth/Store/Upload dialog XAML initialization
            var importedEntry = workspace.ImportSingleThemeZipIntoCurrentPack(validZipBytes, builtPkg.PackageSha256);
            if (!Directory.Exists(importedEntry.Directory) || !File.Exists(Path.Combine(importedEntry.Directory, "thumbnail.png")))
                throw new InvalidOperationException("ImportSingleThemeZipIntoCurrentPack failed to stage verified theme.");

            var testAuthDlg = new StoreAuthDialog(ThemeStoreClient.DefaultBaseUrl);
            testAuthDlg.Close();
            var testUploadDlg = new UploadThemeDialog(pkgPack, pkgWsDir, thumbPng, () => thumbPng);
            testUploadDlg.Close();
            var testStoreDlg = new ThemeStoreWindow(workspace, canUploadActiveTheme: false);
            testStoreDlg.Close();
            var testDetailDlg = new ThemeDetailDialog(
                fullRes.Themes[0],
                thumbPng,
                (_, _) => Task.FromResult(false),
                (_, _) => Task.CompletedTask,
                (_, _) => Task.FromResult(false));
            testDetailDlg.Close();

            // Test RecentWorkspacesService persistence, dedupe, 12-item cap, remove, and clear
            string recentsUnitFile = Path.Combine(storeTestRoot, "recents_unit.json");
            for (int i = 1; i <= 15; i++)
                RecentWorkspacesService.RecordOpened(Path.Combine(storeTestRoot, $"theme_{i:00}"), $"Theme {i:00}", isSingleTheme: true, recentsUnitFile);
            RecentWorkspacesService.RecordOpened(Path.Combine(storeTestRoot, "theme_01"), "Theme 01 Updated", isSingleTheme: false, recentsUnitFile);
            var recList = RecentWorkspacesService.LoadRecent(recentsUnitFile);
            if (recList.Count != RecentWorkspacesService.MaxEntries
                || recList[0].DisplayName != "Theme 01 Updated"
                || recList[0].Kind != "Pack")
                throw new InvalidOperationException("RecentWorkspacesService dedupe/cap/reorder failed.");
            RecentWorkspacesService.RemoveRecent(recList[0].Path, recentsUnitFile);
            if (RecentWorkspacesService.LoadRecent(recentsUnitFile).Count != RecentWorkspacesService.MaxEntries - 1)
                throw new InvalidOperationException("RecentWorkspacesService RemoveRecent failed.");
            RecentWorkspacesService.ClearRecent(recentsUnitFile);
            if (RecentWorkspacesService.LoadRecent(recentsUnitFile).Count != 0)
                throw new InvalidOperationException("RecentWorkspacesService ClearRecent failed.");

            // Opening a single-theme folder via OpenWorkspaceFolderPath records it in recent history and binds cleanly
            OpenWorkspaceFolderPath(pkgWsDir);
            if (!HasActiveTheme || RecentWorkspacesService.LoadRecent(recentStoragePath).Count == 0 || RecentListHost.Children.Count == 0)
                throw new InvalidOperationException("OpenWorkspaceFolderPath failed to activate theme or update Recently Opened UI.");

            // Verify StudioCanvas takes focus to clear TextBoxes and element switching updates WidthBox/HeightBox immediately
            if (!StudioCanvas.Focusable)
                throw new InvalidOperationException("StudioCanvas must be Focusable so clicking canvas elements clears focused inspector inputs.");
            var elemA = new LayoutNodeModel { Id = "sel_a", Type = "tile", Kind = "tile", Title = "A", Width = 220, Height = 120 };
            var elemB = new LayoutNodeModel { Id = "sel_b", Type = "tile", Kind = "tile", Title = "B", Width = 360, Height = 260 };
            currentPack.Root.Children ??= new List<LayoutNodeModel>();
            currentPack.Root.Children.Add(elemA);
            currentPack.Root.Children.Add(elemB);
            BindWholePack(elemA);
            if (WidthBox.Text != "220" || HeightBox.Text != "120")
                throw new InvalidOperationException($"Expected elemA geometry 220x120, got {WidthBox.Text}x{HeightBox.Text}");
            HeightBox.Focus();
            OnCanvasNodeSelected(elemB);
            if (WidthBox.Text != "360" || HeightBox.Text != "260" || elemB.Height != 260)
                throw new InvalidOperationException($"Element switch did not immediately update Width/Height fields to elemB (got {WidthBox.Text}x{HeightBox.Text}).");
            OnCanvasNodeSelected(elemA);
            if (WidthBox.Text != "220" || HeightBox.Text != "120")
                throw new InvalidOperationException($"Element switch back to elemA failed to update Width/Height fields (got {WidthBox.Text}x{HeightBox.Text}).");

            // Verify selecting a sensor from TelemetryKeyCombo keeps the key text populated instead of blanking out
            TelemetryKeyCombo.SelectedItem = PresetCatalog.TelemetrySensors.First(s => !s.IsHeader && s.Key == "hv.soc");
            Dispatcher.Invoke(System.Windows.Threading.DispatcherPriority.Background, new Action(() => { }));
            if (TelemetryKeyCombo.Text != "hv.soc" || elemA.TelemetryKey != "hv.soc")
                throw new InvalidOperationException($"TelemetryKeyCombo failed to retain selected sensor text (text='{TelemetryKeyCombo.Text}', node='{elemA.TelemetryKey}').");
            if (PresetCatalog.TelemetrySensors.Count(s => s.IsHeader) < 6
                || PresetCatalog.TelemetrySensors[0].IsHeader)
                throw new InvalidOperationException("TelemetrySensors missing category headers or (None) entry at index 0.");
            if (!PresetCatalog.ActionPresets.Any(p => !p.IsHeader && p.ActionType == "preset" && p.Cmd == "seat_popup")
                || PresetCatalog.ActionPresets.Count(p => p.IsHeader) < 7
                || PresetCatalog.ActionPresets[0].IsHeader
                || PresetCatalog.ActionPresets.Count(p => !p.IsHeader) < 85)
                throw new InvalidOperationException("ActionPresets missing category headers, Seat Heat & Vent Popup, or full OEM shortcuts.");

            // Verify 135° gradient matches Android BR_TL ((1,1)->(0,0)) rather than WPF out-of-bounds angle bug
            var grad135 = Controls.StudioCanvasControl.CreateAndroidMatchedGradient(
                System.Windows.Media.Color.FromArgb(0xA8, 0x24, 0x11, 0x05),
                System.Windows.Media.Color.FromArgb(0xFF, 0x13, 0x60, 0x89),
                135);
            if (grad135.StartPoint != new Point(1, 1) || grad135.EndPoint != new Point(0, 0))
                throw new InvalidOperationException("135° LinearGradientBrush did not map to Android BR_TL (1,1)->(0,0).");

            // Verify contextual inspector visibility hides irrelevant fields per panel layout & widget kind
            var visPanel = new LayoutNodeModel
            {
                Id = "vis_grid",
                Type = "panel",
                Layout = "grid",
                Children = new List<LayoutNodeModel>
                {
                    new() { Id = "vis_text", Type = "tile", Kind = "text", Title = "Speed" },
                    new() { Id = "vis_bar", Type = "tile", Kind = "progress_bar", ShowText = false },
                    new() { Id = "vis_spacer", Type = "tile", Kind = "spacer" }
                }
            };
            currentPack.Root.Children.Add(visPanel);
            BindWholePack(visPanel);
            if (ColsCell.Visibility != Visibility.Visible || BoundsRow.Visibility == Visibility.Visible)
                throw new InvalidOperationException("Grid panel visibility mismatch in inspector.");
            NodeTypeCombo.SelectedItem = "flex_col";
            if (ColsCell.Visibility != Visibility.Visible || RowsCell.Visibility != Visibility.Visible || GapCell.Visibility != Visibility.Visible)
                throw new InvalidOperationException("flex_col panel should keep Grid Cols/Rows and Gap visible.");
            NodeTypeCombo.SelectedItem = "flex_row";
            if (ColsCell.Visibility != Visibility.Visible || RowsCell.Visibility != Visibility.Visible || GapCell.Visibility != Visibility.Visible)
                throw new InvalidOperationException("flex_row panel should keep Grid Cols/Rows and Gap visible.");
            OnCanvasNodeSelected(visPanel.Children[0]); // text inside flex_row + exercises ScrollTreeToNode
            Dispatcher.Invoke(System.Windows.Threading.DispatcherPriority.Loaded, new Action(() => { }));
            if (MinValCell.Visibility == Visibility.Visible || IconImageSection.Visibility == Visibility.Visible || FlexCell.Visibility != Visibility.Visible || !visPanel.IsExpanded || !visPanel.Children[0].IsSelected)
                throw new InvalidOperationException("text widget inside flex_row showed irrelevant range/icon, hid FlexWeight, or failed tree selection/expansion.");
            BindWholePack(visPanel.Children[1]); // progress_bar without ShowText
            if (BarDirCell.Visibility != Visibility.Visible || FontRow.Visibility != Visibility.Visible)
                throw new InvalidOperationException("progress_bar visibility mismatch (BarDir + Typography should stay visible).");
            BindWholePack(visPanel.Children[2]); // spacer
            if (WidgetFieldsGroup.Visibility == Visibility.Visible || StyleSectionGroup.Visibility == Visibility.Visible)
                throw new InvalidOperationException("spacer widget failed to hide content and style sections.");

            // Verify selecting an off-screen pager page (or its child) switches the preview canvas to that page even when page bar is hidden
            var hiddenBarPager = new LayoutNodeModel
            {
                Id = "hidden_bar_pager",
                Type = "panel",
                Layout = "pager",
                FlexWeight = 1,
                ShowDots = false,
                Children = new List<LayoutNodeModel>
                {
                    new()
                    {
                        Id = "hb_page1", PageTitle = "P1", Type = "panel", Layout = "grid", Columns = 2, Rows = 1,
                        Children = new List<LayoutNodeModel> { new() { Id = "hb_p1_tile", Kind = "tile", Title = "One" } }
                    },
                    new()
                    {
                        Id = "hb_page2", PageTitle = "P2", Type = "panel", Layout = "grid", Columns = 2, Rows = 1,
                        Children = new List<LayoutNodeModel> { new() { Id = "hb_p2_tile", Kind = "tile", Title = "Two" } }
                    }
                }
            };
            currentPack.Root.Children.Add(hiddenBarPager);
            var p1Tile = hiddenBarPager.Children[0].Children![0];
            var p2Tile = hiddenBarPager.Children[1].Children![0];
            BindWholePack(p1Tile);
            if (StudioCanvas.GetPagerPageIndex("hidden_bar_pager") != 0 || !StudioCanvas.TryGetNodeRefRect(p1Tile, out _))
                throw new InvalidOperationException("Expected pager to start on page 0.");
            StudioCanvas.SetSelected(p2Tile);
            PopulateNodeInspector(p2Tile, forceRefreshFocusedFields: true);
            if (StudioCanvas.GetPagerPageIndex("hidden_bar_pager") != 1 || !StudioCanvas.TryGetNodeRefRect(p2Tile, out _))
                throw new InvalidOperationException("Selecting a child on offscreen page 2 did not switch pager preview to page 1.");

            // Verify ActionClsBox preserves OEM activity class names containing 'android.' (e.g. com.android.vehiclesettings.VehicleSetting)
            BindWholePack(p1Tile);
            ActionTypeBox.Text = "launch";
            ActionPkgBox.Text = "com.android.vehiclesettings";
            ActionClsBox.Text = "com.android.vehiclesettings.VehicleSetting";
            CommitInspectorToSelectedNode();
            if (p1Tile.Action?.Class != "com.android.vehiclesettings.VehicleSetting" || p1Tile.Action?.IntentAction != null)
                throw new InvalidOperationException("ActionClsBox misclassified OEM Activity class as IntentAction.");

            ActionTypeBox.Text = "broadcast";
            ActionClsBox.Text = "com.hkmc.intent.action.CLIMATE_POPUP";
            CommitInspectorToSelectedNode();
            if (p1Tile.Action?.IntentAction != "com.hkmc.intent.action.CLIMATE_POPUP" || p1Tile.Action?.Class != null)
                throw new InvalidOperationException("ActionClsBox failed to store broadcast action in IntentAction.");

            // Verify unedited token-backed style fields (CornerRadiusPx / AccentColor) are not baked onto node.Style when editing Title
            currentPack.Tokens.CornerRadiusPx = 30;
            currentPack.Tokens.AccentColor = "#FF10B981";
            var tokenTile = new LayoutNodeModel { Id = "tok_tile", Kind = "tile", Title = "Before" };
            hiddenBarPager.Children[0].Children!.Add(tokenTile);
            BindWholePack(tokenTile);
            TitleBox.Text = "After";
            CommitInspectorToSelectedNode();
            if (tokenTile.Style?.CornerRadiusPx != null || tokenTile.Style?.AccentColor != null)
                throw new InvalidOperationException("Editing Title unexpectedly baked CornerRadiusPx/AccentColor overrides over theme tokens.");

            // Verify Duplicate assigns unique IDs to both panel and descendant children
            OnCanvasNodeSelected(hiddenBarPager.Children[0]);
            OnDuplicateClick(this, new RoutedEventArgs());
            var dupPage = hiddenBarPager.Children.Last();
            if (dupPage.Id == "hb_page1" || dupPage.Children!.Any(c => c.Id == "hb_p1_tile" || c.Id == "tok_tile"))
                throw new InvalidOperationException("OnDuplicateClick left duplicate child IDs inside cloned panel.");

            // Verify StageThemeFolderIntoCurrentPack never points WorkspaceDir at the imported theme (both when promoting single-theme -> pack and inside an existing pack)
            string stageSrcDir = Path.Combine(storeTestRoot, "stage_import_src");
            Directory.CreateDirectory(stageSrcDir);
            var stageImportedPack = PresetCatalog.CreateNewPack("imported-distinct", "Imported Distinct Theme", "Tester", "blank_freeform", true);
            File.WriteAllText(Path.Combine(stageSrcDir, "layout.json"), stageImportedPack.ToJsonString());
            var stagedEntry = workspace.StageThemeFolderIntoCurrentPack(stageSrcDir);
            if (string.Equals(workspace.WorkspaceDir, stagedEntry.Directory, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("StageThemeFolderIntoCurrentPack pointed active WorkspaceDir at imported theme!");
            string wsMultiBefore = workspace.WorkspaceDir;
            var stagedEntry2 = workspace.StageThemeFolderIntoCurrentPack(stageSrcDir);
            if (!string.Equals(workspace.WorkspaceDir, wsMultiBefore, StringComparison.OrdinalIgnoreCase)
                || string.Equals(workspace.WorkspaceDir, stagedEntry2.Directory, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("StageThemeFolderIntoCurrentPack mutated multi-theme WorkspaceDir!");
            workspace.SaveCurrentTheme(currentPack);
            var reloadedStaged = ThemePackModel.FromJson(File.ReadAllText(Path.Combine(stagedEntry.Directory, "layout.json")));
            var reloadedStaged2 = ThemePackModel.FromJson(File.ReadAllText(Path.Combine(stagedEntry2.Directory, "layout.json")));
            if (reloadedStaged.Name != "Imported Distinct Theme" || reloadedStaged2.Name != "Imported Distinct Theme")
                throw new InvalidOperationException("SaveCurrentTheme overwrote staged imported theme!");

            // Verify InsetsJsonConverter (scalar + 2-int), formatVersion 1, top-level 'pages', & all legacy background/widget/style/action aliases
            const string compatJson = """
            {
              "formatVersion": 1,
              "id": "compat-check",
              "name": "Compat Check",
              "background": { "type": "bitmap", "posterAsset": "media/old_poster.jpg" },
              "pages": [
                {
                  "id": "pg1",
                  "type": "panel",
                  "layout": "grid",
                  "paddingPx": 20,
                  "children": [
                    {
                      "id": "w_clk",
                      "type": "clock"
                    },
                    {
                      "id": "t1",
                      "kind": "tile",
                      "marginPx": [12, 8],
                      "style": { "gradientEndColor": "#FF112233", "fontSizePx": 28, "animAsset": "anim/wave", "animFps": 12 },
                      "action": { "type": "preset", "preset": "ev" }
                    },
                    {
                      "id": "t2",
                      "kind": "tile",
                      "action": { "type": "broadcast", "pkg": "com.example.car", "cls": "com.example.car.Main", "action": "com.example.ACT", "extraIntKey": "k", "extraIntValue": 3 }
                    }
                  ]
                }
              ]
            }
            """;
            var compatPack = ThemePackModel.FromJson(compatJson);
            var compatPage = compatPack.Root.Children![0];
            var compatClock = compatPage.Children![0];
            var compatTile = compatPage.Children![1];
            var compatTile2 = compatPage.Children![2];
            if (compatPack.FormatVersion != 1
                || compatPack.Background.BitmapAsset != "media/old_poster.jpg"
                || compatPack.Root.Layout != "pager"
                || compatClock.Kind != "clock"
                || compatPage.PaddingPx is not { Length: 4 } || compatPage.PaddingPx[0] != 20 || compatPage.PaddingPx[3] != 20
                || compatTile.MarginPx is not { Length: 4 } || compatTile.MarginPx[0] != 12 || compatTile.MarginPx[1] != 8 || compatTile.MarginPx[2] != 12
                || compatTile.Style?.BgEndColor != "#FF112233" || compatTile.Style?.TitleSizePx != 28
                || compatTile.Style?.BgAnimAsset != "anim/wave" || compatTile.Style?.BgAnimFps != 12
                || compatTile.Action?.Cmd != "ev"
                || compatTile2.Action?.Package != "com.example.car" || compatTile2.Action?.Class != "com.example.car.Main"
                || compatTile2.Action?.IntentAction != "com.example.ACT"
                || compatTile2.Action?.ExtraKey != "k" || compatTile2.Action?.ExtraType != "int" || compatTile2.Action?.ExtraVal != "3")
                throw new InvalidOperationException("ThemePackModel legacy v0.1-v0.4.x schema compatibility normalization failed.");

            // Verify Studio also still safely imports older pre-v0.5.0 theme .zip files that contain extra unreferenced assets
            byte[] legacyZipBytes = BuildCustomZip(z =>
            {
                AddZipText(z, "layout.json", compatJson);
                var p = z.CreateEntry("media/old_poster.jpg");
                using (var s = p.Open()) s.Write(validJpegBytes, 0, validJpegBytes.Length);
                var u = z.CreateEntry("media/unused_old.jpg");
                using (var s = u.Open()) s.Write(validJpegBytes, 0, validJpegBytes.Length);
                AddZipText(z, "icons/music.svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"5\" cy=\"5\" r=\"2\"/></svg>");
            });
            string legacyExtractDir = Path.Combine(storeTestRoot, "legacy_zip_import");
            var (legacyImportedPack, legacyAudit) = ThemePackageService.ExtractSingleThemeZipSafely(legacyZipBytes, legacyExtractDir);
            if (!legacyAudit.IsSafe
                || legacyImportedPack.Background.BitmapAsset != "media/old_poster.jpg"
                || !File.Exists(Path.Combine(legacyExtractDir, "media", "old_poster.jpg"))
                || !File.Exists(Path.Combine(legacyExtractDir, "media", "unused_old.jpg"))
                || !File.Exists(Path.Combine(legacyExtractDir, "icons", "music.svg")))
                throw new InvalidOperationException("Legacy pre-v0.5.0 .zip theme import failed.");

            // Verify AI Theme Context Builder stays 100% synchronized with PresetCatalog & ThemePackageService validator
            string aiPrompt = AiThemeContextBuilder.BuildPrompt(currentPack, includeActiveThemeJson: true);
            if (!aiPrompt.Contains($"v{PresetCatalog.StudioVersion}", StringComparison.Ordinal)
                || !aiPrompt.Contains("animated_frames", StringComparison.Ordinal)
                || !aiPrompt.Contains("frames.json", StringComparison.Ordinal)
                || !aiPrompt.Contains("bgAnimAsset", StringComparison.Ordinal))
                throw new InvalidOperationException("AiThemeContextBuilder missing version or animated background instructions.");
            foreach (string kindName in PresetCatalog.WidgetKinds)
            {
                if (!aiPrompt.Contains($"\"{kindName}\"", StringComparison.Ordinal))
                    throw new InvalidOperationException($"AiThemeContextBuilder out of sync: missing widget kind '{kindName}'.");
            }
            foreach (string bgType in PresetCatalog.BackgroundTypes)
            {
                if (!aiPrompt.Contains($"\"{bgType}\"", StringComparison.Ordinal))
                    throw new InvalidOperationException($"AiThemeContextBuilder out of sync: missing background type '{bgType}'.");
            }
            foreach (string iconPath in PresetCatalog.BuiltInIcons)
            {
                if (!aiPrompt.Contains($"\"{iconPath}\"", StringComparison.Ordinal))
                    throw new InvalidOperationException($"AiThemeContextBuilder out of sync: missing built-in icon '{iconPath}'.");
            }
            foreach (var sensor in PresetCatalog.TelemetrySensors.Where(s => !s.IsHeader && !string.IsNullOrEmpty(s.Key)))
            {
                if (!aiPrompt.Contains($"`{sensor.Key}`", StringComparison.Ordinal))
                    throw new InvalidOperationException($"AiThemeContextBuilder out of sync: missing telemetry key '{sensor.Key}'.");
            }
            foreach (var act in PresetCatalog.ActionPresets.Where(p => !p.IsHeader && p.ActionType != "none"))
            {
                string expectedToken = string.IsNullOrEmpty(act.Cmd) ? $"\"type\": \"{act.ActionType}\"" : $"\"cmd\": \"{act.Cmd}\"";
                if (!aiPrompt.Contains(expectedToken, StringComparison.Ordinal))
                    throw new InvalidOperationException($"AiThemeContextBuilder out of sync: missing action preset '{act.Display}'.");
            }
            string aiExampleJson = AiThemeContextBuilder.ExtractEmbeddedExampleJson(aiPrompt);
            var aiExampleModel = ThemePackModel.FromJson(aiExampleJson);
            string aiExampleDir = Path.Combine(storeTestRoot, "ai_example_theme");
            Directory.CreateDirectory(aiExampleDir);
            File.WriteAllText(Path.Combine(aiExampleDir, "layout.json"), aiExampleModel.ToJsonString());
            var aiAudit = ThemePackageService.AuditWorkspaceTheme(aiExampleModel, aiExampleDir);
            if (aiAudit.Errors.Count > 0)
                throw new InvalidOperationException("AiThemeContextBuilder example JSON failed AuditWorkspaceTheme: " + string.Join("; ", aiAudit.Errors));
            var aiDlg = new AiContextDialog(currentPack);
            aiDlg.Close();

            // Verify nested [freeform] panel click, drag, resize, inspector commit, & grid->freeform conversion NEVER add parent X/Y offset
            currentPack.Tokens.ShowSystemBar = true; // exercises scaleY = 0.875 too
            var nestedFreeformHost = new LayoutNodeModel
            {
                Id = "ff_host_freeform",
                Type = "panel",
                Layout = "freeform",
                Width = 1920,
                Height = 680,
                Children = new List<LayoutNodeModel>
                {
                    new()
                    {
                        Id = "sys_b",
                        Type = "panel",
                        Layout = "freeform",
                        Bounds = new double[] { 646, 50, 620, 540 },
                        PaddingPx = new[] { 10, 12, 10, 12 }, // inner origin = (656, 62) in ref space
                        Children = new List<LayoutNodeModel>
                        {
                            new() { Id = "sys_b_item1", Kind = "tile", Title = "Item 1", Bounds = new double[] { 64, 20, 200, 80 } },
                            new() { Id = "sys_b_unbounded", Kind = "tile", Title = "Unbounded" }
                        }
                    },
                    new()
                    {
                        Id = "sys_grid_to_ff",
                        Type = "panel",
                        Layout = "grid",
                        Columns = 2,
                        Rows = 1,
                        GapPx = 10,
                        Bounds = new double[] { 646, 600, 600, 70 },
                        Children = new List<LayoutNodeModel>
                        {
                            new() { Id = "g2f_a", Kind = "tile", Title = "A" },
                            new() { Id = "g2f_b", Kind = "tile", Title = "B" }
                        }
                    }
                }
            };
            currentPack.Root.Children.Add(nestedFreeformHost);
            var sysB = nestedFreeformHost.Children[0];
            var sysBItem1 = sysB.Children![0];
            var sysBUnbounded = sysB.Children![1];
            BindWholePack(sysBItem1);

            // 1) Pure click (0,0) and 1px jitter inside nested freeform must NOT mutate Bounds or add parent (656,62) offset
            if (StudioCanvas.SimulateFreeformInteractionForTest(sysBItem1, 0, 0, 0)
                || StudioCanvas.SimulateFreeformInteractionForTest(sysBItem1, 0, 1.5, -1.5))
                throw new InvalidOperationException("Click / sub-deadzone jitter inside freeform unexpectedly reported mutation.");
            if (sysBItem1.Bounds![0] != 64 || sysBItem1.Bounds[1] != 20)
                throw new InvalidOperationException($"Clicking nested freeform child corrupted Bounds to [{sysBItem1.Bounds[0]}, {sysBItem1.Bounds[1]}]!");

            // 2) Dragging (+30 stage px X, +21 stage px Y -> +30 ref X, +24 ref Y at scaleY=0.875) keeps parent-inner-relative coordinates
            if (!StudioCanvas.SimulateFreeformInteractionForTest(sysBItem1, 0, 30.0, 21.0))
                throw new InvalidOperationException("Expected freeform drag >= deadzone to mutate Bounds.");
            if (Math.Abs(sysBItem1.Bounds[0] - 94) > 1 || Math.Abs(sysBItem1.Bounds[1] - 44) > 1)
                throw new InvalidOperationException($"Dragging nested freeform child produced wrong Bounds [{sysBItem1.Bounds[0]}, {sysBItem1.Bounds[1]}] (expected ~[94, 44]).");

            // 3) Resizing (+40 stage px X, +17.5 stage px Y -> +40 ref W, +20 ref H)
            if (!StudioCanvas.SimulateFreeformInteractionForTest(sysBItem1, 1, 40.0, 17.5))
                throw new InvalidOperationException("Expected freeform resize to mutate Bounds.");
            if (Math.Abs(sysBItem1.Bounds[2] - 240) > 1 || Math.Abs(sysBItem1.Bounds[3] - 100) > 1)
                throw new InvalidOperationException($"Resizing nested freeform child produced wrong size [{sysBItem1.Bounds[2]}x{sysBItem1.Bounds[3]}].");

            // 4) Selecting & editing a child with null Bounds inside nested freeform uses local parent-inner coords (40,40), NEVER root (696,102)
            BindWholePack(sysBUnbounded);
            TitleBox.Text = "Unbounded Edited";
            CommitInspectorToSelectedNode();
            if (sysBUnbounded.Bounds == null || Math.Abs(sysBUnbounded.Bounds[0] - 40) > 2 || Math.Abs(sysBUnbounded.Bounds[1] - 40) > 2)
                throw new InvalidOperationException($"Inspector commit on nested freeform child with null Bounds corrupted X/Y to [{sysBUnbounded.Bounds?[0]}, {sysBUnbounded.Bounds?[1]}]!");

            // 5) Converting a nested [grid] panel at X=646 to [freeform] materializes local child Bounds (< 600), NEVER root (>= 646)
            var sysGridToFf = nestedFreeformHost.Children[1];
            BindWholePack(sysGridToFf);
            NodeTypeCombo.SelectedItem = "freeform";
            if (sysGridToFf.Children![0].Bounds is not { Length: >= 4 } bA || sysGridToFf.Children[1].Bounds is not { Length: >= 4 } bB
                || bA[0] > 5 || bB[0] < 280 || bB[0] > 320)
                throw new InvalidOperationException($"Converting nested grid->freeform materialized wrong child X bounds: A={sysGridToFf.Children[0].Bounds?[0]}, B={sysGridToFf.Children[1].Bounds?[0]}");
        }
        finally
        {
            if (Directory.Exists(storeTestRoot))
            {
                try { Directory.Delete(storeTestRoot, recursive: true); } catch { }
            }
        }

        // 7. Close workspace returns Studio to empty welcome state with Recently Opened populated
        CloseActiveWorkspace();
        if (HasActiveTheme || EmptyWorkspaceOverlay.Visibility != Visibility.Visible || LeftSidebarPanel.IsEnabled)
            throw new InvalidOperationException("CloseActiveWorkspace failed to return Studio to empty state.");
    }
}
