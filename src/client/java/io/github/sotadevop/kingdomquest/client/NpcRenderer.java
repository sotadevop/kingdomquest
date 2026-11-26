package io.github.sotadevop.kingdomquest.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.sotadevop.kingdomquest.npc.KingdomNpc;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;

/**
 * Draws KingdomQuest NPCs with the player model: one of the nine vanilla default skins or (single player) a skin from
 * the skins folder, with wide or slim arms. Slim people are drawn by a second renderer with the slim player model.
 */
public class NpcRenderer extends HumanoidMobRenderer<KingdomNpc, NpcRenderer.State, HumanoidModel<NpcRenderer.State>> {
	static final String[] SKINS = {"steve", "alex", "ari", "efe", "kai", "makena", "noor", "sunny", "zuri"};

	public static class State extends HumanoidRenderState {
		int skin;
		boolean slim;
	}

	/** The slim twin (null in the twin itself). */
	private final NpcRenderer slimTwin;

	public NpcRenderer(EntityRendererProvider.Context ctx) {
		this(ctx, false);
	}

	private NpcRenderer(EntityRendererProvider.Context ctx, boolean slim) {
		super(ctx, new HumanoidModel<>(ctx.bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER)), 0.5f);
		// the armour they wear is drawn (user, 2026-11-25: the knights' iron didn't show, only their horses')
		this.addLayer(new net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer<>(this, net.minecraft.client.renderer.entity.ArmorModelSet.bake(
				slim ? ModelLayers.PLAYER_SLIM_ARMOR : ModelLayers.PLAYER_ARMOR, ctx.getModelSet(), HumanoidModel::new), ctx.getEquipmentRenderer()));
		this.slimTwin = slim ? null : new NpcRenderer(ctx, true);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(KingdomNpc npc, State state, float partialTick) {
		super.extractRenderState(npc, state, partialTick);
		state.skin = npc.skin();
		state.slim = slim(npc.skin());
	}

	/** Whether a look has slim arms: as chosen, or as the skin is made (the vanilla skins are drawn wide by default). */
	static boolean slim(int skin) {
		int arms = KingdomNpc.skinArms(skin);
		if (arms != 0) {
			return arms == 2;
		}
		int base = KingdomNpc.skinBase(skin);
		if (base >= KingdomNpc.CUSTOM_SKIN) {
			CustomSkins.Skin s = CustomSkins.get(base);
			return s != null && s.slim();
		}
		// the generated women's skins are drawn for the slim model
		return io.github.sotadevop.kingdomquest.npc.Folk.generated(base)
				&& io.github.sotadevop.kingdomquest.npc.Folk.female(io.github.sotadevop.kingdomquest.npc.Folk.outfitOf(base));
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.slim && this.slimTwin != null) {
			this.slimTwin.submit(state, pose, collector, camera);
		} else {
			super.submit(state, pose, collector, camera);
		}
	}

	@Override
	public Identifier getTextureLocation(State state) {
		return texture(state.skin, state.slim);
	}

	static Identifier texture(int skin, boolean slim) {
		int base = KingdomNpc.skinBase(skin);
		if (base >= KingdomNpc.CUSTOM_SKIN) {
			Identifier own = CustomSkins.texture(base);
			if (own != null) {
				return own;
			}
			base = 0; // a skin this computer doesn't have
		}
		if (io.github.sotadevop.kingdomquest.npc.Folk.generated(base)) {
			return Identifier.fromNamespaceAndPath("kingdomquest", "textures/entity/npc/" + base + ".png");
		}
		return Identifier.withDefaultNamespace("textures/entity/player/" + (slim ? "slim/" : "wide/") + SKINS[Math.floorMod(base, SKINS.length)] + ".png");
	}
}
