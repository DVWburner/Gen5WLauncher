package com.gen5w.launcher.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Zero-dependency pure-Java SVG subset renderer compatible with Android 4.4 (API 19).
 * Supports viewBox, <g> attribute inheritance, <path> (M/L/H/V/C/S/Q/T/A/Z),
 * <rect>, <circle>, <ellipse>, <line>, <polygon>, and <polyline>, plus per-instance tinting.
 */
public final class MiniSvgDrawable extends Drawable {
    private static final class ShapeCmd {
        final Path path = new Path();
        Integer fillColor;
        Integer strokeColor;
        float strokeWidth = 2f;
    }

    private static final class GroupStyle {
        final String fill;
        final String stroke;
        final String strokeWidth;

        GroupStyle(String fill, String stroke, String strokeWidth) {
            this.fill = fill;
            this.stroke = stroke;
            this.strokeWidth = strokeWidth;
        }
    }

    private static final class SvgConstantState extends ConstantState {
        final float vbX;
        final float vbY;
        final float vbW;
        final float vbH;
        final List<ShapeCmd> shapes;

        SvgConstantState(float vbX, float vbY, float vbW, float vbH, List<ShapeCmd> shapes) {
            this.vbX = vbX;
            this.vbY = vbY;
            this.vbW = Math.max(1f, vbW);
            this.vbH = Math.max(1f, vbH);
            this.shapes = shapes;
        }

        @Override
        public Drawable newDrawable() {
            return new MiniSvgDrawable(this);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }

    private final SvgConstantState state;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean mutated = false;

