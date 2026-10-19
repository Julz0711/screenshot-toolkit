# Screenshot Toolkit

A client-side Minecraft Fabric mod for 26.3 that turns F2 into a complete screenshot workflow: every
screenshot lands on your clipboard, and an in-game gallery lets you browse, star, crop and delete them
without leaving the game.

## Features

- **Copy to clipboard**: every F2 screenshot is copied as an image, ready to paste into Discord or a chat.
  On by default.
- **Toast**: a small popup with a thumbnail replaces the "Saved screenshot as" chat line (the chat line can be
  kept in the settings).
- **Gallery** (F9): a grid of thumbnails, newest first. Search by file or world name, show only starred
  screenshots, and filter by world or server and by date. Arrow keys and Enter work too.
- **Viewer**: click a thumbnail to see it full size with its world, dimension, coordinates and time.
  Copy it again, star it, open it in your image viewer, crop it, or delete it. Left and right arrows go to the
  previous and next screenshot.
- **Clean-shot key** (F6): hides the HUD and hand for one frame, takes the screenshot and brings them back.
- **Copy /tp**: copies a command that puts you back where a screenshot was taken, in the right dimension and
  facing the same way (needs cheats or operator rights, like any `/tp`).
- **Crop**: drag over a screenshot in the viewer; the crop is saved as a new file and the original is kept.
- **Safe delete**: deleting asks first and moves the screenshot to `screenshots/.trash`, where it stays for 7 days.

All keys can be changed or unbound in Controls → Key Binds → Screenshot Toolkit.

## Files

The mod keeps your screenshots folder clean and never changes your images:

```
screenshots/
  2026-10-19_19.30.22.png
  .meta/2026-10-19_19.30.22.png.json   world, coordinates, dimension, time, starred
  .thumbs/2026-10-19_19.30.22.png       small copy for the gallery
  .trash/                               deleted screenshots, removed after 7 days
```

## Clipboard on each platform

Java's own image clipboard does not work next to Minecraft's window, so the mod uses each system's own tool:

| Platform | Tool | Notes |
| --- | --- | --- |
| Windows | PowerShell | Built in |
| macOS | AppleScript (`osascript`) | Built in |
| Linux (Wayland) | `wl-copy` | Install `wl-clipboard` |
| Linux (X11) | `xclip` | Install `xclip` |

If no tool is found, the toast says what to install.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.5 or newer for Minecraft 26.3.
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) for 26.3.
3. Put the mod jar in your `mods` folder.
4. (Optional) Install [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen. The gallery also
   has a Settings button.

## Configuration

Use the settings screen, or edit `.minecraft/config/screenshot-toolkit.json`:

```json
{
  "copyToClipboard": true,
  "showToast": true,
  "keepChatMessage": false,
  "saveMetadata": true,
  "trashDays": 7
}
```

## Building

```
./gradlew build                # mod jar and unit tests
./gradlew runClientGameTest    # client game tests (opens a game window)
```
