#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

APP_ID="com.example.app"
MODULE="app"
VARIANT="Debug"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
DO_CLEAN=0
DO_LAUNCH=0

usage() {
  cat <<'EOF'
Сборка и установка Android-приложения на все подключенные устройства.

Использование:
  ./scripts/build_and_install_connected.sh [опции]

Опции:
  --app-id <id>       Application ID (по умолчанию: com.example.app)
  --module <name>     Gradle module (по умолчанию: app)
  --variant <name>    Build variant без assemble (по умолчанию: Debug)
  --apk-path <path>   Явный путь к APK
  --clean             Выполнить clean перед сборкой
  --launch            Запустить приложение после установки
  -h, --help          Показать справку
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app-id)
      APP_ID="${2:-}"; shift 2 ;;
    --module)
      MODULE="${2:-}"; shift 2 ;;
    --variant)
      VARIANT="${2:-}"; shift 2 ;;
    --apk-path)
      APK_PATH="${2:-}"; shift 2 ;;
    --clean)
      DO_CLEAN=1; shift ;;
    --launch)
      DO_LAUNCH=1; shift ;;
    -h|--help)
      usage; exit 0 ;;
    *)
      echo "Неизвестный аргумент: $1" >&2
      usage
      exit 1 ;;
  esac
done

if [[ ! -x "$ROOT_DIR/gradlew" ]]; then
  echo "Ошибка: не найден исполняемый gradlew в $ROOT_DIR" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "Ошибка: adb не найден в PATH. Установите Android platform-tools." >&2
  exit 1
fi

ASSEMBLE_TASK=":$MODULE:assemble$VARIANT"

echo "==> Проверка ADB"
adb start-server >/dev/null

if [[ "$DO_CLEAN" -eq 1 ]]; then
  echo "==> Gradle clean"
  ./gradlew clean
fi

echo "==> Сборка: $ASSEMBLE_TASK"
./gradlew "$ASSEMBLE_TASK"

if [[ ! -f "$APK_PATH" ]]; then
  echo "Ошибка: APK не найден по пути: $APK_PATH" >&2
  echo "Подсказка: укажите --apk-path <path> или проверьте module/variant." >&2
  exit 1
fi

DEVICES=()
while IFS= read -r serial; do
  [[ -n "$serial" ]] && DEVICES+=("$serial")
done < <(adb devices | awk 'NR>1 && $2=="device" {print $1}')
if [[ "${#DEVICES[@]}" -eq 0 ]]; then
  echo "Ошибка: нет подключенных устройств (adb devices)." >&2
  exit 1
fi

echo "==> Найдено устройств: ${#DEVICES[@]}"
printf ' - %s\n' "${DEVICES[@]}"

SUCCESS=()
FAILED=()

for serial in "${DEVICES[@]}"; do
  echo "==> [$serial] Установка APK"
  if adb -s "$serial" install -r -t "$APK_PATH"; then
    SUCCESS+=("$serial")
    if [[ "$DO_LAUNCH" -eq 1 ]]; then
      echo "==> [$serial] Запуск приложения"
      adb -s "$serial" shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null || true
    fi
  else
    FAILED+=("$serial")
  fi
done

echo
echo "===== РЕЗУЛЬТАТ ====="
echo "Успешно: ${#SUCCESS[@]}"
if [[ "${#SUCCESS[@]}" -gt 0 ]]; then
  printf ' + %s\n' "${SUCCESS[@]}"
fi

echo "Ошибки: ${#FAILED[@]}"
if [[ "${#FAILED[@]}" -gt 0 ]]; then
  printf ' - %s\n' "${FAILED[@]}"
  exit 2
fi

echo "Готово."
