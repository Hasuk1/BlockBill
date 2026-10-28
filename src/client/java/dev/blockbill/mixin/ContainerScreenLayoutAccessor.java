package dev.blockbill.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Fabric exposes widgets but not the native container's bounds; only layout is changed. */
@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenLayoutAccessor {
    @Accessor("leftPos") int blockbill$getLeftPos();
    @Accessor("leftPos") void blockbill$setLeftPos(int value);
    @Accessor("topPos") int blockbill$getTopPos();
    @Accessor("imageWidth") int blockbill$getImageWidth();
    @Accessor("imageHeight") int blockbill$getImageHeight();
    @Accessor("lastClickSlot") void blockbill$setLastClickSlot(Slot slot);
}
