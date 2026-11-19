using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Media;
using System.Xml.Linq;

namespace Gen5WStudio.Services;

/// <summary>
/// Renders ThemePack SVG icons cleanly to WPF DrawingImage with optional #AARRGGBB color tint,
/// mirroring KitKat MiniSvgDrawable behavior.
/// </summary>
public static class SvgPreviewRenderer
{
    public static ImageSource? RenderSvgFile(string svgFilePath, Color? tintColor)
    {
        if (string.IsNullOrWhiteSpace(svgFilePath) || !File.Exists(svgFilePath))
        {
            return null;
        }
        try
        {
            string xml = File.ReadAllText(svgFilePath);
            return RenderSvgXml(xml, tintColor);
        }
        catch
        {
            return null;
        }
    }

    public static ImageSource? RenderSvgXml(string svgXml, Color? tintColor)
    {
        if (string.IsNullOrWhiteSpace(svgXml))
        {
            return null;
        }
        try
        {
            var doc = XDocument.Parse(svgXml);
            var root = doc.Root;
            if (root == null) return null;

            double vbX = 0, vbY = 0, vbW = 48, vbH = 48;
            var vbAttr = root.Attribute("viewBox")?.Value;
            if (!string.IsNullOrWhiteSpace(vbAttr))
            {
                var nums = ReadNumbers(vbAttr);
                if (nums.Count >= 4)
                {
                    vbX = nums[0]; vbY = nums[1]; vbW = Math.Max(1, nums[2]); vbH = Math.Max(1, nums[3]);
                }
            }

            var group = new DrawingGroup();
            group.Children.Add(new GeometryDrawing(Brushes.Transparent, null, new RectangleGeometry(new Rect(vbX, vbY, vbW, vbH))));
            RenderChildren(root, group, null, null, 2.0, tintColor);

            var img = new DrawingImage(group);
            img.Freeze();
            return img;
        }
        catch
        {
            return null;
        }
    }

    private static void RenderChildren(XElement parent, DrawingGroup target,
        string? inheritedFill, string? inheritedStroke, double inheritedStrokeWidth, Color? tintColor)
    {
        foreach (var el in parent.Elements())
        {
            string name = el.Name.LocalName;
            string? fillAttr = el.Attribute("fill")?.Value ?? inheritedFill;
            string? strokeAttr = el.Attribute("stroke")?.Value ?? inheritedStroke;
            double sw = ParseNum(el.Attribute("stroke-width")?.Value, inheritedStrokeWidth);

            if (name == "g")
            {
                RenderChildren(el, target, fillAttr, strokeAttr, sw, tintColor);
                continue;
            }

            Geometry? geom = null;
            if (name == "path")
            {
                string? d = el.Attribute("d")?.Value;
                if (!string.IsNullOrWhiteSpace(d))
                {
                    geom = Geometry.Parse(d);
                }
            }
            else if (name == "rect")
            {
                double x = ParseNum(el.Attribute("x")?.Value, 0);
                double y = ParseNum(el.Attribute("y")?.Value, 0);
                double w = ParseNum(el.Attribute("width")?.Value, 0);
                double h = ParseNum(el.Attribute("height")?.Value, 0);
                double rx = ParseNum(el.Attribute("rx")?.Value, 0);
                geom = new RectangleGeometry(new Rect(x, y, w, h), rx, rx);
            }
            else if (name == "circle")
            {
                double cx = ParseNum(el.Attribute("cx")?.Value, 0);
                double cy = ParseNum(el.Attribute("cy")?.Value, 0);
                double r = ParseNum(el.Attribute("r")?.Value, 0);
                geom = new EllipseGeometry(new Point(cx, cy), r, r);
            }
            else if (name == "ellipse")
            {
                double cx = ParseNum(el.Attribute("cx")?.Value, 0);
                double cy = ParseNum(el.Attribute("cy")?.Value, 0);
                double rx = ParseNum(el.Attribute("rx")?.Value, 0);
                double ry = ParseNum(el.Attribute("ry")?.Value, 0);
                geom = new EllipseGeometry(new Point(cx, cy), rx, ry);
            }
            else if (name == "line")
            {
                double x1 = ParseNum(el.Attribute("x1")?.Value, 0);
                double y1 = ParseNum(el.Attribute("y1")?.Value, 0);
                double x2 = ParseNum(el.Attribute("x2")?.Value, 0);
                double y2 = ParseNum(el.Attribute("y2")?.Value, 0);
                geom = new LineGeometry(new Point(x1, y1), new Point(x2, y2));
                if (strokeAttr == null) strokeAttr = "#FFFFFF";
            }
            else if (name == "polygon" || name == "polyline")
            {
                var pts = ReadNumbers(el.Attribute("points")?.Value ?? "");
                if (pts.Count >= 4)
                {
                    var fig = new PathFigure { StartPoint = new Point(pts[0], pts[1]), IsClosed = name == "polygon" };
                    for (int i = 2; i + 1 < pts.Count; i += 2)
                    {
                        fig.Segments.Add(new LineSegment(new Point(pts[i], pts[i + 1]), true));
                    }
                    var pg = new PathGeometry();
                    pg.Figures.Add(fig);
                    geom = pg;
                }
            }

            if (geom == null) continue;

            Brush? fillBrush = ResolveBrush(fillAttr, fillAttr == null && strokeAttr != null ? null : Colors.White, tintColor);
            Color? strokeCol = ResolveColor(strokeAttr, null, tintColor);
            Pen? pen = (strokeCol != null && sw > 0) ? new Pen(new SolidColorBrush(strokeCol.Value), sw)
            {
                StartLineCap = PenLineCap.Round,
                EndLineCap = PenLineCap.Round,
                LineJoin = PenLineJoin.Round
            } : null;

            target.Children.Add(new GeometryDrawing(fillBrush, pen, geom));
        }
    }

