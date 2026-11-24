using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Shapes;
using System.Windows.Threading;
using Gen5WStudio.Models;
using Gen5WStudio.Services;

namespace Gen5WStudio.Controls;

public sealed class TelemetrySimState
{
    public double HvSoc { get; set; } = 78;
    public double Aux12vSoc { get; set; } = 92;
    public double OutsideTempF { get; set; } = 68;
    public double HvPackTempC { get; set; } = 22;
    public int HvHealth { get; set; } = 98;
    public bool Charging { get; set; } = false;
    public bool Ready { get; set; } = true;
    public bool InPark { get; set; } = true;
    public bool ParkingBrake { get; set; } = true;
    public int SpeedKmh { get; set; } = 0;
    public int OdometerKm { get; set; } = 18420;
    public double SteeringAngleDeg { get; set; } = 0;
    public double AccelPedal { get; set; } = 0;
    public double BrakePressure { get; set; } = 0;
    public int DriverSeatHeat { get; set; } = 0;
    public int PassengerSeatHeat { get; set; } = 0;
    public int RearLeftSeatHeat { get; set; } = 0;
    public int RearRightSeatHeat { get; set; } = 0;
    public int DriverSeatVent { get; set; } = 0;
    public int PassengerSeatVent { get; set; } = 0;
    public bool WheelHeat { get; set; } = false;
    public bool Defogger { get; set; } = false;
    public bool ConditioningActive { get; set; } = false;
    public string ConditioningState { get; set; } = "off";
    public bool DrivingRestricted { get; set; } = false;
    public int Battery12vSaver { get; set; } = 1;
    public double HvRangeKm { get; set; } = 395;
    public int ChargingRemainMin { get; set; } = 0;
    public bool ClimateOn { get; set; } = true;
    public bool ClimateAuto { get; set; } = true;
    public bool ClimateAc { get; set; } = true;
    public bool ClimateHeat { get; set; } = false;
    public bool ClimateRecirc { get; set; } = false;
    public bool ClimateSync { get; set; } = true;
    public bool ClimateDriverOnly { get; set; } = false;
    public bool ClimateFrontDefrost { get; set; } = false;
    public bool ClimateRearOn { get; set; } = false;
    public bool ClimateSmartVent { get; set; } = true;
    public bool ClimateAutoDefog { get; set; } = true;
    public bool ClimateAfterBlow { get; set; } = true;
    public double ClimateDriverTempF { get; set; } = 72;
    public double ClimatePassTempF { get; set; } = 72;
    public int ClimateFanSpeed { get; set; } = 3;
    public int ClimateVentMode { get; set; } = 1;
    public int AudioVolume { get; set; } = 16;
    public bool AudioMuted { get; set; } = false;
    public bool DoorsLocked { get; set; } = true;
    public bool AnyDoorOpen { get; set; } = false;
    public bool TrunkOpen { get; set; } = false;
    public bool HoodOpen { get; set; } = false;
    public bool SunroofOpen { get; set; } = false;
}

/// <summary>
/// Scaled 1920x720 WYSIWYG canvas matching KitKat LayoutEngine.java math 1:1.
/// When ShowSystemBar=true, renders the opaque 90px OEM StatusBar at y=0..90 and scales
/// the launcher window cleanly inside the remaining 1920x630 viewport (scaleY=0.875, scaleMin=0.875).
/// </summary>
public sealed class StudioCanvasControl : Border
{
    private readonly Grid stageHost = new() { Width = 1920, Height = 720, ClipToBounds = true, Background = Brushes.Black };
    private readonly Grid launcherWindowHost = new() { Width = 1920, Height = 720, ClipToBounds = true, VerticalAlignment = VerticalAlignment.Bottom };
    private readonly Image backdropImage = new() { Stretch = Stretch.UniformToFill };
    private readonly Canvas proceduralCanvas = new() { IsHitTestVisible = false };
    private readonly Border backdropDimOverlay = new() { IsHitTestVisible = false };
    private readonly Canvas layoutLayer = new();
    private readonly Border oemSystemBarMock = BuildOemSystemBarMock();
    private readonly Canvas selectionOverlay = new() { IsHitTestVisible = true };

    private ThemePackModel? pack;
    private PackWorkspaceService? workspace;
    private TelemetrySimState sim = new();
    private LayoutNodeModel? selectedNode;
    private readonly Dictionary<string, int> pagerActiveIndex = new();
    private readonly Dictionary<LayoutNodeModel, Rect> nodeRects = new();
    private readonly Dictionary<LayoutNodeModel, Rect> panelInnerRects = new();
    private readonly Dictionary<LayoutNodeModel, LayoutNodeModel?> parentMap = new();

    private double scaleX = 1.0;
    private double scaleY = 1.0;
    private double scaleMin = 1.0;
    private double windowTopOffset = 0.0;

    private readonly DispatcherTimer animTimer = new() { Interval = TimeSpan.FromMilliseconds(45) };
    private double animPhase = 0;
    private List<BitmapImage> backdropFrames = new();
    private int backdropFrameIdx = 0;
    private string loadedFramesDir = "";
    private double backdropFps = 16;
    private DateTime lastFrameSwap = DateTime.UtcNow;

    // Freeform drag/resize state (stores parent-inner-relative reference bounds so nested freeform panels never add parent offset)
    private const double DragStartDeadzonePx = 3.0;
    private LayoutNodeModel? dragNode;
    private LayoutNodeModel? dragParent;
    private int dragMode = 0; // 0=move, 1=BR resize
    private bool dragThresholdMet = false;
    private Point dragStartCanvas;
    private Rect dragStartLocalRefRect;
    private Size dragParentInnerRefSize;

    public event Action<LayoutNodeModel?>? NodeSelected;
    public event Action? PackMutated;

    public StudioCanvasControl()
    {
        Focusable = true;
        FocusVisualStyle = null;
        Background = new SolidColorBrush(Color.FromRgb(4, 7, 13));
        BorderBrush = new SolidColorBrush(Color.FromRgb(38, 59, 89));
        BorderThickness = new Thickness(1);
        CornerRadius = new CornerRadius(10);
        Padding = new Thickness(8);

        launcherWindowHost.Children.Add(backdropImage);
        launcherWindowHost.Children.Add(proceduralCanvas);
        launcherWindowHost.Children.Add(backdropDimOverlay);
        launcherWindowHost.Children.Add(layoutLayer);

        stageHost.Children.Add(launcherWindowHost);
        stageHost.Children.Add(oemSystemBarMock);
        stageHost.Children.Add(selectionOverlay);

        Child = new Viewbox
        {
            Stretch = Stretch.Uniform,
            Child = stageHost
        };

        animTimer.Tick += (_, _) => TickAnimation();
        Loaded += (_, _) => animTimer.Start();
        Unloaded += (_, _) => animTimer.Stop();

        layoutLayer.MouseLeftButtonDown += OnLayoutMouseDown;
        stageHost.MouseLeftButtonDown += (_, _) => { if (!IsKeyboardFocused) Focus(); };
        stageHost.MouseMove += OnStageMouseMove;
        stageHost.MouseLeftButtonUp += OnStageMouseUp;
    }

    private static Border BuildOemSystemBarMock()
    {
        var bar = new Border
        {
            Height = 90,
            Width = 1920,
            VerticalAlignment = VerticalAlignment.Top,
            Background = new SolidColorBrush(Color.FromRgb(5, 7, 11)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(40, 255, 255, 255)),
            BorderThickness = new Thickness(0, 0, 0, 1),
            IsHitTestVisible = false
        };
        var grid = new Grid { Margin = new Thickness(48, 0, 36, 0) };
        var leftIcons = new TextBlock
        {
            Text = "<          \u2302          \u2261",
            Foreground = new SolidColorBrush(Color.FromRgb(220, 228, 240)),
            FontSize = 30,
            FontWeight = FontWeights.Light,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Left
        };
        var clockInfo = new TextBlock
        {
            Text = "3:51 PM     Tue, Nov. 17",
            Foreground = Brushes.White,
            FontSize = 30,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(260, 0, 0, 0)
        };
        var statusIcons = new TextBlock
        {
            Text = "OEM BAR (90px)   \u2022   BT \u2582\u2584\u2586",
            Foreground = new SolidColorBrush(Color.FromRgb(148, 163, 184)),
            FontSize = 18,
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Right
        };
        grid.Children.Add(leftIcons);
        grid.Children.Add(clockInfo);
        grid.Children.Add(statusIcons);
        bar.Child = grid;
        return bar;
    }

    public void Bind(ThemePackModel? pack, PackWorkspaceService? workspace, TelemetrySimState sim, LayoutNodeModel? selected)
    {
        this.pack = pack;
        this.workspace = workspace;
        this.sim = sim;
        this.selectedNode = selected;
        UpdateActivePagerIndicesForNode(selected);
        RebuildScene();
    }

    public void SetSelected(LayoutNodeModel? node)
    {
        selectedNode = node;
        if (UpdateActivePagerIndicesForNode(node))
        {
            RebuildScene();
        }
        else
        {
            DrawSelectionAdorner();
        }
    }

