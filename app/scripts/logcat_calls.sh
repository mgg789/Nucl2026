#!/usr/bin/env bash
# Логи звонков WebRTC. Запустить перед тестом звонка, затем инициировать/принять вызов.
# Использование: ./scripts/logcat_calls.sh [номер_устройства]
# Номер устройства из 'adb devices' (например 0 для первого).

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

DEVICE=""
if [ -n "$1" ]; then
  DEVICE="-s $1"
fi

echo "=== PeerDone Call logs (adb $DEVICE). Filter: PeerDoneCall, WebRtcCallSession ==="
adb $DEVICE logcat -c
adb $DEVICE logcat -s PeerDoneCall:V WebRtcCallSession:V
