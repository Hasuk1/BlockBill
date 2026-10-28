package dev.blockbill.client;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.blockbill.mixin.ContainerScreenLayoutAccessor;
import dev.blockbill.mixin.RecipeBookScreenAccessor;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;

/** Opens BlockBill alongside the live vanilla inventory, without replacing its screen or container. */
public final class InventoryTabs {
    private static final Map<Screen, Controller> CONTROLLERS = new IdentityHashMap<>();

    private InventoryTabs() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen)) return;
            Controller controller = CONTROLLERS.computeIfAbsent(screen, Controller::new);
            controller.initialized(client);
            // Fabric recreates per-screen events during both init and resize.
            ScreenEvents.afterTick(screen).register(ignored -> controller.tick());
            // Fabric invokes this after native contents, before the carried stack and deferred
            // tooltips, keeping vanilla item tooltips above the side panel as well.
            ScreenEvents.afterForeground(screen).register((ignored, graphics, mouseX, mouseY, partialTick) -> {
                if (!controller.active) return;
                graphics.nextStratum();
                controller.extractSelectedSlot(graphics);
                controller.hub.extractRenderState(graphics, mouseX, mouseY, partialTick);
            });
            ScreenEvents.remove(screen).register(ignored -> {
                Controller removed = CONTROLLERS.remove(screen);
                if (removed != null) removed.dispose();
            });
            ScreenMouseEvents.allowMouseClick(screen).register((ignored, event) -> {
                // Tabs and the side panel are outside vanilla's container rectangle. Consume even
                // disabled controls and blank panel space, so clicks cannot drop a held stack.
                if (controller.clickTab(event)) return false;
                if (!controller.active) return true;
                if (controller.overPanel(event.x(), event.y())) {
                    controller.ownedPointer = true;
                    controller.breakNativeDoubleClick();
                    hostFocusToHub(controller);
                    controller.hub.mouseClicked(event, false);
                    return false;
                }
                controller.hub.clearFocus();
                if (controller.hub.sellingItems() && client.player != null
                        && client.player.inventoryMenu.getCarried().isEmpty()) {
                    Slot slot = controller.inventorySlot(event.x(), event.y());
                    if (slot != null) {
                        controller.ownedPointer = true;
                        controller.breakNativeDoubleClick();
                        controller.hub.selectInventorySlot(slot.getContainerSlot());
                        return false;
                    }
                }
                return true;
            });
            ScreenMouseEvents.allowMouseRelease(screen).register((ignored, event) -> {
                if (controller.ownedPointer) {
                    controller.ownedPointer = false;
                    if (controller.active) controller.hub.mouseReleased(event);
                    return false;
                }
                if (controller.overTab(event.x(), event.y()) != null
                        || controller.active && controller.overPanel(event.x(), event.y())) {
                    // Finish vanilla's possible drag on empty space INSIDE its frame, rather than
                    // letting a release over our UI become an outside-container drop.
                    controller.breakNativeDoubleClick();
                    screen.mouseReleased(new MouseButtonEvent(inventoryLeft(screen) + 2,
                            inventoryTop(screen) + 2, event.buttonInfo()));
                    return false;
                }
                return true;
            });
            ScreenMouseEvents.allowMouseDrag(screen).register((ignored, event, deltaX, deltaY) -> {
                if (controller.ownedPointer) {
                    if (controller.active) controller.hub.mouseDragged(event, deltaX, deltaY);
                    return false;
                }
                return controller.overTab(event.x(), event.y()) == null
                        && !(controller.active && controller.overPanel(event.x(), event.y()));
            });
            ScreenMouseEvents.allowMouseScroll(screen).register((ignored, mouseX, mouseY, horizontal, vertical) -> {
                if (!controller.active || !controller.overPanel(mouseX, mouseY)) return true;
                controller.hub.mouseScrolled(mouseX, mouseY, horizontal, vertical);
                return false;
            });
            ScreenKeyboardEvents.allowKeyPress(screen).register((ignored, event) -> {
                if (!controller.active) return true;
                boolean editing = controller.hub.getFocused() instanceof EditBox || screen.getFocused() instanceof EditBox;
                if (!editing && client.options.keyInventory.matches(event)) {
                    screen.onClose();
                    return false;
                }
                if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                        || controller.hub.getFocused() != null) {
                    controller.hub.keyPressed(event);
                    return false;
                }
                return true;
            });
            ScreenKeyboardEvents.allowKeyRelease(screen).register((ignored, event) -> {
                if (!controller.active || controller.hub.getFocused() == null) return true;
                controller.hub.keyReleased(event);
                return false;
            });
            ScreenKeyboardEvents.allowCharType(screen).register((ignored, event) -> {
                if (!controller.active || controller.hub.getFocused() == null) return true;
                controller.hub.charTyped(event);
                return false;
            });
        });
    }

    private static void hostFocusToHub(Controller controller) {
        controller.host.clearFocus();
        controller.host.setDragging(false);
    }

    public static BlockBillHubScreen activeHub(Screen screen) {
        Controller controller = CONTROLLERS.get(screen);
        return controller != null && controller.active ? controller.hub : null;
    }

    public static void showInventory(Screen screen) {
        Controller controller = CONTROLLERS.get(screen);
        if (controller != null) controller.showInventory();
    }

    public static void syncWidgets(Screen screen) {
        Controller controller = CONTROLLERS.get(screen);
        if (controller != null && controller.active) controller.syncWidgets();
    }

    public static int inventoryLeft(Screen screen) { return layout(screen).blockbill$getLeftPos(); }
    public static int inventoryTop(Screen screen) { return layout(screen).blockbill$getTopPos(); }
    public static int inventoryWidth(Screen screen) { return layout(screen).blockbill$getImageWidth(); }
    public static int inventoryHeight(Screen screen) { return layout(screen).blockbill$getImageHeight(); }
    private static ContainerScreenLayoutAccessor layout(Screen screen) { return (ContainerScreenLayoutAccessor) screen; }

    private static final class Controller {
        private final Screen host;
        private final List<AbstractWidget> vanillaWidgets = new ArrayList<>();
        private final List<AbstractWidget> mirroredWidgets = new ArrayList<>();
        private final Button inventoryTab;
        private final Button blockBillTab;
        private BlockBillHubScreen hub;
        private boolean active;
        private boolean ownedPointer;
        private AbstractWidget recipeButton;
        private boolean recipeButtonWasActive;
        private boolean recipeWasVisible;
        private int originalLeft;
        private int panelLeft;
        private int panelWidth;
        private int panelTop;
        private int panelHeight;

        private Controller(Screen host) {
            this.host = host;
            inventoryTab = Button.builder(Component.translatable("hub.inventory_tab"), ignored -> showInventory())
                    .bounds(0, 0, 86, 20).build();
            blockBillTab = Button.builder(Component.literal("BlockBill"), ignored -> showHub())
                    .bounds(0, 0, 86, 20).build();
        }

        private RecipeBookComponent<?> recipeBook() {
            return host instanceof RecipeBookScreenAccessor recipe ? recipe.blockbill$getRecipeBookComponent() : null;
        }

        private void initialized(Minecraft client) {
            vanillaWidgets.clear();
            vanillaWidgets.addAll(Screens.getWidgets(host));
            vanillaWidgets.remove(inventoryTab);
            vanillaWidgets.remove(blockBillTab);
            vanillaWidgets.removeAll(mirroredWidgets);
            mirroredWidgets.clear();
            recipeButton = vanillaWidgets.stream().filter(ImageButton.class::isInstance).findFirst().orElse(null);
            originalLeft = inventoryLeft(host);
            if (active) {
                if (recipeButton != null) recipeButtonWasActive = recipeButton.active;
                attach(client);
            } else showVanillaWidgets();
            placeTabs();
        }

        private Button overTab(double mouseX, double mouseY) {
            for (Button tab : List.of(inventoryTab, blockBillTab)) {
                if (tab.visible && mouseX >= tab.getX() && mouseX < tab.getX() + tab.getWidth()
                        && mouseY >= tab.getY() && mouseY < tab.getY() + tab.getHeight()) return tab;
            }
            return null;
        }

        private boolean overPanel(double mouseX, double mouseY) {
            return mouseX >= panelLeft && mouseX < panelLeft + panelWidth
                    && mouseY >= panelTop && mouseY < panelTop + panelHeight;
        }

        private boolean clickTab(MouseButtonEvent event) {
            Button tab = overTab(event.x(), event.y());
            if (tab == null) return false;
            ownedPointer = true;
            breakNativeDoubleClick();
            if (tab.active) tab.mouseClicked(event, false);
            return true;
        }

        private void breakNativeDoubleClick() {
            // A click on our UI interrupts vanilla's consecutive-slot click sequence.
            // Otherwise a fast slot -> tab -> slot sequence becomes PICKUP_ALL instead of placing the stack.
            layout(host).blockbill$setLastClickSlot(null);
        }

        private Slot inventorySlot(double mouseX, double mouseY) {
            var player = Minecraft.getInstance().player;
            if (player == null) return null;
            for (Slot slot : ((AbstractContainerScreen<?>) host).getMenu().slots) {
                if (slot.container != player.getInventory() || !slot.isActive()) continue;
                int x = inventoryLeft(host) + slot.x;
                int y = inventoryTop(host) + slot.y;
                if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) return slot;
            }
            return null;
        }

        private void extractSelectedSlot(GuiGraphicsExtractor graphics) {
            int selected = hub.selectedInventorySlot();
            var player = Minecraft.getInstance().player;
            if (selected < 0 || player == null) return;
            for (Slot slot : ((AbstractContainerScreen<?>) host).getMenu().slots) {
                if (slot.container != player.getInventory() || slot.getContainerSlot() != selected || !slot.isActive()) continue;
                int x = inventoryLeft(host) + slot.x - 1;
                int y = inventoryTop(host) + slot.y - 1;
                int color = 0xFF64C76D;
                graphics.fill(x, y, x + 18, y + 1, color);
                graphics.fill(x, y + 17, x + 18, y + 18, color);
                graphics.fill(x, y + 1, x + 1, y + 17, color);
                graphics.fill(x + 17, y + 1, x + 18, y + 17, color);
                return;
            }
        }

        private void showHub() {
            Minecraft client = Minecraft.getInstance();
            if (active || client.player == null || !client.player.inventoryMenu.getCarried().isEmpty()) return;
            active = true;
            host.clearFocus();
            host.setDragging(false);
            RecipeBookComponent<?> book = recipeBook();
            recipeWasVisible = book != null && book.isVisible();
            if (recipeButton != null) recipeButtonWasActive = recipeButton.active;
            originalLeft = inventoryLeft(host);
            if (hub == null) hub = new BlockBillHubScreen(host);
            attach(client);
        }

        private void moveInventory(int newLeft) {
            int delta = newLeft - inventoryLeft(host);
            layout(host).blockbill$setLeftPos(newLeft);
            for (AbstractWidget widget : vanillaWidgets) widget.setX(widget.getX() + delta);
        }

        private void attach(Minecraft client) {
            RecipeBookComponent<?> book = recipeBook();
            if (book != null && book.isVisible()) book.toggleVisibility();
            if (recipeButton != null) recipeButton.active = false;
            int nativeWidth = inventoryWidth(host);
            int totalWidth = Math.min(host.width - 8, nativeWidth + 6 + 360);
            moveInventory((host.width - totalWidth) / 2);
            panelLeft = inventoryLeft(host) + nativeWidth + 6;
            panelWidth = Math.min(360, host.width - panelLeft - 4);
            panelTop = 14;
            panelHeight = host.height - 20;
            hub.attach(client, host.width, host.height, panelLeft, panelTop, panelWidth, panelHeight);
            placeTabs();
            syncWidgets();
        }

        private void showInventory() {
            if (!active) return;
            active = false;
            hub.removed();
            hub.clearFocus();
            hub.setDragging(false);
            host.clearFocus();
            host.setDragging(false);
            RecipeBookComponent<?> book = recipeBook();
            if (book != null && book.isVisible() != recipeWasVisible) book.toggleVisibility();
            int restoredLeft = book == null ? originalLeft : book.updateScreenPosition(host.width, inventoryWidth(host));
            moveInventory(restoredLeft);
            if (recipeButton != null) recipeButton.active = recipeButtonWasActive;
            showVanillaWidgets();
            placeTabs();
        }

        private void showVanillaWidgets() {
            List<AbstractWidget> widgets = Screens.getWidgets(host);
            widgets.removeAll(mirroredWidgets);
            mirroredWidgets.clear();
            if (!widgets.contains(inventoryTab)) widgets.add(inventoryTab);
            if (!widgets.contains(blockBillTab)) widgets.add(blockBillTab);
        }

        private void syncWidgets() {
            List<AbstractWidget> widgets = Screens.getWidgets(host);
            widgets.removeAll(mirroredWidgets);
            mirroredWidgets.clear();
            mirroredWidgets.addAll(hub.widgets());
            widgets.addAll(mirroredWidgets);
            if (!widgets.contains(inventoryTab)) widgets.add(inventoryTab);
            if (!widgets.contains(blockBillTab)) widgets.add(blockBillTab);
            placeTabs();
        }

        private void placeTabs() {
            int left = inventoryLeft(host);
            // Creative categories already occupy the first 28 pixels above their frame.
            int top = inventoryTop(host) - (host instanceof InventoryScreen ? 21 : 49);
            int tabWidth = Math.min(86, (inventoryWidth(host) - 2) / 2);
            inventoryTab.setX(left);
            inventoryTab.setY(Math.max(1, top));
            inventoryTab.setWidth(tabWidth);
            blockBillTab.setX(left + tabWidth + 2);
            blockBillTab.setY(Math.max(1, top));
            blockBillTab.setWidth(tabWidth);
            inventoryTab.active = active;
            boolean cursorEmpty = Minecraft.getInstance().player != null
                    && Minecraft.getInstance().player.inventoryMenu.getCarried().isEmpty();
            blockBillTab.active = !active && cursorEmpty;
            blockBillTab.setTooltip(Tooltip.create(Component.translatable(cursorEmpty ? "hub.open_hint" : "hub.cursor_hint")));
        }

        private void tick() {
            if (active) {
                hub.tick();
                syncWidgets();
            } else placeTabs();
        }

        private void dispose() {
            if (hub != null) hub.removed();
            if (active && Minecraft.getInstance().player != null) {
                RecipeBookComponent<?> book = recipeBook();
                if (book != null && book.isVisible() != recipeWasVisible) book.toggleVisibility();
            }
            active = false;
        }
    }
}
