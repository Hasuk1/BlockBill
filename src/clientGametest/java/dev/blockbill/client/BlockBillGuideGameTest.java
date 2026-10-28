package dev.blockbill.client;

import java.util.HashSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.infrastructure.Accounts;
import dev.blockbill.market.ItemPriceCatalog;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.input.MouseButtonInfo;
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
            "start", "services", "workshop", "trade", "fishing", "storage", "travel", "bundles", "plans",
            "exchange", "ads", "commands", "autorenew", "time", "safety");
    private static final Set<ServiceType> PLUS_SERVICES = Set.of(ServiceType.MINE, ServiceType.SLEEP,
            ServiceType.SPRINT, ServiceType.FISH, ServiceType.STORAGE);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext world = context.worldBuilder().create()) {
            world.getServer().runCommand("gamemode survival @a");
            world.getConnection().waitForClientboundPackets();
            context.waitFor(client -> client.player != null && client.gameMode != null);
            world.getServer().runOnServer(server -> {
                var player = world.getConnection().getServerPlayer();
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 6));
                player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 16));
                player.inventoryMenu.broadcastChanges();
            });

            checkInventoryTabs(context, world);
            checkLocale(context, "en_us", "BlockBill — player guide");
            checkLocale(context, "ru_ru", "BlockBill — правила игры");
            checkPaidInteractions(context, world);
            checkPlans(context, world);
            checkHubActions(context, world);

            context.setScreen(() -> null);
            world.getServer().runCommand("gamemode creative @a");
            world.getConnection().waitForClientboundPackets();
            context.getInput().pressKey(options -> options.keyInventory);
            context.waitForScreen(CreativeModeInventoryScreen.class);
            context.clickScreenButton("guide.blockbill.open");
            waitForHub(context);
            context.runOnClient(client -> require(Screens.getWidgets(client.gui.screen()).stream()
                    .filter(widget -> widget.getMessage().getContents() instanceof TranslatableContents text && text.getKey().equals("hub.price"))
                    .noneMatch(widget -> widget.active), "Creative mode allowed hub purchases"));
            takeScreenshot(context, "blockbill-hub-creative-ru");
            context.clickScreenButton("hub.tab.rules");
            context.clickScreenButton("hub.read_rules");
            waitForHub(context);
            takeScreenshot(context, "blockbill-guide-creative-ru");
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitFor(client -> client.gui.screen() instanceof CreativeModeInventoryScreen
                    && InventoryTabs.activeHub(client.gui.screen()) == null);
            context.getInput().pressKey(InputConstants.KEY_ESCAPE);
            context.waitForScreen(null);
        }
    }

    private static void checkInventoryTabs(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getConnection().waitForClientboundPackets();
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        Screen original = context.computeOnClient(client -> client.gui.screen());
        Object menu = context.computeOnClient(client -> client.player.containerMenu);
        context.runOnClient(client -> require(InventoryTabs.activeHub(original) == null,
                "Opening inventory selected BlockBill instead of the vanilla tab"));
        takeScreenshot(context, "blockbill-native-inventory-tabs");

        context.runOnClient(client -> require(client.player.inventoryMenu.getSlot(36).getItem().is(Items.DIAMOND)
                && client.player.inventoryMenu.getSlot(36).getItem().getCount() == 6, "Missing native slot fixture"));
        int[] slot = inventorySlotPoint(context, 36);
        clickAt(context, slot[0], slot[1]);
        context.waitFor(client -> client.player.inventoryMenu.getCarried().is(Items.DIAMOND));
        world.getConnection().waitForServerboundPackets();
        world.getServer().runOnServer(server -> require(world.getConnection().getServerPlayer().inventoryMenu.getCarried().getCount() == 6,
                "Native inventory slot pickup was blocked"));

        // A tab change may be blocked while holding a stack, or preserve it unchanged.
        // Either behavior must retain the native menu and never drop or sell the carried items.
        clickWidgetAt(context, widget -> widget.getMessage().getString().equals(Component.translatable("guide.blockbill.open").getString()));
        context.runOnClient(client -> require(client.gui.screen() == original && client.player.containerMenu == menu
                        && client.player.inventoryMenu.getCarried().is(Items.DIAMOND)
                        && client.player.inventoryMenu.getCarried().getCount() == 6,
                "Tab switch lost the held stack or replaced the inventory/menu"));
        if (context.computeOnClient(client -> InventoryTabs.activeHub(original) != null)) {
            clickWidgetAt(context, widget -> widget.getMessage().getString().equals(Component.translatable("hub.inventory_tab").getString()));
        }
        // Keep slot -> tab -> same slot fast: the owned tab click must break vanilla's
        // double-click collect sequence, even when the tab cannot open with a held stack.
        int[] restoreSlot = inventorySlotPoint(context, 36);
        context.runOnClient(client -> {
            require(client.gui.screen() == original && InventoryTabs.activeHub(original) == null,
                    "Disabled-tab test left a different screen or an open side panel");
            require(client.player.inventoryMenu.getCarried().is(Items.DIAMOND)
                            && client.player.inventoryMenu.getCarried().getCount() == 6
                            && client.player.inventoryMenu.getSlot(36).getItem().isEmpty(),
                    "Before placing stack: carried=" + client.player.inventoryMenu.getCarried()
                            + ", native slot=" + client.player.inventoryMenu.getSlot(36).getItem());
        });
        clickAt(context, restoreSlot[0], restoreSlot[1]);
        takeScreenshot(context, "blockbill-native-stack-restored");
        context.runOnClient(client -> require(client.player.inventoryMenu.getCarried().isEmpty()
                        && client.player.inventoryMenu.getSlot(36).getItem().getCount() == 6,
                "Native place click at " + restoreSlot[0] + "," + restoreSlot[1]
                        + " left carried=" + client.player.inventoryMenu.getCarried()
                        + ", slot=" + client.player.inventoryMenu.getSlot(36).getItem()));
        world.getConnection().waitForServerboundPackets();

        int recipeX = context.computeOnClient(client -> recipeButton(client).getX());
        clickWidgetAt(context, ImageButton.class::isInstance);
        context.waitFor(client -> recipeButton(client).getX() != recipeX);
        takeScreenshot(context, "blockbill-native-recipe-book");
        clickWidgetAt(context, ImageButton.class::isInstance);
        context.waitFor(client -> recipeButton(client).getX() == recipeX);
        clickWidgetAt(context, widget -> widget.getMessage().getString().equals(Component.translatable("guide.blockbill.open").getString()));
        waitForHub(context);
        context.runOnClient(client -> require(client.gui.screen() == original && client.player.containerMenu == menu,
                "BlockBill tab replaced the native inventory screen or menu"));
        int[] visibleSlot = inventorySlotPoint(context, 36);
        clickAt(context, visibleSlot[0], visibleSlot[1]);
        context.waitFor(client -> client.player.inventoryMenu.getCarried().is(Items.DIAMOND));
        world.getConnection().waitForServerboundPackets();
        world.getServer().runOnServer(server -> require(world.getConnection().getServerPlayer().inventoryMenu.getCarried().getCount() == 6,
                "Opening BlockBill made vanilla slots unusable"));
        long sidePanelPlaceStart = System.nanoTime();
        context.waitFor(client -> System.nanoTime() - sidePanelPlaceStart >= 300_000_000L);
        clickAt(context, visibleSlot[0], visibleSlot[1]);
        context.waitFor(client -> client.player.inventoryMenu.getCarried().isEmpty()
                && client.player.inventoryMenu.getSlot(36).getItem().getCount() == 6);
        world.getConnection().waitForServerboundPackets();
        context.runOnClient(client -> require(client.gui.screen() == original && client.player.containerMenu == menu,
                "Using native slots alongside BlockBill changed the inventory host"));
        takeScreenshot(context, "blockbill-native-selected-tab");
        clickWidgetAt(context, widget -> widget.getMessage().getString().equals(Component.translatable("hub.inventory_tab").getString()));
        context.runOnClient(client -> require(client.gui.screen() == original && InventoryTabs.activeHub(original) == null
                        && client.player.containerMenu == menu && client.player.inventoryMenu.getCarried().isEmpty(),
                "Returning to vanilla replaced the screen/menu or left a held item"));
        takeScreenshot(context, "blockbill-native-inventory-restored");
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitForScreen(null);
    }

    private static int[] inventorySlotPoint(ClientGameTestContext context, int menuSlot) {
        return context.computeOnClient(client -> {
            var slot = client.player.inventoryMenu.getSlot(menuSlot);
            return new int[]{InventoryTabs.inventoryLeft(client.gui.screen()) + slot.x + 8,
                    InventoryTabs.inventoryTop(client.gui.screen()) + slot.y + 8};
        });
    }

    private static AbstractWidget recipeButton(Minecraft client) {
        return Screens.getWidgets(client.gui.screen()).stream().filter(ImageButton.class::isInstance).findFirst().orElseThrow();
    }

    private static void clickWidgetAt(ClientGameTestContext context, Predicate<AbstractWidget> predicate) {
        int[] point = context.computeOnClient(client -> {
            AbstractWidget widget = Screens.getWidgets(client.gui.screen()).stream().filter(predicate).findFirst().orElseThrow();
            return new int[]{widget.getX() + widget.getWidth() / 2, widget.getY() + widget.getHeight() / 2};
        });
        clickAt(context, point[0], point[1]);
    }

    private static void clickAt(ClientGameTestContext context, int guiX, int guiY) {
        double[] point = context.computeOnClient(client -> new double[]{
                guiX * (double) client.getWindow().getScreenWidth() / client.gui.screen().width,
                guiY * (double) client.getWindow().getScreenHeight() / client.gui.screen().height});
        context.getInput().setCursorPos(point[0], point[1]);
        context.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);
    }

    private static BlockBillHubScreen hub(Minecraft client) {
        BlockBillHubScreen hub = InventoryTabs.activeHub(client.gui.screen());
        if (hub == null) throw new AssertionError("No BlockBill tab in native inventory");
        return hub;
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
        takeScreenshot(context, "blockbill-inventory-" + locale);
        context.clickScreenButton("guide.blockbill.open");
        waitForHub(context);
        checkSmallHub(context, locale);
        context.clickScreenButton("hub.tab.rules");
        context.clickScreenButton("hub.read_rules");
        waitForHub(context);
        context.runOnClient(client -> {
            require(hub(client).getNarrationMessage().getString().startsWith(expectedTitle + ". "),
                    "Guide title was not localized: " + locale);
            require(!button(client, "<").active, "First page allowed previous navigation");
        });
        takeScreenshot(context, "blockbill-guide-" + locale);

        Set<String> visited = new HashSet<>();
        captureSection(context, locale, visited);
        int pages = 1;
        while (context.computeOnClient(client -> button(client, ">").active)) {
            context.clickScreenButton(">");
            context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
            captureSection(context, locale, visited);
            require(++pages < 400, "Guide pagination did not terminate");
        }
        require(pages >= 15, "Guide did not expose all fifteen sections");
        require(visited.containsAll(EXPECTED_SECTIONS), "Missing guide sections in " + locale + ": " + visited);
        takeScreenshot(context, "blockbill-guide-last-" + locale);
        context.clickScreenButton("<");
        context.runOnClient(client -> require(button(client, ">").active, "Previous did not leave last page"));

        // Resize the actual test viewport so pagination and screenshots use the same dimensions.
        record Viewport(int width, int height, int guiScale, int guiWidth, int guiHeight) { }
        Viewport original = context.computeOnClient(client -> new Viewport(
                client.getWindow().getScreenWidth(), client.getWindow().getScreenHeight(),
                client.options.guiScale().get(), client.gui.screen().width, client.gui.screen().height));
        String sectionBeforeResize = context.computeOnClient(client -> hub(client).getNarrationMessage().getString());
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
                require(hub(client).getNarrationMessage().getString().equals(sectionBeforeResize),
                        "Narrowing the guide changed the reader's section in " + locale);
            });
            takeScreenshot(context, "blockbill-guide-small-" + locale);
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
        context.runOnClient(client -> require(hub(client).getNarrationMessage().getString().equals(sectionBeforeResize),
                "Widening the guide changed the reader's section in " + locale));
        context.clickScreenButton("hub.rules_back");
        waitForHub(context);
        context.clickScreenButton("hub.inventory_tab");
        context.waitForScreen(InventoryScreen.class);
    }

    private static void captureSection(ClientGameTestContext context, String locale, Set<String> visited) {
        String section = context.computeOnClient(client -> {
            String narration = hub(client).getNarrationMessage().getString();
            return EXPECTED_SECTIONS.stream().filter(id -> narration.startsWith(Component.translatable("guide.blockbill.title").getString() + ". "
                            + Component.translatable("guide.blockbill." + id + ".title").getString() + ". "))
                    .findFirst().orElseThrow(() -> new AssertionError("Unknown guide section: " + narration));
        });
        if (visited.add(section) && (section.equals("workshop") || section.equals("autorenew") || section.equals("plans"))) {
            takeScreenshot(context, "blockbill-guide-" + section + "-" + locale);
        }
    }

    private static void waitForHub(ClientGameTestContext context) {
        context.waitFor(client -> (client.gui.screen() instanceof InventoryScreen
                || client.gui.screen() instanceof CreativeModeInventoryScreen)
                && InventoryTabs.activeHub(client.gui.screen()) != null
                && InventoryTabs.activeHub(client.gui.screen()).isReady());
    }

    private static void checkSmallHub(ClientGameTestContext context, String locale) {
        record Viewport(int width, int height, int scale) { }
        Viewport original = context.computeOnClient(client -> new Viewport(client.getWindow().getScreenWidth(),
                client.getWindow().getScreenHeight(), client.options.guiScale().get()));
        try {
            context.runOnClient(client -> {
                client.options.guiScale().set(2);
                client.getWindow().setWindowed(640, 480);
                client.resizeGui();
            });
            context.waitFor(client -> client.gui.screen().width == 320 && client.gui.screen().height == 240);
            for (String tab : List.of("overview", "plans", "sell", "ad", "rules")) {
                context.clickScreenButton("hub.tab." + tab);
                context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
                takeScreenshot(context, "blockbill-hub-small-" + tab + "-" + locale);
                if (tab.equals("overview") || tab.equals("plans")) {
                    int pages = 1;
                    while (context.computeOnClient(client -> button(client, ">").active)) {
                        context.clickScreenButton(">");
                        context.runOnClient(BlockBillGuideGameTest::assertButtonsFit);
                        require(++pages <= 10, "Hub pagination did not terminate");
                    }
                    require(pages > 1, "Small hub failed to paginate " + tab);
                    takeScreenshot(context, "blockbill-hub-small-" + tab + "-last-" + locale);
                }
            }
        } finally {
            context.runOnClient(client -> {
                client.options.guiScale().set(original.scale());
                client.getWindow().setWindowed(original.width(), original.height());
                client.resizeGui();
            });
        }
    }

    private static void checkHubActions(ClientGameTestContext context, TestSingleplayerContext world) {
        context.setScreen(() -> null);
        long diamondPrice = world.getServer().computeOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            player.getInventory().clearContent();
            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            player.inventoryMenu.broadcastChanges();
            var accounts = Accounts.get(server);
            var account = accounts.account(player.getUUID());
            account.setCredits(1000);
            for (ServiceType service : ServiceType.values()) account.setAutoRenew(service, false);
            accounts.setDirty();
            return ItemPriceCatalog.unitPriceCents(player.getInventory().getItem(0));
        });
        world.getConnection().waitForClientboundPackets();
        openHub(context);
        context.clickScreenButton("hub.credits");
        int[] saleSlot = inventorySlotPoint(context, 36);
        clickAt(context, saleSlot[0], saleSlot[1]);
        context.runOnClient(client -> require(client.player.inventoryMenu.getCarried().isEmpty()
                && client.player.inventoryMenu.getSlot(36).getItem().getCount() == 3,
                "Selecting an item for sale picked it up or consumed it"));
        world.getConnection().waitForServerboundPackets();
        world.getServer().runOnServer(server -> {
            var player = world.getConnection().getServerPlayer();
            require(player.inventoryMenu.getCarried().isEmpty() && player.getInventory().getItem(0).getCount() == 3,
                    "Sale selection mutated the native inventory before confirmation");
        });
        context.clickScreenButton("hub.sell_one");
        world.getServer().runOnServer(server -> require(world.getConnection().getServerPlayer().getInventory().getItem(0).getCount() == 3,
                "First sale click consumed items before confirmation"));
        takeScreenshot(context, "blockbill-sale-confirm-ru");
        context.clickScreenButton("hub.confirm");
        world.getServer().waitFor(server -> world.getConnection().getServerPlayer().getInventory().getItem(0).getCount() == 2);
        context.waitFor(client -> isEnabled(client, "hub.sell_stack"));
        assertBalance(world, 100_000 + diamondPrice);
        context.clickScreenButton("hub.sell_stack");
        world.getServer().runOnServer(server -> require(world.getConnection().getServerPlayer().getInventory().getItem(0).getCount() == 2,
                "First stack-sale click consumed items before confirmation"));
        context.clickScreenButton("hub.confirm");
        world.getServer().waitFor(server -> world.getConnection().getServerPlayer().getInventory().getItem(0).isEmpty());
        world.getConnection().waitForClientboundPackets();
        context.waitFor(client -> client.player.getInventory().getItem(0).isEmpty());
        assertBalance(world, 100_000 + diamondPrice * 3);

        context.clickScreenButton("hub.catalog");
        context.runOnClient(client -> {
            EditBox search = (EditBox) Screens.getWidgets(client.gui.screen()).stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
            search.setValue("");
        });
        clickWidgetAt(context, EditBox.class::isInstance);
        context.getInput().typeChars("minecraft:diamond");
        context.clickScreenButton("hub.search");
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.runOnClient(client -> {
            EditBox search = (EditBox) Screens.getWidgets(client.gui.screen()).stream().filter(EditBox.class::isInstance).findFirst().orElseThrow();
            require(search.getValue().equals("minecraft:diamond"), "Side panel search did not receive typed text");
        });
        takeScreenshot(context, "blockbill-market-search-ru");
        long expectedMatches = world.getServer().computeOnServer(server -> ItemPriceCatalog.prices().keySet().stream()
                .filter(id -> id.contains("minecraft:diamond")).count());
        long matches = 0;
        int catalogPages = 0;
        do {
            matches += context.computeOnClient(client -> Screens.getWidgets(client.gui.screen()).stream()
                    .filter(widget -> widget.getWidth() == 21 && widget.active).count());
            if (!context.computeOnClient(client -> button(client, ">").active)) break;
            require(++catalogPages < 20, "Search did not narrow the full catalog");
            context.clickScreenButton(">");
        } while (true);
        require(matches == expectedMatches && matches > 0, "Side-panel catalog omitted or added search results");

        context.clickScreenButton("hub.tab.overview");
        long mineBefore = world.getServer().computeOnServer(server -> Accounts.get(server)
                .account(world.getConnection().getServerPlayer().getUUID()).paidUntil(ServiceType.MINE));
        clickPrice(context, 20);
        world.getServer().waitFor(server -> Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID())
                .paidUntil(ServiceType.MINE) == mineBefore + 12_000);
        context.waitFor(client -> isEnabled(client, "hub.auto_off"));
        assertBalance(world, 98_000 + diamondPrice * 3);
        context.clickScreenButton("hub.auto_off");
        world.getServer().waitFor(server -> Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID()).autoRenew(ServiceType.MINE));
        context.waitFor(client -> Screens.getWidgets(client.gui.screen()).stream()
                .anyMatch(widget -> widget.active && widget.getMessage().getString().equals(Component.translatable("hub.auto_on").getString())));
        context.clickScreenButton("hub.auto_on");
        world.getServer().waitFor(server -> !Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID()).autoRenew(ServiceType.MINE));
        context.waitFor(client -> isEnabled(client, "hub.auto_off"));

        Map<ServiceType, Long> beforePlan = world.getServer().computeOnServer(server -> {
            Map<ServiceType, Long> values = new EnumMap<>(ServiceType.class);
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            for (ServiceType service : ServiceType.values()) values.put(service, account.paidUntil(service));
            return values;
        });
        context.clickScreenButton("hub.tab.plans");
        findPricePage(context, 300);
        clickPrice(context, 300);
        world.getServer().waitFor(server -> Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID())
                .paidUntil(ServiceType.TRAVEL) == beforePlan.get(ServiceType.TRAVEL) + 36_000);
        assertBalance(world, 68_000 + diamondPrice * 3);
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            for (ServiceType service : ServiceType.values()) {
                require(account.paidUntil(service) == beforePlan.get(service) + 36_000, "UI Pro purchase missed service: " + service);
                require(!account.autoRenew(service), "UI plan changed auto-renewal settings");
            }
        });
        takeScreenshot(context, "blockbill-plan-purchased-ui-ru");
        checkAdvertisements(context, world);
        context.clickScreenButton("hub.inventory_tab");
        context.waitForScreen(InventoryScreen.class);
    }

    private static void checkAdvertisements(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var accounts = Accounts.get(server);
            var account = accounts.account(world.getConnection().getServerPlayer().getUUID());
            long lastExpiry = 0;
            for (ServiceType service : ServiceType.values()) lastExpiry = Math.max(lastExpiry, account.paidUntil(service));
            while (account.activePlayTicks() <= lastExpiry) account.tick(true);
            accounts.setDirty();
        });
        context.clickScreenButton("hub.tab.ad");
        context.waitFor(client -> isEnabled(client, "hub.ad_start"));
        context.clickScreenButton("hub.ad_start");
        context.waitFor(client -> button(client, "hub.cancel").active);
        context.runOnClient(client -> require(!isEnabled(client, "hub.ad_claim"),
                "Claim button was enabled before the ad finished"));
        takeScreenshot(context, "blockbill-ad-watching-ui-ru");
        context.clickScreenButton("hub.cancel");
        context.waitFor(client -> Screens.getWidgets(client.gui.screen()).stream().anyMatch(widget -> widget.active
                && widget.getMessage().getString().equals(Component.translatable("hub.ad_start").getString())));
        world.getServer().runOnServer(server -> require(Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID())
                .adRemainingTicks() == 0, "Cancelling an ad granted access"));
        context.clickScreenButton("hub.ad_start");
        context.waitFor(client -> button(client, "hub.cancel").active);
        context.clickScreenButton("hub.tab.overview");
        world.getConnection().waitForServerboundPackets();
        world.getConnection().waitForClientboundPackets();
        context.clickScreenButton("hub.tab.ad");
        context.waitFor(client -> isEnabled(client, "hub.ad_start"));
        world.getServer().runOnServer(server -> require(Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID())
                .adRemainingTicks() == 0, "Switching tabs granted an ad reward"));
        context.clickScreenButton("hub.ad_start");
        context.waitFor(client -> button(client, "hub.cancel").active);
        context.clickScreenButton("hub.inventory_tab");
        context.waitForScreen(InventoryScreen.class);
        world.getConnection().waitForServerboundPackets();
        openHubFromInventory(context);
        context.clickScreenButton("hub.tab.ad");
        context.waitFor(client -> isEnabled(client, "hub.ad_start"));
        world.getServer().runOnServer(server -> require(Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID())
                .adRemainingTicks() == 0, "Closing an ad granted access"));
        long beforeWatch = world.getServer().computeOnServer(server -> Accounts.get(server)
                .account(world.getConnection().getServerPlayer().getUUID()).activePlayTicks());
        context.clickScreenButton("hub.ad_start");
        context.waitFor(client -> Screens.getWidgets(client.gui.screen()).stream().anyMatch(widget -> widget.active
                && widget.getMessage().getString().equals(Component.translatable("hub.ad_claim").getString())), 240);
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(account.activePlayTicks() - beforeWatch >= 100, "Ad claim became available before 100 actual server ticks");
            require(account.adRemainingTicks() == 0, "Ad reward was granted without a claim");
        });
        takeScreenshot(context, "blockbill-ad-ready-ui-ru");
        context.clickScreenButton("hub.ad_claim");
        world.getServer().waitFor(server -> Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID()).adRemainingTicks() > 0);
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(account.adRemainingTicks() > 550 && account.adRemainingTicks() <= 600, "Ad did not grant 600 active ticks");
            require(account.adReadyAtTick() - account.adUntilTick() == 1200, "Ad cooldown was not 90 seconds from claim");
            for (ServiceType service : ServiceType.values()) require(SubscriptionEngine.hasAccess(account, service), "Ad missed service " + service);
        });
        world.getConnection().waitForClientboundPackets();
        takeScreenshot(context, "blockbill-ad-reward-ui-ru");
    }

    private static void openHub(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitForScreen(InventoryScreen.class);
        openHubFromInventory(context);
    }

    private static void openHubFromInventory(ClientGameTestContext context) {
        context.clickScreenButton("guide.blockbill.open");
        waitForHub(context);
    }

    private static void assertBalance(TestSingleplayerContext world, long cents) {
        world.getServer().runOnServer(server -> {
            var account = Accounts.get(server).account(world.getConnection().getServerPlayer().getUUID());
            require(account.credits() * 100 + account.creditRemainder() == cents, "UI transaction charged or credited the wrong amount");
        });
    }

    private static void clickWidget(ClientGameTestContext context, Predicate<AbstractWidget> predicate) {
        context.runOnClient(client -> {
            AbstractWidget widget = Screens.getWidgets(client.gui.screen()).stream().filter(predicate).findFirst().orElseThrow();
            require(widget.active && widget instanceof Button, "Attempted to click a disabled/non-button widget");
            ((Button) widget).onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0));
        });
    }

    private static void clickPrice(ClientGameTestContext context, long price) {
        String label = context.computeOnClient(client -> Component.translatable("hub.price", price).getString());
        clickWidget(context, widget -> widget.getMessage().getString().equals(label));
    }

    private static void findPricePage(ClientGameTestContext context, long price) {
        int pages = 0;
        while (!context.computeOnClient(client -> Screens.getWidgets(client.gui.screen()).stream()
                .anyMatch(widget -> widget.getMessage().getString().equals(Component.translatable("hub.price", price).getString())))) {
            require(++pages <= 10, "Could not find plan price " + price);
            context.clickScreenButton(">");
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
        takeScreenshot(context, "blockbill-enchant-paid-client");
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
            takeScreenshot(context, "blockbill-plans-chat-" + locale);
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
        takeScreenshot(context, "blockbill-plans-purchased-client-ru");
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
        return argument instanceof Number number ? number.longValue() : new java.math.BigDecimal(argument.toString()).longValueExact();
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

    private static boolean isEnabled(Minecraft client, String key) {
        String label = Component.translatable(key).getString();
        return Screens.getWidgets(client.gui.screen()).stream().anyMatch(widget -> widget.active && widget.getMessage().getString().equals(label));
    }

    private static void assertButtonsFit(Minecraft client) {
        Screen screen = client.gui.screen();
        require((screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen)
                && InventoryTabs.activeHub(screen) != null, "Navigation replaced or closed the native inventory");
        for (AbstractWidget widget : Screens.getWidgets(screen)) {
            require(widget.getX() >= 0 && widget.getY() >= 0
                            && widget.getX() + widget.getWidth() <= screen.width
                            && widget.getY() + widget.getHeight() <= screen.height,
                    "Guide navigation extends outside the screen");
            require(!widget.getMessage().getString().contains("hub.") && !widget.getMessage().getString().contains("guide.blockbill."),
                    "Untranslated BlockBill widget");
        }
    }

    private static void takeScreenshot(ClientGameTestContext context, String name) {
        // Fixture grants unlock vanilla recipes/advancements. Remove only their transient
        // test overlays so screenshots show the actual BlockBill screen underneath.
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.takeScreenshot(name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