    private bool UpdateActivePagerIndicesForNode(LayoutNodeModel? target)
    {
        if (pack?.Root == null || target == null) return false;
        var path = new List<LayoutNodeModel>();
        if (!FindNodePath(pack.Root, target, path)) return false;

        bool changed = false;
        for (int i = 0; i < path.Count - 1; i++)
        {
            var parent = path[i];
            if (parent.IsPanel
                && string.Equals(parent.Layout, "pager", StringComparison.OrdinalIgnoreCase)
                && parent.Children != null)
            {
                int pageIdx = parent.Children.IndexOf(path[i + 1]);
                if (pageIdx >= 0 && GetPagerPageIndex(parent.Id) != pageIdx)
                {
                    pagerActiveIndex[parent.Id] = pageIdx;
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static bool FindNodePath(LayoutNodeModel current, LayoutNodeModel target, List<LayoutNodeModel> path)
    {
        path.Add(current);
        if (ReferenceEquals(current, target)) return true;
        if (current.Children != null)
        {
            foreach (var child in current.Children)
            {
                if (FindNodePath(child, target, path)) return true;
            }
        }
        path.RemoveAt(path.Count - 1);
        return false;
    }

    public bool TryGetNodeRefRect(LayoutNodeModel? node, out Rect refRect)
    {
        if (node != null && nodeRects.TryGetValue(node, out Rect r))
        {
            double sx = scaleX > 0.01 ? scaleX : 1.0;
            double sy = scaleY > 0.01 ? scaleY : 1.0;
            refRect = new Rect(r.X / sx, r.Y / sy, r.Width / sx, r.Height / sy);
            return true;
        }
        refRect = default;
        return false;
    }

    public bool TryGetPanelInnerRefRect(LayoutNodeModel? panel, out Rect innerRefRect)
    {
        if (panel != null && panelInnerRects.TryGetValue(panel, out Rect inner))
        {
            double sx = scaleX > 0.01 ? scaleX : 1.0;
            double sy = scaleY > 0.01 ? scaleY : 1.0;
            innerRefRect = new Rect(inner.X / sx, inner.Y / sy, inner.Width / sx, inner.Height / sy);
            return true;
        }
        innerRefRect = default;
        return false;
    }

    /// <summary>
    /// Returns the node's reference-space rectangle relative to its parent panel's inner content origin
    /// (subtracting parent inner X/Y and the node's own left/top margin), matching freeform Bounds semantics.
    /// </summary>
    public bool TryGetNodeLocalRefRect(LayoutNodeModel? node, out Rect localRefRect)
    {
        if (node != null && nodeRects.TryGetValue(node, out Rect r))
        {
            double sx = scaleX > 0.01 ? scaleX : 1.0;
            double sy = scaleY > 0.01 ? scaleY : 1.0;
            double parentInnerX = 0;
            double parentInnerY = 0;
            if (parentMap.TryGetValue(node, out var par) && par != null && panelInnerRects.TryGetValue(par, out Rect pInner))
            {
                parentInnerX = pInner.X;
                parentInnerY = pInner.Y;
            }
            int[] m = NormalizeInsets(node.MarginPx);
            double localX = (r.X - parentInnerX - Sx(m[0])) / sx;
            double localY = (r.Y - parentInnerY - Sy(m[1])) / sy;
            localRefRect = new Rect(localX, localY, r.Width / sx, r.Height / sy);
            return true;
        }
        localRefRect = default;
        return false;
    }

    public int GetPagerPageIndex(string pagerId) => pagerActiveIndex.TryGetValue(pagerId, out int idx) ? idx : 0;

    public void SetPagerPageIndex(string pagerId, int idx)
    {
        pagerActiveIndex[pagerId] = Math.Max(0, idx);
        RebuildScene();
    }

    /// <summary>
    /// Captures a crisp Store thumbnail PNG from the full 1920x720 preview stage
    /// without editor selection adorners.
    /// </summary>
    public byte[] CaptureThumbnailPng(int targetWidth = 960, int targetHeight = 360)
    {
        int w = Math.Max(320, targetWidth);
        int h = Math.Max(120, targetHeight);
        var prevVis = selectionOverlay.Visibility;
        selectionOverlay.Visibility = Visibility.Collapsed;
        try
        {
            if (stageHost.ActualWidth < 1919 || stageHost.ActualHeight < 719)
            {
                stageHost.Measure(new Size(1920, 720));
                stageHost.Arrange(new Rect(0, 0, 1920, 720));
            }
            stageHost.UpdateLayout();

            var fullBmp = new RenderTargetBitmap(1920, 720, 96, 96, PixelFormats.Pbgra32);
            fullBmp.Render(stageHost);

            BitmapSource output = fullBmp;
            if (w != 1920 || h != 720)
            {
                var dv = new DrawingVisual();
                RenderOptions.SetBitmapScalingMode(dv, BitmapScalingMode.HighQuality);
                using (var dc = dv.RenderOpen())
                {
                    dc.DrawImage(fullBmp, new Rect(0, 0, w, h));
                }
                var scaled = new RenderTargetBitmap(w, h, 96, 96, PixelFormats.Pbgra32);
                scaled.Render(dv);
                output = scaled;
            }

            var encoder = new PngBitmapEncoder();
            encoder.Frames.Add(BitmapFrame.Create(output));
            using var ms = new MemoryStream();
            encoder.Save(ms);
            return ms.ToArray();
        }
        finally
        {
            selectionOverlay.Visibility = prevVis;
        }
    }

    public void RebuildScene()
    {
        layoutLayer.Children.Clear();
        nodeRects.Clear();
        panelInnerRects.Clear();
        parentMap.Clear();
        selectionOverlay.Children.Clear();
        if (pack == null) return;

        bool showBar = pack.Tokens.ShowSystemBar;
        double usableH = showBar ? 630.0 : 720.0;
        windowTopOffset = showBar ? 90.0 : 0.0;

        scaleX = 1920.0 / Math.Max(320, pack.ReferenceWidth);
        scaleY = usableH / Math.Max(240, pack.ReferenceHeight);
        scaleMin = Math.Min(scaleX, scaleY);

        oemSystemBarMock.Visibility = showBar ? Visibility.Visible : Visibility.Collapsed;
        launcherWindowHost.Height = usableH;
        layoutLayer.Width = 1920.0;
        layoutLayer.Height = usableH;
        proceduralCanvas.Width = 1920.0;
        proceduralCanvas.Height = usableH;

        ConfigureBackdropAssets();
        DrawProceduralBackdrop(usableH);

        RenderNode(pack.Root, null, new Rect(0, 0, 1920.0, usableH));
        DrawSelectionAdorner();
    }

    private double Sx(double v) => Math.Round(v * scaleX);
    private double Sy(double v) => Math.Round(v * scaleY);
    private double Smin(double v) => Math.Round(v * scaleMin);

    private void ConfigureBackdropAssets()
    {
        if (pack == null || workspace == null) return;
        var bg = pack.Background;

        backdropImage.Source = null;
        backdropDimOverlay.Visibility = Visibility.Collapsed;

        if (bg.Type == "bitmap" && !string.IsNullOrWhiteSpace(bg.BitmapAsset))
        {
            string p = workspace.ResolveAssetFullPath(bg.BitmapAsset);
            if (File.Exists(p) && LoadFrozenBitmap(p) is BitmapImage bgBmp)
            {
                backdropImage.Source = bgBmp;
                backdropDimOverlay.Background = new SolidColorBrush(ParseColor(bg.DimColor, Color.FromArgb(72, 6, 11, 18)));
                backdropDimOverlay.Visibility = Visibility.Visible;
            }
            backdropFrames.Clear();
            loadedFramesDir = "";
        }
        else if (bg.Type == "animated_frames" && !string.IsNullOrWhiteSpace(bg.FramesDir))
        {
            string dir = workspace.ResolveAssetFullPath(bg.FramesDir);
            if (!string.Equals(dir, loadedFramesDir, StringComparison.OrdinalIgnoreCase))
            {
                loadedFramesDir = dir;
                backdropFrames = LoadFramesSequence(dir, out double fps);
                backdropFps = bg.Fps ?? fps;
                backdropFrameIdx = 0;
            }
            if (backdropFrames.Count > 0)
            {
                backdropImage.Source = backdropFrames[backdropFrameIdx % backdropFrames.Count];
                backdropDimOverlay.Background = new SolidColorBrush(ParseColor(bg.DimColor, Color.FromArgb(72, 6, 11, 18)));
                backdropDimOverlay.Visibility = Visibility.Visible;
            }
        }
        else
        {
            backdropFrames.Clear();
            loadedFramesDir = "";
        }
    }

    private void TickAnimation()
    {
        if (pack == null) return;
        animPhase += 0.045 * (pack.Background.Speed ?? 1.0);
        if (animPhase > Math.PI * 2) animPhase -= Math.PI * 2;

        if (pack.Background.Type == "animated_frames" && backdropFrames.Count > 0)
        {
            double msPerFrame = 1000.0 / Math.Max(1, backdropFps);
            if ((DateTime.UtcNow - lastFrameSwap).TotalMilliseconds >= msPerFrame)
            {
                lastFrameSwap = DateTime.UtcNow;
                backdropFrameIdx = (backdropFrameIdx + 1) % backdropFrames.Count;
                backdropImage.Source = backdropFrames[backdropFrameIdx];
            }
        }
        else if (pack.Background.Type is "animated_aurora" or "animated_grid" or "animated_mesh" or "gradient" or "solid")
        {
            DrawProceduralBackdrop(launcherWindowHost.Height);
        }
    }

    private void DrawProceduralBackdrop(double vh)
    {
        proceduralCanvas.Children.Clear();
        if (pack == null || vh <= 0) return;
        var bg = pack.Background;
        if (bg.Type is "bitmap" or "animated_frames") return;

        Color pCol = ParseColor(bg.PrimaryColor, Color.FromRgb(6, 11, 18));
        Color sCol = ParseColor(bg.SecondaryColor, Color.FromRgb(13, 30, 52));
        Color aCol = ParseColor(bg.AccentColor, Color.FromRgb(25, 95, 145));
        Color a2Col = ParseColor(bg.SecondaryAccentColor, Color.FromRgb(14, 90, 104));

        var baseRect = new Rectangle { Width = 1920, Height = vh };
        baseRect.Fill = bg.Type == "solid"
            ? new SolidColorBrush(pCol)
            : new LinearGradientBrush(pCol, sCol, new Point(0, 0), new Point(1, 1));
        proceduralCanvas.Children.Add(baseRect);

        if (bg.Type is "animated_aurora" or "animated_mesh")
        {
            AddGlowOrb(1920 * (0.28 + 0.12 * Math.Sin(animPhase)), vh * (0.35 + 0.1 * Math.Cos(animPhase * 0.7)), 540, aCol, 115);
            AddGlowOrb(1920 * (0.75 + 0.1 * Math.Cos(animPhase + 1.2)), vh * (0.68 + 0.12 * Math.Sin(animPhase * 0.8)), 460, a2Col, 100);
            if (bg.Type == "animated_mesh")
            {
                AddGlowOrb(1920 * (0.5 + 0.2 * Math.Sin(animPhase * 1.3)), vh * (0.25 + 0.18 * Math.Cos(animPhase)), 380, aCol, 85);
            }
        }
        else if (bg.Type == "animated_grid")
        {
            var gridPen = new SolidColorBrush(Color.FromArgb(45, aCol.R, aCol.G, aCol.B));
            double offset = (animPhase / (Math.PI * 2)) * 80.0;
            for (double x = -80 + offset; x < 1920; x += 80)
            {
                proceduralCanvas.Children.Add(new Line { X1 = x, Y1 = 0, X2 = x, Y2 = vh, Stroke = gridPen, StrokeThickness = 1.5 });
            }
            for (double y = 0; y < vh; y += 80)
            {
                proceduralCanvas.Children.Add(new Line { X1 = 0, Y1 = y, X2 = 1920, Y2 = y, Stroke = gridPen, StrokeThickness = 1.5 });
            }
        }
    }

    private void AddGlowOrb(double cx, double cy, double r, Color c, byte alpha)
    {
        var rg = new RadialGradientBrush(Color.FromArgb(alpha, c.R, c.G, c.B), Color.FromArgb(0, c.R, c.G, c.B));
        var ell = new Ellipse { Width = r * 2, Height = r * 2, Fill = rg };
        Canvas.SetLeft(ell, cx - r);
        Canvas.SetTop(ell, cy - r);
        proceduralCanvas.Children.Add(ell);
    }

    private void RenderNode(LayoutNodeModel node, LayoutNodeModel? parent, Rect rect)
    {
        nodeRects[node] = rect;
        parentMap[node] = parent;

        if (node.IsPanel) RenderPanel(node, rect);
        else RenderWidget(node, rect);
    }

    private void RenderPanel(LayoutNodeModel panel, Rect rect)
    {
        if (panel.Style?.Transparent != true)
        {
            var card = BuildStyledBorder(panel, rect);
            card.Cursor = Cursors.Hand;
            ApplyOpacity(card, panel.Style);
            Canvas.SetLeft(card, rect.X);
            Canvas.SetTop(card, rect.Y);
            layoutLayer.Children.Add(card);
        }
        else if (parentMap.TryGetValue(panel, out var par) && par != null)
        {
            // Keep transparent sub-panels easy to select by clicking empty panel space or padding
            var hit = new Border
            {
                Width = rect.Width,
                Height = rect.Height,
                Background = Brushes.Transparent,
                Tag = panel,
                Cursor = Cursors.Hand
            };
            Canvas.SetLeft(hit, rect.X);
            Canvas.SetTop(hit, rect.Y);
            layoutLayer.Children.Add(hit);
        }

        int[] pad = NormalizeInsets(panel.PaddingPx ?? panel.Style?.PaddingPx);
        double pL = Sx(pad[0]), pT = Sy(pad[1]), pR = Sx(pad[2]), pB = Sy(pad[3]);
        var inner = new Rect(
            rect.X + pL,
            rect.Y + pT,
            Math.Max(1, rect.Width - pL - pR),
            Math.Max(1, rect.Height - pT - pB));
        panelInnerRects[panel] = inner;

        var children = panel.Children ?? new List<LayoutNodeModel>();
        if (children.Count == 0) return;

        string layout = panel.Layout ?? "flex_col";
        double gap = Sx(panel.GapPx ?? 16);

        if (layout == "pager")
        {
            var ind = ResolveEffectiveIndicator(panel);
            bool showBar = (panel.ShowDots ?? true) && !IsIndicatorDisabled(ind.Mode) && children.Count > 1;
            double dotsH = showBar ? EstimateIndicatorStripHeight(ind) : 0;
            var pageArea = new Rect(inner.X, inner.Y, inner.Width, Math.Max(1, inner.Height - dotsH));
            int activeIdx = Math.Clamp(GetPagerPageIndex(panel.Id), 0, children.Count - 1);
            RenderNode(children[activeIdx], panel, pageArea);

            if (showBar)
            {
                var indTarget = panel.GetOrCreateVirtualIndicatorNode() ?? panel;
                var stripRect = new Rect(inner.X, inner.Bottom - dotsH, inner.Width, dotsH);
                nodeRects[indTarget] = stripRect;
                parentMap[indTarget] = panel;

                if (BuildIndicatorStrip(panel, panel, ind, out HorizontalAlignment stripAlign, selectTarget: indTarget) is StackPanel sp)
                {
                    sp.HorizontalAlignment = stripAlign;
                    sp.VerticalAlignment = VerticalAlignment.Center;
                    var wrapper = new Grid
                    {
                        Width = inner.Width,
                        Height = dotsH,
                        Background = new SolidColorBrush(Color.FromArgb(1, 0, 0, 0)),
                        Tag = indTarget,
                        Cursor = Cursors.Hand,
                        Children = { sp }
                    };
                    Canvas.SetLeft(wrapper, inner.X);
                    Canvas.SetTop(wrapper, inner.Bottom - dotsH);
                    layoutLayer.Children.Add(wrapper);
                }
            }
            return;
        }

        if (layout == "freeform")
        {
            foreach (var c in children)
            {
                int[] m = NormalizeInsets(c.MarginPx);
                double x = (c.Bounds is { Length: >= 4 } ? Sx(c.Bounds[0]) : Sx(40)) + Sx(m[0]);
                double y = (c.Bounds is { Length: >= 4 } ? Sy(c.Bounds[1]) : Sy(40)) + Sy(m[1]);
                double w = c.Bounds is { Length: >= 4 } ? Math.Max(20, Sx(c.Bounds[2])) : EstimateFlexWidth(c, 300);
                double h = c.Bounds is { Length: >= 4 } ? Math.Max(20, Sy(c.Bounds[3])) : EstimateFlexHeight(c, 160);
                RenderNode(c, panel, new Rect(inner.X + x, inner.Y + y, w, h));
            }
            return;
        }

        if (layout == "grid")
        {
            int cols = Math.Max(1, panel.Columns ?? 3);
            int rows = Math.Max(1, panel.Rows ?? 2);
            double cellW = (inner.Width - gap * (cols - 1)) / cols;
            double cellH = (inner.Height - gap * (rows - 1)) / rows;
            int maxTrackRows = Math.Max(rows + 8, children.Count * 2 + 4);
            bool[,] occupied = new bool[maxTrackRows, cols];
            int cursorRow = 0, cursorCol = 0;
            foreach (var c in children)
            {
                int[] m = NormalizeInsets(c.MarginPx);
                int cs = Math.Min(cols, Math.Max(1, c.ColSpan ?? 1));
                int rs = Math.Max(1, c.RowSpan ?? 1);
                while (cursorRow + rs < maxTrackRows)
                {
                    if (cursorCol + cs > cols)
                    {
                        cursorRow++;
                        cursorCol = 0;
                        continue;
                    }
                    bool fits = true;
                    for (int rr = 0; rr < rs && fits; rr++)
                    {
                        for (int cc = 0; cc < cs; cc++)
                        {
                            if (occupied[cursorRow + rr, cursorCol + cc]) { fits = false; break; }
                        }
                    }
                    if (fits) break;
                    cursorCol++;
                }
                int row = cursorRow;
                int col = cursorCol;
                for (int rr = 0; rr < rs && row + rr < maxTrackRows; rr++)
                {
                    for (int cc = 0; cc < cs && col + cc < cols; cc++)
                        occupied[row + rr, col + cc] = true;
                }
                double boxX = inner.X + col * (cellW + gap) + Sx(m[0]);
                double boxY = inner.Y + row * (cellH + gap) + Sy(m[1]);
                double availW = Math.Max(1, cellW * cs + gap * (cs - 1) - Sx(m[0] + m[2]));
                double availH = Math.Max(1, cellH * rs + gap * (rs - 1) - Sy(m[1] + m[3]));
                double cw = c.Width is > 0 ? Math.Max(1, Sx(c.Width.Value)) : availW;
                double ch = c.Height is > 0 ? Math.Max(1, Sy(c.Height.Value)) : availH;
                double cx = (c.HorizontalAlign ?? "").ToLowerInvariant() switch
                {
                    "center" => boxX + (availW - cw) * 0.5,
                    "right" => boxX + (availW - cw),
                    _ => boxX
                };
                double cy = (c.VerticalAlign ?? "").ToLowerInvariant() switch
                {
                    "center" => boxY + (availH - ch) * 0.5,
                    "bottom" => boxY + (availH - ch),
                    _ => boxY
                };
                RenderNode(c, panel, new Rect(cx, cy, cw, ch));
                cursorCol = col + cs;
                if (cursorCol >= cols) { cursorCol = 0; cursorRow++; }
            }
            return;
        }

        bool isRow = layout == "flex_row";
        double totalSpace = Math.Max(1, (isRow ? inner.Width : inner.Height) - gap * Math.Max(0, children.Count - 1));
        double fixedUsed = 0, totalWeight = 0;
        foreach (var c in children)
        {
            int[] m = NormalizeInsets(c.MarginPx);
            double mAxis = isRow ? Sx(m[0] + m[2]) : Sy(m[1] + m[3]);
            double fw = c.FlexWeight ?? 0;
            if (fw > 0)
            {
                totalWeight += fw;
                fixedUsed += mAxis;
            }
            else
            {
                fixedUsed += (isRow ? EstimateFlexWidth(c, 160) : EstimateFlexHeight(c, 64)) + mAxis;
            }
        }
        double remSpace = Math.Max(0, totalSpace - fixedUsed);
        double cursor = isRow ? inner.X : inner.Y;
        foreach (var c in children)
        {
            int[] m = NormalizeInsets(c.MarginPx);
            double mL = Sx(m[0]), mT = Sy(m[1]), mR = Sx(m[2]), mB = Sy(m[3]);
            double fw = c.FlexWeight ?? 0;
            double mainSize = fw > 0 && totalWeight > 0
                ? remSpace * (fw / totalWeight)
                : (isRow ? EstimateFlexWidth(c, 160) : EstimateFlexHeight(c, 64));

            Rect childRect;
            if (isRow)
            {
                double h = c.Height is > 0 ? Math.Min(inner.Height - mT - mB, Sy(c.Height.Value))
                         : (c.Kind == "progress_bar" ? Sy(22) : Math.Max(1, inner.Height - mT - mB));
                double yOffset = (c.VerticalAlign ?? "center").ToLowerInvariant() switch
                {
                    "top" => inner.Y + mT,
                    "bottom" => inner.Bottom - mB - h,
                    _ => inner.Y + mT + Math.Max(0, (inner.Height - mT - mB - h) * 0.5)
                };
                childRect = new Rect(cursor + mL, yOffset, Math.Max(1, mainSize), Math.Max(1, h));
                cursor += mainSize + mL + mR + gap;
            }
            else
            {
                double w = c.Width is > 0 ? Math.Min(inner.Width - mL - mR, Sx(c.Width.Value)) : Math.Max(1, inner.Width - mL - mR);
                double xOffset = (c.HorizontalAlign ?? "left").ToLowerInvariant() switch
                {
                    "center" => inner.X + mL + Math.Max(0, (inner.Width - mL - mR - w) * 0.5),
                    "right" => inner.Right - mR - w,
                    _ => inner.X + mL
                };
                childRect = new Rect(xOffset, cursor + mT, Math.Max(1, w), Math.Max(1, mainSize));
                cursor += mainSize + mT + mB + gap;
            }
            RenderNode(c, panel, childRect);
        }
    }

    private double EstimateFlexWidth(LayoutNodeModel node, double fallback)
    {
        if (node.Width is > 0) return Sx(node.Width.Value);
        if (node.IsPanel)
        {
            if ((node.Layout ?? "") == "flex_row" && node.Children is { Count: > 0 })
            {
                double sum = 0;
                double gap = Sx(node.GapPx ?? 16);
                for (int i = 0; i < node.Children.Count; i++)
                {
                    sum += EstimateFlexWidth(node.Children[i], 120);
                    if (i > 0) sum += gap;
                }
                int[] p = NormalizeInsets(node.PaddingPx ?? node.Style?.PaddingPx);
                return sum + Sx(p[0] + p[2]);
            }
            if ((node.Layout ?? "") == "flex_col" && node.Children is { Count: > 0 })
            {
                double maxChild = 0;
                foreach (var ch in node.Children)
                {
                    double chW = EstimateFlexWidth(ch, 160);
                    if (chW > maxChild) maxChild = chW;
                }
                int[] p = NormalizeInsets(node.PaddingPx ?? node.Style?.PaddingPx);
                return maxChild + Sx(p[0] + p[2]);
            }
            return Sx(fallback);
        }
        if (node.Kind == "progress_bar") return Sx(220);
        if (node.Kind is "icon" or "image") return Smin(node.Style?.IconSizePx ?? 64) + Sx(16);
        return Sx(fallback);
    }

    private double EstimateFlexHeight(LayoutNodeModel node, double fallback)
    {
        if (node.Height is > 0) return Sy(node.Height.Value);
        if (node.IsPanel)
        {
            if ((node.Layout ?? "") == "flex_row" && node.Children is { Count: > 0 })
            {
                double maxChild = 0;
                foreach (var ch in node.Children)
                {
                    double chH = EstimateFlexHeight(ch, 40);
                    if (chH > maxChild) maxChild = chH;
                }
                int[] p = NormalizeInsets(node.PaddingPx ?? node.Style?.PaddingPx);
                return maxChild + Sy(p[1] + p[3]);
            }
            if ((node.Layout ?? "") == "flex_col" && node.Children is { Count: > 0 })
            {
                double sum = 0;
                double gap = Sy(node.GapPx ?? 16);
                for (int i = 0; i < node.Children.Count; i++)
                {
                    sum += EstimateFlexHeight(node.Children[i], 36);
                    if (i > 0) sum += gap;
                }
                int[] p = NormalizeInsets(node.PaddingPx ?? node.Style?.PaddingPx);
                return sum + Sy(p[1] + p[3]);
            }
            return Sy(fallback);
        }

        string kind = node.Kind ?? "tile";
        if (kind == "progress_bar") return Sy(22);
        if (kind == "divider") return Math.Max(2, Sy(2));
        if (kind is "icon" or "image") return Smin(node.Style?.IconSizePx ?? 64) + Sy(8);
        if (kind is "text" or "sensor" or "label")
        {
            double tPx = node.Style?.TitleSizePx ?? 22;
            double sPx = !string.IsNullOrWhiteSpace(node.Subtitle) ? (node.Style?.SubtitleSizePx ?? 15) * 1.35 : 0;
            int padV = node.Style?.PaddingPx is { Length: >= 4 } ? node.Style.PaddingPx[1] + node.Style.PaddingPx[3]
                     : (node.Style?.Transparent == true ? 4 : 12);
            return Smin(tPx * 1.28 + sPx) + Sy(padV);
        }
        if (kind is "soc_ring" or "ring_gauge" or "telemetry_gauge")
        {
            double sz = Smin(node.Style?.IconSizePx ?? 88);
            double extra = !string.IsNullOrWhiteSpace(node.Title) ? Sy(36) : 0;
            return sz + extra + Sy(24);
        }
        return Sy(fallback);
    }

    private void RenderWidget(LayoutNodeModel w, Rect rect)
    {
        string kind = w.Kind ?? "tile";
        if (kind == "spacer") return;

        var host = new Border
        {
            Width = rect.Width,
            Height = rect.Height,
            Tag = w,
            ClipToBounds = true,
            Cursor = Cursors.Hand
        };
        ApplyOpacity(host, w.Style);
        Canvas.SetLeft(host, rect.X);
        Canvas.SetTop(host, rect.Y);

        if (kind == "divider")
        {
            host.Background = new SolidColorBrush(ParseColor(w.Style?.BorderColor, Color.FromArgb(60, 100, 116, 139)));
            layoutLayer.Children.Add(host);
            return;
        }

        if (kind != "progress_bar" && w.Style?.Transparent != true)
        {
            var bgBorder = BuildStyledBorder(w, rect);
            host.Background = bgBorder.Background;
            host.BorderBrush = bgBorder.BorderBrush;
            host.BorderThickness = bgBorder.BorderThickness;
            host.CornerRadius = bgBorder.CornerRadius;
        }

        host.Child = BuildWidgetContent(w, rect);
        layoutLayer.Children.Add(host);
    }

    private UIElement BuildWidgetContent(LayoutNodeModel w, Rect rect)
    {
        string kind = w.Kind ?? "tile";
        Color tokenAccent = ParseColor(pack?.Tokens?.AccentColor, Color.FromRgb(56, 189, 248));
        Color accent = ParseColor(w.Style?.AccentColor, tokenAccent);
        Color txtCol = ParseColor(w.Style?.TextColor, Colors.White);
        Color subCol = ParseColor(w.Style?.SubTextColor, Color.FromRgb(148, 163, 184));
        FontFamily fontFam = workspace?.ResolveFontFamily(w.Style?.FontFamily) ?? new FontFamily("Segoe UI");
        FontStyle fontStyle = w.Style?.FontItalic == true ? FontStyles.Italic : FontStyles.Normal;
        FontWeight titleWeight = ResolveFontWeight(w.Style?.FontWeight, defaultBold: true);
        FontWeight subWeight = FontWeights.Normal;

        if (kind is "text" or "sensor" or "label")
        {
            bool isAtomic = kind is "text" or "sensor";
            var hAlign = ResolveTextAlignment(w.Style?.TextAlign, HorizontalAlignment.Left);
            var vAlign = ResolveVerticalAlignment(w.Style?.VerticalAlign, VerticalAlignment.Center);
            int[] pad = w.Style?.PaddingPx is { Length: >= 4 } ? w.Style.PaddingPx
                      : (w.Style?.Transparent == true ? new[] { 2, 2, 2, 2 } : new[] { 12, 6, 12, 6 });

            var sp = new StackPanel
            {
                HorizontalAlignment = hAlign,
                VerticalAlignment = vAlign,
                Margin = new Thickness(Sx(pad[0]), Sy(pad[1]), Sx(pad[2]), Sy(pad[3]))
            };

            string renderedTitle = (!string.IsNullOrWhiteSpace(w.TelemetryKey) || ContainsToken(w.Title))
                ? FormatDynamicText(w.Title, w.TelemetryKey, w.Unit, isAtomic)
                : (w.Title ?? "");

            sp.Children.Add(new TextBlock
            {
                Text = renderedTitle,
                Foreground = new SolidColorBrush(txtCol),
                FontFamily = fontFam,
                FontStyle = fontStyle,
                FontWeight = titleWeight,
                FontSize = Smin(w.Style?.TitleSizePx ?? 22),
                TextWrapping = w.WrapText == true ? TextWrapping.Wrap : TextWrapping.NoWrap,
                TextTrimming = w.WrapText == true ? TextTrimming.None : TextTrimming.CharacterEllipsis,
                TextAlignment = ToTextAlignment(w.Style?.TextAlign)
            });

            if (!string.IsNullOrWhiteSpace(w.Subtitle))
            {
                string renderedSub = ContainsToken(w.Subtitle)
                    ? FormatDynamicText(w.Subtitle, w.TelemetryKey, w.Unit, false)
                    : w.Subtitle!;
                sp.Children.Add(new TextBlock
                {
                    Text = renderedSub,
                    Foreground = new SolidColorBrush(subCol),
                    FontFamily = fontFam,
                    FontStyle = fontStyle,
                    FontWeight = subWeight,
                    FontSize = Smin(w.Style?.SubtitleSizePx ?? 15),
                    TextWrapping = w.WrapText == true ? TextWrapping.Wrap : TextWrapping.NoWrap,
                    TextAlignment = ToTextAlignment(w.Style?.TextAlign)
                });
            }
            return sp;
        }

        if (kind == "progress_bar")
        {
            bool vertical = string.Equals(w.Orientation, "vertical", StringComparison.OrdinalIgnoreCase);
            string k = w.TelemetryKey ?? "hv.soc";
            string u = w.Unit ?? "";
            double rawVal = ParseSimNumeric(k, u, out bool valid);
            double minV = w.MinValue ?? DefaultSensorMin(k, u);
            double maxV = w.MaxValue ?? DefaultSensorMax(k, u);
            double frac = (!valid || maxV <= minV) ? 0.0 : Math.Clamp((rawVal - minV) / (maxV - minV), 0.0, 1.0);

            Color trackColor = w.Style?.Transparent == true ? Color.FromArgb(85, 41, 59, 82)
                             : ParseColor(w.Style?.BgColor, Color.FromArgb(85, 41, 59, 82));
            Color borderColor = ParseColor(w.Style?.BorderColor, Colors.Transparent);
            double borderW = w.Style?.BorderWidthPx is > 0 ? Math.Max(1, Smin(w.Style.BorderWidthPx.Value)) : 0;
            double cornerR = w.Style?.CornerRadiusPx is >= 0 ? Math.Min(Smin(w.Style.CornerRadiusPx.Value), Math.Min(rect.Width, rect.Height) * 0.5)
                                                             : Math.Min(rect.Width, rect.Height) * 0.5;

            Brush fillBrush = !string.IsNullOrWhiteSpace(w.Style?.BgEndColor)
                ? new LinearGradientBrush(accent, ParseColor(w.Style!.BgEndColor, accent),
                    vertical ? new Point(0, 1) : new Point(0, 0),
                    vertical ? new Point(0, 0) : new Point(1, 0))
                : new SolidColorBrush(accent);

            var grid = new Grid { Width = rect.Width, Height = rect.Height, ClipToBounds = true };
            grid.Children.Add(new Border
            {
                Background = new SolidColorBrush(trackColor),
                CornerRadius = new CornerRadius(cornerR),
                BorderBrush = borderW > 0 ? new SolidColorBrush(borderColor) : null,
                BorderThickness = new Thickness(borderW)
            });

            if (frac > 0.001)
            {
                var fillRect = vertical
                    ? new Border
                    {
                        VerticalAlignment = VerticalAlignment.Bottom,
                        Height = Math.Max(rect.Width * 0.4, rect.Height * frac),
                        CornerRadius = new CornerRadius(cornerR),
                        Background = fillBrush
                    }
                    : new Border
                    {
                        HorizontalAlignment = HorizontalAlignment.Left,
                        Width = Math.Max(rect.Height * 0.4, rect.Width * frac),
                        CornerRadius = new CornerRadius(cornerR),
                        Background = fillBrush
                    };
                grid.Children.Add(fillRect);
            }

            if (w.ShowText == true)
            {
                string txt = !string.IsNullOrWhiteSpace(w.Title)
                    ? FormatDynamicText(w.Title, k, u, true)
                    : ResolveSimValue(k, u);
                grid.Children.Add(new TextBlock
                {
                    Text = txt,
                    Foreground = new SolidColorBrush(txtCol),
                    FontFamily = fontFam,
                    FontStyle = fontStyle,
                    FontWeight = titleWeight,
                    FontSize = Smin(w.Style?.TitleSizePx ?? 14),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                });
            }
            return grid;
        }

        if (kind is "icon" or "image")
        {
            bool cover = string.Equals(w.ScaleType, "cover", StringComparison.OrdinalIgnoreCase);
            bool explicitStretch = (w.Width is > 0 && w.Height is > 0) || (w.FlexWeight ?? 0) > 0 || w.Bounds != null;
            double imgSize = Smin(w.Style?.IconSizePx ?? 64);
            string? assetRef = !string.IsNullOrWhiteSpace(w.AnimIcon) ? w.AnimIcon
                             : (!string.IsNullOrWhiteSpace(w.BitmapAsset) ? w.BitmapAsset
                             : (!string.IsNullOrWhiteSpace(w.Icon) ? w.Icon : w.Style?.BgBitmapAsset));

            UIElement? media = LoadAssetVisual(assetRef, w.Style?.IconTint, explicitStretch ? double.NaN : imgSize, cover);
            if (media == null)
            {
                return new TextBlock
                {
                    Text = "[Image]",
                    Foreground = new SolidColorBrush(subCol),
                    FontSize = Smin(14),
                    HorizontalAlignment = HorizontalAlignment.Center,
                    VerticalAlignment = VerticalAlignment.Center
                };
            }
            if (explicitStretch) return media;
            if (media is FrameworkElement feImg)
            {
                feImg.HorizontalAlignment = ResolveTextAlignment(w.HorizontalAlign ?? w.Style?.TextAlign, HorizontalAlignment.Center);
                feImg.VerticalAlignment = ResolveVerticalAlignment(w.VerticalAlign ?? w.Style?.VerticalAlign, VerticalAlignment.Center);
            }
            return new Grid { Children = { media } };
        }

        if (kind == "clock")
        {
            var sp = new StackPanel
            {
                VerticalAlignment = ResolveVerticalAlignment(w.Style?.VerticalAlign, VerticalAlignment.Center),
                HorizontalAlignment = ResolveTextAlignment(w.Style?.TextAlign, HorizontalAlignment.Left),
                Margin = new Thickness(Sx(6), 0, Sx(6), 0)
            };
            sp.Children.Add(new TextBlock { Text = DateTime.Now.ToString("h:mm tt", CultureInfo.InvariantCulture), Foreground = new SolidColorBrush(txtCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.TitleSizePx ?? 30), FontWeight = titleWeight });
            sp.Children.Add(new TextBlock { Text = DateTime.Now.ToString("ddd, MMM d", CultureInfo.InvariantCulture).ToUpperInvariant(), Foreground = new SolidColorBrush(accent), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.SubtitleSizePx ?? 15) });
            return sp;
        }
        if (kind == "telemetry_badge")
        {
            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            sp.Children.Add(new TextBlock { Text = w.Title ?? w.TelemetryKey ?? "SENSOR", Foreground = new SolidColorBrush(subCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.SubtitleSizePx ?? 13), HorizontalAlignment = HorizontalAlignment.Center });
            sp.Children.Add(new TextBlock { Text = ResolveSimValue(w.TelemetryKey, w.Unit), Foreground = new SolidColorBrush(txtCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.TitleSizePx ?? 22), FontWeight = titleWeight, HorizontalAlignment = HorizontalAlignment.Center });
            return sp;
        }
        if (kind == "switcher_status")
        {
            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            sp.Children.Add(new TextBlock { Text = "DEFAULT HOME", Foreground = Brushes.White, FontFamily = fontFam, FontSize = Smin(w.Style?.TitleSizePx ?? 18), FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center });
            sp.Children.Add(new TextBlock { Text = "5-Layer Failsafe Armed \u2022 v0.3.0", Foreground = new SolidColorBrush(subCol), FontFamily = fontFam, FontSize = Smin(w.Style?.SubtitleSizePx ?? 13), HorizontalAlignment = HorizontalAlignment.Center });
            return sp;
        }
        if (kind == "page_indicator")
        {
            var pager = FindFirstPager(pack?.Root);
            var ind = ResolveEffectiveIndicator(w);
            if (pager?.Children == null || IsIndicatorDisabled(ind.Mode))
                return new Grid();
            var sp = BuildIndicatorStrip(w, pager, ind, out HorizontalAlignment hAlign, selectTarget: w);
            if (sp == null) return new Grid();
            sp.HorizontalAlignment = hAlign;
            sp.VerticalAlignment = ResolveVerticalAlignment(w.Style?.VerticalAlign, VerticalAlignment.Center);
            return new Grid { Children = { sp } };
        }
        if (kind is "soc_ring" or "ring_gauge" or "telemetry_gauge")
        {
            string k = string.IsNullOrWhiteSpace(w.TelemetryKey) ? "hv.soc" : w.TelemetryKey!;
            string u = w.Unit ?? "";
            double rawVal = ParseSimNumeric(k, u, out bool valid);
            double minV = w.MinValue ?? DefaultSensorMin(k, u);
            double maxV = w.MaxValue ?? DefaultSensorMax(k, u);
            double pct = (!valid || maxV <= minV) ? 0.0 : Math.Clamp((rawVal - minV) * 100.0 / (maxV - minV), 0.0, 100.0);
            bool isHv = k is "hv.soc" or "hv_soc";
            Color ringCol = (isHv && sim.Charging) ? Color.FromRgb(16, 185, 129) : accent;
            Color defaultTrackCol = Color.FromArgb(68, 51, 65, 85);
            Color? parsedBorder = ParseNullableColor(w.Style?.BorderColor);
            Color trackCol = w.Style?.Transparent == true
                ? defaultTrackCol
                : (parsedBorder.HasValue && (parsedBorder.Value.A != 0 || parsedBorder.Value.R != 0 || parsedBorder.Value.G != 0 || parsedBorder.Value.B != 0)
                    ? parsedBorder.Value
                    : defaultTrackCol);
            double sz = Smin(w.Style?.IconSizePx ?? 88);
            string centerTxt = ResolveSimValue(k, u);

            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Center };
            sp.Children.Add(BuildRingVisual(sz, pct, centerTxt, ringCol, txtCol, trackCol, fontFam));
            if (!string.IsNullOrWhiteSpace(w.Title))
                sp.Children.Add(new TextBlock { Text = w.Title, Foreground = new SolidColorBrush(txtCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.TitleSizePx ?? 17), FontWeight = titleWeight, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, Sy(8), 0, 0) });
            if (!string.IsNullOrWhiteSpace(w.Subtitle))
                sp.Children.Add(new TextBlock { Text = w.Subtitle, Foreground = new SolidColorBrush(subCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.SubtitleSizePx ?? 13), HorizontalAlignment = HorizontalAlignment.Center });
            return sp;
        }
        if (kind == "ev_hero")
        {
            var sp = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Sx(28), Sy(24), Sx(28), Sy(24)) };
            var hdr = new StackPanel { Orientation = Orientation.Horizontal };
            if (LoadIconElement(w, Smin(w.Style?.IconSizePx ?? 44)) is UIElement ic)
            {
                if (ic is FrameworkElement fe) fe.Margin = new Thickness(0, 0, Sx(14), 0);
                hdr.Children.Add(ic);
            }
            var tCol = new StackPanel();
            tCol.Children.Add(new TextBlock { Text = w.Title ?? "IONIQ 5 TELEMETRY", Foreground = new SolidColorBrush(txtCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.TitleSizePx ?? 22), FontWeight = titleWeight });
            tCol.Children.Add(new TextBlock { Text = sim.Charging ? "CHARGING ACTIVE" : (sim.Ready ? "EV READY" : "STANDBY"), Foreground = new SolidColorBrush(accent), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.SubtitleSizePx ?? 15) });
            hdr.Children.Add(tCol);
            sp.Children.Add(hdr);
            sp.Children.Add(new TextBlock { Text = $"{sim.HvSoc:0}%", Foreground = Brushes.White, FontFamily = fontFam, FontSize = Smin(86), FontWeight = FontWeights.Bold, Margin = new Thickness(0, Sy(16), 0, Sy(10)) });
            var barTrack = new Border { Height = Sy(22), CornerRadius = new CornerRadius(Sy(11)), Background = new SolidColorBrush(Color.FromArgb(90, 41, 59, 82)), Margin = new Thickness(0, 0, 0, Sy(18)), Child = new Border { HorizontalAlignment = HorizontalAlignment.Left, Width = Math.Max(16, (rect.Width - Sx(60)) * Math.Clamp(sim.HvSoc, 0, 100) / 100.0), CornerRadius = new CornerRadius(Sy(11)), Background = new SolidColorBrush(sim.Charging ? Color.FromRgb(16, 185, 129) : accent) } };
            sp.Children.Add(barTrack);
            string extHeroStr = PreferCelsius
                ? $"{(sim.OutsideTempF - 32.0) * 5.0 / 9.0:0}\u00B0C"
                : $"{sim.OutsideTempF:0}\u00B0F";
            string odoHeroStr = PreferMph
                ? $"{Math.Round(sim.OdometerKm * 0.621371):0} mi"
                : $"{sim.OdometerKm} km";
            sp.Children.Add(new TextBlock { Text = $"12V AUX {sim.Aux12vSoc:0}%   \u2022   EXT {extHeroStr}   \u2022   ODO {odoHeroStr}", Foreground = new SolidColorBrush(subCol), FontSize = Smin(17) });
            if (w.Action != null && !string.IsNullOrWhiteSpace(w.Action.Type) && !"none".Equals(w.Action.Type, StringComparison.OrdinalIgnoreCase))
            {
                sp.Children.Add(new TextBlock
                {
                    Text = "Tap anywhere on card to open EV & Energy Screen \u2192",
                    Foreground = new SolidColorBrush(Color.FromArgb(180, accent.R, accent.G, accent.B)),
                    FontSize = Smin(13),
                    Margin = new Thickness(0, Sy(8), 0, 0)
                });
            }
            return sp;
        }
        if (kind == "action_pill")
        {
            var sp = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(Sx(14), Sy(6), Sx(14), Sy(6)) };
            if (LoadIconElement(w, Smin(w.Style?.IconSizePx ?? 32)) is UIElement ic)
            {
                if (ic is FrameworkElement fe) fe.Margin = new Thickness(0, 0, Sx(10), 0);
                sp.Children.Add(ic);
            }
            var tCol = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            bool hasTitleBind = ContainsToken(w.Title) || (string.IsNullOrWhiteSpace(w.Title) && !string.IsNullOrWhiteSpace(w.TelemetryKey));
            string pillTitle = hasTitleBind
                ? FormatDynamicText(w.Title, w.TelemetryKey, w.Unit, true)
                : (w.Title ?? "");
            tCol.Children.Add(new TextBlock { Text = pillTitle, Foreground = new SolidColorBrush(txtCol), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.TitleSizePx ?? 19), FontWeight = titleWeight });

            string? subTxt = w.Action?.Type == "toggle_bar"
                ? (pack?.Tokens.ShowSystemBar == true ? "Bar Shown (630p)" : "Full 1920x720")
                : w.Subtitle;
            bool autoSubSensor = !string.IsNullOrWhiteSpace(w.TelemetryKey) && string.IsNullOrWhiteSpace(subTxt) && !hasTitleBind;
            if (ContainsToken(subTxt) || autoSubSensor)
                subTxt = FormatDynamicText(subTxt, w.TelemetryKey, w.Unit, autoSubSensor);
            if (!string.IsNullOrWhiteSpace(subTxt))
                tCol.Children.Add(new TextBlock { Text = subTxt, Foreground = new SolidColorBrush(accent), FontFamily = fontFam, FontStyle = fontStyle, FontSize = Smin(w.Style?.SubtitleSizePx ?? 13) });
            sp.Children.Add(tCol);
            return sp;
        }

        // Default Tile
        var tileGrid = new Grid();
        if (!string.IsNullOrWhiteSpace(w.Style?.BgAnimAsset) || !string.IsNullOrWhiteSpace(w.Style?.BgBitmapAsset))
        {
            string? imgPath = !string.IsNullOrWhiteSpace(w.Style.BgBitmapAsset)
                ? workspace?.ResolveAssetFullPath(w.Style.BgBitmapAsset)
                : FindFirstFrameInDir(workspace?.ResolveAssetFullPath(w.Style.BgAnimAsset));
            if (!string.IsNullOrEmpty(imgPath) && File.Exists(imgPath) && LoadFrozenBitmap(imgPath) is BitmapImage tileBmp)
            {
                tileGrid.Children.Add(new Image { Source = tileBmp, Stretch = Stretch.UniformToFill });
                byte dimA = (byte)Math.Clamp(w.Style?.BgDimAlpha ?? 95, 0, 230);
                tileGrid.Children.Add(new Border { Background = new SolidColorBrush(Color.FromArgb(dimA, 6, 11, 18)) });
            }
        }
        var tileHAlign = ResolveTextAlignment(w.Style?.TextAlign, HorizontalAlignment.Left);
        var tileTxtAlign = ToTextAlignment(w.Style?.TextAlign);
        int[] tilePad = w.Style?.PaddingPx is { Length: >= 4 } ? w.Style.PaddingPx : new[] { 24, 18, 24, 18 };
        var innerSp = new StackPanel
        {
            VerticalAlignment = ResolveVerticalAlignment(w.Style?.VerticalAlign, VerticalAlignment.Center),
            HorizontalAlignment = tileHAlign,
            Margin = new Thickness(Sx(tilePad[0]), Sy(tilePad[1]), Sx(tilePad[2]), Sy(tilePad[3]))
        };
        if (LoadIconElement(w, Smin(w.Style?.IconSizePx ?? 54)) is UIElement tileIc)
        {
            if (tileIc is FrameworkElement fe)
            {
                fe.HorizontalAlignment = tileHAlign;
                fe.Margin = new Thickness(0, 0, 0, Sy(14));
            }
            innerSp.Children.Add(tileIc);
        }
        bool tileTitleBind = ContainsToken(w.Title) || (string.IsNullOrWhiteSpace(w.Title) && !string.IsNullOrWhiteSpace(w.TelemetryKey));
        string tileTitle = tileTitleBind
            ? FormatDynamicText(w.Title, w.TelemetryKey, w.Unit, true)
            : (w.Title ?? w.Id);
        innerSp.Children.Add(new TextBlock
        {
            Text = tileTitle,
            Foreground = new SolidColorBrush(txtCol),
            FontFamily = fontFam,
            FontStyle = fontStyle,
            FontSize = Smin(w.Style?.TitleSizePx ?? 26),
            FontWeight = titleWeight,
            TextWrapping = w.WrapText == true ? TextWrapping.Wrap : TextWrapping.NoWrap,
            TextTrimming = w.WrapText == true ? TextTrimming.None : TextTrimming.CharacterEllipsis,
            TextAlignment = tileTxtAlign,
            HorizontalAlignment = tileHAlign
        });
        string? tileSub = w.Subtitle;
        bool tileAutoSub = !string.IsNullOrWhiteSpace(w.TelemetryKey) && string.IsNullOrWhiteSpace(tileSub) && !tileTitleBind;
        if (ContainsToken(tileSub) || tileAutoSub)
            tileSub = FormatDynamicText(tileSub, w.TelemetryKey, w.Unit, tileAutoSub);
        if (!string.IsNullOrWhiteSpace(tileSub))
        {
            innerSp.Children.Add(new TextBlock
            {
                Text = tileSub,
                Foreground = new SolidColorBrush(subCol),
                FontFamily = fontFam,
                FontStyle = fontStyle,
                FontWeight = subWeight,
                FontSize = Smin(w.Style?.SubtitleSizePx ?? 16),
                Margin = new Thickness(0, Sy(4), 0, 0),
                TextWrapping = w.WrapText == true ? TextWrapping.Wrap : TextWrapping.NoWrap,
                TextTrimming = w.WrapText == true ? TextTrimming.None : TextTrimming.CharacterEllipsis,
                TextAlignment = tileTxtAlign,
                HorizontalAlignment = tileHAlign
            });
        }
        tileGrid.Children.Add(innerSp);
        if (!string.IsNullOrWhiteSpace(w.BadgeText))
        {
            string badgeTxt = ContainsToken(w.BadgeText)
                ? FormatDynamicText(w.BadgeText, w.TelemetryKey, w.Unit, false)
                : w.BadgeText!;
            tileGrid.Children.Add(new Border
            {
                HorizontalAlignment = HorizontalAlignment.Right,
                VerticalAlignment = VerticalAlignment.Top,
                Margin = new Thickness(0, Sy(14), Sx(14), 0),
                Padding = new Thickness(Sx(10), Sy(3), Sx(10), Sy(3)),
                CornerRadius = new CornerRadius(Smin(999)),
                Background = new SolidColorBrush(Color.FromArgb(52, accent.R, accent.G, accent.B)),
                BorderBrush = new SolidColorBrush(Color.FromArgb(140, accent.R, accent.G, accent.B)),
                BorderThickness = new Thickness(Math.Max(1, Smin(1))),
                Child = new TextBlock
                {
                    Text = badgeTxt,
                    Foreground = new SolidColorBrush(accent),
                    FontFamily = fontFam,
                    FontWeight = FontWeights.Bold,
                    FontSize = Smin(12)
                }
            });
        }
        return tileGrid;
    }

    private UIElement? LoadIconElement(LayoutNodeModel w, double sizePx)
    {
        string? iconRef = !string.IsNullOrWhiteSpace(w.AnimIcon) ? w.AnimIcon
                        : (!string.IsNullOrWhiteSpace(w.BitmapAsset) ? w.BitmapAsset : w.Icon);
        return LoadAssetVisual(iconRef, w.Style?.IconTint, sizePx, cover: false);
    }

    private UIElement? LoadAssetVisual(string? relRef, string? tintHex, double fixedSizePx, bool cover)
    {
        if (workspace == null || string.IsNullOrWhiteSpace(relRef)) return null;
        string path = workspace.ResolveAssetFullPath(relRef);
        string? imgFile = Directory.Exists(path) ? FindFirstFrameInDir(path) : (File.Exists(path) ? path : null);
        if (string.IsNullOrEmpty(imgFile)) return null;

        Color? tint = ParseNullableColor(tintHex);
        ImageSource? src = imgFile.EndsWith(".svg", StringComparison.OrdinalIgnoreCase)
            ? SvgPreviewRenderer.RenderSvgFile(imgFile, tint)
            : LoadFrozenBitmap(imgFile);
        if (src == null) return null;

        var img = new Image { Source = src, Stretch = cover ? Stretch.UniformToFill : Stretch.Uniform };
        if (!double.IsNaN(fixedSizePx))
        {
            img.Width = fixedSizePx;
            img.Height = fixedSizePx;
        }
        return img;
    }

    private static UIElement BuildRingVisual(double size, double pct, string centerText, Color ringColor, Color textColor, Color trackColor, FontFamily fontFam)
    {
        var grid = new Grid { Width = size, Height = size };
        double stroke = Math.Max(6, size * 0.11);
        double pad = stroke * 0.6;
        double r = size * 0.5 - pad;
        var track = new Ellipse { Stroke = new SolidColorBrush(trackColor), StrokeThickness = stroke, Margin = new Thickness(pad * 0.5) };
        grid.Children.Add(track);

        double sweepDeg = Math.Clamp(pct, 0, 100) * 3.599;
        if (sweepDeg > 1)
        {
            double rad = (sweepDeg - 90.0) * Math.PI / 180.0;
            double cx = size * 0.5, cy = size * 0.5;
            var start = new Point(cx, cy - r);
            var end = new Point(cx + r * Math.Cos(rad), cy + r * Math.Sin(rad));
            var seg = new ArcSegment(end, new Size(r, r), 0, sweepDeg > 180, SweepDirection.Clockwise, true);
            var fig = new PathFigure(start, new[] { seg }, false);
            grid.Children.Add(new System.Windows.Shapes.Path { Stroke = new SolidColorBrush(ringColor), StrokeThickness = stroke, StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round, Data = new PathGeometry(new[] { fig }) });
        }
        grid.Children.Add(new TextBlock { Text = centerText, Foreground = new SolidColorBrush(textColor), FontFamily = fontFam, FontSize = Math.Max(14, size * 0.24), FontWeight = FontWeights.Bold, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center });
        return grid;
    }

    private static bool ContainsToken(string? s)
        => !string.IsNullOrEmpty(s) && s.IndexOf('{') >= 0 && s.IndexOf('}') > s.IndexOf('{');

    private string FormatDynamicText(string? template, string? primaryKey, string? unitOverride, bool blankUsesPrimary)
    {
        string tpl = template ?? "";
        if (string.IsNullOrEmpty(tpl))
            return blankUsesPrimary && !string.IsNullOrWhiteSpace(primaryKey) ? ResolveSimValue(primaryKey, unitOverride) : "";

        string working = tpl;
        if (working.Contains("{value}") && !string.IsNullOrWhiteSpace(primaryKey))
            working = working.Replace("{value}", ResolveSimValue(primaryKey, unitOverride));

        if (!working.Contains('{')) return working;

        var sb = new System.Text.StringBuilder();
        int pos = 0;
        while (pos < working.Length)
        {
            int open = working.IndexOf('{', pos);
            if (open < 0) { sb.Append(working, pos, working.Length - pos); break; }
            int close = working.IndexOf('}', open + 1);
            if (close < 0) { sb.Append(working, pos, working.Length - pos); break; }
            sb.Append(working, pos, open - pos);
            string tok = working.Substring(open + 1, close - open - 1).Trim();
            bool sameAsPrimary = !string.IsNullOrWhiteSpace(primaryKey)
                                 && CanonicalSensorKey(tok).Equals(CanonicalSensorKey(primaryKey), StringComparison.OrdinalIgnoreCase);
            sb.Append(string.IsNullOrEmpty(tok) ? "" : ResolveSimValue(tok, sameAsPrimary ? unitOverride : null));
            pos = close + 1;
        }
        return sb.ToString();
    }

    private bool PreferMph => string.Equals(pack?.Tokens?.SpeedUnit, "mph", StringComparison.OrdinalIgnoreCase);
    private bool PreferCelsius => string.Equals(pack?.Tokens?.TempUnit, "C", StringComparison.OrdinalIgnoreCase);

    private bool ShouldUseMph(string rawKey, string unitOverride)
    {
        if (rawKey.Equals("speed_mph", StringComparison.OrdinalIgnoreCase)
            || rawKey.Equals("odometer_mi", StringComparison.OrdinalIgnoreCase)
            || rawKey.Equals("hv.range_mi", StringComparison.OrdinalIgnoreCase))
            return true;
        if (rawKey.Equals("speed_kmh", StringComparison.OrdinalIgnoreCase)
            || rawKey.Equals("odometer_km", StringComparison.OrdinalIgnoreCase)
            || rawKey.Equals("hv.range_km", StringComparison.OrdinalIgnoreCase))
            return false;
        if (unitOverride.Contains("mph", StringComparison.OrdinalIgnoreCase)
            || unitOverride.Equals("mi", StringComparison.OrdinalIgnoreCase))
            return true;
        if (unitOverride.Contains("km", StringComparison.OrdinalIgnoreCase))
            return false;
        return PreferMph;
    }

    private bool ShouldPackTempUseFahrenheit(string rawKey, string unitOverride)
    {
        if (rawKey.Equals("hv.battery_temp_min_f", StringComparison.OrdinalIgnoreCase)) return true;
        if (rawKey.Equals("hv.battery_temp_min_c", StringComparison.OrdinalIgnoreCase)) return false;
        if (unitOverride.Contains('F', StringComparison.OrdinalIgnoreCase)) return true;
        if (unitOverride.Contains('C', StringComparison.OrdinalIgnoreCase)) return false;
        return !PreferCelsius;
    }

    private double DefaultSensorMin(string? key, string? unit)
    {
        string raw = (key ?? "").Trim();
        string k = CanonicalSensorKey(raw);
        string u = (unit ?? "").Trim();
        if (k == "hv.battery_temp_min") return ShouldPackTempUseFahrenheit(raw, u) ? 0 : -20;
        if (k == "outside_temp") return u.Contains('F', StringComparison.OrdinalIgnoreCase) ? -20 : -30;
        if (k == "outside_temp_f") return u.Contains('C', StringComparison.OrdinalIgnoreCase) ? -30 : -20;
        if (k is "climate.driver_temp" or "climate.passenger_temp")
            return (u.Contains('C', StringComparison.OrdinalIgnoreCase) || (string.IsNullOrEmpty(u) && PreferCelsius)) ? 17 : 60;
        if (k == "steering.angle") return -540;
        return 0;
    }

    private double DefaultSensorMax(string? key, string? unit)
    {
        string raw = (key ?? "").Trim();
        string k = CanonicalSensorKey(raw);
        string u = (unit ?? "").Trim();
        if (k == "hv.battery_temp_min") return ShouldPackTempUseFahrenheit(raw, u) ? 140 : 60;
        if (k == "outside_temp") return u.Contains('F', StringComparison.OrdinalIgnoreCase) ? 120 : 50;
        if (k == "outside_temp_f") return u.Contains('C', StringComparison.OrdinalIgnoreCase) ? 50 : 120;
        if (k is "speed" or "gear_speed") return ShouldUseMph(raw, u) ? 120 : 180;
        if (k == "odometer") return ShouldUseMph(raw, u) ? 200000 : 300000;
        if (k == "hv.range") return ShouldUseMph(raw, u) ? 350 : 550;
        if (k == "charging.remain_min") return 600;
        if (k is "climate.driver_temp" or "climate.passenger_temp")
            return (u.Contains('C', StringComparison.OrdinalIgnoreCase) || (string.IsNullOrEmpty(u) && PreferCelsius)) ? 32 : 90;
        if (k == "climate.fan_speed") return 8;
        if (k == "climate.auto_level") return 3;
        if (k == "climate.vent_mode") return 10;
        if (k == "audio.volume") return 45;
        if (k == "steering.angle") return 540;
        if (k == "energy.drivetrain_kw") return 250;
        if (k is "energy.climate_kw" or "energy.care_kw") return 10;
        if (k == "energy.electronics_w") return 1500;
        if (k.StartsWith("tyre.") && k.EndsWith("_psi")) return 60;
        if (k.StartsWith("seat_heat.") || k.StartsWith("seat_vent.")) return 3;
        if (k is "hv.conditioning" or "hv.plug_state") return 2;
        if (k.StartsWith("door.") || k.StartsWith("window.") || k.StartsWith("tyre.")) return 1;
        if (k is "wheel_heat" or "defogger" or "charging" or "hv.ready" or "hv.v2l_active" or "gear.park" or "parking_brake"
            or "moving" or "hv.conditioning_active" or "conditioning.state" or "driving.restricted" or "guide_lines"
            or "climate.on" or "climate.auto" or "climate.ac" or "climate.heat" or "climate.rear_on"
            or "climate.rear_lock" or "climate.air_clean" or "climate.temp_unit"
            or "climate.recirc" or "climate.sync" or "climate.driver_only" or "climate.front_defrost"
            or "climate.smart_vent" or "climate.auto_defog" or "climate.after_blow" or "battery12v.saver"
            or "audio.muted" or "trunk.open" or "trunk.ptg_state" or "charge_door.open" or "hood.open" or "sunroof.open")
            return 1;
        return 100;
    }

    private static string CanonicalSensorKey(string? key) => (key ?? "").Trim() switch
    {
        "hv_soc" => "hv.soc",
        "hv_range" => "hv.range",
        "hv.range_mi" => "hv.range",
        "hv.range_km" => "hv.range",
        "aux12v_soc" => "battery12v.soc",
        "outside_temp_c" => "outside_temp",
        "hv_ready" => "hv.ready",
        "gear_park" => "gear.park",
        "driving_restricted" => "driving.restricted",
        "hv.battery_temp_min_f" => "hv.battery_temp_min",
        "hv.battery_temp_min_c" => "hv.battery_temp_min",
        "climate_temp_driver" => "climate.driver_temp",
        "climate_temp_passenger" => "climate.passenger_temp",
        "climate_fan" => "climate.fan_speed",
        "volume" => "audio.volume",
        "speed_mph" => "speed",
        "speed_kmh" => "speed",
        "odometer_mi" => "odometer",
        "odometer_km" => "odometer",
        var other => other
    };

    private double ParseSimNumeric(string? rawKey, string? unitOverride, out bool valid)
    {
        valid = true;
        string raw = (rawKey ?? "").Trim();
        string k = CanonicalSensorKey(raw);
        string u = (unitOverride ?? "").Trim();
        switch (k)
        {
            case "hv.soc": return sim.HvSoc;
            case "hv.range": return ShouldUseMph(raw, u) ? sim.HvRangeKm * 0.621371 : sim.HvRangeKm;
            case "charging.remain_min": return sim.ChargingRemainMin;
            case "battery12v.soc": return sim.Aux12vSoc;
            case "hv.health": return sim.HvHealth;
            case "hv.battery_temp_min":
                return ShouldPackTempUseFahrenheit(raw, u)
                    ? sim.HvPackTempC * 9.0 / 5.0 + 32.0
                    : sim.HvPackTempC;
            case "outside_temp_f":
                return u.Contains('C', StringComparison.OrdinalIgnoreCase) ? (sim.OutsideTempF - 32.0) * 5.0 / 9.0 : sim.OutsideTempF;
            case "outside_temp":
                return u.Contains('F', StringComparison.OrdinalIgnoreCase) ? sim.OutsideTempF : (sim.OutsideTempF - 32.0) * 5.0 / 9.0;
            case "gear_speed":
            case "speed":
                return ShouldUseMph(raw, u) ? sim.SpeedKmh * 0.621371 : sim.SpeedKmh;
            case "odometer":
                return ShouldUseMph(raw, u) ? sim.OdometerKm * 0.621371 : sim.OdometerKm;
            case "steering.angle": return sim.SteeringAngleDeg;
            case "pedal.accel": return sim.AccelPedal;
            case "brake.pressure": return sim.BrakePressure;
            case "climate.driver_temp":
                return (u.Contains('C', StringComparison.OrdinalIgnoreCase) || (string.IsNullOrEmpty(u) && PreferCelsius))
                    ? (sim.ClimateDriverTempF - 32.0) * 5.0 / 9.0
                    : sim.ClimateDriverTempF;
            case "climate.passenger_temp":
                return (u.Contains('C', StringComparison.OrdinalIgnoreCase) || (string.IsNullOrEmpty(u) && PreferCelsius))
                    ? (sim.ClimatePassTempF - 32.0) * 5.0 / 9.0
                    : sim.ClimatePassTempF;
            case "climate.fan_speed": return sim.ClimateFanSpeed;
            case "climate.auto_level": return sim.ClimateAuto ? 1 : 0;
            case "climate.vent_mode": return sim.ClimateVentMode;
            case "climate.on": return sim.ClimateOn ? 1 : 0;
            case "climate.auto": return sim.ClimateAuto ? 1 : 0;
            case "climate.ac": return sim.ClimateAc ? 1 : 0;
            case "climate.heat": return sim.ClimateHeat ? 1 : 0;
            case "climate.recirc": return sim.ClimateRecirc ? 1 : 0;
            case "climate.sync": return sim.ClimateSync ? 1 : 0;
            case "climate.driver_only": return sim.ClimateDriverOnly ? 1 : 0;
            case "climate.front_defrost": return sim.ClimateFrontDefrost ? 1 : 0;
            case "climate.rear_on": return sim.ClimateRearOn ? 1 : 0;
            case "climate.rear_lock": return 0;
            case "climate.air_clean": return 0;
            case "climate.temp_unit": return PreferCelsius ? 0 : 1;
            case "climate.smart_vent": return sim.ClimateSmartVent ? 1 : 0;
            case "climate.auto_defog": return sim.ClimateAutoDefog ? 1 : 0;
            case "climate.after_blow": return sim.ClimateAfterBlow ? 1 : 0;
            case "audio.volume": return sim.AudioVolume;
            case "audio.muted": return sim.AudioMuted ? 1 : 0;
            case "door.lock_state":
            case "door.lock.fl":
            case "door.lock.fr":
            case "door.lock.rl":
            case "door.lock.rr": return sim.DoorsLocked ? 1 : 0;
            case "door.any_open": return sim.AnyDoorOpen ? 1 : 0;
            case "door.fl":
            case "door.fr":
            case "door.rl":
            case "door.rr":
            case "window.fl":
            case "window.fr":
            case "window.rl":
            case "window.rr": return 0;
            case "trunk.open":
            case "trunk.ptg_state": return sim.TrunkOpen ? 1 : 0;
            case "charge_door.open": return 0;
            case "hood.open": return sim.HoodOpen ? 1 : 0;
            case "sunroof.open": return sim.SunroofOpen ? 1 : 0;
            case "tyre.fl_status":
            case "tyre.fr_status":
            case "tyre.rl_status":
            case "tyre.rr_status": return 0;
            case "tyre.fl_psi":
            case "tyre.fr_psi":
            case "tyre.rl_psi":
            case "tyre.rr_psi": return 36;
            case "energy.drivetrain_kw": return 12.4;
            case "energy.climate_kw": return 1.1;
            case "energy.electronics_w": return 340;
            case "energy.care_kw": return 0.0;
            case "seat_heat.driver": return sim.DriverSeatHeat;
            case "seat_heat.passenger": return sim.PassengerSeatHeat;
            case "seat_heat.rear_left": return sim.RearLeftSeatHeat;
            case "seat_heat.rear_right": return sim.RearRightSeatHeat;
            case "seat_vent.driver": return sim.DriverSeatVent;
            case "seat_vent.passenger": return sim.PassengerSeatVent;
            case "seat_state.driver": return sim.DriverSeatHeat > 0 ? (sim.DriverSeatHeat + 5) : (sim.DriverSeatVent > 0 ? (sim.DriverSeatVent + 2) : 2);
            case "seat_state.passenger": return sim.PassengerSeatHeat > 0 ? (sim.PassengerSeatHeat + 5) : (sim.PassengerSeatVent > 0 ? (sim.PassengerSeatVent + 2) : 2);
            case "seat_state.rear_left": return sim.RearLeftSeatHeat > 0 ? (sim.RearLeftSeatHeat + 5) : 2;
            case "seat_state.rear_right": return sim.RearRightSeatHeat > 0 ? (sim.RearRightSeatHeat + 5) : 2;
            case "wheel_heat": return sim.WheelHeat ? 1 : 0;
            case "defogger": return sim.Defogger ? 1 : 0;
            case "charging": return sim.Charging ? 1 : 0;
            case "hv.plug_state": return sim.Charging ? 1 : 0;
            case "hv.v2l_active": return 0;
            case "hv.ready": return sim.Ready ? 1 : 0;
            case "hv.conditioning": return 2;
            case "hv.conditioning_active": return sim.ConditioningActive ? 1 : 0;
            case "gear.park": return sim.InPark ? 1 : 0;
            case "parking_brake": return sim.ParkingBrake ? 1 : 0;
            case "guide_lines": return 1;
            case "moving": return !sim.InPark && sim.SpeedKmh > 0 ? 1 : 0;
            case "driving.restricted": return sim.DrivingRestricted ? 1 : 0;
            case "battery12v.saver": return sim.Battery12vSaver;
            default:
                valid = false;
                return 0;
        }
    }

    private static string FormatLevel(int level) => level <= 0 ? "OFF" : level == 1 ? "LOW" : level == 2 ? "MED" : "HIGH";

    private string FormatSimClimateTemp(double tempF, string u)
    {
        bool useC = u.Contains('C', StringComparison.OrdinalIgnoreCase) || (string.IsNullOrEmpty(u) && PreferCelsius);
        string suffix = string.IsNullOrEmpty(u) ? (useC ? "\u00B0C" : "\u00B0F") : u;
        return useC
            ? $"{Math.Round((tempF - 32.0) * 5.0 / 9.0):0}{suffix}"
            : $"{Math.Round(tempF):0}{suffix}";
    }

    private string ResolveSimValue(string? key, string? unitOverride = null)
    {
        string rawKey = (key ?? "").Trim();
        string k = CanonicalSensorKey(rawKey);
        string u = (unitOverride ?? "").Trim();
        return k switch
        {
            "hv.soc" => $"{sim.HvSoc:0}{(string.IsNullOrEmpty(u) ? "%" : u)}",
            "hv.range" => ShouldUseMph(rawKey, u)
                ? $"{Math.Round(sim.HvRangeKm * 0.621371):0} {(string.IsNullOrEmpty(u) ? "mi" : u)}"
                : $"{sim.HvRangeKm:0} {(string.IsNullOrEmpty(u) ? "km" : u)}",
            "charging.remain_min" => sim.ChargingRemainMin > 0 ? $"{sim.ChargingRemainMin}{(string.IsNullOrEmpty(u) ? "m" : u)}" : "--",
            "battery12v.soc" => $"{sim.Aux12vSoc:0}{(string.IsNullOrEmpty(u) ? "%" : u)}",
            "hv.health" => $"{sim.HvHealth}{u}",
            "hv.battery_temp_min" => ShouldPackTempUseFahrenheit(rawKey, u)
                ? $"{sim.HvPackTempC * 9.0 / 5.0 + 32.0:0}{(string.IsNullOrEmpty(u) ? "\u00B0F" : u)}"
                : $"{sim.HvPackTempC:0}{(string.IsNullOrEmpty(u) ? "\u00B0C" : u)}",
            "outside_temp_f" => u.Contains('C', StringComparison.OrdinalIgnoreCase)
                ? $"{(sim.OutsideTempF - 32.0) * 5.0 / 9.0:0}{u}" : $"{sim.OutsideTempF:0}{(string.IsNullOrEmpty(u) ? "\u00B0F" : u)}",
            "outside_temp" => u.Contains('F', StringComparison.OrdinalIgnoreCase)
                ? $"{sim.OutsideTempF:0}{u}" : $"{(sim.OutsideTempF - 32.0) * 5.0 / 9.0:0}{(string.IsNullOrEmpty(u) ? "\u00B0C" : u)}",
            "speed" => ShouldUseMph(rawKey, u)
                ? $"{sim.SpeedKmh * 0.621371:0} {(string.IsNullOrEmpty(u) ? "mph" : u)}"
                : $"{sim.SpeedKmh} {(string.IsNullOrEmpty(u) ? "km/h" : u)}",
            "odometer" => ShouldUseMph(rawKey, u)
                ? $"{sim.OdometerKm * 0.621371:0} {(string.IsNullOrEmpty(u) ? "mi" : u)}"
                : $"{sim.OdometerKm} {(string.IsNullOrEmpty(u) ? "km" : u)}",
            "gear_speed" => sim.InPark
                ? "PARK"
                : (sim.SpeedKmh > 0
                    ? (ShouldUseMph(rawKey, u)
                        ? $"{sim.SpeedKmh * 0.621371:0} {(string.IsNullOrEmpty(u) ? "mph" : u)}"
                        : $"{sim.SpeedKmh} {(string.IsNullOrEmpty(u) ? "km/h" : u)}")
                    : "DRIVE"),
            "gear.park" => sim.InPark ? "PARK" : "DRIVE",
            "parking_brake" => sim.ParkingBrake ? "EPB ON" : "RELEASED",
            "moving" => (!sim.InPark && sim.SpeedKmh > 0) ? "MOVING" : "STOPPED",
            "charging" => sim.Charging ? "CHARGING" : "IDLE",
            "hv.plug_state" => sim.Charging ? "PLUGGED" : "UNPLUGGED",
            "hv.v2l_active" => "OFF",
            "hv.ready" => sim.Ready ? "READY" : "STANDBY",
            "hv.conditioning" => "ON",
            "hv.conditioning_active" => sim.ConditioningActive ? "HEATING" : "IDLE",
            "conditioning.state" => sim.ConditioningState,
            "driving.restricted" => sim.DrivingRestricted ? "LOCKED" : "OPEN",
            "guide_lines" => "ON",
            "battery12v.saver" => $"{sim.Battery12vSaver}{u}",
            "wheel_heat" => sim.WheelHeat ? "ON" : "OFF",
            "defogger" => sim.Defogger ? "ON" : "OFF",
            "steering.angle" => $"{sim.SteeringAngleDeg:0}{(string.IsNullOrEmpty(u) ? "\u00B0" : u)}",
            "pedal.accel" => $"{sim.AccelPedal:0}{(string.IsNullOrEmpty(u) ? "%" : u)}",
            "brake.pressure" => $"{sim.BrakePressure:0}{u}",
            "climate.on" => sim.ClimateOn ? "ON" : "OFF",
            "climate.auto" => sim.ClimateAuto ? "ON" : "OFF",
            "climate.auto_level" => sim.ClimateAuto ? "LVL 1" : "OFF",
            "climate.ac" => sim.ClimateAc ? "ON" : "OFF",
            "climate.heat" => sim.ClimateHeat ? "ON" : "OFF",
            "climate.temp_unit" => PreferCelsius ? "C" : "F",
            "climate.recirc" => sim.ClimateRecirc ? "ON" : "OFF",
            "climate.sync" => sim.ClimateSync ? "ON" : "OFF",
            "climate.driver_only" => sim.ClimateDriverOnly ? "ON" : "OFF",
            "climate.front_defrost" => sim.ClimateFrontDefrost ? "ON" : "OFF",
            "climate.rear_on" => sim.ClimateRearOn ? "ON" : "OFF",
            "climate.rear_lock" => "OFF",
            "climate.air_clean" => "OFF",
            "climate.smart_vent" => sim.ClimateSmartVent ? "ON" : "OFF",
            "climate.auto_defog" => sim.ClimateAutoDefog ? "ON" : "OFF",
            "climate.after_blow" => sim.ClimateAfterBlow ? "ON" : "OFF",
            "climate.driver_temp" => FormatSimClimateTemp(sim.ClimateDriverTempF, u),
            "climate.passenger_temp" => FormatSimClimateTemp(sim.ClimatePassTempF, u),
            "climate.fan_speed" => string.IsNullOrEmpty(u)
                ? (sim.ClimateFanSpeed <= 0 ? "OFF" : $"LVL {sim.ClimateFanSpeed}")
                : $"{sim.ClimateFanSpeed}{u}",
            "climate.vent_mode" => sim.ClimateVentMode switch
            {
                0 => "AUTO",
                1 => "FACE",
                2 => "BI-LVL",
                3 => "FLOOR",
                4 => "FLR+DEF",
                5 or 10 => "DEFROST",
                _ => $"M{sim.ClimateVentMode}"
            },
            "audio.volume" => $"{sim.AudioVolume}{u}",
            "audio.muted" => sim.AudioMuted ? "MUTED" : "ACTIVE",
            "door.lock_state" or "door.lock.fl" or "door.lock.fr" or "door.lock.rl" or "door.lock.rr"
                => sim.DoorsLocked ? "LOCKED" : "UNLOCKED",
            "door.any_open" => sim.AnyDoorOpen ? "DOOR OPEN" : "ALL CLOSED",
            "door.fl" or "door.fr" or "door.rl" or "door.rr" => "CLOSED",
            "window.fl" or "window.fr" or "window.rl" or "window.rr" => "CLOSED",
            "trunk.open" or "trunk.ptg_state" => sim.TrunkOpen ? "OPEN" : "CLOSED",
            "charge_door.open" => "CLOSED",
            "hood.open" => sim.HoodOpen ? "OPEN" : "CLOSED",
            "sunroof.open" => sim.SunroofOpen ? "OPEN" : "CLOSED",
            "tyre.fl_status" or "tyre.fr_status" or "tyre.rl_status" or "tyre.rr_status" => "OK",
            "tyre.fl_psi" or "tyre.fr_psi" or "tyre.rl_psi" or "tyre.rr_psi" => $"36 {(string.IsNullOrEmpty(u) ? "psi" : u)}",
            "energy.drivetrain_kw" => $"12.4 {(string.IsNullOrEmpty(u) ? "kW" : u)}",
            "energy.climate_kw" => $"1.1 {(string.IsNullOrEmpty(u) ? "kW" : u)}",
            "energy.electronics_w" => $"340 {(string.IsNullOrEmpty(u) ? "W" : u)}",
            "energy.care_kw" => $"0 {(string.IsNullOrEmpty(u) ? "kW" : u)}",
            "seat_heat.driver" => string.IsNullOrEmpty(u) ? FormatLevel(sim.DriverSeatHeat) : $"{sim.DriverSeatHeat}{u}",
            "seat_heat.passenger" => string.IsNullOrEmpty(u) ? FormatLevel(sim.PassengerSeatHeat) : $"{sim.PassengerSeatHeat}{u}",
            "seat_heat.rear_left" => string.IsNullOrEmpty(u) ? FormatLevel(sim.RearLeftSeatHeat) : $"{sim.RearLeftSeatHeat}{u}",
            "seat_heat.rear_right" => string.IsNullOrEmpty(u) ? FormatLevel(sim.RearRightSeatHeat) : $"{sim.RearRightSeatHeat}{u}",
            "seat_vent.driver" => string.IsNullOrEmpty(u) ? FormatLevel(sim.DriverSeatVent) : $"{sim.DriverSeatVent}{u}",
            "seat_vent.passenger" => string.IsNullOrEmpty(u) ? FormatLevel(sim.PassengerSeatVent) : $"{sim.PassengerSeatVent}{u}",
            "seat_state.driver" => $"{(sim.DriverSeatHeat > 0 ? sim.DriverSeatHeat + 5 : sim.DriverSeatVent > 0 ? sim.DriverSeatVent + 2 : 2)}{u}",
            "seat_state.passenger" => $"{(sim.PassengerSeatHeat > 0 ? sim.PassengerSeatHeat + 5 : sim.PassengerSeatVent > 0 ? sim.PassengerSeatVent + 2 : 2)}{u}",
            "seat_state.rear_left" => $"{(sim.RearLeftSeatHeat > 0 ? sim.RearLeftSeatHeat + 5 : 2)}{u}",
            "seat_state.rear_right" => $"{(sim.RearRightSeatHeat > 0 ? sim.RearRightSeatHeat + 5 : 2)}{u}",
            _ => "OK"
        };
    }

    private static void ApplyOpacity(UIElement el, WidgetStyleModel? st)
    {
        if (st?.Opacity is >= 0 and < 1) el.Opacity = Math.Clamp(st.Opacity.Value, 0.0, 1.0);
    }

    private static FontWeight ResolveFontWeight(string? fw, bool defaultBold)
    {
        if (string.Equals(fw, "normal", StringComparison.OrdinalIgnoreCase)) return FontWeights.Normal;
        if (string.Equals(fw, "bold", StringComparison.OrdinalIgnoreCase)) return FontWeights.Bold;
        return defaultBold ? FontWeights.Bold : FontWeights.Normal;
    }

    private static HorizontalAlignment ResolveTextAlignment(string? align, HorizontalAlignment fallback) => (align ?? "").ToLowerInvariant() switch
    {
        "center" => HorizontalAlignment.Center,
        "right" => HorizontalAlignment.Right,
        "left" => HorizontalAlignment.Left,
        _ => fallback
    };

    private static TextAlignment ToTextAlignment(string? align) => (align ?? "").ToLowerInvariant() switch
    {
        "center" => TextAlignment.Center,
        "right" => TextAlignment.Right,
        _ => TextAlignment.Left
    };

    private static VerticalAlignment ResolveVerticalAlignment(string? align, VerticalAlignment fallback) => (align ?? "").ToLowerInvariant() switch
    {
        "top" => VerticalAlignment.Top,
        "bottom" => VerticalAlignment.Bottom,
        "center" => VerticalAlignment.Center,
        _ => fallback
    };

    public static LinearGradientBrush CreateAndroidMatchedGradient(Color startColor, Color endColor, int angleDeg)
    {
        int norm = ((angleDeg % 360) + 360) % 360;
        Point startPt;
        Point endPt;
        if (norm < 23 || norm >= 338) { startPt = new Point(0, 0.5); endPt = new Point(1, 0.5); }
        else if (norm < 68)           { startPt = new Point(0, 1);   endPt = new Point(1, 0); }
        else if (norm < 113)          { startPt = new Point(0.5, 1); endPt = new Point(0.5, 0); }
        else if (norm < 158)          { startPt = new Point(1, 1);   endPt = new Point(0, 0); }
        else if (norm < 203)          { startPt = new Point(1, 0.5); endPt = new Point(0, 0.5); }
        else if (norm < 248)          { startPt = new Point(1, 0);   endPt = new Point(0, 1); }
        else if (norm < 293)          { startPt = new Point(0.5, 0); endPt = new Point(0.5, 1); }
        else                          { startPt = new Point(0, 0);   endPt = new Point(1, 1); }
        return new LinearGradientBrush(startColor, endColor, startPt, endPt);
    }

    private Border BuildStyledBorder(LayoutNodeModel node, Rect rect)
    {
        var s = node.Style;
        Color bgStart = ParseColor(s?.BgColor, Color.FromArgb(208, 18, 26, 38));
        Brush bgBrush = bgStart != Colors.Transparent && !string.IsNullOrWhiteSpace(s?.BgEndColor)
            ? CreateAndroidMatchedGradient(bgStart, ParseColor(s!.BgEndColor, bgStart), s.GradientAngle ?? 135)
            : new SolidColorBrush(bgStart);
        int bWidth = (s?.BorderWidthPx ?? 2) > 0 ? Math.Max(1, (int)Smin(s?.BorderWidthPx ?? 2)) : 0;
        int defaultRadius = (!node.IsPanel && string.Equals(node.Kind, "action_pill", StringComparison.OrdinalIgnoreCase))
            ? 999
            : (pack?.Tokens?.CornerRadiusPx ?? 20);
        double resolvedRadius = Math.Min(Smin(s?.CornerRadiusPx ?? defaultRadius), Math.Min(rect.Width, rect.Height) * 0.5);
        return new Border
        {
            Width = rect.Width,
            Height = rect.Height,
            Background = bgBrush,
            BorderBrush = new SolidColorBrush(ParseColor(s?.BorderColor, Color.FromArgb(70, 86, 139, 181))),
            BorderThickness = new Thickness(bWidth),
            CornerRadius = new CornerRadius(resolvedRadius),
            Tag = node
        };
    }

    private void DrawSelectionAdorner()
    {
        selectionOverlay.Children.Clear();
        if (selectedNode == null || !nodeRects.TryGetValue(selectedNode, out Rect r)) return;

        var box = new Rectangle
        {
            Width = r.Width,
            Height = r.Height,
            Stroke = new SolidColorBrush(Color.FromRgb(56, 189, 248)),
            StrokeThickness = 3,
            StrokeDashArray = new DoubleCollection { 6, 4 },
            IsHitTestVisible = false
        };
        Canvas.SetLeft(box, r.X);
        Canvas.SetTop(box, r.Y + windowTopOffset);
        selectionOverlay.Children.Add(box);

        if (parentMap.TryGetValue(selectedNode, out var par) && par?.Layout == "freeform")
        {
            var handle = new Rectangle
            {
                Width = 18,
                Height = 18,
                Fill = new SolidColorBrush(Color.FromRgb(56, 189, 248)),
                Stroke = Brushes.White,
                StrokeThickness = 2,
                Cursor = Cursors.SizeNWSE
            };
            Canvas.SetLeft(handle, r.Right - 9);
            Canvas.SetTop(handle, r.Bottom + windowTopOffset - 9);
            handle.MouseLeftButtonDown += (s, e) =>
            {
                e.Handled = true;
                var targetNode = selectedNode;
                if (!IsKeyboardFocused) Focus();
                if (targetNode != null)
                {
                    BeginFreeformDrag(targetNode, 1, e.GetPosition(layoutLayer));
                }
            };
            selectionOverlay.Children.Add(handle);
        }
    }

    private void OnLayoutMouseDown(object sender, MouseButtonEventArgs e)
    {
        LayoutNodeModel? hit = (e.OriginalSource is FrameworkElement fe) ? FindNodeTag(fe) : null;
        // Move keyboard focus off any active inspector TextBox before switching selection so
        // pending edits commit cleanly to the old node and focused fields do not stay stale.
        if (!IsKeyboardFocused)
        {
            Focus();
        }
        if (hit != null)
        {
            selectedNode = hit;
            NodeSelected?.Invoke(hit);
            DrawSelectionAdorner();
            if (parentMap.TryGetValue(hit, out var par) && par?.Layout == "freeform")
            {
                BeginFreeformDrag(hit, 0, e.GetPosition(layoutLayer));
            }
        }
    }

    private void BeginFreeformDrag(LayoutNodeModel node, int mode, Point pos)
    {
        if (!nodeRects.TryGetValue(node, out Rect r)) return;
        if (!parentMap.TryGetValue(node, out var par) || par?.Layout != "freeform") return;

        double sx = Math.Max(0.1, scaleX);
        double sy = Math.Max(0.1, scaleY);
        Rect pInner = panelInnerRects.TryGetValue(par, out Rect pi)
            ? pi
            : new Rect(0, 0, (pack?.ReferenceWidth ?? 1920) * sx, (pack?.ReferenceHeight ?? 720) * sy);

        double startRefX, startRefY, startRefW, startRefH;
        if (node.Bounds is { Length: >= 4 })
        {
            startRefX = node.Bounds[0];
            startRefY = node.Bounds[1];
            startRefW = Math.Max(20, node.Bounds[2]);
            startRefH = Math.Max(20, node.Bounds[3]);
        }
        else
        {
            int[] m = NormalizeInsets(node.MarginPx);
            startRefX = (r.X - pInner.X - Sx(m[0])) / sx;
            startRefY = (r.Y - pInner.Y - Sy(m[1])) / sy;
            startRefW = Math.Max(20, r.Width / sx);
            startRefH = Math.Max(20, r.Height / sy);
        }

        dragNode = node;
        dragParent = par;
        dragMode = mode;
        dragThresholdMet = false;
        dragStartCanvas = pos;
        dragStartLocalRefRect = new Rect(startRefX, startRefY, startRefW, startRefH);
        dragParentInnerRefSize = new Size(Math.Max(20, pInner.Width / sx), Math.Max(20, pInner.Height / sy));
        stageHost.CaptureMouse();
    }

    private void OnStageMouseMove(object sender, MouseEventArgs e)
    {
        if (dragNode == null) return;
        ApplyFreeformDragMove(e.GetPosition(layoutLayer));
    }

    private void ApplyFreeformDragMove(Point cur)
    {
        if (dragNode == null) return;
        double rawDx = cur.X - dragStartCanvas.X;
        double rawDy = cur.Y - dragStartCanvas.Y;
        if (!dragThresholdMet)
        {
            if (Math.Abs(rawDx) < DragStartDeadzonePx && Math.Abs(rawDy) < DragStartDeadzonePx)
                return;
            dragThresholdMet = true;
        }

        double dx = rawDx / Math.Max(0.1, scaleX);
        double dy = rawDy / Math.Max(0.1, scaleY);
        double startRefX = dragStartLocalRefRect.X;
        double startRefY = dragStartLocalRefRect.Y;
        double startRefW = dragStartLocalRefRect.Width;
        double startRefH = dragStartLocalRefRect.Height;

        int[] m = NormalizeInsets(dragNode.MarginPx);
        double maxRefX = Math.Max(0, dragParentInnerRefSize.Width - startRefW - m[0] - m[2]);
        double maxRefY = Math.Max(0, dragParentInnerRefSize.Height - startRefH - m[1] - m[3]);

        dragNode.Bounds ??= new double[] { startRefX, startRefY, startRefW, startRefH };
        if (dragMode == 0)
        {
            dragNode.Bounds[0] = Math.Round(Math.Clamp(startRefX + dx, 0, maxRefX));
            dragNode.Bounds[1] = Math.Round(Math.Clamp(startRefY + dy, 0, maxRefY));
        }
        else
        {
            double newW = Math.Round(Math.Max(20, startRefW + dx));
            double newH = Math.Round(Math.Max(20, startRefH + dy));
            dragNode.Bounds[2] = newW;
            dragNode.Bounds[3] = newH;
            if (dragNode.Width.HasValue) dragNode.Width = (int)newW;
            if (dragNode.Height.HasValue) dragNode.Height = (int)newH;
        }
        RebuildScene();
    }

    private void OnStageMouseUp(object sender, MouseButtonEventArgs e)
    {
        EndFreeformDrag();
    }

    private bool EndFreeformDrag()
    {
        if (dragNode == null) return false;
        bool mutated = dragThresholdMet;
        dragNode = null;
        dragParent = null;
        dragThresholdMet = false;
        stageHost.ReleaseMouseCapture();
        if (mutated)
        {
            PackMutated?.Invoke();
        }
        return mutated;
    }

    public bool SimulateFreeformInteractionForTest(LayoutNodeModel node, int mode, double stageDeltaX, double stageDeltaY)
    {
        if (!nodeRects.TryGetValue(node, out Rect r)) return false;
        Point start = new(r.X + r.Width * 0.5, r.Y + r.Height * 0.5);
        BeginFreeformDrag(node, mode, start);
        ApplyFreeformDragMove(new Point(start.X + stageDeltaX, start.Y + stageDeltaY));
        return EndFreeformDrag();
    }

    private static LayoutNodeModel? FindNodeTag(DependencyObject d)
    {
        while (d != null)
        {
            if (d is FrameworkElement fe && fe.Tag is LayoutNodeModel m) return m;
            d = VisualTreeHelper.GetParent(d);
        }
        return null;
    }

    private static LayoutNodeModel? FindFirstPager(LayoutNodeModel? root)
    {
        if (root == null) return null;
        if (root.Layout == "pager") return root;
        if (root.Children == null) return null;
        foreach (var c in root.Children)
        {
            var found = FindFirstPager(c);
            if (found != null) return found;
        }
        return null;
    }

    private static bool IsIndicatorDisabled(string? mode) =>
        string.Equals(mode, "none", StringComparison.OrdinalIgnoreCase)
        || string.Equals(mode, "hidden", StringComparison.OrdinalIgnoreCase)
        || string.Equals(mode, "off", StringComparison.OrdinalIgnoreCase);

    private static IndicatorStyleModel ResolveEffectiveIndicator(LayoutNodeModel node)
    {
        var raw = node.Indicator;
        var st = node.Style;
        var resolved = new IndicatorStyleModel
        {
            Mode = !string.IsNullOrWhiteSpace(raw?.Mode) ? raw!.Mode!.Trim().ToLowerInvariant() : "pills",
            Align = !string.IsNullOrWhiteSpace(raw?.Align) ? raw!.Align!.Trim().ToLowerInvariant()
                  : (!string.IsNullOrWhiteSpace(st?.TextAlign) ? st!.TextAlign!.Trim().ToLowerInvariant() : "center"),
            ActiveFillColor = raw?.ActiveFillColor,
            InactiveFillColor = raw?.InactiveFillColor,
            ActiveBorderColor = raw?.ActiveBorderColor,
            InactiveBorderColor = raw?.InactiveBorderColor,
            ActiveTextColor = raw?.ActiveTextColor,
            InactiveTextColor = raw?.InactiveTextColor,
            FontFamily = !string.IsNullOrWhiteSpace(raw?.FontFamily) ? raw!.FontFamily : st?.FontFamily,
            FontWeight = !string.IsNullOrWhiteSpace(raw?.FontWeight) ? raw!.FontWeight : "bold",
            FontItalic = raw?.FontItalic ?? false,
            TextSizePx = raw?.TextSizePx is > 0 ? raw.TextSizePx.Value : 13,
            CornerRadiusPx = raw?.CornerRadiusPx is >= 0 ? raw.CornerRadiusPx.Value : 12,
            BorderWidthPx = raw?.BorderWidthPx is >= 0 ? raw.BorderWidthPx.Value : 1,
            GapPx = raw?.GapPx is >= 0 ? raw.GapPx.Value : 10,
            PadXPx = raw?.PadXPx is >= 0 ? raw.PadXPx.Value : 14,
            PadYPx = raw?.PadYPx is >= 0 ? raw.PadYPx.Value : 4,
            DotWidthPx = raw?.DotWidthPx is > 0 ? raw.DotWidthPx.Value : 28,
            DotHeightPx = raw?.DotHeightPx is > 0 ? raw.DotHeightPx.Value : 10,
            ActiveDotWidthPx = raw?.ActiveDotWidthPx is >= 0 ? raw.ActiveDotWidthPx.Value : 0
        };

        if (raw == null && !node.IsPanel && st != null)
        {
            if (st.TitleSizePx is > 0) resolved.TextSizePx = st.TitleSizePx.Value;
            if (st.CornerRadiusPx is >= 0) resolved.CornerRadiusPx = st.CornerRadiusPx.Value;
            if (!string.IsNullOrWhiteSpace(st.TextColor)) resolved.ActiveTextColor = st.TextColor;
            if (!string.IsNullOrWhiteSpace(st.SubTextColor)) resolved.InactiveTextColor = st.SubTextColor;
            if (!string.IsNullOrWhiteSpace(st.FontWeight)) resolved.FontWeight = st.FontWeight;
            if (st.FontItalic == true) resolved.FontItalic = true;
        }
        return resolved;
    }

    private double EstimateIndicatorStripHeight(IndicatorStyleModel ind)
    {
        if (string.Equals(ind.Mode, "dots", StringComparison.OrdinalIgnoreCase))
        {
            int dh = ind.DotHeightPx ?? 10;
            return Math.Max(Sy(22), Sy(dh + 14));
        }
        double ts = ind.TextSizePx ?? 13;
        int py = ind.PadYPx ?? 4;
        return Math.Max(Sy(32), Smin(ts * 1.45) + Sy(py * 2 + 12));
    }

    private StackPanel? BuildIndicatorStrip(LayoutNodeModel styleSource, LayoutNodeModel pagerNode, IndicatorStyleModel ind, out HorizontalAlignment align, LayoutNodeModel? selectTarget = null)
    {
        align = ResolveTextAlignment(ind.Align, HorizontalAlignment.Center);
        var pages = pagerNode.Children;
        if (pages == null || pages.Count == 0 || IsIndicatorDisabled(ind.Mode)) return null;

        bool dotsMode = string.Equals(ind.Mode, "dots", StringComparison.OrdinalIgnoreCase);
        bool numbersMode = string.Equals(ind.Mode, "numbers", StringComparison.OrdinalIgnoreCase);

        Color fallbackAccent = ParseColor(styleSource.Style?.AccentColor, Color.FromRgb(56, 189, 248));
        Color activeBorder = ParseColor(ind.ActiveBorderColor, fallbackAccent);
        Color activeFill = !string.IsNullOrWhiteSpace(ind.ActiveFillColor)
            ? ParseColor(ind.ActiveFillColor, activeBorder)
            : (dotsMode ? activeBorder : Color.FromArgb(100, activeBorder.R, activeBorder.G, activeBorder.B));
        Color inactiveFill = ParseColor(ind.InactiveFillColor, Color.FromArgb(60, 30, 41, 59));
        Color inactiveBorder = ParseColor(ind.InactiveBorderColor, Color.FromArgb(90, 100, 116, 139));
        Color activeText = ParseColor(ind.ActiveTextColor, Colors.White);
        Color inactiveText = ParseColor(ind.InactiveTextColor, Color.FromRgb(203, 213, 225));

        FontFamily fontFam = workspace?.ResolveFontFamily(ind.FontFamily) ?? new FontFamily("Segoe UI");
        FontWeight fontWeight = ResolveFontWeight(ind.FontWeight, defaultBold: true);
        FontStyle fontStyle = ind.FontItalic == true ? FontStyles.Italic : FontStyles.Normal;
        double textSize = Smin(ind.TextSizePx ?? 13);
        double cornerR = Smin(ind.CornerRadiusPx ?? 12);
        double borderPx = (ind.BorderWidthPx ?? 1) > 0 ? Math.Max(1, Smin(ind.BorderWidthPx ?? 1)) : 0;
        double gap = Sx(ind.GapPx ?? 10);
        double padX = Sx(ind.PadXPx ?? 14);
        double padY = Sy(ind.PadYPx ?? 4);
        double dotW = Sx(ind.DotWidthPx ?? 28);
        double dotH = Sy(ind.DotHeightPx ?? 10);
        double activeDotW = (ind.ActiveDotWidthPx is > 0) ? Sx(ind.ActiveDotWidthPx.Value) : dotW;

        int activeIdx = Math.Clamp(GetPagerPageIndex(pagerNode.Id), 0, pages.Count - 1);
        var sp = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center
        };

        for (int i = 0; i < pages.Count; i++)
        {
            int targetIdx = i;
            bool isActive = (i == activeIdx);
            var chip = new Border
            {
                Margin = new Thickness(0, 0, i < pages.Count - 1 ? gap : 0, 0),
                CornerRadius = new CornerRadius(cornerR),
                Cursor = Cursors.Hand,
                Background = new SolidColorBrush(isActive ? activeFill : inactiveFill),
                BorderBrush = new SolidColorBrush(isActive ? activeBorder : inactiveBorder),
                BorderThickness = new Thickness(borderPx)
            };

            if (dotsMode)
            {
                chip.Width = Math.Max(2, isActive ? activeDotW : dotW);
                chip.Height = Math.Max(2, dotH);
            }
            else
            {
                string label = numbersMode
                    ? (i + 1).ToString()
                    : (!string.IsNullOrWhiteSpace(pages[i].PageTitle) ? pages[i].PageTitle! : $"Page {i + 1}");
                chip.Padding = new Thickness(padX, padY, padX, padY);
                chip.Child = new TextBlock
                {
                    Text = label,
                    Foreground = new SolidColorBrush(isActive ? activeText : inactiveText),
                    FontFamily = fontFam,
                    FontWeight = fontWeight,
                    FontStyle = fontStyle,
                    FontSize = textSize,
                    VerticalAlignment = VerticalAlignment.Center
                };
            }

            chip.MouseLeftButtonDown += (_, e) =>
            {
                e.Handled = true;
                if (selectTarget != null)
                {
                    selectedNode = selectTarget;
                    NodeSelected?.Invoke(selectTarget);
                }
                SetPagerPageIndex(pagerNode.Id, targetIdx);
            };
            sp.Children.Add(chip);
        }
        return sp;
    }

    private static string? FindFirstFrameInDir(string? dir)
    {
        if (string.IsNullOrEmpty(dir) || !Directory.Exists(dir)) return null;
        foreach (string f in Directory.GetFiles(dir))
        {
            string ext = System.IO.Path.GetExtension(f).ToLowerInvariant();
            if (ext is ".jpg" or ".jpeg" or ".png" or ".webp") return f;
        }
        return null;
    }

    private static List<BitmapImage> LoadFramesSequence(string dir, out double fps)
    {
        fps = 16;
        var list = new List<BitmapImage>();
        if (!Directory.Exists(dir)) return list;
        string mf = System.IO.Path.Combine(dir, "frames.json");
        var names = new List<string>();
        if (File.Exists(mf))
        {
            try
            {
                using var doc = JsonDocument.Parse(File.ReadAllText(mf));
                if (doc.RootElement.TryGetProperty("fps", out var fpsEl)) fps = fpsEl.GetDouble();
                if (doc.RootElement.TryGetProperty("frames", out var arrEl))
                {
                    foreach (var el in arrEl.EnumerateArray())
                        if (el.GetString() is string s) names.Add(System.IO.Path.Combine(dir, s));
                }
            }
            catch { }
        }
        if (names.Count == 0)
        {
            foreach (string f in Directory.GetFiles(dir))
            {
                string ext = System.IO.Path.GetExtension(f).ToLowerInvariant();
                if (ext is ".jpg" or ".jpeg" or ".png" or ".webp") names.Add(f);
            }
            names.Sort(StringComparer.OrdinalIgnoreCase);
        }
        foreach (string p in names)
        {
            if (File.Exists(p) && LoadFrozenBitmap(p) is BitmapImage frameBmp) list.Add(frameBmp);
        }
        return list;
    }

    private static BitmapImage? LoadFrozenBitmap(string path)
    {
        try
        {
            var bi = new BitmapImage();
            bi.BeginInit();
            bi.CacheOption = BitmapCacheOption.OnLoad;
            bi.UriSource = new Uri(path);
            bi.EndInit();
            bi.Freeze();
            return bi;
        }
        catch
        {
            return null;
        }
    }

    private static int[] NormalizeInsets(int[]? p)
    {
        if (p == null || p.Length == 0) return new[] { 0, 0, 0, 0 };
        if (p.Length == 1) return new[] { p[0], p[0], p[0], p[0] };
        if (p.Length == 2) return new[] { p[0], p[1], p[0], p[1] };
        return p.Length >= 4 ? p : new[] { 0, 0, 0, 0 };
    }
    private static Color ParseColor(string? hex, Color def) => ParseNullableColor(hex) ?? def;

    private static Color? ParseNullableColor(string? hex)
    {
        if (string.IsNullOrWhiteSpace(hex)) return null;
        try
        {
            string h = hex.Trim();
            if (!h.StartsWith("#")) h = "#" + h;
            return (Color)ColorConverter.ConvertFromString(h);
        }
        catch { return null; }
    }
}
