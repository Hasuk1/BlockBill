package dev.blockbill.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public final class SubscriptionEngine {
    public static final long TRIAL_TICKS = 6_000;
    public static final long PERIOD_TICKS = 12_000;
    public static final long ALL_PRICE = 120;

    private SubscriptionEngine() { }

    public static boolean hasAccess(PlayerAccount account, ServiceType service) {
        return account.activePlayTicks() < account.trialEndTick()
                || account.activePlayTicks() < account.paidUntil(service);
    }

    public static long remainingTicks(PlayerAccount account, ServiceType service) {
        return Math.max(0, Math.max(account.trialEndTick(), account.paidUntil(service)) - account.activePlayTicks());
    }

    public static long trialRemainingTicks(PlayerAccount account) {
        return Math.max(0, account.trialEndTick() - account.activePlayTicks());
    }

    public static TransactionResult purchase(PlayerAccount account, ServiceType service) {
        if (account.credits() < service.price()) {
            return TransactionResult.INSUFFICIENT_CREDITS;
        }
        final long expiry;
        try {
            expiry = renewedExpiry(account, service, PERIOD_TICKS);
        } catch (ArithmeticException overflow) {
            return TransactionResult.OVERFLOW;
        }
        account.setCredits(account.credits() - service.price());
        account.setPaidUntil(service, expiry);
        return TransactionResult.SUCCESS;
    }

    public static TransactionResult purchaseAll(PlayerAccount account) {
        return purchaseBundle(account, SubscriptionBundle.ALL);
    }

    public static TransactionResult purchaseBundle(PlayerAccount account, SubscriptionBundle bundle) {
        if (account.credits() < bundle.price()) {
            return TransactionResult.INSUFFICIENT_CREDITS;
        }
        Map<ServiceType, Long> expiries = new EnumMap<>(ServiceType.class);
        try {
            for (ServiceType service : bundle.services()) {
                expiries.put(service, renewedExpiry(account, service, bundle.durationTicks()));
            }
        } catch (ArithmeticException overflow) {
            return TransactionResult.OVERFLOW;
        }
        account.setCredits(account.credits() - bundle.price());
        expiries.forEach(account::setPaidUntil);
        return TransactionResult.SUCCESS;
    }

    /** Called only by the adapter for online survival players. Successful services renew once. */
    public static Map<ServiceType, TransactionResult> renewDue(PlayerAccount account) {
        Map<ServiceType, TransactionResult> results = new EnumMap<>(ServiceType.class);
        for (ServiceType service : ServiceType.values()) {
            if (account.autoRenew(service) && !hasAccess(account, service)) {
                TransactionResult result = purchase(account, service);
                if (result != TransactionResult.SUCCESS) account.setAutoRenew(service, false);
                results.put(service, result);
            }
        }
        return Collections.unmodifiableMap(results);
    }

    private static long renewedExpiry(PlayerAccount account, ServiceType service, long durationTicks) {
        long base = Math.max(account.activePlayTicks(), Math.max(account.trialEndTick(), account.paidUntil(service)));
        return Math.addExact(base, durationTicks);
    }
}
