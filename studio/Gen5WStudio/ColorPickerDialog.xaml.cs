using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Shapes;

namespace Gen5WStudio;

public partial class ColorPickerDialog : Window
{
    private readonly Action<string>? onPreview;
    private double hue;       // 0..360
    private double sat = 1.0; // 0..1
    private double val = 1.0; // 0..1
    private double alpha = 1.0; // 0..1
    private bool suppress;
    private bool draggingSv;
    private bool draggingHue;

    public string SelectedHex { get; private set; } = "#FF38BDF8";
    public bool IsCleared { get; private set; }

    private static readonly string[] PresetHexes =
    {
        "#FFFFFFFF", "#FFF1F5F9", "#FF94A3B8", "#FF38BDF8",
        "#FF10B981", "#FFF59E0B", "#FFEF4444", "#FFBD5338",
        "#FFD946EF", "#FF818CF8", "#D4111D2D", "#880F172A",
        "#00000000"
    };

    public ColorPickerDialog() : this("#FF38BDF8", null)
    {
    }

    public ColorPickerDialog(string initialHex, Action<string>? onLivePreview)
    {
        InitializeComponent();
        onPreview = onLivePreview;

        Color start = TryParseColor(initialHex, out Color parsed)
            ? parsed
            : Color.FromRgb(56, 189, 248);

        OldSwatchRect.Fill = new SolidColorBrush(start);
        SetFromColor(start, notifyPreview: false);
        BuildQuickPresets();
    }

    private void BuildQuickPresets()
    {
        foreach (string hex in PresetHexes)
        {
            if (!TryParseColor(hex, out Color c)) continue;
            var btn = new Button
            {
                Width = 24,
                Height = 22,
                Padding = new Thickness(0),
                Margin = new Thickness(0, 0, 5, 4),
                ToolTip = hex == "#00000000" ? "Transparent (#00000000)" : hex
            };
            var grid = new Grid();
            if (TryFindResource("CheckerBrush") is Brush chk)
                grid.Children.Add(new Rectangle { Fill = chk });
            grid.Children.Add(new Rectangle { Fill = new SolidColorBrush(c) });
            btn.Content = grid;
            btn.Click += (_, _) => SetFromColor(c, notifyPreview: true);
            PresetsPanel.Children.Add(btn);
        }
    }

    public static bool TryParseColor(string? raw, out Color color)
    {
        color = default;
        if (string.IsNullOrWhiteSpace(raw)) return false;
        try
        {
            string h = raw.Trim();
            if (!h.StartsWith('#')) h = "#" + h;
            color = (Color)ColorConverter.ConvertFromString(h);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static string FormatArgbHex(Color c) => $"#{c.A:X2}{c.R:X2}{c.G:X2}{c.B:X2}";

    public static void RgbToHsv(Color c, out double h, out double s, out double v)
    {
        double r = c.R / 255.0;
        double g = c.G / 255.0;
        double b = c.B / 255.0;
        double max = Math.Max(r, Math.Max(g, b));
        double min = Math.Min(r, Math.Min(g, b));
        double delta = max - min;

        v = max;
        s = max <= 0.0001 ? 0.0 : delta / max;

        if (delta <= 0.0001)
        {
            h = 0;
            return;
        }

        if (max == r) h = 60.0 * (((g - b) / delta) % 6.0);
        else if (max == g) h = 60.0 * (((b - r) / delta) + 2.0);
        else h = 60.0 * (((r - g) / delta) + 4.0);
        if (h < 0) h += 360.0;
    }

    public static Color HsvToColor(double alpha01, double hDeg, double s, double v)
    {
        double a = Math.Clamp(alpha01, 0.0, 1.0);
        double h = ((hDeg % 360.0) + 360.0) % 360.0;
        double sv = Math.Clamp(s, 0.0, 1.0);
        double vv = Math.Clamp(v, 0.0, 1.0);

        double c = vv * sv;
        double x = c * (1.0 - Math.Abs((h / 60.0) % 2.0 - 1.0));
        double m = vv - c;
        double r1 = 0, g1 = 0, b1 = 0;
        if (h < 60) { r1 = c; g1 = x; }
        else if (h < 120) { r1 = x; g1 = c; }
        else if (h < 180) { g1 = c; b1 = x; }
        else if (h < 240) { g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; b1 = c; }
        else { r1 = c; b1 = x; }

        return Color.FromArgb(
            (byte)Math.Round(a * 255.0),
            (byte)Math.Clamp(Math.Round((r1 + m) * 255.0), 0, 255),
            (byte)Math.Clamp(Math.Round((g1 + m) * 255.0), 0, 255),
            (byte)Math.Clamp(Math.Round((b1 + m) * 255.0), 0, 255));
    }

    private void SetFromColor(Color c, bool notifyPreview)
    {
        alpha = c.A / 255.0;
        RgbToHsv(c, out double parsedHue, out double parsedSat, out double parsedVal);
        if (parsedSat > 0.001) hue = parsedHue;
        sat = parsedSat;
        val = parsedVal;
        UpdateUiFromHsv(notifyPreview);
    }

    private void UpdateUiFromHsv(bool notifyPreview)
    {
        Color pureHue = HsvToColor(1.0, hue, 1.0, 1.0);
        Color current = HsvToColor(alpha, hue, sat, val);
        SelectedHex = FormatArgbHex(current);

        suppress = true;
        try
        {
            SvHueStop.Color = pureHue;
            AlphaBarStart.Color = Color.FromArgb(0, current.R, current.G, current.B);
            AlphaBarEnd.Color = Color.FromArgb(255, current.R, current.G, current.B);
            AlphaSlider.Value = Math.Round(alpha * 100.0);
            AlphaPctText.Text = $"{Math.Round(alpha * 100.0):0}%";

            NewSwatchRect.Fill = new SolidColorBrush(current);
            HexBox.Text = SelectedHex;
            RBox.Text = current.R.ToString(CultureInfo.InvariantCulture);
            GBox.Text = current.G.ToString(CultureInfo.InvariantCulture);
            BBox.Text = current.B.ToString(CultureInfo.InvariantCulture);

            double svW = SvCanvas.Width;
            double svH = SvCanvas.Height;
            Canvas.SetLeft(SvThumb, Math.Clamp(sat * svW, 0, svW) - SvThumb.Width * 0.5);
            Canvas.SetTop(SvThumb, Math.Clamp((1.0 - val) * svH, 0, svH) - SvThumb.Height * 0.5);

            double hueH = HueCanvas.Height;
            Canvas.SetTop(HueThumb, Math.Clamp((hue / 360.0) * hueH, 0, hueH) - HueThumb.Height * 0.5);
        }
        finally
        {
            suppress = false;
        }

        if (notifyPreview)
        {
            IsCleared = false;
            onPreview?.Invoke(SelectedHex);
        }
    }

    private void UpdateSvFromPoint(Point p)
    {
        sat = Math.Clamp(p.X / SvCanvas.Width, 0.0, 1.0);
        val = Math.Clamp(1.0 - (p.Y / SvCanvas.Height), 0.0, 1.0);
        UpdateUiFromHsv(notifyPreview: true);
    }

    private void UpdateHueFromPoint(Point p)
    {
        hue = Math.Clamp(p.Y / HueCanvas.Height, 0.0, 1.0) * 359.9;
        UpdateUiFromHsv(notifyPreview: true);
    }

    private void OnSvMouseDown(object sender, MouseButtonEventArgs e)
    {
        draggingSv = true;
        SvCanvas.CaptureMouse();
        UpdateSvFromPoint(e.GetPosition(SvCanvas));
    }

    private void OnSvMouseMove(object sender, MouseEventArgs e)
    {
        if (draggingSv) UpdateSvFromPoint(e.GetPosition(SvCanvas));
    }

    private void OnSvMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!draggingSv) return;
        draggingSv = false;
        SvCanvas.ReleaseMouseCapture();
    }

