package com.julz.screenshottoolkit.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** A toast with a thumbnail of the new screenshot, shown instead of the chat line. */
public class ScreenshotToast implements Toast {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("toast/system");
    private static final long DISPLAY_TIME = 5000;
    private static final int THUMB_HEIGHT = 24;

    private final ShotTextures.Texture thumbnail;
    private Component title;
    private Component message;
    private long lastChanged;
    private boolean changed;
    private Visibility wantedVisibility = Visibility.SHOW;

    public ScreenshotToast(ShotTextures.Texture thumbnail, Component title, Component message) {
        this.thumbnail = thumbnail;
        this.title = title;
        this.message = message;
    }

    /** Changes the text, for example once the clipboard copy finished, and shows the toast a little longer. */
    public void update(Component title, Component message) {
        this.title = title;
        this.message = message;
        this.changed = true;
    }

    @Override
    public Visibility getWantedVisibility() {
        return wantedVisibility;
    }

    @Override
    public void update(ToastManager manager, long visibleTime) {
        if (changed) {
            lastChanged = visibleTime;
            changed = false;
        }
        double displayTime = DISPLAY_TIME * manager.getNotificationDisplayTimeMultiplier();
        wantedVisibility = visibleTime - lastChanged < displayTime ? Visibility.SHOW : Visibility.HIDE;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, Font font, long visibleTime) {
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, BACKGROUND, 0, 0, width(), height());
        int textX = 8;
        if (thumbnail != null) {
            int thumbWidth = thumbWidth();
            graphics.blit(RenderPipelines.GUI_TEXTURED, thumbnail.id(), 4, 4, 0, 0, thumbWidth, THUMB_HEIGHT,
                    thumbnail.width(), thumbnail.height(), thumbnail.width(), thumbnail.height());
            textX = 4 + thumbWidth + 6;
        }
        graphics.text(font, title, textX, 7, 0xFFFFFF00, false);
        graphics.text(font, message, textX, 18, 0xFFFFFFFF, false);
    }

    @Override
    public int width() {
        Font font = Minecraft.getInstance().font;
        int textWidth = Math.max(font.width(title), font.width(message));
        int textX = thumbnail != null ? 4 + thumbWidth() + 6 : 8;
        return Math.max(DEFAULT_WIDTH, textX + textWidth + 8);
    }

    @Override
    public void onFinishedRendering() {
        if (thumbnail != null) {
            Minecraft.getInstance().getTextureManager().release(thumbnail.id());
        }
    }

    private int thumbWidth() {
        return Math.max(1, Math.round(THUMB_HEIGHT * (float) thumbnail.width() / thumbnail.height()));
    }
}
