package dev.blockbill.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

/** A compact sidebar controller; the actual screen and slots remain vanilla inventory. */
public final class BlockBillHubScreen extends Screen {
    private enum Tab { OVERVIEW, PLANS, SELL, AD, RULES }
    private record Text(Component value, int x, int y, int maxWidth) { }
    private record Line(FormattedCharSequence value, int x, int y) { }
    private record Icon(ItemStack stack, int x, int y) { }
    private record RulePage(String section, int part, List<FormattedCharSequence> lines) { }
    private static final List<String> RULE_SECTIONS = List.of("start", "services", "workshop", "trade", "fishing",
            "storage", "travel", "bundles", "plans", "exchange", "ads", "commands", "autorenew", "time", "safety");
    private final Screen parent;
    private final List<Text> texts = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private final List<Icon> icons = new ArrayList<>();
    private final List<RulePage> rulePages = new ArrayList<>();
    private HubClient.State state;
    private Tab tab = Tab.OVERVIEW;
    private int x, y, inner, contentTop, page, pages = 1, heartbeat, messageTicks;
    private int selectedSlot = -1, confirmedAmount;
    private long confirmedRevision = -1;
    private boolean catalog, opened, pending, readingRules;
    private String localMessage = "hub.loading", catalogSearch = "";

    public BlockBillHubScreen(Screen parent) { super(Component.literal("BlockBill")); this.parent = parent; }

    public void attach(Minecraft client, int screenWidth, int screenHeight, int left, int top, int panelWidth, int panelHeight) {
        RulePage previous = readingRules && !rulePages.isEmpty() ? rulePages.get(Math.min(page, rulePages.size() - 1)) : null;
        x = left; y = top;
        init(panelWidth, panelHeight);
        if (previous != null) {
            page = 0;
            for (int i = 0; i < rulePages.size(); i++) {
                RulePage candidate = rulePages.get(i);
                if (candidate.section().equals(previous.section()) && candidate.part() <= previous.part()) page = i;
            }
            rebuild();
        }
    }

    public List<AbstractWidget> widgets() { return Screens.getWidgets(this); }
    public boolean isReady() { return state != null && !pending; }
    public boolean sellingItems() {
        return tab == Tab.SELL && !catalog && state != null && minecraft.player != null
                && minecraft.player.inventoryMenu.getCarried().isEmpty();
    }
    public int selectedInventorySlot() { return sellingItems() && selected() != null ? selectedSlot : -1; }
    public void selectInventorySlot(int slot) {
        if (!sellingItems() || pending) return;
        selectedSlot = slot;
        clearConfirmation();
        rebuild();
    }

    @Override protected void init() {
        if (!opened) { opened = true; pending = true; request("OPEN", "", -1, 0); }
        else if (tab == Tab.SELL && catalog) { page = 0; request("CATALOG", catalogSearch, 0, 0); }
        rebuild();
    }
    public void accept(HubClient.State next) {
        if (state == null || state.revision() != next.revision()) clearConfirmation();
        state = next; pending = false;
        if (next.message() != null && !next.message().isEmpty()) { localMessage = next.message(); messageTicks = 100; }
        else if (messageTicks == 0) localMessage = "";
        rebuild();
    }
    public void unavailable() { pending = false; state = null; localMessage = "hub.unavailable"; rebuild(); }
    private void request(String action, String value, int slot, int amount) {
        if (!HubClient.send(action, value, slot, amount, state == null ? -1 : state.revision())) {
            localMessage = "hub.unavailable"; pending = false;
        }
    }
    private void mutate(String action, String value, int slot, int amount) {
        if (!canAct()) return;
        pending = true; clearConfirmation(); request(action, value, slot, amount); rebuild();
    }
    @Override public void tick() {
        if (messageTicks > 0 && --messageTicks == 0) { localMessage = ""; rebuild(); }
        if (++heartbeat >= 20) { heartbeat = 0; request("REFRESH", "", -1, 0); }
    }

