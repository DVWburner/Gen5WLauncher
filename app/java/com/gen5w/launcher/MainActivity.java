package com.gen5w.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gen5w.launcher.action.AppLauncher;
import com.gen5w.launcher.audio.SafeClickSoundPlayer;
import com.gen5w.launcher.ipc.SwitcherClient;
import com.gen5w.launcher.ipc.TelemetryModel;
import com.gen5w.launcher.model.ActionSpec;
import com.gen5w.launcher.model.LayoutNode;
import com.gen5w.launcher.model.ThemePack;
import com.gen5w.launcher.model.WidgetSpec;
import com.gen5w.launcher.render.AssetResolver;
import com.gen5w.launcher.render.BackgroundHostView;
import com.gen5w.launcher.render.LayoutEngine;
import com.gen5w.launcher.render.ThemePackLoader;
import com.gen5w.launcher.render.ThemePackManager;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Standalone Gen5W HOME / Launcher Activity (com.gen5w.launcher/.MainActivity).
 * Declared with android:disableSS="true" so HKMC RightScreenHome suppresses split-screen overlay.
 */
public final class MainActivity extends Activity implements TelemetryModel.Listener {
    private static final String PREFS = "gen5w_launcher_prefs";
    private static final String PREF_SHOW_BAR = "show_system_bar";
    private static final String PREF_BAR_PACK_KEY = "show_system_bar_pack_key";
    private static final String PREF_BAR_PACK_DEFAULT = "show_system_bar_pack_default";
    private static final String PREF_SOUND_MODE = "sound_mode";
    private static final String PREF_BG_OVERRIDE = "bg_override";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final TelemetryModel telemetry = new TelemetryModel();

    private SharedPreferences prefs;
    private SafeClickSoundPlayer soundPlayer;
    private ThemePack currentPack;
    private AssetResolver assets;
    private boolean showSystemBar = true;
    private boolean appliedSystemBar = true;
    private boolean recreatePending = false;

    private FrameLayout rootFrame;
    private BackgroundHostView backgroundHost;
    private FrameLayout packLayer;
    private LayoutEngine.Bindings bindings;
    private LinearLayout deadmanBanner;
    private TextView deadmanText;
    private AppDrawerOverlay appDrawer;
    private SwitcherOverlayDialog switcherDialog;
    private StockButtonsOverlay stockButtonsOverlay;
    private CarControlOverlayDialog carControlDialog;
    private ThemedSettingsLabOverlay themedSettingsLab;

    private SwitcherClient.Status latestSwitcherStatus = new SwitcherClient.Status();
    private int lastLayoutW = 0;
    private int lastLayoutH = 0;
    private int loadedPackChangeSeq = -1;
    private boolean resumed = false;

    private final Runnable periodicTicker = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            if (bindings != null) {
                bindings.updateClock();
            }
            pollSwitcher("STATUS");
            mainHandler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadPackFromPrefs();
        showSystemBar = resolveEffectiveSystemBar();
        appliedSystemBar = showSystemBar;
        setTheme(showSystemBar ? R.style.LauncherTheme : R.style.LauncherTheme_Fullscreen);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(savedInstanceState);
        soundPlayer = new SafeClickSoundPlayer(this);
        soundPlayer.setMode(prefs.getString(PREF_SOUND_MODE, currentPack.tokens.soundMode));

        applySystemBarMode();

