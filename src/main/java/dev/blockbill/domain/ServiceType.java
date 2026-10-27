package dev.blockbill.domain;

public enum ServiceType {
    MINE("mine", 20),
    SLEEP("sleep", 15),
    SPRINT("sprint", 15),
    ENCHANT("enchant", 25),
    ANVIL("anvil", 20),
    SMITH("smith", 20),
    TRADE("trade", 20),
    FISH("fish", 15),
    STORAGE("storage", 15);

    private final String id;
    private final long price;

    ServiceType(String id, long price) {
        this.id = id;
        this.price = price;
    }

    public String id() { return id; }

    public long price() { return price; }

    public static java.util.Optional<ServiceType> fromId(String id) {
        for (ServiceType service : values()) {
            if (service.id.equals(id)) return java.util.Optional.of(service);
        }
        return java.util.Optional.empty();
    }
}
