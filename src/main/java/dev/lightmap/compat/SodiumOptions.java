package dev.lightmap.compat;

import dev.lightmap.Settings;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/**
 * LightMap's page in the video settings screen, which Sodium replaces with its own. Sodium finds
 * this class through the {@code sodium:config_api_user} entry in fabric.mod.json and only then
 * loads it, so nothing here is touched when Sodium is not installed.
 *
 * <p>Changes apply to {@link Settings} when the screen's Apply button is pressed and show on the
 * next frame; none needs a restart. A setting fixed by a JVM system property is shown disabled.
 */
public final class SodiumOptions implements ConfigEntryPoint {
    private final StorageEventHandler save = Settings::save;

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("lightmap.options.minimap"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(id("minimap.show"))
                                        .setName(Component.translatable("lightmap.options.minimap.show"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.show.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setMinimap, Settings::minimap)
                                        .setDefaultValue(true)
                                        .setEnabled(!Settings.pinned(Settings.MINIMAP)))
                                .addOption(builder.createIntegerOption(id("minimap.opacity"))
                                        .setName(Component.translatable("lightmap.options.minimap.opacity"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.opacity.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setOpacity, Settings::opacity)
                                        .setRange(0, 100, Settings.OPACITY_STEP)
                                        .setValueFormatter(value -> Component.literal(value + "%"))
                                        .setDefaultValue(100)
                                        .setEnabled(!Settings.pinned(Settings.OPACITY)))
                                .addOption(builder.createIntegerOption(id("minimap.size"))
                                        .setName(Component.translatable("lightmap.options.minimap.size"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.size.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setSize, Settings::size)
                                        .setRange(Settings.SIZE_MIN, Settings.SIZE_MAX, Settings.SIZE_STEP)
                                        .setValueFormatter(value -> Component.literal(value + " px"))
                                        .setDefaultValue(368)
                                        .setEnabled(!Settings.pinned(Settings.SIZE)))
                                .addOption(builder.createBooleanOption(id("minimap.rotate"))
                                        .setName(Component.translatable("lightmap.options.minimap.rotate"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.rotate.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setRotate, Settings::rotate)
                                        .setDefaultValue(true)
                                        .setEnabled(!Settings.pinned(Settings.ROTATE)))
                                .addOption(builder.createEnumOption(id("minimap.marker_size"), Settings.MarkerSize.class)
                                        .setName(Component.translatable("lightmap.options.minimap.marker_size"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.marker_size.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setMarkerSize, Settings::markerSize)
                                        .setElementNameProvider(value -> Component.translatable(
                                                "lightmap.options.minimap.marker_size." + value.name().toLowerCase(Locale.ROOT)))
                                        .setDefaultValue(Settings.MarkerSize.MEDIUM)
                                        .setEnabled(!Settings.pinned(Settings.MARKER_SIZE)))
                                .addOption(builder.createIntegerOption(id("minimap.label_scale"))
                                        .setName(Component.translatable("lightmap.options.minimap.label_scale"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.label_scale.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setLabelScale, Settings::labelScale)
                                        .setRange(Settings.LABEL_SCALE_MIN, Settings.LABEL_SCALE_MAX, Settings.LABEL_SCALE_STEP)
                                        .setValueFormatter(value -> Component.literal(value + "%"))
                                        .setDefaultValue(100)
                                        .setEnabled(!Settings.pinned(Settings.LABEL_SCALE)))
                                .addOption(builder.createEnumOption(id("minimap.players"), Settings.Players.class)
                                        .setName(Component.translatable("lightmap.options.minimap.players"))
                                        .setTooltip(Component.translatable("lightmap.options.minimap.players.tooltip"))
                                        .setStorageHandler(save)
                                        .setBinding(Settings::setPlayers, Settings::players)
                                        .setElementNameProvider(value -> Component.translatable(
                                                "lightmap.options.minimap.players." + value.name().toLowerCase(Locale.ROOT)))
                                        .setDefaultValue(Settings.Players.OFF)
                                        .setEnabled(!Settings.pinned(Settings.PLAYERS)))));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("lightmap", path);
    }
}
