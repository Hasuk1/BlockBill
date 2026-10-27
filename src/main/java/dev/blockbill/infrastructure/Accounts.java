package dev.blockbill.infrastructure;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.blockbill.domain.PlayerAccount;
import net.minecraft.core.UUIDUtil;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** World-owned UUID accounts. Access and mutation are restricted to the logical server thread. */
public final class Accounts extends SavedData {
    private static final Codec<Long> NON_NEGATIVE_LONG = Codec.LONG.flatXmap(
            value -> value >= 0 ? DataResult.success(value) : DataResult.error(() -> "Negative BlockBill account value"),
            DataResult::success);
    private static final Codec<PlayerAccount.Snapshot> SNAPSHOT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("uuid").forGetter(PlayerAccount.Snapshot::uuid),
            NON_NEGATIVE_LONG.fieldOf("credits").forGetter(PlayerAccount.Snapshot::credits),
            NON_NEGATIVE_LONG.fieldOf("activeTicks").forGetter(PlayerAccount.Snapshot::activePlayTicks),
            NON_NEGATIVE_LONG.fieldOf("trialEnd").forGetter(PlayerAccount.Snapshot::trialEndTick),
            NON_NEGATIVE_LONG.fieldOf("mineUntil").forGetter(PlayerAccount.Snapshot::paidUntilMine),
            NON_NEGATIVE_LONG.fieldOf("sleepUntil").forGetter(PlayerAccount.Snapshot::paidUntilSleep),
            NON_NEGATIVE_LONG.fieldOf("sprintUntil").forGetter(PlayerAccount.Snapshot::paidUntilSprint),
            Codec.unboundedMap(Codec.STRING, NON_NEGATIVE_LONG).optionalFieldOf("extraPaidUntil", Map.of())
                    .forGetter(PlayerAccount.Snapshot::extraPaidUntil),
            Codec.STRING.listOf().xmap(Set::copyOf, List::copyOf).optionalFieldOf("autoRenew", Set.of())
                    .forGetter(PlayerAccount.Snapshot::autoRenew)
    ).apply(instance, PlayerAccount.Snapshot::new));

    public static final Codec<Accounts> CODEC = SNAPSHOT_CODEC.listOf().flatXmap(Accounts::decode,
            accounts -> DataResult.success(accounts.players.values().stream().map(PlayerAccount::snapshot).toList()))
            .mapResult(new Codec.ResultFunction<>() {
                @Override
                public <T> DataResult<Pair<Accounts, T>> apply(DynamicOps<T> ops, T input, DataResult<Pair<Accounts, T>> result) {
                    // SavedDataStorage accepts resultOrPartial: never expose a truncated account list.
                    return result.error().isPresent() ? DataResult.error(() -> result.error().orElseThrow().message()) : result;
                }

                @Override
                public <T> DataResult<T> coApply(DynamicOps<T> ops, Accounts input, DataResult<T> result) { return result; }
            });
    public static final SavedDataType<Accounts> TYPE = new SavedDataType<>(Identifier.fromNamespaceAndPath("blockbill", "accounts"),
            Accounts::new, CODEC, null);

    private final Map<UUID, PlayerAccount> players = new HashMap<>();
    private static final Map<SavedDataStorage, Accounts> LOADED = new WeakHashMap<>();

    public static Accounts get(MinecraftServer server) {
        SavedDataStorage storage = server.overworld().getDataStorage();
        Accounts existing = LOADED.get(storage);
        if (existing != null) return existing;
        Path directory = DimensionType.getStorageFolder(Level.OVERWORLD, server.getWorldPath(LevelResource.ROOT))
                .resolve(LevelResource.DATA.id());
        return load(storage, directory);
    }

    /** Strict, once-per-storage load. A corrupt file must never turn into a fresh trial or partial wallet table. */
    public static Accounts load(SavedDataStorage storage, Path dataDirectory) {
        Accounts existing = LOADED.get(storage);
        if (existing != null) return existing;
        Path file = TYPE.id().withSuffix(".dat").resolveAgainst(dataDirectory);
        final Accounts accounts;
        if (Files.notExists(file)) {
            accounts = new Accounts();
        } else {
            try {
                var root = storage.readTagFromDisk(file, null, SharedConstants.getCurrentVersion().dataVersion().version());
                var data = root.get("data");
                if (data == null) throw new IllegalStateException("Missing account data");
                accounts = CODEC.parse(NbtOps.INSTANCE, data).getOrThrow();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("BlockBill cannot load " + file.toAbsolutePath()
                        + ". Original file was preserved; restore a valid world backup before restarting.", exception);
            }
        }
        // Only validated data reaches vanilla's saving cache. No fallback on a parsing or I/O error.
        storage.set(TYPE, accounts);
        LOADED.put(storage, accounts);
        return accounts;
    }

    public static void markLoadedDirty(MinecraftServer server) {
        if (server.overworld() == null) return;
        Accounts accounts = LOADED.get(server.overworld().getDataStorage());
        if (accounts != null) accounts.setDirty();
    }

    public static void forget(MinecraftServer server) {
        if (server.overworld() != null) LOADED.remove(server.overworld().getDataStorage());
    }

    public PlayerAccount account(UUID uuid) {
        return players.computeIfAbsent(uuid, id -> {
            setDirty();
            return new PlayerAccount(id);
        });
    }

    private static DataResult<Accounts> decode(List<PlayerAccount.Snapshot> snapshots) {
        Accounts result = new Accounts();
        try {
            for (PlayerAccount.Snapshot snapshot : snapshots) {
                if (result.players.putIfAbsent(snapshot.uuid(), PlayerAccount.restore(snapshot)) != null) {
                    return DataResult.error(() -> "Duplicate BlockBill account UUID");
                }
            }
            return DataResult.success(result);
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Invalid BlockBill account: " + exception.getMessage());
        }
    }
}
