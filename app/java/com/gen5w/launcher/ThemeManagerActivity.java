package com.gen5w.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.ipc.TelemetryModel;
import com.gen5w.launcher.model.ThemePack;
import com.gen5w.launcher.render.AssetResolver;
import com.gen5w.launcher.render.ThemePackLoader;
import com.gen5w.launcher.render.ThemePackManager;
import com.gen5w.launcher.store.StoreThemeListing;
import com.gen5w.launcher.store.StoreThumbnailLoader;
import com.gen5w.launcher.store.ThemeStoreClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone automotive Theme Manager & Online Theme Store activity
 * (com.gen5w.launcher/.ThemeManagerActivity), accessible both from themes that expose
 * an open_themes button and directly via NEMESIS Manager.
 */
public final class ThemeManagerActivity extends Activity implements TelemetryModel.Listener {
    public static final String EXTRA_TAB = "tab";
    public static final String TAB_INSTALLED = "installed";
    public static final String TAB_STORE = "store";

    private static final String DEFAULT_STORE_CATEGORY = "All Categories";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TelemetryModel telemetry = new TelemetryModel();
    private final ThemeStoreClient storeClient = new ThemeStoreClient();

    private SafeClickSoundPlayer soundPlayer;
    private StoreThumbnailLoader thumbnailLoader;
    private Drawable defaultPackIcon;
    private String currentAppVersion = "0.4.9";

    private FrameLayout rootFrame;
    private TextView activeSummaryView;
    private TextView tabInstalledBtn;
    private TextView tabStoreBtn;
    private FrameLayout tabContentHost;

    // Installed & USB view state
    private LinearLayout installedRoot;
    private TextView usbHintView;
    private TextView freeSpaceView;
    private LinearLayout installedGridHost;

    // Theme Store view state
    private LinearLayout storeRoot;
    private TextView categoryPickerBtn;
    private EditText searchBox;
    private TextView clearSearchBtn;
    private TextView storeCountView;
    private LinearLayout storeStatusBanner;
    private LinearLayout storeGridHost;

    private final List<String> serverCategories = new ArrayList<String>();
    private List<StoreThemeListing> currentStoreThemes = new ArrayList<StoreThemeListing>();
    private String selectedCategory = DEFAULT_STORE_CATEGORY;
    private String activeQuery = "";
    private boolean storeLoadedOnce = false;
    private boolean storeLoading = false;
    private String storeErrorMsg = "";
    private int catalogRequestSeq = 0;

    // Modals
    private FrameLayout detailModal;
    private StoreThemeListing detailStoreListing;
    private ThemePackManager.PackEntry detailLocalEntry;
    private LinearLayout detailProgressArea;
    private ProgressBar detailProgressBar;
    private TextView detailProgressText;
    private TextView detailDownloadApplyBtn;
    private TextView detailDownloadOnlyBtn;
    private TextView detailCancelBtn;
    private volatile boolean downloadInFlight = false;
    private volatile boolean downloadCancelRequested = false;
    private volatile int activeDownloadSeq = 0;
    private volatile boolean usbImportInFlight = false;
    private int installedReloadSeq = 0;

    private SwitcherOverlayDialog switcherDialog;
    private StockButtonsOverlay stockButtonsOverlay;
    private ThemedSettingsLabOverlay themedSettingsLab;
    private SwitcherClient.Status latestSwitcherStatus = new SwitcherClient.Status();

    private String activeTab = TAB_INSTALLED;
    private boolean resumed = false;

