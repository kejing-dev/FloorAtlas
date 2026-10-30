# Floor Atlas (脚下地图)

Geography on your floor for PICO OS 6. Kotlin, PICO Spatial SDK 6.1.9, one Mixed Stage (Full Space only, skeleton D),
package `io.embodit.flooratlas`. A satellite-relief world map (NASA Blue Marble, tiled levels 0–6, real elevation
relief) lies on the floor in front of you. You stay put: trigger-drag pans it, stick left/right turns it about the
crosshair at your feet, stick up/down (or two-hand pinch, or the board's − +) zooms 2.4–98 m. Explore shows a set's
places; Quiz asks "Go to X": bring X into the ring at your feet and hold still 0.8 s (or click). 6 sets, 67 places.

Design package (binding): `../spatial-app-factory/apps/floor-atlas/design-package.json` (v5). PRD and sample: see
`state.json` artifacts. Base code ported from Furniture Fit / Spatial Ruler (panel pool, Stage root, gestures,
DragLine, ControllerStick, HeadTracker, PlaneTracker, theme, fonts).

## Structure

```
app/src/main/assets/atlas/  sets.json (tools/make_maps.py), index.json + elev.h16 + tiles/z/i_j.{jpg,h16} (tools/make_tiles.py)
app/src/main/java/io/embodit/flooratlas/
  domain/      pure Kotlin, JVM-tested: Atlas (data model + JSON), MapPose (map on the floor), Judge (distance, pick,
               snap, haversine), Scale, Quiz + DwellJudge (arm / settle / rejudge), Tiles (LOD plan), Settings + Relief
  platform/    AtlasGraph, HeadTracker, ControllerStick (X turn, Y zoom), PlaneTracker (FLOOR height only),
               TileStore (assets, 2 decode threads, global height grid), Prefs, Audio (SoundPool cues, TTS)
  ui/atlas/    AtlasUiState (+ designState naming), AtlasViewModel (phases, quiz, feedback, bests), AtlasScreen
  ui/atlas/scene/  AtlasScene (one ECS system: map transform, drag/stick/scale, crosshair, outlines, labels, panel
               placement), TileLayer (<= 30 drawn, <= 48 resident, 1 upload / frame, no holes), PanelRig, SceneKit
  ui/atlas/components/  Board (all phases), Overlays (question tag, menu, snackbar, coach, labels, plates), AtlasStage
app/src/debug/ DebugCommandReceiver + DebugCommands
tools/         make_tiles.py, make_maps.py (+ ne-data/), make_icon.py, make_icons.py, subset_cjk_font.py, dev.sh, harness/
```

Coordinates: map degrees (x east of 30 W, 0..360; lat 84 N..80 S). The map root is scaled (s, 1, s) and turned by
yaw; tiles and outlines live under a relief node scaled (1, V, 1) with heights in metres (V keeps relief ≤ 0.10 m,
≤ 0.03 m in a quiz). Texture v = 0 is the bitmap's bottom row (tile mesh puts v = 1 at the north edge).

Rules: SpatialUI + PicoTheme only (Material gate `../spatial-app-factory/bin/material-grep.txt`); all 2D UI is
pre-built Stage attachment panels; per-frame motion only in `AtlasScene.onFrame`; panels read `scene.live` (10 Hz).
After changing zh copy or sets.json run `python tools/subset_cjk_font.py`.

## Build, run (Git Bash)

```bash
export JAVA_HOME='C:\Users\Admin\.pico\primer-cli\jdk\jdk-21.0.12.1+1'
./gradlew.bat assembleDebug testDebugUnitTest
DEV=emulator-5570 tools/dev.sh install && DEV=emulator-5570 tools/dev.sh launch
python tools/make_tiles.py && python tools/make_maps.py   # data (downloads cached in tools/.cache)
```

## Debug commands (debug build)

`adb -s <serial> shell "am broadcast -a io.embodit.flooratlas.DEBUG -p io.embodit.flooratlas --es cmd NAME [--es s TEXT] [--es a NUMBERS]"`
Every command answers one `CMD` line in logcat (tag `FloorAtlas`).

| cmd | args | effect |
|---|---|---|
| `state` | s=`phase[+overlay...][:mode][@setId]` | build that design state, place the map for it, freeze clock and short animations until the next command |
| `set` / `mode` | s=setId / explore\|quiz | like the picker |
| `drag` | a=dx,dz | pan the map (metres) |
| `rotate` / `zoom` | a=deg / a=long side m | turn about the crosshair / zoom about it |
| `stick` | a=x,y[,ms] | the thumbstick reads (x, y) for ms (same path as the controller) |
| `goto` | s=targetId or landform (`everest`, `kilimanjaro`, …) | bring its safe point under the crosshair |
| `goto_wrong` / `goto_nowhere` | | another place of the set / a point far from all |
| `dwell` | | judge the crosshair now (asking: answer; explore: select) |
| `answer_all` | a=rate | finish the quiz (rate first-try) → result |
| `relay` | | Bring to me (re-lay map, crosshair, board at the current head pose) |
| `nofloor` / `lost` | s=on\|off | ignore FLOOR planes (estimate after 5 s) / force tracking lost |
| `coach_reset` / `best_reset` | | |
| `ui` | s=control | back more menu_close mute play pause resume restart review again change_set measure measure_done start_quiz coach_ok point drag_mode zh en system speak rotl rotr zin zout exit |
| `dump` / `pose` / `panels` | | `DUMP {json}` with designState (also files/dump.json) / scene line / panel slots |

## QA screenshot list (design `states[]`, naming `phase[+overlay][:mode]`)

starting · picker · picker+muted · picker+menu · picker+floorEstimate (`nofloor on`, relaunch, wait 6 s) · explore ·
explore+selected · explore+measuring (Beijing → Cairo ≈ 7,550 km) · explore+terrain (zoom 60 + goto everest) ·
explore+zoomLimit · explore+nowhere · explore+offMap · explore+moving · explore+coach · explore:point · quizReady ·
asking · asking+dwell · asking+right · asking+wrong · asking+reveal · asking+coach · asking:point · paused ·
result · result:review · asking+lost (paused) · blocked.
Snap: `state explore`, `goto africa`, `drag 0 0.05` … release snaps the crosshair inside (log `SNAP`).
Arm sequence (T9): `state asking`, `dwell` on the current answer is refused until the map moved 0.3 m or the
crosshair changed target: `goto <current>` then wait 1 s → judged right.

## Log prefixes

`STATE` `EVENT` `QUIZ` `ANSWER` `MAP` `SNAP` `TILE` `TAP` `DRAG` `PLANES` `FLOOR` `PANEL` `FRAME` (every 5 s: size,
yaw, relief, floor, stick, tiles) `CMD` `DUMP`.
