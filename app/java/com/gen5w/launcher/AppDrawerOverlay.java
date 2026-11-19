package com.gen5w.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gen5w.launcher.action.AppLauncher;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;

import java.util.List;
import java.util.Locale;

/**
 * Full-width scrollable All-Apps drawer overlay presenting Stock Launcher + HMG-API
 * shortcuts first, followed by a complete section of all installed launchable activities.
 */
final class AppDrawerOverlay extends FrameLayout {
    interface Listener {
        void onCloseRequested();
    }

    private final SafeClickSoundPlayer sound;
    private final Listener listener;
    private final LinearLayout gridHost;
    private List<SwitcherClient.ModLauncherItem> cachedModEntries;
    private int modFetchSeq = 0;

    AppDrawerOverlay(Context context, SafeClickSoundPlayer sound, Listener listener) {
        super(context);
        this.sound = sound;
        this.listener = listener;
        setBackgroundColor(0xEE070C14);
        setClickable(true);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(36, 24, 36, 22);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 14);

        TextView title = new TextView(context);
        title.setText("All Applications");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, tp);

        TextView closeBtn = makeButton(context, "Close", 0xFF1E293B, 0xFF64748B);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                if (AppDrawerOverlay.this.listener != null) {
                    AppDrawerOverlay.this.listener.onCloseRequested();
                }
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        ScrollView scroll = new ScrollView(context);
        gridHost = new LinearLayout(context);
        gridHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(gridHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        reload();
    }

    void reload() {
        renderSections(AppLauncher.loadDrawerSections(getContext(), cachedModEntries));
        final int seq = ++modFetchSeq;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<SwitcherClient.ModLauncherItem> fresh = SwitcherClient.fetchHmgApiLauncherEntriesBlocking();
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != modFetchSeq) return;
                        if (sameModSignature(cachedModEntries, fresh)) return;
                        cachedModEntries = fresh;
                        renderSections(AppLauncher.loadDrawerSections(getContext(), fresh));
                    }
                });
            }
        }, "drawer-hmgapi-mods");
        t.setDaemon(true);
        t.start();
    }

    private void renderSections(AppLauncher.DrawerSections sections) {
        gridHost.removeAllViews();
        addSectionHeader(
                "STOCK LAUNCHER & HMG-API (" + sections.stockAndHmgApi.size() + ")",
                "OEM stock home icons plus custom HMG-API mod shortcuts",
                0xFF38BDF8,
                false);
        addAppGrid(sections.stockAndHmgApi);

        addSectionHeader(
                "ALL INSTALLED APPS (" + sections.allApps.size() + ")",
                "Complete list of launchable OEM, system & side-loaded activities",
                0xFF34D399,
                true);
        addAppGrid(sections.allApps);
    }

    private void addSectionHeader(String titleText, String subtitleText, int accentColor, boolean topGap) {
        Context c = getContext();
        if (topGap) {
            View div = new View(c);
            div.setBackgroundColor(0x3364748B);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2);
            dp.topMargin = 14;
            dp.bottomMargin = 16;
            gridHost.addView(div, dp);
        }

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.bottomMargin = 10;

        TextView t = new TextView(c);
        t.setText(titleText);
        t.setTextColor(accentColor);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f);
        row.addView(t);

        TextView s = new TextView(c);
        s.setText("   \u2022   " + subtitleText);
        s.setTextColor(0xFF94A3B8);
        s.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(s, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        gridHost.addView(row, rp);
    }

    private void addAppGrid(List<AppLauncher.AppEntry> apps) {
        final int cols = 5;
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 12;
                gridHost.addView(row, rp);
            }
            View card = createAppTile(apps.get(i));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, 118, 1f);
            if ((i % cols) < cols - 1) cp.rightMargin = 12;
            row.addView(card, cp);
        }
        if (row != null) {
            int rem = apps.size() % cols;
            if (rem != 0) {
                for (int j = rem; j < cols; j++) {
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 118, 1f);
                    if (j < cols - 1) sp.rightMargin = 12;
                    row.addView(new View(getContext()), sp);
                }
            }
        }
    }

    private View createAppTile(final AppLauncher.AppEntry app) {
        Context c = getContext();
        LinearLayout tile = new LinearLayout(c);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(14, 10, 14, 10);

        int bgColor = app.isHmgApi ? 0xD8152942 : (app.isOemPreset ? 0xD0112236 : 0xD016273B);
        int borderColor = app.isHmgApi ? 0xAA38BDF8 : (app.isOemPreset ? 0x5538BDF8 : 0x5534D399);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bgColor);
        gd.setCornerRadius(16f);
        gd.setStroke(2, borderColor);
        tile.setBackground(gd);

        if (app.icon != null) {
            ImageView iv = new ImageView(c);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageDrawable(app.icon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(52, 52);
            ip.rightMargin = 12;
            tile.addView(iv, ip);
        } else {
            TextView badgeIcon = new TextView(c);
            badgeIcon.setGravity(Gravity.CENTER);
            badgeIcon.setText(initialsFor(app.label));
            badgeIcon.setTextColor(0xFFBAE6FD);
            badgeIcon.setTypeface(Typeface.DEFAULT_BOLD);
            badgeIcon.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
            GradientDrawable igd = new GradientDrawable();
            igd.setColor(0x550EA5E9);
            igd.setCornerRadius(14f);
            igd.setStroke(1, 0x7738BDF8);
            badgeIcon.setBackground(igd);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(52, 52);
            ip.rightMargin = 12;
            tile.addView(badgeIcon, ip);
        }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(c);
        label.setText(app.label);
        label.setTextColor(0xFFFFFFFF);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(label);

        TextView sub = new TextView(c);
        String subTxt = app.subtitle == null ? "" : app.subtitle.trim();
        if (subTxt.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setText(subTxt);
            sub.setTextColor("HMG-API".equals(app.badgeText) ? 0xFF7DD3FC : 0xFF94A3B8);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
        }
        col.addView(sub);

        tile.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                AppLauncher.launchEntry(getContext(), app);
                if (listener != null) {
                    listener.onCloseRequested();
                }
            }
        });
        return tile;
    }

    private static String initialsFor(String label) {
        if (label == null || label.trim().isEmpty()) return "APP";
        String clean = label.trim().toUpperCase(Locale.US);
        String[] parts = clean.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return "" + parts[0].charAt(0) + parts[1].charAt(0);
        }
        return clean.length() >= 2 ? clean.substring(0, 2) : clean;
    }

    private static boolean sameModSignature(
            List<SwitcherClient.ModLauncherItem> a,
            List<SwitcherClient.ModLauncherItem> b) {
        if (a == b) return true;
        if (a == null || b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            SwitcherClient.ModLauncherItem x = a.get(i);
            SwitcherClient.ModLauncherItem y = b.get(i);
            if (!x.modId.equals(y.modId)
                    || !x.packageName.equals(y.packageName)
                    || !x.className.equals(y.className)
                    || !x.title.equals(y.title)) {
                return false;
            }
            int lenX = x.iconBytes == null ? 0 : x.iconBytes.length;
            int lenY = y.iconBytes == null ? 0 : y.iconBytes.length;
            if (lenX != lenY) return false;
        }
        return true;
    }

    private static TextView makeButton(Context c, String text, int fill, int border) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
        tv.setPadding(24, 12, 24, 12);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(14f);
        gd.setStroke(2, border);
        tv.setBackground(gd);
        return tv;
    }
}
