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
    private static final PlayerAccount.Snapshot LEGACY_FIRST = new PlayerAccount.Snapshot(FIRST, 83, 6042, 6000, 18042, 18042, 18042);
    private static final PlayerAccount.Snapshot V02_FIRST = new PlayerAccount.Snapshot(FIRST, 880, 6042, 6000,
            30042, 30042, 30042,
            Map.of("enchant", 18042L, "anvil", 18042L, "smith", 18042L, "trade", 18042L, "fish", 18042L, "storage", 18042L),
            Set.of("enchant", "fish"));
    private static final PlayerAccount.Snapshot FIRST_EXPECTED = new PlayerAccount.Snapshot(FIRST, 70, 6042, 6000,
            156042, 156042, 156042,
            Map.of("enchant", 126042L, "anvil", 126042L, "smith", 126042L, "trade", 126042L,
                    "fish", 144042L, "storage", 144042L), Set.of("enchant", "fish"));
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
            if (phase.equals("write")) {
                // Repeating the write phase is safe only when the earlier fixture is identical.
                // Never silently reset an account or any real player's state.
                if (first.snapshot().equals(new PlayerAccount(FIRST).snapshot())) {
                    for (int tick = 0; tick < 6042; tick++) first.tick(true);
                    first.setCredits(123);
                    require(SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.BASIC) == TransactionResult.SUCCESS,
                            "Could not seed legacy-compatible fixture");
                }
                if (second.snapshot().equals(new PlayerAccount(SECOND).snapshot())) {
                    for (int tick = 0; tick < 123; tick++) second.tick(true);
                    second.setCredits(7);
                }
                if (!first.snapshot().equals(V02_FIRST) && !first.snapshot().equals(FIRST_EXPECTED)) {
                    require(first.snapshot().equals(LEGACY_FIRST), "v0.1 migration changed existing wallet/trial/licenses");
                    for (ServiceType service : ServiceType.values()) {
                        require(!first.autoRenew(service), "Migration enabled automatic spending");
                    }
                    first.setCredits(1000);
                    require(SubscriptionEngine.purchaseAll(first) == TransactionResult.SUCCESS, "Could not purchase all nine services");
                    first.setAutoRenew(ServiceType.ENCHANT, true);
                    first.setAutoRenew(ServiceType.FISH, true);
                }
                if (!first.snapshot().equals(FIRST_EXPECTED)) {
                    require(first.snapshot().equals(V02_FIRST), "v0.2 migration changed wallet, licenses or autorenew settings");
                    first.setCredits(1000);
                    require(SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.PLUS) == TransactionResult.SUCCESS, "Plus failed");
                    require(SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.PRO) == TransactionResult.SUCCESS, "Pro failed");
                    require(SubscriptionEngine.purchaseBundle(first, SubscriptionBundle.BUSINESS) == TransactionResult.SUCCESS, "Business failed");
                }
                accounts.setDirty();
            }
            require(first.snapshot().equals(FIRST_EXPECTED), "First persisted UUID differs: " + first.snapshot());
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
