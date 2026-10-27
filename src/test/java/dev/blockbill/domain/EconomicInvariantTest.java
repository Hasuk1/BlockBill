package dev.blockbill.domain;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.blockbill.domain.ServiceType.*;
import static dev.blockbill.domain.TransactionResult.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reproducible generated cases supplement the explicit catalog and gameplay examples. */
class EconomicInvariantTest {
    @Test
    void everyPairOfEarlyPurchasesCommutesEvenWhenSavedAndRestoredBetweenPurchases() {
        PlayerAccount.Snapshot initial = new PlayerAccount.Snapshot(UUID.randomUUID(), 5_000, 3_100, 6_000,
                9_000, 0, 2_500, Map.of("enchant", 20_000L, "storage", 10_000L, "fish", 1_000L, "future_glide", 52L),
                Set.of("fish", "future_glide"));
        for (SubscriptionBundle first : SubscriptionBundle.values()) {
            for (SubscriptionBundle second : SubscriptionBundle.values()) {
                PlayerAccount forward = PlayerAccount.restore(initial);
                assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(forward, first));
                forward = PlayerAccount.restore(forward.snapshot());
                assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(forward, second));

                PlayerAccount backward = PlayerAccount.restore(initial);
                assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(backward, second));
                backward = PlayerAccount.restore(backward.snapshot());
                assertEquals(SUCCESS, SubscriptionEngine.purchaseBundle(backward, first));

