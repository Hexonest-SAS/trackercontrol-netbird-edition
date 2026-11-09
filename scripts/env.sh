# À sourcer : `. scripts/env.sh` — outils de compilation du fork (sans sudo).
# Chaque variable peut être posée avant (CI, autre machine) ; sinon valeurs locales par défaut.
[ -d "$HOME/.local/bin" ] && export PATH="$HOME/.local/bin:$PATH"   # go, gomobile, gobind
if command -v go >/dev/null 2>&1; then
  export PATH="$(go env GOPATH)/bin:$PATH"   # où `go install` pose gomobile/gobind
fi
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/${NDK_VERSION:-29.0.13846066}}"