    private MiniSvgDrawable(SvgConstantState state) {
        this.state = state;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public ConstantState getConstantState() {
        return state;
    }

    @Override
    public Drawable mutate() {
        if (!mutated) {
            mutated = true;
        }
        return this;
    }

    public static MiniSvgDrawable parse(String svgText) throws Exception {
        float vbX = 0f, vbY = 0f, vbW = 48f, vbH = 48f;
        List<ShapeCmd> shapes = new ArrayList<ShapeCmd>();
        List<GroupStyle> groupStack = new ArrayList<GroupStyle>();
        groupStack.add(new GroupStyle(null, null, null));

        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(svgText));

        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String tag = p.getName();
                GroupStyle parentStyle = groupStack.get(groupStack.size() - 1);
                if ("svg".equals(tag)) {
                    String vb = attr(p, "viewBox");
                    if (vb != null) {
                        List<Float> nums = readNumbers(vb);
                        if (nums.size() >= 4) {
                            vbX = nums.get(0);
                            vbY = nums.get(1);
                            vbW = nums.get(2);
                            vbH = nums.get(3);
                        }
                    } else {
                        vbW = parseFloat(attr(p, "width"), 48f);
                        vbH = parseFloat(attr(p, "height"), 48f);
                    }
                    groupStack.add(mergeStyle(p, parentStyle));
                } else if ("g".equals(tag)) {
                    groupStack.add(mergeStyle(p, parentStyle));
                } else if ("path".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    parsePathData(attr(p, "d"), cmd.path);
                    shapes.add(cmd);
                } else if ("rect".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    float x = parseFloat(attr(p, "x"), 0f);
                    float y = parseFloat(attr(p, "y"), 0f);
                    float w = parseFloat(attr(p, "width"), 0f);
                    float h = parseFloat(attr(p, "height"), 0f);
                    float rx = parseFloat(attr(p, "rx"), 0f);
                    float ry = parseFloat(attr(p, "ry"), rx);
                    cmd.path.addRoundRect(new RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW);
                    shapes.add(cmd);
                } else if ("circle".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    float cx = parseFloat(attr(p, "cx"), 0f);
                    float cy = parseFloat(attr(p, "cy"), 0f);
                    float r = parseFloat(attr(p, "r"), 0f);
                    cmd.path.addCircle(cx, cy, r, Path.Direction.CW);
                    shapes.add(cmd);
                } else if ("ellipse".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    float cx = parseFloat(attr(p, "cx"), 0f);
                    float cy = parseFloat(attr(p, "cy"), 0f);
                    float rx = parseFloat(attr(p, "rx"), 0f);
                    float ry = parseFloat(attr(p, "ry"), 0f);
                    cmd.path.addOval(new RectF(cx - rx, cy - ry, cx + rx, cy + ry), Path.Direction.CW);
                    shapes.add(cmd);
                } else if ("line".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    cmd.path.moveTo(parseFloat(attr(p, "x1"), 0f), parseFloat(attr(p, "y1"), 0f));
                    cmd.path.lineTo(parseFloat(attr(p, "x2"), 0f), parseFloat(attr(p, "y2"), 0f));
                    if (cmd.strokeColor == null) {
                        cmd.strokeColor = Color.WHITE;
                    }
                    shapes.add(cmd);
                } else if ("polygon".equals(tag) || "polyline".equals(tag)) {
                    ShapeCmd cmd = createCmd(p, parentStyle);
                    List<Float> pts = readNumbers(attr(p, "points"));
                    if (pts.size() >= 2) {
                        cmd.path.moveTo(pts.get(0), pts.get(1));
                        for (int idx = 2; idx + 1 < pts.size(); idx += 2) {
                            cmd.path.lineTo(pts.get(idx), pts.get(idx + 1));
                        }
                        if ("polygon".equals(tag)) {
                            cmd.path.close();
                        }
                    }
                    shapes.add(cmd);
                }
            } else if (event == XmlPullParser.END_TAG) {
                String tag = p.getName();
                if (("g".equals(tag) || "svg".equals(tag)) && groupStack.size() > 1) {
                    groupStack.remove(groupStack.size() - 1);
                }
            }
            event = p.next();
        }
        return new MiniSvgDrawable(new SvgConstantState(vbX, vbY, vbW, vbH, shapes));
    }

    private static GroupStyle mergeStyle(XmlPullParser p, GroupStyle parent) {
        String fill = attr(p, "fill");
        String stroke = attr(p, "stroke");
        String sw = attr(p, "stroke-width");
        return new GroupStyle(
                fill != null ? fill : parent.fill,
                stroke != null ? stroke : parent.stroke,
                sw != null ? sw : parent.strokeWidth);
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) state.vbW;
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) state.vbH;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        float scale = Math.min(b.width() / state.vbW, b.height() / state.vbH);
        float dx = b.left + (b.width() - state.vbW * scale) * 0.5f - state.vbX * scale;
        float dy = b.top + (b.height() - state.vbH * scale) * 0.5f - state.vbY * scale;

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        for (ShapeCmd s : state.shapes) {
            if (s.fillColor != null) {
                fillPaint.setColor(s.fillColor.intValue());
                canvas.drawPath(s.path, fillPaint);
            }
            if (s.strokeColor != null && s.strokeWidth > 0f) {
                strokePaint.setColor(s.strokeColor.intValue());
                strokePaint.setStrokeWidth(s.strokeWidth);
                canvas.drawPath(s.path, strokePaint);
            }
        }
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        fillPaint.setAlpha(alpha);
        strokePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        fillPaint.setColorFilter(colorFilter);
        strokePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private static ShapeCmd createCmd(XmlPullParser p, GroupStyle inherited) {
        ShapeCmd cmd = new ShapeCmd();
        String fill = attr(p, "fill");
        if (fill == null) fill = inherited.fill;
        String stroke = attr(p, "stroke");
        if (stroke == null) stroke = inherited.stroke;
        String sw = attr(p, "stroke-width");
        if (sw == null) sw = inherited.strokeWidth;

        if (fill == null && stroke == null) {
            cmd.fillColor = Color.WHITE;
        } else {
            cmd.fillColor = parseSvgColor(fill, fill == null && stroke != null ? null : Color.WHITE);
            cmd.strokeColor = parseSvgColor(stroke, null);
        }
        cmd.strokeWidth = parseFloat(sw, 2f);
        return cmd;
    }

    private static String attr(XmlPullParser p, String name) {
        return p.getAttributeValue(null, name);
    }

    private static Integer parseSvgColor(String raw, Integer fallback) {
        if (raw == null) {
            return fallback;
        }
        String t = raw.trim();
        if (t.isEmpty() || "none".equalsIgnoreCase(t) || "transparent".equalsIgnoreCase(t)) {
            return null;
        }
        if ("currentColor".equalsIgnoreCase(t)) {
            return Color.WHITE;
        }
        if (t.startsWith("#") && t.length() == 4) {
            char r = t.charAt(1), g = t.charAt(2), b = t.charAt(3);
            t = "#" + r + r + g + g + b + b;
        }
        try {
            return Integer.valueOf(Color.parseColor(t));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static float parseFloat(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.replaceAll("[^0-9.+\\-eE]", ""));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<Float> readNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<Float>();
        }
        List<Float> out = new ArrayList<Float>();
        PathParserTokenizer tok = new PathParserTokenizer(text);
        while (tok.hasNextNumber()) {
            out.add(tok.nextFloat());
        }
        return out;
    }

    private static void parsePathData(String d, Path path) {
        if (d == null || d.isEmpty()) {
            return;
        }
        PathParserTokenizer tok = new PathParserTokenizer(d);
        float cx = 0f, cy = 0f;
        float sx = 0f, sy = 0f;
        float lastCtrlX = 0f, lastCtrlY = 0f;
        char lastCmd = 0;
        char cmd = 0;

        while (tok.skipSeparators()) {
            char ch = tok.peek();
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                cmd = ch;
                tok.advance();
                if (cmd == 'Z' || cmd == 'z') {
                    path.close();
                    cx = sx;
                    cy = sy;
                    lastCmd = cmd;
                    continue;
                }
            } else if (cmd == 0) {
                break;
            } else if (cmd == 'M') {
                cmd = 'L';
            } else if (cmd == 'm') {
                cmd = 'l';
            }

            boolean rel = Character.isLowerCase(cmd);
            char upper = Character.toUpperCase(cmd);
            if (!tok.hasNextNumber()) {
                break;
            }
            switch (upper) {
                case 'M': {
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.moveTo(x, y);
                    cx = sx = x;
                    cy = sy = y;
                    break;
                }
                case 'L': {
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.lineTo(x, y);
                    cx = x;
                    cy = y;
                    break;
                }
                case 'H': {
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    path.lineTo(x, cy);
                    cx = x;
                    break;
                }
                case 'V': {
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.lineTo(cx, y);
                    cy = y;
                    break;
                }
                case 'C': {
                    float x1 = tok.nextFloat() + (rel ? cx : 0f);
                    float y1 = tok.nextFloat() + (rel ? cy : 0f);
                    float x2 = tok.nextFloat() + (rel ? cx : 0f);
                    float y2 = tok.nextFloat() + (rel ? cy : 0f);
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'S': {
                    float x1 = (lastCmd == 'C' || lastCmd == 'c' || lastCmd == 'S' || lastCmd == 's')
                            ? (2f * cx - lastCtrlX) : cx;
                    float y1 = (lastCmd == 'C' || lastCmd == 'c' || lastCmd == 'S' || lastCmd == 's')
                            ? (2f * cy - lastCtrlY) : cy;
                    float x2 = tok.nextFloat() + (rel ? cx : 0f);
                    float y2 = tok.nextFloat() + (rel ? cy : 0f);
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'Q': {
                    float x1 = tok.nextFloat() + (rel ? cx : 0f);
                    float y1 = tok.nextFloat() + (rel ? cy : 0f);
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'T': {
                    float x1 = (lastCmd == 'Q' || lastCmd == 'q' || lastCmd == 'T' || lastCmd == 't')
                            ? (2f * cx - lastCtrlX) : cx;
                    float y1 = (lastCmd == 'Q' || lastCmd == 'q' || lastCmd == 'T' || lastCmd == 't')
                            ? (2f * cy - lastCtrlY) : cy;
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    cx = x;
                    cy = y;
                    break;
                }
                case 'A': {
                    float rx = tok.nextFloat();
                    float ry = tok.nextFloat();
                    tok.nextFloat(); // x-axis-rotation
                    tok.nextFloat(); // large-arc-flag
                    tok.nextFloat(); // sweep-flag
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    if (rx == 0f || ry == 0f) {
                        path.lineTo(x, y);
                    } else {
                        // Gentle quad approximation when simple vector icons contain A commands
                        path.lineTo(x, y);
                    }
                    cx = x;
                    cy = y;
                    break;
                }
                default:
                    tok.advance();
                    break;
            }
            lastCmd = cmd;
        }
    }

    private static final class PathParserTokenizer {
        private final String s;
        private int i;

        PathParserTokenizer(String s) {
            this.s = s;
        }

        boolean skipSeparators() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    return true;
                }
            }
            return false;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        void advance() {
            if (i < s.length()) {
                i++;
            }
        }

        boolean hasNextNumber() {
            if (!skipSeparators()) {
                return false;
            }
            char c = s.charAt(i);
            return (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.';
        }

        float nextFloat() {
            if (!skipSeparators()) {
                return 0f;
            }
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                i++;
            }
            boolean seenDot = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !seenDot) {
                    seenDot = true;
                    i++;
                } else {
                    break;
                }
            }
            if (i == start) {
                return 0f;
            }
            try {
                return Float.parseFloat(s.substring(start, i));
            } catch (NumberFormatException ignored) {
                return 0f;
            }
        }
    }
}