                String scenario = first + " then " + second;
                assertEquals(forward.snapshot(), backward.snapshot(), scenario);
                assertEquals(5_000 - first.price() - second.price(), forward.credits(), scenario);
                assertEquals(initial.autoRenew(), forward.snapshot().autoRenew(), scenario);
                assertEquals(52L, forward.snapshot().extraPaidUntil().get("future_glide"), scenario);
            }
        }
    }

    @Test
    void bundleArithmeticMatchesUnboundedIntegerOracleAcrossLongBoundaries() {
        Random random = new Random(0xB10CB111L);
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            for (int scenario = 0; scenario < 120; scenario++) {
                long now = boundaryValue(random, bundle.durationTicks());
                long balance = switch (scenario % 4) {
                    case 0 -> bundle.price() - 1;
                    case 1 -> bundle.price();
                    case 2 -> Long.MAX_VALUE;
                    default -> 1_000;
                };
                Map<ServiceType, Long> expiries = new EnumMap<>(ServiceType.class);
                for (ServiceType service : ServiceType.values()) expiries.put(service, boundaryValue(random, bundle.durationTicks()));
                PlayerAccount account = restore(balance, now, expiries, Set.of("mine", "future_glide"));
                PlayerAccount.Snapshot before = account.snapshot();
                Map<ServiceType, Long> expectedExpiries = new EnumMap<>(expiries);
                boolean overflow = false;
                for (ServiceType service : bundle.services()) {
                    BigInteger renewed = BigInteger.valueOf(now).max(BigInteger.valueOf(6_000))
                            .max(BigInteger.valueOf(expiries.get(service))).add(BigInteger.valueOf(bundle.durationTicks()));
                    if (renewed.compareTo(maximum) > 0) overflow = true;
                    else expectedExpiries.put(service, renewed.longValueExact());
                }
                TransactionResult expected = balance < bundle.price() ? INSUFFICIENT_CREDITS : overflow ? OVERFLOW : SUCCESS;
                String description = bundle + " generated boundary case " + scenario;
                assertEquals(expected, SubscriptionEngine.purchaseBundle(account, bundle), description);
                if (expected != SUCCESS) {
                    assertEquals(before, account.snapshot(), description);
                } else {
                    assertEquals(balance - bundle.price(), account.credits(), description);
                    for (ServiceType service : ServiceType.values()) {
                        assertEquals(expectedExpiries.get(service), account.paidUntil(service), description + "/" + service);
                    }
                    assertEquals(before.autoRenew(), account.snapshot().autoRenew(), description);
                }
            }
        }
    }

    @Test
    void generatedStackDistributionsConserveUnrelatedItemsAndRejectEveryFailedTradeAtomically() {
        Random random = new Random(0xC0A1B111L);
        for (ExchangeOffer offer : ExchangeOffer.values()) {
            for (int scenario = 0; scenario < 120; scenario++) {
                int[] counts = new int[42];
                boolean[] matching = new boolean[counts.length];
                long available = 0;
                for (int slot = 0; slot < counts.length; slot++) {
                    counts[slot] = random.nextInt(65);
                    matching[slot] = random.nextBoolean();
                    if (matching[slot]) available += counts[slot];
                }
                int bundles = 1 + random.nextInt((int) (available / offer.itemsPerBundle()) + 2);
                long required = (long) bundles * offer.itemsPerBundle();
                long payment = bundles * offer.creditsPerBundle();
                long balance = scenario % 4 == 0 ? Long.MAX_VALUE - random.nextInt(100) : random.nextInt(1_000);
                PlayerAccount account = new PlayerAccount(UUID.randomUUID());
                account.setCredits(balance);
                PlayerAccount.Snapshot beforeAccount = account.snapshot();
                int[] beforeItems = counts.clone();
                MutableSlots inventory = new MutableSlots(counts, matching);
                TransactionResult expected = payment > Long.MAX_VALUE - balance ? OVERFLOW
                        : available < required ? INSUFFICIENT_ITEMS : SUCCESS;
                String description = offer + " generated inventory case " + scenario;
                assertEquals(expected, ExchangeService.exchange(account, offer, bundles, inventory, true), description);
                if (expected != SUCCESS) {
                    assertEquals(beforeAccount, account.snapshot(), description);
                    assertArrayEquals(beforeItems, counts, description);
                    assertEquals(0, inventory.removalCalls, description);
                } else {
                    long removed = 0;
                    for (int slot = 0; slot < counts.length; slot++) {
                        assertTrue(counts[slot] >= 0 && counts[slot] <= beforeItems[slot], description);
                        if (matching[slot]) removed += beforeItems[slot] - counts[slot];
                        else assertEquals(beforeItems[slot], counts[slot], description);
                    }
                    assertEquals(required, removed, description);
                    assertEquals(balance + payment, account.credits(), description);
                }
            }
        }
    }

    @Test
    void generatedSnapshotsRoundTripAtLongExtremesAndAccessAgreesWithRemainingTime() {
        Random random = new Random(0x5A9EB111L);
        for (int scenario = 0; scenario < 240; scenario++) {
            Map<ServiceType, Long> expiries = new EnumMap<>(ServiceType.class);
            Set<String> flags = new HashSet<>(Set.of("future_glide"));
            for (ServiceType service : ServiceType.values()) {
                expiries.put(service, boundaryValue(random, 72_000));
                if (random.nextBoolean()) flags.add(service.id());
            }
            PlayerAccount account = restore(boundaryValue(random, 540), boundaryValue(random, 72_000), expiries, flags);
            PlayerAccount.Snapshot saved = account.snapshot();
            for (int reload = 0; reload < 3; reload++) account = PlayerAccount.restore(account.snapshot());
            assertEquals(saved, account.snapshot(), "Generated snapshot " + scenario);
            for (ServiceType service : ServiceType.values()) {
                assertEquals(SubscriptionEngine.remainingTicks(account, service) > 0, SubscriptionEngine.hasAccess(account, service));
                assertEquals(flags.contains(service.id()), account.autoRenew(service));
            }
        }
    }

    private static long boundaryValue(Random random, long duration) {
        return switch (random.nextInt(9)) {
            case 0 -> 0;
            case 1 -> 5_999;
            case 2 -> 6_000;
            case 3 -> 6_001;
            case 4 -> Long.MAX_VALUE;
            case 5 -> Long.MAX_VALUE - duration;
            case 6 -> Long.MAX_VALUE - duration + 1;
            case 7 -> random.nextInt(100_000);
            default -> random.nextLong() & Long.MAX_VALUE;
        };
    }

    private static PlayerAccount restore(long balance, long now, Map<ServiceType, Long> expiries, Set<String> flags) {
        Map<String, Long> extras = new HashMap<>();
        for (ServiceType service : ServiceType.values()) {
            if (service != MINE && service != SLEEP && service != SPRINT) extras.put(service.id(), expiries.get(service));
        }
        extras.put("future_glide", 123_456L);
        // Even an opaque future/shadow field must not override an authoritative legacy field.
        extras.put("mine", 654_321L);
        return PlayerAccount.restore(new PlayerAccount.Snapshot(UUID.randomUUID(), balance, now, 6_000,
                expiries.get(MINE), expiries.get(SLEEP), expiries.get(SPRINT), extras, flags));
    }

    private static final class MutableSlots implements ExchangeService.Inventory {
        private final int[] counts;
        private final boolean[] matching;
        private int removalCalls;

        private MutableSlots(int[] counts, boolean[] matching) {
            this.counts = counts;
            this.matching = matching;
        }

        @Override public int size() { return counts.length; }
        @Override public int count(int slot) { return counts[slot]; }
        @Override public boolean matches(int slot, ExchangeOffer offer) { return matching[slot]; }
        @Override public void remove(int slot, int count) {
            assertTrue(count > 0 && count <= counts[slot]);
            counts[slot] -= count;
            removalCalls++;
        }
    }
}