    private static Brush? ResolveBrush(string? raw, Color? defColor, Color? tint)
    {
        var c = ResolveColor(raw, defColor, tint);
        return c == null ? null : new SolidColorBrush(c.Value);
    }

    private static Color? ResolveColor(string? raw, Color? defColor, Color? tint)
    {
        if (raw == null) return defColor.HasValue ? (tint ?? defColor) : null;
        string t = raw.Trim();
        if (t.Length == 0 || t.Equals("none", StringComparison.OrdinalIgnoreCase)
            || t.Equals("transparent", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }
        if (tint.HasValue) return tint.Value;
        if (t.Equals("currentColor", StringComparison.OrdinalIgnoreCase)) return Colors.White;
        try
        {
            if (t.StartsWith("#") && t.Length == 4)
            {
                t = $"#{t[1]}{t[1]}{t[2]}{t[2]}{t[3]}{t[3]}";
            }
            return (Color)ColorConverter.ConvertFromString(t);
        }
        catch
        {
            return defColor;
        }
    }

    private static double ParseNum(string? raw, double fallback)
    {
        if (string.IsNullOrWhiteSpace(raw)) return fallback;
        string clean = "";
        foreach (char c in raw)
        {
            if (char.IsDigit(c) || c == '.' || c == '-' || c == '+') clean += c;
        }
        return double.TryParse(clean, NumberStyles.Float, CultureInfo.InvariantCulture, out double v) ? v : fallback;
    }

    private static List<double> ReadNumbers(string raw)
    {
        var list = new List<double>();
        if (string.IsNullOrWhiteSpace(raw)) return list;
        string cur = "";
        foreach (char c in raw)
        {
            if (char.IsDigit(c) || c == '.' || (c == '-' && cur.Length == 0) || c == 'e' || c == 'E')
            {
                cur += c;
            }
            else if (c == '-' && cur.Length > 0 && (cur[^1] == 'e' || cur[^1] == 'E'))
            {
                cur += c;
            }
            else
            {
                if (cur.Length > 0 && double.TryParse(cur, NumberStyles.Float, CultureInfo.InvariantCulture, out double val))
                    list.Add(val);
                cur = (c == '-') ? "-" : "";
            }
        }
        if (cur.Length > 0 && double.TryParse(cur, NumberStyles.Float, CultureInfo.InvariantCulture, out double lastVal))
            list.Add(lastVal);
        return list;
    }
}
