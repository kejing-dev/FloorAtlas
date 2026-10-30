#!/usr/bin/env bash
# One design state -> .scratch/shots/<name>.png (debug broadcast, wait, console capture twice).
#   tools/st.sh NAME "state explore+selected" [WAIT_S]
set -u
DEV=${DEV:-emulator-5562}
export PATH="$PATH:$LOCALAPPDATA/Android/Sdk/platform-tools"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
name=$1; shift
cmd=$1; shift
wait=${1:-12}
c=${cmd%% *}; s=${cmd#* }; [ "$s" = "$cmd" ] && s=""
adb -s $DEV shell "am broadcast -a io.embodit.flooratlas.DEBUG -p io.embodit.flooratlas --es cmd $c ${s:+--es s '$s'}" >/dev/null
sleep "$wait"
"$ROOT/tools/cap.sh" "$name" >/dev/null
sleep 2
"$ROOT/tools/cap.sh" "$name" | tail -1
