package dev.blockbill.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionBundle;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.domain.TransactionResult;
import dev.blockbill.infrastructure.Accounts;
import dev.blockbill.market.ItemPriceCatalog;
import dev.blockbill.market.MarketSales;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Snapshot/revision protocol; the authenticated sender is the sole possible account owner. */
public final class HubServer {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, Integer> LAST_ACCESS = new HashMap<>();
    private static final Set<String> MUTATIONS = Set.of("BUY", "AUTO", "SELL", "AD_START", "AD_CLAIM");
    private static long sequence;

    private HubServer() { }

    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(HubRequest.TYPE, HubRequest.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(HubState.TYPE, HubState.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AccessState.TYPE, AccessState.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HubRequest.TYPE, (request, context) -> {
            HubState state = handle(context.player(), request);
            if (state != null && ServerPlayNetworking.canSend(context.player(), HubState.TYPE)) {
                ServerPlayNetworking.send(context.player(), state);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ItemPriceCatalog.validateRegistry());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SESSIONS.remove(handler.player.getUUID()); LAST_ACCESS.remove(handler.player.getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { SESSIONS.clear(); LAST_ACCESS.clear(); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 20 != 0) return;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Session session = SESSIONS.get(player.getUUID());
                if (session != null && server.getTickCount() - session.lastHeartbeat > 100) SESSIONS.remove(player.getUUID());
                if (!ServerPlayNetworking.canSend(player, AccessState.TYPE)) continue;
                var account = Accounts.get(server).account(player.getUUID());
                int mask = 0;
                for (ServiceType service : ServiceType.values()) {
                    if (player.gameMode() != GameType.SURVIVAL || SubscriptionEngine.hasAccess(account, service)) mask |= 1 << service.ordinal();
                }
                Integer previous = LAST_ACCESS.put(player.getUUID(), mask);
                if (previous == null || previous != mask) ServerPlayNetworking.send(player, new AccessState(mask));
            }
        });
    }

    /** Also exercised directly by server GameTests, without manufacturing trusted client state. */
    public static HubState handle(ServerPlayer player, HubRequest request) {
        long tick = player.level().getServer().getTickCount();
        if (request.action().equals("CLOSE")) { SESSIONS.remove(player.getUUID()); return null; }
        Session session = SESSIONS.get(player.getUUID());
        if (request.action().equals("OPEN")) {
            if (session == null) { session = new Session(); SESSIONS.put(player.getUUID(), session); }
            session.open = true;
        }
        if (session == null || !session.open) return null;
        if (tick - session.rateWindow >= 20 || tick < session.rateWindow) { session.rateWindow = tick; session.requests = 0; }
        if (++session.requests > 30) return null;
        session.lastHeartbeat = tick;
        PlayerAccount account = Accounts.get(player.level().getServer()).account(player.getUUID());
        refreshQuote(player, session);
        String message = "";
        if (MUTATIONS.contains(request.action())) {
            if (request.revision() != session.revision) return state(player, account, session, "hub.error.stale");
            session.revision = ++sequence; // Consume intent even on failure; replays can never spend or reward twice.
            if (player.gameMode() != GameType.SURVIVAL) return state(player, account, session, "hub.error.survival");
        }
        switch (request.action()) {
            case "OPEN", "REFRESH" -> { }
            case "BUY" -> {
                ServiceType service = ServiceType.fromId(request.value()).orElse(null);
                SubscriptionBundle bundle = null;
                for (SubscriptionBundle candidate : SubscriptionBundle.values()) if (candidate.id().equals(request.value())) bundle = candidate;
                TransactionResult result = service != null ? SubscriptionEngine.purchase(account, service)
                        : bundle != null ? SubscriptionEngine.purchaseBundle(account, bundle) : TransactionResult.INVALID_AMOUNT;
                message = result == TransactionResult.SUCCESS ? "hub.buy.success" : error(result);
            }
            case "AUTO" -> {
                ServiceType service = ServiceType.fromId(request.value()).orElse(null);
                if (service == null || (request.amount() != 0 && request.amount() != 1)) message = "hub.error.invalid";
                else { account.setAutoRenew(service, request.amount() == 1); message = "hub.auto.success"; }
            }
            case "SELL" -> {
                if (request.slot() < 0 || request.slot() >= session.quote.size()) message = "hub.error.invalid";
                else message = MarketSales.sell(player, account, request.slot(), request.amount(), session.quote.get(request.slot()));
            }
            case "AD_START" -> {
                if (account.adCooldownTicks() > 0) message = "hub.ad.cooldown";
                else if (session.adStart < 0) { session.adStart = account.activePlayTicks(); message = "hub.ad.watching"; }
            }
            case "AD_CLAIM" -> {
                if (session.adStart < 0 || account.activePlayTicks() - session.adStart < 100) message = "hub.ad.incomplete";
                else if (!account.grantAdvertisement()) message = "hub.ad.cooldown";
                else { session.adStart = -1; message = "hub.ad.granted"; }
            }
            case "AD_CANCEL" -> session.adStart = -1;
            case "CATALOG" -> {
                session.search = request.value().strip().toLowerCase(Locale.ROOT);
                String search = session.search;
                session.catalog = ItemPriceCatalog.prices().entrySet().stream()
                        .filter(e -> e.getKey().contains(search)).sorted(Map.Entry.comparingByKey()).toList();
                session.page = Math.clamp(request.slot(), 0, Math.max(0, (session.catalog.size() - 1) / 45));
            }
            default -> message = "hub.error.invalid";
        }
        if (MUTATIONS.contains(request.action())) Accounts.get(player.level().getServer()).setDirty();
        refreshQuote(player, session);
        return state(player, account, session, message);
    }

