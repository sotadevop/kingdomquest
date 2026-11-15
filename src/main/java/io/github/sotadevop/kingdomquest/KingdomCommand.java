package io.github.sotadevop.kingdomquest;

import io.github.sotadevop.kingdomquest.building.Template;
import io.github.sotadevop.kingdomquest.kingdom.Settlements;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** /kingdomquest selftest: checks every building template and builds them all once (used by CI). */
final class KingdomCommand {
	private KingdomCommand() {
	}

	static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(Commands.literal("kingdomquest")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("selftest").executes(ctx -> {
					List<String> ids = new ArrayList<>();
					for (String style : List.of("temperate", "frozen")) {
						for (int i = 1; i <= 3; i++) {
							ids.add(style + "/tent_" + i);
							if (i == 1) {
								ids.add(style + "/hamlet_1");
								ids.add(style + "/farm_1");
							}
						}
					}
					List<String> errors = Template.selfTest(ids);
					errors.forEach(e -> KingdomQuest.LOG.error("Template error: {}", e));
					int blocks = Settlements.buildAllForTest(ctx.getSource().getServer().overworld(), new BlockPos(0, -60, 40));
					String result = errors.isEmpty() ? "Template self-test passed" : "Template self-test failed";
					KingdomQuest.LOG.info("{} ({} templates, {} blocks placed)", result, ids.size(), blocks);
					ctx.getSource().sendSuccess(() -> Component.literal(result), false);
					return errors.isEmpty() ? 1 : 0;
				}))));
	}
}
