package dev.blockbill.domain;

public enum ExchangeOffer {
    LOGS("logs", 16, 12),
    COBBLESTONE("cobblestone", 32, 16),
    ROTTEN_FLESH("rotten_flesh", 8, 12),
    IRON_INGOT("iron_ingot", 4, 36),
    COAL("coal", 8, 16),
    WHEAT("wheat", 16, 12),
    COPPER_INGOT("copper_ingot", 8, 16),
    REDSTONE("redstone", 16, 20),
    GOLD_INGOT("gold_ingot", 4, 32),
    EMERALD("emerald", 1, 28);

    public static final int MAX_BUNDLES = 1_000_000;
    private final String id;
    private final int itemsPerBundle;
    private final long creditsPerBundle;

    ExchangeOffer(String id, int itemsPerBundle, long creditsPerBundle) {
        this.id = id;
        this.itemsPerBundle = itemsPerBundle;
        this.creditsPerBundle = creditsPerBundle;
    }

    public String id() { return id; }
    public int itemsPerBundle() { return itemsPerBundle; }
    public long creditsPerBundle() { return creditsPerBundle; }
}