    private void rebuild() {
        boolean searchFocused = getFocused() instanceof EditBox;
        int searchCursor = searchFocused ? ((EditBox) getFocused()).getCursorPosition() : 0;
        clearWidgets(); texts.clear(); lines.clear(); icons.clear();
        inner = width - 8;
        text(title, 4, 6, inner);
        text(tr("hub.balance", state == null ? "—" : state.balance()), 4, 18, inner);
        button(tr("hub.credits"), 4, 31, inner, () -> { catalog = false; select(Tab.SELL); });
        int columns = width >= 250 ? 5 : 3;
        int tabWidth = inner / columns;
        for (Tab candidate : Tab.values()) {
            Button item = button(tr("hub.tab." + candidate.name().toLowerCase(Locale.ROOT)),
                    4 + candidate.ordinal() % columns * tabWidth, 54 + candidate.ordinal() / columns * 21,
                    tabWidth - 1, () -> select(candidate));
            item.active = candidate != tab;
        }
        contentTop = columns == 5 ? 79 : 100;
        pages = 1;
        if (state != null) {
            switch (tab) {
                case OVERVIEW -> overview();
                case PLANS -> plans();
                case SELL -> sell(searchFocused, searchCursor);
                case AD -> advertisement();
                case RULES -> rules();
            }
        } else paragraph(tr(localMessage), contentTop, height - 45);
        String notice = !localMessage.isEmpty() ? localMessage : state != null && !state.survival() ? "hub.paused" : "";
        if (!notice.isEmpty() && state != null) text(tr(notice), 4, height - 39, inner);
        Button previous = button(Component.literal("<"), 4, height - 24, 18, () -> changePage(-1));
        previous.active = page > 0 && !pending;
        text(tr("hub.page", page + 1, pages), 25, height - 18, Math.max(24, inner - 65));
        Button next = button(Component.literal(">"), width - 41, height - 24, 18, () -> changePage(1));
        next.active = page + 1 < pages && !pending;
        button(Component.literal("×"), width - 21, height - 24, 17, this::onClose)
                .setTooltip(Tooltip.create(tr("hub.close")));
        InventoryTabs.syncWidgets(parent);
    }