    private void OnHueMouseDown(object sender, MouseButtonEventArgs e)
    {
        draggingHue = true;
        HueCanvas.CaptureMouse();
        UpdateHueFromPoint(e.GetPosition(HueCanvas));
    }

    private void OnHueMouseMove(object sender, MouseEventArgs e)
    {
        if (draggingHue) UpdateHueFromPoint(e.GetPosition(HueCanvas));
    }

    private void OnHueMouseUp(object sender, MouseButtonEventArgs e)
    {
        if (!draggingHue) return;
        draggingHue = false;
        HueCanvas.ReleaseMouseCapture();
    }

    private void OnAlphaSliderChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (suppress || !IsLoaded) return;
        alpha = Math.Clamp(AlphaSlider.Value / 100.0, 0.0, 1.0);
        UpdateUiFromHsv(notifyPreview: true);
    }

    private void OnHexBoxKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            ApplyHexText();
        }
    }

    private void OnHexBoxApplied(object sender, RoutedEventArgs e)
    {
        if (!suppress) ApplyHexText();
    }

    private void ApplyHexText()
    {
        if (TryParseColor(HexBox.Text, out Color c))
        {
            SetFromColor(c, notifyPreview: true);
        }
        else
        {
            HexBox.Text = SelectedHex;
        }
    }

    private void OnRgbBoxKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            ApplyRgbBoxes();
        }
    }

    private void OnRgbBoxApplied(object sender, RoutedEventArgs e)
    {
        if (!suppress) ApplyRgbBoxes();
    }

    private void ApplyRgbBoxes()
    {
        if (int.TryParse(RBox.Text.Trim(), out int r)
            && int.TryParse(GBox.Text.Trim(), out int g)
            && int.TryParse(BBox.Text.Trim(), out int b))
        {
            Color c = Color.FromArgb(
                (byte)Math.Round(alpha * 255.0),
                (byte)Math.Clamp(r, 0, 255),
                (byte)Math.Clamp(g, 0, 255),
                (byte)Math.Clamp(b, 0, 255));
            SetFromColor(c, notifyPreview: true);
        }
    }

    private void OnClearClick(object sender, RoutedEventArgs e)
    {
        IsCleared = true;
        SelectedHex = "";
        DialogResult = true;
    }

    private void OnApplyClick(object sender, RoutedEventArgs e)
    {
        IsCleared = false;
        DialogResult = true;
    }
}
