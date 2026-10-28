package dev.blockbill;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Exercises registered Fabric callbacks through vanilla game mode and packet interaction entry points. */
public final class ExpansionGameTests {
    @GameTest
    public void workshopMenusRequireTheirOwnSubscription(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            var cases = List.of(new BlockCase(Blocks.ENCHANTING_TABLE, ServiceType.ENCHANT),
                    new BlockCase(Blocks.ANVIL, ServiceType.ANVIL), new BlockCase(Blocks.CHIPPED_ANVIL, ServiceType.ANVIL),
                    new BlockCase(Blocks.DAMAGED_ANVIL, ServiceType.ANVIL), new BlockCase(Blocks.SMITHING_TABLE, ServiceType.SMITH));
            for (BlockCase test : cases) {
                BlockPos pos = station(helper, test.block());
                helper.assertTrue(useBlock(fixture.player, pos) == InteractionResult.FAIL, "Unpaid workshop interaction was not rejected: " + test.block());
                helper.assertTrue(fixture.player.containerMenu == fixture.player.inventoryMenu, "Unpaid workshop opened a menu");
            }
            buy(fixture, ServiceType.ENCHANT);
            helper.assertTrue(useBlock(fixture.player, station(helper, Blocks.ENCHANTING_TABLE)).consumesAction(), "Paid enchant action failed");
            helper.assertTrue(fixture.player.containerMenu != fixture.player.inventoryMenu, "Paid enchant menu did not open");
            fixture.player.closeContainer();
            helper.assertTrue(useBlock(fixture.player, station(helper, Blocks.ANVIL)) == InteractionResult.FAIL, "Enchant+ incorrectly unlocked Anvil+");
            buy(fixture, ServiceType.ANVIL);
            buy(fixture, ServiceType.SMITH);
            for (BlockCase test : cases) {
                helper.assertTrue(useBlock(fixture.player, station(helper, test.block())).consumesAction(), "Paid workshop interaction failed");
                helper.assertTrue(fixture.player.containerMenu != fixture.player.inventoryMenu, "Paid workshop did not open a menu: " + test.block());
                fixture.player.closeContainer();
            }
            helper.succeed();
        }
    }

    @GameTest
    public void storageCoversEnderChestAndEveryShulkerColor(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            List<Block> blocks = new ArrayList<>(Blocks.DYED_SHULKER_BOX.asList());
            blocks.add(Blocks.SHULKER_BOX);
            blocks.add(Blocks.ENDER_CHEST);
            for (Block block : blocks) {
                helper.assertTrue(useBlock(fixture.player, station(helper, block)) == InteractionResult.FAIL, "Unpaid storage variant was allowed: " + block);
                helper.assertTrue(fixture.player.containerMenu == fixture.player.inventoryMenu, "Unpaid storage menu opened");
            }
            buy(fixture, ServiceType.STORAGE);
            for (Block block : blocks) {
                helper.assertTrue(useBlock(fixture.player, station(helper, block)).consumesAction(), "Paid storage interaction failed: " + block);
                helper.assertTrue(fixture.player.containerMenu != fixture.player.inventoryMenu, "Paid storage menu did not open");
                fixture.player.closeContainer();
            }
            helper.succeed();
        }
    }

    @GameTest
    public void freeStationsAndSneakPlacementStayAvailable(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            for (Block block : List.of(Blocks.CHEST, Blocks.BARREL, Blocks.CRAFTING_TABLE, Blocks.FURNACE)) {
                helper.assertTrue(useBlock(fixture.player, station(helper, block)).consumesAction(), "Free station was blocked: " + block);
                helper.assertTrue(fixture.player.containerMenu != fixture.player.inventoryMenu, "Free station menu did not open");
                fixture.player.closeContainer();
            }
            BlockPos pos = station(helper, Blocks.ENCHANTING_TABLE);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIRT, 2));
            fixture.player.setShiftKeyDown(true);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.5, 0, 0), Direction.EAST, pos, false);
            InteractionResult result = fixture.player.gameMode.useItemOn(fixture.player, helper.getLevel(),
                    fixture.player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
            helper.assertTrue(result.consumesAction(), "Sneak placement beside paid station was blocked");
            helper.assertTrue(helper.getLevel().getBlockState(pos.east()).is(Blocks.DIRT), "Sneak placement did not place the block");
            helper.assertTrue(fixture.player.getMainHandItem().getCount() == 1, "Placement consumed wrong item count");
            helper.assertTrue(fixture.player.containerMenu == fixture.player.inventoryMenu, "Sneak placement opened paid station");
            helper.succeed();
        }
    }

    @GameTest
    public void expiryStopsNewMenusButDoesNotCloseAnOpenMenu(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            while (fixture.account().activePlayTicks() < SubscriptionEngine.TRIAL_TICKS - 1) fixture.account().tick(true);
            BlockPos pos = station(helper, Blocks.ENCHANTING_TABLE);
            helper.assertTrue(useBlock(fixture.player, pos).consumesAction(), "Last trial tick should permit the station");
            var menu = fixture.player.containerMenu;
            helper.assertTrue(menu != fixture.player.inventoryMenu, "Trial station menu did not open");
            fixture.account().tick(true);
            helper.assertTrue(fixture.player.containerMenu == menu, "Expiry forcibly closed a menu");
            fixture.player.closeContainer();
            helper.assertTrue(useBlock(fixture.player, pos) == InteractionResult.FAIL, "Exact expiry allowed another menu");
            helper.succeed();
        }
    }

    @GameTest
    public void villagerAndWanderingTraderNeedTradeButCuringRemainsFree(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            AbstractVillager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(2, 2, 2));
            AbstractVillager trader = helper.spawn(EntityTypes.WANDERING_TRADER, new BlockPos(3, 2, 2));
            for (AbstractVillager merchant : List.of(villager, trader)) {
                merchant.setNoAi(true);
                MerchantOffers offers = merchant.getOffers();
                offers.clear();
                offers.add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 10, 1, 0));
                interact(fixture.player, merchant);
                helper.assertTrue(!merchant.isTrading() && fixture.player.containerMenu == fixture.player.inventoryMenu,
                        "Unpaid merchant opened trading");
            }
            buy(fixture, ServiceType.TRADE);
            for (AbstractVillager merchant : List.of(villager, trader)) {
                interact(fixture.player, merchant);
                helper.assertTrue(merchant.getTradingPlayer() == fixture.player, "Paid interaction did not start merchant trade");
                helper.assertTrue(fixture.player.containerMenu != fixture.player.inventoryMenu, "Paid trade menu did not open");
                fixture.player.closeContainer();
            }
            // Another expired, unpaid account may still cure a zombie villager.
            try (Fixture healer = player(helper, GameType.SURVIVAL)) {
                expireTrial(healer.account());
                var zombie = helper.spawn(EntityTypes.ZOMBIE_VILLAGER, new BlockPos(2, 2, 3));
                zombie.setNoAi(true);
                zombie.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 600));
                healer.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 2));
                interact(healer.player, zombie);
                helper.assertTrue(zombie.isConverting(), "Trade+ blocked zombie-villager curing");
                helper.assertTrue(healer.player.getMainHandItem().getCount() == 1, "Vanilla curing did not consume exactly one apple");
            }
            villager.discard();
            trader.discard();
            helper.succeed();
        }
    }

    @GameTest
    public void fishingCastIsAtomicAndExistingLineCanAlwaysBeReeled(GameTestHelper helper) {
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            ItemStack rod = new ItemStack(Items.FISHING_ROD);
            rod.setDamageValue(7);
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, rod);
            ItemStack before = rod.copy();
            helper.assertTrue(useItem(fixture.player, InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "Unpaid rod cast was allowed");
            helper.assertTrue(fixture.player.fishing == null, "Unpaid cast spawned fishing hook");
            helper.assertTrue(ItemStack.matches(before, fixture.player.getMainHandItem()), "Denied cast modified rod");
            fixture.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            fixture.player.setItemInHand(InteractionHand.OFF_HAND, rod);
            helper.assertTrue(useItem(fixture.player, InteractionHand.OFF_HAND) == InteractionResult.FAIL, "Offhand bypassed Fish+");
            helper.assertTrue(ItemStack.matches(before, fixture.player.getOffhandItem()), "Denied offhand cast modified rod");
            buy(fixture, ServiceType.FISH);
            helper.assertTrue(useItem(fixture.player, InteractionHand.OFF_HAND).consumesAction(), "Paid fishing cast failed");
            helper.assertTrue(fixture.player.fishing != null, "Paid cast did not create real fishing hook");
            while (SubscriptionEngine.hasAccess(fixture.account(), ServiceType.FISH)) fixture.account().tick(true);
            helper.assertTrue(useItem(fixture.player, InteractionHand.OFF_HAND).consumesAction(), "Expired subscription prevented reeling");
            helper.assertTrue(fixture.player.fishing == null, "Reeling left hook attached");
            helper.assertTrue(fixture.player.getOffhandItem().is(Items.FISHING_ROD), "Reeling removed rod");
            helper.assertTrue(useItem(fixture.player, InteractionHand.OFF_HAND) == InteractionResult.FAIL, "Expired account could cast again");
            helper.succeed();
        }
    }

    @GameTest
    public void nonSurvivalAndUnrelatedEntitiesPassAllAccessCallbacks(GameTestHelper helper) {
        var villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(2, 2, 2));
        BlockPos pos = station(helper, Blocks.ENCHANTING_TABLE);
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE)) {
            try (Fixture fixture = player(helper, mode)) {
                expireTrial(fixture.account());
                fixture.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
                helper.assertTrue(UseBlockCallback.EVENT.invoker().interact(fixture.player, helper.getLevel(), InteractionHand.MAIN_HAND, hit(pos))
                        == InteractionResult.PASS, mode + " had a station access restriction");
                helper.assertTrue(UseEntityCallback.EVENT.invoker().interact(fixture.player, helper.getLevel(), InteractionHand.MAIN_HAND, villager, null)
                        == InteractionResult.PASS, mode + " had a merchant access restriction");
                helper.assertTrue(UseItemCallback.EVENT.invoker().interact(fixture.player, helper.getLevel(), InteractionHand.MAIN_HAND)
                        == InteractionResult.PASS, mode + " had a rod access restriction");
            }
        }
        try (Fixture fixture = player(helper, GameType.SURVIVAL)) {
            expireTrial(fixture.account());
            villager.setBaby(true);
            helper.assertTrue(UseEntityCallback.EVENT.invoker().interact(fixture.player, helper.getLevel(), InteractionHand.MAIN_HAND, villager, null)
                    == InteractionResult.PASS, "Baby villager was treated as a trader");
            var cow = helper.spawn(EntityTypes.COW, new BlockPos(3, 2, 2));
            helper.assertTrue(UseEntityCallback.EVENT.invoker().interact(fixture.player, helper.getLevel(), InteractionHand.MAIN_HAND, cow, null)
                    == InteractionResult.PASS, "Animal interaction was treated as a trader");
            cow.discard();
        }
        villager.discard();
        helper.succeed();
    }

    @GameTest(maxTicks = 50)
    public void denialMessageIncludesRuleAndIsThrottledPerPlayer(GameTestHelper helper) {
        Fixture fixture = player(helper, GameType.SURVIVAL);
        expireTrial(fixture.account());
        BlockPos pos = station(helper, Blocks.SMITHING_TABLE);
        for (int attempt = 0; attempt < 10; attempt++) useBlock(fixture.player, pos);
        helper.assertTrue(fixture.denials().size() == 1, "Repeated same-tick attempts spammed chat");
        TranslatableContents message = (TranslatableContents) fixture.denials().getFirst().getContents();
        Object[] arguments = message.getArgs();
        helper.assertTrue(arguments.length == 3 && arguments[1].equals(ServiceType.SMITH.price())
                && arguments[2] instanceof Component path
                && path.getContents() instanceof TranslatableContents destination
                && destination.getKey().equals("blockbill.open_hub"), "Denial omitted price or inventory purchase path");
        helper.runAfterDelay(19, () -> {
            useBlock(fixture.player, pos);
            helper.assertTrue(fixture.denials().size() == 1, "Cooldown expired before 20 active ticks");
        });
        helper.runAfterDelay(21, () -> {
            try (fixture) {
                useBlock(fixture.player, pos);
                helper.assertTrue(fixture.denials().size() == 2, "Denial cooldown never reset");
                helper.succeed();
            }
        });
    }

    private static Fixture player(GameTestHelper helper, GameType mode) {
        UUID uuid = UUID.randomUUID();
        GameProfile profile = new GameProfile(uuid, "bbx-" + uuid.toString().substring(0, 8));
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        List<Component> messages = new ArrayList<>();
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), profile, cookie.clientInformation()) {
            @Override
            public void sendSystemMessage(Component message) {
                messages.add(message);
                super.sendSystemMessage(message);
            }
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        helper.getLevel().getServer().getConnection().getConnections().add(connection);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        player.setGameMode(mode);
        player.setNoGravity(true);
        player.getAbilities().invulnerable = true;
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 4));
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        return new Fixture(player, connection, channel, messages);
    }

    private static void expireTrial(PlayerAccount account) {
        while (SubscriptionEngine.trialRemainingTicks(account) > 0) account.tick(true);
    }

    private static void buy(Fixture fixture, ServiceType service) {
        fixture.account().setCredits(service.price());
        try {
            int result = fixture.player.level().getServer().getCommands().getDispatcher().execute("blockbill buy " + service.id(),
                    fixture.player.createCommandSourceStack().withPermission(PermissionSet.NO_PERMISSIONS));
            if (result != 1) throw new AssertionError("Purchase failed: " + service);
        } catch (CommandSyntaxException exception) {
            throw new AssertionError("Purchase command missing: " + service, exception);
        }
    }

    private static BlockPos station(GameTestHelper helper, Block block) {
        BlockPos local = new BlockPos(2, 2, 2);
        helper.setBlock(local.below(), Blocks.STONE);
        helper.setBlock(local, block);
        helper.setBlock(local.above(), Blocks.AIR);
        return helper.absolutePos(local);
    }

    private static BlockHitResult hit(BlockPos pos) {
        return new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0, 0.5), Direction.SOUTH, pos, false);
    }

    private static InteractionResult useBlock(ServerPlayer player, BlockPos pos) {
        return player.gameMode.useItemOn(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit(pos));
    }

    private static InteractionResult useItem(ServerPlayer player, InteractionHand hand) {
        return player.gameMode.useItem(player, player.level(), player.getItemInHand(hand), hand);
    }

    private static void interact(ServerPlayer player, Entity target) {
        player.connection.handleInteract(new ServerboundInteractPacket(target.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, false));
    }

    private record BlockCase(Block block, ServiceType service) { }

    private record Fixture(ServerPlayer player, Connection connection, EmbeddedChannel channel, List<Component> messages) implements AutoCloseable {
        PlayerAccount account() { return Accounts.get(player.level().getServer()).account(player.getUUID()); }
        List<Component> denials() {
            return messages.stream().filter(component -> component.getContents() instanceof TranslatableContents contents
                    && contents.getKey().equals("blockbill.access.denied")).toList();
        }
        @Override
        public void close() {
            if (player.fishing != null) player.fishing.discard();
            player.closeContainer();
            player.level().getServer().getPlayerList().remove(player);
            player.level().getServer().getConnection().getConnections().remove(connection);
            connection.disconnect(Component.literal("Expansion test complete"));
            channel.finishAndReleaseAll();
        }
    }
}
