package dev.lightmap.mc;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.lightmap.core.Palette;
import dev.lightmap.core.RegionCodec;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.IdMapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.Map;

/**
 * What the map knows about each block state, flattened into arrays indexed by the game's global
 * state id so the worker never has to ask a block anything.
 *
 * <p>Colours are the game's own map colours for now, the palette the vanilla map item uses.
 * Built once per session: state ids are stable while connected but may differ between sessions.
 */
public final class StateTables implements RegionCodec.StateNames {
    public final Palette palette;
    /** Id of air, used for a column in which nothing drawable was found. */
    public final int air;

    private final Map<String, Integer> idsByName = new HashMap<>();

    private StateTables(Palette palette, int air) {
        this.palette = palette;
        this.air = air;
    }

    public static StateTables build() {
        IdMapper<BlockState> registry = Block.BLOCK_STATE_REGISTRY;
        int size = 0;
        for (BlockState state : registry) {
            size = Math.max(size, registry.getId(state) + 1);
        }
        int[] rgb = new int[size];
        byte[] flags = new byte[size];
        for (BlockState state : registry) {
            int id = registry.getId(state);
            try {
                classify(state, id, rgb, flags);
            } catch (RuntimeException e) {
                // A block that cannot answer without a real world is simply not drawn.
                rgb[id] = 0;
                flags[id] = 0;
            }
        }
        return new StateTables(new Palette(rgb, flags), Block.getId(Blocks.AIR.defaultBlockState()));
    }

    /**
     * Follows the vanilla map: a block that holds fluid and has no solid top (water itself, but
     * also seagrass or a waterlogged fence) is drawn as that fluid.
     */
    private static void classify(BlockState state, int id, int[] rgb, byte[] flags) {
        FluidState fluid = state.getFluidState();
        boolean holdsFluid = !fluid.isEmpty();
        boolean asFluid = holdsFluid && !state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, Direction.UP);
        BlockState shown = asFluid ? fluid.createLegacyBlock() : state;
        MapColor color = shown.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);

        byte f = holdsFluid ? Palette.HAS_FLUID : 0;
        if (color != MapColor.NONE) {
            f |= Palette.VISIBLE;
            if (asFluid) {
                f |= Palette.FLUID;
            }
            rgb[id] = color.col;
        }
        flags[id] = f;
    }

    @Override
    public String nameOf(int stateId) {
        BlockState state = Block.BLOCK_STATE_REGISTRY.byId(stateId);
        return BlockStateParser.serialize(state == null ? Blocks.AIR.defaultBlockState() : state);
    }

    @Override
    public int idOf(String name) {
        Integer known = idsByName.get(name);
        if (known != null) {
            return known;
        }
        int id;
        try {
            id = Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, name, false).blockState());
        } catch (CommandSyntaxException | RuntimeException e) {
            // A block from a mod that is no longer installed, or a renamed one.
            id = air;
        }
        idsByName.put(name, id);
        return id;
    }
}
