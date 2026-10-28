package dev.blockbill.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the side panel access to the vanilla recipe book's existing public visibility API. */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookScreenAccessor {
    @Accessor("recipeBookComponent") RecipeBookComponent<?> blockbill$getRecipeBookComponent();
}
