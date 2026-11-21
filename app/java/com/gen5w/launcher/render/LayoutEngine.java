package com.gen5w.launcher.render;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.ipc.TelemetryModel;
import com.gen5w.launcher.model.ActionSpec;
import com.gen5w.launcher.model.IndicatorStyle;
import com.gen5w.launcher.model.LayoutNode;
import com.gen5w.launcher.model.PanelSpec;
import com.gen5w.launcher.model.ThemePack;
import com.gen5w.launcher.model.WidgetSpec;
import com.gen5w.launcher.model.WidgetStyle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Materializes a hierarchical ThemePack (freeform, flex_row, flex_col, grid, pager)
 * into hardware-accelerated Android Views scaled proportionally to the active viewport.
 */
public final class LayoutEngine {
    public interface ActionHandler {
        void onAction(View view, LayoutNode node, ActionSpec action);
    }

    public static Set<String> collectTelemetryKeys(ThemePack pack) {
        Set<String> out = new LinkedHashSet<String>();
        if (pack != null && pack.root != null) {
            collectKeysRecursive(pack.root, out);
        }
        return out;
    }

    private static void collectKeysRecursive(LayoutNode node, Set<String> out) {
        if (node instanceof PanelSpec) {
            PanelSpec p = (PanelSpec) node;
            for (LayoutNode child : p.children) {
                collectKeysRecursive(child, out);
            }
        } else if (node instanceof WidgetSpec) {
            WidgetSpec w = (WidgetSpec) node;
            if (w.telemetryKey != null && !w.telemetryKey.trim().isEmpty()) {
                out.add(TelemetryModel.canonicalKey(w.telemetryKey));
            }
            extractPlaceholderKeys(w.title, out);
            extractPlaceholderKeys(w.subtitle, out);
            extractPlaceholderKeys(w.badgeText, out);
        }
    }

    private static void extractPlaceholderKeys(String text, Set<String> out) {
        if (text == null || text.isEmpty()) return;
        int pos = 0;
        while (pos < text.length()) {
            int open = text.indexOf('{', pos);
            if (open < 0) break;
            int close = text.indexOf('}', open + 1);
            if (close < 0) break;
            String token = text.substring(open + 1, close).trim();
            if (!token.isEmpty() && !"value".equals(token)) {
                out.add(TelemetryModel.canonicalKey(token));
            }
            pos = close + 1;
        }
    }

    public static final class PageScopedAnim {
        final int pageIndex; // -1 = global / outside pager
        final WidgetAnimator.IdleHandle idleHandle;
        final InteractiveCardFrame cardFrame;
        final AnimatedMediaLayer mediaLayer;

        PageScopedAnim(int pageIndex, WidgetAnimator.IdleHandle idleHandle, InteractiveCardFrame cardFrame, AnimatedMediaLayer mediaLayer) {
            this.pageIndex = pageIndex;
            this.idleHandle = idleHandle;
            this.cardFrame = cardFrame;
            this.mediaLayer = mediaLayer;
        }

        void applyPaused(boolean paused) {
            if (idleHandle != null) idleHandle.setPaused(paused);
            if (cardFrame != null) cardFrame.setExternalPaused(paused);
            if (mediaLayer != null) mediaLayer.setExternalPaused(paused);
        }

        void release() {
            if (idleHandle != null) idleHandle.release();
            if (cardFrame != null) cardFrame.setExternalPaused(true);
            if (mediaLayer != null) mediaLayer.clear();
        }
    }

    public static final class Bindings {
        public final List<TextView> clockTimes = new ArrayList<TextView>();
        public final List<TextView> clockDates = new ArrayList<TextView>();
        public final List<BadgeBinding> telemetryBadges = new ArrayList<BadgeBinding>();
        public final List<DynamicTextBinding> dynamicTexts = new ArrayList<DynamicTextBinding>();
        public final List<ProgressBarBinding> progressBars = new ArrayList<ProgressBarBinding>();
        public final List<EvHeroBinding> evHeroes = new ArrayList<EvHeroBinding>();
        public final List<RingGaugeBinding> ringGauges = new ArrayList<RingGaugeBinding>();
        public final List<InteractiveCardFrame> evReactiveCards = new ArrayList<InteractiveCardFrame>();
        public final List<SwitcherBinding> switcherPills = new ArrayList<SwitcherBinding>();
        public final List<HorizontalPagerView> pagers = new ArrayList<HorizontalPagerView>();
        public final List<FrameLayout> pageIndicatorSlots = new ArrayList<FrameLayout>();
        public final List<PageScopedAnim> scopedAnims = new ArrayList<PageScopedAnim>();
        public TextView barToggleSub;
        public TextView soundToggleSub;
        public boolean preferMph = false;
        public boolean preferCelsius = false;
        private boolean animationsActive = true;
        private final SimpleDateFormat clockTimeFormat = new SimpleDateFormat("h:mm a", Locale.US);
        private final SimpleDateFormat clockDateFormat = new SimpleDateFormat("EEE, MMM d", Locale.US);

        public void setAnimationsActive(boolean active) {
            this.animationsActive = active;
            syncScopedAnimations();
            if (!active) {
                for (SwitcherBinding sb : switcherPills) {
                    sb.stopPulse();
                }
            }
        }

        public void syncScopedAnimations() {
            int activePage = pagers.isEmpty() ? 0 : pagers.get(0).getCurrentPage();
            for (PageScopedAnim a : scopedAnims) {
                boolean onVisiblePage = a.pageIndex < 0 || a.pageIndex == activePage;
                a.applyPaused(!(animationsActive && onVisiblePage));
            }
        }

        public void release() {
            animationsActive = false;
            for (PageScopedAnim a : scopedAnims) {
                a.release();
            }
            for (SwitcherBinding sb : switcherPills) {
                sb.stopPulse();
            }
        }

        public boolean navigateToPage(String target) {
            if (pagers.isEmpty()) {
                return false;
            }
            if (target == null || target.trim().isEmpty() || "next".equalsIgnoreCase(target)) {
                HorizontalPagerView p = pagers.get(0);
                p.setCurrentPage((p.getCurrentPage() + 1) % Math.max(1, p.getPageCount()), true);
                return true;
            }
            if ("prev".equalsIgnoreCase(target)) {
                HorizontalPagerView p = pagers.get(0);
                int c = Math.max(1, p.getPageCount());
                p.setCurrentPage((p.getCurrentPage() - 1 + c) % c, true);
                return true;
            }
            for (HorizontalPagerView p : pagers) {
                if (p.setCurrentPageByIdOrIndex(target, true)) {
                    return true;
                }
            }
            return false;
        }

        public void updateClock() {
            if (clockTimes.isEmpty() && clockDates.isEmpty()) {
                return;
            }
            Date now = new Date();
            String t = clockTimeFormat.format(now);
            String d = clockDateFormat.format(now).toUpperCase(Locale.US);
            for (TextView tv : clockTimes) setTextIfChanged(tv, t);
            for (TextView tv : clockDates) setTextIfChanged(tv, d);
        }

        public void updateTelemetry(TelemetryModel.Snapshot s) {
            for (BadgeBinding b : telemetryBadges) {
                setTextIfChanged(b.valueView, resolveTelemetryValue(b.spec.telemetryKey, b.spec.unit, s));
            }
            for (DynamicTextBinding dt : dynamicTexts) {
                if (dt.titleView != null) {
                    setTextIfChanged(dt.titleView, formatDynamicText(dt.spec.title, dt.spec.telemetryKey, dt.spec.unit, s, dt.titleBlankUsesValue));
                }
                if (dt.subView != null) {
                    setTextIfChanged(dt.subView, formatDynamicText(dt.spec.subtitle, dt.spec.telemetryKey, dt.spec.unit, s, dt.subBlankUsesValue));
                }
                if (dt.badgeView != null) {
                    setTextIfChanged(dt.badgeView, formatDynamicText(dt.spec.badgeText, dt.spec.telemetryKey, dt.spec.unit, s, false));
                }
            }
            for (ProgressBarBinding pb : progressBars) {
                String k = pb.spec.telemetryKey;
                String u = pb.spec.unit;
                float val = parseTelemetryNumeric(k, u, s, Float.NaN);
                double minV = pb.spec.minValue != null ? pb.spec.minValue.doubleValue() : defaultSensorMin(k, u, s);
                double maxV = pb.spec.maxValue != null ? pb.spec.maxValue.doubleValue() : defaultSensorMax(k, u, s);
                float pct = Float.isNaN(val) || maxV <= minV ? 0f : (float) ((val - minV) * 100.0 / (maxV - minV));
                String textOverlay = "";
                if (pb.spec.showText) {
                    if (pb.spec.title != null && !pb.spec.title.isEmpty()) {
                        textOverlay = formatDynamicText(pb.spec.title, k, u, s, true);
                    } else {
                        textOverlay = resolveTelemetryValue(k, u, s);
                    }
                }
                pb.bar.setTelemetry(pct, textOverlay);
            }
            for (RingGaugeBinding rg : ringGauges) {
                String k = (rg.spec.telemetryKey == null || rg.spec.telemetryKey.isEmpty()) ? "hv.soc" : rg.spec.telemetryKey;
                String u = rg.spec.unit;
                float val = parseTelemetryNumeric(k, u, s, Float.NaN);
                double minV = rg.spec.minValue != null ? rg.spec.minValue.doubleValue() : defaultSensorMin(k, u, s);
                double maxV = rg.spec.maxValue != null ? rg.spec.maxValue.doubleValue() : defaultSensorMax(k, u, s);
                float pct = Float.isNaN(val) || maxV <= minV ? -1f : (float) ((val - minV) * 100.0 / (maxV - minV));
                String label = resolveTelemetryValue(k, u, s);
                boolean isHv = "hv.soc".equals(TelemetryModel.canonicalKey(k));
                rg.gauge.setTelemetry(pct, label, isHv && s.isChargingActive());
            }
            for (InteractiveCardFrame cf : evReactiveCards) {
                int c = s.isChargingActive() ? 0xFF10B981 : (s.isReady() ? 0xFF38BDF8 : 0xFF64748B);
                cf.setDynamicGlowColor(c);
            }
            for (EvHeroBinding h : evHeroes) {
                float pct = s.parseHvSocPercent(-1f);
                setTextIfChanged(h.socValue, "--".equals(s.hvSoc) ? "--%" : (stripTrailingZero(s.hvSoc) + "%"));
                h.gaugeView.setProgress(pct < 0f ? 0f : pct, s.isChargingActive());
                String readyTxt;
                if (s.isChargingActive()) {
                    readyTxt = "CHARGING ACTIVE";
                } else if (s.isReady()) {
                    readyTxt = "EV READY";
                } else if (s.connected) {
                    readyTxt = "STANDBY";
                } else if (!s.error.isEmpty()) {
                    String msg = s.error.length() > 42 ? s.error.substring(0, 42) + "..." : s.error;
                    readyTxt = "HMG-API: " + msg;
                } else {
                    readyTxt = "HMG-API LINKING...";
                }
                setTextIfChanged(h.statusChip, readyTxt);
                String extStr = fmtTemp(s);
                String odoStr;
                if (preferMph) {
                    if ("--".equals(s.odometer)) odoStr = "-- mi";
                    else {
                        float mi = parseTelemetryNumeric("odometer_mi", "mi", s, 0f);
                        odoStr = stripTrailingZero(String.format(Locale.US, "%.0f", mi)) + " mi";
                    }
                } else {
                    odoStr = fmtUnit(s.odometer, " km");
                }
                setTextIfChanged(h.auxDetail, "12V AUX " + fmtUnit(s.aux12vSoc, "%")
                        + "   \u2022   EXT " + extStr
                        + "   \u2022   ODO " + odoStr);
            }
        }

