package dev.lightmap.mc;

import dev.lightmap.core.Markers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The dialog a right click on the world map opens: a name, whether the marker also shows on the
 * minimap, whether its name does too, and Done or Cancel. For an existing marker there is also Delete. Every way out goes back
 * to the world map as it was.
 */
final class MarkerScreen extends Screen {
    private final WorldMapScreen map;
    private final MapSession session;
    private final Markers.@Nullable Marker existing;
    private final int x;
    private final int z;

    // Kept here rather than read from the widgets, so a resize (which rebuilds them) loses nothing.
    private String name;
    private boolean onMinimap;
    private boolean nameOnMinimap;
    private Button done;

    MarkerScreen(WorldMapScreen map, MapSession session, Markers.@Nullable Marker existing, int x, int z) {
        super(Component.translatable(existing == null ? "lightmap.marker.new" : "lightmap.marker.edit"));
        this.map = map;
        this.session = session;
        this.existing = existing;
        this.x = x;
        this.z = z;
        this.name = existing == null ? "" : existing.name();
        this.onMinimap = existing == null || existing.onMinimap();
        this.nameOnMinimap = existing != null && existing.nameOnMinimap();
    }

    @Override
    protected void init() {
        int left = width / 2 - 100;
        int top = top();
        EditBox field = new EditBox(font, left, top, 200, 20, Component.translatable("lightmap.marker.name"));
        field.setMaxLength(Markers.MAX_NAME);
        field.setValue(name);
        field.setHint(Component.translatable("lightmap.marker.name"));
        field.setResponder(value -> {
            name = value;
            done.active = !value.isBlank();
        });
        addRenderableWidget(field);
        // The name can only show where the marker does, so the second box follows the first.
        Checkbox nameBox = Checkbox.builder(Component.translatable("lightmap.marker.minimap_name"), font)
                .pos(left, top + 48).selected(nameOnMinimap).onValueChange((box, value) -> nameOnMinimap = value).build();
        nameBox.active = onMinimap;
        addRenderableWidget(Checkbox.builder(Component.translatable("lightmap.marker.minimap"), font)
                .pos(left, top + 26).selected(onMinimap).onValueChange((box, value) -> {
                    onMinimap = value;
                    nameBox.active = value;
                }).build());
        addRenderableWidget(nameBox);
        done = addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> {
            commit();
            onClose();
        }).bounds(left, top + 74, 98, 20).build());
        done.active = !name.isBlank();
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> onClose()).bounds(left + 102, top + 74, 98, 20).build());
        if (existing != null) {
            addRenderableWidget(Button.builder(Component.translatable("lightmap.marker.delete"), button -> {
                remove();
                onClose();
            })
                    .bounds(left, top + 98, 200, 20).build());
        }
        setInitialFocus(field);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partial) {
        super.extractRenderState(graphics, mouseX, mouseY, partial);
        graphics.centeredText(font, title, width / 2, top() - font.lineHeight - 6, 0xFFFFFFFF);
    }

    private int top() {
        return height / 2 - 52;
    }

    /** Enter in the name field is as good as Done. */
    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isConfirmation() && done.active) {
            commit();
            onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Adds or changes the marker as the dialog stands; a blank name changes nothing. */
    void commit() {
        if (name.isBlank()) {
            return;
        }
        Markers.Marker marker = new Markers.Marker(x, z, name.strip(), onMinimap, onMinimap && nameOnMinimap);
        if (existing == null) {
            session.markers.add(marker);
        } else {
            session.markers.replace(existing, marker);
        }
        session.markersChanged();
    }

    void remove() {
        if (existing != null) {
            session.markers.replace(existing, null);
            session.markersChanged();
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(map);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
