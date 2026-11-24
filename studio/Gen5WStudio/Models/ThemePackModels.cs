using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Gen5WStudio.Models;

public sealed class ThemePackModel
{
    [JsonPropertyName("formatVersion")]
    public int FormatVersion { get; set; } = 2;

    [JsonPropertyName("id")]
    public string Id { get; set; } = "custom-theme";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "My Custom EV Theme";

    [JsonPropertyName("author")]
    public string Author { get; set; } = "Gen5WStudio";

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0.0";

    [JsonPropertyName("revision")]
    public int Revision { get; set; } = 1;

    [JsonPropertyName("category")]
    public string Category { get; set; } = "EV & Telemetry";

    [JsonPropertyName("minAppVersion")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? MinAppVersion { get; set; }

    [JsonPropertyName("compatibleVersions")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<string>? CompatibleVersions { get; set; }

    [JsonPropertyName("createdAt")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? CreatedAt { get; set; }

    [JsonPropertyName("updatedAt")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? UpdatedAt { get; set; }

    [JsonPropertyName("description")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Description { get; set; }

    [JsonPropertyName("referenceWidth")]
    public int ReferenceWidth { get; set; } = 1920;

    [JsonPropertyName("referenceHeight")]
    public int ReferenceHeight { get; set; } = 720;

    [JsonPropertyName("tokens")]
    public ThemeTokensModel Tokens { get; set; } = new();

    [JsonPropertyName("background")]
    public BackgroundSpecModel Background { get; set; } = new();

    [JsonPropertyName("root")]
    public LayoutNodeModel Root { get; set; } = new()
    {
        Id = "root_shell",
        Type = "panel",
        Layout = "flex_col",
        GapPx = 14,
        PaddingPx = new[] { 26, 16, 26, 18 },
        Style = new WidgetStyleModel { Transparent = true }
    };

    [JsonPropertyName("pages")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public List<LayoutNodeModel>? Pages { get; set; }

    internal static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
    };

    public string ToJsonString() => JsonSerializer.Serialize(this, JsonOpts).Replace("\r\n", "\n") + "\n";

    public static ThemePackModel FromJson(string json)
    {
        var pack = JsonSerializer.Deserialize<ThemePackModel>(json, JsonOpts)
                   ?? throw new InvalidOperationException("Failed to deserialize layout.json");
        pack.Normalize();
        return pack;
    }

    public ThemePackModel DeepClone() => FromJson(ToJsonString());

    public void Normalize()
    {
        if (Revision < 1) Revision = 1;
        Category = string.IsNullOrWhiteSpace(Category) ? "EV & Telemetry" : Category.Trim();
        Tokens ??= new();
        Background ??= new();
        Background.NormalizeAliases();
        if (Pages is { Count: > 0 } && (Root == null || Root.Children == null || Root.Children.Count == 0))
        {
            Root = new LayoutNodeModel
            {
                Id = "root_pager",
                Type = "panel",
                Layout = "pager",
                ShowDots = Pages.Count > 1,
                Style = new WidgetStyleModel { Transparent = true },
                Children = new List<LayoutNodeModel>(Pages)
            };
        }
        Pages = null;
        Root ??= new() { Id = "root_shell", Type = "panel", Layout = "flex_col" };
        Root.NormalizeRecursive(isRoot: true);
    }
}

public sealed class ThemeTokensModel
{
    [JsonPropertyName("showSystemBar")]
    public bool ShowSystemBar { get; set; } = true;

    [JsonPropertyName("soundMode")]
    public string SoundMode { get; set; } = "system";

    [JsonPropertyName("speedUnit")]
    public string SpeedUnit { get; set; } = "kmh";

    [JsonPropertyName("tempUnit")]
    public string TempUnit { get; set; } = "F";

    [JsonPropertyName("cornerRadiusPx")]
    public int CornerRadiusPx { get; set; } = 22;

    [JsonPropertyName("accentColor")]
    public string AccentColor { get; set; } = "#FF38BDF8";

    [JsonPropertyName("okColor")]
    public string OkColor { get; set; } = "#FF10B981";

    [JsonPropertyName("warnColor")]
    public string WarnColor { get; set; } = "#FFF59E0B";

    [JsonPropertyName("dangerColor")]
    public string DangerColor { get; set; } = "#FFEF4444";
}

public sealed class BackgroundSpecModel
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "animated_aurora";

    [JsonPropertyName("primaryColor")]
    public string PrimaryColor { get; set; } = "#FF060B12";

    [JsonPropertyName("secondaryColor")]
    public string SecondaryColor { get; set; } = "#FF0D1E34";

    [JsonPropertyName("accentColor")]
    public string AccentColor { get; set; } = "#FF195F91";

    [JsonPropertyName("secondaryAccentColor")]
    public string SecondaryAccentColor { get; set; } = "#FF0E5A68";

    [JsonPropertyName("dimColor")]
    public string? DimColor { get; set; } = "#48060B12";

    [JsonPropertyName("bitmapAsset")]
    public string? BitmapAsset { get; set; }

    [JsonPropertyName("framesDir")]
    public string? FramesDir { get; set; }

    [JsonPropertyName("gifAsset")]
    public string? GifAsset { get; set; }

    [JsonPropertyName("videoAsset")]
    public string? VideoAsset { get; set; }

    [JsonPropertyName("fps")]
    public int? Fps { get; set; } = 16;

    [JsonPropertyName("speed")]
    public double? Speed { get; set; } = 1.0;

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? ExtensionData { get; set; }

    public void NormalizeAliases()
    {
        if (ExtensionData == null) return;
        if (string.IsNullOrWhiteSpace(BitmapAsset) && WidgetStyleModel.TryGetExtString(ExtensionData, "posterAsset", out string? poster))
            BitmapAsset = poster;
        if (string.IsNullOrWhiteSpace(FramesDir) && WidgetStyleModel.TryGetExtString(ExtensionData, "animAsset", out string? animDir))
            FramesDir = animDir;
        ExtensionData = null;
    }
}

public sealed class LayoutNodeModel : INotifyPropertyChanged
{
    private bool _isExpanded = true;
    private bool _isSelected;

    public event PropertyChangedEventHandler? PropertyChanged;

    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("type")]
    public string? Type { get; set; }

    [JsonPropertyName("kind")]
    public string? Kind { get; set; }

    [JsonPropertyName("layout")]
    public string? Layout { get; set; }

    [JsonPropertyName("pageTitle")]
    public string? PageTitle { get; set; }

    [JsonPropertyName("showDots")]
    public bool? ShowDots { get; set; }

    [JsonPropertyName("columns")]
    public int? Columns { get; set; }

    [JsonPropertyName("rows")]
    public int? Rows { get; set; }

    [JsonPropertyName("gapPx")]
    public int? GapPx { get; set; }

    [JsonPropertyName("paddingPx")]
    [JsonConverter(typeof(InsetsJsonConverter))]
    public int[]? PaddingPx { get; set; }

    [JsonPropertyName("bounds")]
    public double[]? Bounds { get; set; }

    [JsonPropertyName("width")]
    public int? Width { get; set; }

    [JsonPropertyName("height")]
    public int? Height { get; set; }

    [JsonPropertyName("flexWeight")]
    public double? FlexWeight { get; set; }

    [JsonPropertyName("colSpan")]
    public int? ColSpan { get; set; }

    [JsonPropertyName("rowSpan")]
    public int? RowSpan { get; set; }

    [JsonPropertyName("title")]
    public string? Title { get; set; }

    [JsonPropertyName("subtitle")]
    public string? Subtitle { get; set; }

    [JsonPropertyName("icon")]
    public string? Icon { get; set; }

    [JsonPropertyName("animIcon")]
    public string? AnimIcon { get; set; }

    [JsonPropertyName("badgeText")]
    public string? BadgeText { get; set; }

    [JsonPropertyName("telemetryKey")]
    public string? TelemetryKey { get; set; }

    [JsonPropertyName("unit")]
    public string? Unit { get; set; }

    [JsonPropertyName("minValue")]
    public double? MinValue { get; set; }

    [JsonPropertyName("maxValue")]
    public double? MaxValue { get; set; }

    [JsonPropertyName("orientation")]
    public string? Orientation { get; set; }

    [JsonPropertyName("showText")]
    public bool? ShowText { get; set; }

    [JsonPropertyName("wrapText")]
    public bool? WrapText { get; set; }

    [JsonPropertyName("maxLines")]
    public int? MaxLines { get; set; }

    [JsonPropertyName("scaleType")]
    public string? ScaleType { get; set; }

    [JsonPropertyName("bitmapAsset")]
    public string? BitmapAsset { get; set; }

    [JsonPropertyName("marginPx")]
    [JsonConverter(typeof(InsetsJsonConverter))]
    public int[]? MarginPx { get; set; }

    [JsonPropertyName("horizontalAlign")]
    public string? HorizontalAlign { get; set; }

    [JsonPropertyName("verticalAlign")]
    public string? VerticalAlign { get; set; }

    [JsonPropertyName("style")]
    public WidgetStyleModel? Style { get; set; }

    [JsonPropertyName("indicator")]
    public IndicatorStyleModel? Indicator { get; set; }

    [JsonPropertyName("animation")]
    public AnimationSpecModel? Animation { get; set; }

    [JsonPropertyName("action")]
    public ActionSpecModel? Action { get; set; }

    [JsonPropertyName("children")]
    public List<LayoutNodeModel>? Children { get; set; }

    [JsonIgnore]
    public bool IsPanel => "panel".Equals(Type, StringComparison.OrdinalIgnoreCase)
                           || !string.IsNullOrEmpty(Layout)
                           || Children != null;

    [JsonIgnore]
    public LayoutNodeModel? VirtualOwnerPager { get; set; }

    [JsonIgnore]
    private LayoutNodeModel? _virtualIndicatorNode;

    [JsonIgnore]
    public bool HasVisibleBuiltinIndicator =>
        IsPanel
        && string.Equals(Layout, "pager", StringComparison.OrdinalIgnoreCase)
        && (ShowDots ?? true)
        && (Children?.Count ?? 0) > 1
        && !string.Equals(Indicator?.Mode, "none", StringComparison.OrdinalIgnoreCase)
        && !string.Equals(Indicator?.Mode, "hidden", StringComparison.OrdinalIgnoreCase)
        && !string.Equals(Indicator?.Mode, "off", StringComparison.OrdinalIgnoreCase);

    public LayoutNodeModel? GetOrCreateVirtualIndicatorNode()
    {
        if (!HasVisibleBuiltinIndicator) return null;
        _virtualIndicatorNode ??= new LayoutNodeModel
        {
            Id = Id + "_indicator_bar",
            Kind = "page_indicator",
            VirtualOwnerPager = this
        };
        _virtualIndicatorNode.Id = Id + "_indicator_bar";
        _virtualIndicatorNode.VirtualOwnerPager = this;
        return _virtualIndicatorNode;
    }

    [JsonIgnore]
    public IEnumerable<LayoutNodeModel>? TreeChildren
    {
        get
        {
            if (Children == null) return null;
            var vInd = GetOrCreateVirtualIndicatorNode();
            if (vInd == null) return Children;
            var list = new List<LayoutNodeModel>(Children.Count + 1);
            list.AddRange(Children);
            list.Add(vInd);
            return list;
        }
    }

    [JsonIgnore]
    public string DisplayLabel
    {
        get
        {
            if (VirtualOwnerPager != null)
            {
                string modeTag = string.IsNullOrWhiteSpace(VirtualOwnerPager.Indicator?.Mode)
                    ? "pills"
                    : VirtualOwnerPager.Indicator!.Mode!;
                return $"[indicator] Page Bar ({modeTag})";
            }
            string tapTag = (Action != null && !string.IsNullOrWhiteSpace(Action.Type) && !"none".Equals(Action.Type, StringComparison.OrdinalIgnoreCase))
                ? " [Tap]"
                : "";
            if (IsPanel)
            {
                string title = !string.IsNullOrWhiteSpace(PageTitle) ? PageTitle!
                             : (!string.IsNullOrWhiteSpace(Title) ? Title! : Id);
                return $"[{Layout ?? "panel"}] {title}{tapTag}";
            }
            string k = Kind ?? "widget";
            string t = !string.IsNullOrWhiteSpace(Title) ? Title!
                     : (!string.IsNullOrWhiteSpace(TelemetryKey) ? TelemetryKey! : Id);
            if ("progress_bar".Equals(k, StringComparison.OrdinalIgnoreCase) && !string.IsNullOrWhiteSpace(TelemetryKey))
                t = TelemetryKey!;
            return $"{t} ({k}){tapTag}";
        }
    }

    [JsonIgnore]
    public bool IsExpanded
    {
        get => _isExpanded;
        set
        {
            if (_isExpanded != value)
            {
                _isExpanded = value;
                NotifyChanged(nameof(IsExpanded));
            }
        }
    }

    [JsonIgnore]
    public bool IsSelected
    {
        get => _isSelected;
        set
        {
            if (_isSelected != value)
            {
                _isSelected = value;
                NotifyChanged(nameof(IsSelected));
            }
        }
    }

    public void NotifyChanged(string propertyName)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));

    public void NotifyLabelChanged()
        => NotifyChanged(nameof(DisplayLabel));

    public void NormalizeRecursive(bool isRoot = false)
    {
        if (string.IsNullOrWhiteSpace(Id))
        {
            Id = (IsPanel ? "panel_" : "w_") + Guid.NewGuid().ToString("N")[..6];
        }
        Style ??= new WidgetStyleModel();
        Style.NormalizeAliases();
        Action?.NormalizeAliases();
        if (IsPanel || isRoot)
        {
            Type = "panel";
            Layout ??= "flex_col";
            Children ??= new List<LayoutNodeModel>();
            foreach (var child in Children)
            {
                child.NormalizeRecursive(false);
            }
        }
        else
        {
            if (string.IsNullOrWhiteSpace(Kind)
                && !string.IsNullOrWhiteSpace(Type)
                && !"widget".Equals(Type, StringComparison.OrdinalIgnoreCase))
            {
                Kind = Type;
            }
            Kind ??= "tile";
        }
    }

    public LayoutNodeModel DeepCloneWithFreshIds(HashSet<string>? existingIds = null)
    {
        string raw = JsonSerializer.Serialize(this, ThemePackModel.JsonOpts);
        var clone = JsonSerializer.Deserialize<LayoutNodeModel>(raw, ThemePackModel.JsonOpts)
                    ?? throw new InvalidOperationException("Failed to clone LayoutNodeModel");
        clone.NormalizeRecursive(false);
        AssignFreshIdsRecursive(clone, existingIds ?? new HashSet<string>(StringComparer.OrdinalIgnoreCase), isTopClone: true);
        return clone;
    }

    private static void AssignFreshIdsRecursive(LayoutNodeModel node, HashSet<string> existingIds, bool isTopClone)
    {
        string baseId = string.IsNullOrWhiteSpace(node.Id) ? (node.IsPanel ? "panel" : "w") : node.Id;
        string candidate = isTopClone ? $"{baseId}_copy" : $"{baseId}_{Guid.NewGuid().ToString("N")[..4]}";
        int n = 2;
        while (existingIds.Contains(candidate))
        {
            candidate = isTopClone ? $"{baseId}_copy{n++}" : $"{baseId}_{Guid.NewGuid().ToString("N")[..4]}";
        }
        node.Id = candidate;
        existingIds.Add(candidate);
        if (node.Children != null)
        {
            foreach (var ch in node.Children)
                AssignFreshIdsRecursive(ch, existingIds, isTopClone: false);
        }
    }
}

public sealed class InsetsJsonConverter : JsonConverter<int[]?>
{
    public override int[]? Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        if (reader.TokenType == JsonTokenType.Null) return null;
        if (reader.TokenType == JsonTokenType.Number && reader.TryGetInt32(out int single))
        {
            int v = Math.Max(0, single);
            return new[] { v, v, v, v };
        }
        if (reader.TokenType == JsonTokenType.StartArray)
        {
            var list = new List<int>(4);
            while (reader.Read() && reader.TokenType != JsonTokenType.EndArray)
            {
                if (reader.TokenType == JsonTokenType.Number)
                {
                    int v = reader.TryGetInt32(out int iv) ? iv : (int)Math.Round(reader.GetDouble());
                    list.Add(Math.Max(0, v));
                }
            }
            return list.Count switch
            {
                0 => null,
                1 => new[] { list[0], list[0], list[0], list[0] },
                2 => new[] { list[0], list[1], list[0], list[1] },
                3 => new[] { list[0], list[1], list[2], list[1] },
                _ => new[] { list[0], list[1], list[2], list[3] }
            };
        }
        reader.Skip();
        return null;
    }

