package com.julz.screenshottoolkit.client;

import com.julz.screenshottoolkit.config.ModConfig;
import com.julz.screenshottoolkit.core.Shot;
import com.mojang.blaze3d.platform.InputConstants;
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

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A scrollable grid of thumbnails. Arrow keys move the cursor, Enter opens it, Ctrl+scroll changes the thumbnail size.
 * Thumbnails can also be checked for bulk actions: the box in the corner, Ctrl+click, Shift+click for a range, dragging
 * a box across them, Space, or Ctrl+A for all. While any are checked, a plain click checks instead of opening.
 * <p>
 * Clicks act on release, so a press that turns into a drag selects instead of opening.
 */
public class ShotGrid extends AbstractWidget {
    /** Thumbnail widths for each size step; heights are 16:9. */
    private static final int[] SIZES = {96, 128, 176, 232};
    public static final int DEFAULT_SIZE = 1;
    private static final int GAP = 8;
    // Room around the cells so the selection outline is not cut off at the edges
    private static final int PADDING = 2;
    private static final int LABEL_HEIGHT = 12;
    private static final int BOX_SIZE = 11;
    // How far the mouse moves before a press becomes a drag
    private static final int DRAG_THRESHOLD = 4;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final ShotTextures textures;
    private final Consumer<Shot> onOpen;
    private final Consumer<List<Shot>> onDelete;
    private final Runnable onCheckedChanged;
    private Function<Shot, String> labels = shot -> shot.customName() != null
            ? shot.customName() : DATE.format(Instant.ofEpochMilli(shot.takenAt()));
    private boolean quickDelete = true;
    private boolean clickChecks;

    private List<Shot> shots = List.of();
    private Component emptyMessage = Component.empty();
    private int selected = -1;
    private double scroll;
    private int thumbWidth;
    private int thumbHeight;
    private final Set<Path> checked = new LinkedHashSet<>();
    // Where a Shift+click range starts
    private int anchor = -1;

    // The press being handled, in content coordinates (y includes the scroll)
    private boolean pressing;
    private double pressX;
    private double pressY;
    private int pressIndex = -1;
    private boolean pressShift;
    private boolean pressControl;
    private boolean dragging;
    private double dragX;
    private double dragY;
    private Set<Path> checkedBeforeDrag = Set.of();

    private int lastMouseX = -1;
    private int lastMouseY = -1;

    public ShotGrid(int x, int y, int width, int height, ShotTextures textures, Consumer<Shot> onOpen,
                    Consumer<List<Shot>> onDelete, Runnable onCheckedChanged) {
        super(x, y, width, height, Component.translatable("screenshot-toolkit.gallery.title"));
        this.textures = textures;
        this.onOpen = onOpen;
        this.onDelete = onDelete;
        this.onCheckedChanged = onCheckedChanged;
        applySize(ScreenshotToolkitClient.config().thumbnailSize);
    }

    /** What each thumbnail says underneath; the date or the given name by default. */
    public ShotGrid labels(Function<Shot, String> labels) {
        this.labels = labels;
        return this;
    }

    /** Whether hovering shows the ✕ that deletes without asking. */
    public ShotGrid quickDelete(boolean quickDelete) {
        this.quickDelete = quickDelete;
        return this;
    }

    /** Whether a plain click checks a thumbnail even when nothing is checked yet, instead of opening it. */
    public ShotGrid clickChecks(boolean clickChecks) {
        this.clickChecks = clickChecks;
        return this;
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
        // Hidden screenshots are not checked, so a bulk action never touches what the player can't see
        if (checked.retainAll(shots.stream().map(Shot::file).toList())) {
            onCheckedChanged.run();
        }
    }

    /** What the grid shows, in order. */
    public List<Shot> shots() {
        return shots;
    }

    public Shot selectedShot() {
        return selected >= 0 && selected < shots.size() ? shots.get(selected) : null;
    }

    public void select(Shot shot) {
        selected = shots.indexOf(shot);
        scrollToSelected();
    }

    // ---- Checking ----

    /** The checked screenshots, in grid order. */
    public List<Shot> checkedShots() {
        return shots.stream().filter(shot -> checked.contains(shot.file())).toList();
    }

    public boolean isChecking() {
        return !checked.isEmpty();
    }

    public void checkAll() {
        shots.forEach(shot -> checked.add(shot.file()));
        onCheckedChanged.run();
    }

    /** Checks exactly these screenshots, for example again after a dialog rebuilt the screen. */
    public void setChecked(java.util.Collection<Path> files) {
        checked.clear();
        shots.stream().map(Shot::file).filter(files::contains).forEach(checked::add);
        onCheckedChanged.run();
    }

    public void clearChecked() {
        checked.clear();
        anchor = -1;
        onCheckedChanged.run();
    }

