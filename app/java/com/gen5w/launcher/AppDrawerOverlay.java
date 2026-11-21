package com.gen5w.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.launcher.action.AppCatalogOverrides;
import com.gen5w.launcher.action.AppLauncher;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Full-screen Gen5W all-apps drawer featuring clean human-readable names, curated SVG badges,
 * live Manage/Hide mode, and an inline per-app Rename / SVG Icon / Hide customizer.
 */
final class AppDrawerOverlay extends FrameLayout {
    interface Listener {
        void onCloseRequested();
        void onOpenCarControlsRequested();
        void onOpenThemedSettingsLabRequested();
    }

    private static final int DRAWER_COLS = 5;
    private static final int ICON_GRID_COLS = 7;

    private final SafeClickSoundPlayer sound;
    private final Listener listener;
    private final TextView titleLabel;
    private final TextView subtitleLabel;
    private final TextView svgToggleBtn;
    private final TextView resetAllBtn;
    private final TextView manageBtn;
    private final LinearLayout container;
    private final FrameLayout modalScrim;

    private List<SwitcherClient.ModLauncherItem> cachedModEntries;
    private boolean manageMode = false;
    private final AtomicBoolean modRefreshRunning = new AtomicBoolean(false);

    AppDrawerOverlay(Context context, SafeClickSoundPlayer sound, Listener listener) {
        super(context);
        this.sound = sound;
        this.listener = listener;
        setBackgroundColor(0xEC060C16);
        setClickable(true);

        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(32, 22, 32, 22);
        addView(shell, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, 14);

        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        titleLabel = new TextView(context);
        titleLabel.setText("All Applications");
        titleLabel.setTextColor(0xFFFFFFFF);
        titleLabel.setTypeface(Typeface.DEFAULT_BOLD);
        titleLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 28f);
        titleCol.addView(titleLabel);

        subtitleLabel = new TextView(context);
        subtitleLabel.setTextColor(0xFF94A3B8);
        subtitleLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        titleCol.addView(subtitleLabel);

