package io.github.sotadevop.kingdomquest;

import io.github.sotadevop.kingdomquest.charter.RoyalCharterItem;
import io.github.sotadevop.kingdomquest.client.FoundingScreen;
import io.github.sotadevop.kingdomquest.client.KingdomScreen;
import io.github.sotadevop.kingdomquest.client.PlacementPreview;
import io.github.sotadevop.kingdomquest.kingdom.KingdomInfoPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client entry point: item screens, the kingdom menu and the placement preview. */
public class KingdomQuestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RoyalCharterItem.openScreen = FoundingScreen::open;
		ClientPlayNetworking.registerGlobalReceiver(KingdomInfoPayload.TYPE, (payload, context) -> KingdomScreen.open(payload));
		ClientTickEvents.END_CLIENT_TICK.register(PlacementPreview::tick);
	}
}
