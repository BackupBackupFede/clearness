package net.emeraude.clearness.mixin;

import net.emeraude.clearness.world.OutbreakTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Waking up after a skipped night feeds the Clearness around the bed. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Unique
    private BlockPos clearness$bed;

    @Inject(method = "stopSleepInBed", at = @At("HEAD"))
    private void clearness$beforeWake(boolean forceful, boolean updateLevelList, CallbackInfo ci) {
        // The bed position is cleared while waking up: read it first.
        clearness$bed = ((ServerPlayer) (Object) this).getSleepingPos().orElse(null);
    }

    @Inject(method = "stopSleepInBed", at = @At("RETURN"))
    private void clearness$afterWake(boolean forceful, boolean updateLevelList, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        if (clearness$bed != null && self.level() instanceof ServerLevel level) {
            OutbreakTriggers.onStopSleeping(level, clearness$bed);
        }
        clearness$bed = null;
    }
}
