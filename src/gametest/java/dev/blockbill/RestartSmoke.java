package dev.blockbill;

import com.google.gson.GsonBuilder;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.domain.SubscriptionBundle;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.TransactionResult;
import dev.blockbill.infrastructure.Accounts;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Opt-in verification mod. Never packaged in the BlockBill release jar. */
public final class RestartSmoke implements ModInitializer {
    private static final UUID FIRST = UUID.fromString("6ffba4c3-ecdd-41c2-957e-d10ea6045751");
    private static final UUID SECOND = UUID.fromString("ac29510f-f674-48b0-b487-18436d932473");
    private static final PlayerAccount.Snapshot V03_FIRST = new PlayerAccount.Snapshot(FIRST, 70, 6042, 6000,
            156042, 156042, 156042,
            Map.of("enchant", 126042L, "anvil", 126042L, "smith", 126042L, "trade", 126042L,
                    "fish", 144042L, "storage", 144042L), Set.of("enchant", "fish"));
    private static final PlayerAccount.Snapshot UPGRADE_EXPECTED = new PlayerAccount.Snapshot(FIRST, 30, 6042, 6000,
            156042, 156042, 156042,
            Map.of("enchant", 126042L, "anvil", 126042L, "smith", 126042L, "trade", 126042L,
                    "fish", 144042L, "storage", 144042L, "travel", 18042L), Set.of("enchant", "fish"), 37, 6642, 7842);
    private static final PlayerAccount.Snapshot FRESH_EXPECTED = new PlayerAccount.Snapshot(FIRST, 700, 0, 6000,
            42000, 42000, 42000,
            Map.of("enchant", 42000L, "anvil", 42000L, "smith", 42000L, "trade", 42000L,
                    "fish", 42000L, "storage", 42000L, "travel", 42000L), Set.of(), 37, 600, 1800);
    private static final PlayerAccount.Snapshot SECOND_EXPECTED = new PlayerAccount.Snapshot(SECOND, 7, 123, 6000, 0, 0, 0);
    private Evidence evidence;

    @Override
    public void onInitialize() {
        String phase = System.getProperty("blockbill.restartPhase");
        if (phase == null) return;
        if (!phase.equals("write") && !phase.equals("read")) throw new IllegalArgumentException("restartPhase must be write or read");
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            verify(server, phase);
            server.halt(false);
        });
        // This event runs after vanilla has saved the world and closed its data storage.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (evidence == null) return;
            try {
                Path marker = Path.of(phase + "-success.json").toAbsolutePath();
                Files.writeString(marker, new GsonBuilder().setPrettyPrinting().create().toJson(evidence) + "\n");
                BlockBillMod.LOGGER.info("BLOCKBILL_RESTART_SMOKE_SUCCESS phase={} pid={} marker={}", phase, evidence.pid(), marker);
            } catch (IOException exception) {
                throw new IllegalStateException("Could not write restart verification marker", exception);
            }
        });
    }

    private void verify(MinecraftServer server, String phase) {
        try {
            Path source = Path.of(BlockBillMod.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            require(Files.isRegularFile(source) && source.getFileName().toString().endsWith(".jar"),
                    "Production smoke must load BlockBill from a built jar: " + source);
            require(server.isDedicatedServer(), "Expected dedicated server");
            Accounts accounts = Accounts.get(server);
            PlayerAccount first = accounts.account(FIRST);
            PlayerAccount second = accounts.account(SECOND);
            boolean upgrade = System.getProperty("blockbill.smokeScenario", "fresh").equals("upgrade");
            PlayerAccount.Snapshot expected = upgrade ? UPGRADE_EXPECTED : FRESH_EXPECTED;
            if (phase.equals("write")) {
                if (!first.snapshot().equals(expected)) {
                    if (upgrade) {
                        require(first.snapshot().equals(V03_FIRST), "Upgrade changed existing v0.3 wallet, licenses, trial or flags");
                        require(first.paidUntil(ServiceType.TRAVEL) == 0 && first.adRemainingTicks() == 0,
                                "Upgrade granted new services or advertising without consent");
                        first.creditCents(37);
                        require(SubscriptionEngine.purchase(first, ServiceType.TRAVEL) == TransactionResult.SUCCESS, "Travel purchase failed");
                    } else {
                        require(first.snapshot().equals(new PlayerAccount(FIRST).snapshot()), "Fresh fixture was not empty");
                        first.creditCents(100037);
                        require(SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.PRO) == TransactionResult.SUCCESS, "Pro purchase failed");
                    }
                    require(first.grantAdvertisement(), "Advertisement grant failed");
                }
                if (second.snapshot().equals(new PlayerAccount(SECOND).snapshot())) {
                    for (int tick = 0; tick < 123; tick++) second.tick(true);
                    second.setCredits(7);
                }
                accounts.setDirty();
            }
            require(first.snapshot().equals(expected), "First persisted UUID differs: " + first.snapshot());
            require(second.snapshot().equals(SECOND_EXPECTED), "Second persisted UUID differs: " + second.snapshot());
            String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            evidence = new Evidence(phase, ProcessHandle.current().pid(), source.toUri().toString(), sha256,
                    server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString(),
                    List.of(first.snapshot(), second.snapshot()));
        } catch (IOException | URISyntaxException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Restart verification failed", exception);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private record Evidence(String phase, long pid, String sourceJar, String sha256, String world,
                            List<PlayerAccount.Snapshot> accounts) { }
}
