package io.github.sotadevop.kingdomquest;

import io.github.sotadevop.kingdomquest.charter.RoyalCharterItem;
import io.github.sotadevop.kingdomquest.client.FoundingScreen;
import net.fabricmc.api.ClientModInitializer;

/** Client entry point: hooks the item screens in. */
public class KingdomQuestClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		RoyalCharterItem.openScreen = FoundingScreen::open;
	}
}
