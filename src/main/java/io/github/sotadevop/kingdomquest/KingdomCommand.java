package io.github.sotadevop.kingdomquest;

import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.kingdom.Settlements;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.sotadevop.kingdomquest.kingdom.Progression;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Predicate;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * /kingdomquest selftest: checks every building template and builds them all once (used by CI).
 * /kingdomquest test finish|max|produce|reset|set &lt;track&gt; &lt;level&gt;: test tools for the player's own settlement.
 */
final class KingdomCommand {
	private KingdomCommand() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("kingdomquest")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("selftest").executes(ctx -> {
					List<String> ids = new ArrayList<>();
					for (String style : io.github.sotadevop.kingdomquest.kingdom.Style.ALL) {
						for (int i = 1; i <= 3; i++) {
							ids.add(style + "/tent_" + i);
							if (i == 1) {
								ids.add(style + "/hamlet_1");
								ids.add(style + "/farm_1");
								ids.add(style + "/farm_2");
								ids.add(style + "/farm_3");
								for (String n : List.of("hamlet_2", "hamlet_3", "village_1", "town_1", "castle_1", "castle_2", "cellar_1", "cellar_2", "gate_1", "gate_2", "gate_3", "gate_4", "wall_1", "wall_2", "wall_3", "wall_4", "smith_1", "smith_2", "wood_1", "wood_2", "road_1", "road_2", "road_3", "road_4", "lamp_1", "lamp_2", "lamp_3", "library_1", "library_2", "brewery_1", "brewery_2", "stables_1", "stables_2", "barracks_1", "barracks_2", "guild_1", "guild_2", "guild_3", "gate_5", "cellar_3")) {
									ids.add(style + "/" + n);
								}
							}
						}
					}
					List<String> errors = Template.selfTest(ids);
					errors = new ArrayList<>(errors);
					errors.addAll(Settlements.siteTest(ctx.getSource().getServer().overworld()));
					errors.forEach(e -> KingdomQuest.LOG.error("Template error: {}", e));
					int blocks = Settlements.buildAllForTest(ctx.getSource().getServer().overworld(), new BlockPos(0, -60, 40));
					String result = errors.isEmpty() ? "Template self-test passed" : "Template self-test failed";
					KingdomQuest.LOG.info("{} ({} templates, {} blocks placed)", result, ids.size(), blocks);
					ctx.getSource().sendSuccess(() -> Component.literal(result), false);
					return errors.isEmpty() ? 1 : 0;
				}))
				.then(Commands.literal("test")
						.then(Commands.literal("finish").executes(ctx -> run(ctx, p -> Settlements.testFinish(p) != null, "Build finished.")))
						.then(Commands.literal("max").executes(ctx -> run(ctx, Settlements::testMax, "Everything built to the highest level.")))
						.then(Commands.literal("attack").executes(ctx -> run(ctx, Settlements::testAttack, "An army is on its way.")))
						.then(Commands.literal("attacks").executes(ctx -> run(ctx, Settlements::testAttacksToggle, "Done.")))
						.then(Commands.literal("produce").executes(ctx -> run(ctx, Settlements::testProduce, "Production ran once.")))
						.then(Commands.literal("reset").executes(ctx -> run(ctx, Settlements::testReset, "Settlement removed. You can found a new one.")))
						.then(Commands.literal("set").then(Commands.argument("track", StringArgumentType.word())
								.suggests((ctx, b) -> SharedSuggestionProvider.suggest(Progression.ORDER, b))
								.then(Commands.argument("level", IntegerArgumentType.integer(1, 10)).executes(ctx -> run(ctx,
										p -> Settlements.testSet(p, StringArgumentType.getString(ctx, "track"), IntegerArgumentType.getInteger(ctx, "level")),
										"Level built."))))))));
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Predicate<ServerPlayer> action, String done) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) {
			ctx.getSource().sendFailure(Component.literal("Players only."));
			return 0;
		}
		if (!action.test(player)) {
			ctx.getSource().sendFailure(Component.literal("No settlement of yours, or no such track/level."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(done), false);
		return 1;
	}
}