    public override void Write(Utf8JsonWriter writer, int[]? value, JsonSerializerOptions options)
    {
        if (value == null)
        {
            writer.WriteNullValue();
            return;
        }
        writer.WriteStartArray();
        for (int i = 0; i < value.Length; i++)
            writer.WriteNumberValue(value[i]);
        writer.WriteEndArray();
    }
}

public sealed class WidgetStyleModel
{
    [JsonPropertyName("transparent")]
    public bool? Transparent { get; set; }

    [JsonPropertyName("bgColor")]
    public string? BgColor { get; set; }

    [JsonPropertyName("bgEndColor")]
    public string? BgEndColor { get; set; }

    [JsonPropertyName("gradientAngle")]
    public int? GradientAngle { get; set; }

    [JsonPropertyName("pressedColor")]
    public string? PressedColor { get; set; }

    [JsonPropertyName("borderColor")]
    public string? BorderColor { get; set; }

    [JsonPropertyName("accentColor")]
    public string? AccentColor { get; set; }

    [JsonPropertyName("textColor")]
    public string? TextColor { get; set; }

    [JsonPropertyName("subTextColor")]
    public string? SubTextColor { get; set; }

    [JsonPropertyName("iconTint")]
    public string? IconTint { get; set; }

    [JsonPropertyName("borderWidthPx")]
    public int? BorderWidthPx { get; set; }