        header.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView carCtrlBtn = makeHeaderBtn(context, "Car Controls", 0xFF0E3554, 0xFF38BDF8);
        carCtrlBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                dismissCustomizeModal();
                manageMode = false;
                if (AppDrawerOverlay.this.listener != null) {
                    AppDrawerOverlay.this.listener.onOpenCarControlsRequested();
                }
            }
        });
        LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ccLp.setMargins(0, 0, 10, 0);
        header.addView(carCtrlBtn, ccLp);

        TextView labBtn = makeHeaderBtn(context, "Setup Theme Lab", 0xFF0E3B3E, 0xFF10B981);
        labBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                dismissCustomizeModal();
                manageMode = false;
                if (AppDrawerOverlay.this.listener != null) {
                    AppDrawerOverlay.this.listener.onOpenThemedSettingsLabRequested();
                }
            }
        });
        LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labLp.setMargins(0, 0, 10, 0);
        header.addView(labBtn, labLp);

        svgToggleBtn = makeHeaderBtn(context, "SVG Icons: ON", 0xFF0C2D48, 0xFF38BDF8);
        svgToggleBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                boolean next = !AppCatalogOverrides.isPreferSvgIcons(getContext());
                AppCatalogOverrides.setPreferSvgIcons(getContext(), next);
                refreshFromCatalog();
            }
        });
        LinearLayout.LayoutParams svgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        svgLp.setMargins(0, 0, 10, 0);
        header.addView(svgToggleBtn, svgLp);

        resetAllBtn = makeHeaderBtn(context, "Reset Defaults", 0xFF3B1D14, 0xFFF97316);
        resetAllBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                AppCatalogOverrides.resetAllOverrides(getContext());
                refreshFromCatalog();
                Toast.makeText(getContext(), "App names, icons & hidden defaults restored", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams rstLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rstLp.setMargins(0, 0, 10, 0);
        header.addView(resetAllBtn, rstLp);

        manageBtn = makeHeaderBtn(context, "Manage / Hide Apps", 0xFF1E293B, 0xFF475569);
        manageBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                manageMode = !manageMode;
                refreshFromCatalog();
            }
        });
        LinearLayout.LayoutParams mgrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mgrLp.setMargins(0, 0, 10, 0);
        header.addView(manageBtn, mgrLp);

        TextView closeBtn = makeHeaderBtn(context, "Close", 0xFF1E293B, 0xFF475569);
        closeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                AppDrawerOverlay.this.sound.playClick(v);
                dismissCustomizeModal();
                manageMode = false;
                if (AppDrawerOverlay.this.listener != null) {
                    AppDrawerOverlay.this.listener.onCloseRequested();
                }
            }
        });
        header.addView(closeBtn);
        shell.addView(header);

        ScrollView scroll = new ScrollView(context);
        container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(container);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        modalScrim = new FrameLayout(context);
        modalScrim.setBackgroundColor(0xCC020617);
        modalScrim.setVisibility(View.GONE);
        modalScrim.setClickable(true);
        modalScrim.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                dismissCustomizeModal();
            }
        });
        addView(modalScrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        reload();
    }

    boolean onBackPressed() {
        if (modalScrim.getVisibility() == View.VISIBLE) {
            dismissCustomizeModal();
            return true;
        }
        if (manageMode) {
            manageMode = false;
            refreshFromCatalog();
            return true;
        }
        return false;
    }

    void reload() {
        manageMode = false;
        dismissCustomizeModal();
        refreshFromCatalog();
        if (!modRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<SwitcherClient.ModLauncherItem> fresh = SwitcherClient.fetchHmgApiLauncherEntriesBlocking();
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (fresh != null && (!fresh.isEmpty() || cachedModEntries == null)) {
                                if (sameModSignature(cachedModEntries, fresh)) return;
                                cachedModEntries = fresh;
                                refreshFromCatalog();
                            }
                        }
                    });
                } finally {
                    modRefreshRunning.set(false);
                }
            }
        }, "drawer-mod-refresh");
        t.setDaemon(true);
        t.start();
    }

    private void refreshFromCatalog() {
        syncHeaderState();
        AppLauncher.DrawerSections sections = AppLauncher.loadDrawerSections(
                getContext(), cachedModEntries, manageMode);
        renderSections(sections);
    }

    private void syncHeaderState() {
        boolean preferSvg = AppCatalogOverrides.isPreferSvgIcons(getContext());
        svgToggleBtn.setText(preferSvg ? "SVG Icons: ON" : "SVG Icons: OFF");
        applyBox(svgToggleBtn, preferSvg ? 0xFF0C3554 : 0xFF1E293B, preferSvg ? 0xFF38BDF8 : 0xFF475569, 10f);
        svgToggleBtn.setVisibility(manageMode ? View.VISIBLE : View.GONE);
        resetAllBtn.setVisibility(manageMode ? View.VISIBLE : View.GONE);

        manageBtn.setText(manageMode ? "Done Managing" : "Manage / Hide Apps");
        applyBox(manageBtn, manageMode ? 0xFF0369A1 : 0xFF1E293B, manageMode ? 0xFF38BDF8 : 0xFF475569, 10f);
        titleLabel.setText(manageMode ? "Manage Applications" : "All Applications");
    }

    private void renderSections(AppLauncher.DrawerSections sections) {
        container.removeAllViews();
        String hiddenNote = sections.hiddenCount > 0 ? (sections.hiddenCount + " hidden") : "0 hidden";
        if (manageMode) {
            subtitleLabel.setText(hiddenNote + "  \u2022  Tap an app to rename or change SVG icon, or tap HIDE / SHOW");
        } else {
            subtitleLabel.setText(hiddenNote + "  \u2022  Long-press any app to rename, change SVG icon, or hide");
        }

        if (!sections.stockAndHmgApi.isEmpty()) {
            addSectionHeader(
                    "STOCK LAUNCHER & HMG-API (" + sections.stockAndHmgApi.size() + ")",
                    "NEMESIS, HMG-API mods & curated OEM vehicle shortcuts",
                    0xFF38BDF8,
                    false);
            addAppGrid(sections.stockAndHmgApi);
        }

        if (!sections.allApps.isEmpty()) {
            addSectionHeader(
                    "ALL INSTALLED APPS (" + sections.allApps.size() + ")",
                    "Every installed head unit activity & third-party app",
                    0xFF94A3B8,
                    !sections.stockAndHmgApi.isEmpty());
            addAppGrid(sections.allApps);
        }
    }

    private void addSectionHeader(String titleText, String subtitleText, int accentColor, boolean topGap) {
        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topGap ? 22 : 4;
        lp.bottomMargin = 10;

        TextView title = new TextView(getContext());
        title.setText(titleText);
        title.setTextColor(accentColor);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        bar.addView(title);

        if (subtitleText != null && !subtitleText.isEmpty()) {
            TextView sub = new TextView(getContext());
            sub.setText("  \u2022  " + subtitleText);
            sub.setTextColor(0xFF94A3B8);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
            bar.addView(sub);
        }
        container.addView(bar, lp);
    }

    private void addAppGrid(List<AppLauncher.AppEntry> apps) {
        LinearLayout currentRow = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % DRAWER_COLS == 0) {
                currentRow = new LinearLayout(getContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = 12;
                container.addView(currentRow, rowLp);
            }
            AppLauncher.AppEntry entry = apps.get(i);
            View tile = createAppTile(entry);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if ((i % DRAWER_COLS) < DRAWER_COLS - 1) cp.rightMargin = 12;
            currentRow.addView(tile, cp);
        }
        int rem = apps.size() % DRAWER_COLS;
        if (rem != 0 && currentRow != null) {
            for (int j = rem; j < DRAWER_COLS; j++) {
                View spacer = new View(getContext());
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                if (j < DRAWER_COLS - 1) sp.rightMargin = 12;
                currentRow.addView(spacer, sp);
            }
        }
    }

    private View createAppTile(final AppLauncher.AppEntry app) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(14, 12, 14, 12);
        tile.setMinimumHeight(76);
        tile.setAlpha(app.hidden ? 0.56f : 1.0f);

        int fillColor = app.hidden ? 0xFF1A140B : (app.isHmgApi ? 0xCC0D233D : 0xB80F172A);
        int strokeColor = app.hidden ? 0xFFB45309 : (app.isHmgApi ? 0xFF38BDF8 : 0xFF1E3A5F);
        applyBox(tile, fillColor, strokeColor, 14f);

        ImageView iconView = new ImageView(getContext());
        if (app.icon != null) {
            iconView.setImageDrawable(app.icon);
        } else {
            iconView.setImageDrawable(makeMonogramFallback(app.label, app.isHmgApi));
        }
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(48, 48);
        ilp.rightMargin = 12;
        tile.addView(iconView, ilp);

        LinearLayout textCol = new LinearLayout(getContext());
        textCol.setOrientation(LinearLayout.VERTICAL);

        LinearLayout nameRow = new LinearLayout(getContext());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(getContext());
        name.setText(app.label);
        name.setTextColor(app.hidden ? 0xFFFDE68A : 0xFFFFFFFF);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (app.hidden) {
            nameRow.addView(makeTinyBadge("HIDDEN", 0xFFFDE68A, 0xFF451A03, 0xFFD97706));
        } else if (app.isHmgApi && !app.badgeText.isEmpty()) {
            nameRow.addView(makeTinyBadge(app.badgeText, 0xFFBAE6FD, 0xFF0C4A6E, 0xFF38BDF8));
        }
        textCol.addView(nameRow);

        TextView sub = new TextView(getContext());
        sub.setText(app.subtitle);
        sub.setTextColor(app.isHmgApi ? 0xFF93C5FD : 0xFF94A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(sub);

        tile.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (manageMode) {
            TextView toggleHideBtn = new TextView(getContext());
            toggleHideBtn.setText(app.hidden ? "SHOW" : "HIDE");
            toggleHideBtn.setTextColor(app.hidden ? 0xFF86EFAC : 0xFFFCA5A5);
            toggleHideBtn.setTypeface(Typeface.DEFAULT_BOLD);
            toggleHideBtn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
            toggleHideBtn.setPadding(12, 8, 12, 8);
            applyBox(toggleHideBtn, app.hidden ? 0xFF14532D : 0xFF450A0A, app.hidden ? 0xFF22C55E : 0xFFEF4444, 8f);
            toggleHideBtn.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sound.playClick(v);
                    AppCatalogOverrides.setHidden(getContext(), app.entryKey, !app.hidden);
                    refreshFromCatalog();
                }
            });
            LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hLp.leftMargin = 8;
            tile.addView(toggleHideBtn, hLp);
        }

        tile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                if (manageMode) {
                    openCustomizeModal(app);
                    return;
                }
                if (AppLauncher.launchEntry(getContext(), app)) {
                    if (listener != null) {
                        listener.onCloseRequested();
                    }
                }
            }
        });
        tile.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                sound.playClick(v);
                openCustomizeModal(app);
                return true;
            }
        });
        return tile;
    }

    private void openCustomizeModal(final AppLauncher.AppEntry app) {
        modalScrim.removeAllViews();
        final boolean[] draftHidden = new boolean[] { app.hidden };
        final String[] draftIconMode = new String[] { app.iconOverride };

        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(24, 18, 24, 18);
        card.setClickable(true);
        applyBox(card, 0xFF0F172A, 0xFF38BDF8, 16f);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(1280, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        cardLp.setMargins(40, 24, 40, 24);
        modalScrim.addView(card, cardLp);

        // Modal Header
        LinearLayout hdr = new LinearLayout(getContext());
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(0, 0, 0, 12);

        final ImageView previewIcon = new ImageView(getContext());
        previewIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        updateModalPreviewIcon(previewIcon, app, draftIconMode[0]);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(52, 52);
        prevLp.rightMargin = 14;
        hdr.addView(previewIcon, prevLp);

        LinearLayout titleBox = new LinearLayout(getContext());
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView hdrTitle = new TextView(getContext());
        hdrTitle.setText("Customize App");
        hdrTitle.setTextColor(0xFFFFFFFF);
        hdrTitle.setTypeface(Typeface.DEFAULT_BOLD);
        hdrTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        titleBox.addView(hdrTitle);

        TextView compSub = new TextView(getContext());
        compSub.setText(app.entryKey);
        compSub.setTextColor(0xFF64748B);
        compSub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        compSub.setSingleLine(true);
        compSub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleBox.addView(compSub);
        hdr.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView visBtn = makeHeaderBtn(getContext(), "", 0xFF14532D, 0xFF22C55E);
        syncVisibilityPill(visBtn, draftHidden[0]);
        visBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                draftHidden[0] = !draftHidden[0];
                syncVisibilityPill(visBtn, draftHidden[0]);
            }
        });
        hdr.addView(visBtn);
        card.addView(hdr);

        // Scrollable Modal Body
        ScrollView bodyScroll = new ScrollView(getContext());
        LinearLayout body = new LinearLayout(getContext());
        body.setOrientation(LinearLayout.VERTICAL);

        TextView nameSection = makeModalLabel("Display Name");
        body.addView(nameSection);

        final EditText nameInput = new EditText(getContext());
        nameInput.setText(app.label);
        nameInput.setTextColor(0xFFFFFFFF);
        nameInput.setHintTextColor(0xFF64748B);
        nameInput.setHint("Enter clean display name");
        nameInput.setTextSize(TypedValue.COMPLEX_UNIT_PX, 17f);
        nameInput.setSingleLine(true);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameInput.setPadding(16, 12, 16, 12);
        applyBox(nameInput, 0xFF09111E, 0xFF334155, 10f);
        LinearLayout.LayoutParams editLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        editLp.bottomMargin = 8;
        body.addView(nameInput, editLp);

        // Quick Name Suggestion Chips (max 4 per row)
        if (!app.nameSuggestions.isEmpty()) {
            TextView sugLbl = new TextView(getContext());
            sugLbl.setText("Quick Name Suggestions (tap to fill):");
            sugLbl.setTextColor(0xFF94A3B8);
            sugLbl.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
            sugLbl.setPadding(0, 0, 0, 6);
            body.addView(sugLbl);

            LinearLayout chipRow = null;
            int chipCols = 4;
            for (int i = 0; i < app.nameSuggestions.size(); i++) {
                if (i % chipCols == 0) {
                    chipRow = new LinearLayout(getContext());
                    chipRow.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    crLp.bottomMargin = 8;
                    body.addView(chipRow, crLp);
                }
                final String suggestion = app.nameSuggestions.get(i);
                TextView chip = new TextView(getContext());
                chip.setText(suggestion);
                chip.setTextColor(0xFFBAE6FD);
                chip.setTypeface(Typeface.DEFAULT_BOLD);
                chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
                chip.setGravity(Gravity.CENTER);
                chip.setSingleLine(true);
                chip.setEllipsize(TextUtils.TruncateAt.END);
                chip.setPadding(10, 9, 10, 9);
                applyBox(chip, 0xFF0C2D48, 0xFF0284C7, 8f);
                chip.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        sound.playClick(v);
                        nameInput.setText(suggestion);
                        nameInput.setSelection(suggestion.length());
                    }
                });
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if ((i % chipCols) < chipCols - 1) cLp.rightMargin = 8;
                chipRow.addView(chip, cLp);
            }
            int rem = app.nameSuggestions.size() % chipCols;
            if (rem != 0 && chipRow != null) {
                for (int j = rem; j < chipCols; j++) {
                    View sp = new View(getContext());
                    LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (j < chipCols - 1) sLp.rightMargin = 8;
                    chipRow.addView(sp, sLp);
                }
            }
        }

        TextView iconSection = makeModalLabel("Icon Style & Built-in SVG Picker");
        iconSection.setPadding(0, 6, 0, 8);
        body.addView(iconSection);

        final List<Runnable> iconRefreshers = new ArrayList<Runnable>();
        LinearLayout modeRow = new LinearLayout(getContext());
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mrLp.bottomMargin = 10;

        final TextView autoModeBtn = makeModePill("Auto Curated", AppCatalogOverrides.ICON_MODE_AUTO,
                draftIconMode, previewIcon, app, iconRefreshers);
        final TextView apkModeBtn = makeModePill("Original APK Icon", AppCatalogOverrides.ICON_MODE_APK,
                draftIconMode, previewIcon, app, iconRefreshers);
        LinearLayout.LayoutParams m1Lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        m1Lp.rightMargin = 10;
        modeRow.addView(autoModeBtn, m1Lp);
        modeRow.addView(apkModeBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(modeRow, mrLp);

        // 7-column SVG icon grid
        List<AppCatalogOverrides.IconChoice> choices = AppCatalogOverrides.getIconChoices();
        LinearLayout gridRow = null;
        for (int i = 0; i < choices.size(); i++) {
            if (i % ICON_GRID_COLS == 0) {
                gridRow = new LinearLayout(getContext());
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams grLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                grLp.bottomMargin = 8;
                body.addView(gridRow, grLp);
            }
            final AppCatalogOverrides.IconChoice ic = choices.get(i);
            final LinearLayout cell = new LinearLayout(getContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(6, 8, 6, 8);

            ImageView iv = new ImageView(getContext());
            Drawable svgDraw = AppLauncher.buildBadgedSvgIcon(getContext(), ic.svgPath, app.isHmgApi);
            if (svgDraw != null) iv.setImageDrawable(svgDraw);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(iv, new LinearLayout.LayoutParams(36, 36));

            TextView lbl = new TextView(getContext());
            lbl.setText(ic.label);
            lbl.setTextColor(0xFFE2E8F0);
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f);
            lbl.setGravity(Gravity.CENTER);
            lbl.setSingleLine(true);
            lbl.setEllipsize(TextUtils.TruncateAt.END);
            lbl.setPadding(0, 4, 0, 0);
            cell.addView(lbl);

            iconRefreshers.add(new Runnable() {
                @Override
                public void run() {
                    boolean selected = ic.svgPath.equals(draftIconMode[0]);
                    applyBox(cell, selected ? 0xFF0369A1 : 0xFF1E293B, selected ? 0xFF38BDF8 : 0xFF334155, 10f);
                }
            });
            cell.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    sound.playClick(v);
                    draftIconMode[0] = ic.svgPath;
                    for (Runnable r : iconRefreshers) r.run();
                    updateModalPreviewIcon(previewIcon, app, draftIconMode[0]);
                }
            });

            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if ((i % ICON_GRID_COLS) < ICON_GRID_COLS - 1) cellLp.rightMargin = 8;
            gridRow.addView(cell, cellLp);
        }

        for (Runnable r : iconRefreshers) r.run();

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400);
        bodyScroll.addView(body);
        card.addView(bodyScroll, scrollLp);

        // Modal Footer
        LinearLayout footer = new LinearLayout(getContext());
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(0, 14, 0, 0);

        TextView resetAppBtn = makeHeaderBtn(getContext(), "Reset App", 0xFF3B1D14, 0xFFF97316);
        resetAppBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                AppCatalogOverrides.clearEntryOverride(getContext(), app.entryKey);
                dismissCustomizeModal();
                refreshFromCatalog();
            }
        });
        footer.addView(resetAppBtn);

        footer.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));

        TextView cancelBtn = makeHeaderBtn(getContext(), "Cancel", 0xFF1E293B, 0xFF475569);
        cancelBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                dismissCustomizeModal();
            }
        });
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.rightMargin = 10;
        footer.addView(cancelBtn, cLp);

        TextView saveBtn = makeHeaderBtn(getContext(), "Save Changes", 0xFF0369A1, 0xFF38BDF8);
        saveBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                String typed = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
                String customToSave = (typed.isEmpty() || typed.equals(app.defaultLabel)) ? "" : typed;
                AppCatalogOverrides.setCustomization(
                        getContext(), app.entryKey, draftHidden[0], customToSave, draftIconMode[0]);
                dismissCustomizeModal();
                refreshFromCatalog();
            }
        });
        footer.addView(saveBtn);
        card.addView(footer);

        modalScrim.setVisibility(View.VISIBLE);
        modalScrim.bringToFront();
    }

    private TextView makeModePill(
            String text,
            final String modeValue,
            final String[] draftIconMode,
            final ImageView previewIcon,
            final AppLauncher.AppEntry app,
            final List<Runnable> iconRefreshers) {
        final TextView btn = makeHeaderBtn(getContext(), text, 0xFF1E293B, 0xFF334155);
        btn.setGravity(Gravity.CENTER);
        iconRefreshers.add(new Runnable() {
            @Override
            public void run() {
                boolean active = modeValue.equals(draftIconMode[0]);
                applyBox(btn, active ? 0xFF0369A1 : 0xFF1E293B, active ? 0xFF38BDF8 : 0xFF334155, 10f);
            }
        });
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                sound.playClick(v);
                draftIconMode[0] = modeValue;
                for (Runnable r : iconRefreshers) r.run();
                updateModalPreviewIcon(previewIcon, app, draftIconMode[0]);
            }
        });
        return btn;
    }

    private void updateModalPreviewIcon(ImageView iv, AppLauncher.AppEntry app, String draftMode) {
        Drawable d = null;
        if (draftMode != null && !draftMode.isEmpty() && !AppCatalogOverrides.ICON_MODE_APK.equals(draftMode)) {
            d = AppLauncher.buildBadgedSvgIcon(getContext(), draftMode, app.isHmgApi);
        } else if (AppCatalogOverrides.ICON_MODE_AUTO.equals(draftMode)
                && AppCatalogOverrides.isPreferSvgIcons(getContext())
                && !app.resolvedSvgPath.isEmpty()) {
            d = AppLauncher.buildBadgedSvgIcon(getContext(), app.resolvedSvgPath, app.isHmgApi);
        }
        if (d == null) d = app.icon;
        if (d == null) d = makeMonogramFallback(app.label, app.isHmgApi);
        iv.setImageDrawable(d);
    }

    private void syncVisibilityPill(TextView btn, boolean hidden) {
        btn.setText(hidden ? "Status: Hidden from Drawer" : "Status: Visible in Drawer");
        applyBox(btn, hidden ? 0xFF451A03 : 0xFF14532D, hidden ? 0xFFD97706 : 0xFF22C55E, 10f);
    }

    private void dismissCustomizeModal() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
        modalScrim.removeAllViews();
        modalScrim.setVisibility(View.GONE);
    }

    private TextView makeModalLabel(String text) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextColor(0xFFE2E8F0);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        tv.setPadding(0, 0, 0, 6);
        return tv;
    }

    private TextView makeTinyBadge(String text, int textColor, int fillColor, int strokeColor) {
        TextView badge = new TextView(getContext());
        badge.setText(text);
        badge.setTextColor(textColor);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f);
        badge.setPadding(7, 2, 7, 2);
        applyBox(badge, fillColor, strokeColor, 6f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = 6;
        badge.setLayoutParams(blp);
        return badge;
    }

    private static TextView makeHeaderBtn(Context ctx, String text, int fillColor, int strokeColor) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        btn.setPadding(18, 10, 18, 10);
        applyBox(btn, fillColor, strokeColor, 10f);
        return btn;
    }

    private static void applyBox(View v, int fillColor, int strokeColor, float radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fillColor);
        bg.setStroke(2, strokeColor);
        bg.setCornerRadius(radius);
        v.setBackground(bg);
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
                    || !x.title.equals(y.title)
                    || !Arrays.equals(x.iconBytes, y.iconBytes)) {
                return false;
            }
        }
        return true;
    }

    private static Drawable makeMonogramFallback(String label, final boolean highlight) {
        String letters = "";
        if (label != null) {
            String[] words = label.trim().split("\\s+");
            for (int i = 0; i < words.length && letters.length() < 2; i++) {
                String w = words[i].replaceAll("[^A-Za-z0-9]", "");
                if (!w.isEmpty()) {
                    letters += Character.toUpperCase(w.charAt(0));
                }
            }
            if (letters.isEmpty()) {
                String clean = label.replaceAll("[^A-Za-z0-9]", "");
                if (!clean.isEmpty()) {
                    letters = clean.substring(0, Math.min(2, clean.length())).toUpperCase();
                }
            }
        }
        if (letters.isEmpty()) letters = "APP";
        final String text = letters;
        return new Drawable() {
            private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                bg.setColor(highlight ? 0xFF0E7490 : 0xFF1D4ED8);
                border.setStyle(Paint.Style.STROKE);
                border.setStrokeWidth(2f);
                border.setColor(highlight ? 0xFF38BDF8 : 0xFF60A5FA);
                fg.setColor(0xFFFFFFFF);
                fg.setTypeface(Typeface.DEFAULT_BOLD);
                fg.setTextAlign(Paint.Align.CENTER);
            }

            @Override
            public void draw(Canvas canvas) {
                RectF r = new RectF(getBounds());
                r.inset(2f, 2f);
                float rad = Math.min(r.width(), r.height()) * 0.28f;
                canvas.drawRoundRect(r, rad, rad, bg);
                canvas.drawRoundRect(r, rad, rad, border);
                fg.setTextSize(r.height() * 0.42f);
                Paint.FontMetrics fm = fg.getFontMetrics();
                float cy = r.centerY() - (fm.ascent + fm.descent) * 0.5f;
                canvas.drawText(text, r.centerX(), cy, fg);
            }

            @Override
            public void setAlpha(int alpha) {
                bg.setAlpha(alpha);
                fg.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                fg.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }
}
