package dev.blockbill.mixin;

import dev.blockbill.fabric.ProgressionAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityPortalMixin {
    @Inject(method = "teleportToPortalDestination", at = @At("HEAD"), cancellable = true)
    private void blockbill$checkPortalEntry(ServerLevel source, TeleportTransition transition, CallbackInfo callback) {
        if (!ProgressionAccess.allowPortal((Entity) (Object) this, transition.newLevel())) callback.cancel();
    }
}
