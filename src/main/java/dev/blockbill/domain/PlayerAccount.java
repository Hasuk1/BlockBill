package dev.blockbill.domain;

import java.util.Objects;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Mutable only on the logical server thread; snapshots contain all persistent state. */
public final class PlayerAccount {
    private final UUID uuid;
    private long credits;
    private long activePlayTicks;
    private final long trialEndTick;
    private final EnumMap<ServiceType, Long> paidUntil = new EnumMap<>(ServiceType.class);
    private final EnumSet<ServiceType> autoRenew = EnumSet.noneOf(ServiceType.class);
    // Unknown future IDs round-trip without being activated or charged; legacy fields remain authoritative.
    private final Map<String, Long> passthroughPaidUntil = new HashMap<>();
    private final Set<String> passthroughAutoRenew = new HashSet<>();

    public PlayerAccount(UUID uuid) {
        this(new Snapshot(uuid, 0, 0, SubscriptionEngine.TRIAL_TICKS, 0, 0, 0));
    }

    private PlayerAccount(Snapshot snapshot) {
        uuid = snapshot.uuid();
        credits = snapshot.credits();
        activePlayTicks = snapshot.activePlayTicks();
        trialEndTick = snapshot.trialEndTick();
        paidUntil.put(ServiceType.MINE, snapshot.paidUntilMine());
        paidUntil.put(ServiceType.SLEEP, snapshot.paidUntilSleep());
        paidUntil.put(ServiceType.SPRINT, snapshot.paidUntilSprint());
        snapshot.extraPaidUntil().forEach((id, expiry) -> {
            ServiceType service = ServiceType.fromId(id).orElse(null);
            if (service == null || isLegacy(service)) passthroughPaidUntil.put(id, expiry);
            else paidUntil.put(service, expiry);
        });
        for (String id : snapshot.autoRenew()) {
            ServiceType service = ServiceType.fromId(id).orElse(null);
            if (service == null) passthroughAutoRenew.add(id);
            else autoRenew.add(service);
        }
    }

    public static PlayerAccount restore(Snapshot snapshot) {
        return new PlayerAccount(Objects.requireNonNull(snapshot));
    }

    public Snapshot snapshot() {
        Map<String, Long> extras = new HashMap<>(passthroughPaidUntil);
        paidUntil.forEach((service, expiry) -> {
            if (!isLegacy(service)) extras.put(service.id(), expiry);
        });
        Set<String> renew = new HashSet<>(passthroughAutoRenew);
        for (ServiceType service : autoRenew) renew.add(service.id());
        return new Snapshot(uuid, credits, activePlayTicks, trialEndTick,
                paidUntil(ServiceType.MINE), paidUntil(ServiceType.SLEEP), paidUntil(ServiceType.SPRINT), extras, renew);
    }

    public UUID uuid() { return uuid; }
    public long credits() { return credits; }
    public long activePlayTicks() { return activePlayTicks; }
    public long trialEndTick() { return trialEndTick; }

    public long paidUntil(ServiceType service) {
        return paidUntil.getOrDefault(Objects.requireNonNull(service), 0L);
    }

    public boolean autoRenew(ServiceType service) {
        return autoRenew.contains(Objects.requireNonNull(service));
    }

    public void setAutoRenew(ServiceType service, boolean enabled) {
        Objects.requireNonNull(service);
        if (enabled) autoRenew.add(service);
        else autoRenew.remove(service);
    }

    /** Called once per online player's server tick. A saturated clock never wraps. */
    public boolean tick(boolean survival) {
        if (!survival || activePlayTicks == Long.MAX_VALUE) {
            return false;
        }
        activePlayTicks++;
        return true;
    }

    public void credit(long amount) {
        requireNonNegative(amount);
        credits = Math.addExact(credits, amount);
    }

    /** For explicitly operator-protected diagnostics; normal spending belongs to the engine. */
    public void setCredits(long amount) {
        requireNonNegative(amount);
        credits = amount;
    }

    void setPaidUntil(ServiceType service, long expiry) {
        requireNonNegative(expiry);
        paidUntil.put(Objects.requireNonNull(service), expiry);
    }

    private static boolean isLegacy(ServiceType service) {
        return service == ServiceType.MINE || service == ServiceType.SLEEP || service == ServiceType.SPRINT;
    }

    private static void requireNonNegative(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Account values must not be negative");
        }
    }

    public record Snapshot(UUID uuid, long credits, long activePlayTicks, long trialEndTick,
                           long paidUntilMine, long paidUntilSleep, long paidUntilSprint,
                           Map<String, Long> extraPaidUntil, Set<String> autoRenew) {
        /** Reads v0.1 state without issuing new trial time, new licenses, or automatic purchases. */
        public Snapshot(UUID uuid, long credits, long activePlayTicks, long trialEndTick,
                        long paidUntilMine, long paidUntilSleep, long paidUntilSprint) {
            this(uuid, credits, activePlayTicks, trialEndTick, paidUntilMine, paidUntilSleep, paidUntilSprint,
                    Map.of(), Set.of());
        }

        public Snapshot {
            Objects.requireNonNull(uuid, "uuid");
            requireNonNegative(credits);
            requireNonNegative(activePlayTicks);
            requireNonNegative(trialEndTick);
            requireNonNegative(paidUntilMine);
            requireNonNegative(paidUntilSleep);
            requireNonNegative(paidUntilSprint);
            extraPaidUntil = Map.copyOf(extraPaidUntil);
            autoRenew = Set.copyOf(autoRenew);
            extraPaidUntil.values().forEach(PlayerAccount::requireNonNegative);
        }
    }
}
