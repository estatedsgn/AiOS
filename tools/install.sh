#!/usr/bin/env bash
#
# Installs AiOS onto an Android phone connected over USB and switches on the
# accessibility service the agent needs in order to act.
#
# Enabling that service normally means walking through Settings by hand. Over a
# cable it can be written directly to secure settings, which is what makes this
# a one-command install - and it is also why the script prints exactly what it
# granted: this is the permission that lets the agent touch anything on screen.
#
# Usage:
#   ./tools/install.sh                 # use a locally built APK, or build one
#   ./tools/install.sh --download      # fetch the latest published APK
#   ./tools/install.sh --apk path.apk  # install a specific file
#   ./tools/install.sh --uninstall     # remove AiOS and its granted access
#
set -euo pipefail

PACKAGE="ai.aios.app"
SERVICE="${PACKAGE}/${PACKAGE}.device.AiosAccessibilityService"
ACTIVITY="${PACKAGE}/${PACKAGE}.ui.MainActivity"
REPO="estatedsgn/AiOS"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_APK="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"

APK=""
MODE="auto"
SERIAL=""

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '  %s\n' "$*"; }
warn() { printf '\033[33m  %s\033[0m\n' "$*" >&2; }
die()  { printf '\033[31merror: %s\033[0m\n' "$*" >&2; exit 1; }

usage() {
    sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --download)  MODE="download" ;;
        --build)     MODE="build" ;;
        --uninstall) MODE="uninstall" ;;
        --apk)       shift; [ $# -gt 0 ] || die "--apk needs a path"; APK="$1"; MODE="file" ;;
        --serial|-s) shift; [ $# -gt 0 ] || die "--serial needs a device id"; SERIAL="$1" ;;
        -h|--help)   usage ;;
        *)           die "unknown option: $1 (try --help)" ;;
    esac
    shift
done

# --- adb -------------------------------------------------------------------

command -v adb >/dev/null 2>&1 || die "adb is not on PATH.
  macOS:   brew install --cask android-platform-tools
  Linux:   sudo apt install android-tools-adb
  Windows: install Android SDK platform-tools, or use tools/install.ps1"

adb start-server >/dev/null 2>&1 || true

# Collect attached devices, separating the states that need the user to act.
mapfile -t DEVICE_LINES < <(adb devices | tail -n +2 | grep -v '^\s*$' || true)

READY=()
for line in "${DEVICE_LINES[@]}"; do
    serial="$(printf '%s' "$line" | awk '{print $1}')"
    state="$(printf '%s' "$line" | awk '{print $2}')"
    case "$state" in
        device)       READY+=("$serial") ;;
        unauthorized) die "Phone $serial has not authorised this computer.
  Unlock the screen and tap \"Allow USB debugging\", then run this again." ;;
        offline)      warn "Phone $serial is offline - replug the cable if it is not picked up." ;;
    esac
done

if [ -n "$SERIAL" ]; then
    READY=("$SERIAL")
elif [ "${#READY[@]}" -eq 0 ]; then
    die "No phone is connected with USB debugging on.
  On the phone: Settings > About phone > tap \"Build number\" seven times,
  then Settings > Developer options > USB debugging."
elif [ "${#READY[@]}" -gt 1 ]; then
    die "More than one device is attached: ${READY[*]}
  Pick one with --serial <id>."
fi

DEVICE="${READY[0]}"
adb_() { adb -s "$DEVICE" "$@"; }

# `settings get` comes back with a trailing carriage return on many devices.
adb_sh() { adb_ shell "$@" | tr -d '\r'; }

MODEL="$(adb_sh getprop ro.product.model || echo "unknown")"
RELEASE="$(adb_sh getprop ro.build.version.release || echo "?")"
SDK_LEVEL="$(adb_sh getprop ro.build.version.sdk || echo 0)"

bold "AiOS installer"
info "Device:  ${MODEL} (${DEVICE})"
info "Android: ${RELEASE} (API ${SDK_LEVEL})"

if [ "${SDK_LEVEL:-0}" -lt 26 ] 2>/dev/null; then
    die "AiOS needs Android 8.0 (API 26) or newer; this device reports API ${SDK_LEVEL}."
fi

# --- uninstall --------------------------------------------------------------

