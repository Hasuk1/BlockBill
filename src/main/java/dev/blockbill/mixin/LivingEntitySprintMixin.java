package dev.blockbill.mixin;

import dev.blockbill.fabric.ProgressionAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
abstract class LivingEntitySprintMixin {
    @ModifyVariable(method = "setSprinting", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean blockbill$checkSprintRequest(boolean sprinting) {
        return sprinting && ProgressionAccess.allowSprint((Entity) (Object) this);
    }
}
