package net.emeraude.clearness.mixin;

import net.emeraude.clearness.Clearness;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** The propagation runs at the end of every server tick. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void clearness$tick(BooleanSupplier haveTime, CallbackInfo ci) {
        Clearness.MANAGER.onServerTick((MinecraftServer) (Object) this);
    }
}
