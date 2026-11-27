package dev.lightmap;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lightmap.core.MapWorker;
import dev.lightmap.mc.MapSession;
import dev.lightmap.mc.MinimapHud;
import dev.lightmap.mc.WorldMapScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point: wires the game's events to the map session of the current level.
 *
 * <p>All of these run on the client thread.
 */
public final class LightMapClient implements ClientModInitializer {
    public static final String ID = "lightmap";
    private static final Logger LOG = LoggerFactory.getLogger(ID);
    private static final int STATS_EVERY_TICKS = 100;

    private static MapWorker worker;
    private static MapSession session;
    private static KeyMapping openMap;
    private static int ticks;

    @Override
    public void onInitializeClient() {
        Settings.load(FabricLoader.getInstance().getConfigDir().resolve("lightmap.properties"));
        worker = new MapWorker("LightMap map worker");

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "map"));
        openMap = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.lightmap.world_map", InputConstants.KEY_M, category));

        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            // Chunks can arrive before the first tick in a new level, so the session follows the
            // level of the event rather than waiting for a tick to notice the change.
            MapSession current = chunk == null ? null : sessionFor(Minecraft.getInstance(), level);
            if (current != null) {
                current.chunkLoaded(chunk);
            }
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            MapSession current = session;
            if (current != null && current.level == level) {
                current.chunkUnloaded(chunk);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(LightMapClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            closeSession();
            if (!worker.await(10_000)) {
                LOG.warn("[lightmap] the map was still being saved when the game closed");
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(ID, "minimap"), new MinimapHud());
    }

    /** The session of the level the client is in, or null. */
    public static MapSession session() {
        return session;
    }

    public static KeyMapping openMapKey() {
        return openMap;
    }

    /** Called from the block-change hook. */
    public static void blockChanged(ClientLevel level, BlockPos pos) {
        MapSession current = session;
        if (current != null && current.level == level) {
            current.blockChanged(pos);
        }
    }

    private static void tick(Minecraft client) {
        MapSession current = sessionFor(client, client.level);
        if (current == null) {
            return;
        }
        current.tick();
        while (openMap.consumeClick()) {
            if (client.gui.screen() == null) {
                client.gui.setScreen(new WorldMapScreen());
            }
        }
        if (Config.STATS && ++ticks % STATS_EVERY_TICKS == 0) {
            LOG.info("[lightmap] {}", current.statsSummary());
        }
    }

    /**
     * Makes the session match the given level, closing the previous one if the level changed.
     *
     * <p>Called from the first chunk or tick in a level, not from the level-change event: when a
     * server is joined the level is set before the player exists, and until then the game cannot
     * say which server this is, which decides where the map is stored.
     */
    private static MapSession sessionFor(Minecraft client, ClientLevel level) {
        MapSession current = session;
        if (current != null && current.level == level) {
            return current;
        }
        closeSession();
        if (level != null) {
            session = MapSession.open(client, level, worker);
        }
        return session;
    }

    private static void closeSession() {
        MapSession current = session;
        if (current != null) {
            session = null;
            current.close();
            // The upkeep hook holds on to the session, and through it the level it mapped.
            worker.setIdle(null);
        }
    }
}
