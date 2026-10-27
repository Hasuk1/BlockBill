package dev.blockbill.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

public final class BlockBillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen) {
                int buttonWidth = Math.min(106, Math.max(60, width - 8));
                Screens.getWidgets(screen).add(Button.builder(Component.translatable("guide.blockbill.open"),
                                button -> client.gui.setScreen(new BlockBillGuideScreen(screen)))
                        .bounds(width - buttonWidth - 4, 4, buttonWidth, 20)
                        .tooltip(Tooltip.create(Component.translatable("guide.blockbill.open_hint")))
                        .build());
            }
        });
    }
}