        rootFrame = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                if (w > 0 && h > 0 && (w != lastLayoutW || h != lastLayoutH)) {
                    final int vw = w;
                    final int vh = h;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            rebuildPackView(vw, vh);
                        }
                    });
                }
            }
        };
        rootFrame.setFitsSystemWindows(false);

        backgroundHost = new BackgroundHostView(this);
        rootFrame.addView(backgroundHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        packLayer = new FrameLayout(this);
        rootFrame.addView(packLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildDeadmanBanner();
        applyBackgroundSpec();
        setContentView(rootFrame);
        applySystemBarMode();

        telemetry.setListener(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        closeOverlays();
        pollSwitcher("STATUS");
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (ThemePackManager.getPackChangeSeq(this) != loadedPackChangeSeq) {
            reloadThemePack();
        }
        applySystemBarMode();
        syncHomeAnimationsAndOverlayState();
        telemetry.start();
        mainHandler.removeCallbacks(periodicTicker);
        mainHandler.post(periodicTicker);
    }

    @Override
    protected void onPause() {
        resumed = false;
        mainHandler.removeCallbacks(periodicTicker);
        syncHomeAnimationsAndOverlayState();
        telemetry.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        telemetry.setListener(null);
        telemetry.stop();
        if (bindings != null) {
            bindings.release();
        }
        if (backgroundHost != null) {
            backgroundHost.setPaused(true);
        }
        soundPlayer.release();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applySystemBarMode();
        }
    }

    private String resolveActiveOverlayName() {
        if (carControlDialog != null && carControlDialog.getVisibility() == View.VISIBLE) return "car_controls";
        if (appDrawer != null && appDrawer.getVisibility() == View.VISIBLE) return "app_drawer";
        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) return "switcher";
        if (stockButtonsOverlay != null && stockButtonsOverlay.getVisibility() == View.VISIBLE) return "stock_tester";
        if (themedSettingsLab != null && themedSettingsLab.getVisibility() == View.VISIBLE) return "themed_settings";
        return "none";
    }

    private void syncHomeAnimationsAndOverlayState() {
        String ovl = resolveActiveOverlayName();
        boolean homeAnimActive = resumed && "none".equals(ovl);
        if (backgroundHost != null) {
            backgroundHost.setPaused(!homeAnimActive);
        }
        if (bindings != null) {
            bindings.setAnimationsActive(homeAnimActive);
        }
        syncTelemetryExtraKeys();
    }

    private void syncTelemetryExtraKeys() {
        Set<String> keys = LayoutEngine.collectTelemetryKeys(currentPack);
        if (carControlDialog != null && carControlDialog.getVisibility() == View.VISIBLE) {
            keys.addAll(Arrays.asList(CarControlOverlayDialog.REQUIRED_HMG_KEYS));
        }
        telemetry.setExtraKeys(keys);
    }

    @Override
    public void onBackPressed() {
        if (appDrawer != null && appDrawer.getVisibility() == View.VISIBLE && appDrawer.onBackPressed()) {
            return;
        }
        if (closeOverlays()) {
            return;
        }
        // Intentionally swallow Back at root HOME level just like stock Launcher.
    }

    @Override
    public void onTelemetryUpdated(TelemetryModel.Snapshot snapshot) {
        if (bindings != null) {
            bindings.updateTelemetry(snapshot);
        }
        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
            switcherDialog.update(latestSwitcherStatus, snapshot);
        }
        if (carControlDialog != null && carControlDialog.getVisibility() == View.VISIBLE) {
            carControlDialog.update(snapshot);
        }
    }

    private void loadPackFromPrefs() {
        loadedPackChangeSeq = ThemePackManager.getPackChangeSeq(this);
        String sourceMode = prefs.getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
        currentPack = ThemePackLoader.load(this, sourceMode);
        assets = new AssetResolver(this, currentPack.baseDir);
        syncTelemetryExtraKeys();
        String bgOverride = prefs.getString(PREF_BG_OVERRIDE, "");
        if (!bgOverride.isEmpty()) {
            currentPack.background.type = bgOverride;
        }
    }

    private void applyBackgroundSpec() {
        if (backgroundHost != null && currentPack != null) {
            backgroundHost.setSpec(currentPack.background, assets);
            backgroundHost.setPaused(!(resumed && "none".equals(resolveActiveOverlayName())));
        }
    }

    private void rebuildPackView(int w, int h) {
        if (w <= 0 || h <= 0 || currentPack == null) {
            return;
        }
        lastLayoutW = w;
        lastLayoutH = h;
        if (bindings != null) {
            bindings.release();
            bindings = null;
        }
        packLayer.removeAllViews();

        LayoutEngine.Bindings[] out = new LayoutEngine.Bindings[1];
        View rendered = LayoutEngine.build(this, currentPack, assets, w, h,
                new LayoutEngine.ActionHandler() {
                    @Override
                    public void onAction(View view, LayoutNode node, ActionSpec action) {
                        handleWidgetAction(view, action);
                    }
                }, out);
        bindings = out[0];
        packLayer.addView(rendered);

        if (bindings != null) {
            bindings.setAnimationsActive(resumed && "none".equals(resolveActiveOverlayName()));
            bindings.updateClock();
            bindings.updateTelemetry(telemetry.getLatest());
            bindings.updateSwitcher(latestSwitcherStatus);
            syncQuickToggleLabels();
        }
    }

    private void syncQuickToggleLabels() {
        if (bindings == null) {
            return;
        }
        if (bindings.barToggleSub != null) {
            bindings.barToggleSub.setText(showSystemBar ? "Bar Shown (630p)" : "Full 1920x720");
        }
        if (bindings.soundToggleSub != null) {
            bindings.soundToggleSub.setText(soundPlayer.getModeDisplayLabel());
        }
    }

    private void handleWidgetAction(View view, ActionSpec action) {
        soundPlayer.playClick(view);
        if (action == null) {
            return;
        }
        String t = action.type;
        if ("open_car_controls".equals(t)
                || ("preset".equals(t) && "car_controls_popup".equalsIgnoreCase(action.cmd))) {
            openCarControlOverlay();
        } else if ("car_cmd".equals(t)) {
            dispatchCarCommand(action.cmd, action.extraVal);
        } else if ("preset".equals(t) && action.cmd != null
                && action.cmd.toLowerCase(Locale.US).startsWith("car_cmd:")) {
            dispatchCarCommand(action.cmd.substring("car_cmd:".length()), action.extraVal);
        } else if ("preset".equals(t) || "launch".equals(t) || "launch_climate".equals(t) || "launch_projection".equals(t)
                || "broadcast".equals(t) || "root_broadcast".equals(t) || "root_start".equals(t) || "root_service".equals(t)) {
            AppLauncher.launchAction(this, action);
        } else if ("goto_page".equals(t)) {
            if (bindings != null) {
                bindings.navigateToPage(action.cmd);
            }
        } else if ("open_drawer".equals(t) || "open_all_apps".equals(t)) {
            openAppDrawer();
        } else if ("open_switcher".equals(t)) {
            openSwitcherOverlay();
        } else if ("open_themes".equals(t)) {
            openThemeManagerActivity("store".equalsIgnoreCase(action.cmd)
                    ? ThemeManagerActivity.TAB_STORE
                    : ThemeManagerActivity.TAB_INSTALLED);
        } else if ("open_theme_store".equals(t)) {
            openThemeManagerActivity(ThemeManagerActivity.TAB_STORE);
        } else if ("open_stock_tester".equals(t)) {
            openStockTesterOverlay();
        } else if ("switcher_cmd".equals(t)) {
            pollSwitcher(action.cmd);
        } else if ("toggle_bar".equals(t)) {
            showSystemBar = !showSystemBar;
            persistBarChoice(showSystemBar);
            applySystemBarMode();
            syncQuickToggleLabels();
            if (showSystemBar != appliedSystemBar) {
                requestWindowModeRecreate();
            }
        } else if ("cycle_sound".equals(t)) {
            String next = soundPlayer.cycleMode();
            prefs.edit().putString(PREF_SOUND_MODE, next).apply();
            syncQuickToggleLabels();
            Toast.makeText(this, "Click audio: " + soundPlayer.getModeDisplayLabel(), Toast.LENGTH_SHORT).show();
        } else if ("toggle_bg".equals(t)) {
            cycleBackgroundMode();
        } else if ("launch_stock".equals(t) || "launch_stock_home".equals(t)) {
            AppLauncher.launchStockHome(this);
        } else if ("reload_pack".equals(t)) {
            reloadThemePack();
        }
    }

    private void cycleBackgroundMode() {
        String cur = currentPack.background.type;
        String next;
        if ("animated_aurora".equals(cur)) next = "animated_grid";
        else if ("animated_grid".equals(cur)) next = "animated_mesh";
        else if ("animated_mesh".equals(cur)) next = "animated_frames";
        else if ("animated_frames".equals(cur)) next = "gradient";
        else if ("gradient".equals(cur)) next = "solid";
        else next = "animated_aurora";

        if ("animated_frames".equals(next) && currentPack.background.framesDir.isEmpty()) {
            currentPack.background.framesDir = "anim/wave";
        }
        currentPack.background.type = next;
        prefs.edit().putString(PREF_BG_OVERRIDE, next).apply();
        applyBackgroundSpec();
        Toast.makeText(this, "Backdrop: " + next, Toast.LENGTH_SHORT).show();
    }

    private void reloadThemePack() {
        loadPackFromPrefs();
        boolean nextBar = resolveEffectiveSystemBar();
        if (nextBar != appliedSystemBar) {
            showSystemBar = nextBar;
            applySystemBarMode();
            requestWindowModeRecreate();
            return;
        }
        applyBackgroundSpec();
        if (lastLayoutW > 0 && lastLayoutH > 0) {
            rebuildPackView(lastLayoutW, lastLayoutH);
        }
        if (themedSettingsLab != null) {
            themedSettingsLab.updateActiveThemePack(currentPack);
        }
        Toast.makeText(this, "Loaded pack (" + currentPack.sourceLabel + ")", Toast.LENGTH_SHORT).show();
    }

    private static String packIdentity(ThemePack pack) {
        if (pack == null) {
            return "default";
        }
        return (pack.id == null ? "" : pack.id) + "|" + (pack.sourceLabel == null ? "" : pack.sourceLabel);
    }

    private boolean resolveEffectiveSystemBar() {
        String packKey = packIdentity(currentPack);
        boolean packDefault = currentPack == null || currentPack.tokens == null || currentPack.tokens.showSystemBar;
        boolean samePack = prefs.contains(PREF_BAR_PACK_KEY)
                && packKey.equals(prefs.getString(PREF_BAR_PACK_KEY, ""))
                && prefs.contains(PREF_BAR_PACK_DEFAULT)
                && prefs.getBoolean(PREF_BAR_PACK_DEFAULT, packDefault) == packDefault;
        if (!samePack || !prefs.contains(PREF_SHOW_BAR)) {
            prefs.edit()
                    .putString(PREF_BAR_PACK_KEY, packKey)
                    .putBoolean(PREF_BAR_PACK_DEFAULT, packDefault)
                    .putBoolean(PREF_SHOW_BAR, packDefault)
                    .apply();
            return packDefault;
        }
        return prefs.getBoolean(PREF_SHOW_BAR, packDefault);
    }

    private void persistBarChoice(boolean showBar) {
        String packKey = packIdentity(currentPack);
        boolean packDefault = currentPack == null || currentPack.tokens == null || currentPack.tokens.showSystemBar;
        prefs.edit()
                .putString(PREF_BAR_PACK_KEY, packKey)
                .putBoolean(PREF_BAR_PACK_DEFAULT, packDefault)
                .putBoolean(PREF_SHOW_BAR, showBar)
                .apply();
    }

    private void requestWindowModeRecreate() {
        if (recreatePending || isFinishing()) {
            return;
        }
        recreatePending = true;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                recreatePending = false;
                if (!isFinishing()) {
                    recreate();
                }
            }
        });
    }

    private void applySystemBarMode() {
        Window win = getWindow();
        View decor = win.getDecorView();
        if (showSystemBar) {
            win.addFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            win.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } else {
            // Recreate uses LauncherTheme.Fullscreen (windowFullscreen=true, matching stock Launcher_WP3);
            // clearing FLAG_LAYOUT_INSET_DECOR with FLAG_LAYOUT_IN_SCREEN forces KitKat PhoneWindowManager
            // to lay out pf/df/cf across the entire 1920x720 display instead of reserving a 90px top band.
            win.clearFlags(
                    WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
            win.addFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        if (rootFrame != null) {
            rootFrame.setPadding(0, 0, 0, 0);
            rootFrame.requestLayout();
        }
    }

    private void openAppDrawer() {
        if (appDrawer == null) {
            appDrawer = new AppDrawerOverlay(this, soundPlayer, new AppDrawerOverlay.Listener() {
                @Override
                public void onCloseRequested() {
                    if (appDrawer != null) {
                        appDrawer.setVisibility(View.GONE);
                    }
                    syncHomeAnimationsAndOverlayState();
                }

                @Override
                public void onOpenCarControlsRequested() {
                    if (appDrawer != null) {
                        appDrawer.setVisibility(View.GONE);
                    }
                    openCarControlOverlay();
                }

                @Override
                public void onOpenThemedSettingsLabRequested() {
                    if (appDrawer != null) {
                        appDrawer.setVisibility(View.GONE);
                    }
                    openThemedSettingsLabOverlay();
                }
            });
            rootFrame.addView(appDrawer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            appDrawer.reload();
        }
        appDrawer.setVisibility(View.VISIBLE);
        appDrawer.bringToFront();
        syncHomeAnimationsAndOverlayState();
    }

    private void openSwitcherOverlay() {
        if (switcherDialog == null) {
            switcherDialog = new SwitcherOverlayDialog(this, soundPlayer, new SwitcherOverlayDialog.Actions() {
                @Override
                public void onSendSwitcherCmd(String cmd) {
                    pollSwitcher(cmd);
                }

                @Override
                public void onSwitchPackSourceAndReload() {
                    String cur = prefs.getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
                    String next = "auto".equals(cur) ? "usb" : ("usb".equals(cur) ? "default" : "auto");
                    ThemePackManager.setActivePack(MainActivity.this, ThemePackManager.getActivePackId(MainActivity.this), next);
                    reloadThemePack();
                    if (switcherDialog != null) {
                        switcherDialog.update(latestSwitcherStatus, telemetry.getLatest());
                    }
                }

                @Override
                public void onOpenThemesRequested() {
                    openThemeManagerActivity(ThemeManagerActivity.TAB_INSTALLED);
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
                    syncHomeAnimationsAndOverlayState();
                }

                @Override
                public String getPackSourceMode() {
                    return prefs.getString(ThemePackLoader.PACK_PREF_SOURCE, "auto");
                }

                @Override
                public ThemePack getCurrentPack() {
                    return currentPack;
                }
            });
            rootFrame.addView(switcherDialog, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        switcherDialog.setVisibility(View.VISIBLE);
        switcherDialog.bringToFront();
        switcherDialog.update(latestSwitcherStatus, telemetry.getLatest());
        syncHomeAnimationsAndOverlayState();
    }

    private void openStockTesterOverlay() {
        if (stockButtonsOverlay == null) {
            stockButtonsOverlay = new StockButtonsOverlay(this, soundPlayer, new StockButtonsOverlay.Listener() {
                @Override
                public void onCloseRequested() {
                    if (stockButtonsOverlay != null) {
                        stockButtonsOverlay.setVisibility(View.GONE);
                    }
                    syncHomeAnimationsAndOverlayState();
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
        syncHomeAnimationsAndOverlayState();
    }

    private void openThemedSettingsLabOverlay() {
        if (themedSettingsLab == null) {
            themedSettingsLab = new ThemedSettingsLabOverlay(this, soundPlayer, new ThemedSettingsLabOverlay.Listener() {
                @Override
                public void onCloseRequested() {
                    if (themedSettingsLab != null) {
                        themedSettingsLab.setVisibility(View.GONE);
                    }
                    syncHomeAnimationsAndOverlayState();
                }
            });
            rootFrame.addView(themedSettingsLab, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        themedSettingsLab.updateActiveThemePack(currentPack);
        themedSettingsLab.reload();
        themedSettingsLab.setVisibility(View.VISIBLE);
        themedSettingsLab.bringToFront();
        syncHomeAnimationsAndOverlayState();
    }

    private void openCarControlOverlay() {
        if (carControlDialog == null) {
            carControlDialog = new CarControlOverlayDialog(this, soundPlayer, new CarControlOverlayDialog.Listener() {
                @Override
                public void onCloseRequested() {
                    if (carControlDialog != null) {
                        carControlDialog.setVisibility(View.GONE);
                    }
                    syncHomeAnimationsAndOverlayState();
                }

                @Override
                public void onCommandDispatched() {
                    telemetry.requestImmediateRefresh();
                }
            });
            rootFrame.addView(carControlDialog, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        carControlDialog.setVisibility(View.VISIBLE);
        carControlDialog.bringToFront();
        syncHomeAnimationsAndOverlayState();
        carControlDialog.update(telemetry.getLatest());
        telemetry.requestImmediateRefresh();
    }

    private void dispatchCarCommand(final String cmd, final String arg) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return;
        }
        final String cleanCmd = cmd.trim();
        final String cleanArg = arg == null ? "" : arg.trim();
        SwitcherClient.sendCarCommandAsync(cleanCmd, cleanArg, new SwitcherClient.SimpleCallback() {
            @Override
            public void onResult(boolean ok, String message) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                telemetry.requestImmediateRefresh();
                if (!ok && message != null && !message.isEmpty()) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void openThemeManagerActivity(String initialTab) {
        try {
            Intent intent = new Intent(this, ThemeManagerActivity.class);
            intent.putExtra(ThemeManagerActivity.EXTRA_TAB,
                    ThemeManagerActivity.TAB_STORE.equals(initialTab)
                            ? ThemeManagerActivity.TAB_STORE
                            : ThemeManagerActivity.TAB_INSTALLED);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to open Themes: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean closeOverlays() {
        boolean closed = false;
        if (appDrawer != null && appDrawer.getVisibility() == View.VISIBLE) {
            appDrawer.setVisibility(View.GONE);
            closed = true;
        }
        if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
            switcherDialog.setVisibility(View.GONE);
            closed = true;
        }
        if (stockButtonsOverlay != null && stockButtonsOverlay.getVisibility() == View.VISIBLE) {
            stockButtonsOverlay.setVisibility(View.GONE);
            closed = true;
        }
        if (carControlDialog != null && carControlDialog.getVisibility() == View.VISIBLE) {
            carControlDialog.setVisibility(View.GONE);
            closed = true;
        }
        if (themedSettingsLab != null && themedSettingsLab.getVisibility() == View.VISIBLE) {
            themedSettingsLab.setVisibility(View.GONE);
            closed = true;
        }
        if (closed) {
            syncHomeAnimationsAndOverlayState();
        }
        return closed;
    }

    private void pollSwitcher(final String command) {
        SwitcherClient.sendAsync(command, new SwitcherClient.Callback() {
            @Override
            public void onResult(SwitcherClient.Status status) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                latestSwitcherStatus = status;
                if (bindings != null) {
                    bindings.updateSwitcher(status);
                }
                updateDeadmanBanner(status);
                if (switcherDialog != null && switcherDialog.getVisibility() == View.VISIBLE) {
                    switcherDialog.update(status, telemetry.getLatest());
                }
                if (!"STATUS".equals(command) && !status.message.isEmpty()) {
                    Toast.makeText(MainActivity.this, status.message, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void buildDeadmanBanner() {
        deadmanBanner = new LinearLayout(this);
        deadmanBanner.setOrientation(LinearLayout.HORIZONTAL);
        deadmanBanner.setGravity(Gravity.CENTER_VERTICAL);
        deadmanBanner.setPadding(24, 14, 24, 14);
        deadmanBanner.setVisibility(View.GONE);

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(0xF01C1408);
        gd.setCornerRadius(18f);
        gd.setStroke(3, 0xFFF59E0B);
        deadmanBanner.setBackground(gd);

        deadmanText = new TextView(this);
        deadmanText.setTextColor(0xFFFDE68A);
        deadmanText.setTypeface(Typeface.DEFAULT_BOLD);
        deadmanText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 21f);
        deadmanBanner.addView(deadmanText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView confirm = makeBannerBtn("CONFIRM CUSTOM HOME", 0xFF059669, 0xFF34D399);
        confirm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                pollSwitcher("CONFIRM");
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = 14;
        deadmanBanner.addView(confirm, cp);

        TextView revert = makeBannerBtn("REVERT TO STOCK", 0xFFB91C1C, 0xFFF87171);
        revert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundPlayer.playClick(v);
                pollSwitcher("REVERT");
            }
        });
        deadmanBanner.addView(revert);

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bp.setMargins(28, 0, 28, 20);
        rootFrame.addView(deadmanBanner, bp);
    }

    private void updateDeadmanBanner(SwitcherClient.Status st) {
        if (deadmanBanner == null) {
            return;
        }
        if (st.serviceReachable && st.deadmanRemainingSec > 0) {
            deadmanBanner.setVisibility(View.VISIBLE);
            deadmanBanner.bringToFront();
            deadmanText.setText("DEADMAN SAFETY TEST: Auto-reverting to Stock Home in " + st.deadmanRemainingSec + "s");
        } else {
            deadmanBanner.setVisibility(View.GONE);
        }
    }

    private TextView makeBannerBtn(String label, int fill, int border) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 19f);
        tv.setPadding(22, 12, 22, 12);
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(12f);
        gd.setStroke(2, border);
        tv.setBackground(gd);
        return tv;
    }
}
