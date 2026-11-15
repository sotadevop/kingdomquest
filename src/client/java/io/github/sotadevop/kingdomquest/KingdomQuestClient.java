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
		io.github.sotadevop.kingdomquest.client.ClientConfig.load();
		RoyalCharterItem.openScreen = FoundingScreen::open;
		net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(io.github.sotadevop.kingdomquest.npc.KingdomNpc.TYPE,
				io.github.sotadevop.kingdomquest.client.NpcRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(KingdomInfoPayload.TYPE, (payload, context) -> KingdomScreen.open(payload));
		ClientTickEvents.END_CLIENT_TICK.register(PlacementPreview::tick);
	}
}
