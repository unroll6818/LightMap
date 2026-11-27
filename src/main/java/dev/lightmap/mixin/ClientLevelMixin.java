package dev.lightmap.mixin;

import dev.lightmap.LightMapClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place every effective block change on the client passes through: server updates,
 * section updates and the player's own predicted changes all end in {@code Level.setBlock}, which
 * calls this when the state really changed. Chunk (re)loads are covered separately by Fabric's
 * chunk events.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void lightmap$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        LightMapClient.blockChanged((ClientLevel) (Object) this, pos);
    }
}
