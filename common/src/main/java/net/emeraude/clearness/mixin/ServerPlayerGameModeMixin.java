package net.emeraude.clearness.mixin;

import net.emeraude.clearness.world.Monument;
import net.emeraude.clearness.world.OutbreakTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The player's hands: right-click on a block (workstation punishments, opening the sealed chest),
 * right-click with an item (the Seeker's Compass), breaking a block (the sealed chest resists).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow
    @Final
    protected ServerPlayer player;

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void clearness$useOn(ServerPlayer user, Level world, ItemStack stack, InteractionHand hand, BlockHitResult hit,
                                 CallbackInfoReturnable<InteractionResult> cir) {
        if (!(world instanceof ServerLevel serverLevel) || user.isSpectator()) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (Monument.isSealed(serverLevel, pos)) {
            // Opening the box releases the Clearness; the vault's own interaction never runs.
            Monument.capture(serverLevel, user, pos);
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        OutbreakTriggers.onUseBlock(serverLevel, pos); // never blocks the interaction
    }

    @Inject(method = "useItem", at = @At("HEAD"))
    private void clearness$use(ServerPlayer user, Level world, ItemStack stack, InteractionHand hand,
                               CallbackInfoReturnable<InteractionResult> cir) {
        if (world instanceof ServerLevel serverLevel && Monument.isSeekerCompass(stack)) {
            Monument.onCompassUse(serverLevel, user);
        }
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void clearness$destroy(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Unbreakable until opened, like bedrock: creative mode excepted.
        if (!player.isCreative() && Monument.isSealed(level, pos)) {
            cir.setReturnValue(false);
        }
    }
}
