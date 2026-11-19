package com.gen5w.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gen5w.launcher.action.StockMenuCatalog;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interactive diagnostic overlay listing every button/entry extracted from
 * stock Launcher_WP3 so each target can be tested via both App Context and root `am`.
 */
final class StockButtonsOverlay extends FrameLayout {
    interface Listener {
        void onCloseRequested();
    }

    private static final String FILTER_ALL = "All";

    private final SafeClickSoundPlayer sound;
    private final Listener listener;
    private final List<StockMenuCatalog.StockItem> allItems;
    private final Map<String, String> availabilityCache = new HashMap<String, String>();
    private final Map<String, String> lastResultById = new HashMap<String, String>();

    private final LinearLayout filterBar;
    private final LinearLayout listHost;
    private final TextView summaryLabel;
    private final TextView lastOutputView;
    private String selectedCategory = FILTER_ALL;
    private boolean hideMissing = false;

    StockButtonsOverlay(Context context, SafeClickSoundPlayer sound, Listener listener) {
        super(context);
        this.sound = sound;
        this.listener = listener;
        this.allItems = StockMenuCatalog.buildAll();
        resolveAvailabilities();

        setBackgroundColor(0xEE060B12);
        setClickable(true);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(32, 20, 32, 20);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header row
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 10);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Stock Launcher_WP3 Buttons Tester");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        summaryLabel = new TextView(context);
        summaryLabel.setTextColor(0xFF94A3B8);
        summaryLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        titleCol.addView(title);
        titleCol.addView(summaryLabel);
        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = makeBtn(context, "Close", 0xFF1E293B, 0xFF64748B, 18f);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                StockButtonsOverlay.this.sound.playClick(v);
                if (StockButtonsOverlay.this.listener != null) {
                    StockButtonsOverlay.this.listener.onCloseRequested();
                }
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        // Filter row
        filterBar = new LinearLayout(context);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        filterBar.setPadding(0, 0, 0, 10);
        shell.addView(filterBar);

        // Live result strip
        lastOutputView = new TextView(context);
        lastOutputView.setText("Tap [Test (App)] or [Test (Root am)] on any stock button below to verify on unit.");
        lastOutputView.setTextColor(0xFFE2E8F0);
        lastOutputView.setTypeface(Typeface.MONOSPACE);
        lastOutputView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        lastOutputView.setPadding(16, 10, 16, 10);
        lastOutputView.setSingleLine(true);
        lastOutputView.setEllipsize(TextUtils.TruncateAt.END);
        lastOutputView.setBackground(makeBox(0xD80D1B2E, 0x5538BDF8, 12f, 2));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.bottomMargin = 12;
        shell.addView(lastOutputView, op);

