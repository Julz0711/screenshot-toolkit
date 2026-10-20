package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

public class ScreenshotToolkitConfigScreen extends OptionsSubScreen {
    private static final String PREFIX = "screenshot-toolkit.config.";

    private final OptionInstance<Boolean> copyToClipboard;
    private final OptionInstance<Boolean> showToast;
    private final OptionInstance<Boolean> keepChatMessage;
    private final OptionInstance<Boolean> saveMetadata;
    private final OptionInstance<Integer> trashDays;
    private final OptionInstance<Boolean> confirmDelete;

    public ScreenshotToolkitConfigScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable(PREFIX + "title"));
        ModConfig config = ModConfig.getInstance();
        this.copyToClipboard = booleanOption("copyToClipboard", config.copyToClipboard);
        this.showToast = booleanOption("showToast", config.showToast);
        this.keepChatMessage = booleanOption("keepChatMessage", config.keepChatMessage);
        this.saveMetadata = booleanOption("saveMetadata", config.saveMetadata);
        this.confirmDelete = booleanOption("confirmDelete", config.confirmDelete);
        this.trashDays = new OptionInstance<>(
                PREFIX + "trashDays",
                OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + "trashDays.tooltip")),
                (caption, v) -> Options.genericValueLabel(caption, Component.translatable("screenshot-toolkit.unit.days", v)),
                new OptionInstance.IntRange(1, 90),
                Math.clamp(config.trashDays, 1, 90),
                v -> {
                });
    }

    @Override
    protected void addOptions() {
        this.list.addBig(this.copyToClipboard);
        this.list.addSmall(this.showToast, this.keepChatMessage);
        this.list.addBig(this.saveMetadata);
        this.list.addSmall(this.confirmDelete, this.trashDays);
    }

    @Override
    public void removed() {
        super.removed();
        ModConfig config = ModConfig.getInstance();
        config.copyToClipboard = this.copyToClipboard.get();
        config.showToast = this.showToast.get();
        config.keepChatMessage = this.keepChatMessage.get();
        config.saveMetadata = this.saveMetadata.get();
        config.trashDays = this.trashDays.get();
        config.confirmDelete = this.confirmDelete.get();
        config.sanitize();
        ModConfig.save();
    }

    private static OptionInstance<Boolean> booleanOption(String name, boolean value) {
        return OptionInstance.createBoolean(PREFIX + name,
                OptionInstance.cachedConstantTooltip(Component.translatable(PREFIX + name + ".tooltip")),
                value);
    }
}