    [JsonPropertyName("cornerRadiusPx")]
    public int? CornerRadiusPx { get; set; }

    [JsonPropertyName("iconSizePx")]
    public int? IconSizePx { get; set; }

    [JsonPropertyName("titleSizePx")]
    public double? TitleSizePx { get; set; }

    [JsonPropertyName("subtitleSizePx")]
    public double? SubtitleSizePx { get; set; }

    [JsonPropertyName("textAlign")]
    public string? TextAlign { get; set; }

    [JsonPropertyName("verticalAlign")]
    public string? VerticalAlign { get; set; }

    [JsonPropertyName("fontFamily")]
    public string? FontFamily { get; set; }

    [JsonPropertyName("fontWeight")]
    public string? FontWeight { get; set; }

    [JsonPropertyName("fontItalic")]
    public bool? FontItalic { get; set; }

    [JsonPropertyName("opacity")]
    public double? Opacity { get; set; }

    [JsonPropertyName("bgBitmapAsset")]
    public string? BgBitmapAsset { get; set; }

    [JsonPropertyName("bgAnimAsset")]
    public string? BgAnimAsset { get; set; }

    [JsonPropertyName("bgAnimFps")]
    public int? BgAnimFps { get; set; }

    [JsonPropertyName("bgDimAlpha")]
    public int? BgDimAlpha { get; set; }

