package com.julz.screenshottoolkit.core;

import com.julz.screenshottoolkit.core.ImageClipboard.Command;
import com.julz.screenshottoolkit.core.ImageClipboard.Platform;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ImageClipboardTest {
    private static final Path IMAGE = Path.of("shot.png").toAbsolutePath();

    @Test
    void windowsPassesThePathThroughTheEnvironment() {
        Command command = ImageClipboard.command(Platform.WINDOWS, IMAGE, Map.of(), tool -> false);
        assertEquals("powershell.exe", command.args().getFirst());
        assertTrue(command.args().contains("-STA"));
        assertEquals(IMAGE.toString(), command.environment().get("SCREENSHOT_TOOLKIT_IMAGE"));
        assertFalse(String.join(" ", command.args()).contains(IMAGE.toString()));
    }

    @Test
    void macPassesThePathAsAnArgument() {
        Command command = ImageClipboard.command(Platform.MAC, IMAGE, Map.of(), tool -> false);
        assertEquals("osascript", command.args().getFirst());
        assertEquals(IMAGE.toString(), command.args().getLast());
    }

    @Test
    void waylandPrefersWlCopy() {
        Map<String, String> env = Map.of("WAYLAND_DISPLAY", "wayland-0");
        Command command = ImageClipboard.command(Platform.LINUX, IMAGE, env, Set.of("wl-copy", "xclip")::contains);
        assertEquals("wl-copy", command.args().getFirst());
        assertEquals(IMAGE, command.stdin());
    }

    @Test
    void x11UsesXclip() {
        Command command = ImageClipboard.command(Platform.LINUX, IMAGE, Map.of(), Set.of("wl-copy", "xclip")::contains);
        assertEquals("xclip", command.args().getFirst());
        assertTrue(command.args().contains("image/png"));
    }

    @Test
    void waylandFallsBackToXclip() {
        Map<String, String> env = Map.of("WAYLAND_DISPLAY", "wayland-0");
        Command command = ImageClipboard.command(Platform.LINUX, IMAGE, env, Set.of("xclip")::contains);
        assertEquals("xclip", command.args().getFirst());
    }

    @Test
    void linuxWithoutToolsNamesWhatToInstall() {
        assertNull(ImageClipboard.command(Platform.LINUX, IMAGE, Map.of(), tool -> false));
        assertEquals("xclip", ImageClipboard.missingTool(Platform.LINUX, Map.of()));
        assertEquals("wl-clipboard", ImageClipboard.missingTool(Platform.LINUX, Map.of("WAYLAND_DISPLAY", "w")));
    }
}
