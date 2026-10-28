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
    private int creditRemainder;
    private long adUntilTick;
    private long adReadyAtTick;
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
        creditRemainder = snapshot.creditRemainder();
        adUntilTick = snapshot.adUntilTick();
        adReadyAtTick = snapshot.adReadyAtTick();
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
                paidUntil(ServiceType.MINE), paidUntil(ServiceType.SLEEP), paidUntil(ServiceType.SPRINT), extras, renew,
                creditRemainder, adUntilTick, adReadyAtTick);
    }

    public UUID uuid() { return uuid; }
    public long credits() { return credits; }
    public long activePlayTicks() { return activePlayTicks; }
    public long trialEndTick() { return trialEndTick; }
    public int creditRemainder() { return creditRemainder; }
    public long adUntilTick() { return adUntilTick; }
    public long adReadyAtTick() { return adReadyAtTick; }
    public long adRemainingTicks() { return Math.max(0, adUntilTick - activePlayTicks); }
    public long adCooldownTicks() { return Math.max(0, adReadyAtTick - activePlayTicks); }
    public String formattedCredits() { return credits + "." + (creditRemainder < 10 ? "0" : "") + creditRemainder; }

    /** Check overflow without modifying money; used before any inventory mutation. */
    public boolean canCreditCents(long cents) {
        requireNonNegative(cents);
        try {
            Math.addExact(credits, cents / 100 + (creditRemainder + cents % 100) / 100);
            return true;
        } catch (ArithmeticException exception) { return false; }
    }

    public void creditCents(long cents) {
        requireNonNegative(cents);
        long fractional = creditRemainder + cents % 100;
        long next = Math.addExact(credits, cents / 100 + fractional / 100);
        credits = next;
        creditRemainder = (int) (fractional % 100);
    }

    /** Called only after a server-validated five-second watch. One grant, persisted cooldown. */
    public boolean grantAdvertisement() {
        if (adCooldownTicks() > 0 || activePlayTicks > Long.MAX_VALUE - 1800) return false;
        adUntilTick = activePlayTicks + 600;
        adReadyAtTick = activePlayTicks + 1800;
        return true;
    }

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
                           Map<String, Long> extraPaidUntil, Set<String> autoRenew,
                           int creditRemainder, long adUntilTick, long adReadyAtTick) {
        /** v0.2/v0.3 snapshots have no fractional money or advertising state. */
        public Snapshot(UUID uuid, long credits, long activePlayTicks, long trialEndTick,
                        long paidUntilMine, long paidUntilSleep, long paidUntilSprint,
                        Map<String, Long> extraPaidUntil, Set<String> autoRenew) {
            this(uuid, credits, activePlayTicks, trialEndTick, paidUntilMine, paidUntilSleep, paidUntilSprint,
                    extraPaidUntil, autoRenew, 0, 0, 0);
        }
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
            if (creditRemainder < 0 || creditRemainder > 99) throw new IllegalArgumentException("Invalid credit remainder");
            requireNonNegative(adUntilTick);
            requireNonNegative(adReadyAtTick);
            extraPaidUntil = Map.copyOf(extraPaidUntil);
            autoRenew = Set.copyOf(autoRenew);
            extraPaidUntil.values().forEach(PlayerAccount::requireNonNegative);
        }
    }
}