    [JsonPropertyName("paddingPx")]
    [JsonConverter(typeof(InsetsJsonConverter))]
    public int[]? PaddingPx { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? ExtensionData { get; set; }

    public void NormalizeAliases()
    {
        if (ExtensionData == null) return;
        if (string.IsNullOrWhiteSpace(BgEndColor) && TryGetExtString(ExtensionData, "gradientEndColor", out string? gradEnd))
            BgEndColor = gradEnd;
        if (!TitleSizePx.HasValue && TryGetExtDouble(ExtensionData, "fontSizePx", out double fSize))
            TitleSizePx = fSize;
        if (string.IsNullOrWhiteSpace(BgAnimAsset) && TryGetExtString(ExtensionData, "animAsset", out string? animRef))
            BgAnimAsset = animRef;
        if (!BgAnimFps.HasValue && TryGetExtDouble(ExtensionData, "animFps", out double aFps))
            BgAnimFps = (int)Math.Round(aFps);
        ExtensionData = null;
    }

    internal static bool TryGetExtString(Dictionary<string, JsonElement> ext, string key, out string? value)
    {
        foreach (var kv in ext)
        {
            if (string.Equals(kv.Key, key, StringComparison.OrdinalIgnoreCase) && kv.Value.ValueKind == JsonValueKind.String)
            {
                value = kv.Value.GetString();
                return !string.IsNullOrWhiteSpace(value);
            }
        }
        value = null;
        return false;
    }

    internal static bool TryGetExtDouble(Dictionary<string, JsonElement> ext, string key, out double value)
    {
        foreach (var kv in ext)
        {
            if (string.Equals(kv.Key, key, StringComparison.OrdinalIgnoreCase) && kv.Value.ValueKind == JsonValueKind.Number && kv.Value.TryGetDouble(out value))
                return true;
        }
        value = 0;
        return false;
    }
}

public sealed class IndicatorStyleModel
{
    [JsonPropertyName("mode")]
    public string? Mode { get; set; }

