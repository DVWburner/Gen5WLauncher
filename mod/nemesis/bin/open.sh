#!/system/bin/sh
# NEMESIS Manager Launch entry for Gen5W Theme Store & Switcher (runs as root).
[ "$(getprop sys.boot_completed)" = 1 ] || { echo "Android is still starting; wait a moment"; exit 1; }
case "$(pm path com.gen5w.launcher 2>/dev/null)" in
    package:*) ;;
    *)
        echo "Gen5W Launcher APK is not installed yet; wait a few seconds or apply mods in HMG-API"
        exit 1 ;;
esac
out="$(am start -a android.intent.action.MAIN -n com.gen5w.launcher/.ThemeManagerActivity 2>&1)" || {
    echo "Failed to launch Gen5W Theme Store: $out"
    exit 1
}
case "$out" in
    *Error:*|*Exception*)
        echo "Failed to launch Gen5W Theme Store: $out"
        exit 1 ;;
esac
echo "Gen5W Theme Store opened"
exit 0
