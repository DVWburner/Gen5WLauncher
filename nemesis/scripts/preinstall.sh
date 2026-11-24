#!/system/bin/sh
# Standalone NEMESIS preinstall: migrates existing state and themes from legacy v0.4.x HMG-API installs
# and cleanly removes the old hmgapi.gen5w-launcher service so two HomeSwitchers never collide.
umask 022

OLD_STATE="${ROOT:-}/data/hmgapi/run/gen5w-launcher/home-switcher/switcher-state.properties"
NEW_DIR="${ROOT:-}/data/gen5w_launcher/run/home-switcher"
NEW_STATE="$NEW_DIR/switcher-state.properties"
mkdir -p "$NEW_DIR" 2>/dev/null || true
if [ -f "$OLD_STATE" ] && [ ! -f "$NEW_STATE" ]; then
    cp "$OLD_STATE" "$NEW_STATE" 2>/dev/null || true
fi

OLD_MOD="${ROOT:-}/data/hmgapi/mods/gen5w-launcher"
if [ -d "$OLD_MOD" ]; then
    BK_DIR="${ROOT:-}/data/gen5w_launcher/upgrade_backup"
    APP_DATA="${ROOT:-}/data/data/com.gen5w.launcher"
    if [ -d "$APP_DATA/files" ] || [ -d "$APP_DATA/shared_prefs" ]; then
        rm -rf "$BK_DIR" 2>/dev/null || true
        mkdir -p "$BK_DIR" 2>/dev/null || true
        [ -d "$APP_DATA/files" ] && cp -r "$APP_DATA/files" "$BK_DIR/files" 2>/dev/null || true
        [ -d "$APP_DATA/shared_prefs" ] && cp -r "$APP_DATA/shared_prefs" "$BK_DIR/shared_prefs" 2>/dev/null || true
    fi

    H="${ROOT:-}/system/bin/hmgapid"
    if [ -z "${ROOT:-}" ] && [ -x "$H" ]; then
        "$H" ping >/dev/null 2>&1 || "$H" daemon >/dev/null 2>&1 || true
        "$H" engine remove gen5w-launcher --from-nemesis >/dev/null 2>&1 || true
        "$H" engine apply >/dev/null 2>&1 || true
    fi
    rm -rf "$OLD_MOD" 2>/dev/null || true
fi

if [ -z "${ROOT:-}" ] && [ -d /data/nemesis/apps/hmgapi.gen5w-launcher ] && [ -x /system/xbin/nemesis ]; then
    HMGAPI_NEMESIS_SYNC=1 /system/xbin/nemesis uninstall --force hmgapi.gen5w-launcher >/dev/null 2>&1 || true
fi
exit 0
