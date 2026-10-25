package net.emeraude.clearness.mixin;

import net.emeraude.clearness.ClearnessSelfTest;
import net.emeraude.clearness.TraceDump;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Runs the self-test (or the protection trace) once the dedicated server's levels exist. No-op unless requested. */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Inject(method = "initServer", at = @At("RETURN"))
    private void clearness$selfTest(CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) return;
        if (TraceDump.requested()) {
            TraceDump.run((MinecraftServer) (Object) this);
        } else if (ClearnessSelfTest.requested()) {
            ClearnessSelfTest.run((MinecraftServer) (Object) this);
        }
    }
}