    private static String error(TransactionResult result) {
        return switch (result) {
            case INSUFFICIENT_CREDITS -> "hub.error.funds";
            case OVERFLOW -> "hub.error.overflow";
            case NOT_SURVIVAL -> "hub.error.survival";
            default -> "hub.error.invalid";
        };
    }

    private static void refreshQuote(ServerPlayer player, Session session) {
        var inventory = player.getInventory();
        boolean changed = session.quote.size() != inventory.getContainerSize();
        if (!changed) for (int i = 0; i < session.quote.size(); i++) {
            if (!ItemStack.matches(session.quote.get(i), inventory.getItem(i))) { changed = true; break; }
        }
        if (changed) {
            session.quote.clear();
            for (int i = 0; i < inventory.getContainerSize(); i++) session.quote.add(inventory.getItem(i).copy());
            session.revision = ++sequence;
        }
    }

    private static HubState state(ServerPlayer player, PlayerAccount account, Session session, String message) {
        JsonObject out = new JsonObject();
        out.addProperty("revision", session.revision); out.addProperty("balance", account.formattedCredits());
        out.addProperty("survival", player.gameMode() == GameType.SURVIVAL);
        out.addProperty("trialTicks", SubscriptionEngine.trialRemainingTicks(account));
        out.addProperty("adTicks", account.adRemainingTicks()); out.addProperty("adCooldownTicks", account.adCooldownTicks());
        out.addProperty("adWatching", session.adStart >= 0);
        out.addProperty("adWatchTicks", session.adStart < 0 ? 100 : Math.max(0, 100 - (account.activePlayTicks() - session.adStart)));
        out.addProperty("message", message);
        JsonArray services = new JsonArray();
        for (ServiceType service : ServiceType.values()) {
            JsonObject row = new JsonObject(); row.addProperty("id", service.id()); row.addProperty("price", service.price());
            row.addProperty("remainingTicks", SubscriptionEngine.remainingTicks(account, service));
            row.addProperty("autoRenew", account.autoRenew(service)); row.addProperty("access", SubscriptionEngine.hasAccess(account, service));
            services.add(row);
        }
        out.add("services", services);
        JsonArray plans = new JsonArray();
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            JsonObject row = new JsonObject(); row.addProperty("id", bundle.id()); row.addProperty("price", bundle.price());
            row.addProperty("minutes", bundle.durationMinutes()); JsonArray included = new JsonArray();
            for (ServiceType service : bundle.services()) included.add(service.id()); row.add("services", included); plans.add(row);
        }
        out.add("plans", plans);
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < session.quote.size(); slot++) {
            ItemStack stack = session.quote.get(slot); JsonObject row = new JsonObject();
            row.addProperty("slot", slot); row.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            row.addProperty("count", stack.getCount()); row.addProperty("unitPriceCents", stack.isEmpty() ? 0 : ItemPriceCatalog.unitPriceCents(stack));
            row.addProperty("blocked", stack.isEmpty() ? "" : ItemPriceCatalog.saleBlockedReason(stack).orElse("")); inventory.add(row);
        }
        out.add("inventory", inventory);
        if (session.catalog == null) session.catalog = ItemPriceCatalog.prices().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        JsonArray catalog = new JsonArray();
        for (int i = session.page * 45; i < Math.min(session.page * 45 + 45, session.catalog.size()); i++) {
            var entry = session.catalog.get(i); JsonObject row = new JsonObject(); row.addProperty("item", entry.getKey());
            row.addProperty("unitPriceCents", entry.getValue()); catalog.add(row);
        }
        out.add("catalog", catalog); out.addProperty("catalogPage", session.page);
        out.addProperty("catalogPages", Math.max(1, (session.catalog.size() + 44) / 45)); out.addProperty("catalogSearch", session.search);
        return new HubState(out.toString());
    }

    private static final class Session {
        private boolean open;
        private long revision;
        private final List<ItemStack> quote = new ArrayList<>();
        private long adStart = -1;
        private long lastHeartbeat;
        private long rateWindow;
        private int requests;
        private String search = "";
        private int page;
        private List<Map.Entry<String, Long>> catalog;
    }
}
