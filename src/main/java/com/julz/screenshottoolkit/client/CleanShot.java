package com.julz.screenshottoolkit.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * The clean-shot key: hides the HUD and hand, lets one frame render without them, takes the
 * screenshot, then shows the HUD again. Runs per frame rather than per tick, so it waits exactly
 * one frame at any frame rate.
 */
public final class CleanShot {
    private enum State { IDLE, HIDDEN }

    private static State state = State.IDLE;
    private static boolean hidHud;

    private CleanShot() {
    }

    public static void request(Minecraft client) {
        if (state != State.IDLE) {
            return;
        }
        // The HUD may already be hidden with F1; then it stays hidden afterwards
        hidHud = !client.gui.hud.isHidden();
        if (hidHud) {
            client.gui.hud.toggle();
        }
        state = State.HIDDEN;
    }

    public static boolean isActive() {
        return state != State.IDLE;
    }

    /** Called at the start of every frame, after the previous frame finished. */
    public static void onFrame(Minecraft client) {
        if (state != State.HIDDEN) {
            return;
        }
        // request() runs during a tick, before that frame renders, so by the next frame the
        // screen holds one image without the HUD
        state = State.IDLE;
        Screenshot.grab(client, false);
        if (hidHud && client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        hidHud = false;
    }
}
