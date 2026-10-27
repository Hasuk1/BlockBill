package dev.blockbill.client;

import java.util.HashSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Exercises real client screens and locale resources, without desktop input automation. */
public final class BlockBillGuideGameTest implements FabricClientGameTest {
    private static final List<String> EXPECTED_SECTIONS = List.of(
            "start", "services", "workshop", "trade", "fishing", "storage", "bundles", "plans",
            "exchange", "commands", "autorenew", "time", "safety");
    private static final Set<ServiceType> PLUS_SERVICES = Set.of(ServiceType.MINE, ServiceType.SLEEP,
            ServiceType.SPRINT, ServiceType.FISH, ServiceType.STORAGE);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode survival @a");
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player != null && client.gameMode != null);

            checkLocale(context, "en_us", "BlockBill — player guide");
            checkLocale(context, "ru_ru", "BlockBill — правила игры");
            checkPaidInteractions(context, world);
            checkPlans(context, world);

            context.setScreen(() -> null);
            world.getServer().runCommand("gamemode creative @a");
            world.getConnection().waitForClientboundPackets();
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(CreativeModeInventoryScreen.class);
            context.clickScreenButton("guide.blockbill.open");
            context.waitForScreen(BlockBillGuideScreen.class);
            context.takeScreenshot("blockbill-guide-creative-ru");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitForScreen(CreativeModeInventoryScreen.class);
        }
    }

    private static void checkLocale(ClientGameTestContext context, String locale, String expectedTitle) {
        context.runOnClient(client -> {
            client.getLanguageManager().setSelected(locale);
            client.options.languageCode = locale;
            client.getLanguageManager().onResourceManagerReload(client.getResourceManager());
            client.gui.setScreen(null);
        });
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        context.takeScreenshot("blockbill-inventory-" + locale);
        context.clickScreenButton("guide.blockbill.open");
        context.waitForScreen(BlockBillGuideScreen.class);
        context.runOnClient(client -> {
            require(client.gui.screen().getTitle().getString().equals(expectedTitle), "Guide title was not localized: " + locale);
            require(!button(client, "guide.blockbill.previous").active, "First page allowed previous navigation");
        });
        context.takeScreenshot("blockbill-guide-" + locale);

        Set<String> visited = new HashSet<>();
        captureSection(context, locale, visited);
        int pages = 1;
        while (context.computeOnClient(client -> button(client, "guide.blockbill.next").active)) {
            context.clickScreenButton("guide.blockbill.next");
            context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
            captureSection(context, locale, visited);
            require(++pages < 100, "Guide pagination did not terminate");
        }
        require(pages >= 13, "Guide did not expose all thirteen sections");
        require(visited.containsAll(EXPECTED_SECTIONS), "Missing guide sections in " + locale + ": " + visited);
        context.takeScreenshot("blockbill-guide-last-" + locale);
        context.clickScreenButton("guide.blockbill.previous");
        context.runOnClient(client -> require(button(client, "guide.blockbill.next").active, "Previous did not leave last page"));

        // Resize the actual test viewport so pagination and screenshots use the same dimensions.
        record Viewport(int width, int height, int guiScale, int guiWidth, int guiHeight) { }
        Viewport original = context.computeOnClient(client -> new Viewport(
                client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight(),
                client.options.guiScale().get(), client.gui.screen().width, client.gui.screen().height));
        String sectionBeforeResize = context.computeOnClient(client -> client.gui.screen().getNarrationMessage().getString());
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.getWindow().setWindowed(640, 480);
                client.resizeGui();
            });
            context.waitFor(client -> client.gui.screen().width == 320 && client.gui.screen().height == 240);
            context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
            context.runOnClient(client -> {
                require(client.getWindow().getWidth() == 640 && client.getWindow().getHeight() == 480,
                        "Small guide framebuffer does not match the viewport");
                require(client.gui.screen().getNarrationMessage().getString().equals(sectionBeforeResize),
                        "Narrowing the guide changed the reader's section in " + locale);
            });
            context.takeScreenshot("blockbill-guide-small-" + locale);
        } finally {
            context.runOnClient(client -> {
                client.options.guiScale().set(original.guiScale());
                client.getWindow().setWindowed(original.width(), original.height());
                client.resizeGui();
            });
        }
        context.waitFor(client -> client.gui.screen().width == original.guiWidth()
                && client.gui.screen().height == original.guiHeight());
        context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
        context.runOnClient(client -> require(client.gui.screen().getNarrationMessage().getString().equals(sectionBeforeResize),
                "Widening the guide changed the reader's section in " + locale));
        context.clickScreenButton("guide.blockbill.close");
        context.waitForScreen(InventoryScreen.class);
    }

    private static void captureSection(ClientGameTestContext context, String locale, Set<String> visited) {
        String section = context.computeOnClient(client -> {
            Screen screen = client.gui.screen();
            String narration = screen.getNarrationMessage().getString();
            return EXPECTED_SECTIONS.stream().filter(id -> narration.startsWith(screen.getTitle().getString() + ". "
                            + Component.translatable("guide.blockbill." + id + ".title").getString() + ". "))
                    .findFirst().orElseThrow(() -> new AssertionError("Unknown guide section: " + narration));
        });
        if (visited.add(section) && (section.equals("workshop") || section.equals("autorenew") || section.equals("plans"))) {
            context.takeScreenshot("blockbill-guide-" + section + "-" + locale);
        }
    }

    private static void checkPaidInteractions(ClientGameTestContext context, TestSingleplayerContext world) {
        context.setScreen(() -> null);
        BlockPos table = world.getServer().computeOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            var accounts = Accounts.get(server);
            var account = accounts.account(player.getUUID());
            while (account.activePlayTicks() < SubscriptionEngine.TRIAL_TICKS) account.tick(true);
            accounts.setDirty();
            require(!SubscriptionEngine.hasAccess(account, ServiceType.ENCHANT), "Trial still active in fixture");
            BlockPos position = player.blockPosition().east();
            player.level().setBlockAndUpdate(position, Blocks.ENCHANTING_TABLE.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.LAPIS_LAZULI, 7));
            player.inventoryMenu.broadcastChanges();
            return position;
        });
        context.waitTick();
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.level.getBlockState(table).is(Blocks.ENCHANTING_TABLE)
                && client.player.getMainHandItem().is(Items.LAPIS_LAZULI));

        useTable(context, table);
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.runOnClient(client -> {
            require(client.gui.screen() == null, "Unpaid enchanting opened a client menu");
            require(client.player.getMainHandItem().getCount() == 7, "Unpaid enchanting changed client lapis");
        });
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            require(player.containerMenu == player.inventoryMenu, "Unpaid enchanting opened a server menu");
            require(player.getMainHandItem().is(Items.LAPIS_LAZULI) && player.getMainHandItem().getCount() == 7,
                    "Unpaid enchanting consumed server resources");
        });

        world.getServer().runCommand("execute as @a run blockbill debug credits 100");
        world.getServer().runCommand("execute as @a run blockbill buy enchant");
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(SubscriptionEngine.hasAccess(account, ServiceType.ENCHANT) && account.credits() == 75,
                    "Enchant+ command did not buy exactly one period");
        });
        useTable(context, table);
        world.getConnection().waitForServerboundPackets();
        context.waitForScreen(EnchantmentScreen.class);
        context.takeScreenshot("blockbill-enchant-paid-client");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(null);
        world.getConnection().waitForServerboundPackets();

        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FISHING_ROD));
            player.inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.player.getMainHandItem().is(Items.FISHING_ROD));
        useRod(context);
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.runOnClient(client -> require(client.player.fishing == null, "Unpaid cast created a client hook"));
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            require(player.fishing == null, "Unpaid cast created a server hook");
            require(player.getMainHandItem().is(Items.FISHING_ROD) && player.getMainHandItem().getCount() == 1
                            && player.getMainHandItem().getDamageValue() == 0,
                    "Unpaid cast damaged or consumed the rod");
        });

        world.getServer().runCommand("execute as @a run blockbill buy fish");
        useRod(context);
        world.getConnection().waitForServerboundPackets();
        world.getServer().waitFor(server -> world.getConnection().getServerPlayer().fishing != null);
        context.waitFor(client -> client.player.fishing != null);
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            var accounts = Accounts.get(server);
            var account = accounts.account(player.getUUID());
            require(account.credits() == 60, "Fish+ command did not charge exactly 15 credits");
            while (account.activePlayTicks() < account.paidUntil(ServiceType.FISH)) account.tick(true);
            accounts.setDirty();
            require(!SubscriptionEngine.hasAccess(account, ServiceType.FISH), "Fish+ did not expire in fixture");
            require(player.fishing != null, "Fixture lost the existing fishing hook");
        });
        useRod(context);
        world.getConnection().waitForServerboundPackets();
        world.getServer().waitFor(server -> world.getConnection().getServerPlayer().fishing == null);
        context.waitFor(client -> client.player.fishing == null);
        world.getServer().runOnServer(server -> require(
                world.getConnection().getServerPlayer().getMainHandItem().is(Items.FISHING_ROD),
                "Reeling after expiry lost the rod"));
    }

    private static void checkPlans(ClientGameTestContext context, TestSingleplayerContext world) {
        List<Component> messages = new CopyOnWriteArrayList<>();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> messages.add(message));
        for (String locale : List.of("en_us", "ru_ru")) {
            context.runOnClient(client -> {
                client.getLanguageManager().setSelected(locale);
                client.options.languageCode = locale;
                client.getLanguageManager().onResourceManagerReload(client.getResourceManager());
                messages.clear();
                client.getConnection().sendCommand("blockbill plans");
            });
            world.getConnection().waitForServerboundPackets();
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> translated(messages, "blockbill.plans.note").size() == 1);
            context.runOnClient(client -> {
                List<TranslatableContents> rows = translated(messages, "blockbill.catalog.bundle");
                require(rows.size() == 3, "/plans must list exactly three plans");
                String[] ids = {"plus", "pro", "business"};
                long[] prices = {90, 300, 540};
                long[] minutes = {15, 30, 60};
                for (int index = 0; index < ids.length; index++) {
                    Object[] args = rows.get(index).getArgs();
                    require(args.length == 5, "Plan catalog lost dynamic duration argument");
                    require(args[0] instanceof Component name && name.getContents() instanceof TranslatableContents title
                                    && title.getKey().equals("blockbill.bundle." + ids[index]), "Wrong plan catalog order/name");
                    require(number(args[1]) == prices[index] && number(args[2]) == minutes[index],
                            "Plan catalog has wrong price/minutes");
                    require(args[4].toString().equals("/blockbill buy " + ids[index]), "Plan catalog command mismatch");
                    require(args[3] instanceof Component, "Plan catalog did not send service names");
                    String services = ((Component) args[3]).getString();
                    for (ServiceType service : ServiceType.values()) {
                        boolean included = index > 0 || PLUS_SERVICES.contains(service);
                        require(services.contains(Component.translatable("blockbill.service." + service.id()).getString()) == included,
                                "Plan catalog service membership mismatch: " + ids[index] + "/" + service);
                    }
                }
                for (Component message : messages) {
                    require(!message.getString().contains("blockbill."), "Untranslated plan message in " + locale);
                }
            });
            context.takeScreenshot("blockbill-plans-chat-" + locale);
        }

        // Future expiries keep exact addition assertions independent of ticks spent delivering real packets.
        Map<ServiceType, Long> before = world.getServer().computeOnServer(server -> {
            var accounts = Accounts.get(server);
            var account = accounts.account(world.getConnection().getServerPlayer().getUUID());
            account.setCredits(120);
            SubscriptionEngine.purchaseAll(account);
            account.setCredits(1000);
            Map<ServiceType, Long> expiries = new EnumMap<>(ServiceType.class);
            for (ServiceType service : ServiceType.values()) {
                require(account.paidUntil(service) > account.activePlayTicks(), "Client plan fixture failed to seed future expiry");
                expiries.put(service, account.paidUntil(service));
            }
            accounts.setDirty();
            return expiries;
        });
        sendPlanPurchase(context, world, messages, "plus", 15, 910);
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(account.credits() == 910, "Client Plus purchase charged wrong balance");
            for (ServiceType service : ServiceType.values()) {
                require(account.paidUntil(service) == before.get(service) + (PLUS_SERVICES.contains(service) ? 18000 : 0),
                        "Client Plus purchase changed incorrect duration/service: " + service);
                require(!account.autoRenew(service), "Client Plus purchase enabled auto-renew");
            }
        });
        sendPlanPurchase(context, world, messages, "pro", 30, 610);
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(account.credits() == 610, "Client Pro purchase charged wrong balance");
            for (ServiceType service : ServiceType.values()) {
                long expected = before.get(service) + (PLUS_SERVICES.contains(service) ? 18000 : 0) + 36000;
                require(account.paidUntil(service) == expected, "Client Pro upgrade duration mismatch: " + service);
                require(SubscriptionEngine.hasAccess(account, service), "Client Pro upgrade did not grant service: " + service);
                require(!account.autoRenew(service), "Client Pro purchase enabled auto-renew");
            }
        });
        context.takeScreenshot("blockbill-plans-purchased-client-ru");
    }

    private static void sendPlanPurchase(ClientGameTestContext context, TestSingleplayerContext world,
                                         List<Component> messages, String id, long minutes, long balance) {
        context.runOnClient(client -> {
            messages.clear();
            client.getConnection().sendCommand("blockbill buy " + id);
        });
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> !translated(messages, "blockbill.buy.success").isEmpty());
        context.runOnClient(client -> {
            List<TranslatableContents> receipts = translated(messages, "blockbill.buy.success");
            require(receipts.size() == 1, "Expected one purchase receipt");
            Object[] args = receipts.getFirst().getArgs();
            require(args.length == 3 && number(args[1]) == minutes && number(args[2]) == balance,
                    "Purchase receipt lost dynamic minutes/balance for " + id);
            require(args[0] instanceof Component name && name.getContents() instanceof TranslatableContents title
                    && title.getKey().equals("blockbill.bundle." + id), "Purchase receipt named wrong plan");
        });
    }

    private static List<TranslatableContents> translated(List<Component> messages, String key) {
        return messages.stream().map(Component::getContents).filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast).filter(contents -> contents.getKey().equals(key)).toList();
    }

    private static long number(Object argument) {
        // Component network codecs may preserve numeric values or serialize translation arguments as strings.
        return argument instanceof Number number ? number.longValue() : Long.parseLong(argument.toString());
    }

    private static void useTable(ClientGameTestContext context, BlockPos position) {
        context.runOnClient(client -> client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false)));
    }

    private static void useRod(ClientGameTestContext context) {
        context.runOnClient(client -> client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND));
    }

    private static AbstractWidget button(Minecraft client, String key) {
        String label = Component.translatable(key).getString();
        return Screens.getWidgets(client.gui.screen()).stream()
                .filter(widget -> widget.getMessage().getString().equals(label))
                .findFirst().orElseThrow(() -> new AssertionError("Missing guide button: " + key));
    }

    private static void assertButtonsFit(Minecraft client) {
        Screen screen = client.gui.screen();
        require(screen instanceof BlockBillGuideScreen, "Navigation closed the guide");
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            require(widget.getX() >= 0 && widget.getY() >= 0
                            && widget.getX() + widget.getWidth() <= screen.width
                            && widget.getY() + widget.getHeight() <= screen.height,
                    "Guide navigation extends outside the screen");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
