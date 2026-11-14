package io.github.sotadevop.kingdomquest;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entry point. Grows into the kingdom systems (charter, building, NPCs) from v0.1 on. */
public class KingdomQuest implements ModInitializer {
	public static final String MOD_ID = "kingdomquest";
	public static final Logger LOG = LoggerFactory.getLogger("KingdomQuest");

	@Override
	public void onInitialize() {
		LOG.info("KingdomQuest loaded");
	}
}
