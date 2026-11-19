package com.gen5w.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gen5w.launcher.action.AppLauncher;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.ipc.TelemetryModel;
import com.gen5w.launcher.model.ThemePack;
import com.gen5w.launcher.render.ThemePackLoader;

import java.io.File;
import java.util.Map;

/**
 * Interactive Phase 1 control console for Home Switcher tests, 5-layer failsafe inspection,
 * USB ThemePack testing, and live HMG-API telemetry diagnostics.
 */
final class SwitcherOverlayDialog extends FrameLayout {
    interface Actions {
        void onSendSwitcherCmd(String cmd);
        void onSwitchPackSourceAndReload();
        void onOpenThemesRequested();
        void onOpenStockTesterRequested();
        void onCloseRequested();
        String getPackSourceMode();
        ThemePack getCurrentPack();
    }

    private final SafeClickSoundPlayer sound;
    private final Actions actions;
    private final TextView statusSummary;
    private final TextView failsafeDetail;
    private final TextView telemetryDetail;
    private final TextView packDetail;
    private final TextView confirmBtn;
    private final TextView testBtn;

    SwitcherOverlayDialog(Context context, SafeClickSoundPlayer sound, Actions actions) {
        super(context);
        this.sound = sound;
        this.actions = actions;
        setBackgroundColor(0xEE060B12);
        setClickable(true);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(36, 24, 36, 24);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 14);

