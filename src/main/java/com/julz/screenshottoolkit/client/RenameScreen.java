package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.ScreenshotLibrary;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Asks for a new name for one screenshot. The extension stays as it is. */
public class RenameScreen extends Screen {
    private static final String PREFIX = "screenshot-toolkit.rename.";

    private final Screen parent;
    private final ScreenshotLibrary library;
    private final Path file;
    private final Consumer<Path> onRenamed;
    private EditBox name;
    private Button done;
    private Component error = Component.empty();

    public RenameScreen(Screen parent, ScreenshotLibrary library, Path file, Consumer<Path> onRenamed) {
        super(Component.translatable(PREFIX + "title"));
        this.parent = parent;
        this.library = library;
        this.file = file;
        this.onRenamed = onRenamed;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(300, width - 40);
        int left = (width - boxWidth) / 2;
        int top = height / 2 - 30;
        String current = name != null ? name.getValue() : baseName(file);
        name = addRenderableWidget(new EditBox(font, left, top, boxWidth, 20, Component.translatable(PREFIX + "name")));
        name.setMaxLength(ScreenshotLibrary.MAX_NAME_LENGTH);
        name.setValue(current);
        name.setResponder(text -> {
            error = Component.empty();
            done.active = !ScreenshotLibrary.cleanName(text).isEmpty();
        });

        int buttonWidth = (boxWidth - 8) / 2;
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> rename())
                .bounds(left, top + 44, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + buttonWidth + 8, top + 44, buttonWidth, 20).build());
        done.active = !ScreenshotLibrary.cleanName(current).isEmpty();
        setInitialFocus(name);
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void rename() {
        try {
            Path renamed = library.rename(file, name.getValue());
            onRenamed.accept(renamed);
            minecraft.gui.setScreen(parent);
        } catch (FileAlreadyExistsException e) {
            error = Component.translatable(PREFIX + "taken");
        } catch (IllegalArgumentException e) {
            error = Component.translatable(PREFIX + "empty");
        } catch (IOException e) {
            ScreenshotToolkitClient.LOGGER.warn("Could not rename {}", file, e);
            error = Component.translatable(PREFIX + "failed");
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && done.active) {
            rename();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, name.getY() - 24, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable(PREFIX + "hint"), width / 2, name.getY() - 11, 0xFFA0A0A0);
        graphics.centeredText(font, error, width / 2, name.getY() + 28, 0xFFFF5555);
    }

    /** For tests. */
    public EditBox nameBox() {
        return name;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