        ScrollView scroll = new ScrollView(context);
        listHost = new LinearLayout(context);
        listHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rebuildFilters();
        rebuildList();
    }

    void reload() {
        resolveAvailabilities();
        rebuildFilters();
        rebuildList();
    }

    private void resolveAvailabilities() {
        PackageManager pm = getContext().getPackageManager();
        for (StockMenuCatalog.StockItem item : allItems) {
            availabilityCache.put(item.id, item.resolveAvailability(pm));
        }
    }

    private void rebuildFilters() {
        Context c = getContext();
        filterBar.removeAllViews();
        String[] cats = new String[] {
                FILTER_ALL,
                StockMenuCatalog.CAT_CLIMATE_EV,
                StockMenuCatalog.CAT_NAVI_VEH,
                StockMenuCatalog.CAT_MEDIA_RADIO,
                StockMenuCatalog.CAT_PHONE_REAR,
                StockMenuCatalog.CAT_SYSTEM
        };
        for (final String cat : cats) {
            boolean active = cat.equals(selectedCategory);
            TextView tab = makeBtn(c, cat, active ? 0xFF0E4A6F : 0xFF131D2D,
                    active ? 0xFF38BDF8 : 0xFF334155, 15f);
            tab.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sound.playClick(v);
                    selectedCategory = cat;
                    rebuildFilters();
                    rebuildList();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            filterBar.addView(tab, lp);
        }

        View spacer = new View(c);
        filterBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView hideBtn = makeBtn(c, hideMissing ? "Showing: Available Only" : "Showing: All (" + allItems.size() + ")",
                hideMissing ? 0xFF065F46 : 0xFF1E293B,
                hideMissing ? 0xFF10B981 : 0xFF475569, 15f);
        hideBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                hideMissing = !hideMissing;
                rebuildFilters();
                rebuildList();
            }
        });
        filterBar.addView(hideBtn);
    }

    private void rebuildList() {
        Context c = getContext();
        listHost.removeAllViews();

        List<StockMenuCatalog.StockItem> visible = new ArrayList<StockMenuCatalog.StockItem>();
        int installedCount = 0;
        for (StockMenuCatalog.StockItem item : allItems) {
            String avail = availabilityCache.get(item.id);
            boolean missing = "MISSING".equals(avail) || "PKG MISSING".equals(avail) || "NO ACT".equals(avail);
            if (!missing) {
                installedCount++;
            }
            if (!FILTER_ALL.equals(selectedCategory) && !selectedCategory.equals(item.category)) {
                continue;
            }
            if (hideMissing && missing) {
                continue;
            }
            visible.add(item);
        }

        summaryLabel.setText("Showing " + visible.size() + " of " + allItems.size()
                + " Launcher_WP3 entries (" + installedCount + " resolved/broadcast on unit)");

        int cols = 2;
        LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(c);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 12;
                listHost.addView(row, rp);
            }
            View card = buildItemCard(c, visible.get(i));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i % cols == 0) {
                cp.rightMargin = 12;
            }
            row.addView(card, cp);
        }
        if (visible.size() % cols == 1 && row != null) {
            row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1f));
        }
    }

    private View buildItemCard(Context c, final StockMenuCatalog.StockItem item) {
        String avail = availabilityCache.get(item.id);
        boolean isMissing = "MISSING".equals(avail) || "PKG MISSING".equals(avail) || "NO ACT".equals(avail);
        boolean isBcast = "BCAST".equals(avail) || "PKG+BCAST".equals(avail);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 12, 16, 12);
        card.setBackground(makeBox(isMissing ? 0x990C1320 : 0xD8111C2D,
                isMissing ? 0x33475569 : (isBcast ? 0x5538BDF8 : 0x5510B981), 14f, 2));

        // Top row: Title + Availability pill
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(c);
        title.setText(item.title);
        title.setTextColor(isMissing ? 0xFF94A3B8 : 0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = new TextView(c);
        badge.setText(avail);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        badge.setPadding(10, 4, 10, 4);
        if (isMissing) {
            badge.setTextColor(0xFFFCA5A5);
            badge.setBackground(makeBox(0x667F1D1D, 0x88EF4444, 8f, 1));
        } else if (isBcast) {
            badge.setTextColor(0xFF7DD3FC);
            badge.setBackground(makeBox(0x660E4A6F, 0x8838BDF8, 8f, 1));
        } else {
            badge.setTextColor(0xFF6EE7B7);
            badge.setBackground(makeBox(0x66065F46, 0x8810B981, 8f, 1));
        }
        top.addView(badge);
        card.addView(top);

        // Target descriptor
        TextView target = new TextView(c);
        target.setText(item.category + "  \u2022  " + item.smaliRef + "  \u2022  " + item.describeTarget());
        target.setTextColor(0xFF94A3B8);
        target.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        target.setSingleLine(true);
        target.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = 4;
        tp.bottomMargin = 8;
        card.addView(target, tp);

        // Action row + inline status
        LinearLayout actionsRow = new LinearLayout(c);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);

        final TextView inlineResult = new TextView(c);
        String prev = lastResultById.get(item.id);
        inlineResult.setText(prev == null ? "Untested" : prev);
        inlineResult.setTextColor(colorForResult(prev));
        inlineResult.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        inlineResult.setSingleLine(true);
        inlineResult.setEllipsize(TextUtils.TruncateAt.END);

        TextView appBtn = makeBtn(c, "Test (App)", 0xFF0E4A6F, 0xFF38BDF8, 14f);
        appBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                String res = item.triggerViaApp(getContext());
                recordResult(item, "[APP] " + res, inlineResult);
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.rightMargin = 8;
        actionsRow.addView(appBtn, ap);

        if (item.mode != StockMenuCatalog.Mode.CLIMATE_SMART) {
            TextView rootBtn = makeBtn(c, "Test (Root am)", 0xFF311B4E, 0xFFA78BFA, 14f);
            rootBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sound.playClick(v);
                    recordResult(item, "[ROOT am] running...", inlineResult);
                    item.triggerViaRoot(new SwitcherClient.SimpleCallback() {
                        @Override
                        public void onResult(boolean ok, String message) {
                            String res = (ok ? "OK: " : "ERR: ") + message;
                            recordResult(item, "[ROOT am] " + res, inlineResult);
                        }
                    });
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.rightMargin = 10;
            actionsRow.addView(rootBtn, rp);
        }

        actionsRow.addView(inlineResult, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(actionsRow);
        return card;
    }

    private void recordResult(StockMenuCatalog.StockItem item, String result, TextView inlineResult) {
        lastResultById.put(item.id, result);
        int color = colorForResult(result);
        inlineResult.setText(result);
        inlineResult.setTextColor(color);
        lastOutputView.setText(item.title + "  ->  " + result);
        lastOutputView.setTextColor(color == 0xFF64748B ? 0xFFE2E8F0 : color);
    }

    private static int colorForResult(String r) {
        if (r == null) return 0xFF64748B;
        if (r.contains("OK:")) return 0xFF34D399;
        if (r.contains("ERR:")) return 0xFFF87171;
        return 0xFFFBBF24;
    }

    private static TextView makeBtn(Context c, String label, int fill, int border, float textPx) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(14, 8, 14, 8);
        tv.setBackground(makeBox(fill, border, 10f, 2));
        return tv;
    }

    private static GradientDrawable makeBox(int fill, int border, float radius, int strokePx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke(strokePx, border);
        return gd;
    }
}