    private void toggleChecked(int index) {
        Path file = shots.get(index).file();
        if (!checked.remove(file)) {
            checked.add(file);
        }
        anchor = index;
        onCheckedChanged.run();
    }

    private void checkRange(int index) {
        int from = anchor >= 0 && anchor < shots.size() ? anchor : index;
        for (int i = Math.min(from, index); i <= Math.max(from, index); i++) {
            checked.add(shots.get(i).file());
        }
        onCheckedChanged.run();
    }

    // ---- Size and layout ----

    public int thumbWidth() {
        return thumbWidth;
    }

    public int thumbHeight() {
        return thumbHeight;
    }

    public int sizeStep() {
        return ScreenshotToolkitClient.config().thumbnailSize;
    }

    /** Makes thumbnails bigger or smaller by whole steps, keeping the first visible row in view. */
    public void changeSize(int steps) {
        int current = sizeStep();
        int next = Mth.clamp(current + steps, 0, SIZES.length - 1);
        if (next == current) {
            return;
        }
        int firstVisible = (int) (scroll / cellHeight()) * columns();
        applySize(next);
        ModConfig config = ScreenshotToolkitClient.config();
        config.thumbnailSize = next;
        ModConfig.save();
        setScroll(firstVisible / columns() * cellHeight());
    }

    private void applySize(int step) {
        thumbWidth = SIZES[Mth.clamp(step, 0, SIZES.length - 1)];
        thumbHeight = thumbWidth * 9 / 16;
    }

    private int cellWidth() {
        return thumbWidth + GAP;
    }

    private int cellHeight() {
        return thumbHeight + LABEL_HEIGHT + GAP;
    }

    public int columns() {
        return Math.max(1, (getWidth() - 2 * PADDING + GAP) / cellWidth());
    }

    private int rows() {
        return (shots.size() + columns() - 1) / columns();
    }

    private int maxScroll() {
        return Math.max(0, rows() * cellHeight() - GAP + 2 * PADDING - getHeight());
    }

    private void setScroll(double value) {
        scroll = Mth.clamp(value, 0, maxScroll());
    }

    /** Left edge of the first column, so the grid is centered. */
    private int left() {
        int used = columns() * cellWidth() - GAP;
        return getX() + PADDING + (getWidth() - 2 * PADDING - used) / 2;
    }

    private int indexAt(double mouseX, double mouseY) {
        if (!isMouseOver(mouseX, mouseY)) {
            return -1;
        }
        int localX = (int) mouseX - left();
        int localY = (int) (mouseY - getY() - PADDING + scroll);
        if (localX < 0 || localY < 0 || localX % cellWidth() >= thumbWidth || localY % cellHeight() >= thumbHeight + LABEL_HEIGHT) {
            return -1;
        }
        int column = localX / cellWidth();
        if (column >= columns()) {
            return -1;
        }
        int index = localY / cellHeight() * columns() + column;
        return index < shots.size() ? index : -1;
    }

    /** Where cell {@code index} starts on screen, for tests and the corner buttons. */
    public int[] cellPosition(int index) {
        int column = index % columns();
        int row = index / columns();
        return new int[]{left() + column * cellWidth(), getY() + PADDING + row * cellHeight() - (int) scroll};
    }