if [ "$MODE" = "uninstall" ]; then
    bold "Removing AiOS"
    # Drop the accessibility grant first: uninstalling alone can leave the
    # component listed in secure settings.
    current="$(adb_sh settings get secure enabled_accessibility_services || echo "")"
    if [ "$current" != "null" ] && [ -n "$current" ]; then
        remaining="$(printf '%s' "$current" | tr ':' '\n' | grep -v "^${PACKAGE}/" | paste -sd: - || true)"
        adb_sh settings put secure enabled_accessibility_services "${remaining:-""}" >/dev/null || true
        info "Revoked the accessibility grant."
    fi
    adb_ uninstall "$PACKAGE" >/dev/null 2>&1 && info "Uninstalled ${PACKAGE}." \
        || warn "${PACKAGE} was not installed."
    exit 0
fi

# --- find an APK ------------------------------------------------------------

download_latest() {
    local url
    command -v curl >/dev/null 2>&1 || die "curl is needed for --download"
    info "Looking up the latest release of ${REPO}…"
    url="$(curl -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
        | grep -o '"browser_download_url": *"[^"]*\.apk"' \
        | head -1 | cut -d'"' -f4)" || true
    [ -n "$url" ] || die "No published APK found.
  Tag a commit (git tag v0.1.0 && git push --tags) to have CI build and publish one,
  or download the 'aios-apk' artifact from the Actions tab and pass it with --apk."
    APK="$(mktemp -d)/aios.apk"
    info "Downloading $(basename "$url")"
    curl -fsSL -o "$APK" "$url" || die "download failed"
}

build_locally() {
    [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ] || [ -f "${REPO_ROOT}/local.properties" ] \
        || die "No Android SDK found, so the APK cannot be built here.
  Either install the SDK, or use --download to fetch a CI-built APK."
    info "Building the APK (this takes a few minutes the first time)…"
    ( cd "$REPO_ROOT" && ./gradlew :app:assembleDebug --no-daemon -q )
    APK="$LOCAL_APK"
}

case "$MODE" in
    download) download_latest ;;
    build)    build_locally ;;
    file)     [ -f "$APK" ] || die "no such file: $APK" ;;
    auto)
        if [ -f "$LOCAL_APK" ]; then
            APK="$LOCAL_APK"
            info "Using the APK already built at app/build/outputs/apk/debug/"
        elif [ -n "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
            build_locally
        else
            download_latest
        fi
        ;;
esac

[ -f "$APK" ] || die "APK not found: $APK"

# --- install ----------------------------------------------------------------

bold "Installing"
info "$(basename "$APK") ($(du -h "$APK" | cut -f1))"

# -r replaces an existing install, -g pre-grants runtime permissions so the run
# notification (and its Stop button) is never silent.
if ! adb_ install -r -g "$APK" 2>&1 | tail -2 | grep -qi success; then
    # A signature clash means an older build is installed with a different key.
    warn "Install failed. If this says INSTALL_FAILED_UPDATE_INCOMPATIBLE, run:"
    warn "  ./tools/install.sh --uninstall   then install again."
    die "adb install did not report success"
fi
info "Installed ${PACKAGE}."

# --- grant the agent its hands ---------------------------------------------

bold "Enabling the accessibility service"
info "This is what lets the agent read the screen and tap, swipe and type."

current="$(adb_sh settings get secure enabled_accessibility_services || echo "")"
if [ "$current" = "null" ] || [ -z "$current" ]; then
    updated="$SERVICE"
elif printf '%s' "$current" | grep -q "$SERVICE"; then
    updated="$current"
else
    # Preserve any other accessibility services the user relies on.
    updated="${current}:${SERVICE}"
fi

adb_sh settings put secure enabled_accessibility_services "$updated" >/dev/null
adb_sh settings put secure accessibility_enabled 1 >/dev/null

verify="$(adb_sh settings get secure enabled_accessibility_services || echo "")"
if printf '%s' "$verify" | grep -q "$SERVICE"; then
    info "Granted. AiOS can now operate this phone."
else
    warn "Could not enable it over the cable - some vendor builds refuse this."
    warn "Turn on \"AiOS agent control\" by hand in Settings > Accessibility."
fi

# --- done -------------------------------------------------------------------

adb_ shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true

bold "Done"
info "Open AiOS on the phone, paste your Anthropic API key in Settings, and give it a goal."
info "Every run shows a notification with a Stop button."
info "To remove it and revoke the access: ./tools/install.sh --uninstall"
