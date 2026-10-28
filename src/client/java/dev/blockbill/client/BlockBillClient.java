package dev.blockbill.client;

import net.fabricmc.api.ClientModInitializer;

public final class BlockBillClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HubClient.register();
        InventoryTabs.register();
    }
}
