package dev.blockbill.fabric;

import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SprintTracker;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.domain.TransactionResult;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;

import java.util.HashMap;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class BlockBillEvents {
    private final Map<UUID, Session> sessions = new HashMap<>();

    public void register() {
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            sessions.remove(handler.player.getUUID());
            Accounts.get(server).setDirty();
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> sessions.remove(newPlayer.getUUID()));
        ServerLifecycleEvents.SERVER_STARTED.register(Accounts::get);
        ServerLifecycleEvents.SERVER_STOPPING.register(Accounts::markLoadedDirty);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            sessions.clear();
            Accounts.forget(server);
        });
        EntitySleepEvents.ALLOW_SLEEPING.register((player, pos) -> {
            if (player instanceof ServerPlayer serverPlayer && serverPlayer.gameMode() == GameType.SURVIVAL
                    && !SubscriptionEngine.hasAccess(Accounts.get(serverPlayer.level().getServer()).account(player.getUUID()), ServiceType.SLEEP)) {
                return new Player.BedSleepingProblem(Component.translatable("blockbill.sleep.denied"));
            }
            return null;
        });
    }

    private void tick(MinecraftServer server) {
        Accounts accounts = Accounts.get(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.gameMode() != GameType.SURVIVAL) {
                Session previous = sessions.get(player.getUUID());
                if (previous != null) {
                    previous.sprint.reset();
                    previous.inMine = false;
                    previous.pending.clear();
                }
                continue;
            }
            PlayerAccount account = accounts.account(player.getUUID());
            Session session = sessions.computeIfAbsent(player.getUUID(), ignored -> new Session());
            if (!session.welcomed) {
                player.sendSystemMessage(Component.translatable(account.activePlayTicks() == 0 ? "blockbill.welcome.trial" : "blockbill.welcome.back"));
                session.welcomed = true;
                session.nextNotice = noticeAfter(account.activePlayTicks());
            }
            long before = account.activePlayTicks();
            account.tick(true);
            long now = account.activePlayTicks();
            Map<ServiceType, TransactionResult> renewals = SubscriptionEngine.renewDue(account);
            if (!renewals.isEmpty()) {
                accounts.setDirty();
                session.autoResults.putAll(renewals);
            }
            trackExpiry(session, null, account.trialEndTick(), before, now);
            for (ServiceType service : ServiceType.values()) {
                if (account.paidUntil(service) > account.trialEndTick()) {
                    trackExpiry(session, service, account.paidUntil(service), before, now);
                }
            }
            if (now % 20 == 0) {
                accounts.setDirty();
                boolean inMine = player.getY() < 40 && !SubscriptionEngine.hasAccess(account, ServiceType.MINE);
                if (inMine) {
                    applyIfAbsent(player, MobEffects.MINING_FATIGUE, 60);
                    if (!session.inMine) session.pending.put("mine",
                            new Notice(NoticeKind.MINE, ServiceType.MINE, 0, Component.translatable("blockbill.mine.denied")));
                }
                session.inMine = inMine;
            }
            if (session.sprint.tick(true, player.isSprinting(), SubscriptionEngine.hasAccess(account, ServiceType.SPRINT))) {
                applyIfAbsent(player, MobEffects.SLOWNESS, SprintTracker.TTL_TICKS);
                session.pending.put("sprint",
                        new Notice(NoticeKind.SPRINT, ServiceType.SPRINT, 0, Component.translatable("blockbill.sprint.denied")));
            }
            session.pending.values().removeIf(notice -> !notice.isRelevant(player, account));
            if (now >= session.nextNotice && (!session.pending.isEmpty() || !session.autoResults.isEmpty())) {
                player.sendSystemMessage(session.autoResults.isEmpty() ? takeNextMessage(session) : autoReceipt(session, account));
                session.nextNotice = noticeAfter(now);
            }
        }
    }

    private static Component autoReceipt(Session session, PlayerAccount account) {
        var paid = Component.empty();
        var stopped = Component.empty();
        long cost = 0;
        for (var result : session.autoResults.entrySet()) {
            var names = result.getValue() == TransactionResult.SUCCESS ? paid : stopped;
            if (!names.getSiblings().isEmpty()) names.append(", ");
            names.append(BlockBillCommands.name(result.getKey()));
            if (result.getValue() == TransactionResult.SUCCESS) cost += result.getKey().price();
        }
        if (paid.getSiblings().isEmpty()) paid.append(Component.translatable("blockbill.none"));
        if (stopped.getSiblings().isEmpty()) stopped.append(Component.translatable("blockbill.none"));
        session.autoResults.clear();
        return Component.translatable("blockbill.autorenew.summary", paid, stopped, cost, account.credits());
    }

    private static Component takeNextMessage(Session session) {
        var pending = session.pending.values().iterator();
        Notice first = pending.next();
        pending.remove();
        if (first.kind() != NoticeKind.WARNING && first.kind() != NoticeKind.EXPIRED) return first.message();

        var services = Component.empty().append(serviceName(first.service() == null ? "trial" : first.service().id()));
        while (pending.hasNext()) {
            Notice notice = pending.next();
            if (notice.kind() == first.kind()) {
                services.append(", ").append(serviceName(notice.service() == null ? "trial" : notice.service().id()));
                pending.remove();
            }
        }
        String key = first.kind() == NoticeKind.WARNING ? "blockbill.notice.warning" : "blockbill.notice.expired";
        return Component.translatable(key, services);
    }

    private static void trackExpiry(Session session, ServiceType service, long expiry, long before, long now) {
        String id = service == null ? "trial" : service.id();
        if (before < expiry - 1200 && now >= expiry - 1200) {
            session.pending.put(id + ".time", new Notice(NoticeKind.WARNING, service, expiry,
                    Component.translatable("blockbill.notice.warning", serviceName(id))));
        }
        if (before < expiry && now >= expiry) {
            session.pending.put(id + ".time", new Notice(NoticeKind.EXPIRED, service, expiry,
                    Component.translatable("blockbill.notice.expired", serviceName(id))));
        }
    }

    private static Component serviceName(String id) { return Component.translatable("blockbill.service." + id); }

    private static long noticeAfter(long now) { return now > Long.MAX_VALUE - 600 ? Long.MAX_VALUE : now + 600; }

    /** Never replace or remove a potion owned by vanilla or another mod, even at the same amplifier. */
    private static void applyIfAbsent(ServerPlayer player, Holder<MobEffect> effect, int duration) {
        if (player.getEffect(effect) == null) player.addEffect(new MobEffectInstance(effect, duration, 0, false, false, true));
    }

    private enum NoticeKind { WARNING, EXPIRED, MINE, SPRINT }

    private record Notice(NoticeKind kind, ServiceType service, long expiry, Component message) {
        boolean isRelevant(ServerPlayer player, PlayerAccount account) {
            if (kind == NoticeKind.MINE) {
                return player.getY() < 40 && !SubscriptionEngine.hasAccess(account, ServiceType.MINE);
            }
            if (kind == NoticeKind.SPRINT) {
                return !SubscriptionEngine.hasAccess(account, ServiceType.SPRINT);
            }
            long currentExpiry = service == null ? account.trialEndTick() : account.paidUntil(service);
            if (currentExpiry != expiry) return false;
            long remaining = service == null ? SubscriptionEngine.trialRemainingTicks(account)
                    : SubscriptionEngine.remainingTicks(account, service);
            return kind == NoticeKind.WARNING ? remaining > 0 && remaining <= 1200 : remaining == 0;
        }
    }

    private static final class Session {
        private final SprintTracker sprint = new SprintTracker();
        private final LinkedHashMap<String, Notice> pending = new LinkedHashMap<>();
        private final EnumMap<ServiceType, TransactionResult> autoResults = new EnumMap<>(ServiceType.class);
        private boolean welcomed;
        private boolean inMine;
        private long nextNotice;
    }
}
