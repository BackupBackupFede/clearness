package net.emeraude.clearness.mixin;

import net.emeraude.clearness.world.Monument;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** First join builds the cathedral; every player gets the Seeker's Compass once. */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void clearness$join(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        Monument.onPlayerJoin(player.level().getServer(), player);
    }
}
