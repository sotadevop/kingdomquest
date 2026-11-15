package io.github.sotadevop.kingdomquest.client;

import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/** Draws KingdomQuest NPCs with the player model and one of the nine vanilla default skins. */
public class NpcRenderer extends HumanoidMobRenderer<KingdomNpc, NpcRenderer.State, HumanoidModel<NpcRenderer.State>> {
	private static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

	public static class State extends HumanoidRenderState {
		int skin;
	}

	public NpcRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new HumanoidModel<>(ctx.bakeLayer(ModelLayers.PLAYER)), 0.5f);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(KingdomNpc npc, State state, float partialTick) {
		super.extractRenderState(npc, state, partialTick);
		state.skin = npc.skin();
	}

	@Override
	public Identifier getTextureLocation(State state) {
		return Identifier.withDefaultNamespace("textures/entity/player/wide/" + SKINS[Math.floorMod(state.skin, SKINS.length)] + ".png");
	}
}