    [JsonPropertyName("align")]
    public string? Align { get; set; }

    [JsonPropertyName("activeFillColor")]
    public string? ActiveFillColor { get; set; }

    [JsonPropertyName("inactiveFillColor")]
    public string? InactiveFillColor { get; set; }

    [JsonPropertyName("activeBorderColor")]
    public string? ActiveBorderColor { get; set; }

    [JsonPropertyName("inactiveBorderColor")]
    public string? InactiveBorderColor { get; set; }

    [JsonPropertyName("activeTextColor")]
    public string? ActiveTextColor { get; set; }

    [JsonPropertyName("inactiveTextColor")]
    public string? InactiveTextColor { get; set; }

    [JsonPropertyName("fontFamily")]
    public string? FontFamily { get; set; }

    [JsonPropertyName("fontWeight")]
    public string? FontWeight { get; set; }

    [JsonPropertyName("fontItalic")]
    public bool? FontItalic { get; set; }

    [JsonPropertyName("textSizePx")]
    public double? TextSizePx { get; set; }

    [JsonPropertyName("cornerRadiusPx")]
    public int? CornerRadiusPx { get; set; }

    [JsonPropertyName("borderWidthPx")]
    public int? BorderWidthPx { get; set; }

    [JsonPropertyName("gapPx")]
    public int? GapPx { get; set; }

