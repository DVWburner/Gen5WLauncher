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
        public Drawable newDrawable(android.content.res.Resources res) {
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
                    float xAxisRot = tok.nextFloat();
                    boolean largeArc = tok.nextArcFlag();
                    boolean sweep = tok.nextArcFlag();
                    float x = tok.nextFloat() + (rel ? cx : 0f);
                    float y = tok.nextFloat() + (rel ? cy : 0f);
                    arcToBeziers(path, cx, cy, rx, ry, xAxisRot, largeArc, sweep, x, y);
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

    private static void arcToBeziers(Path path, float x0, float y0, float rxIn, float ryIn,
            float angleDeg, boolean largeArcFlag, boolean sweepFlag, float x, float y) {
        if ((x0 == x && y0 == y)) {
            return;
        }
        float rx = Math.abs(rxIn);
        float ry = Math.abs(ryIn);
        if (rx <= 0.0001f || ry <= 0.0001f) {
            path.lineTo(x, y);
            return;
        }
        double phi = Math.toRadians(angleDeg % 360.0);
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);

        double dx2 = (x0 - x) * 0.5;
        double dy2 = (y0 - y) * 0.5;
        double x1p = cosPhi * dx2 + sinPhi * dy2;
        double y1p = -sinPhi * dx2 + cosPhi * dy2;

        double rxSq = (double) rx * rx;
        double rySq = (double) ry * ry;
        double x1pSq = x1p * x1p;
        double y1pSq = y1p * y1p;

        double radiiCheck = x1pSq / rxSq + y1pSq / rySq;
        if (radiiCheck > 1.0) {
            double scale = Math.sqrt(radiiCheck);
            rx = (float) (scale * rx);
            ry = (float) (scale * ry);
            rxSq = (double) rx * rx;
            rySq = (double) ry * ry;
        }

        double sign = (largeArcFlag == sweepFlag) ? -1.0 : 1.0;
        double sq = ((rxSq * rySq) - (rxSq * y1pSq) - (rySq * x1pSq))
                / ((rxSq * y1pSq) + (rySq * x1pSq));
        double coef = sign * Math.sqrt(Math.max(0.0, sq));
        double cxp = coef * ((rx * y1p) / ry);
        double cyp = coef * -((ry * x1p) / rx);

        double cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) * 0.5;
        double cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) * 0.5;

        double ux = (x1p - cxp) / rx;
        double uy = (y1p - cyp) / ry;
        double vx = (-x1p - cxp) / rx;
        double vy = (-y1p - cyp) / ry;

        double theta1 = Math.atan2(uy, ux);
        double deltaTheta = Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy);
        if (!sweepFlag && deltaTheta > 0) {
            deltaTheta -= Math.PI * 2.0;
        } else if (sweepFlag && deltaTheta < 0) {
            deltaTheta += Math.PI * 2.0;
        }

        int segments = Math.max(1, (int) Math.ceil(Math.abs(deltaTheta) / (Math.PI * 0.5)));
        double delta = deltaTheta / segments;
        double t = (8.0 / 3.0) * Math.sin(delta * 0.25) * Math.sin(delta * 0.25) / Math.sin(delta * 0.5);

        double curTheta = theta1;
        double cosT1 = Math.cos(curTheta);
        double sinT1 = Math.sin(curTheta);
        for (int s = 0; s < segments; s++) {
            double nextTheta = curTheta + delta;
            double cosT2 = Math.cos(nextTheta);
            double sinT2 = Math.sin(nextTheta);

            double ep1x = cosT1 - t * sinT1;
            double ep1y = sinT1 + t * cosT1;
            double ep2x = cosT2 + t * sinT2;
            double ep2y = sinT2 - t * cosT2;

            float c1x = (float) (cx + cosPhi * rx * ep1x - sinPhi * ry * ep1y);
            float c1y = (float) (cy + sinPhi * rx * ep1x + cosPhi * ry * ep1y);
            float c2x = (float) (cx + cosPhi * rx * ep2x - sinPhi * ry * ep2y);
            float c2y = (float) (cy + sinPhi * rx * ep2x + cosPhi * ry * ep2y);
            float ex = (s == segments - 1) ? x : (float) (cx + cosPhi * rx * cosT2 - sinPhi * ry * sinT2);
            float ey = (s == segments - 1) ? y : (float) (cy + sinPhi * rx * cosT2 + cosPhi * ry * sinT2);
            path.cubicTo(c1x, c1y, c2x, c2y, ex, ey);

            curTheta = nextTheta;
            cosT1 = cosT2;
            sinT1 = sinT2;
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

        boolean nextArcFlag() {
            if (!skipSeparators()) {
                return false;
            }
            char c = s.charAt(i);
            if (c == '0' || c == '1') {
                i++;
                return c == '1';
            }
            return nextFloat() != 0f;
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
            boolean seenExp = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' && !seenDot && !seenExp) {
                    seenDot = true;
                    i++;
                } else if ((c == 'e' || c == 'E') && !seenExp && i > start) {
                    seenExp = true;
                    i++;
                    if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
                        i++;
                    }
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
