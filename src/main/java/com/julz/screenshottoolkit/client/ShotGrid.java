package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.core.Shot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** A scrollable grid of thumbnails. Arrow keys move the selection, Enter opens it. */
public class ShotGrid extends AbstractWidget {
    public static final int THUMB_WIDTH = 128;
    public static final int THUMB_HEIGHT = 72;
    private static final int GAP = 8;
    // Room around the cells so the selection outline is not cut off at the edges
    private static final int PADDING = 2;
    private static final int LABEL_HEIGHT = 12;
    private static final int CELL_WIDTH = THUMB_WIDTH + GAP;
    private static final int CELL_HEIGHT = THUMB_HEIGHT + LABEL_HEIGHT + GAP;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ShotTextures textures;
    private final Consumer<Shot> onOpen;
    private List<Shot> shots = List.of();
    private Component emptyMessage = Component.empty();
    private int selected = -1;
    private double scroll;

    public ShotGrid(int x, int y, int width, int height, ShotTextures textures, Consumer<Shot> onOpen) {
        super(x, y, width, height, Component.translatable("screenshot-toolkit.gallery.title"));
        this.textures = textures;
        this.onOpen = onOpen;
    }

    public void setShots(List<Shot> shots, Component emptyMessage) {
        Shot previous = selectedShot();
        int previousIndex = selected;
        this.shots = shots;
        this.emptyMessage = emptyMessage;
        this.selected = previous != null ? shots.indexOf(previous) : -1;
        if (selected < 0 && previous != null) {
            // The selected screenshot is gone; keep the spot so the next one is selected
            selected = Math.min(shots.size() - 1, previousIndex);
        }
        setScroll(scroll);
    }

    public Shot selectedShot() {
        return selected >= 0 && selected < shots.size() ? shots.get(selected) : null;
    }

    public void select(Shot shot) {
        selected = shots.indexOf(shot);
        scrollToSelected();
    }

    public int columns() {
        return Math.max(1, (getWidth() - 2 * PADDING + GAP) / CELL_WIDTH);
    }

    private int rows() {
        return (shots.size() + columns() - 1) / columns();
    }

    private int maxScroll() {
        return Math.max(0, rows() * CELL_HEIGHT - GAP + 2 * PADDING - getHeight());
    }

    private void setScroll(double value) {
        scroll = Mth.clamp(value, 0, maxScroll());
    }