        TextView title = new TextView(context);
        title.setText("Home Switcher \u2022 5-Layer Failsafe & Pack Inspector");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 27f);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView closeBtn = makeBtn(context, "Close", 0xFF1E293B, 0xFF64748B);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onCloseRequested();
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);

        // Left column: Home Switcher status + action controls
        LinearLayout leftCol = makeCard(context);
        leftCol.setOrientation(LinearLayout.VERTICAL);

        statusSummary = addHeading(context, leftCol, "Checking home-switcher...");
        failsafeDetail = addMono(context, leftCol, "");

        LinearLayout btnRow1 = new LinearLayout(context);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r1p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r1p.topMargin = 14;

        testBtn = makeBtn(context, "Test Custom Home (45s)", 0xFF0E4A6F, 0xFF38BDF8);
        testBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onSendSwitcherCmd("ACTIVATE_TEST");
            }
        });
        confirmBtn = makeBtn(context, "Confirm Custom Home", 0xFF065F46, 0xFF10B981);
        confirmBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onSendSwitcherCmd("CONFIRM");
            }
        });
        addWeightedBtn(btnRow1, testBtn, 1f, 10);
        addWeightedBtn(btnRow1, confirmBtn, 1f, 0);
        leftCol.addView(btnRow1, r1p);

        LinearLayout btnRow2 = new LinearLayout(context);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = 10;

        TextView revertBtn = makeBtn(context, "Revert to Stock Home", 0xFF7F1D1D, 0xFFEF4444);
        revertBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onSendSwitcherCmd("REVERT");
            }
        });
        TextView clearBtn = makeBtn(context, "Clear Boot Strikes", 0xFF334155, 0xFF94A3B8);
        clearBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onSendSwitcherCmd("CLEAR_STRIKES");
            }
        });
        addWeightedBtn(btnRow2, revertBtn, 1f, 10);
        addWeightedBtn(btnRow2, clearBtn, 1f, 0);
        leftCol.addView(btnRow2, r2p);

        TextView openStockBtn = makeBtn(context, "Open Stock Launcher (Stay Here)", 0xFF1E293B, 0xFF60A5FA);
        openStockBtn.setGravity(Gravity.CENTER);
        openStockBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                AppLauncher.launchStockHome(getContext());
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = 10;
        leftCol.addView(openStockBtn, sp);

        TextView stockTesterBtn = makeBtn(context, "Open Stock Launcher Buttons Tester", 0xFF1E3A5F, 0xFF38BDF8);
        stockTesterBtn.setGravity(Gravity.CENTER);
        stockTesterBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onCloseRequested();
                SwitcherOverlayDialog.this.actions.onOpenStockTesterRequested();
            }
        });
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.topMargin = 10;
        leftCol.addView(stockTesterBtn, stp);

        // Right column: ThemePack Inspector + Live HMG-API Telemetry
        LinearLayout rightCol = makeCard(context);
        rightCol.setOrientation(LinearLayout.VERTICAL);

        addHeading(context, rightCol, "ThemePack v1 Engine (Windows App Target)");
        packDetail = addMono(context, rightCol, "");

        TextView packBtn = makeBtn(context, "Open Theme Store & Packs", 0xFF065F46, 0xFF10B981);
        packBtn.setGravity(Gravity.CENTER);
        packBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SwitcherOverlayDialog.this.sound.playClick(v);
                SwitcherOverlayDialog.this.actions.onCloseRequested();
                SwitcherOverlayDialog.this.actions.onOpenThemesRequested();
            }
        });
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbp.topMargin = 10;
        pbp.bottomMargin = 14;
        rightCol.addView(packBtn, pbp);

        addHeading(context, rightCol, "Live HMG-API Vehicle Telemetry");
        telemetryDetail = addMono(context, rightCol, "Waiting for telemetry...");

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = 16;
        body.addView(leftCol, lp);
        body.addView(rightCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scroll.addView(body);
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    void update(SwitcherClient.Status st, TelemetryModel.Snapshot tel) {
        if (!st.serviceReachable) {
            statusSummary.setText("home-switcher service OFFLINE");
            statusSummary.setTextColor(0xFFFCA5A5);
        } else if (st.deadmanRemainingSec > 0) {
            statusSummary.setText("45s Deadman Test Active — " + st.deadmanRemainingSec + "s left");
            statusSummary.setTextColor(0xFFFCD34D);
        } else if (!st.trippedReason.isEmpty()) {
            statusSummary.setText("SAFE MODE TRIPPED: " + st.trippedReason);
            statusSummary.setTextColor(0xFFF87171);
        } else if (st.isCustomHome) {
            statusSummary.setText("Gen5W Home is DEFAULT HOME");
            statusSummary.setTextColor(0xFF34D399);
        } else {
            statusSummary.setText("Stock Launcher_WP3 is active HOME");
            statusSummary.setTextColor(0xFF38BDF8);
        }

        confirmBtn.setEnabled(st.serviceReachable && st.isCustomHome);
        confirmBtn.setAlpha(confirmBtn.isEnabled() ? 1.0f : 0.45f);
        testBtn.setEnabled(st.serviceReachable);

        StringBuilder fs = new StringBuilder();
        fs.append("APK Version    : ").append(st.appVersionName).append(" (code ").append(st.appVersionCode).append(")\n");
        fs.append("Preferred HOME : ").append(st.activeHome).append('\n');
        fs.append("Confirmed Boot : ").append(st.homeEnabled).append('\n');
        fs.append("Boot Strikes   : ").append(st.strikes).append(" / 2\n");
        fs.append("Boot Grace Left: ").append(st.graceRemainingSec).append("s\n");
        fs.append("Health Confirm : ").append(st.healthConfirmRemainingSec).append("s\n");
        fs.append("USB Safety     : ").append(st.usbSignal).append('\n');
        fs.append("Launcher PID   : ").append(st.appPid).append("  (60s transitions: ").append(st.crashesIn60s).append(")\n");
        fs.append("Last Event     : ").append(st.lastEvent);
        if (!st.lastError.isEmpty()) {
            fs.append("\nLast Error     : ").append(st.lastError);
        }
        failsafeDetail.setText(fs.toString());

        ThemePack pack = actions.getCurrentPack();
        File usbPack = ThemePackLoader.findUsbPreviewDir();
        StringBuilder pd = new StringBuilder();
        pd.append("Pack ID / Name : ").append(pack.id).append(" / ").append(pack.name).append('\n');
        pd.append("Format / Ver   : v").append(pack.formatVersion).append(" / ").append(pack.version).append('\n');
        pd.append("Source Mode    : ").append(actions.getPackSourceMode())
                .append("  (").append(pack.sourceLabel).append(")\n");
        pd.append("Reference Grid : ").append(pack.referenceWidth).append('x').append(pack.referenceHeight).append('\n');
        pd.append("Backdrop Mode  : ").append(pack.background.type).append('\n');
        pd.append("USB Preview    : ").append(usbPack == null ? "Not found (/Gen5WLauncher/pack/layout.json)" : usbPack.getAbsolutePath());
        packDetail.setText(pd.toString());

        StringBuilder td = new StringBuilder();
        if (!tel.connected) {
            td.append("HMG-API Status : disconnected (").append(tel.error).append(")");
        } else {
            for (Map.Entry<String, String> e : tel.allValues.entrySet()) {
                td.append(String.format("%-20s : %s\n", e.getKey(), e.getValue()));
            }
        }
        telemetryDetail.setText(td.toString().trim());
    }

    private static LinearLayout makeCard(Context c) {
        LinearLayout ll = new LinearLayout(c);
        ll.setPadding(22, 18, 22, 18);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xD80E1726);
        gd.setCornerRadius(18f);
        gd.setStroke(2, 0x44477BA8);
        ll.setBackground(gd);
        return ll;
    }

    private static TextView addHeading(Context c, LinearLayout parent, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        parent.addView(tv, lp);
        return tv;
    }

    private static TextView addMono(Context c, LinearLayout parent, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0xFFCBD5E1);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        parent.addView(tv);
        return tv;
    }

    private static TextView makeBtn(Context c, String text, int bg, int border) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(18, 13, 18, 13);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(14f);
        gd.setStroke(2, border);
        tv.setBackground(gd);
        return tv;
    }

    private static void addWeightedBtn(LinearLayout row, View btn, float weight, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = rightMargin;
        row.addView(btn, lp);
    }
}
