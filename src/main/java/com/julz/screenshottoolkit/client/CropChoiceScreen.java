package com.julz.screenshottoolkit.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Asks whether a crop becomes a new image or replaces the original. Escape goes back to cropping. */
public class CropChoiceScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.cropChoice.";

    private final Screen parent;
    private final int trashDays;
    private final Runnable saveAsNew;
    private final Runnable replace;
    private Button newButton;
    private Button replaceButton;

    public CropChoiceScreen(Screen parent, int trashDays, Runnable saveAsNew, Runnable replace) {
        super(Component.translatable(PREFIX + "title"));
        this.parent = parent;
        this.trashDays = trashDays;
        this.saveAsNew = saveAsNew;
        this.replace = replace;
    }

    @Override
    protected void init() {
        int rowWidth = Math.min(3 * 110 + 2 * 6, width - 24);
        int button = (rowWidth - 2 * 6) / 3;
        int left = (width - rowWidth) / 2;
        int y = height / 2 + 6;
        newButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "new"), b -> choose(saveAsNew))
                .bounds(left, y, button, 20).build());
        replaceButton = addRenderableWidget(Button.builder(Component.translatable(PREFIX + "replace"), b -> choose(replace))
                .bounds(left + button + 6, y, button, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + 2 * (button + 6), y, rowWidth - 2 * (button + 6), 20).build());
        setInitialFocus(newButton);
    }

    private void choose(Runnable action) {
        minecraft.gui.setScreen(parent);
        action.run();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, height / 2 - 44, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(PREFIX + "message"), width / 2, height / 2 - 26, 0xFFC0C0C0);
        graphics.centeredText(font, Component.translatable(PREFIX + "trash", trashDays), width / 2, height / 2 - 14, 0xFF808080);
    }

    /** For tests. */
    public Button newButton() {
        return newButton;
    }

    public Button replaceButton() {
        return replaceButton;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