    private final Runnable switcherTicker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
                SwitcherClient.sendAsync("STATUS", new SwitcherClient.Callback() {
                    @Override
                    public void onResult(SwitcherClient.Status status) {
                        latestSwitcherStatus = status;
                        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
                            switcherDialog.update(status, telemetry.getLatest());
                        }
                    }
                });
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

        soundPlayer = new SafeClickSoundPlayer(this);
        SharedPreferences prefs = getSharedPreferences(ThemePackManager.PREF_NAME, MODE_PRIVATE);
        soundPlayer.setMode(prefs.getString("sound_mode", "system"));
        thumbnailLoader = new StoreThumbnailLoader(this, storeClient);
        currentAppVersion = resolveAppVersionName();
        try {
            defaultPackIcon = new AssetResolver(this, "").loadDrawable("icons/home.svg");
        } catch (Throwable ignored) {
        }

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(0xFF070B12);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(28, 18, 28, 16);
        rootFrame.addView(shell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        shell.addView(buildHeaderBar());

        tabContentHost = new FrameLayout(this);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tp.topMargin = 12;
        shell.addView(tabContentHost, tp);

        buildInstalledTab();
        buildStoreTab();
        tabContentHost.addView(installedRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabContentHost.addView(storeRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        telemetry.setListener(this);

        String requestedTab = getIntent() != null ? getIntent().getStringExtra(EXTRA_TAB) : null;
        switchTab(TAB_STORE.equals(requestedTab) ? TAB_STORE : TAB_INSTALLED);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String requestedTab = intent != null ? intent.getStringExtra(EXTRA_TAB) : null;
        if (TAB_STORE.equals(requestedTab) || TAB_INSTALLED.equals(requestedTab)) {
            switchTab(requestedTab);
        }
        reloadInstalledPacks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        reloadInstalledPacks();
        mainHandler.removeCallbacks(switcherTicker);
        mainHandler.post(switcherTicker);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(switcherTicker);
        telemetry.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        downloadCancelRequested = true;
        activeDownloadSeq++;
        mainHandler.removeCallbacksAndMessages(null);
        telemetry.setListener(null);
        telemetry.stop();
        soundPlayer.release();
        thumbnailLoader.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (themedSettingsLab != null && themedSettingsLab.getVisibility() == View.VISIBLE) {
            themedSettingsLab.setVisibility(View.GONE);
            return;
        }
        if (stockButtonsOverlay != null && stockButtonsOverlay.getVisibility() == View.VISIBLE) {
            stockButtonsOverlay.setVisibility(View.GONE);
            return;
        }
        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
            switcherDialog.setVisibility(View.GONE);
            telemetry.stop();
            return;
        }
        if (detailModal != null && detailModal.getVisibility() == View.VISIBLE) {
            closeDetailModal();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onTelemetryUpdated(TelemetryModel.Snapshot snapshot) {
        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
            switcherDialog.update(latestSwitcherStatus, snapshot);
        }
    }

    private View buildHeaderBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(18, 12, 18, 12);
        bar.setBackground(makeBox(0xD80D1624, 0x44477BA8, 18f, 2));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Gen5W Themes & Store");
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f);
        activeSummaryView = new TextView(this);
        activeSummaryView.setTextColor(0xFF7DD3FC);
        activeSummaryView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        activeSummaryView.setSingleLine(true);
        activeSummaryView.setEllipsize(TextUtils.TruncateAt.END);
        titleCol.addView(title);
        titleCol.addView(activeSummaryView);
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcp.rightMargin = 16;
        bar.addView(titleCol, tcp);

        // Segmented tab buttons
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(4, 4, 4, 4);
        tabs.setBackground(makeBox(0xFF0A111D, 0x44568BB5, 14f, 1));

        tabInstalledBtn = makeHeaderBtn("Installed & USB", 0xFF0E4A6F, 0xFF38BDF8);
        tabInstalledBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                switchTab(TAB_INSTALLED);
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.rightMargin = 6;
        tabs.addView(tabInstalledBtn, ip);

        tabStoreBtn = makeHeaderBtn("Theme Store", 0xFF0A111D, 0x00000000);
        tabStoreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                switchTab(TAB_STORE);
            }
        });
        tabs.addView(tabStoreBtn);

        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsLp.rightMargin = 14;
        bar.addView(tabs, tabsLp);

        // Utility buttons
        TextView failsafeBtn = makeHeaderBtn("Default Home / Failsafe", 0xFF132840, 0xFF38BDF8);
        failsafeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                openSwitcherOverlay();
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.rightMargin = 10;
        bar.addView(failsafeBtn, fp);

        TextView homeBtn = makeHeaderBtn("Open Gen5W Home", 0xFF065F46, 0xFF10B981);
        homeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                openGen5WHome();
            }
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.rightMargin = 10;
        bar.addView(homeBtn, hp);

        TextView closeBtn = makeHeaderBtn("Close", 0xFF1E293B, 0xFF64748B);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                finish();
            }
        });
        bar.addView(closeBtn);
        return bar;
    }

    private void switchTab(String tab) {
        activeTab = TAB_STORE.equals(tab) ? TAB_STORE : TAB_INSTALLED;
        boolean isStore = TAB_STORE.equals(activeTab);
        installedRoot.setVisibility(isStore ? View.GONE : View.VISIBLE);
        storeRoot.setVisibility(isStore ? View.VISIBLE : View.GONE);

        tabInstalledBtn.setBackground(makeBox(isStore ? 0x00000000 : 0xFF0E4A6F, isStore ? 0x00000000 : 0xFF38BDF8, 10f, isStore ? 0 : 2));
        tabInstalledBtn.setTextColor(isStore ? 0xFF94A3B8 : 0xFFFFFFFF);
        tabStoreBtn.setBackground(makeBox(isStore ? 0xFF0E4A6F : 0x00000000, isStore ? 0xFF38BDF8 : 0x00000000, 10f, isStore ? 2 : 0));
        tabStoreBtn.setTextColor(isStore ? 0xFFFFFFFF : 0xFF94A3B8);

        if (!isStore) {
            hideSoftKeyboard();
            reloadInstalledPacks();
        } else if (!storeLoadedOnce && !storeLoading) {
            fetchStoreCatalogAsync();
        }
    }

    // -------------------------------------------------------------------------
    // Tab 1: Installed & USB Themes
    // -------------------------------------------------------------------------

    private void buildInstalledTab() {
        installedRoot = new LinearLayout(this);
        installedRoot.setOrientation(LinearLayout.VERTICAL);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(6, 4, 6, 10);

        usbHintView = new TextView(this);
        usbHintView.setText("USB paths: /Gen5WLauncher/pack, /packs/<name>, and *.zip bundles");
        usbHintView.setTextColor(0xFF94A3B8);
        usbHintView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        toolbar.addView(usbHintView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        freeSpaceView = new TextView(this);
        freeSpaceView.setTextColor(0xFFCBD5E1);
        freeSpaceView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        freeSpaceView.setPadding(12, 6, 12, 6);
        freeSpaceView.setBackground(makeBox(0xFF101928, 0x44568BB5, 10f, 1));
        LinearLayout.LayoutParams fsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fsp.rightMargin = 12;
        toolbar.addView(freeSpaceView, fsp);

        TextView rescanBtn = makeBtn("Rescan USB", 0xFF0E4A6F, 0xFF38BDF8, 16f);
        rescanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                reloadInstalledPacks();
                Toast.makeText(ThemeManagerActivity.this, "Rescanned local & USB themes", Toast.LENGTH_SHORT).show();
            }
        });
        toolbar.addView(rescanBtn);
        installedRoot.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        installedGridHost = new LinearLayout(this);
        installedGridHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(installedGridHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        installedRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void reloadInstalledPacks() {
        final int seq = ++installedReloadSeq;
        final Context appCtx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final ThemePack activePack = ThemePackLoader.load(
                        appCtx,
                        appCtx.getSharedPreferences(ThemePackManager.PREF_NAME, MODE_PRIVATE)
                                .getString(ThemePackLoader.PACK_PREF_SOURCE, "auto"));
                final long freeMb = ThemePackManager.getAvailableInternalBytes(appCtx) / (1024L * 1024L);
                final List<ThemePackManager.PackEntry> packs = ThemePackManager.discoverAllPacks(appCtx);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != installedReloadSeq || isFinishing() || isDestroyed()) {
                            return;
                        }
                        activeSummaryView.setText("Active: " + activePack.name + "  (" + activePack.sourceLabel + ")");
                        freeSpaceView.setText("Unit Free: " + freeMb + " MB");
                        if (themedSettingsLab != null) {
                            themedSettingsLab.updateActiveThemePack(activePack);
                        }
                        renderInstalledPackCards(packs);
                    }
                });
            }
        }, "theme-packs-scan");
        t.setDaemon(true);
        t.start();
    }

    private void renderInstalledPackCards(List<ThemePackManager.PackEntry> packs) {
        installedGridHost.removeAllViews();

        int cols = getResources().getDisplayMetrics().widthPixels >= 1200 ? 3 : 2;
        LinearLayout row = null;
        for (int i = 0; i < packs.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 14;
                installedGridHost.addView(row, rp);
            }
            View card = buildInstalledPackCard(packs.get(i));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if ((i % cols) < cols - 1) cp.rightMargin = 14;
            row.addView(card, cp);
        }
        if (row != null) {
            int rem = packs.size() % cols;
            if (rem != 0) {
                for (int j = rem; j < cols; j++) {
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (j < cols - 1) sp.rightMargin = 14;
                    row.addView(new View(this), sp);
                }
            }
        }
    }

    private View buildInstalledPackCard(final ThemePackManager.PackEntry entry) {
        boolean isUsb = entry.sourceType == ThemePackManager.SourceType.USB
                || entry.sourceType == ThemePackManager.SourceType.USB_ZIP;
        int border = entry.isActive ? 0xFF10B981 : (isUsb ? 0xFF38BDF8 : 0x44568BB5);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 14, 16, 14);
        card.setBackground(makeBox(0xD8101A29, border, 16f, entry.isActive ? 3 : 2));

        // Thumbnail preview box
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(makeBox(0xFF080E18, 0x33477BA8, 12f, 1));
        LinearLayout.LayoutParams pfp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 152);
        pfp.bottomMargin = 10;

        if (entry.thumbnailFile != null || entry.zipFile != null || (entry.zipThumbnailBytes != null && entry.zipThumbnailBytes.length > 0)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            previewFrame.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumbnailLoader.loadLocalThumbnail(iv, entry.thumbnailFile, entry.zipFile, entry.zipThumbnailBytes, 520, 195);
        } else {
            LinearLayout placeholder = new LinearLayout(this);
            placeholder.setOrientation(LinearLayout.VERTICAL);
            placeholder.setGravity(Gravity.CENTER);
            if (defaultPackIcon != null) {
                ImageView icon = new ImageView(this);
                icon.setImageDrawable(defaultPackIcon.getConstantState() != null
                        ? defaultPackIcon.getConstantState().newDrawable(getResources())
                        : defaultPackIcon);
                placeholder.addView(icon, new LinearLayout.LayoutParams(42, 42));
            }
            TextView noThumb = new TextView(this);
            noThumb.setText(entry.sourceType == ThemePackManager.SourceType.BUNDLED ? "Built-in Default Theme" : "No Thumbnail Preview");
            noThumb.setTextColor(0xFF64748B);
            noThumb.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
            noThumb.setPadding(0, 6, 0, 0);
            placeholder.addView(noThumb);
            previewFrame.addView(placeholder, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Category pill (top-left) if available
        if (!entry.category.isEmpty()) {
            TextView catBadge = makeOverlayPill(entry.category, 0xCC0B1320, 0xFF7DD3FC);
            FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            cbp.setMargins(8, 8, 8, 8);
            previewFrame.addView(catBadge, cbp);
        }

        // Active / source badge (top-right)
        String srcLabel = entry.isActive ? "ACTIVE"
                : (entry.sourceType == ThemePackManager.SourceType.USB_ZIP ? "USB ZIP"
                : (entry.sourceType == ThemePackManager.SourceType.USB ? "USB DIR"
                : entry.sourceType.name()));
        TextView srcBadge = makeOverlayPill(
                srcLabel,
                entry.isActive ? 0xCC064E3B : (isUsb ? 0xCC0C3B5E : 0xCC1E293B),
                entry.isActive ? 0xFF6EE7B7 : (isUsb ? 0xFF7DD3FC : 0xFFCBD5E1));
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
        sbp.setMargins(8, 8, 8, 8);
        previewFrame.addView(srcBadge, sbp);

        previewFrame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                openLocalDetailModal(entry);
            }
        });
        card.addView(previewFrame, pfp);

        // Title & meta
        TextView name = new TextView(this);
        name.setText(entry.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        TextView meta = new TextView(this);
        meta.setText(entry.author + "  \u2022  v" + entry.version
                + (entry.revision > 1 ? " (r" + entry.revision + ")" : "")
                + "  \u2022  " + entry.id);
        meta.setTextColor(0xFF94A3B8);
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.topMargin = 3;
        mp.bottomMargin = 10;
        card.addView(meta, mp);

        if (!entry.error.isEmpty()) {
            TextView err = new TextView(this);
            err.setText("Invalid: " + entry.error);
            err.setTextColor(0xFFFCA5A5);
            err.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
            card.addView(err);
            return card;
        }

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        if (entry.sourceType == ThemePackManager.SourceType.USB_ZIP) {
            TextView impZipBtn = makeBtn("Import ZIP & Apply", 0xFF065F46, 0xFF10B981, 15f);
            impZipBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    importUsbZipAsync(entry);
                }
            });
            addRowBtn(btnRow, impZipBtn, 0);
        } else if (entry.sourceType == ThemePackManager.SourceType.USB) {
            TextView impBtn = makeBtn("Import & Apply", 0xFF065F46, 0xFF10B981, 15f);
            impBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    importUsbDirAsync(entry);
                }
            });
            addRowBtn(btnRow, impBtn, 8);

            TextView prevBtn = makeBtn("Preview Live", 0xFF1E3A5F, 0xFF38BDF8, 15f);
            prevBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    ThemePackManager.setActiveUsbPreviewDir(ThemeManagerActivity.this, entry.dir);
                    reloadInstalledPacks();
                    Toast.makeText(ThemeManagerActivity.this, "Live USB preview active: " + entry.name, Toast.LENGTH_SHORT).show();
                }
            });
            addRowBtn(btnRow, prevBtn, 0);
        } else {
            TextView activateBtn = makeBtn(
                    entry.isActive ? "Active Now" : "Activate",
                    entry.isActive ? 0xFF1E293B : 0xFF0E4A6F,
                    entry.isActive ? 0xFF10B981 : 0xFF38BDF8,
                    15f);
            activateBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    String srcMode = entry.sourceType == ThemePackManager.SourceType.BUNDLED ? "default" : "auto";
                    ThemePackManager.setActivePack(ThemeManagerActivity.this, entry.id, srcMode);
                    reloadInstalledPacks();
                    Toast.makeText(ThemeManagerActivity.this, "Activated " + entry.name, Toast.LENGTH_SHORT).show();
                }
            });
            addRowBtn(btnRow, activateBtn, 8);

            TextView detBtn = makeBtn("Details", 0xFF172538, 0xFF475569, 15f);
            detBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    openLocalDetailModal(entry);
                }
            });
            addRowBtn(btnRow, detBtn, entry.sourceType == ThemePackManager.SourceType.INSTALLED ? 8 : 0);

            if (entry.sourceType == ThemePackManager.SourceType.INSTALLED) {
                TextView delBtn = makeBtn("Delete", 0xFF7F1D1D, 0xFFEF4444, 15f);
                delBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        soundPlayer.playClick(v);
                        confirmDeleteInstalledPack(entry);
                    }
                });
                addRowBtn(btnRow, delBtn, 0);
            }
        }
        card.addView(btnRow);
        return card;
    }

    private void confirmDeleteInstalledPack(final ThemePackManager.PackEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + entry.name + "\"?")
                .setMessage("This removes the downloaded theme files from internal storage on the head unit.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ThemePackManager.deleteInstalledPack(ThemeManagerActivity.this, entry.id);
                        reloadInstalledPacks();
                        if (storeLoadedOnce) {
                            renderStoreCards();
                        }
                        Toast.makeText(ThemeManagerActivity.this, "Deleted " + entry.name, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void importUsbDirAsync(final ThemePackManager.PackEntry entry) {
        if (usbImportInFlight) {
            return;
        }
        usbImportInFlight = true;
        Toast.makeText(this, "Importing " + entry.name + "...", Toast.LENGTH_SHORT).show();
        final Context appCtx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ThemePackManager.importFromUsbAndActivate(appCtx, entry.dir);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbImportInFlight = false;
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThemeManagerActivity.this, "Imported & activated " + entry.name, Toast.LENGTH_SHORT).show();
                            reloadInstalledPacks();
                        }
                    });
                } catch (final Throwable ex) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbImportInFlight = false;
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThemeManagerActivity.this, "USB import failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "usb-dir-importer");
        t.setDaemon(true);
        t.start();
    }

    private void importUsbZipAsync(final ThemePackManager.PackEntry entry) {
        if (usbImportInFlight) {
            return;
        }
        usbImportInFlight = true;
        Toast.makeText(this, "Verifying & extracting " + entry.name + "...", Toast.LENGTH_SHORT).show();
        final Context appCtx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ThemePackManager.importFromUsbZipAndActivate(appCtx, entry.zipFile);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbImportInFlight = false;
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThemeManagerActivity.this, "Imported & activated " + entry.name, Toast.LENGTH_SHORT).show();
                            reloadInstalledPacks();
                        }
                    });
                } catch (final Throwable ex) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            usbImportInFlight = false;
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(ThemeManagerActivity.this, "USB ZIP blocked: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }, "usb-zip-importer");
        t.setDaemon(true);
        t.start();
    }

    // -------------------------------------------------------------------------
    // Tab 2: Online Theme Store Browser (https://market.ioniqthemes.com/)
    // -------------------------------------------------------------------------

    private void buildStoreTab() {
        storeRoot = new LinearLayout(this);
        storeRoot.setOrientation(LinearLayout.VERTICAL);

        // Filter & Search bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(12, 8, 12, 8);
        bar.setBackground(makeBox(0xCC0D1624, 0x33477BA8, 14f, 1));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = 10;

        categoryPickerBtn = makeBtn("Category: All Categories \u25BC", 0xFF132236, 0xFF38BDF8, 15f);
        categoryPickerBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                showCategoryPickerDialog();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = 10;
        bar.addView(categoryPickerBtn, cp);

        searchBox = new EditText(this);
        searchBox.setHint("Search name, author, tag...");
        searchBox.setHintTextColor(0xFF64748B);
        searchBox.setTextColor(0xFFFFFFFF);
        searchBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f);
        searchBox.setSingleLine(true);
        searchBox.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.setPadding(14, 8, 14, 8);
        searchBox.setBackground(makeBox(0xFF080E18, 0x44477BA8, 10f, 1));
        searchBox.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    triggerStoreSearch();
                    return true;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sbp.rightMargin = 8;
        bar.addView(searchBox, sbp);

        TextView searchBtn = makeBtn("Search", 0xFF0E4A6F, 0xFF38BDF8, 15f);
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                triggerStoreSearch();
            }
        });
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srp.rightMargin = 8;
        bar.addView(searchBtn, srp);

        clearSearchBtn = makeBtn("Clear", 0xFF1E293B, 0xFF64748B, 15f);
        clearSearchBtn.setVisibility(View.GONE);
        clearSearchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                searchBox.setText("");
                activeQuery = "";
                clearSearchBtn.setVisibility(View.GONE);
                hideSoftKeyboard();
                fetchStoreCatalogAsync();
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = 8;
        bar.addView(clearSearchBtn, clp);

        TextView sortBadge = new TextView(this);
        sortBadge.setText("Newest");
        sortBadge.setTextColor(0xFF7DD3FC);
        sortBadge.setTypeface(Typeface.DEFAULT_BOLD);
        sortBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        sortBadge.setPadding(12, 7, 12, 7);
        sortBadge.setBackground(makeBox(0xFF0B1828, 0x4438BDF8, 10f, 1));
        LinearLayout.LayoutParams sbp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp2.rightMargin = 8;
        bar.addView(sortBadge, sbp2);

        storeCountView = new TextView(this);
        storeCountView.setText("");
        storeCountView.setTextColor(0xFF94A3B8);
        storeCountView.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scp.rightMargin = 10;
        bar.addView(storeCountView, scp);

        TextView refreshBtn = makeBtn("Refresh", 0xFF132236, 0xFF475569, 15f);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                fetchStoreCatalogAsync();
            }
        });
        bar.addView(refreshBtn);
        storeRoot.addView(bar, barLp);

        // Creator callout banner pointing users to market.ioniqthemes.com
        LinearLayout creatorBar = new LinearLayout(this);
        creatorBar.setOrientation(LinearLayout.HORIZONTAL);
        creatorBar.setGravity(Gravity.CENTER_VERTICAL);
        creatorBar.setPadding(14, 8, 14, 8);
        creatorBar.setBackground(makeBox(0xCC081E26, 0x5510B981, 12f, 1));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.bottomMargin = 10;

        TextView creatorBadge = new TextView(this);
        creatorBadge.setText("MAKE YOUR OWN THEMES");
        creatorBadge.setTextColor(0xFF34D399);
        creatorBadge.setTypeface(Typeface.DEFAULT_BOLD);
        creatorBadge.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        creatorBadge.setPadding(10, 4, 10, 4);
        creatorBadge.setBackground(makeBox(0xFF064E3B, 0x6610B981, 8f, 1));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = 12;
        creatorBar.addView(creatorBadge, badgeLp);

        TextView creatorNote = new TextView(this);
        creatorNote.setText("Visit market.ioniqthemes.com on your computer to build & share your own custom themes with Gen5W Studio.");
        creatorNote.setTextColor(0xFFE2E8F0);
        creatorNote.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        creatorBar.addView(creatorNote, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        storeRoot.addView(creatorBar, cbLp);

        storeStatusBanner = new LinearLayout(this);
        storeStatusBanner.setOrientation(LinearLayout.VERTICAL);
        storeStatusBanner.setGravity(Gravity.CENTER);
        storeStatusBanner.setVisibility(View.GONE);
        storeRoot.addView(storeStatusBanner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        storeGridHost = new LinearLayout(this);
        storeGridHost.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(storeGridHost, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        storeRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private void triggerStoreSearch() {
        hideSoftKeyboard();
        activeQuery = searchBox.getText() == null ? "" : searchBox.getText().toString().trim();
        clearSearchBtn.setVisibility(activeQuery.isEmpty() ? View.GONE : View.VISIBLE);
        fetchStoreCatalogAsync();
    }

    private void showCategoryPickerDialog() {
        final List<String> opts = new ArrayList<String>();
        opts.add(DEFAULT_STORE_CATEGORY);
        for (String c : serverCategories) {
            if (!DEFAULT_STORE_CATEGORY.equalsIgnoreCase(c) && !opts.contains(c)) {
                opts.add(c);
            }
        }
        String[] items = opts.toArray(new String[opts.size()]);
        new AlertDialog.Builder(this)
                .setTitle("Store Categories")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which >= 0 && which < opts.size()) {
                            selectedCategory = opts.get(which);
                            categoryPickerBtn.setText("Category: " + selectedCategory + " \u25BC");
                            fetchStoreCatalogAsync();
                        }
                    }
                })
                .show();
    }

    private void fetchStoreCatalogAsync() {
        storeLoading = true;
        storeErrorMsg = "";
        final int seq = ++catalogRequestSeq;
        final String catParam = DEFAULT_STORE_CATEGORY.equalsIgnoreCase(selectedCategory) ? "" : selectedCategory;
        final String qParam = activeQuery;

        renderStoreStatusLoading();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final StoreThemeListing.CatalogResult res = storeClient.fetchCatalog(catParam, qParam);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != catalogRequestSeq || isFinishing() || isDestroyed()) {
                            return;
                        }
                        storeLoading = false;
                        if (!res.ok) {
                            storeErrorMsg = res.errorMessage;
                            currentStoreThemes = new ArrayList<StoreThemeListing>();
                            renderStoreCards();
                            return;
                        }
                        storeLoadedOnce = true;
                        storeErrorMsg = "";
                        serverCategories.clear();
                        serverCategories.addAll(res.categories);
                        if (!DEFAULT_STORE_CATEGORY.equalsIgnoreCase(selectedCategory)
                                && !containsCategoryIgnoreCase(serverCategories, selectedCategory)) {
                            selectedCategory = DEFAULT_STORE_CATEGORY;
                            categoryPickerBtn.setText("Category: " + selectedCategory + " \u25BC");
                        }
                        currentStoreThemes = new ArrayList<StoreThemeListing>(res.themes);
                        renderStoreCards();
                    }
                });
            }
        }, "store-catalog-fetch");
        t.setDaemon(true);
        t.start();
    }

    private void renderStoreStatusLoading() {
        storeStatusBanner.removeAllViews();
        storeStatusBanner.setVisibility(View.VISIBLE);
        storeGridHost.removeAllViews();
        storeCountView.setText("Loading...");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(24, 24, 24, 24);
        box.setBackground(makeBox(0xD80D1624, 0x4438BDF8, 16f, 1));

        ProgressBar pb = new ProgressBar(this);
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(40, 40);
        pbp.rightMargin = 16;
        box.addView(pb, pbp);

        TextView msg = new TextView(this);
        msg.setText("Connecting to market.ioniqthemes.com...");
        msg.setTextColor(0xFFE2E8F0);
        msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
        box.addView(msg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        storeStatusBanner.addView(box, lp);
    }

    private void renderStoreCards() {
        storeStatusBanner.removeAllViews();
        storeGridHost.removeAllViews();

        if (!storeErrorMsg.isEmpty()) {
            storeStatusBanner.setVisibility(View.VISIBLE);
            storeCountView.setText("Offline");

            LinearLayout errBox = new LinearLayout(this);
            errBox.setOrientation(LinearLayout.VERTICAL);
            errBox.setGravity(Gravity.CENTER_HORIZONTAL);
            errBox.setPadding(28, 24, 28, 24);
            errBox.setBackground(makeBox(0xD81E1015, 0xFFEF4444, 16f, 2));

            TextView h = new TextView(this);
            h.setText("Unable to Connect to Theme Store");
            h.setTextColor(0xFFFCA5A5);
            h.setTypeface(Typeface.DEFAULT_BOLD);
            h.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f);
            errBox.addView(h);

            TextView d = new TextView(this);
            d.setText(storeErrorMsg + "\n\nVerify Wi-Fi hotspot or phone tethering is connected on the head unit.");
            d.setTextColor(0xFFE2E8F0);
            d.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
            d.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp.topMargin = 8;
            dp.bottomMargin = 16;
            errBox.addView(d, dp);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView retryBtn = makeBtn("Retry Connection", 0xFF0E4A6F, 0xFF38BDF8, 16f);
            retryBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    fetchStoreCatalogAsync();
                }
            });
            LinearLayout.LayoutParams rbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rbp.rightMargin = 14;
            actions.addView(retryBtn, rbp);

            TextView wifiBtn = makeBtn("Wi-Fi Settings", 0xFF1E293B, 0xFF64748B, 16f);
            wifiBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    openWifiSettings();
                }
            });
            actions.addView(wifiBtn);
            errBox.addView(actions);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 16;
            storeStatusBanner.addView(errBox, lp);
            return;
        }

        if (currentStoreThemes.isEmpty()) {
            storeStatusBanner.setVisibility(View.VISIBLE);
            storeCountView.setText("0 themes");

            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyBox.setPadding(24, 24, 24, 24);
            emptyBox.setBackground(makeBox(0xD80D1624, 0x44477BA8, 16f, 1));

            TextView msg = new TextView(this);
            msg.setText("No themes matched your filter.");
            msg.setTextColor(0xFFCBD5E1);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f);
            emptyBox.addView(msg);

            if (!DEFAULT_STORE_CATEGORY.equalsIgnoreCase(selectedCategory) || !activeQuery.isEmpty()) {
                TextView showAllBtn = makeBtn("Show All Themes", 0xFF0E4A6F, 0xFF38BDF8, 15f);
                showAllBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        soundPlayer.playClick(v);
                        selectedCategory = DEFAULT_STORE_CATEGORY;
                        categoryPickerBtn.setText("Category: " + selectedCategory + " \u25BC");
                        searchBox.setText("");
                        activeQuery = "";
                        clearSearchBtn.setVisibility(View.GONE);
                        fetchStoreCatalogAsync();
                    }
                });
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bp.topMargin = 12;
                emptyBox.addView(showAllBtn, bp);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 16;
            storeStatusBanner.addView(emptyBox, lp);
            return;
        }

        storeStatusBanner.setVisibility(View.GONE);
        storeCountView.setText(currentStoreThemes.size() + (currentStoreThemes.size() == 1 ? " theme" : " themes"));

        Map<String, ThemePackManager.PackEntry> installedMap = ThemePackManager.mapInstalledByStoreOrPackId(this);
        int cols = getResources().getDisplayMetrics().widthPixels >= 1200 ? 3 : 2;
        LinearLayout row = null;
        for (int i = 0; i < currentStoreThemes.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = 14;
                storeGridHost.addView(row, rp);
            }
            View card = buildStoreThemeCard(currentStoreThemes.get(i), installedMap);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if ((i % cols) < cols - 1) cp.rightMargin = 14;
            row.addView(card, cp);
        }
        if (row != null) {
            int rem = currentStoreThemes.size() % cols;
            if (rem != 0) {
                for (int j = rem; j < cols; j++) {
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, 1, 1f);
                    if (j < cols - 1) sp.rightMargin = 14;
                    row.addView(new View(this), sp);
                }
            }
        }
    }

    private View buildStoreThemeCard(
            final StoreThemeListing listing,
            Map<String, ThemePackManager.PackEntry> installedMap) {
        final ThemePackManager.PackEntry match = findMatch(listing, installedMap);
        boolean isInstalled = match != null;
        boolean hasUpdate = isInstalled && (listing.revision > match.revision
                || StoreThemeListing.compareVersions(listing.version, match.version) > 0);
        boolean compat = listing.isCompatibleWith(currentAppVersion);

        int border = (match != null && match.isActive) ? 0xFF10B981
                : (hasUpdate ? 0xFFF59E0B : 0x5538BDF8);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 14, 16, 14);
        card.setBackground(makeBox(0xD8101A29, border, 16f, 2));

        // 8:3 preview container with prominent category & status badges
        FrameLayout previewBox = new FrameLayout(this);
        previewBox.setBackground(makeBox(0xFF070C15, 0x33477BA8, 12f, 1));
        LinearLayout.LayoutParams pbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 174);
        pbp.bottomMargin = 10;

        TextView fallbackTxt = new TextView(this);
        fallbackTxt.setText("Tap for Preview & Details");
        fallbackTxt.setTextColor(0xFF64748B);
        fallbackTxt.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        fallbackTxt.setGravity(Gravity.CENTER);
        previewBox.addView(fallbackTxt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        previewBox.addView(thumb, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        thumbnailLoader.loadRemoteThumbnail(thumb, listing.thumbnailUrl, 520, 195);

        if (!listing.category.isEmpty()) {
            TextView catPill = makeOverlayPill(listing.category, 0xD80A1525, 0xFF38BDF8);
            FrameLayout.LayoutParams cbp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            cbp.setMargins(8, 8, 8, 8);
            previewBox.addView(catPill, cbp);
        }

        if (isInstalled) {
            String badgeTxt = hasUpdate ? ("UPDATE r" + listing.revision)
                    : (match.isActive ? "ACTIVE" : "INSTALLED");
            int badgeBg = hasUpdate ? 0xCC78350F : 0xCC064E3B;
            int badgeFg = hasUpdate ? 0xFFFDE68A : 0xFF6EE7B7;
            TextView statePill = makeOverlayPill(badgeTxt, badgeBg, badgeFg);
            FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT);
            sbp.setMargins(8, 8, 8, 8);
            previewBox.addView(statePill, sbp);
        }
        card.addView(previewBox, pbp);

        TextView title = new TextView(this);
        title.setText(listing.name);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);

        String barLabel = listing.showSystemBar ? "Bar 630p" : "Full 720p";
        TextView sub = new TextView(this);
        sub.setText(listing.author
                + "  \u2022  v" + listing.version + " (r" + listing.revision + ")"
                + "  \u2022  " + StoreThemeListing.formatByteSize(listing.fileSizeBytes));
        sub.setTextColor(0xFF94A3B8);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = 3;
        sp.bottomMargin = 6;
        card.addView(sub, sp);

        LinearLayout footerRow = new LinearLayout(this);
        footerRow.setOrientation(LinearLayout.HORIZONTAL);
        footerRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView modeChip = new TextView(this);
        modeChip.setText(barLabel);
        modeChip.setTextColor(0xFFCBD5E1);
        modeChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        modeChip.setPadding(8, 3, 8, 3);
        modeChip.setBackground(makeBox(0xFF172538, 0x3364748B, 8f, 1));
        footerRow.addView(modeChip);

        if (!compat) {
            TextView warnChip = new TextView(this);
            warnChip.setText("Needs v" + listing.minAppVersion + "+");
            warnChip.setTextColor(0xFFFCD34D);
            warnChip.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
            warnChip.setPadding(8, 3, 8, 3);
            warnChip.setBackground(makeBox(0x5578350F, 0xFFF59E0B, 8f, 1));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wp.leftMargin = 8;
            footerRow.addView(warnChip, wp);
        }

        View spacer = new View(this);
        footerRow.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView tapHint = new TextView(this);
        tapHint.setText("View Details \u203A");
        tapHint.setTextColor(0xFF38BDF8);
        tapHint.setTypeface(Typeface.DEFAULT_BOLD);
        tapHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        footerRow.addView(tapHint);

        card.addView(footerRow);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                openStoreDetailModal(listing);
            }
        });
        return card;
    }

    // -------------------------------------------------------------------------
    // Enlarged Preview & Details Modal (Store + Local)
    // -------------------------------------------------------------------------

    private void openStoreDetailModal(StoreThemeListing listing) {
        if (listing == null) return;
        detailStoreListing = listing;
        detailLocalEntry = null;
        showDetailModalInternal();
    }

    private void openLocalDetailModal(ThemePackManager.PackEntry entry) {
        if (entry == null) return;
        detailStoreListing = null;
        detailLocalEntry = entry;
        showDetailModalInternal();
    }

    private void showDetailModalInternal() {
        detailDownloadApplyBtn = null;
        detailDownloadOnlyBtn = null;
        if (detailModal == null) {
            detailModal = new FrameLayout(this);
            detailModal.setBackgroundColor(0xEE04080F);
            detailModal.setClickable(true);
            rootFrame.addView(detailModal, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        detailModal.removeAllViews();

        LinearLayout dialog = new LinearLayout(this);
        dialog.setOrientation(LinearLayout.VERTICAL);
        dialog.setPadding(24, 18, 24, 18);
        dialog.setBackground(makeBox(0xF80D1624, 0x8838BDF8, 20f, 2));
        dialog.setClickable(true);

        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dlp.setMargins(38, 22, 38, 22);
        detailModal.addView(dialog, dlp);

        // Top modal title bar
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        topRow.setPadding(0, 0, 0, 12);

        String titleText = detailStoreListing != null ? detailStoreListing.name : detailLocalEntry.name;
        String catText = detailStoreListing != null ? detailStoreListing.category : detailLocalEntry.category;

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, 26f);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!catText.isEmpty()) {
            TextView catBadge = makeOverlayPill(catText, 0xFF0E3A5A, 0xFF7DD3FC);
            LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cbp.rightMargin = 12;
            topRow.addView(catBadge, cbp);
        }

        TextView closeBtn = makeBtn("Close", 0xFF1E293B, 0xFF64748B, 16f);
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                closeDetailModal();
            }
        });
        topRow.addView(closeBtn);
        dialog.addView(topRow);

        // Split body: Enlarged thumbnail on left, full details & actions on right
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        dialog.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Left column: large 8:3 preview + progress box
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.25f);
        leftLp.rightMargin = 20;
        body.addView(left, leftLp);

        FrameLayout largePreview = new FrameLayout(this);
        largePreview.setBackground(makeBox(0xFF060B12, 0x44477BA8, 16f, 2));
        ImageView fullImg = new ImageView(this);
        fullImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        largePreview.addView(fullImg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (detailStoreListing != null) {
            thumbnailLoader.loadRemoteThumbnail(fullImg, detailStoreListing.thumbnailUrl, 960, 360);
        } else {
            thumbnailLoader.loadLocalThumbnail(
                    fullImg,
                    detailLocalEntry.thumbnailFile,
                    detailLocalEntry.zipFile,
                    detailLocalEntry.zipThumbnailBytes,
                    960,
                    360);
        }
        left.addView(largePreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Progress box (shown during active store download / verification)
        detailProgressArea = new LinearLayout(this);
        detailProgressArea.setOrientation(LinearLayout.VERTICAL);
        detailProgressArea.setPadding(16, 12, 16, 12);
        detailProgressArea.setBackground(makeBox(0xFF0B1626, 0x5538BDF8, 12f, 1));
        detailProgressArea.setVisibility(View.GONE);

        LinearLayout progHead = new LinearLayout(this);
        progHead.setOrientation(LinearLayout.HORIZONTAL);
        progHead.setGravity(Gravity.CENTER_VERTICAL);
        detailProgressText = new TextView(this);
        detailProgressText.setText("Preparing...");
        detailProgressText.setTextColor(0xFFE2E8F0);
        detailProgressText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        progHead.addView(detailProgressText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        detailCancelBtn = makeBtn("Cancel", 0xFF7F1D1D, 0xFFEF4444, 14f);
        detailCancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                downloadCancelRequested = true;
                detailCancelBtn.setEnabled(false);
                detailProgressText.setText("Cancelling...");
            }
        });
        progHead.addView(detailCancelBtn);
        detailProgressArea.addView(progHead);

        detailProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        detailProgressBar.setMax(100);
        detailProgressBar.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.topMargin = 10;
        detailProgressArea.addView(detailProgressBar, pbLp);

        LinearLayout.LayoutParams paLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paLp.topMargin = 12;
        left.addView(detailProgressArea, paLp);

        // Right column: metadata + description + actions
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(18, 16, 18, 16);
        right.setBackground(makeBox(0xCC09111C, 0x33477BA8, 16f, 1));
        body.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.9f));

        ScrollView metaScroll = new ScrollView(this);
        LinearLayout metaCol = new LinearLayout(this);
        metaCol.setOrientation(LinearLayout.VERTICAL);
        metaScroll.addView(metaCol, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        right.addView(metaScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.topMargin = 14;
        right.addView(actionBar, abLp);

        if (detailStoreListing != null) {
            populateStoreDetailRightPane(metaCol, actionBar, detailStoreListing);
        } else {
            populateLocalDetailRightPane(metaCol, actionBar, detailLocalEntry);
        }

        detailModal.setVisibility(View.VISIBLE);
        detailModal.bringToFront();
    }

    private void populateStoreDetailRightPane(
            LinearLayout metaCol,
            LinearLayout actionBar,
            final StoreThemeListing listing) {
        Map<String, ThemePackManager.PackEntry> installedMap = ThemePackManager.mapInstalledByStoreOrPackId(this);
        final ThemePackManager.PackEntry match = findMatch(listing, installedMap);
        boolean compat = listing.isCompatibleWith(currentAppVersion);

        addDetailRow(metaCol, "Theme ID", listing.id);
        addDetailRow(metaCol, "Author", listing.author);
        addDetailRow(metaCol, "Version / Rev", "v" + listing.version + "  (revision " + listing.revision + ")");
        addDetailRow(metaCol, "Layout Format", "v" + listing.formatVersion + "  ("
                + (listing.showSystemBar ? "90px Top Bar • 630p" : "Full 1920x720") + ")");
        addDetailRow(metaCol, "Package Size", StoreThemeListing.formatByteSize(listing.fileSizeBytes));
        if (listing.downloads > 0L) {
            addDetailRow(metaCol, "Downloads", String.valueOf(listing.downloads));
        }
        String compatText = compat
                ? ("Compatible" + (!listing.minAppVersion.isEmpty() ? " (min v" + listing.minAppVersion + ")" : ""))
                : ("Warning: Requires v" + listing.minAppVersion + "+ (this unit is v" + currentAppVersion + ")");
        TextView compatRow = addDetailRow(metaCol, "Compatibility", compatText);
        if (!compat) {
            compatRow.setTextColor(0xFFFCD34D);
        }
        addDetailRow(metaCol, "Updated", StoreThemeListing.formatShortDate(listing.updatedAtMs, listing.updatedAt));
        if (!listing.tags.isEmpty()) {
            addDetailRow(metaCol, "Tags", TextUtils.join(", ", listing.tags));
        }

        TextView descHeading = new TextView(this);
        descHeading.setText("Description");
        descHeading.setTextColor(0xFF7DD3FC);
        descHeading.setTypeface(Typeface.DEFAULT_BOLD);
        descHeading.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        LinearLayout.LayoutParams dhp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dhp.topMargin = 12;
        dhp.bottomMargin = 4;
        metaCol.addView(descHeading, dhp);

        TextView descBody = new TextView(this);
        descBody.setText(listing.description.isEmpty() ? "No description provided." : listing.description);
        descBody.setTextColor(0xFFE2E8F0);
        descBody.setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f);
        metaCol.addView(descBody);

        boolean sameOrNewerInstalled = match != null
                && match.revision >= listing.revision
                && StoreThemeListing.compareVersions(match.version, listing.version) >= 0;

        if (sameOrNewerInstalled) {
            if (!match.isActive) {
                TextView actBtn = makeBtn("Activate Installed", 0xFF065F46, 0xFF10B981, 16f);
                actBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        soundPlayer.playClick(v);
                        ThemePackManager.setActivePack(ThemeManagerActivity.this, match.id, "auto");
                        reloadInstalledPacks();
                        renderStoreCards();
                        closeDetailModal();
                        Toast.makeText(ThemeManagerActivity.this, "Activated " + match.name, Toast.LENGTH_SHORT).show();
                    }
                });
                addRowBtn(actionBar, actBtn, 10);
            } else {
                TextView homeBtn = makeBtn("Active • Go Home", 0xFF065F46, 0xFF10B981, 16f);
                homeBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        soundPlayer.playClick(v);
                        openGen5WHome();
                    }
                });
                addRowBtn(actionBar, homeBtn, 10);
            }
            detailDownloadOnlyBtn = makeBtn("Re-download", 0xFF0E4A6F, 0xFF38BDF8, 16f);
            detailDownloadOnlyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    startStoreDownload(listing, false);
                }
            });
            addRowBtn(actionBar, detailDownloadOnlyBtn, 0);
        } else {
            detailDownloadApplyBtn = makeBtn(
                    match != null ? "Update & Apply" : "Download & Apply",
                    0xFF065F46, 0xFF10B981, 16f);
            detailDownloadApplyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    startStoreDownload(listing, true);
                }
            });
            addRowBtn(actionBar, detailDownloadApplyBtn, 10);

            detailDownloadOnlyBtn = makeBtn(
                    match != null ? "Update Only" : "Download",
                    0xFF0E4A6F, 0xFF38BDF8, 16f);
            detailDownloadOnlyBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    startStoreDownload(listing, false);
                }
            });
            addRowBtn(actionBar, detailDownloadOnlyBtn, 0);
        }
    }

    private void populateLocalDetailRightPane(
            LinearLayout metaCol,
            LinearLayout actionBar,
            final ThemePackManager.PackEntry entry) {
        addDetailRow(metaCol, "Theme ID", entry.id);
        addDetailRow(metaCol, "Source", entry.sourceType.name());
        addDetailRow(metaCol, "Author", entry.author);
        addDetailRow(metaCol, "Version", "v" + entry.version + (entry.revision > 1 ? " (rev " + entry.revision + ")" : ""));
        addDetailRow(metaCol, "Layout Format", "v" + entry.formatVersion);
        if (entry.dir != null) {
            addDetailRow(metaCol, "Location", entry.dir.getAbsolutePath());
        } else if (entry.zipFile != null) {
            addDetailRow(metaCol, "ZIP File", entry.zipFile.getAbsolutePath());
        }
        if (!entry.description.isEmpty()) {
            addDetailRow(metaCol, "Description", entry.description);
        }

        if (entry.sourceType == ThemePackManager.SourceType.USB_ZIP) {
            TextView impBtn = makeBtn("Import ZIP & Apply", 0xFF065F46, 0xFF10B981, 16f);
            impBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    closeDetailModal();
                    importUsbZipAsync(entry);
                }
            });
            addRowBtn(actionBar, impBtn, 0);
        } else if (entry.sourceType == ThemePackManager.SourceType.USB) {
            TextView impBtn = makeBtn("Import & Apply", 0xFF065F46, 0xFF10B981, 16f);
            impBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    closeDetailModal();
                    importUsbDirAsync(entry);
                }
            });
            addRowBtn(actionBar, impBtn, 0);
        } else {
            TextView actBtn = makeBtn(entry.isActive ? "Active • Go Home" : "Activate & Go Home",
                    0xFF065F46, 0xFF10B981, 16f);
            actBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    soundPlayer.playClick(v);
                    if (!entry.isActive) {
                        String srcMode = entry.sourceType == ThemePackManager.SourceType.BUNDLED ? "default" : "auto";
                        ThemePackManager.setActivePack(ThemeManagerActivity.this, entry.id, srcMode);
                    }
                    openGen5WHome();
                }
            });
            addRowBtn(actionBar, actBtn, 0);
        }
    }

    private void startStoreDownload(final StoreThemeListing listing, final boolean activateNow) {
        if (downloadInFlight) {
            return;
        }
        downloadInFlight = true;
        downloadCancelRequested = false;
        final int dlSeq = ++activeDownloadSeq;
        final Context appCtx = getApplicationContext();

        setDetailDownloadButtonsEnabled(false);
        detailProgressArea.setVisibility(View.VISIBLE);
        detailCancelBtn.setEnabled(true);
        detailProgressBar.setIndeterminate(true);
        detailProgressText.setText("Checking storage & starting download...");

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                File dlDir = new File(appCtx.getCacheDir(), "store_downloads");
                if (!dlDir.exists()) dlDir.mkdirs();
                File stagedZip = new File(dlDir, ThemePackManager.sanitizeId(listing.id) + "_" + dlSeq + "_dl.zip");
                try {
                    ThemePackManager.ensureSpaceForDownload(appCtx, listing.fileSizeBytes);
                    storeClient.downloadPackageStreamed(
                            listing.downloadUrl,
                            stagedZip,
                            listing.sha256,
                            new ThemeStoreClient.ProgressListener() {
                                private long lastUiPostMs = 0L;

                                @Override
                                public void onProgress(final long bytesRead, final long totalBytes) {
                                    long now = System.currentTimeMillis();
                                    if (bytesRead > 0 && bytesRead < totalBytes && (now - lastUiPostMs) < 120L) {
                                        return;
                                    }
                                    lastUiPostMs = now;
                                    mainHandler.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (dlSeq != activeDownloadSeq || detailProgressArea == null || isFinishing() || isDestroyed()) return;
                                            long knownTotal = totalBytes > 0L ? totalBytes : listing.fileSizeBytes;
                                            if (knownTotal > 0L) {
                                                detailProgressBar.setIndeterminate(false);
                                                int pct = (int) Math.min(100L, (bytesRead * 100L) / knownTotal);
                                                detailProgressBar.setProgress(pct);
                                                detailProgressText.setText("Downloading "
                                                        + StoreThemeListing.formatByteSize(bytesRead)
                                                        + " / " + StoreThemeListing.formatByteSize(knownTotal)
                                                        + " (" + pct + "%)");
                                            } else {
                                                detailProgressBar.setIndeterminate(true);
                                                detailProgressText.setText("Downloading "
                                                        + StoreThemeListing.formatByteSize(bytesRead) + "...");
                                            }
                                        }
                                    });
                                }
                            },
                            new ThemeStoreClient.CancelFlag() {
                                @Override
                                public boolean isCancelled() {
                                    return downloadCancelRequested || dlSeq != activeDownloadSeq;
                                }
                            });

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dlSeq != activeDownloadSeq || isFinishing() || isDestroyed()) return;
                            if (detailProgressArea != null) {
                                detailProgressBar.setIndeterminate(true);
                                detailProgressText.setText("Verifying archive & installing theme...");
                                detailCancelBtn.setEnabled(false);
                            }
                        }
                    });

                    final ThemePackManager.PackEntry installed = ThemePackManager.installDownloadedZip(
                            appCtx,
                            stagedZip,
                            listing,
                            activateNow);

                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dlSeq != activeDownloadSeq) {
                                return;
                            }
                            downloadInFlight = false;
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            if (detailProgressArea != null) {
                                detailProgressArea.setVisibility(View.GONE);
                            }
                            setDetailDownloadButtonsEnabled(true);
                            reloadInstalledPacks();
                            renderStoreCards();
                            Toast.makeText(
                                    ThemeManagerActivity.this,
                                    (activateNow ? "Installed & activated " : "Installed ") + installed.name,
                                    Toast.LENGTH_SHORT).show();
                            if (detailModal != null && detailModal.getVisibility() == View.VISIBLE
                                    && detailStoreListing == listing) {
                                openStoreDetailModal(listing);
                            }
                        }
                    });
                } catch (final Throwable ex) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (dlSeq != activeDownloadSeq) {
                                return;
                            }
                            downloadInFlight = false;
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            setDetailDownloadButtonsEnabled(true);
                            if (detailProgressArea != null) {
                                detailProgressArea.setVisibility(View.GONE);
                            }
                            Toast.makeText(ThemeManagerActivity.this,
                                    "Store install failed: " + ex.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } finally {
                    if (stagedZip.exists()) {
                        stagedZip.delete();
                    }
                }
            }
        }, "store-pkg-downloader");
        t.setDaemon(true);
        t.start();
    }

    private void setDetailDownloadButtonsEnabled(boolean enabled) {
        if (detailDownloadApplyBtn != null) {
            detailDownloadApplyBtn.setEnabled(enabled);
            detailDownloadApplyBtn.setAlpha(enabled ? 1f : 0.45f);
        }
        if (detailDownloadOnlyBtn != null) {
            detailDownloadOnlyBtn.setEnabled(enabled);
            detailDownloadOnlyBtn.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private void closeDetailModal() {
        if (downloadInFlight) {
            downloadCancelRequested = true;
            activeDownloadSeq++;
            downloadInFlight = false;
        }
        if (detailModal != null) {
            detailModal.setVisibility(View.GONE);
        }
    }

    // -------------------------------------------------------------------------
    // Failsafe / Home Launcher Controls
    // -------------------------------------------------------------------------

    private void openSwitcherOverlay() {
        if (switcherDialog == null) {
            switcherDialog = new SwitcherOverlayDialog(this, soundPlayer, new SwitcherOverlayDialog.Actions() {
                @Override
                public void onSendSwitcherCmd(String cmd) {
                    SwitcherClient.sendAsync(cmd, new SwitcherClient.Callback() {
                        @Override
                        public void onResult(SwitcherClient.Status status) {
                            latestSwitcherStatus = status;
                            if (switcherDialog != null) {
                                switcherDialog.update(status, telemetry.getLatest());
                            }
                            if (!status.message.isEmpty()) {
                                Toast.makeText(ThemeManagerActivity.this, status.message, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }

                @Override
                public void onSwitchPackSourceAndReload() {
                    String cur = getSharedPreferences(ThemePackManager.PREF_NAME, MODE_PRIVATE)
                            .getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
                    String next = "auto".equals(cur) ? "usb" : ("usb".equals(cur) ? "default" : "auto");
                    ThemePackManager.setActivePack(ThemeManagerActivity.this, ThemePackManager.getActivePackId(ThemeManagerActivity.this), next);
                    reloadInstalledPacks();
                    if (switcherDialog != null) {
                        switcherDialog.update(latestSwitcherStatus, telemetry.getLatest());
                    }
                }

                @Override
                public void onOpenThemesRequested() {
                    if (switcherDialog != null) {
                        switcherDialog.setVisibility(View.GONE);
                    }
                    telemetry.stop();
                }

                @Override
                public void onOpenStockTesterRequested() {
                    openStockTesterOverlay();
                }

                @Override
                public void onCloseRequested() {
                    if (switcherDialog != null) {
                        switcherDialog.setVisibility(View.GONE);
                    }
                    telemetry.stop();
                }

                @Override
                public String getPackSourceMode() {
                    return getSharedPreferences(ThemePackManager.PREF_NAME, MODE_PRIVATE)
                            .getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
                }

                @Override
                public ThemePack getCurrentPack() {
                    return ThemePackLoader.load(ThemeManagerActivity.this, getPackSourceMode());
                }
            });
            rootFrame.addView(switcherDialog, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        telemetry.start();
        switcherDialog.setVisibility(View.VISIBLE);
        switcherDialog.bringToFront();
        switcherDialog.update(latestSwitcherStatus, telemetry.getLatest());
        SwitcherClient.sendAsync("STATUS", new SwitcherClient.Callback() {
            @Override
            public void onResult(SwitcherClient.Status status) {
                latestSwitcherStatus = status;
                if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
                    switcherDialog.update(status, telemetry.getLatest());
                }
            }
        });
    }

    private void openStockTesterOverlay() {
        if (stockButtonsOverlay == null) {
            stockButtonsOverlay = new StockButtonsOverlay(this, soundPlayer, new StockButtonsOverlay.Listener() {
                @Override
                public void onCloseRequested() {
                    if (stockButtonsOverlay != null) {
                        stockButtonsOverlay.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onOpenThemedSettingsLabRequested() {
                    if (stockButtonsOverlay != null) {
                        stockButtonsOverlay.setVisibility(View.GONE);
                    }
                    openThemedSettingsLabOverlay();
                }
            });
            rootFrame.addView(stockButtonsOverlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            stockButtonsOverlay.reload();
        }
        stockButtonsOverlay.setVisibility(View.VISIBLE);
        stockButtonsOverlay.bringToFront();
    }

    private void openThemedSettingsLabOverlay() {
        if (themedSettingsLab == null) {
            themedSettingsLab = new ThemedSettingsLabOverlay(this, soundPlayer, new ThemedSettingsLabOverlay.Listener() {
                @Override
                public void onCloseRequested() {
                    if (themedSettingsLab != null) {
                        themedSettingsLab.setVisibility(View.GONE);
                    }
                }
            });
            rootFrame.addView(themedSettingsLab, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        String srcMode = getSharedPreferences(ThemePackManager.PREF_NAME, MODE_PRIVATE)
                .getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
        themedSettingsLab.updateActiveThemePack(ThemePackLoader.load(this, srcMode));
        themedSettingsLab.reload();
        themedSettingsLab.setVisibility(View.VISIBLE);
        themedSettingsLab.bringToFront();
    }

    private void openGen5WHome() {
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(home);
        finish();
    }

    private void openWifiSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Open Android Setup -> Wi-Fi to connect.", Toast.LENGTH_SHORT).show();
        }
    }

    private void hideSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private String resolveAppVersionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (pi != null && pi.versionName != null && !pi.versionName.trim().isEmpty()) {
                return pi.versionName.trim();
            }
        } catch (Throwable ignored) {
        }
        return "0.4.9";
    }

    private static ThemePackManager.PackEntry findMatch(
            StoreThemeListing listing,
            Map<String, ThemePackManager.PackEntry> installedMap) {
        if (listing == null || installedMap == null) return null;
        if (listing.id != null && !listing.id.isEmpty()) {
            ThemePackManager.PackEntry e = installedMap.get(listing.id.toLowerCase(Locale.US));
            if (e != null) return e;
            e = installedMap.get(ThemePackManager.sanitizeId(listing.id));
            if (e != null) return e;
        }
        return null;
    }

    private static boolean containsCategoryIgnoreCase(List<String> list, String target) {
        for (String c : list) {
            if (c.equalsIgnoreCase(target)) return true;
        }
        return false;
    }

    private TextView addDetailRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 4, 0, 4);

        TextView k = new TextView(this);
        k.setText(label);
        k.setTextColor(0xFF94A3B8);
        k.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        k.setWidth(145);
        row.addView(k);

        TextView v = new TextView(this);
        v.setText(value == null || value.isEmpty() ? "-" : value);
        v.setTextColor(0xFFFFFFFF);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
        return v;
    }

    private TextView makeHeaderBtn(String label, int bg, int border) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 16f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(16, 9, 16, 9);
        tv.setBackground(makeBox(bg, border, 12f, border == 0 ? 0 : 2));
        return tv;
    }

    private TextView makeBtn(String label, int bg, int border, float textPx) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, textPx);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(14, 9, 14, 9);
        tv.setBackground(makeBox(bg, border, 12f, 2));
        return tv;
    }

    private TextView makeOverlayPill(String label, int bg, int fg) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(fg);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f);
        tv.setPadding(10, 4, 10, 4);
        tv.setBackground(makeBox(bg, fg, 8f, 1));
        return tv;
    }

    private static void addRowBtn(LinearLayout row, View btn, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = rightMargin;
        row.addView(btn, lp);
    }

    private static GradientDrawable makeBox(int bg, int border, float radius, int stroke) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(radius);
        if (stroke > 0 && border != 0) {
            gd.setStroke(stroke, border);
        }
        return gd;
    }
}
