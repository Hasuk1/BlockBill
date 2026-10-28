package dev.blockbill.client;

import java.util.List;

import com.google.gson.Gson;
import dev.blockbill.domain.ServiceType;
import dev.blockbill.fabric.ProgressionAccess;
import dev.blockbill.network.AccessState;
import dev.blockbill.network.HubRequest;
import dev.blockbill.network.HubState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** The client displays server quotes; it never calculates a purchase or grants access. */
public final class HubClient {
    private static final Gson GSON = new Gson();

    private HubClient() { }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(HubState.TYPE, (payload, context) -> context.client().execute(() -> {
            BlockBillHubScreen screen = InventoryTabs.activeHub(context.client().gui.screen());
            if (screen != null) {
                try {
                    State state = GSON.fromJson(payload.json(), State.class);
                    if (state == null || state.services() == null || state.plans() == null
                            || state.inventory() == null || state.catalog() == null) {
                        screen.unavailable();
                    } else {
                        screen.accept(state);
                    }
                } catch (RuntimeException invalidState) {
                    screen.unavailable();
                }
            }
        }));
        ClientPlayNetworking.registerGlobalReceiver(AccessState.TYPE, (payload, context) -> context.client().execute(() ->
                ProgressionAccess.setClientSprintAllowed((payload.mask() & (1 << ServiceType.SPRINT.ordinal())) != 0)));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ProgressionAccess.setClientSprintAllowed(true));
    }

    public static boolean send(String action, String value, int slot, int amount, long revision) {
        if (!ClientPlayNetworking.canSend(HubRequest.TYPE)) return false;
        ClientPlayNetworking.send(new HubRequest(action, value, slot, amount, revision));
        return true;
    }

    public record State(long revision, String balance, boolean survival, long trialTicks, long adTicks,
                        long adCooldownTicks, int adWatchTicks, boolean adWatching, String message,
                        List<Service> services, List<Plan> plans, List<InventoryItem> inventory,
                        List<CatalogItem> catalog, int catalogPage, int catalogPages, String catalogSearch) { }

    public record Service(String id, long price, long remainingTicks, boolean autoRenew, boolean access) { }
    public record Plan(String id, long price, long minutes, List<String> services) { }
    public record InventoryItem(int slot, String item, int count, long unitPriceCents, String blocked) { }
    public record CatalogItem(String item, long unitPriceCents) { }
}
