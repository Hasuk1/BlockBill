# Progression gates: Minecraft 26.3 / Fabric API 0.165.0

The v0.4 owner contract explicitly replaces the earlier soft penalties. All access checks delegate to `SubscriptionEngine.hasAccess`, so paid, trial and temporary advertisement access use one server-owned rule.

## Mining

`AttackBlockCallback` rejects the initial server start request. Fabric's `ServerPlayerGameModeMixin.startBlockBreak` checks reach/world bounds, dispatches the callback and sends block-state correction when rejected. The client callback passes through until a server answer; it never determines entitlement.

`PlayerBlockBreakEvents.BEFORE.beforeBlockBreak(Level, Player, BlockPos, BlockState, BlockEntity): boolean` performs the final check. Exact Fabric injection is before `Block.playerWillDestroy` inside `ServerPlayerGameMode.destroyBlock`, therefore cancellation precedes block removal, drops and tool damage. It also covers access expiry between start and completion. Survival restriction is `Y < 40 || BlockTags.ORES || Blocks.ANCIENT_DEBRIS`. Minecraft 26.3's `data/minecraft/tags/block/ores.json` includes the coal/copper/iron/gold/diamond/emerald/lapis/redstone groups and Nether quartz, but does not include ancient debris.

This gate controls player block breaking. It does not redefine explosion loot, mob drops, villager trades or every possible third-party world mutation. Travel remains the direct progression gate for entry to Nether/End.

## Portal mixin justification

The exact entity-events API exposes only `ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE` and `AFTER_PLAYER_CHANGE`; no cancellable portal-entry event exists. Returning players after teleport would already perform unwanted transfers and could trap them.

`EntityPortalMixin` injects cancellable HEAD into `Entity.teleportToPortalDestination(ServerLevel, TeleportTransition): void`. Vanilla `Entity.handlePortal` invokes this method after obtaining a destination, before `Entity.teleport`. The destination is read from `TeleportTransition.newLevel()`. Only entry into a different Nether/End dimension requires Travel; same-dimension End gateways and return to Overworld remain free. Administrative `teleportTo` uses another path and remains unaffected.

A portal may teleport a mount as the root. The gate checks root plus `getIndirectPassengers()` and cancels the entire transfer if any survival player lacks access. Non-player creatures, creative, spectator and adventure are exempt.

Destination lookup happens before the hook, and vanilla may have generated destination chunks/portal blocks already. No player or inventory transfer occurs on denial. Avoiding those lookup side effects would require intercepting individual portal implementations and duplicating destination decisions, which is deliberately outside this minimal patch.

Vanilla also establishes a portal cooldown before this hook. After denial, leave the portal and let its normal cooldown expire before entering again. We retain this behavior instead of clearing cooldown and repeatedly looking up/generating destinations while a denied player remains inside a portal.

## Sprint mixin justification

Fabric supplies no cancellable sprint-start event. `LivingEntity.setSprinting(boolean)` first sets the entity flag, then separately removes/adds the movement-speed modifier. Cancelling only `Entity.setSprinting` would leave the modifier active.

`LivingEntitySprintMixin` modifies the boolean argument at method HEAD, before both mutations. On the logical server, unpaid survival requests become false and vanilla removes the modifier normally. The server's tick adapter also clears an already-running sprint when access expires. No Mining Fatigue or Slowness is added, and foreign effects are not removed.

The common client flag `ProgressionAccess.setClientSprintAllowed(boolean)` controls local prediction without importing client-only classes. The UI/network adapter updates it from the server's AccessState packet. Client state never grants server access.

## Compatibility and verification

The required mixins use exact unobfuscated 26.3 method names with `defaultRequire: 1` and `JAVA_25`; unsupported future method changes fail startup instead of silently bypassing gates. Mods replacing portal or sprint behavior may conflict and require their own compatibility check. No base game or third-party source is modified.

`ProgressionGameTests` uses ordinary ServerPlayer/network fixtures, real destruction/start requests, actual sprint attribute changes and actual portal transition methods. It covers surface/deep boundaries, all vanilla ores, two-player isolation, payment, expiry during mining, foreign effects, free return/admin teleport, vehicle passengers, trial and exempt modes. Runtime results are reported by the root verification run, not implied by this source review.

Primary evidence: the pinned Mojang `minecraft-common.jar` in Loom's 26.3 cache and Fabric's pinned `fabric-events-interaction-v0` / `fabric-entity-events-v1` jars and sources. Upstream API: https://github.com/FabricMC/fabric/tree/26.3/fabric-events-interaction-v0 and https://github.com/FabricMC/fabric/tree/26.3/fabric-entity-events-v1 .