    [JsonPropertyName("padXPx")]
    public int? PadXPx { get; set; }

    [JsonPropertyName("padYPx")]
    public int? PadYPx { get; set; }

    [JsonPropertyName("dotWidthPx")]
    public int? DotWidthPx { get; set; }

    [JsonPropertyName("dotHeightPx")]
    public int? DotHeightPx { get; set; }

    [JsonPropertyName("activeDotWidthPx")]
    public int? ActiveDotWidthPx { get; set; }
}

public sealed class AnimationSpecModel
{
    [JsonPropertyName("pressScale")]
    public double? PressScale { get; set; }

    [JsonPropertyName("enterDelayMs")]
    public int? EnterDelayMs { get; set; }

    [JsonPropertyName("enterDurationMs")]
    public int? EnterDurationMs { get; set; }

    [JsonPropertyName("idleEffect")]
    public string? IdleEffect { get; set; }

    [JsonPropertyName("idleSpeed")]
    public double? IdleSpeed { get; set; }

    [JsonPropertyName("idleIntensity")]
    public double? IdleIntensity { get; set; }
}

public sealed class ActionSpecModel
{
    [JsonPropertyName("type")]
    public string Type { get; set; } = "none";

    [JsonPropertyName("package")]
    public string? Package { get; set; }

    [JsonPropertyName("class")]
    public string? Class { get; set; }

    [JsonPropertyName("intentAction")]
    public string? IntentAction { get; set; }

    [JsonPropertyName("extraKey")]
    public string? ExtraKey { get; set; }

    [JsonPropertyName("extraType")]
    public string? ExtraType { get; set; }

    [JsonPropertyName("extraVal")]
    public string? ExtraVal { get; set; }

    [JsonPropertyName("extraIntKey")]
    public string? ExtraIntKey { get; set; }

    [JsonPropertyName("extraIntValue")]
    public int? ExtraIntValue { get; set; }

    [JsonPropertyName("fallbackPackage")]
    public string? FallbackPackage { get; set; }

    [JsonPropertyName("fallbackClass")]
    public string? FallbackClass { get; set; }

    [JsonPropertyName("cmd")]
    public string? Cmd { get; set; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? ExtensionData { get; set; }

    public void NormalizeAliases()
    {
        if (string.IsNullOrWhiteSpace(ExtraKey) && !string.IsNullOrWhiteSpace(ExtraIntKey))
        {
            ExtraKey = ExtraIntKey;
            ExtraType = "int";
            ExtraVal = (ExtraIntValue ?? 0).ToString();
        }
        if (ExtensionData == null) return;
        if (string.IsNullOrWhiteSpace(Package) && WidgetStyleModel.TryGetExtString(ExtensionData, "pkg", out string? pkg))
            Package = pkg;
        if (string.IsNullOrWhiteSpace(Class) && WidgetStyleModel.TryGetExtString(ExtensionData, "cls", out string? cls))
            Class = cls;
        if (string.IsNullOrWhiteSpace(IntentAction) && WidgetStyleModel.TryGetExtString(ExtensionData, "action", out string? act))
            IntentAction = act;
        if (string.IsNullOrWhiteSpace(Cmd)
            && (WidgetStyleModel.TryGetExtString(ExtensionData, "preset", out string? legCmd)
                || WidgetStyleModel.TryGetExtString(ExtensionData, "page", out legCmd)
                || WidgetStyleModel.TryGetExtString(ExtensionData, "tab", out legCmd)))
        {
            Cmd = legCmd;
        }
        ExtensionData = null;
    }
}
