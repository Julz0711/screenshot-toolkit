package com.julz.screenshottoolkit.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Function;

/**
 * Asks for a name, for example of an album. {@code apply} gets what was typed and returns an
 * error to show, or null when the name was taken and the screen can go back.
 */
public class NameScreen extends Screen {
    private final Screen parent;
    private final Component hint;
    private final String initial;
    private final int maxLength;
    private final Function<String, Component> apply;
    private EditBox name;
    private Button done;
    private Component error = Component.empty();

    public NameScreen(Screen parent, Component title, Component hint, String initial, int maxLength, Function<String, Component> apply) {
        super(title);
        this.parent = parent;
        this.hint = hint;
        this.initial = initial;
        this.maxLength = maxLength;
        this.apply = apply;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(300, width - 40);
        int left = (width - boxWidth) / 2;
        int top = height / 2 - 30;
        String current = name != null ? name.getValue() : initial;
        name = addRenderableWidget(new EditBox(font, left, top, boxWidth, 20, title));
        name.setMaxLength(maxLength);
        name.setValue(current);
        name.setResponder(text -> {
            error = Component.empty();
            done.active = !text.isBlank();
        });
        int buttonWidth = (boxWidth - 8) / 2;
        done = addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> submit())
                .bounds(left, top + 44, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(left + buttonWidth + 8, top + 44, buttonWidth, 20).build());
        done.active = !current.isBlank();
        setInitialFocus(name);
    }

    private void submit() {
        Component problem = apply.apply(name.getValue());
        if (problem == null) {
            minecraft.gui.setScreen(parent);
        } else {
            error = problem;
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && done.active) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, name.getY() - 24, 0xFFFFFFFF);
        graphics.centeredText(font, hint, width / 2, name.getY() - 11, 0xFFA0A0A0);
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
