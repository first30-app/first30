#!/usr/bin/env bash
# 클라우드 세션(Claude Code on the web) 준비: JDK 17, Android SDK, 골든 픽스처.
# - 로컬(Mac)에서는 아무것도 하지 않는다(CLAUDE_CODE_REMOTE 가 "true" 일 때만 동작).
# - 이미 설치돼 있으면 건너뛴다(여러 번 실행해도 됨). 클라우드 환경 설정의 "setup script" 칸에 그대로 붙여 넣어도 된다.
# - Android SDK 는 dl.google.com 에서 받는다. 환경의 네트워크를 Custom(dl.google.com 추가) 또는 Full 로 해야 한다.
#   못 받아도 :core 테스트(엔진)와 npm test 는 돌아간다.
set -u
[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && SUDO="sudo"
log() { echo "[cloud-setup] $*"; }

# 1) JDK 17 (이미지에는 JDK 21 만 있다 — Gradle 툴체인이 17 을 요구한다)
JDK17="$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)"
if [ -z "$JDK17" ]; then
  log "JDK 17 설치"
  $SUDO apt-get update -qq >/dev/null 2>&1 || true
  $SUDO apt-get install -y -qq openjdk-17-jdk-headless >/dev/null 2>&1 || log "JDK 17 설치 실패 — 네트워크 설정 확인"
  JDK17="$(ls -d /usr/lib/jvm/java-17-openjdk-* 2>/dev/null | head -1)"
fi

# 2) Android SDK (compileSdk 35, build-tools 35)
SDK="${ANDROID_HOME:-/opt/android-sdk}"
if [ ! -d "$SDK/platforms/android-35" ]; then
  log "Android SDK 설치 ($SDK)"
  $SUDO mkdir -p "$SDK/cmdline-tools" && $SUDO chown -R "$(id -u):$(id -g)" "$SDK"
  TMP="$(mktemp -d)"
  if curl -fsSL -o "$TMP/tools.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
     && unzip -q "$TMP/tools.zip" -d "$TMP" && rm -rf "$SDK/cmdline-tools/latest" && mv "$TMP/cmdline-tools" "$SDK/cmdline-tools/latest"; then
    yes | JAVA_HOME="${JDK17:-${JAVA_HOME:-}}" "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
    JAVA_HOME="${JDK17:-${JAVA_HOME:-}}" "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
      "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null 2>&1 || log "SDK 패키지 설치 실패"
  else
    log "Android SDK 를 받지 못함 — 환경 네트워크에 dl.google.com 을 허용하세요(:app 빌드만 안 됨)"
  fi
  rm -rf "$TMP"
fi
[ -d "$SDK/platforms/android-35" ] && echo "sdk.dir=$SDK" > "$ROOT/android/local.properties"

# 3) 세션 환경 변수 (SessionStart 훅에서 부를 때 CLAUDE_ENV_FILE 로 넘긴다)
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  [ -n "$JDK17" ] && echo "export JAVA_HOME=$JDK17" >> "$CLAUDE_ENV_FILE"
  [ -d "$SDK/platforms/android-35" ] && echo "export ANDROID_HOME=$SDK" >> "$CLAUDE_ENV_FILE"
fi

# 4) Kotlin 골든 테스트 픽스처(.gitignore 대상)
if [ ! -f "$ROOT/android/core/src/test/resources/golden/index.json" ]; then
  log "골든 픽스처 생성"
  (cd "$ROOT" && npm run -s golden >/dev/null 2>&1) || log "npm run golden 실패"
fi

log "완료: JDK17=${JDK17:-없음}, AndroidSDK=$([ -d "$SDK/platforms/android-35" ] && echo "$SDK" || echo 없음)"
exit 0
