#!/system/bin/sh
# Standalone NEMESIS postinstall: ensures runtime directories exist and notifies the user if Android is up.
umask 022
mkdir -p "${ROOT:-}/data/gen5w_launcher/run/home-switcher" 2>/dev/null || true
if [ -z "${ROOT:-}" ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
    am broadcast -a com.hkmc.intent.action.TOAST \
        -e com.hkmc.intent.extra.TOAST_MESSAGE "Gen5W Theme Launcher installed (standalone)" \
        --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 || true
fi
exit 0
