# TV Head

A single-purpose Android app: fullscreen, landscape, always-on display for a Deltarune
TV-head cosplay. A 6-key programmable USB keyboard triggers clips and stills; whatever is
pressed stays on screen until something else is pressed.

## What it does

- Launches straight into a fullscreen landscape display - no title bar, no status bar, no
  on-screen controls. The screen never sleeps.
- Shows a **default face** on launch. If that file is a video it loops.
- Pressing a mapped key chord switches to that clip or still image. **It stays there** -
  nothing returns to the default on its own.
- Videos loop while shown. `.jpg` / `.png` stills are decoded to a bitmap and held.
- Unmapped keys are ignored, but their name is printed on screen so you can learn what the
  keyboard sends.
- Media and key mappings live on the device, so they can be changed over USB without
  rebuilding the APK.
- **Tap the screen** to reveal a gear icon that opens an on-screen bindings editor, so keys
  can be re-bound on the tablet with no cable. See [Bindings panel](#bindings-panel).

## Build and install

Prerequisites already set up on this machine: JDK 21, `adb`, Android SDK
(`%LOCALAPPDATA%\Android\Sdk` with platform 35 + build-tools 35.0.0), and a local Gradle
wrapper.

```powershell
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then push your media - this is the whole deploy step:

```powershell
.\tools\push-media.ps1
```

Everything under the repo `media/` folder goes to `/sdcard/Movies/tv-head/` on the tablet,
which also shows up in Windows Explorer when the tablet is plugged in. `adb push` is used
because it is more reliable than MTP, and it creates the folders for you.

### Working over WiFi

```powershell
adb connect 192.168.1.157:5555
adb devices
```

## Mapping keys

Edit `media/media.json` in the repo, then push it:

```powershell
.\tools\push-media.ps1
```

On the tablet the config lives in the app-specific folder, not beside the media, because
Android's scoped storage refuses an app path access to a non-media file such as `.json` in
shared storage - even with permissions granted. The folder needs no permission and is still
reachable with `adb`.

```
/sdcard/Android/data/com.ortus.tvhead/files/media.json
```

To pull back whatever is currently on the device:

```powershell
adb pull /sdcard/Android/data/com.ortus.tvhead/files/media.json .
```

Then press the chord you bound to `__rescan__` (or restart the app) to pick up the change.
The startup banner shows the exact config file in use, so there is no doubt which one was
read.

```json
{
  "default": "face.mp4",
  "scale": "fit",
  "showKeyDebug": true,
  "bindings": [
    { "keys": "F13",          "media": "laugh.mp4" },
    { "keys": "F14",          "media": "blink.mp4" },
    { "keys": "F16",          "media": "stare.jpg" },
    { "keys": "Ctrl+Shift+A", "media": "sad.mp4" },
    { "keys": "F17",          "media": "__rescan__" },
    { "keys": "Ctrl+Shift+Q", "media": "__quit__" }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `default` | The face shown on launch. `"*"` or `"random"` picks one at random; omit it to use the first file found. |
| `scale` | `fit` (letterbox, default), `fill` (stretch to the screen) or `zoom` (crop to fill). |
| `showKeyDebug` | Print every key press on screen. Default `true` - turn it off for the real costume. |
| `bindings` | `keys` is the chord, `media` is the file to show. `video` is still accepted for older files. |

### Chords

`keys` is case-insensitive and whitespace-tolerant, so `Ctrl + Shift + A`, `ctrl+shift+a`
and `KEYCODE_CTRL_LEFT+KEYCODE_SHIFT_LEFT+KEYCODE_A` are all the same binding.

- Single keys: `F1`-`F24`, `a`, `7`, `ENTER`, `SPACE`, `TAB`, `ESCAPE`, `BACKSPACE`,
  `DELETE`, `HOME`, `BACK`, `VOLUME_UP`, `VOLUME_DOWN`, `MEDIA_PLAY_PAUSE`, `MEDIA_NEXT`,
  `MEDIA_PREVIOUS`, `MEDIA_STOP`
- Programmable keypads usually send `F13`-`F24`. Those are real Android key codes (183-194)
  and are named `F13`...`F24` here, so no `KEYCODE_183` guesswork is needed.
- Modifiers: `Ctrl`, `Shift`, `Alt`, `Meta` (left and right collapse together, because
  keyboards report them inconsistently)
- Combinations: `Ctrl+Shift+A`, `Alt+F13`

A chord matches only when **exactly** those keys are held, so `A` and `Ctrl+A` are two
different bindings and neither shadows the other.

Anything the app does not recognise still works as a binding under the name
`KEYCODE_<number>`, which the on-screen debug overlay tells you - so nothing is ever
silently unmatchable.

### Reserved values

| Value | Effect |
| --- | --- |
| `__quit__` | Closes the app |
| `__rescan__` | Re-reads `media.json` and the media folder, then shows the default face |

`__rescan__` matters: unmapped keys are swallowed and the Back button is consumed, so
without it there is no way out short of `adb`.

## Bindings panel

Because unmapped keys are swallowed and the Back button is consumed, normal Android
navigation is unavailable, so the editor is reached by touch:

1. **Tap anywhere** on the screen (or double tap). A gear icon appears in the bottom-right
   corner and stays for 5 seconds.
2. **Tap the gear** to open the panel.

The panel lists every media file on the device - not just the ones already bound - with a
thumbnail, its current key, and a `video`/`still` tag. Selecting a row shows a large preview
on the right.

- **Re-bind key** - the button changes to `Press a key...`, then the next key or combo you
  press becomes that file's binding. The config is written to disk immediately.
- **Clear key** - removes the binding from the selected file.
- **Close** - dismisses the panel.

While the panel is open, media bindings do not fire (so behind it the face does not change),
and hardware **volume** buttons still work. **Escape** closes the panel, so a capture can
never become a dead end.

Bindings are one-to-one in both directions, and re-binding enforces that automatically: if
you give a file a key that another file already used, the other file is unbound, and if a
file already had a key, that old key is freed. So a file never ends up with two keys and a
key never fires two files.

## Finding out what your keyboard sends

Leave `showKeyDebug` at `true`, run the app, and press each key. The top-left corner prints
e.g. `KEY: F13  (no binding)`. Put those names in `media.json` verbatim, or just use the
bindings panel and press the key. `logcat` shows the same thing:

```powershell
adb logcat -s TvHead
```

## Where the files live

The repo `media/` folder is the source of truth - it is version controlled, and pushing it
to the tablet is all that is needed:

```
media/
  videos/   angry.mp4  big_mouth.mp4  blush_smile.mp4  ...
  images/   angry.jpg  blushing_smile.jpg  cat.jpg  ...
  media.json
```

`tools/push-media.ps1` flattens both subfolders onto the tablet, and puts `media.json`
where the app can read it:

```powershell
.\tools\push-media.ps1
```

That is the whole deploy step. It copies every supported file from `media/` (recursively)
to `/sdcard/Movies/tv-head/` and `media/media.json` to the app's config folder.

Push to a different folder, or a specific device:

```powershell
.\tools\push-media.ps1 -Source D:\other\media -Config .\media\media.json
.\tools\push-media.ps1 -Source .\media -Serial 192.168.1.157:35089
```

In order, first match wins:

1. `/sdcard/Movies/tv-head/` ← use this one
2. `/sdcard/tv-head/`
3. `/sdcard/Android/data/com.ortus.tvhead/files/media/`
4. bundled `assets/media/`

Names match leniently: an entry of `loop` finds `loop.mp4`, and `Loop.MP4` finds `loop.mp4`.
Supported: `.mp4 .m4v .mkv .webm .avi .mov .3gp .ts` and `.jpg .jpeg .png .webp .bmp`.

The mapping config is looked for separately, since it is not media:

1. `/sdcard/Android/data/com.ortus.tvhead/files/media.json` ← where it is written
2. `/sdcard/Movies/tv-head/media.json` (only readable with all-files access)

## Exiting

- Bind a chord to `__quit__`, or
- `adb shell am force-stop com.ortus.tvhead`

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/ortus/tvhead/MainActivity.java` | Fullscreen display, key handling, image/video switching |
| `app/src/main/java/com/ortus/tvhead/KeyChord.java` | Parses and matches key chords |
| `app/src/main/java/com/ortus/tvhead/Config.java` | Reads `media.json` |
| `app/src/main/java/com/ortus/tvhead/MediaLibrary.java` | Finds media, lists it, classifies video vs image |
| `tools/push-media.ps1` | Copies `media/` + config to the tablet |
| `tools/probe-media.ps1` | Reports codec details for every video in `media/` |
| `tools/make-test-media.ps1` | Generates throwaway clips/stills for testing |
| `tools/setup-sdk.ps1` | One-time Android SDK install |

## Verifying on the device

The app tags everything with `TvHead`, so a single filter shows startup, config loading
every key press, and each switch:

```powershell
adb logcat -s TvHead
```

To exercise a binding without the keypad:

```powershell
adb shell input keyevent 29                       # 29 = A
adb shell input keyevent 184                      # 184 = F13
adb shell input keycombination 113 59 47          # Ctrl+Shift+S
```

## Notes

- `minSdk 26`, `targetSdk 35`, landscape-locked via `android:screenOrientation`.
- The backdrop is **white**, not black. The artwork is drawn on white, and switching media
  briefly blanks the player while the next item is prepared, so a white backdrop hides that
  gap instead of flashing. `keep_content_on_player_reset` on the `PlayerView` keeps the last
  frame up across a player reset, and the image view is only swapped in once the decoded
  bitmap is ready, so neither of those shows the backdrop in practice.
- Release builds are signed with the debug key on purpose so a side-loaded APK can be
  updated in place without managing a keystore.
- Playback is ExoPlayer (Media3), which uses the tablet's hardware decoder. If a file will
  not play, transcode it to H.264/AAC in an MP4 container - `.mpg`/MPEG-1/2 files generally
  will not decode. `tools/make-test-media.ps1` shows the ffmpeg settings that are known to
  work (H.264 high profile, yuv420p, 1920x1080).
- Media is read with `FileInputStream`, not `java.nio.file.Files`. Android's FUSE layer can
  refuse `java.nio` path access to shared storage with `AccessDeniedException` even for a
  file the app may read, so the older stream API is the portable choice.
- Unmapped keys are swallowed and Back is consumed, so always keep a `__rescan__` and a
  `__quit__` binding - they are the only way out short of `adb`.