    // ---- Drawing ----

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        textures.upload();
        lastMouseX = mouseX;
        lastMouseY = mouseY;
        Font font = Minecraft.getInstance().font;
        if (shots.isEmpty()) {
            graphics.centeredText(font, emptyMessage, getX() + getWidth() / 2, getY() + getHeight() / 2 - 4, 0xFFA0A0A0);
            return;
        }
        int hovered = dragging ? -1 : indexAt(mouseX, mouseY);
        graphics.enableScissor(getX(), getY(), getRight(), getBottom());
        int columns = columns();
        int firstRow = (int) (scroll / cellHeight());
        int lastRow = (int) ((scroll + getHeight()) / cellHeight());
        for (int row = firstRow; row <= lastRow; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                if (index >= shots.size()) {
                    break;
                }
                int x = left() + column * cellWidth();
                int y = getY() + PADDING + row * cellHeight() - (int) scroll;
                drawCell(graphics, font, shots.get(index), x, y, index == hovered, index == selected && isFocused(),
                        checked.contains(shots.get(index).file()), mouseX, mouseY);
            }
        }
        if (dragging) {
            int x1 = (int) Math.min(pressX, dragX);
            int x2 = (int) Math.max(pressX, dragX);
            int y1 = (int) (Math.min(pressY, dragY) - scroll);
            int y2 = (int) (Math.max(pressY, dragY) - scroll);
            graphics.fill(x1, y1, x2, y2, 0x303070F0);
            graphics.outline(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1), 0xFF3070F0);
        }
        graphics.disableScissor();
        if (maxScroll() > 0) {
            int barHeight = Math.max(16, getHeight() * getHeight() / (maxScroll() + getHeight()));
            int barY = getY() + (int) ((getHeight() - barHeight) * scroll / maxScroll());
            graphics.fill(getRight() - 3, barY, getRight() - 1, barY + barHeight, 0xFFA0A0A0);
        }
    }

    private void drawCell(GuiGraphicsExtractor graphics, Font font, Shot shot, int x, int y, boolean hovered, boolean selected,
                          boolean isChecked, int mouseX, int mouseY) {
        graphics.fill(x, y, x + thumbWidth, y + thumbHeight, 0xFF101010);
        ShotTextures.Texture texture = textures.get(shot.file());
        if (texture != null) {
            drawFitted(graphics, texture, x, y, thumbWidth, thumbHeight);
        } else {
            Component status = Component.translatable(textures.failed(shot.file())
                    ? "screenshot-toolkit.gallery.unreadable" : "screenshot-toolkit.gallery.loading");
            graphics.centeredText(font, status, x + thumbWidth / 2, y + thumbHeight / 2 - 4, 0xFF808080);
        }
        if (shot.favorite()) {
            graphics.text(font, "★", x + thumbWidth - 10, y + thumbHeight - 11, 0xFFFFD700, true);
        }
        if (isChecked) {
            graphics.fill(x, y, x + thumbWidth, y + thumbHeight, 0x403070F0);
        }
        if (hovered || isChecking()) {
            // Check box in the other corner; always shown while checking so it is clear what a click does
            int boxX = x + thumbWidth - 2 - BOX_SIZE;
            boolean overBox = inCheckBox(x, y, mouseX, mouseY);
            graphics.fill(boxX, y + 2, boxX + BOX_SIZE, y + 2 + BOX_SIZE, isChecked ? 0xFF3070F0 : overBox ? 0xC0404040 : 0xB0000000);
            graphics.outline(boxX, y + 2, BOX_SIZE, BOX_SIZE, isChecked || overBox ? 0xFFFFFFFF : 0xFFA0A0A0);
            if (isChecked) {
                graphics.centeredText(font, Component.literal("✔"), boxX + BOX_SIZE / 2 + 1, y + 4, 0xFFFFFFFF);
            }
        }
        if (hovered && quickDelete && !isChecking()) {
            // Quick delete in the corner; it goes to the trash and the gallery offers Undo
            boolean overDelete = inDeleteBox(x, y, mouseX, mouseY);
            graphics.fill(x + 2, y + 2, x + 2 + BOX_SIZE, y + 2 + BOX_SIZE, overDelete ? 0xE0C03030 : 0xB0000000);
            graphics.centeredText(font, Component.literal("✕"), x + 2 + BOX_SIZE / 2 + 1, y + 4, 0xFFFFFFFF);
        }
        String label = labels.apply(shot);
        if (font.width(Component.literal(label)) > thumbWidth - 2) {
            while (label.length() > 1 && font.width(Component.literal(label + "...")) > thumbWidth - 2) {
                label = label.substring(0, label.length() - 1);
            }
            label += "...";
        }
        graphics.text(font, label, x + 1, y + thumbHeight + 3, hovered || selected ? 0xFFFFFFFF : 0xFFA0A0A0, false);
        if (selected) {
            graphics.outline(x - 2, y - 2, thumbWidth + 4, thumbHeight + 4, 0xFFFFFF00);
        } else if (isChecked) {
            graphics.outline(x - 1, y - 1, thumbWidth + 2, thumbHeight + 2, 0xFF3070F0);
        } else if (hovered) {
            graphics.outline(x - 1, y - 1, thumbWidth + 2, thumbHeight + 2, 0xFFFFFFFF);
        }
    }

    private static boolean inDeleteBox(int cellX, int cellY, double mouseX, double mouseY) {
        return mouseX >= cellX + 2 && mouseX < cellX + 2 + BOX_SIZE && mouseY >= cellY + 2 && mouseY < cellY + 2 + BOX_SIZE;
    }

    private boolean inCheckBox(int cellX, int cellY, double mouseX, double mouseY) {
        int boxX = cellX + thumbWidth - 2 - BOX_SIZE;
        return mouseX >= boxX && mouseX < boxX + BOX_SIZE && mouseY >= cellY + 2 && mouseY < cellY + 2 + BOX_SIZE;
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

    /** The cursor as the grid last saw it, for tests. */
    public int[] lastMouse() {
        return new int[]{lastMouseX, lastMouseY};
    }

    // ---- Mouse ----

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        pressing = true;
        dragging = false;
        pressX = event.x();
        pressY = event.y() + scroll;
        pressIndex = indexAt(event.x(), event.y());
        pressShift = event.hasShiftDown();
        pressControl = event.hasControlDown();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        dragTo(event.x(), event.y());
        return pressing || super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && pressing) {
            release(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    /** Whether a press on the grid is still held; screens pass mouse moves on while it is. */
    public boolean isPressing() {
        return pressing;
    }

    /** Follows the mouse while the button is held: past a few pixels the press becomes a selection box. */
    public void dragTo(double mouseX, double mouseY) {
        if (!pressing) {
            return;
        }
        // Scrolls when the box is pulled past the top or bottom edge
        if (mouseY < getY()) {
            setScroll(scroll - (getY() - mouseY));
        } else if (mouseY > getBottom()) {
            setScroll(scroll + (mouseY - getBottom()));
        }
        dragX = Mth.clamp(mouseX, getX(), getRight());
        dragY = Mth.clamp(mouseY, getY(), getBottom()) + scroll;
        if (!dragging && Math.hypot(dragX - pressX, dragY - pressY) < DRAG_THRESHOLD) {
            return;
        }
        if (!dragging) {
            dragging = true;
            // Ctrl or Shift adds to what is checked, a plain drag starts over
            checkedBeforeDrag = pressControl || pressShift ? Set.copyOf(checked) : Set.of();
        }
        checked.clear();
        checked.addAll(checkedBeforeDrag);
        double top = Math.min(pressY, dragY) - getY() - PADDING;
        double bottom = Math.max(pressY, dragY) - getY() - PADDING;
        double leftEdge = Math.min(pressX, dragX) - left();
        double rightEdge = Math.max(pressX, dragX) - left();
        for (int i = 0; i < shots.size(); i++) {
            int cellX = i % columns() * cellWidth();
            int cellY = i / columns() * cellHeight();
            if (cellX < rightEdge && cellX + thumbWidth > leftEdge && cellY < bottom && cellY + thumbHeight > top) {
                checked.add(shots.get(i).file());
            }
        }
        onCheckedChanged.run();
    }

    /** Ends a press: a drag keeps what its box checked, anything else is a click on the thumbnail it started on. */
    public void release(double mouseX, double mouseY) {
        if (!pressing) {
            return;
        }
        pressing = false;
        if (dragging) {
            dragging = false;
            return;
        }
        int index = pressIndex;
        if (index < 0 || index >= shots.size() || index != indexAt(mouseX, mouseY)) {
            return;
        }
        int[] cell = cellPosition(index);
        if (pressShift) {
            selected = index;
            checkRange(index);
        } else if (inCheckBox(cell[0], cell[1], mouseX, mouseY) || pressControl || isChecking() || clickChecks) {
            selected = index;
            toggleChecked(index);
        } else if (quickDelete && inDeleteBox(cell[0], cell[1], mouseX, mouseY)) {
            onDelete.accept(List.of(shots.get(index)));
        } else {
            selected = index;
            onOpen.accept(shots.get(index));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (Minecraft.getInstance().hasControlDown()) {
            changeSize(scrollY > 0 ? 1 : -1);
            return true;
        }
        setScroll(scroll - scrollY * cellHeight() / 2.0);
        return true;
    }

    // ---- Keyboard ----

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
        } else if (event.key() == InputConstants.KEY_HOME) {
            move = -shots.size();
        } else if (event.key() == InputConstants.KEY_END) {
            move = shots.size();
        } else if (event.key() == InputConstants.KEY_PAGEUP) {
            move = -columns() * Math.max(1, getHeight() / cellHeight());
        } else if (event.key() == InputConstants.KEY_PAGEDOWN) {
            move = columns() * Math.max(1, getHeight() / cellHeight());
        }
        if (move != 0) {
            selected = selected < 0 ? 0 : Mth.clamp(selected + move, 0, shots.size() - 1);
            if (event.hasShiftDown()) {
                checkRange(selected);
            } else {
                anchor = selected;
            }
            scrollToSelected();
            return true;
        }
        if (event.isSelectAll()) {
            checkAll();
            return true;
        }
        if (event.key() == InputConstants.KEY_SPACE && selectedShot() != null) {
            toggleChecked(selected);
            return true;
        }
        if (event.key() == InputConstants.KEY_DELETE && (isChecking() || selectedShot() != null)) {
            onDelete.accept(isChecking() ? checkedShots() : List.of(selectedShot()));
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
        int top = selected / columns() * cellHeight();
        if (top < scroll) {
            setScroll(top);
        } else if (top + cellHeight() - GAP + 2 * PADDING > scroll + getHeight()) {
            setScroll(top + cellHeight() - GAP + 2 * PADDING - getHeight());
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
