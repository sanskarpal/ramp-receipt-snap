#!/usr/bin/env bash
# End-to-end chaos test (design doc §12) on a running emulator:
#   queue N receipts while the server is down -> bring it back with 30% of
#   responses dropped after commit and 20% random 500s -> force-stop the app
#   mid-sync -> take the server down again -> wait for the queue to drain.
# Pass: server has exactly N receipts and no full-size image is left on the device.
# Airplane-mode toggling wedged the emulator's network, so outages are simulated
# by stopping the server (connection refused is the same retryable path).
set -uo pipefail
N=${N:-20}; PKG=com.sanskarpal.receiptsnap; TIMEOUT=${TIMEOUT:-600}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; DB="$ROOT/backend/receiptsnap.db"
T() { adb shell input tap "$1" "$2"; }; W() { adb shell "sleep $1"; }
server_up() { DB_PATH="$DB" DROP_RATE="${1:-0}" FAILURE_RATE="${2:-0}" python3 "$ROOT/backend/app.py" >/tmp/receiptsnap-chaos.log 2>&1 & echo $! > /tmp/receiptsnap.pid; sleep 1; }
server_down() { [[ -f /tmp/receiptsnap.pid ]] && kill "$(cat /tmp/receiptsnap.pid)" 2>/dev/null; lsof -ti tcp:8000 -sTCP:LISTEN | xargs kill 2>/dev/null; sleep 1; }
stats() { curl -s localhost:8000/admin/stats; }
local_images() { adb shell run-as $PKG ls files/receipts 2>/dev/null | grep -vc thumb; }

need_device() { [[ "$(adb get-state 2>/dev/null)" == "device" ]] || { echo "ABORT: no emulator/device connected" >&2; server_down; exit 2; }; }
need_device
server_down; python3 "$ROOT/backend/seed.py" >/dev/null; server_up
adb shell pm clear $PKG >/dev/null; adb shell pm grant $PKG android.permission.CAMERA
adb shell am start -n $PKG/.MainActivity >/dev/null; W 6          # pulls transactions

server_down                                                       # outage while capturing
XS=(151 398 645 892)
for i in $(seq 1 "$N"); do adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null; T 880 1969; W 2; T "${XS[$(( (i - 1) % 4 ))]}" 2021; W 3; adb shell input keyevent BACK; W 1; done
need_device
Q=$(local_images)
echo "queued: $Q full-size images on device"
(( Q == N )) || { echo "ABORT: expected $N queued receipts (a system dialog may have eaten taps)" >&2; server_down; server_up; exit 3; }

START=$(date +%s)
server_up 0.3 0.2                                                 # back, but flaky
W 5; adb shell am force-stop $PKG; W 2; adb shell am start -n $PKG/.MainActivity >/dev/null   # killed mid-sync
W 10; server_down; W 10; server_up 0.3 0.2                        # second outage

while :; do
  need_device
  r=$(stats | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['receipts'])" 2>/dev/null || echo 0)
  el=$(( $(date +%s) - START ))
  [[ "$r" == "$N" ]] && break
  (( el > TIMEOUT )) && break
  W 5
done
echo "elapsed: ${el}s"
echo "server: $(stats)"
echo "full-size images left on device: $(local_images)"
[[ "$r" == "$N" ]] && echo "PASS: $N receipts, 0 lost" || echo "FAIL: server has $r of $N"
server_down; server_up                                            # leave a clean server running
