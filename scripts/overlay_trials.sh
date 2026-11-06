#!/usr/bin/env bash
# Times the whole-app pause overlay. Usage: overlay_trials.sh <source: accessibility|usage> <warm count> <cold count> <out.csv>
# Phone must be unlocked. Latency is measured from just before the launch command, on the phone's clock.
set -u
SRC=$1; WARM=$2; COLD=$3; OUT=$4
P=dev.keel.spike; Y=com.google.android.youtube
COMP="$Y/.app.honeycomb.Shell\\\$HomeActivity"
ev() { adb shell run-as $P cat files/events.csv 2>/dev/null; }
[ -f "$OUT" ] || echo "source,kind,trial,drawn,overlay_ms,app_total_ms" > "$OUT"
trial() {
  local kind=$1 n=$2
  adb shell input keyevent KEYCODE_HOME; sleep 2
  [ "$kind" = cold ] && { adb shell am force-stop $Y; sleep 1; }
  local mark; mark=$(ev | wc -l)
  local raw; raw=$(adb shell "date +%s%N; am start -W -n $COMP" | tr -d '\r')
  local t0 total
  t0=$(echo "$raw" | head -1 | cut -c1-13)
  total=$(echo "$raw" | sed -n 's/^TotalTime: //p')
  sleep 2
  local line; line=$(ev | tail -n +$((mark+1)) | grep "overlay_drawn,$SRC" | head -1)
  if [ -n "$line" ]; then
    echo "$SRC,$kind,$n,1,$(( ${line%%,*} - t0 )),${total:-}" >> "$OUT"
  else
    echo "$SRC,$kind,$n,0,,${total:-}" >> "$OUT"
    echo "MISS trial=$n mark=$mark now=$(ev | wc -l) t0=$t0 tail:" >&2
    ev | tail -n 4 | cut -c1-90 >&2
  fi
}
for i in $(seq 1 "$WARM"); do trial warm "$i"; done
for i in $(seq 1 "$COLD"); do trial cold "$i"; done
adb shell input keyevent KEYCODE_HOME
