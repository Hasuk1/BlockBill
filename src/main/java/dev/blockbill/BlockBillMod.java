package dev.blockbill;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import dev.blockbill.fabric.BlockBillCommands;
import dev.blockbill.fabric.BlockBillEvents;
import dev.blockbill.fabric.ServiceAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockBillMod implements ModInitializer {
    public static final String ID = "blockbill";
    public static final Logger LOGGER = LoggerFactory.getLogger(ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) -> BlockBillCommands.register(dispatcher));
        new BlockBillEvents().register();
        ServiceAccess.register();
        LOGGER.info("BlockBill initialized: in-game subscriptions only.");
    }
}
