package dev.blockbill.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static dev.blockbill.domain.ServiceType.*;

public enum SubscriptionBundle {
    BASIC("basic", 40, EnumSet.of(MINE, SLEEP, SPRINT)),
    WORKSHOP("workshop", 50, EnumSet.of(ENCHANT, ANVIL, SMITH)),
    EXPLORER("explorer", 50, EnumSet.of(MINE, SPRINT, FISH, STORAGE)),
    ALL("all", 120, EnumSet.allOf(ServiceType.class)),
    PLUS("plus", 90, 18_000, EnumSet.of(MINE, SLEEP, SPRINT, FISH, STORAGE)),
    PRO("pro", 300, 36_000, EnumSet.allOf(ServiceType.class)),
    BUSINESS("business", 540, 72_000, EnumSet.allOf(ServiceType.class));

    private final String id;
    private final long price;
    private final long durationTicks;
    private final Set<ServiceType> services;

    SubscriptionBundle(String id, long price, EnumSet<ServiceType> services) {
        this(id, price, 12_000, services);
    }

    SubscriptionBundle(String id, long price, long durationTicks, EnumSet<ServiceType> services) {
        this.id = id;
        this.price = price;
        this.durationTicks = durationTicks;
        this.services = Collections.unmodifiableSet(services);
    }

    public String id() { return id; }
    public long price() { return price; }
    public long durationTicks() { return durationTicks; }
    public long durationMinutes() { return durationTicks / 1200; }
    public boolean isPlan() { return this == PLUS || this == PRO || this == BUSINESS; }
    public Set<ServiceType> services() { return services; }
}