        private static void setTextIfChanged(TextView tv, String text) {
            if (tv == null) {
                return;
            }
            String next = text == null ? "" : text;
            CharSequence cur = tv.getText();
            if (cur != null && next.contentEquals(cur)) {
                return;
            }
            tv.setText(next);
        }

        private static float parsePctSafe(String raw, float fallback) {
            try {
                if (raw == null || "--".equals(raw) || raw.isEmpty()) return fallback;
                return Math.max(0f, Math.min(100f, Float.parseFloat(raw)));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        public void updateSwitcher(SwitcherClient.Status st) {
            String ver = (st.appVersionName != null && !st.appVersionName.isEmpty()) ? (" \u2022 v" + st.appVersionName) : "";
            for (SwitcherBinding b : switcherPills) {
                if (!st.serviceReachable) {
                    setTextIfChanged(b.title, "SWITCHER OFFLINE");
                    setTextIfChanged(b.sub, "Manual / Stock Mode");
                    setCardAccent(b, 0xFF94A3B8);
                    b.stopPulse();
                } else if (st.deadmanRemainingSec > 0) {
                    setTextIfChanged(b.title, "TEST MODE: " + st.deadmanRemainingSec + "s");
                    setTextIfChanged(b.sub, "Tap to Confirm or Revert" + ver);
                    setCardAccent(b, 0xFFF59E0B);
                    if (animationsActive) {
                        b.startPulse();
                    } else {
                        b.stopPulse();
                    }
                } else if (!st.trippedReason.isEmpty()) {
                    setTextIfChanged(b.title, "SAFE MODE");
                    setTextIfChanged(b.sub, st.trippedReason + ver);
                    setCardAccent(b, 0xFFEF4444);
                    b.stopPulse();
                } else if (st.isCustomHome) {
                    setTextIfChanged(b.title, "DEFAULT HOME");
                    setTextIfChanged(b.sub, (st.homeEnabled ? "5-Layer Failsafe Armed" : "Active") + ver);
                    setCardAccent(b, 0xFF10B981);
                    b.stopPulse();
                } else {
                    setTextIfChanged(b.title, "STOCK HOME ACTIVE");
                    setTextIfChanged(b.sub, "Tap to Test / Enable Custom" + ver);
                    setCardAccent(b, 0xFF38BDF8);
                    b.stopPulse();
                }
            }
        }

        private static void setCardAccent(SwitcherBinding b, int borderColor) {
            if (b.hasAccent && b.lastAccentColor == borderColor) {
                return;
            }
            b.hasAccent = true;
            b.lastAccentColor = borderColor;
            b.card.setBackground(makeStateDrawable(b.spec.style, borderColor, b.borderPx, b.radiusPx));
        }

        private String formatDynamicText(String template, String primaryKey, String unitOverride, TelemetryModel.Snapshot s, boolean blankUsesPrimary) {
            String tpl = template == null ? "" : template;
            if (tpl.isEmpty()) {
                return blankUsesPrimary && primaryKey != null && !primaryKey.isEmpty()
                        ? resolveTelemetryValue(primaryKey, unitOverride, s)
                        : "";
            }
            String working = tpl;
            if (working.contains("{value}") && primaryKey != null && !primaryKey.isEmpty()) {
                working = working.replace("{value}", resolveTelemetryValue(primaryKey, unitOverride, s));
            }
            if (working.indexOf('{') < 0) {
                return working;
            }
            StringBuilder out = new StringBuilder(working.length() + 16);
            int pos = 0;
            while (pos < working.length()) {
                int open = working.indexOf('{', pos);
                if (open < 0) {
                    out.append(working, pos, working.length());
                    break;
                }
                int close = working.indexOf('}', open + 1);
                if (close < 0) {
                    out.append(working, pos, working.length());
                    break;
                }
                out.append(working, pos, open);
                String token = working.substring(open + 1, close).trim();
                if (!token.isEmpty()) {
                    boolean sameAsPrimary = primaryKey != null && !primaryKey.isEmpty()
                            && TelemetryModel.canonicalKey(token).equals(TelemetryModel.canonicalKey(primaryKey));
                    out.append(resolveTelemetryValue(token, sameAsPrimary ? unitOverride : "", s));
                }
                pos = close + 1;
            }
            return out.toString();
        }

        private boolean shouldUseMph(String rawKey, String unit) {
            if ("speed_mph".equals(rawKey) || "odometer_mi".equals(rawKey) || "hv.range_mi".equals(rawKey)) return true;
            if ("speed_kmh".equals(rawKey) || "odometer_km".equals(rawKey) || "hv.range_km".equals(rawKey)) return false;
            if (unit != null && !unit.trim().isEmpty()) {
                String u = unit.trim().toLowerCase(Locale.US);
                if (u.contains("mph") || u.equals("mi")) return true;
                if (u.contains("km")) return false;
            }
            return preferMph;
        }

        private boolean shouldPackTempUseFahrenheit(String rawKey, String unit) {
            if ("hv.battery_temp_min_f".equals(rawKey)) return true;
            if ("hv.battery_temp_min_c".equals(rawKey)) return false;
            if (unit != null && !unit.trim().isEmpty()) {
                String u = unit.trim().toUpperCase(Locale.US);
                if (u.contains("F")) return true;
                if (u.contains("C")) return false;
            }
            return !preferCelsius;
        }

        private boolean isClimateCelsius(String unit, TelemetryModel.Snapshot s) {
            if (unit != null && !unit.trim().isEmpty()) {
                String u = unit.trim().toUpperCase(Locale.US);
                if (u.contains("C")) return true;
                if (u.contains("F")) return false;
            }
            String tUnit = s != null ? s.getRaw("climate.temp_unit") : "--";
            if ("C".equalsIgnoreCase(tUnit)) return true;
            if ("F".equalsIgnoreCase(tUnit)) return false;
            return preferCelsius;
        }

        private double defaultSensorMin(String key, String unit, TelemetryModel.Snapshot s) {
            String k = TelemetryModel.canonicalKey(key);
            if ("hv.battery_temp_min".equals(k)) {
                return shouldPackTempUseFahrenheit(key, unit) ? 0.0 : -20.0;
            }
            if ("outside_temp".equals(k)) return (unit != null && unit.toUpperCase(Locale.US).contains("F")) ? -20.0 : -30.0;
            if ("outside_temp_f".equals(k)) return (unit != null && unit.toUpperCase(Locale.US).contains("C")) ? -30.0 : -20.0;
            if ("climate.driver_temp".equals(k) || "climate.passenger_temp".equals(k)) {
                return isClimateCelsius(unit, s) ? 17.0 : 62.0;
            }
            if ("steering.angle".equals(k)) return -540.0;
            return 0.0;
        }

        private double defaultSensorMax(String key, String unit, TelemetryModel.Snapshot s) {
            String k = TelemetryModel.canonicalKey(key);
            if ("hv.battery_temp_min".equals(k)) {
                return shouldPackTempUseFahrenheit(key, unit) ? 140.0 : 60.0;
            }
            if ("outside_temp".equals(k)) return (unit != null && unit.toUpperCase(Locale.US).contains("F")) ? 120.0 : 50.0;
            if ("outside_temp_f".equals(k)) return (unit != null && unit.toUpperCase(Locale.US).contains("C")) ? 50.0 : 120.0;
            if ("speed".equals(k)) return shouldUseMph(key, unit) ? 120.0 : 180.0;
            if ("hv.range".equals(k)) return shouldUseMph(key, unit) ? 350.0 : 550.0;
            if ("odometer".equals(k)) return shouldUseMph(key, unit) ? 100000.0 : 160000.0;
            if ("charging.remain_min".equals(k)) return 600.0;
            if ("climate.fan_speed".equals(k)) return 8.0;
            if ("climate.auto_level".equals(k)) return 3.0;
            if ("climate.vent_mode".equals(k)) return 5.0;
            if ("climate.driver_temp".equals(k) || "climate.passenger_temp".equals(k)) {
                return isClimateCelsius(unit, s) ? 27.0 : 82.0;
            }
            if ("audio.volume".equals(k)) return 45.0;
            if ("steering.angle".equals(k)) return 540.0;
            if ("energy.drivetrain_kw".equals(k)) return 150.0;
            if ("energy.climate_kw".equals(k)) return 10.0;
            if ("energy.care_kw".equals(k)) return 15.0;
            if ("energy.electronics_w".equals(k)) return 1500.0;
            if ("hv.conditioning".equals(k)) return 2.0;
            if ("conditioning.state".equals(k)) return 5.0;
            if (k.startsWith("tyre.") && k.endsWith("_psi")) return 55.0;
            if (k.startsWith("seat_heat.") || k.startsWith("seat_vent.")) return 3.0;
            if ("wheel_heat".equals(k) || "defogger".equals(k) || "gear.park".equals(k) || "parking_brake".equals(k)
                    || "charging".equals(k) || "hv.ready".equals(k) || "moving".equals(k)
                    || "hv.conditioning_active".equals(k) || "hv.conditioning_failure".equals(k)
                    || "hv.v2l_active".equals(k) || "driving.restricted".equals(k)
                    || "battery12v.saver".equals(k) || "battery12v.sensor_invalid".equals(k)
                    || "battery12v.sensor_error".equals(k) || "guide_lines".equals(k)
                    || "climate.on".equals(k) || "climate.auto".equals(k) || "climate.ac".equals(k)
                    || "climate.heat".equals(k) || "climate.rear_on".equals(k)
                    || "climate.rear_lock".equals(k) || "climate.air_clean".equals(k)
                    || "climate.recirc".equals(k) || "climate.sync".equals(k)
                    || "climate.driver_only".equals(k) || "climate.front_defrost".equals(k)
                    || "climate.smart_vent".equals(k) || "climate.auto_defog".equals(k)
                    || "climate.after_blow".equals(k) || "audio.muted".equals(k)
                    || "sunroof.open".equals(k) || "trunk.open".equals(k)
                    || "trunk.ptg_state".equals(k) || "hood.open".equals(k)
                    || "charge_door.open".equals(k)
                    || k.startsWith("door.") || k.startsWith("window.")
                    || (k.startsWith("tyre.") && k.endsWith("_status"))) {
                return 1.0;
            }
            return 100.0;
        }

        private float parseTelemetryNumeric(String key, String unitOverride, TelemetryModel.Snapshot s, float fallback) {
            if (key == null || key.isEmpty()) return fallback;
            String k = TelemetryModel.canonicalKey(key);
            String u = unitOverride == null ? "" : unitOverride.trim();
            String raw = s.getRaw(k);
            if ("--".equals(raw)) {
                return fallback;
            }
            if ("climate.driver_temp".equals(k) || "climate.passenger_temp".equals(k)) {
                if ("LO".equalsIgnoreCase(raw)) {
                    return (float) defaultSensorMin(key, u, s);
                }
                if ("HI".equalsIgnoreCase(raw)) {
                    return (float) defaultSensorMax(key, u, s);
                }
                if ("OFF".equalsIgnoreCase(raw)) {
                    return 0f;
                }
            }
            try {
                float val = Float.parseFloat(raw);
                if ("hv.battery_temp_min".equals(k) && shouldPackTempUseFahrenheit(key, u)) {
                    val = val * 9f / 5f + 32f;
                } else if ("outside_temp".equals(k) && u.toUpperCase(Locale.US).contains("F")) {
                    val = val * 9f / 5f + 32f;
                } else if ("outside_temp_f".equals(k) && u.toUpperCase(Locale.US).contains("C")) {
                    val = (val - 32f) * 5f / 9f;
                } else if ("speed".equals(k) && shouldUseMph(key, u)) {
                    val = val * 0.621371f;
                } else if ("odometer".equals(k) && shouldUseMph(key, u)) {
                    val = val * 0.621371f;
                } else if ("hv.range".equals(k) && shouldUseMph(key, u)) {
                    val = val * 0.621371f;
                }
                return val;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private String resolveTelemetryValue(String key, String unitOverride, TelemetryModel.Snapshot s) {
            if (key == null || key.trim().isEmpty()) return s.connected ? "OK" : "--";
            String rawKey = key.trim();
            String k = TelemetryModel.canonicalKey(rawKey);
            String u = unitOverride == null ? "" : unitOverride.trim();

            if ("hv.soc".equals(k)) return fmtUnit(s.hvSoc, u.isEmpty() ? "%" : u);
            if ("battery12v.soc".equals(k)) return fmtUnit(s.aux12vSoc, u.isEmpty() ? "%" : u);
            if ("hv.battery_temp_min".equals(k)) {
                boolean useF = shouldPackTempUseFahrenheit(rawKey, u);
                String defSuff = useF ? "\u00B0F" : "\u00B0C";
                String raw = s.getRaw(k);
                if ("--".equals(raw)) return "--" + (u.isEmpty() ? defSuff : u);
                float v = parseTelemetryNumeric(rawKey, u, s, Float.NaN);
                if (Float.isNaN(v)) return "--";
                return Math.round(v) + (u.isEmpty() ? defSuff : u);
            }
            if ("outside_temp_f".equals(k)) {
                if ("--".equals(s.outsideTempF)) return fmtTemp(s);
                return !u.isEmpty() && u.toUpperCase(Locale.US).contains("C")
                        ? Math.round(parseTelemetryNumeric(k, u, s, 0f)) + u
                        : stripTrailingZero(s.outsideTempF) + (u.isEmpty() ? "\u00B0F" : u);
            }
            if ("outside_temp".equals(k)) {
                if ("--".equals(s.outsideTempC)) return fmtTemp(s);
                return !u.isEmpty() && u.toUpperCase(Locale.US).contains("F")
                        ? Math.round(parseTelemetryNumeric(k, u, s, 0f)) + u
                        : stripTrailingZero(s.outsideTempC) + (u.isEmpty() ? "\u00B0C" : u);
            }
            if ("odometer".equals(k)) {
                boolean useMi = shouldUseMph(rawKey, u);
                String suff = !u.isEmpty() ? (" " + u) : (useMi ? " mi" : " km");
                if ("--".equals(s.odometer)) return "--" + suff;
                float v = parseTelemetryNumeric(rawKey, u, s, 0f);
                return stripTrailingZero(String.format(Locale.US, "%.0f", v)) + suff;
            }
            if ("speed".equals(k)) {
                boolean useMph = shouldUseMph(rawKey, u);
                String suff = !u.isEmpty() ? (" " + u) : (useMph ? " mph" : " km/h");
                if ("--".equals(s.speed)) return "--" + suff;
                float v = parseTelemetryNumeric(rawKey, u, s, 0f);
                return Math.round(v) + suff;
            }
            if ("gear.park".equals(k)) return !s.connected ? "--" : (s.isInPark() ? "PARK" : "DRIVE");
            if ("parking_brake".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : ("1".equals(r) ? "EPB ON" : "RELEASED");
            }
            if ("moving".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : ("1".equals(r) ? "MOVING" : "STOPPED");
            }
            if ("charging".equals(k)) return !s.connected ? "--" : (s.isChargingActive() ? "CHARGING" : "IDLE");
            if ("hv.ready".equals(k)) return !s.connected ? "--" : (s.isReady() ? "READY" : "STANDBY");
            if ("hv.conditioning".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : ("2".equals(r) ? "ON" : "OFF");
            }
            if ("hv.conditioning_active".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : ("1".equals(r) ? "HEATING" : "IDLE");
            }
            if ("wheel_heat".equals(k) || "defogger".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : ("1".equals(stripTrailingZero(r)) ? "ON" : "OFF");
            }
            if (k.startsWith("seat_heat.") || k.startsWith("seat_vent.")) {
                String r = s.getRaw(k);
                if ("--".equals(r)) return "--";
                String n = stripTrailingZero(r);
                if (!u.isEmpty()) return n + u;
                if ("0".equals(n)) return "OFF";
                if ("1".equals(n)) return "LOW";
                if ("2".equals(n)) return "MED";
                if ("3".equals(n)) return "HIGH";
                return n;
            }
            if ("driving.restricted".equals(k)) {
                return !s.connected ? "--" : ("1".equals(s.drivingRestricted) ? "LOCKED" : "OPEN");
            }
            if ("gear_speed".equals(k)) {
                if (!s.connected) return "--";
                if (s.isInPark()) return "PARK";
                if ("--".equals(s.speed)) return "DRIVE";
                if (preferMph) {
                    float mph = parseTelemetryNumeric("speed_mph", "mph", s, 0f);
                    return Math.round(mph) + " mph";
                }
                float kmh = parseTelemetryNumeric("speed_kmh", "km/h", s, Float.NaN);
                return Float.isNaN(kmh) ? (s.speed + " km/h") : (Math.round(kmh) + " km/h");
            }
            if ("pedal.accel".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? ("--" + (u.isEmpty() ? "%" : u)) : (stripTrailingZero(r) + (u.isEmpty() ? "%" : u));
            }
            if ("steering.angle".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? ("--" + (u.isEmpty() ? "\u00B0" : u)) : (stripTrailingZero(r) + (u.isEmpty() ? "\u00B0" : u));
            }
            if ("hv.range".equals(k)) {
                boolean useMi = shouldUseMph(rawKey, u);
                String suff = !u.isEmpty() ? (" " + u) : (useMi ? " mi" : " km");
                String raw = s.getRaw(k);
                if ("--".equals(raw)) return "--" + suff;
                float v = parseTelemetryNumeric(rawKey, u, s, Float.NaN);
                return Float.isNaN(v) ? (raw + suff) : (Math.round(v) + suff);
            }
            if ("charging.remain_min".equals(k)) {
                return fmtDurationMin(s.getRaw(k), u);
            }
            if ("climate.driver_temp".equals(k) || "climate.passenger_temp".equals(k)) {
                String r = s.getRaw(k);
                if ("--".equals(r)) return "--";
                if ("OFF".equalsIgnoreCase(r) || "LO".equalsIgnoreCase(r) || "HI".equalsIgnoreCase(r)) {
                    return r.toUpperCase(Locale.US);
                }
                if (!u.isEmpty()) return stripTrailingZero(r) + u;
                String tUnit = s.getRaw("climate.temp_unit");
                String deg = "C".equalsIgnoreCase(tUnit) ? "\u00B0C"
                        : ("F".equalsIgnoreCase(tUnit) ? "\u00B0F" : (preferCelsius ? "\u00B0C" : "\u00B0F"));
                return stripTrailingZero(r) + deg;
            }
            if ("climate.fan_speed".equals(k)) {
                String r = s.getRaw(k);
                if ("--".equals(r)) return "--";
                String n = stripTrailingZero(r);
                if (!u.isEmpty()) return n + u;
                return "0".equals(n) ? "OFF" : ("Lvl " + n);
            }
            if ("climate.auto_level".equals(k)) {
                String r = s.getRaw(k);
                if ("--".equals(r)) return "--";
                String n = stripTrailingZero(r);
                if (!u.isEmpty()) return n + u;
                if ("0".equals(n)) return "OFF";
                if ("1".equals(n)) return "LOW";
                if ("2".equals(n)) return "MED";
                if ("3".equals(n)) return "HIGH";
                return n;
            }
            if ("climate.on".equals(k) || "climate.auto".equals(k) || "climate.ac".equals(k)
                    || "climate.heat".equals(k) || "climate.rear_on".equals(k)
                    || "climate.rear_lock".equals(k) || "climate.air_clean".equals(k)
                    || "climate.recirc".equals(k) || "climate.sync".equals(k)
                    || "climate.driver_only".equals(k) || "climate.front_defrost".equals(k)
                    || "climate.smart_vent".equals(k) || "climate.auto_defog".equals(k)
                    || "climate.after_blow".equals(k) || "guide_lines".equals(k)
                    || "battery12v.saver".equals(k) || "hv.v2l_active".equals(k)) {
                return fmtBinaryFlag(s.getRaw(k), "ON", "OFF");
            }
            if ("climate.vent_mode".equals(k)) {
                return fmtVentMode(s.getRaw(k));
            }
            if ("door.any_open".equals(k)) {
                return fmtZeroFlag(s.getRaw(k), "ALL CLOSED", "DOOR OPEN");
            }
            if ("door.lock_state".equals(k) || k.startsWith("door.lock.")) {
                return fmtBinaryFlag(s.getRaw(k), "LOCKED", "UNLOCKED");
            }
            if ((k.startsWith("door.") && !k.startsWith("door.lock"))
                    || "trunk.open".equals(k) || "trunk.ptg_state".equals(k)
                    || "hood.open".equals(k) || "charge_door.open".equals(k)) {
                return fmtZeroFlag(s.getRaw(k), "CLOSED", "OPEN");
            }
            if ("audio.volume".equals(k)) {
                String r = s.getRaw(k);
                if ("--".equals(r)) return "--";
                return u.isEmpty() ? stripTrailingZero(r) : (stripTrailingZero(r) + u);
            }
            if ("audio.muted".equals(k)) {
                return fmtBinaryFlag(s.getRaw(k), "MUTED", "ACTIVE");
            }
            if (k.startsWith("tyre.") && k.endsWith("_status")) {
                return fmtZeroFlag(s.getRaw(k), "OK", "LOW");
            }
            if (k.startsWith("window.") || "sunroof.open".equals(k)) {
                return fmtZeroFlag(s.getRaw(k), "CLOSED", "OPEN");
            }
            if ("energy.drivetrain_kw".equals(k) || "energy.climate_kw".equals(k) || "energy.care_kw".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : (stripTrailingZero(r) + (u.isEmpty() ? " kW" : (" " + u)));
            }
            if ("energy.electronics_w".equals(k)) {
                String r = s.getRaw(k);
                return "--".equals(r) ? "--" : (stripTrailingZero(r) + (u.isEmpty() ? " W" : (" " + u)));
            }
            String generic = s.getRaw(k);
            if (!"--".equals(generic)) {
                return u.isEmpty() ? stripTrailingZero(generic) : (stripTrailingZero(generic) + u);
            }
            return s.connected ? "--" : "--";
        }

        private static String fmtBinaryFlag(String r, String oneLabel, String zeroLabel) {
            if ("--".equals(r)) return "--";
            return "1".equals(stripTrailingZero(r)) ? oneLabel : zeroLabel;
        }

        private static String fmtZeroFlag(String r, String zeroLabel, String nonZeroLabel) {
            if ("--".equals(r)) return "--";
            return "0".equals(stripTrailingZero(r)) ? zeroLabel : nonZeroLabel;
        }

        private static String fmtVentMode(String r) {
            if ("--".equals(r)) return "--";
            String n = stripTrailingZero(r);
            if ("0".equals(n)) return "AUTO";
            if ("1".equals(n)) return "FACE";
            if ("2".equals(n)) return "BI-LVL";
            if ("3".equals(n)) return "FLOOR";
            if ("4".equals(n)) return "FLR+DEF";
            if ("5".equals(n) || "10".equals(n)) return "DEFROST";
            return "M" + n;
        }

        private static String fmtDurationMin(String r, String unit) {
            if ("--".equals(r)) return "--";
            if (!unit.isEmpty()) return stripTrailingZero(r) + unit;
            try {
                int mins = Math.round(Float.parseFloat(r));
                if (mins <= 0) return "--";
                if (mins >= 60) {
                    int h = mins / 60;
                    int m = mins % 60;
                    return m == 0 ? (h + "h") : (h + "h " + m + "m");
                }
                return mins + "m";
            } catch (NumberFormatException ignored) {
                return r;
            }
        }

        private static String fmtUnit(String v, String unit) {
            return "--".equals(v) ? ("--" + unit) : (stripTrailingZero(v) + unit);
        }

        private String fmtTemp(TelemetryModel.Snapshot s) {
            if (preferCelsius) {
                if (!"--".equals(s.outsideTempC)) return stripTrailingZero(s.outsideTempC) + "\u00B0C";
                if (!"--".equals(s.outsideTempF)) {
                    float c = parseTelemetryNumeric("outside_temp_f", "C", s, 0f);
                    return Math.round(c) + "\u00B0C";
                }
                return "--\u00B0C";
            }
            if (!"--".equals(s.outsideTempF)) return stripTrailingZero(s.outsideTempF) + "\u00B0F";
            if (!"--".equals(s.outsideTempC)) {
                float f = parseTelemetryNumeric("outside_temp", "F", s, 0f);
                return Math.round(f) + "\u00B0F";
            }
            return "--\u00B0F";
        }

        private static String stripTrailingZero(String raw) {
            return raw.endsWith(".0") ? raw.substring(0, raw.length() - 2) : raw;
        }
    }

    public static final class BadgeBinding {
        final WidgetSpec spec;
        final TextView valueView;

        BadgeBinding(WidgetSpec spec, TextView valueView) {
            this.spec = spec;
            this.valueView = valueView;
        }
    }

    public static final class DynamicTextBinding {
        final WidgetSpec spec;
        final TextView titleView;
        final TextView subView;
        final TextView badgeView;
        final boolean titleBlankUsesValue;
        final boolean subBlankUsesValue;

        DynamicTextBinding(WidgetSpec spec, TextView titleView, TextView subView, boolean titleBlankUsesValue) {
            this(spec, titleView, subView, null, titleBlankUsesValue, false);
        }

        DynamicTextBinding(WidgetSpec spec, TextView titleView, TextView subView, TextView badgeView,
                boolean titleBlankUsesValue, boolean subBlankUsesValue) {
            this.spec = spec;
            this.titleView = titleView;
            this.subView = subView;
            this.badgeView = badgeView;
            this.titleBlankUsesValue = titleBlankUsesValue;
            this.subBlankUsesValue = subBlankUsesValue;
        }
    }

    public static final class ProgressBarBinding {
        final WidgetSpec spec;
        final LinearGaugeView bar;

        ProgressBarBinding(WidgetSpec spec, LinearGaugeView bar) {
            this.spec = spec;
            this.bar = bar;
        }
    }

    public static final class RingGaugeBinding {
        final WidgetSpec spec;
        final CircularRingGaugeView gauge;

        RingGaugeBinding(WidgetSpec spec, CircularRingGaugeView gauge) {
            this.spec = spec;
            this.gauge = gauge;
        }
    }

    public static final class EvHeroBinding {
        final TextView socValue;
        final TextView statusChip;
        final TextView auxDetail;
        final SocGaugeView gaugeView;

        EvHeroBinding(TextView socValue, TextView statusChip, TextView auxDetail, SocGaugeView gaugeView) {
            this.socValue = socValue;
            this.statusChip = statusChip;
            this.auxDetail = auxDetail;
            this.gaugeView = gaugeView;
        }
    }

    public static final class SwitcherBinding {
        final View card;
        final WidgetSpec spec;
        final TextView title;
        final TextView sub;
        final int borderPx;
        final float radiusPx;
        boolean hasAccent = false;
        int lastAccentColor = 0;
        ObjectAnimator pulse;

        SwitcherBinding(View card, WidgetSpec spec, TextView title, TextView sub, int borderPx, float radiusPx) {
            this.card = card;
            this.spec = spec;
            this.title = title;
            this.sub = sub;
            this.borderPx = borderPx;
            this.radiusPx = radiusPx;
        }

        void startPulse() {
            if (pulse == null) {
                pulse = WidgetAnimator.createPulseAnimator(card);
                pulse.start();
            }
        }

        void stopPulse() {
            if (pulse != null) {
                pulse.cancel();
                pulse = null;
                card.setAlpha(1f);
            }
        }
    }

    private final Context context;
    private final ThemePack pack;
    private final AssetResolver assets;
    private final float scaleX;
    private final float scaleY;
    private final float scaleMin;
    private final ActionHandler handler;
    private final Bindings bindings = new Bindings();
    private int currentBuildPageIndex = -1;

    private LayoutEngine(Context context, ThemePack pack, AssetResolver assets,
            int viewportW, int viewportH, ActionHandler handler) {
        this.context = context;
        this.pack = pack;
        this.assets = assets;
        this.scaleX = viewportW / (float) pack.referenceWidth;
        this.scaleY = viewportH / (float) pack.referenceHeight;
        this.scaleMin = Math.min(scaleX, scaleY);
        this.handler = handler;
        this.bindings.preferMph = pack.tokens != null && "mph".equalsIgnoreCase(pack.tokens.speedUnit);
        this.bindings.preferCelsius = pack.tokens != null && "c".equalsIgnoreCase(pack.tokens.tempUnit);
    }

    private int effectiveCornerRadiusPx(WidgetStyle st) {
        if (st != null && st.hasCornerRadiusOverride) {
            return st.cornerRadiusPx;
        }
        return pack != null && pack.tokens != null ? pack.tokens.cornerRadiusPx : (st != null ? st.cornerRadiusPx : 20);
    }

    private int effectiveAccentColor(WidgetStyle st) {
        if (st != null && st.hasAccentColorOverride) {
            return st.accentColor;
        }
        return pack != null && pack.tokens != null ? pack.tokens.accentColor : (st != null ? st.accentColor : 0xFF38BDF8);
    }

    private void registerIdleAnim(WidgetAnimator.IdleHandle handle) {
        if (handle != null) {
            bindings.scopedAnims.add(new PageScopedAnim(currentBuildPageIndex, handle, null, null));
        }
    }

    private void registerCardFrameAnim(InteractiveCardFrame frame) {
        if (frame != null) {
            bindings.scopedAnims.add(new PageScopedAnim(currentBuildPageIndex, null, frame, null));
        }
    }

    private void registerMediaLayerAnim(AnimatedMediaLayer layer) {
        if (layer != null) {
            bindings.scopedAnims.add(new PageScopedAnim(currentBuildPageIndex, null, null, layer));
        }
    }

    public static View build(Context context, ThemePack pack, AssetResolver assets,
            int viewportW, int viewportH, ActionHandler handler, Bindings[] outBindings) {
        LayoutEngine engine = new LayoutEngine(context, pack, assets, viewportW, viewportH, handler);
        View rootView = engine.buildNode(pack.root, false);
        engine.populateStandalonePageIndicators();
        engine.bindings.syncScopedAnimations();
        rootView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (outBindings != null && outBindings.length > 0) {
            outBindings[0] = engine.bindings;
        }
        return rootView;
    }

    private static IndicatorStyle resolveEffectiveIndicator(LayoutNode node) {
        IndicatorStyle ind = (node != null && node.indicator != null)
                ? node.indicator.copy()
                : new IndicatorStyle();
        if (node != null && node.style != null) {
            if (ind.align.isEmpty() && node.style.textAlign != null) {
                ind.align = node.style.textAlign;
            }
            if (ind.fontFamily.isEmpty() && node.style.fontFamily != null) {
                ind.fontFamily = node.style.fontFamily;
            }
            if (node.indicator == null && node instanceof WidgetSpec) {
                if (node.style.titleSizePx > 0 && node.style.titleSizePx != 26f) {
                    ind.textSizePx = node.style.titleSizePx;
                }
                if (node.style.cornerRadiusPx != 20) {
                    ind.cornerRadiusPx = node.style.cornerRadiusPx;
                }
                if (node.style.textColor != 0xFFF1F5F9) {
                    ind.activeTextColor = node.style.textColor;
                }
                if (node.style.subTextColor != 0xFF94A3B8) {
                    ind.inactiveTextColor = node.style.subTextColor;
                }
                if ("normal".equalsIgnoreCase(node.style.fontWeight)) {
                    ind.fontWeight = "normal";
                }
                ind.fontItalic = node.style.fontItalic;
            }
        }
        return ind;
    }

    private void populateStandalonePageIndicators() {
        if (bindings.pagers.isEmpty() || bindings.pageIndicatorSlots.isEmpty()) {
            return;
        }
        HorizontalPagerView mainPager = bindings.pagers.get(0);
        for (FrameLayout slot : bindings.pageIndicatorSlots) {
            slot.removeAllViews();
            Object tag = slot.getTag();
            if (!(tag instanceof WidgetSpec)) {
                int accent = tag instanceof Integer ? ((Integer) tag).intValue() : pack.tokens.accentColor;
                slot.addView(new HorizontalPagerView.PageDotsBar(context, mainPager, accent, true),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                continue;
            }
            WidgetSpec w = (WidgetSpec) tag;
            IndicatorStyle ind = resolveEffectiveIndicator(w);
            if (ind.isDisabled()) {
                continue;
            }
            int hGrav = resolveHorizontalGravity(ind.align, Gravity.CENTER_HORIZONTAL);
            int vGrav = resolveVerticalGravity(w.style.verticalAlign, Gravity.CENTER_VERTICAL);
            slot.addView(new HorizontalPagerView.PageDotsBar(
                            context, mainPager, ind, w.style.accentColor, assets, scaleX, scaleY, scaleMin),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, hGrav | vGrav));
        }
    }

    private View buildNode(LayoutNode node, boolean ancestorHandlesClicks) {
        if (node instanceof PanelSpec) {
            return buildPanel((PanelSpec) node, ancestorHandlesClicks);
        }
        return buildWidget((WidgetSpec) node, ancestorHandlesClicks);
    }

    private View buildPanel(PanelSpec panel, boolean ancestorHandlesClicks) {
        if ("pager".equals(panel.layout)) {
            return buildPagerPanel(panel, ancestorHandlesClicks);
        }

        ViewGroup container;
        boolean isFlexRow = "flex_row".equals(panel.layout);
        boolean isFlexCol = "flex_col".equals(panel.layout);
        boolean isGrid = "grid".equals(panel.layout);
        int scaledGap = sx(panel.gapPx);

        if (isGrid) {
            container = new GridPanelView(context, panel.columns, panel.rows, scaledGap);
        } else if (isFlexRow || isFlexCol) {
            LinearLayout ll = new LinearLayout(context);
            ll.setOrientation(isFlexRow ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
            ll.setGravity(resolveCombinedGravity(panel.horizontalAlign, panel.verticalAlign,
                    resolveHorizontalGravity(panel.style.textAlign, Gravity.LEFT),
                    resolveVerticalGravity(panel.style.verticalAlign, Gravity.TOP)));
            container = ll;
        } else {
            container = new FrameLayout(context);
        }

        container.setPadding(
                sx(panel.paddingPx[0]),
                sy(panel.paddingPx[1]),
                sx(panel.paddingPx[2]),
                sy(panel.paddingPx[3]));

        boolean panelHasAction = !ancestorHandlesClicks && hasAction(panel.action);
        boolean passAncestorAction = ancestorHandlesClicks || panelHasAction;

        int count = panel.children.size();
        for (int i = 0; i < count; i++) {
            LayoutNode child = panel.children.get(i);
            View childView = buildNode(child, passAncestorAction);
            boolean hasNext = (i < count - 1);
            ViewGroup.LayoutParams lp = createChildLayoutParams(panel, child, scaledGap, hasNext);
            container.addView(childView, lp);
        }

        View host = wrapContainerEffectsIfNeeded(container, panel, !panelHasAction);
        applyOpacity(host, panel.style);
        if (panelHasAction) {
            applyInteractiveStyle(host, panel.style);
            bindClickAndAnim(host, panel, panel.action);
        } else {
            applyContainerStyle(host, panel.style);
            registerIdleAnim(WidgetAnimator.attach(host, panel.animation, false));
        }
        return host;
    }

    private View buildPagerPanel(PanelSpec panel, boolean ancestorHandlesClicks) {
        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(
                sx(panel.paddingPx[0]),
                sy(panel.paddingPx[1]),
                sx(panel.paddingPx[2]),
                sy(panel.paddingPx[3]));
        applyContainerStyle(shell, panel.style);
        applyOpacity(shell, panel.style);

        HorizontalPagerView pager = new HorizontalPagerView(context);
        final boolean isPrimaryPager = bindings.pagers.isEmpty();
        int prevPageIndex = currentBuildPageIndex;
        for (int i = 0; i < panel.children.size(); i++) {
            if (isPrimaryPager) {
                currentBuildPageIndex = i;
            }
            LayoutNode child = panel.children.get(i);
            View pageView = buildNode(child, ancestorHandlesClicks);
            if (!child.id.isEmpty()) {
                pageView.setTag(child.id);
            }
            String title = (child instanceof PanelSpec && !((PanelSpec) child).pageTitle.isEmpty())
                    ? ((PanelSpec) child).pageTitle
                    : (child instanceof WidgetSpec && !((WidgetSpec) child).title.isEmpty()
                            ? ((WidgetSpec) child).title
                            : ("Page " + (i + 1)));
            pager.addPage(pageView, title);
        }
        currentBuildPageIndex = prevPageIndex;
        bindings.pagers.add(pager);
        if (isPrimaryPager) {
            pager.addOnPageChangeListener(new HorizontalPagerView.OnPageChangeListener() {
                @Override
                public void onPageSelected(int index) {
                    bindings.syncScopedAnimations();
                }
            });
        }
        shell.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        IndicatorStyle ind = resolveEffectiveIndicator(panel);
        if (panel.showDots && !ind.isDisabled() && pager.getPageCount() > 1) {
            HorizontalPagerView.PageDotsBar dots = new HorizontalPagerView.PageDotsBar(
                    context, pager, ind, effectiveAccentColor(panel.style), assets, scaleX, scaleY, scaleMin);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp.gravity = resolveHorizontalGravity(ind.align, Gravity.CENTER_HORIZONTAL);
            dp.topMargin = sy(8);
            shell.addView(dots, dp);
        }
        return shell;
    }

    private ViewGroup.LayoutParams createChildLayoutParams(PanelSpec parent, LayoutNode child, int gap, boolean addGap) {
        int mL = child.marginPx != null ? sx(child.marginPx[0]) : 0;
        int mT = child.marginPx != null ? sy(child.marginPx[1]) : 0;
        int mR = child.marginPx != null ? sx(child.marginPx[2]) : 0;
        int mB = child.marginPx != null ? sy(child.marginPx[3]) : 0;
        String wKind = (child instanceof WidgetSpec) ? ((WidgetSpec) child).kind : "";

        if ("freeform".equals(parent.layout) && child.bounds != null) {
            FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                    Math.max(1, sx(child.bounds[2])),
                    Math.max(1, sy(child.bounds[3])));
            fp.leftMargin = sx(child.bounds[0]) + mL;
            fp.topMargin = sy(child.bounds[1]) + mT;
            return fp;
        }
        if ("grid".equals(parent.layout)) {
            return new GridPanelView.LayoutParams(
                    child.colSpan,
                    child.rowSpan,
                    mL, mT, mR, mB,
                    child.width > 0 ? sx(child.width) : -1,
                    child.height > 0 ? sy(child.height) : -1,
                    resolveHorizontalGravity(child.horizontalAlign, Gravity.LEFT),
                    resolveVerticalGravity(child.verticalAlign, Gravity.TOP));
        }
        if ("flex_row".equals(parent.layout)) {
            int defW = "progress_bar".equals(wKind) ? sx(220) : ViewGroup.LayoutParams.WRAP_CONTENT;
            int w = child.flexWeight > 0f ? 0 : (child.width > 0 ? sx(child.width) : defW);
            int h = child.height > 0 ? sy(child.height) : ("progress_bar".equals(wKind) ? sy(22) : ViewGroup.LayoutParams.MATCH_PARENT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, child.flexWeight);
            lp.leftMargin = mL;
            lp.topMargin = mT;
            lp.rightMargin = mR + (addGap && gap > 0 ? gap : 0);
            lp.bottomMargin = mB;
            int g = resolveCombinedGravity(child.horizontalAlign, child.verticalAlign, Gravity.NO_GRAVITY, Gravity.CENTER_VERTICAL);
            if (g != Gravity.NO_GRAVITY) lp.gravity = g;
            return lp;
        }
        if ("flex_col".equals(parent.layout)) {
            int w = child.width > 0 ? sx(child.width) : ViewGroup.LayoutParams.MATCH_PARENT;
            int defH = "progress_bar".equals(wKind) ? sy(22) : ViewGroup.LayoutParams.WRAP_CONTENT;
            int h = child.flexWeight > 0f ? 0 : (child.height > 0 ? sy(child.height) : defH);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, child.flexWeight);
            lp.leftMargin = mL;
            lp.topMargin = mT;
            lp.rightMargin = mR;
            lp.bottomMargin = mB + (addGap && gap > 0 ? gap : 0);
            int hGrav = resolveHorizontalGravity(child.horizontalAlign, Gravity.NO_GRAVITY);
            if (hGrav != Gravity.NO_GRAVITY) lp.gravity = hGrav;
            return lp;
        }
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(
                child.width > 0 ? sx(child.width) : ViewGroup.LayoutParams.WRAP_CONTENT,
                child.height > 0 ? sy(child.height) : ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = mL;
        fp.topMargin = mT;
        fp.rightMargin = mR;
        fp.bottomMargin = mB;
        int g = resolveCombinedGravity(child.horizontalAlign, child.verticalAlign, Gravity.NO_GRAVITY, Gravity.NO_GRAVITY);
        if (g != Gravity.NO_GRAVITY) fp.gravity = g;
        return fp;
    }

    private View buildWidget(final WidgetSpec w, boolean ancestorHandlesClicks) {
        if ("spacer".equals(w.kind)) {
            return new View(context);
        }
        if ("divider".equals(w.kind)) {
            View div = new View(context);
            div.setBackgroundColor(w.style.borderColor != 0 ? w.style.borderColor : 0x3364748B);
            applyOpacity(div, w.style);
            return div;
        }
        if ("text".equals(w.kind) || "sensor".equals(w.kind) || "label".equals(w.kind)) {
            return buildTextWidget(w, ancestorHandlesClicks);
        }
        if ("progress_bar".equals(w.kind)) {
            return buildProgressBarWidget(w, ancestorHandlesClicks);
        }
        if ("icon".equals(w.kind) || "image".equals(w.kind)) {
            return buildImageWidget(w, ancestorHandlesClicks);
        }
        if ("page_indicator".equals(w.kind)) {
            FrameLayout slot = new FrameLayout(context);
            applyInsetsOrDefault(slot, w.style, w.style.transparent ? 0 : 8, w.style.transparent ? 0 : 4);
            applyContainerStyle(slot, w.style);
            applyOpacity(slot, w.style);
            slot.setTag(w);
            bindings.pageIndicatorSlots.add(slot);
            return slot;
        }
        if ("clock".equals(w.kind)) {
            return buildClockWidget(w, ancestorHandlesClicks);
        }
        if ("telemetry_badge".equals(w.kind)) {
            return buildTelemetryBadge(w, ancestorHandlesClicks);
        }
        if ("soc_ring".equals(w.kind) || "ring_gauge".equals(w.kind) || "telemetry_gauge".equals(w.kind)) {
            return buildSocRingWidget(w, ancestorHandlesClicks);
        }
        if ("ev_hero".equals(w.kind)) {
            return buildEvHeroWidget(w, ancestorHandlesClicks);
        }
        if ("switcher_status".equals(w.kind)) {
            return buildSwitcherStatusWidget(w, ancestorHandlesClicks);
        }
        if ("action_pill".equals(w.kind)) {
            return buildActionPillWidget(w, ancestorHandlesClicks);
        }
        return buildTileWidget(w, ancestorHandlesClicks);
    }

    private View buildTextWidget(WidgetSpec w, boolean ancestorHandlesClicks) {
        int hGrav = resolveHorizontalGravity(w.style.textAlign, Gravity.LEFT);
        int vGrav = resolveVerticalGravity(w.style.verticalAlign, Gravity.CENTER_VERTICAL);

        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(vGrav | hGrav);
        applyInsetsOrDefault(ll, w.style, w.style.transparent ? 2 : 12, w.style.transparent ? 2 : 6);

        boolean hasPrimaryBinding = !w.telemetryKey.isEmpty() || containsToken(w.title);
        boolean isAtomicText = "text".equals(w.kind) || "sensor".equals(w.kind);

        TextView title = new TextView(context);
        title.setTextColor(w.style.textColor);
        applyTextTypography(title, w.style, 22f, false, true);
        title.setGravity(hGrav);
        if (w.wrapText || w.maxLines > 1) {
            title.setSingleLine(false);
            title.setMaxLines(Math.max(2, w.maxLines));
        } else {
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        if (hasPrimaryBinding) {
            title.setText(bindings.formatDynamicText(w.title, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, isAtomicText));
        } else {
            title.setText(w.title);
        }
        ll.addView(title);

        TextView sub = null;
        boolean hasSubBinding = containsToken(w.subtitle);
        if (w.subtitle != null && !w.subtitle.isEmpty()) {
            sub = new TextView(context);
            sub.setTextColor(w.style.subTextColor);
            applyTextTypography(sub, w.style, 15f, true, false);
            sub.setGravity(hGrav);
            sub.setSingleLine(!w.wrapText);
            sub.setText(hasSubBinding
                    ? bindings.formatDynamicText(w.subtitle, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, false)
                    : w.subtitle);
            ll.addView(sub);
        }

        if (hasPrimaryBinding || hasSubBinding) {
            bindings.dynamicTexts.add(new DynamicTextBinding(w, hasPrimaryBinding ? title : null, hasSubBinding ? sub : null, isAtomicText));
        }

        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(ll, w, !ownAction);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildProgressBarWidget(WidgetSpec w, boolean ancestorHandlesClicks) {
        boolean vertical = "vertical".equalsIgnoreCase(w.orientation);
        int trackColor = w.style.transparent ? 0x55293B52 : w.style.bgColor;
        int fillStart = w.style.accentColor;
        int fillEnd = w.style.bgEndColor;
        float radius = w.style.cornerRadiusPx >= 0 ? smin(w.style.cornerRadiusPx) : sy(10);
        int borderPx = w.style.borderWidthPx > 0 ? Math.max(1, smin(w.style.borderWidthPx)) : 0;
        Typeface tf = assets.loadTypeface(w.style.fontFamily,
                !"normal".equalsIgnoreCase(w.style.fontWeight), w.style.fontItalic);
        float textSize = smin(w.style.titleSizePx > 0 ? w.style.titleSizePx : 14f);

        LinearGaugeView bar = new LinearGaugeView(
                context, vertical, trackColor, fillStart, fillEnd,
                w.style.borderColor, borderPx, radius,
                w.showText, w.style.textColor, tf, textSize);
        bindings.progressBars.add(new ProgressBarBinding(w, bar));

        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(bar, w, true);
        applyOpacity(host, w.style);
        if (ownAction) {
            bindClickAndAnim(host, w, w.action);
        } else {
            registerIdleAnim(WidgetAnimator.attach(host, w.animation, false));
        }
        return host;
    }

    private View buildImageWidget(WidgetSpec w, boolean ancestorHandlesClicks) {
        String assetRef = !w.animIcon.isEmpty() ? w.animIcon : (!w.bitmapAsset.isEmpty() ? w.bitmapAsset : (!w.icon.isEmpty() ? w.icon : w.style.bgBitmapAsset));
        View mediaView;
        boolean explicitStretch = (w.width > 0 && w.height > 0) || w.flexWeight > 0f || w.bounds != null;
        int fallbackSize = smin(w.style.iconSizePx > 0 ? w.style.iconSizePx : 64);

        if (!w.animIcon.isEmpty()) {
            AnimatedMediaLayer anim = new AnimatedMediaLayer(context);
            anim.setCornerRadiusPx(smin(effectiveCornerRadiusPx(w.style)));
            if (AnimatedMediaLayer.isGifAsset(w.animIcon)) {
                anim.setGif(assets, w.animIcon, 0);
            } else {
                anim.setFrames(assets, w.animIcon, w.style.bgAnimFps, "cover".equalsIgnoreCase(w.scaleType), 0);
            }
            registerMediaLayerAnim(anim);
            mediaView = anim;
        } else {
            ImageView iv = createIconView(assetRef, fallbackSize, w.style.iconTint);
            if (iv == null) {
                iv = new ImageView(context);
            }
            iv.setScaleType("cover".equalsIgnoreCase(w.scaleType) ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            mediaView = iv;
        }

        FrameLayout frame = new FrameLayout(context);
        applyInsetsOrDefault(frame, w.style, w.style.transparent ? 0 : 8, w.style.transparent ? 0 : 8);
        FrameLayout.LayoutParams lp = explicitStretch
                ? new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                : new FrameLayout.LayoutParams(fallbackSize, fallbackSize,
                        resolveCombinedGravity(w.horizontalAlign, w.verticalAlign,
                                resolveHorizontalGravity(w.style.textAlign, Gravity.CENTER_HORIZONTAL),
                                resolveVerticalGravity(w.style.verticalAlign, Gravity.CENTER_VERTICAL)));
        frame.addView(mediaView, lp);

        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(frame, w, !ownAction);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildSocRingWidget(WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        applyInsetsOrDefault(card, w.style, w.style.transparent ? 4 : 14, w.style.transparent ? 4 : 12);

        int ringSize = smin(w.style.iconSizePx > 0 ? w.style.iconSizePx : 88);
        CircularRingGaugeView ring = new CircularRingGaugeView(context, w.style.accentColor);
        Typeface tf = assets.loadTypeface(w.style.fontFamily, !"normal".equalsIgnoreCase(w.style.fontWeight), w.style.fontItalic);
        ring.setStyleOptions(tf, w.style.textColor, 0f, w.style.transparent ? 0 : (w.style.borderColor != 0 ? w.style.borderColor : 0x44334155));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ringSize, ringSize);
        if ((w.title != null && !w.title.isEmpty()) || (w.subtitle != null && !w.subtitle.isEmpty())) {
            rp.bottomMargin = sy(8);
        }
        card.addView(ring, rp);

        if (w.title != null && !w.title.isEmpty()) {
            TextView label = new TextView(context);
            label.setText(w.title);
            label.setTextColor(w.style.textColor);
            applyTextTypography(label, w.style, 17f, false, true);
            label.setSingleLine(true);
            card.addView(label);
        }

        if (w.subtitle != null && !w.subtitle.isEmpty()) {
            TextView sub = new TextView(context);
            sub.setText(w.subtitle);
            sub.setTextColor(w.style.subTextColor);
            applyTextTypography(sub, w.style, 13f, true, false);
            sub.setSingleLine(true);
            card.addView(sub);
        }

        bindings.ringGauges.add(new RingGaugeBinding(w, ring));
        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(card, w, !ownAction);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildClockWidget(WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(resolveVerticalGravity(w.style.verticalAlign, Gravity.CENTER_VERTICAL)
                | resolveHorizontalGravity(w.style.textAlign, Gravity.LEFT));
        applyInsetsOrDefault(ll, w.style, w.style.transparent ? 0 : 12, w.style.transparent ? 0 : 6);

        TextView time = new TextView(context);
        time.setTextColor(w.style.textColor);
        applyTextTypography(time, w.style, 28f, false, true);
        time.setText("--:--");

        TextView date = new TextView(context);
        date.setTextColor(w.style.accentColor);
        applyTextTypography(date, w.style, 14f, true, false);
        date.setText("---");

        ll.addView(time);
        ll.addView(date);
        bindings.clockTimes.add(time);
        bindings.clockDates.add(date);

        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(ll, w, !ownAction);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildTelemetryBadge(WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setPadding(sx(10), sy(4), sx(10), sy(4));

        TextView label = new TextView(context);
        label.setText(w.title);
        label.setTextColor(w.style.subTextColor);
        applyTextTypography(label, w.style, 13f, true, false);
        label.setSingleLine(true);

        TextView val = new TextView(context);
        val.setText("--");
        val.setTextColor(w.style.textColor);
        applyTextTypography(val, w.style, 21f, false, true);
        val.setSingleLine(true);

        ll.addView(label);
        ll.addView(val);
        bindings.telemetryBadges.add(new BadgeBinding(w, val));
        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        applyOpacity(ll, w.style);
        finalizeInteractiveOrStatic(ll, w, ownAction);
        return ll;
    }

    private View buildEvHeroWidget(final WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        applyInsetsOrDefault(card, w.style, 28, 24);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        int heroIconSz = smin(w.style.iconSizePx > 0 ? w.style.iconSizePx : 44);
        View icon = createIconOrAnimView(w, heroIconSz);
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(heroIconSz, heroIconSz);
            ip.rightMargin = sx(14);
            header.addView(icon, ip);
        }

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText(w.title);
        title.setTextColor(w.style.textColor);
        applyTextTypography(title, w.style, 22f, false, true);
        TextView status = new TextView(context);
        status.setText("HMG-API VEHICLE BUS");
        status.setTextColor(w.style.accentColor);
        applyTextTypography(status, w.style, 15f, true, false);
        titleCol.addView(title);
        titleCol.addView(status);
        header.addView(titleCol);
        card.addView(header);

        TextView socBig = new TextView(context);
        socBig.setText("--%");
        socBig.setTextColor(0xFFFFFFFF);
        socBig.setTypeface(assets.loadTypeface(w.style.fontFamily, true, w.style.fontItalic));
        socBig.setTextSize(TypedValue.COMPLEX_UNIT_PX, smin(86));
        LinearLayout.LayoutParams socLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        socLp.topMargin = sy(16);
        socLp.bottomMargin = sy(10);
        card.addView(socBig, socLp);

        SocGaugeView gauge = new SocGaugeView(context, w.style.accentColor);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sy(22));
        gp.bottomMargin = sy(18);
        card.addView(gauge, gp);

        TextView aux = new TextView(context);
        aux.setText("12V AUX --%   \u2022   EXT --\u00B0F   \u2022   ODO -- km");
        aux.setTextColor(w.style.subTextColor);
        aux.setTextSize(TypedValue.COMPLEX_UNIT_PX, smin(17));
        card.addView(aux);

        TextView hint = new TextView(context);
        hint.setText("Tap card for OEM EV & Charging Screen");
        hint.setTextColor(0xAA7DD3FC);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, smin(14));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = sy(12);
        card.addView(hint, hp);

        bindings.evHeroes.add(new EvHeroBinding(socBig, status, aux, gauge));
        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(card, w, false);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildSwitcherStatusWidget(final WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(sx(14), sy(4), sx(14), sy(4));
        applyOpacity(card, w.style);
        applyInteractiveStyle(card, w.style);

        TextView title = new TextView(context);
        title.setText(w.title);
        title.setTextColor(0xFFFFFFFF);
        applyTextTypography(title, w.style, 18f, false, true);
        title.setSingleLine(true);

        TextView sub = new TextView(context);
        sub.setText("Checking home-switcher...");
        sub.setTextColor(w.style.subTextColor);
        applyTextTypography(sub, w.style, 13f, true, false);
        sub.setSingleLine(true);

        card.addView(title);
        card.addView(sub);
        bindings.switcherPills.add(new SwitcherBinding(
                card, w, title, sub,
                Math.max(2, smin(w.style.borderWidthPx)),
                smin(effectiveCornerRadiusPx(w.style))));
        if (!ancestorHandlesClicks && hasAction(w.action)) {
            bindClickAndAnim(card, w, w.action);
        }
        return card;
    }

    private View buildActionPillWidget(final WidgetSpec w, boolean ancestorHandlesClicks) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER);
        applyInsetsOrDefault(card, w.style, 14, 6);

        int sz = smin(w.style.iconSizePx > 0 ? w.style.iconSizePx : 32);
        View icon = createIconOrAnimView(w, sz);
        if (icon != null) {
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
            ip.rightMargin = sx(10);
            card.addView(icon, ip);
        }

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setGravity(Gravity.CENTER_VERTICAL | resolveHorizontalGravity(w.style.textAlign, Gravity.LEFT));

        boolean hasTitleBinding = containsToken(w.title) || ((w.title == null || w.title.isEmpty()) && !w.telemetryKey.isEmpty());
        TextView title = new TextView(context);
        title.setText(hasTitleBinding
                ? bindings.formatDynamicText(w.title, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, true)
                : w.title);
        title.setTextColor(w.style.textColor);
        applyTextTypography(title, w.style, 19f, false, true);
        title.setSingleLine(true);
        textCol.addView(title);

        TextView sub = null;
        String pillSubRaw = w.subtitle == null ? "" : w.subtitle;
        boolean autoPillSub = !w.telemetryKey.isEmpty() && !hasTitleBinding && pillSubRaw.isEmpty()
                && !"toggle_bar".equals(w.action.type) && !"cycle_sound".equals(w.action.type);
        boolean hasSubBinding = containsToken(pillSubRaw) || autoPillSub;
        if (!pillSubRaw.isEmpty() || hasSubBinding) {
            sub = new TextView(context);
            sub.setText(hasSubBinding
                    ? bindings.formatDynamicText(pillSubRaw, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, autoPillSub)
                    : pillSubRaw);
            sub.setTextColor(w.style.accentColor);
            applyTextTypography(sub, w.style, 13f, true, false);
            sub.setSingleLine(true);
            textCol.addView(sub);
            if ("toggle_bar".equals(w.action.type)) bindings.barToggleSub = sub;
            else if ("cycle_sound".equals(w.action.type)) bindings.soundToggleSub = sub;
        }
        if (hasTitleBinding || hasSubBinding) {
            bindings.dynamicTexts.add(new DynamicTextBinding(w, hasTitleBinding ? title : null, hasSubBinding ? sub : null, null,
                    hasTitleBinding, autoPillSub));
        }

        card.addView(textCol);
        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(card, w, false);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View buildTileWidget(final WidgetSpec w, boolean ancestorHandlesClicks) {
        int hGravity = resolveHorizontalGravity(w.style.textAlign, Gravity.LEFT);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(resolveVerticalGravity(w.style.verticalAlign, Gravity.CENTER_VERTICAL) | hGravity);
        applyInsetsOrDefault(card, w.style, 24, 18);

        int sz = smin(w.style.iconSizePx > 0 ? w.style.iconSizePx : 54);
        View icon = createIconOrAnimView(w, sz);
        TextView badge = null;
        boolean hasBadgeBinding = false;
        if (icon != null || (w.badgeText != null && !w.badgeText.isEmpty())) {
            LinearLayout topRow = new LinearLayout(context);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL | hGravity);
            if (icon != null) {
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(sz, sz);
                topRow.addView(icon, ip);
            }
            if (w.badgeText != null && !w.badgeText.isEmpty()) {
                if (icon != null && hGravity == Gravity.LEFT) {
                    topRow.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
                }
                badge = new TextView(context);
                hasBadgeBinding = containsToken(w.badgeText);
                badge.setText(hasBadgeBinding
                        ? bindings.formatDynamicText(w.badgeText, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, false)
                        : w.badgeText);
                badge.setTextColor(0xFFFFFFFF);
                badge.setTypeface(Typeface.DEFAULT_BOLD);
                badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, smin(12f));
                badge.setPadding(sx(10), sy(4), sx(10), sy(4));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor((w.style.accentColor & 0x00FFFFFF) | 0x44000000);
                bg.setCornerRadius(smin(10f));
                bg.setStroke(1, w.style.accentColor);
                badge.setBackground(bg);
                topRow.addView(badge);
            }
            LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            trp.bottomMargin = sy(14);
            card.addView(topRow, trp);
        }

        boolean hasTitleBinding = containsToken(w.title) || ((w.title == null || w.title.isEmpty()) && !w.telemetryKey.isEmpty());
        TextView title = new TextView(context);
        title.setText(hasTitleBinding
                ? bindings.formatDynamicText(w.title, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, true)
                : w.title);
        title.setTextColor(w.style.textColor);
        applyTextTypography(title, w.style, 26f, false, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(hGravity);
        card.addView(title);

        TextView sub = null;
        String tileSubRaw = w.subtitle == null ? "" : w.subtitle;
        boolean autoTileSub = !w.telemetryKey.isEmpty() && !hasTitleBinding && tileSubRaw.isEmpty();
        boolean hasSubBinding = containsToken(tileSubRaw) || autoTileSub;
        if (!tileSubRaw.isEmpty() || hasSubBinding) {
            sub = new TextView(context);
            sub.setText(hasSubBinding
                    ? bindings.formatDynamicText(tileSubRaw, w.telemetryKey, w.unit, TelemetryModel.Snapshot.EMPTY, autoTileSub)
                    : tileSubRaw);
            sub.setTextColor(w.style.subTextColor);
            applyTextTypography(sub, w.style, 16f, true, false);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setGravity(hGravity);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = sy(4);
            card.addView(sub, sp);
        }

        if (hasTitleBinding || hasSubBinding || hasBadgeBinding) {
            bindings.dynamicTexts.add(new DynamicTextBinding(w,
                    hasTitleBinding ? title : null,
                    hasSubBinding ? sub : null,
                    hasBadgeBinding ? badge : null,
                    hasTitleBinding, autoTileSub));
        }

        boolean ownAction = !ancestorHandlesClicks && hasAction(w.action);
        View host = wrapContainerEffectsIfNeeded(card, w, false);
        applyOpacity(host, w.style);
        finalizeInteractiveOrStatic(host, w, ownAction);
        return host;
    }

    private View createIconOrAnimView(WidgetSpec w, int sizePx) {
        if (w.animIcon != null && !w.animIcon.trim().isEmpty()) {
            AnimatedMediaLayer anim = new AnimatedMediaLayer(context);
            String ref = w.animIcon.trim();
            if (AnimatedMediaLayer.isGifAsset(ref)) {
                anim.setGif(assets, ref, 0);
            } else {
                anim.setFrames(assets, ref, w.style.bgAnimFps, false, 0);
            }
            registerMediaLayerAnim(anim);
            return anim;
        }
        return createIconView(w.icon, sizePx, w.style.iconTint);
    }

    private ImageView createIconView(String ref, int sizePx, int iconTint) {
        Drawable d = assets.loadDrawable(ref);
        if (d == null) {
            return null;
        }
        Drawable iconDrawable = d.mutate();
        if (iconTint != 0) {
            iconDrawable.setColorFilter(iconTint, PorterDuff.Mode.SRC_IN);
        }
        ImageView iv = new ImageView(context);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(iconDrawable);
        iv.setMinimumWidth(sizePx);
        iv.setMinimumHeight(sizePx);
        return iv;
    }

    private View wrapContainerEffectsIfNeeded(View content, LayoutNode node, boolean respectTransparentSkip) {
        WidgetStyle st = node.style;
        String fx = node.animation != null && node.animation.idleEffect != null ? node.animation.idleEffect : "none";
        boolean needsFrame = (!st.bgAnimAsset.isEmpty() || !st.bgBitmapAsset.isEmpty()
                || "shimmer".equals(fx) || "glow".equals(fx) || "border_glow".equals(fx) || "ev_reactive".equals(fx));
        if (!needsFrame || (respectTransparentSkip && st.transparent && st.bgAnimAsset.isEmpty() && st.bgBitmapAsset.isEmpty())) {
            return content;
        }
        InteractiveCardFrame frame = new InteractiveCardFrame(context);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.configureVisualEffects(st, node.animation, assets, smin(effectiveCornerRadiusPx(st)));
        registerCardFrameAnim(frame);
        if ("ev_reactive".equals(fx)) {
            bindings.evReactiveCards.add(frame);
        }
        return frame;
    }

    private void applyInsetsOrDefault(View v, WidgetStyle st, int defH, int defV) {
        if (st != null && st.paddingPx != null && st.paddingPx.length >= 4) {
            v.setPadding(sx(st.paddingPx[0]), sy(st.paddingPx[1]), sx(st.paddingPx[2]), sy(st.paddingPx[3]));
        } else {
            v.setPadding(sx(defH), sy(defV), sx(defH), sy(defV));
        }
    }

    private void applyTextTypography(TextView tv, WidgetStyle st, float defaultSize, boolean isSubtitle, boolean defaultBold) {
        boolean bold = !isSubtitle && ("bold".equalsIgnoreCase(st.fontWeight) || (!"normal".equalsIgnoreCase(st.fontWeight) && defaultBold));
        tv.setTypeface(assets.loadTypeface(st.fontFamily, bold, st.fontItalic));
        float px = isSubtitle ? (st.subtitleSizePx > 0 ? st.subtitleSizePx : defaultSize)
                              : (st.titleSizePx > 0 ? st.titleSizePx : defaultSize);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, smin(px));
    }

    private void applyOpacity(View v, WidgetStyle st) {
        if (v != null && st != null && st.opacity < 0.999f) {
            v.setAlpha(Math.max(0f, Math.min(1f, st.opacity)));
        }
    }

    private void finalizeInteractiveOrStatic(View host, LayoutNode node, boolean interactive) {
        if (interactive) {
            applyInteractiveStyle(host, node.style);
            bindClickAndAnim(host, node, node.action);
        } else {
            applyContainerStyle(host, node.style);
            registerIdleAnim(WidgetAnimator.attach(host, node.animation, false));
        }
    }

    private static boolean hasAction(ActionSpec a) {
        return a != null && a.type != null && !a.type.isEmpty() && !"none".equalsIgnoreCase(a.type);
    }

    private static boolean containsToken(String s) {
        return s != null && s.indexOf('{') >= 0 && s.indexOf('}') > s.indexOf('{');
    }

    private static int resolveHorizontalGravity(String textAlign, int fallback) {
        if ("center".equalsIgnoreCase(textAlign)) return Gravity.CENTER_HORIZONTAL;
        if ("right".equalsIgnoreCase(textAlign)) return Gravity.RIGHT;
        if ("left".equalsIgnoreCase(textAlign)) return Gravity.LEFT;
        return fallback;
    }

    private static int resolveVerticalGravity(String align, int fallback) {
        if ("top".equalsIgnoreCase(align)) return Gravity.TOP;
        if ("bottom".equalsIgnoreCase(align)) return Gravity.BOTTOM;
        if ("center".equalsIgnoreCase(align)) return Gravity.CENTER_VERTICAL;
        return fallback;
    }

    private static int resolveCombinedGravity(String hAlign, String vAlign, int defH, int defV) {
        int h = resolveHorizontalGravity(hAlign, defH);
        int v = resolveVerticalGravity(vAlign, defV);
        if (h == Gravity.NO_GRAVITY) return v;
        if (v == Gravity.NO_GRAVITY) return h;
        return h | v;
    }

    private void bindClickAndAnim(View view, final LayoutNode node, final ActionSpec action) {
        view.setClickable(true);
        view.setFocusable(true);
        registerIdleAnim(WidgetAnimator.attach(view, node.animation, true));
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (handler != null) {
                    handler.onAction(v, node, action);
                }
            }
        });
    }

