#!/usr/bin/env bash
# Lanza ksqlc con un JDK 21 (el que exige jvmToolchain(21) en los build.gradle.kts).
#
# Uso:  ./run.sh [argumentos de gradlew...]
#       ./run.sh                  -> ./gradlew :app:run
#       ./run.sh test             -> ./gradlew test
# Forzar un JDK:  KSQLC_JAVA_HOME=/ruta/al/jdk ./run.sh
set -euo pipefail

REQUIRED_MAJOR=21
cd "$(dirname "$0")"

java_major() {  # imprime la versión mayor del JDK en $1, o nada si no es válido
  local out
  out=$("$1/bin/java" -version 2>&1) || return 0
  sed -nE 's/.*version "([0-9]+)(\.[0-9]+)*.*/\1/p' <<<"$out" | head -n1
}

is_required() { [[ -x "$1/bin/java" && "$(java_major "$1")" == "$REQUIRED_MAJOR" ]]; }

find_jdk() {
  local c
  # 1. Override explícito, luego JAVA_HOME actual.
  for c in "${KSQLC_JAVA_HOME:-}" "${JAVA_HOME:-}"; do
    [[ -n "$c" ]] && is_required "$c" && { echo "$c"; return; }
  done
  # 2. SDKMAN: JDK 21 sin JavaFX ni GraalVM (la versión más alta primero).
  local sdk="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
  if [[ -d "$sdk" ]]; then
    while IFS= read -r c; do
      is_required "$sdk/$c" && { echo "$sdk/$c"; return; }
    done < <(ls -1 "$sdk" | grep -E "^${REQUIRED_MAJOR}\." | grep -vE 'fx|graal' | sort -rV)
  fi
  # 3. macOS: java_home.
  if [[ -x /usr/libexec/java_home ]]; then
    c=$(/usr/libexec/java_home -v "$REQUIRED_MAJOR" 2>/dev/null || true)
    [[ -n "$c" ]] && is_required "$c" && { echo "$c"; return; }
  fi
  return 1
}

if ! JDK=$(find_jdk); then
  cat >&2 <<MSG
No encontré un JDK $REQUIRED_MAJOR.
Instálalo con SDKMAN:   sdk install java 21.0.2-zulu
o indica uno:           KSQLC_JAVA_HOME=/ruta/al/jdk $0
MSG
  exit 1
fi

export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"
echo "ksqlc: usando JDK $REQUIRED_MAJOR en $JAVA_HOME" >&2

[[ $# -eq 0 ]] && set -- :app:run
exec ./gradlew "$@"