    private void overview() {
        text(tr("hub.free"), 4, contentTop, inner);
        text(tr("hub.bonus", time(state.trialTicks()), time(state.adTicks())), 4, contentTop + 12, inner);
        int rows = Math.max(1, (height - 46 - contentTop - 24) / 36);
        paginate(state.services().size(), rows);
        for (int i = page * rows; i < Math.min(state.services().size(), (page + 1) * rows); i++) {
            HubClient.Service service = state.services().get(i);
            int top = contentTop + 24 + (i - page * rows) * 36;
            Component label = tr("hub.service_status", serviceName(service.id()), service.access() ? tr("hub.active") : tr("hub.locked"), time(service.remainingTicks()));
            text(label, 4, top, inner);
            int half = (inner - 2) / 2;
            Button buy = button(tr("hub.price", service.price()), 4, top + 11, half,
                    () -> mutate("BUY", service.id(), -1, 0));
            buy.active = canAct();
            buy.setTooltip(Tooltip.create(tr("hub.service_buy", serviceName(service.id()), service.price()).append("\n").append(tr("blockbill.rule." + service.id()))));
            Button auto = button(tr(service.autoRenew() ? "hub.auto_on" : "hub.auto_off"), 6 + half, top + 11, inner - half - 2,
                    () -> mutate("AUTO", service.id(), -1, service.autoRenew() ? 0 : 1));
            auto.active = canAct();
            auto.setTooltip(Tooltip.create(tr("hub.auto_hint", serviceName(service.id()), service.price())));
        }
    }
    private void plans() {
        text(tr("hub.plans_hint"), 4, contentTop, inner);
        int rows = Math.max(1, (height - 46 - contentTop - 14) / 49);
        paginate(state.plans().size(), rows);
        for (int i = page * rows; i < Math.min(state.plans().size(), (page + 1) * rows); i++) {
            var plan = state.plans().get(i); int top = contentTop + 14 + (i - page * rows) * 49;
            Component name = tr("blockbill.bundle." + plan.id());
            text(tr("hub.plan", name, plan.minutes()), 4, top, inner);
            Component included = Component.empty();
            for (String id : plan.services()) included = included.copy().append(included.getString().isEmpty() ? "" : ", ").append(serviceName(id));
            text(included, 4, top + 11, inner);
            Button buy = button(tr("hub.price", plan.price()), 4, top + 23, inner, () -> mutate("BUY", plan.id(), -1, 0));
            buy.active = canAct();
            buy.setTooltip(Tooltip.create(tr("hub.plan_buy", name, plan.price(), plan.minutes()).append("\n").append(included)));
        }
    }
    private int catalogColumns() { return Math.max(1, inner / 22); }
    private int catalogRows() { return Math.max(1, (height - 46 - contentTop - 46) / 22); }
    private void sell(boolean focused, int cursor) {
        button(tr(catalog ? "hub.inventory" : "hub.catalog"), 4, contentTop, inner, () -> {
            catalog = !catalog; page = 0; clearConfirmation();
            if (catalog) request("CATALOG", catalogSearch, 0, 0);
            rebuild();
        });
        if (catalog) {
            EditBox search = addRenderableWidget(new EditBox(font, x + 4, y + contentTop + 23, inner - 27, 18, tr("hub.search")));
            search.setMaxLength(64); search.setValue(catalogSearch); search.setHint(tr("hub.search_hint"));
            search.setResponder(value -> catalogSearch = value);
            if (focused) { setFocused(search); search.setCursorPosition(cursor); }
            button(tr("hub.search"), width - 29, contentTop + 22, 25, () -> { page = 0; request("CATALOG", catalogSearch, 0, 0); });
            int perPage = catalogColumns() * catalogRows();
            int localPages = (45 + perPage - 1) / perPage;
            pages = Math.max(1, state.catalogPages() * localPages);
            if (state.catalogPage() == state.catalogPages() - 1) pages = Math.max(1, state.catalogPage() * localPages + (state.catalog().size() + perPage - 1) / perPage);
            page = Math.clamp(page, 0, pages - 1);
            if (state.catalogPage() != page / localPages) return;
            int first = page % localPages * perPage;
            for (int i = first; i < Math.min(state.catalog().size(), first + perPage); i++) {
                var item = state.catalog().get(i); ItemStack icon = stack(item.item()); int index = i - first;
                int left = 4 + index % catalogColumns() * 22, top = contentTop + 45 + index / catalogColumns() * 22;
                Component detail = tr("hub.item_price", icon.getHoverName(), money(item.unitPriceCents(), 1));
                Button widget = button(Component.empty(), left, top, 21, () -> { });
                widget.setTooltip(Tooltip.create(detail));
                icons.add(new Icon(icon, left + 2, top + 2));
            }
            return;
        }
        var selected = selected();
        if (selected == null) { paragraph(tr("hub.select_native_item"), contentTop + 25, height - 46); return; }
        ItemStack icon = stack(selected.item()); icon.setCount(selected.count());
        icons.add(new Icon(icon, 5, contentTop + 25));
        text(icon.getHoverName(), 25, contentTop + 24, inner - 21);
        text(tr("hub.price", money(selected.unitPriceCents(), 1)), 25, contentTop + 36, inner - 21);
        if (confirmedAmount > 0) {
            text(tr("hub.confirm_short", confirmedAmount, money(selected.unitPriceCents(), confirmedAmount)), 4, contentTop + 49, inner);
            Button confirm = button(tr("hub.confirm"), 4, contentTop + 60, inner - 22, () -> confirmSale(selected, confirmedAmount));
            confirm.active = canAct();
            button(Component.literal("×"), width - 24, contentTop + 60, 20, () -> { clearConfirmation(); rebuild(); });
        } else {
            text(tr("hub.item_count", selected.count()), 4, contentTop + 49, inner);
            int half = (inner - 2) / 2;
            Button one = button(tr("hub.sell_one"), 4, contentTop + 60, half, () -> confirmSale(selected, 1));
            Button all = button(tr("hub.sell_stack"), 6 + half, contentTop + 60, inner - half - 2, () -> confirmSale(selected, selected.count()));
            one.active = all.active = canAct() && (selected.blocked() == null || selected.blocked().isEmpty());
            if (selected.blocked() != null && !selected.blocked().isEmpty()) {
                one.setTooltip(Tooltip.create(tr(selected.blocked()))); all.setTooltip(Tooltip.create(tr(selected.blocked())));
            }
        }
    }
    private HubClient.InventoryItem selected() { return state.inventory().stream().filter(item -> item.slot() == selectedSlot && item.count() > 0).findFirst().orElse(null); }
    private void confirmSale(HubClient.InventoryItem item, int amount) {
        if (!canAct()) return;
        if (confirmedAmount == amount && confirmedRevision == state.revision()) mutate("SELL", "", item.slot(), amount);
        else { confirmedAmount = amount; confirmedRevision = state.revision(); rebuild(); }
    }
    private void advertisement() {
        Component creative = tr(state.adWatching() ? "hub.ad_brand" : "hub.ad_description");
        paragraph(creative, contentTop, height - 85);
        text(state.adWatching() ? tr(state.adWatchTicks() > 0 ? "hub.ad_wait" : "hub.ad_ready", time(state.adWatchTicks()))
                : tr("hub.ad_cooldown", time(state.adCooldownTicks())), 4, height - 79, inner);
        Button watch = button(tr(state.adWatching() ? "hub.ad_claim" : "hub.ad_start"), 4, height - 65, inner - 46,
                () -> mutate(state.adWatching() ? "AD_CLAIM" : "AD_START", "", -1, 0));
        watch.active = canAct() && (state.adWatching() ? state.adWatchTicks() <= 0 : state.adCooldownTicks() <= 0);
        Button cancel = button(tr("hub.cancel"), width - 48, height - 65, 44, () -> request("AD_CANCEL", "", -1, 0));
        cancel.active = state.adWatching() && !pending;
    }
    private void rules() {
        if (!readingRules) {
            paragraph(tr("hub.rules_summary"), contentTop, height - 73);
            button(tr("hub.read_rules"), 4, height - 68, inner, () -> { readingRules = true; page = 0; rebuild(); });
            return;
        }
        rulePages.clear();
        int count = Math.max(1, (height - 46 - contentTop - 35) / 11);
        for (String section : RULE_SECTIONS) {
            var wrapped = font.split(tr("guide.blockbill." + section + ".body"), inner);
            for (int start = 0; start < wrapped.size(); start += count) rulePages.add(new RulePage(section, start / count,
                    List.copyOf(wrapped.subList(start, Math.min(wrapped.size(), start + count)))));
        }
        pages = Math.max(1, rulePages.size()); page = Math.clamp(page, 0, pages - 1);
        RulePage current = rulePages.get(page);
        button(tr("hub.rules_back"), 4, contentTop, inner, () -> { readingRules = false; page = 0; rebuild(); });
        text(tr("guide.blockbill." + current.section() + ".title"), 4, contentTop + 23, inner);
        int top = contentTop + 35;
        for (var line : current.lines()) { lines.add(new Line(line, 4, top)); top += 11; }
    }
    @Override public Component getNarrationMessage() {
        if (!readingRules || tab != Tab.RULES || rulePages.isEmpty()) return title;
        String section = rulePages.get(Math.min(page, rulePages.size() - 1)).section();
        return tr("guide.blockbill.title").append(". ").append(tr("guide.blockbill." + section + ".title"))
                .append(". ").append(tr("guide.blockbill." + section + ".body"));
    }
    private void paragraph(Component value, int top, int bottom) {
        for (var line : font.split(value, inner)) { if (top + 10 > bottom) break; lines.add(new Line(line, 4, top)); top += 11; }
    }
    private void text(Component value, int left, int top, int maxWidth) { texts.add(new Text(value, left, top, maxWidth)); }
    private Button button(Component value, int left, int top, int buttonWidth, Runnable action) {
        return addRenderableWidget(Button.builder(value, ignored -> action.run()).bounds(x + left, y + top, buttonWidth, 20)
                .tooltip(Tooltip.create(value)).build());
    }
    private void paginate(int count, int perPage) { pages = Math.max(1, (count + perPage - 1) / perPage); page = Math.clamp(page, 0, pages - 1); }
    private void changePage(int direction) {
        page = Math.clamp(page + direction, 0, pages - 1); clearConfirmation();
        if (tab == Tab.SELL && catalog) {
            int serverPage = page / ((45 + catalogColumns() * catalogRows() - 1) / (catalogColumns() * catalogRows()));
            if (state.catalogPage() != serverPage) request("CATALOG", catalogSearch, serverPage, 0);
        }
        rebuild();
    }
    private void select(Tab target) {
        if (tab == Tab.AD && target != Tab.AD) request("AD_CANCEL", "", -1, 0);
        if (target == Tab.RULES && tab != Tab.RULES) readingRules = false;
        tab = target; page = 0; clearConfirmation();
        if (tab == Tab.SELL && catalog) request("CATALOG", catalogSearch, 0, 0);
        rebuild();
    }
    private void clearConfirmation() { confirmedAmount = 0; confirmedRevision = -1; }
    private boolean canAct() { return state != null && state.survival() && !pending; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(x, y, x + width, y + height, 0xFF373737);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, 0xFFC6C6C6);
        for (Text text : texts) {
            graphics.text(font, font.plainSubstrByWidth(text.value().getString(), text.maxWidth()), x + text.x(), y + text.y(), 0xFF303030, false);
            if (mouseX >= x + text.x() && mouseX < x + text.x() + text.maxWidth() && mouseY >= y + text.y() && mouseY < y + text.y() + 10)
                graphics.setTooltipForNextFrame(font, List.of(text.value()), java.util.Optional.empty(), mouseX, mouseY);
        }
        for (Line line : lines) graphics.text(font, line.value(), x + line.x(), y + line.y(), 0xFF303030, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        for (Icon icon : icons) { graphics.item(icon.stack(), x + icon.x(), y + icon.y()); graphics.itemDecorations(font, icon.stack(), x + icon.x(), y + icon.y()); }
    }
    @Override public void removed() { if (opened) request("CLOSE", "", -1, 0); opened = false; }
    @Override public void onClose() { InventoryTabs.showInventory(parent); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    private static Component serviceName(String id) { return tr("blockbill.service." + id); }
    private static net.minecraft.network.chat.MutableComponent tr(String key, Object... args) { return Component.translatable(key, args); }
    private static String time(long ticks) { long value = Math.max(0, ticks); long seconds = value / 20 + (value % 20 == 0 ? 0 : 1); return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60); }
    private static String money(long cents, int count) { return java.math.BigDecimal.valueOf(cents).multiply(java.math.BigDecimal.valueOf(count)).movePointLeft(2).toPlainString(); }
    private static ItemStack stack(String value) { Identifier id = Identifier.tryParse(value); return id == null ? ItemStack.EMPTY : new ItemStack(BuiltInRegistries.ITEM.getValue(id)); }
}
