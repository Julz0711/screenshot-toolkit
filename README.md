# Screenshot Toolkit

A Minecraft screenshot workflow that stays inside the game: capture, copy, browse, organize, and edit your shots.

**Status:** `main` contains this overview. Source code, build files, tests, and full usage instructions are on [`feat/screenshot-toolkit`](https://github.com/Julz0711/screenshot-toolkit/tree/feat/screenshot-toolkit).

## Features on the implementation branch

| Area | Capabilities |
| --- | --- |
| Capture | Copy F2 screenshots to the clipboard and show a thumbnail toast. |
| Clean shots | Press F6 to hide the HUD and hand for the capture, then restore them. |
| Gallery | Press F9 to browse thumbnails, search, filter, star, and select batches. |
| Organization | Add shots to albums without moving the image files. |
| Viewer | Zoom, pan, rename, crop, copy, or open an image in the system viewer. |
| Location | Save world, dimension, coordinates, and time; copy a teleport command. |
| Recovery | Move deleted shots to trash, undo deletion, and restore from the trash view. |

Keybindings are configurable. Teleport commands require the normal in-game permissions.

## Requirements

Minecraft **26.3**, **Java 25**, Fabric Loader **0.19.5+**, and Fabric API for 26.3. Mod Menu is optional; settings are also available from the gallery.

Image copying uses native platform tools:

| Platform | Clipboard tool |
| --- | --- |
| Windows | PowerShell |
| macOS | AppleScript / `osascript` |
| Linux with Wayland | `wl-copy` from `wl-clipboard` |
| Linux with X11 | `xclip` |

## Build the implementation

```sh
git clone --branch feat/screenshot-toolkit https://github.com/Julz0711/screenshot-toolkit.git
cd screenshot-toolkit
./gradlew build
```

Copy the playable JAR from `build/libs/` into your Fabric `mods/` directory. On Windows, use `gradlew.bat build`.

Run client tests with `./gradlew runClientGameTest`; they open a game window and require a graphical display.

## Files and settings

Images stay in Minecraft's `screenshots/` directory. Sidecar metadata lives in `.meta/`, cached thumbnails in `.thumbs/`, and deleted shots in `.trash/`. Trash retention defaults to seven days. Cropping can create a new image or replace an original while retaining the original in trash.

Settings are stored in `config/screenshot-toolkit.json`:

```json
{
  "copyToClipboard": true,
  "showToast": true,
  "keepChatMessage": false,
  "saveMetadata": true,
  "trashDays": 7
}
```

## Implementation map

The branch separates in-game screens and capture code in `client/`, file management and metadata in `core/`, configuration in `config/`, and Minecraft hooks in `mixin/`, under `src/main/java/com/julz/screenshottoolkit/`.

See the [implementation README](https://github.com/Julz0711/screenshot-toolkit/blob/feat/screenshot-toolkit/README.md) for gallery shortcuts, batch operations, albums, cropping, and platform-specific behavior.
