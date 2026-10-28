package dev.blockbill.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.blockbill.domain.ExchangeOffer;
import dev.blockbill.domain.ExchangeService;
import dev.blockbill.market.MarketSales;
import dev.blockbill.domain.PlayerAccount;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.domain.SubscriptionEngine;
import dev.blockbill.domain.SubscriptionBundle;
import dev.blockbill.domain.TransactionResult;
import dev.blockbill.infrastructure.Accounts;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class BlockBillCommands {
    private BlockBillCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = literal("blockbill").executes(ctx -> help(ctx.getSource()));
        root.then(literal("help").executes(ctx -> help(ctx.getSource())));
        root.then(literal("status").executes(ctx -> status(ctx.getSource().getPlayerOrException())));
        root.then(literal("prices").executes(ctx -> prices(ctx.getSource())));
        root.then(literal("services").executes(ctx -> services(ctx.getSource())));
        root.then(literal("plans").executes(ctx -> plans(ctx.getSource())));
        var buy = literal("buy");
        for (ServiceType service : ServiceType.values()) {
            buy.then(literal(service.id()).executes(ctx -> buy(ctx.getSource().getPlayerOrException(), service, null)));
        }
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            buy.then(literal(bundle.id()).executes(ctx -> buy(ctx.getSource().getPlayerOrException(), null, bundle)));
        }
        root.then(buy);
        var autoRenew = literal("autorenew");
        for (ServiceType service : ServiceType.values()) {
            autoRenew.then(literal(service.id())
                    .then(literal("on").executes(ctx -> autoRenew(ctx.getSource().getPlayerOrException(), service, true)))
                    .then(literal("off").executes(ctx -> autoRenew(ctx.getSource().getPlayerOrException(), service, false))));
        }
        root.then(autoRenew);
        var exchange = literal("exchange");
        for (ExchangeOffer offer : ExchangeOffer.values()) {
            exchange.then(literal(offer.id()).then(argument("bundles", IntegerArgumentType.integer(1, ExchangeOffer.MAX_BUNDLES))
                    .executes(ctx -> exchange(ctx.getSource().getPlayerOrException(), offer,
                            IntegerArgumentType.getInteger(ctx, "bundles")))));
        }
        root.then(exchange);
        root.then(literal("debug")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .then(literal("credits").then(argument("amount", LongArgumentType.longArg(0))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            Accounts accounts = Accounts.get(player.level().getServer());
                            accounts.account(player.getUUID()).setCredits(LongArgumentType.getLong(ctx, "amount"));
                            accounts.setDirty();
                            return status(player);
                        }))));
        dispatcher.register(root);
    }

    private static int help(CommandSourceStack source) {
        for (int i = 1; i <= 8; i++) source.sendSystemMessage(Component.translatable("blockbill.help." + i));
        return 1;
    }

    private static int prices(CommandSourceStack source) {
        for (ServiceType service : ServiceType.values()) {
            source.sendSystemMessage(Component.translatable("blockbill.price.service", name(service), service.price(),
                    "/blockbill buy " + service.id()));
        }
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            showBundle(source, bundle);
        }
        source.sendSystemMessage(Component.translatable("hub.legacy.market"));
        return 1;
    }

    private static int plans(CommandSourceStack source) {
        for (SubscriptionBundle bundle : SubscriptionBundle.values()) {
            if (bundle.isPlan()) showBundle(source, bundle);
        }
        source.sendSystemMessage(Component.translatable("blockbill.plans.note"));
        return 1;
    }

    private static void showBundle(CommandSourceStack source, SubscriptionBundle bundle) {
        var names = Component.empty();
        for (ServiceType service : ServiceType.values()) {
            if (!bundle.services().contains(service)) continue;
            if (!names.getSiblings().isEmpty()) names.append(", ");
            names.append(name(service));
        }
        source.sendSystemMessage(Component.translatable("blockbill.catalog.bundle",
                Component.translatable("blockbill.bundle." + bundle.id()), bundle.price(), bundle.durationMinutes(), names,
                "/blockbill buy " + bundle.id()));
    }

    private static int services(CommandSourceStack source) {
        for (ServiceType service : ServiceType.values()) {
            source.sendSystemMessage(Component.translatable("blockbill.catalog.service", name(service), service.price(),
                    Component.translatable("blockbill.rule." + service.id()), "/blockbill buy " + service.id()));
        }
        return 1;
    }

    private static int autoRenew(ServerPlayer player, ServiceType service, boolean enabled) {
        if (!survival(player)) return 0;
        Accounts accounts = Accounts.get(player.level().getServer());
        accounts.account(player.getUUID()).setAutoRenew(service, enabled);
        accounts.setDirty();
        player.sendSystemMessage(Component.translatable("blockbill.autorenew.changed", name(service),
                Component.translatable(enabled ? "blockbill.autorenew.on" : "blockbill.autorenew.off"), service.price()));
        return 1;
    }

    private static int status(ServerPlayer player) {
        PlayerAccount account = Accounts.get(player.level().getServer()).account(player.getUUID());
        player.sendSystemMessage(Component.translatable("blockbill.status.balance", account.formattedCredits()));
        player.sendSystemMessage(Component.translatable("blockbill.status.trial", time(SubscriptionEngine.trialRemainingTicks(account))));
        for (ServiceType service : ServiceType.values()) {
            player.sendSystemMessage(Component.translatable("blockbill.status.service", name(service),
                    time(SubscriptionEngine.remainingTicks(account, service)),
                    Component.translatable(account.autoRenew(service) ? "blockbill.autorenew.on" : "blockbill.autorenew.off")));
        }
        if (player.gameMode() != GameType.SURVIVAL) {
            player.sendSystemMessage(Component.translatable("blockbill.status.paused"));
        }
        return 1;
    }

    private static int buy(ServerPlayer player, ServiceType service, SubscriptionBundle bundle) {
        if (!survival(player)) return 0;
        Accounts accounts = Accounts.get(player.level().getServer());
        PlayerAccount account = accounts.account(player.getUUID());
        TransactionResult result = bundle != null ? SubscriptionEngine.purchaseBundle(account, bundle) : SubscriptionEngine.purchase(account, service);
        if (result != TransactionResult.SUCCESS) return failure(player, result);
        accounts.setDirty();
        player.sendSystemMessage(Component.translatable("blockbill.buy.success",
                bundle != null ? Component.translatable("blockbill.bundle." + bundle.id()) : name(service),
                bundle != null ? bundle.durationMinutes() : SubscriptionEngine.PERIOD_TICKS / 1200, account.formattedCredits()));
        return 1;
    }

    private static int exchange(ServerPlayer player, ExchangeOffer offer, int bundles) {
        if (!survival(player)) return 0;
        Accounts accounts = Accounts.get(player.level().getServer());
        PlayerAccount account = accounts.account(player.getUUID());
        TransactionResult result = MarketSales.exchange(player, account, offer, bundles);
        if (result != TransactionResult.SUCCESS) return failure(player, result);
        accounts.setDirty();
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        player.sendSystemMessage(Component.translatable("hub.sale.success"));
        player.sendSystemMessage(Component.translatable("blockbill.status.balance", account.formattedCredits()));
        return 1;
    }

    private static boolean survival(ServerPlayer player) {
        if (player.gameMode() == GameType.SURVIVAL) return true;
        failure(player, TransactionResult.NOT_SURVIVAL);
        return false;
    }

    private static int failure(ServerPlayer player, TransactionResult result) {
        player.sendSystemMessage(Component.translatable("blockbill.error." + result.name().toLowerCase(java.util.Locale.ROOT)));
        return 0;
    }

    public static Component name(ServiceType service) {
        return Component.translatable("blockbill.service." + service.id());
    }

    private static String time(long ticks) {
        long seconds = ticks / 20 + (ticks % 20 == 0 ? 0 : 1);
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