    /** Left edge of the first column, so the grid is centered. */
    private int left() {
        int used = columns() * CELL_WIDTH - GAP;
        return getX() + PADDING + (getWidth() - 2 * PADDING - used) / 2;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int localX = (int) mouseX - left();
        int localY = (int) (mouseY - getY() - PADDING + scroll);
        if (localX < 0 || localY < 0 || localX % CELL_WIDTH >= THUMB_WIDTH || localY % CELL_HEIGHT >= THUMB_HEIGHT + LABEL_HEIGHT) {
            return -1;
        }
        int column = localX / CELL_WIDTH;
        if (column >= columns()) {
            return -1;
        }
        int index = localY / CELL_HEIGHT * columns() + column;
        return index < shots.size() ? index : -1;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        textures.upload();
        Font font = Minecraft.getInstance().font;
        if (shots.isEmpty()) {
            graphics.centeredText(font, emptyMessage, getX() + getWidth() / 2, getY() + getHeight() / 2 - 4, 0xFFA0A0A0);
            return;
        }
        int hovered = indexAt(mouseX, mouseY);
        graphics.enableScissor(getX(), getY(), getRight(), getBottom());
        int columns = columns();
        int firstRow = (int) (scroll / CELL_HEIGHT);
        int lastRow = (int) ((scroll + getHeight()) / CELL_HEIGHT);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= shots.size()) {
                    break;
                }
                int x = left() + column * CELL_WIDTH;
                int y = getY() + PADDING + row * CELL_HEIGHT - (int) scroll;
                drawCell(graphics, font, shots.get(index), x, y, index == hovered, index == selected && isFocused());
            }
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int barHeight = Math.max(16, getHeight() * getHeight() / (maxScroll() + getHeight()));
            int barY = getY() + (int) ((getHeight() - barHeight) * scroll / maxScroll());
            graphics.fill(getRight() - 3, barY, getRight() - 1, barY + barHeight, 0xFFA0A0A0);
        }
    }

    private void drawCell(GuiGraphicsExtractor graphics, Font font, Shot shot, int x, int y, boolean hovered, boolean selected) {
        graphics.fill(x, y, x + THUMB_WIDTH, y + THUMB_HEIGHT, 0xFF101010);
        ShotTextures.Texture texture = textures.get(shot.file());
        if (texture != null) {
            drawFitted(graphics, texture, x, y, THUMB_WIDTH, THUMB_HEIGHT);
        } else {
            Component status = Component.translatable(textures.failed(shot.file())
                    ? "screenshot-toolkit.gallery.unreadable" : "screenshot-toolkit.gallery.loading");
            graphics.centeredText(font, status, x + THUMB_WIDTH / 2, y + THUMB_HEIGHT / 2 - 4, 0xFF808080);
        }
        if (shot.favorite()) {
            graphics.text(font, "★", x + THUMB_WIDTH - 10, y + 3, 0xFFFFD700, true);
        }
        String label = DATE.format(Instant.ofEpochMilli(shot.takenAt()));
        graphics.text(font, label, x + 1, y + THUMB_HEIGHT + 3, hovered || selected ? 0xFFFFFFFF : 0xFFA0A0A0, false);
        if (selected) {
            graphics.outline(x - 2, y - 2, THUMB_WIDTH + 4, THUMB_HEIGHT + 4, 0xFFFFFF00);
        } else if (hovered) {
            graphics.outline(x - 1, y - 1, THUMB_WIDTH + 2, THUMB_HEIGHT + 2, 0xFFFFFFFF);
        }
    }

    /** Draws the texture as large as fits in the box, keeping its shape, centered. */
    static void drawFitted(GuiGraphicsExtractor graphics, ShotTextures.Texture texture, int x, int y, int width, int height) {
        float scale = Math.min(width / (float) texture.width(), height / (float) texture.height());
        int drawWidth = Math.max(1, Math.round(texture.width() * scale));
        int drawHeight = Math.max(1, Math.round(texture.height() * scale));
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(),
                x + (width - drawWidth) / 2, y + (height - drawHeight) / 2, 0, 0, drawWidth, drawHeight,
                texture.width(), texture.height(), texture.width(), texture.height());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        int index = indexAt(event.x(), event.y());
        if (index >= 0) {
            selected = index;
            onOpen.accept(shots.get(index));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        setScroll(scroll - scrollY * CELL_HEIGHT / 2.0);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (shots.isEmpty()) {
            return false;
        }
        int move = 0;
        if (event.isLeft()) {
            move = -1;
        } else if (event.isRight()) {
            move = 1;
        } else if (event.isUp()) {
            move = -columns();
        } else if (event.isDown()) {
            move = columns();
        }
        if (move != 0) {
            selected = selected < 0 ? 0 : Mth.clamp(selected + move, 0, shots.size() - 1);
            scrollToSelected();
            return true;
        }
        if (event.isConfirmation() && selectedShot() != null) {
            onOpen.accept(selectedShot());
            return true;
        }
        return false;
    }

    private void scrollToSelected() {
        if (selected < 0) {
            return;
        }
        int top = selected / columns() * CELL_HEIGHT;
        if (top < scroll) {
            setScroll(top);
        } else if (top + CELL_HEIGHT - GAP + 2 * PADDING > scroll + getHeight()) {
            setScroll(top + CELL_HEIGHT - GAP + 2 * PADDING - getHeight());
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        Shot shot = selectedShot();
        if (shot != null) {
            output.add(NarratedElementType.TITLE, Component.literal(shot.name()));
        }
    }
}