    private void applyContainerStyle(View v, WidgetStyle style) {
        if (style == null || style.transparent) {
            return;
        }
        v.setBackground(buildFillDrawable(
                style,
                style.bgColor,
                style.borderColor,
                style.borderWidthPx > 0 ? Math.max(1, smin(style.borderWidthPx)) : 0,
                smin(effectiveCornerRadiusPx(style))));
    }

    private void applyInteractiveStyle(View v, WidgetStyle style) {
        if (style == null || style.transparent) {
            return;
        }
        v.setBackground(makeStateDrawable(
                style,
                style.borderColor,
                style.borderWidthPx > 0 ? Math.max(1, smin(style.borderWidthPx)) : 0,
                smin(effectiveCornerRadiusPx(style))));
    }

    private static Drawable makeStateDrawable(WidgetStyle style, int borderColor, int borderPx, float radiusPx) {
        Drawable normal = buildFillDrawable(style, style.bgColor, borderColor, borderPx, radiusPx);
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(style.pressedColor);
        pressed.setCornerRadius(radiusPx);
        if (borderPx > 0) {
            pressed.setStroke(borderPx + 1, 0xCCFFFFFF);
        }
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed }, pressed);
        sld.addState(new int[] {}, normal);
        return sld;
    }

    private static GradientDrawable buildFillDrawable(WidgetStyle style, int startColor, int borderColor, int borderPx, float radiusPx) {
        boolean hasMediaBg = style != null && (!style.bgAnimAsset.isEmpty() || !style.bgBitmapAsset.isEmpty());
        GradientDrawable gd;
        if (hasMediaBg) {
            gd = new GradientDrawable();
            gd.setColor(0x00000000);
        } else if (style != null && style.bgEndColor != 0) {
            gd = new GradientDrawable(angleToOrientation(style.gradientAngle), new int[] { startColor, style.bgEndColor });
        } else {
            gd = new GradientDrawable();
            gd.setColor(startColor);
        }
        gd.setCornerRadius(radiusPx);
        if (borderPx > 0) {
            gd.setStroke(borderPx, borderColor);
        }
        return gd;
    }

    private static GradientDrawable.Orientation angleToOrientation(int deg) {
        int norm = ((deg % 360) + 360) % 360;
        if (norm < 23 || norm >= 338) return GradientDrawable.Orientation.LEFT_RIGHT;
        if (norm < 68) return GradientDrawable.Orientation.BL_TR;
        if (norm < 113) return GradientDrawable.Orientation.BOTTOM_TOP;
        if (norm < 158) return GradientDrawable.Orientation.BR_TL;
        if (norm < 203) return GradientDrawable.Orientation.RIGHT_LEFT;
        if (norm < 248) return GradientDrawable.Orientation.TR_BL;
        if (norm < 293) return GradientDrawable.Orientation.TOP_BOTTOM;
        return GradientDrawable.Orientation.TL_BR;
    }

    private int sx(float refPx) {
        return Math.round(refPx * scaleX);
    }

    private int sy(float refPx) {
        return Math.round(refPx * scaleY);
    }

    private int smin(float refPx) {
        return Math.round(refPx * scaleMin);
    }

    /** Custom grid container respecting equal column/row tracks, colSpan/rowSpan, gaps, custom W/H, and child margins. */
    private static final class GridPanelView extends ViewGroup {
        static final class LayoutParams extends ViewGroup.LayoutParams {
            final int colSpan;
            final int rowSpan;
            final int mLeft, mTop, mRight, mBottom;
            final int fixedW, fixedH;
            final int hGrav, vGrav;

            LayoutParams(int colSpan, int rowSpan, int mLeft, int mTop, int mRight, int mBottom,
                    int fixedW, int fixedH, int hGrav, int vGrav) {
                super(MATCH_PARENT, MATCH_PARENT);
                this.colSpan = Math.max(1, colSpan);
                this.rowSpan = Math.max(1, rowSpan);
                this.mLeft = mLeft;
                this.mTop = mTop;
                this.mRight = mRight;
                this.mBottom = mBottom;
                this.fixedW = fixedW;
                this.fixedH = fixedH;
                this.hGrav = hGrav;
                this.vGrav = vGrav;
            }
        }

        private final int cols;
        private final int rows;
        private final int gap;

        GridPanelView(Context context, int cols, int rows, int gap) {
            super(context);
            this.cols = Math.max(1, cols);
            this.rows = Math.max(1, rows);
            this.gap = Math.max(0, gap);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w = MeasureSpec.getSize(widthMeasureSpec);
            int h = MeasureSpec.getSize(heightMeasureSpec);
            int innerW = Math.max(0, w - getPaddingLeft() - getPaddingRight());
            int innerH = Math.max(0, h - getPaddingTop() - getPaddingBottom());
            float cellW = (innerW - gap * (cols - 1)) / (float) cols;
            float cellH = (innerH - gap * (rows - 1)) / (float) rows;

            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                LayoutParams lp = (LayoutParams) c.getLayoutParams();
                int spanCols = Math.min(cols, lp.colSpan);
                int availW = Math.max(1, Math.round(cellW * spanCols + gap * (spanCols - 1)) - lp.mLeft - lp.mRight);
                int availH = Math.max(1, Math.round(cellH * lp.rowSpan + gap * (lp.rowSpan - 1)) - lp.mTop - lp.mBottom);
                int cw = lp.fixedW > 0 ? lp.fixedW : availW;
                int ch = lp.fixedH > 0 ? lp.fixedH : availH;
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            }
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int innerW = Math.max(0, (r - l) - getPaddingLeft() - getPaddingRight());
            int innerH = Math.max(0, (b - t) - getPaddingTop() - getPaddingBottom());
            float cellW = (innerW - gap * (cols - 1)) / (float) cols;
            float cellH = (innerH - gap * (rows - 1)) / (float) rows;

            int maxTrackRows = Math.max(rows, getChildCount() * 2 + 4);
            boolean[][] occupied = new boolean[maxTrackRows][cols];
            int row = 0;
            int col = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                LayoutParams lp = (LayoutParams) c.getLayoutParams();
                int spanCols = Math.min(cols, lp.colSpan);
                int spanRows = Math.max(1, lp.rowSpan);

                while (row < maxTrackRows) {
                    if (col + spanCols > cols) {
                        row++;
                        col = 0;
                        continue;
                    }
                    boolean fits = true;
                    for (int dc = 0; dc < spanCols; dc++) {
                        if (occupied[row][col + dc]) {
                            fits = false;
                            break;
                        }
                    }
                    if (fits) {
                        break;
                    }
                    col++;
                }

                for (int dr = 0; dr < spanRows && (row + dr) < maxTrackRows; dr++) {
                    for (int dc = 0; dc < spanCols && (col + dc) < cols; dc++) {
                        occupied[row + dr][col + dc] = true;
                    }
                }

                int availW = Math.max(1, Math.round(cellW * spanCols + gap * (spanCols - 1)) - lp.mLeft - lp.mRight);
                int availH = Math.max(1, Math.round(cellH * spanRows + gap * (spanRows - 1)) - lp.mTop - lp.mBottom);
                int measW = c.getMeasuredWidth();
                int measH = c.getMeasuredHeight();
                int dx = lp.hGrav == Gravity.CENTER_HORIZONTAL ? Math.round((availW - measW) * 0.5f)
                       : (lp.hGrav == Gravity.RIGHT ? (availW - measW) : 0);
                int dy = lp.vGrav == Gravity.CENTER_VERTICAL ? Math.round((availH - measH) * 0.5f)
                       : (lp.vGrav == Gravity.BOTTOM ? (availH - measH) : 0);
                int left = getPaddingLeft() + Math.round(col * (cellW + gap)) + lp.mLeft + dx;
                int top = getPaddingTop() + Math.round(row * (cellH + gap)) + lp.mTop + dy;
                c.layout(left, top, left + measW, top + measH);
                col += spanCols;
                if (col >= cols) {
                    col = 0;
                    row++;
                }
            }
        }
    }

    /** Generalized customizable percent/range bar rendered cleanly on Canvas for any sensor. */
    public static final class LinearGaugeView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean vertical;
        private final int fillStartColor;
        private final int fillEndColor;
        private final int borderPx;
        private final float cornerRadiusPx;
        private final boolean showText;
        private float progressPct = 0f;
        private String overlayText = "";
        private int lastShaderW = -1;
        private int lastShaderH = -1;

        public LinearGaugeView(
                Context context,
                boolean vertical,
                int trackColor,
                int fillStartColor,
                int fillEndColor,
                int borderColor,
                int borderPx,
                float cornerRadiusPx,
                boolean showText,
                int textColor,
                Typeface typeface,
                float textSizePx) {
            super(context);
            this.vertical = vertical;
            this.fillStartColor = fillStartColor;
            this.fillEndColor = fillEndColor;
            this.borderPx = borderPx;
            this.cornerRadiusPx = cornerRadiusPx;
            this.showText = showText;

            trackPaint.setColor(trackColor);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(borderColor);
            borderPaint.setStrokeWidth(borderPx);
            fillPaint.setColor(fillStartColor);

            textPaint.setColor(textColor);
            if (typeface != null) textPaint.setTypeface(typeface);
            textPaint.setTextSize(Math.max(10f, textSizePx));
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        public void setTelemetry(float pct, String text) {
            float nextPct = Float.isNaN(pct) ? 0f : Math.max(0f, Math.min(100f, pct));
            String nextText = text == null ? "" : text;
            if (Math.abs(nextPct - this.progressPct) < 0.01f && nextText.equals(this.overlayText)) {
                return;
            }
            this.progressPct = nextPct;
            this.overlayText = nextText;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float r = cornerRadiusPx > 0f ? Math.min(cornerRadiusPx, Math.min(w, h) * 0.5f) : (Math.min(w, h) * 0.5f);
            rect.set(0f, 0f, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);

            float frac = progressPct / 100f;
            if (frac > 0.001f) {
                if (fillEndColor != 0 && (w != lastShaderW || h != lastShaderH)) {
                    lastShaderW = w;
                    lastShaderH = h;
                    fillPaint.setShader(vertical
                            ? new LinearGradient(0f, h, 0f, 0f, fillStartColor, fillEndColor, Shader.TileMode.CLAMP)
                            : new LinearGradient(0f, 0f, w, 0f, fillStartColor, fillEndColor, Shader.TileMode.CLAMP));
                }
                if (vertical) {
                    float fillH = Math.max(Math.min(w, h) * 0.5f, h * frac);
                    rect.set(0f, Math.max(0f, h - fillH), w, h);
                } else {
                    float fillW = Math.max(Math.min(w, h) * 0.5f, w * frac);
                    rect.set(0f, 0f, Math.min(w, fillW), h);
                }
                canvas.drawRoundRect(rect, r, r, fillPaint);
            }

            if (borderPx > 0) {
                float half = borderPx * 0.5f;
                rect.set(half, half, w - half, h - half);
                canvas.drawRoundRect(rect, r, r, borderPaint);
            }

            if (showText && !overlayText.isEmpty()) {
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textY = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(overlayText, w * 0.5f, textY, textPaint);
            }
        }
    }

    /** Sleek EV battery level progress bar rendered directly on Canvas. */
    public static final class SocGaugeView extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progressPct = 0f;
        private boolean charging = false;
        private int accentColor;

        public SocGaugeView(Context context, int accentColor) {
            super(context);
            this.accentColor = accentColor;
            trackPaint.setColor(0x55293B52);
            fillPaint.setColor(accentColor);
        }

        public void setProgress(float pct, boolean isCharging) {
            float nextPct = Math.max(0f, Math.min(100f, pct));
            if (Math.abs(nextPct - this.progressPct) < 0.01f && isCharging == this.charging) {
                return;
            }
            this.progressPct = nextPct;
            this.charging = isCharging;
            fillPaint.setColor(isCharging ? 0xFF10B981 : accentColor);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = h * 0.5f;
            rect.set(0f, 0f, w, h);
            canvas.drawRoundRect(rect, r, r, trackPaint);
            float fillW = Math.max(progressPct > 0 ? h : 0f, w * (progressPct / 100f));
            if (fillW > 0f) {
                rect.set(0f, 0f, Math.min(w, fillW), h);
                canvas.drawRoundRect(rect, r, r, fillPaint);
            }
        }
    }
}
