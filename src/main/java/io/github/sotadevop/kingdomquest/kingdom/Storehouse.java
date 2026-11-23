package io.github.sotadevop.kingdomquest.kingdom;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.sotadevop.kingdomquest.KingdomQuest;
import io.github.sotadevop.kingdomquest.building.Template;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The storehouse: all production is delivered here as simulated stock (counts in the world's save data, not items in
 * blocks), shown in a ledger on a lectern in the hall. The lord takes goods out, puts his own in (one slot or
 * everything but the hotbar and armor), sets a stock limit per item and switches single drops of a producer off,
 * buys production levels, and can unload the stock into the hall's real chests. The hall fills visibly with crates,
 * sacks and barrels as the stock grows. Every change is made by the server, after checking the packet; the stock is
 * saved with the world (an attachment of the overworld), so it is neither lost nor doubled on reload.
 */
public final class Storehouse {
	static final String TRACK = Progression.STOREHOUSE;
	/** Room per storehouse level (0 = none built), in stacks: 10, 20, 40, 80, 160 chests. */
	private static final int[] CAPACITY = {0, 270, 540, 1080, 2160, 4320};
	private static final int STAGES = 8;
	/** The ledger is used standing at it. */
	private static final double REACH = 8;
	private static final int NO_DROPS = Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS;
	/** Workers.Spec coordinate of the storekeeper: his post comes from the template (next to the ledger). */
	static final int FROM_TEMPLATE = 0;

	/** One kind of goods in store: the item (count 1, with its components), how many, when some last came in. */
	record Line(int id, ItemStack item, long count, long since) {
		static final Codec<Line> CODEC = RecordCodecBuilder.create(i -> i.group(
				Codec.INT.fieldOf("id").forGetter(Line::id),
				ItemStack.CODEC.fieldOf("item").forGetter(Line::item),
				Codec.LONG.fieldOf("count").forGetter(Line::count),
				Codec.LONG.fieldOf("since").forGetter(Line::since)
		).apply(i, Line::new));
	}

	/**
	 * The saved stock of one settlement.
	 *
	 * @param limits stock limit per item id (production of it waits there)
	 * @param off    switched-off drops, "producer|item"
	 * @param last   game time of the last production round
	 * @param carry  fractions of an item made but not delivered yet, per "producer|item"
	 * @param next   id of the next new line
	 */
	record Store(List<Line> lines, Map<String, Integer> limits, List<String> off, long last, Map<String, Double> carry, int next) {
		// a line that can't be read (an item of a removed mod) is left out with a warning, the rest of the stock stays
		static final Codec<Store> CODEC = RecordCodecBuilder.create(i -> i.group(
				Line.CODEC.listOf().promotePartial(e -> KingdomQuest.LOG.warn("Storehouse: a stock line could not be read: {}", e))
						.optionalFieldOf("lines", List.of()).forGetter(Store::lines),
				Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("limits", Map.of()).forGetter(Store::limits),
				Codec.STRING.listOf().optionalFieldOf("off", List.of()).forGetter(Store::off),
				Codec.LONG.optionalFieldOf("last", 0L).forGetter(Store::last),
				Codec.unboundedMap(Codec.STRING, Codec.DOUBLE).optionalFieldOf("carry", Map.of()).forGetter(Store::carry),
				Codec.INT.optionalFieldOf("next", 0).forGetter(Store::next)
		).apply(i, Store::new));
		static final Store EMPTY = new Store(List.of(), Map.of(), List.of(), 0, Map.of(), 0);
	}

	public static final AttachmentType<Map<String, Store>> DATA = AttachmentRegistry.<Map<String, Store>>builder()
			.persistent(Codec.unboundedMap(Codec.STRING, Store.CODEC))
			.initializer(Map::of)
			.buildAndRegister(Identifier.fromNamespaceAndPath(KingdomQuest.MOD_ID, "storehouse"));

	private Storehouse() {
	}

	static void register() {
		PayloadTypeRegistry.clientboundPlay().register(StorePayload.TYPE, StorePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(StoreActionPayload.TYPE, StoreActionPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(StoreActionPayload.TYPE, (payload, context) -> handle(context.player(), payload));
		// the ledger: the lectern of the storehouse opens the stores instead of a book
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!(player instanceof ServerPlayer sp) || level.dimension() != Level.OVERWORLD
					|| !(level.getBlockState(hit.getBlockPos()).getBlock() instanceof LecternBlock)) {
				return InteractionResult.PASS;
			}
			Settlement s = atLedger(sp.level().getServer(), hit.getBlockPos());
			if (s == null) {
				return InteractionResult.PASS;
			}
			if (hand == InteractionHand.MAIN_HAND) {
				open(sp, s);
			}
			return InteractionResult.SUCCESS;
		});
	}

	// ---- the stock ----

	/** A working copy of a settlement's stock: changed, then saved in one go. */
	static final class Stock {
		final List<Line> lines;
		final Map<String, Integer> limits;
		final Set<String> off;
		final Map<String, Double> carry;
		long last;
		int next;
		/** Stacks taken up. */
		int used;

		Stock(Store st) {
			this.lines = new ArrayList<>();
			st.lines().forEach(l -> this.lines.add(new Line(l.id(), l.item().copyWithCount(1), l.count(), l.since())));
			this.limits = new HashMap<>(st.limits());
			this.off = new LinkedHashSet<>(st.off());
			this.carry = new HashMap<>(st.carry());
			this.last = st.last();
			this.next = Math.max(st.next(), this.lines.stream().mapToInt(l -> l.id() + 1).max().orElse(0));
			for (Line l : this.lines) {
				this.used += slots(l.count(), l.item().getMaxStackSize());
			}
		}

		Store freeze() {
			return new Store(this.lines.stream().filter(l -> l.count() > 0).toList(), Map.copyOf(this.limits), List.copyOf(this.off), this.last,
					Map.copyOf(this.carry), this.next);
		}

		void save(MinecraftServer server, Settlement s) {
			Map<String, Store> all = new HashMap<>(server.overworld().getAttachedOrCreate(DATA));
			all.put(s.id().toString(), freeze());
			server.overworld().setAttached(DATA, Map.copyOf(all));
		}

		int indexOf(ItemStack item) {
			for (int i = 0; i < this.lines.size(); i++) {
				if (ItemStack.isSameItemSameComponents(this.lines.get(i).item(), item)) {
					return i;
				}
			}
			return -1;
		}

		int indexOfId(int id) {
			for (int i = 0; i < this.lines.size(); i++) {
				if (this.lines.get(i).id() == id) {
					return i;
				}
			}
			return -1;
		}

		long count(ItemStack item) {
			int i = indexOf(item);
			return i < 0 ? 0 : this.lines.get(i).count();
		}

		/**
		 * Puts up to n of the item in store: as much as the room (and with {@code limited} the item's stock limit)
		 * allows. Returns how many went in.
		 */
		long add(ItemStack item, long n, int capacity, boolean limited, long now) {
			int i = indexOf(item);
			long have = i < 0 ? 0 : this.lines.get(i).count();
			int max = item.getMaxStackSize();
			long partial = have % max == 0 ? 0 : max - have % max;
			long ok = Math.min(n, Math.max(0, (long) (capacity - this.used) * max + partial));
			Integer limit = limited ? this.limits.get(itemKey(item)) : null;
			if (limit != null) {
				ok = Math.min(ok, Math.max(0, limit - have));
			}
			return ok <= 0 ? 0 : put(i, item, have + ok, now) - have;
		}

		/** Puts goods in whatever the room: what was in a crate the mod takes away must not be lost. */
		void force(ItemStack item, long n, long now) {
			int i = indexOf(item);
			put(i, item, (i < 0 ? 0 : this.lines.get(i).count()) + n, now);
		}

		/** Takes up to n from a line; returns how many were taken. */
		long remove(int index, long n) {
			Line l = this.lines.get(index);
			long take = Math.min(n, l.count());
			put(index, l.item(), l.count() - take, l.since());
			return take;
		}

		private long put(int index, ItemStack item, long count, long since) {
			int max = item.getMaxStackSize();
			if (index < 0) {
				this.lines.add(new Line(this.next++, item.copyWithCount(1), count, since));
			} else {
				Line l = this.lines.get(index);
				this.used -= slots(l.count(), max);
				this.lines.set(index, new Line(l.id(), l.item(), count, since));
			}
			this.used += slots(count, max);
			return count;
		}
	}

	private static int slots(long count, int max) {
		return (int) ((count + max - 1) / max);
	}

	static String itemKey(ItemStack item) {
		return BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
	}

	static Stock stock(MinecraftServer server, Settlement s) {
		return new Stock(server.overworld().getAttachedOrCreate(DATA).getOrDefault(s.id().toString(), Store.EMPTY));
	}

	static boolean built(Settlement s) {
		return s.level(TRACK) > 0;
	}

	static int capacity(Settlement s) {
		return CAPACITY[Math.max(0, Math.min(CAPACITY.length - 1, s.level(TRACK)))];
	}

	/**
	 * The tab an item is listed under: "crops" (and food), "mob_drops", "ores", "blocks" or "misc". Shared with the
	 * screen, so both sort the same way.
	 */
	public static String tab(ItemStack item) {
		String id = BuiltInRegistries.ITEM.getKey(item.getItem()).getPath();
		if (MOB_DROPS.contains(id) || id.endsWith("_wool") || id.endsWith("_skull") || id.endsWith("_scute")) {
			return "mob_drops";
		}
		if (ORES.contains(id) || id.startsWith("raw_") || id.endsWith("_ingot") || id.endsWith("_nugget") || id.endsWith("_ore")) {
			return "ores";
		}
		if (CROPS.contains(id) || id.endsWith("_seeds") || id.endsWith("_sapling") || item.has(DataComponents.FOOD)) {
			return "crops";
		}
		return item.getItem() instanceof BlockItem ? "blocks" : "misc";
	}

	private static final Set<String> MOB_DROPS = Set.of("rotten_flesh", "bone", "string", "spider_eye", "gunpowder", "arrow", "slime_ball",
			"ender_pearl", "blaze_rod", "ghast_tear", "magma_cream", "phantom_membrane", "shulker_shell", "breeze_rod", "feather", "leather",
			"rabbit_hide", "rabbit_foot", "ink_sac", "glow_ink_sac", "prismarine_shard", "prismarine_crystals", "nautilus_shell", "egg",
			"beef", "porkchop", "chicken", "mutton", "rabbit", "cod", "salmon", "tropical_fish", "pufferfish", "honeycomb", "honey_bottle", "bone_meal");
	private static final Set<String> ORES = Set.of("coal", "charcoal", "diamond", "emerald", "lapis_lazuli", "redstone", "quartz", "netherite_scrap",
			"amethyst_shard", "glowstone_dust", "flint", "ancient_debris", "obsidian", "crying_obsidian");
	private static final Set<String> CROPS = Set.of("wheat", "carrot", "potato", "beetroot", "pumpkin", "melon", "melon_slice", "sugar_cane",
			"bamboo", "cactus", "cocoa_beans", "sweet_berries", "glow_berries", "nether_wart", "chorus_fruit", "apple", "pitcher_pod", "kelp",
			"lily_pad", "poppy", "dandelion", "hay_block");

	// ---- the hall ----

	private static String job(Settlement s) {
		Progression.Step step = Progression.current(s, TRACK);
		return step == null ? null : s.style() + "/" + step.building();
	}

	/** The ledger's lectern in the world, or null without a storehouse. */
	static BlockPos ledger(Settlement s) {
		String job = job(s);
		Template t = job == null ? null : Template.get(job);
		BlockPos off = t == null ? null : t.storageOffset(Rotation.values()[s.rotation()]);
		return off == null ? null : s.heart().offset(off).above(Settlements.lift(s, job));
	}

	/** Where the storekeeper stands: beside the ledger, where the lord reads it. */
	static BlockPos keeperPost(Settlement s) {
		BlockPos l = ledger(s);
		return l == null ? null : l.offset(Template.rotate(new BlockPos(1, 0, 1), Rotation.values()[s.rotation()]));
	}

	private static Settlement atLedger(MinecraftServer server, BlockPos pos) {
		for (Settlement s : Settlements.all(server)) {
			if (built(s) && pos.equals(ledger(s))) {
				return s;
			}
		}
		return null;
	}

	/**
	 * Shows the stock in the hall: the more is stored, the more of the template's goods (crates, sacks, hay, jars)
	 * stand in the bays, in 8 stages. They count as built (they drop nothing); what someone put in a crate goes into
	 * the stock when the crate is taken away. Waits while the hall is being built.
	 */
	static void updateFill(ServerLevel level, Settlement given, Stock stock) {
		Settlement s = Settlements.byHeart(level.getServer(), given.heart());
		String job = s == null ? null : job(s);
		Template t = job == null ? null : Template.get(job);
		if (t == null || TRACK.equals(s.jobTrack()) || !level.isLoaded(s.heart())) {
			return;
		}
		int capacity = capacity(s);
		int stage = capacity <= 0 || stock.used <= 0 ? 0 : Math.min(STAGES, (int) Math.ceil((double) STAGES * stock.used / capacity));
		BlockPos base = s.heart().above(Settlements.lift(s, job));
		List<Template.Placement> fill = t.fillPlacements(Rotation.values()[s.rotation()]);
		List<Long> built = new ArrayList<>(s.built().getOrDefault(TRACK, List.of()));
		Set<Long> mine = new HashSet<>(built);
		boolean changed = false;
		boolean forced = false;
		long now = level.getGameTime();
		for (int i = 0; i < fill.size(); i++) {
			Template.Placement p = fill.get(i);
			if (p == null) {
				continue;
			}
			BlockPos pos = base.offset(p.offset());
			BlockState here = level.getBlockState(pos);
			if (t.fillStages[i] <= stage) {
				if (here.isAir()) {
					level.setBlock(pos, p.state(), Block.UPDATE_ALL);
					if (mine.add(pos.asLong())) {
						built.add(pos.asLong());
					}
					changed = true;
				}
			} else if (here.is(p.state().getBlock()) && mine.contains(pos.asLong())) {
				if (level.getBlockEntity(pos) instanceof Container c) {
					for (int k = 0; k < c.getContainerSize(); k++) {
						ItemStack st = c.getItem(k);
						if (!st.isEmpty()) {
							stock.force(st, st.getCount(), now);
							forced = true;
						}
					}
					c.clearContent();
				}
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), NO_DROPS);
				built.remove(pos.asLong());
				mine.remove(pos.asLong());
				changed = true;
			}
		}
		if (forced) {
			stock.save(level.getServer(), s);
		}
		if (changed) {
			Settlements.put(level.getServer(), s.withBuilt(TRACK, built));
		}
	}

	/** How many of the hall's goods blocks stand in the world. */
	static int shown(ServerLevel level, Settlement s) {
		String job = job(s);
		Template t = job == null ? null : Template.get(job);
		if (t == null) {
			return 0;
		}
		BlockPos base = s.heart().above(Settlements.lift(s, job));
		int n = 0;
		for (Template.Placement p : t.fillPlacements(Rotation.values()[s.rotation()])) {
			n += p != null && level.getBlockState(base.offset(p.offset())).is(p.state().getBlock()) ? 1 : 0;
		}
		return n;
	}

	/** The hall's real chests and barrels (the goods in the bays are not among them). */
	private static List<Container> chests(ServerLevel level, Settlement s) {
		String job = job(s);
		Template t = job == null ? null : Template.get(job);
		List<Container> out = new ArrayList<>();
		if (t == null) {
			return out;
		}
		BlockPos base = s.heart().above(Settlements.lift(s, job));
		for (Template.Placement p : t.placements("white", Rotation.values()[s.rotation()])) {
			if ((p.state().getBlock() instanceof ChestBlock || p.state().getBlock() instanceof BarrelBlock)
					&& level.getBlockEntity(base.offset(p.offset())) instanceof Container c) {
				out.add(c);
			}
		}
		return out;
	}

	/** Moves stock into the hall's real chests, the biggest lines first, as far as they have room. Returns the items moved. */
	static long unload(ServerLevel level, Settlement s, Stock stock) {
		List<Container> chests = chests(level, s);
		List<Line> order = new ArrayList<>(stock.lines);
		order.sort(Comparator.comparingLong(Line::count).reversed());
		long moved = 0;
		for (Line l : order) {
			long left = l.count();
			for (Container c : chests) {
				while (left > 0) {
					ItemStack part = l.item().copyWithCount((int) Math.min(left, l.item().getMaxStackSize()));
					int before = part.getCount();
					Production.insert(c, part);
					left -= before - part.getCount();
					if (!part.isEmpty()) {
						break;
					}
				}
				c.setChanged();
			}
			long gone = l.count() - left;
			if (gone > 0) {
				moved += stock.remove(stock.indexOfId(l.id()), gone);
			}
		}
		return moved;
	}

	// ---- the lord at the ledger ----

	/** How many of the item fit into the main inventory and hotbar (what {@link Inventory#add} can place). */
	private static long room(Inventory inv, ItemStack item) {
		long room = 0;
		for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
			ItemStack slot = inv.getItem(i);
			if (slot.isEmpty()) {
				room += item.getMaxStackSize();
			} else if (ItemStack.isSameItemSameComponents(slot, item)) {
				room += Math.max(0, slot.getMaxStackSize() - slot.getCount());
			}
		}
		return room;
	}

	/** Hands up to {@code want} of a line to the player, only as much as his inventory takes; returns how many. */
	static long give(ServerPlayer player, Stock stock, int index, long want) {
		Line l = stock.lines.get(index);
		Inventory inv = player.getInventory();
		long n = Math.min(want, Math.min(l.count(), room(inv, l.item())));
		long given = 0;
		while (given < n) {
			int k = (int) Math.min(n - given, l.item().getMaxStackSize());
			ItemStack part = l.item().copyWithCount(k);
			inv.add(part);
			given += k - part.getCount();
			if (!part.isEmpty()) {
				break;
			}
		}
		inv.setChanged();
		return stock.remove(index, given);
	}

	/** Puts what lies in an inventory slot into store, as far as there is room; returns how many. */
	static long deposit(ServerPlayer player, Stock stock, int slot, int capacity, long now) {
		Inventory inv = player.getInventory();
		ItemStack st = inv.getItem(slot);
		if (st.isEmpty()) {
			return 0;
		}
		long ok = stock.add(st, st.getCount(), capacity, false, now);
		if (ok > 0) {
			st.shrink((int) ok);
			if (st.isEmpty()) {
				inv.setItem(slot, ItemStack.EMPTY);
			}
			inv.setChanged();
		}
		return ok;
	}

	static void open(ServerPlayer player, Settlement s) {
		if (!s.owner().equals(player.getUUID())) {
			People.say(player, "Storekeeper", "Only the lord of " + s.name() + " may open the ledger.");
			return;
		}
		handle(player, new StoreActionPayload(s.heart(), "open", 0, 0));
	}

	/** Every ledger action, checked here: the lord, at the ledger, of a built storehouse; ids, slots and amounts in range. */
	static void handle(ServerPlayer player, StoreActionPayload p) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.overworld();
		Settlement s = Settlements.byHeart(server, p.heart());
		if (s == null || player.level() != level || !s.owner().equals(player.getUUID()) || !built(s)) {
			return;
		}
		BlockPos ledger = ledger(s);
		if (ledger == null || player.distanceToSqr(Vec3.atCenterOf(ledger)) > REACH * REACH) {
			return;
		}
		Production.produce(level, s, level.getGameTime()); // the books are brought up to date first
		s = Settlements.byHeart(server, p.heart());
		Stock stock = stock(server, s);
		long now = level.getGameTime();
		int capacity = capacity(s);
		String[] a = p.action().split("\\|", -1);
		switch (a[0]) {
			case "open" -> {
			}
			case "take" -> {
				int i = stock.indexOfId(p.id());
				if (i >= 0 && p.amount() > 0) {
					give(player, stock, i, p.amount());
				}
			}
			case "deposit" -> {
				if (p.id() >= 0 && p.id() < player.getInventory().getNonEquipmentItems().size()) {
					deposit(player, stock, p.id(), capacity, now);
				}
			}
			case "deposit_all" -> {
				long n = 0;
				// everything but the hotbar (and armor and offhand, which are not among these slots)
				for (int i = Inventory.getSelectionSize(); i < player.getInventory().getNonEquipmentItems().size(); i++) {
					n += deposit(player, stock, i, capacity, now);
				}
				if (n == 0 && stock.used >= capacity) {
					player.sendOverlayMessage(Component.translatable("kingdomquest.store.full"));
				}
			}
			case "limit" -> {
				int i = stock.indexOfId(p.id());
				if (i >= 0) {
					String key = itemKey(stock.lines.get(i).item());
					if (p.amount() <= 0) {
						stock.limits.remove(key);
					} else {
						stock.limits.put(key, Math.min(p.amount(), 1_000_000));
					}
				}
			}
			case "toggle" -> {
				Production.Producer pr = a.length == 3 ? Production.producer(a[1]) : null;
				if (pr != null && pr.outputs().stream().anyMatch(o -> o.item().equals(a[2]))) {
					String key = a[1] + "|" + a[2];
					if (!stock.off.remove(key)) {
						stock.off.add(key);
					}
				}
			}
			case "upgrade" -> {
				Production.Producer pr = a.length == 2 ? Production.producer(a[1]) : null;
				if (pr != null && Production.upgrade(player, s, pr)) {
					player.sendOverlayMessage(Component.translatable("kingdomquest.store.upgraded",
							Component.translatable("kingdomquest.producer." + pr.id()), Production.level(Settlements.byHeart(server, p.heart()), pr)));
				}
			}
			case "unload" -> {
				long moved = unload(level, s, stock);
				player.sendOverlayMessage(Component.translatable("kingdomquest.store.unloaded", moved));
			}
			default -> {
				return;
			}
		}
		stock.save(server, s);
		s = Settlements.byHeart(server, p.heart());
		updateFill(level, s, stock);
		send(player, Settlements.byHeart(server, p.heart()), stock);
	}

	private static void send(ServerPlayer player, Settlement s, Stock stock) {
		List<StorePayload.Line> lines = stock.lines.stream().filter(l -> l.count() > 0)
				.map(l -> new StorePayload.Line(l.id(), l.item(), l.count(), stock.limits.getOrDefault(itemKey(l.item()), 0), l.since())).toList();
		List<StorePayload.Prod> prods = new ArrayList<>();
		for (Production.Producer pr : Production.PRODUCERS) {
			if (!Production.active(s, pr)) {
				continue;
			}
			List<StorePayload.Out> outs = new ArrayList<>();
			for (Production.Output o : pr.outputs()) {
				ItemStack item = Production.stack(o.item(), s.style());
				if (item.isEmpty()) {
					continue;
				}
				boolean on = !stock.off.contains(Production.key(pr, o));
				outs.add(new StorePayload.Out(o.item(), item, on ? (float) Production.perDay(s, pr, o) : 0, o.level(),
						o.milestone() == null ? "" : Production.advancementTitle(o.milestone().advancement),
						o.milestone() == null || s.level(o.milestone().key()) > 0, on));
			}
			Progression.Step next = Production.nextLevel(s, pr);
			prods.add(new StorePayload.Prod(pr.id(), pr.levels() ? Production.level(s, pr) : 0, next == null ? "" : Progression.costText(next),
					next == null || next.requirementKey() == null ? "" : next.requirementKey(),
					next != null && Progression.requirementMet(player, next), next != null && Progression.canPay(player, next), outs));
		}
		ServerPlayNetworking.send(player, new StorePayload(s.heart(), s.name(), s.level(TRACK), stock.used, capacity(s), lines, prods));
	}

	// ---- field test ----

	/**
	 * Field test of the production and the storehouse in a built settlement: production over a few game days, the
	 * goods in the hall, stock limits and toggles, unloading into the chests and the save round trip. Writes a line to
	 * the report and returns problems.
	 */
	static List<String> fieldTest(ServerLevel level, Settlement given, StringBuilder report) {
		List<String> problems = new ArrayList<>();
		MinecraftServer server = level.getServer();
		for (Production.Producer pr : Production.PRODUCERS) {
			for (String adv : List.of(pr.quest4(), pr.quest5())) {
				if (!adv.isEmpty() && server.getAdvancements().get(Identifier.withDefaultNamespace(adv)) == null) {
					problems.add("production: " + pr.id() + " needs unknown advancement " + adv);
				}
			}
			for (Production.Output o : pr.outputs()) {
				if (Production.stack(o.item(), given.style()).isEmpty()) {
					problems.add("production: " + pr.id() + " makes unknown item " + o.item());
				}
			}
		}
		for (Production.Milestone m : Production.Milestone.values()) {
			if (server.getAdvancements().get(Identifier.withDefaultNamespace(m.advancement)) == null) {
				problems.add("production: milestone " + m + " has unknown advancement " + m.advancement);
			}
		}
		Settlement s = Settlements.byHeart(server, given.heart());
		if (s == null || !built(s)) {
			problems.add("storehouse not built");
			return problems;
		}
		BlockPos ledger = ledger(s);
		if (ledger == null || !(level.getBlockState(ledger).getBlock() instanceof LecternBlock)) {
			problems.add("storehouse: no ledger lectern at " + ledger);
		}
		long now = level.getGameTime();
		// one game day at level 1, then two more: output on game time, no limit while away
		Stock fresh = stock(server, s);
		fresh.lines.clear();
		fresh.used = 0;
		fresh.carry.clear();
		fresh.last = now - Production.DAY;
		fresh.save(server, s);
		long day1 = Production.produce(level, s, now);
		Stock afterDay = stock(server, s);
		afterDay.lines.clear();
		afterDay.used = 0;
		afterDay.last = now - 3L * Production.DAY;
		afterDay.save(server, s);
		long day3 = Production.produce(level, s, now);
		s = Settlements.byHeart(server, s.heart());
		Stock stock = stock(server, s);
		int capacity = capacity(s);
		if (day1 <= 0) {
			problems.add("production: nothing delivered in a game day");
		}
		if (day3 < Math.min(2 * day1, (long) capacity) && stock.used < capacity) {
			problems.add("production: 3 days gave " + day3 + " items, 1 day " + day1);
		}
		int fills = shown(level, s);
		if (fills == 0) {
			problems.add("storehouse: " + stock.used + " of " + capacity + " stacks in store but no goods shown in the hall");
		}
		// everything at level 5 with every milestone: rare drops come too
		Settlement max = s;
		for (Production.Producer pr : Production.PRODUCERS) {
			max = max.withLevel("prod_" + pr.id(), Production.MAX_LEVEL);
		}
		for (Production.Milestone m : Production.Milestone.values()) {
			max = max.withLevel(m.key(), 1);
		}
		Settlements.put(server, max);
		stock.lines.clear();
		stock.used = 0;
		stock.last = now - Production.DAY;
		stock.save(server, max);
		long top = Production.produce(level, max, now);
		stock = stock(server, max);
		int kinds = stock.lines.size();
		// stock limit and toggle: wheat stops at its limit, cobblestone not at all
		stock.lines.clear();
		stock.used = 0;
		stock.limits.put("minecraft:wheat", 100);
		stock.off.add("miner|cobblestone");
		stock.last = now - Production.DAY;
		stock.save(server, max);
		Production.produce(level, max, now);
		stock = stock(server, max);
		long wheat = stock.count(new ItemStack(net.minecraft.world.item.Items.WHEAT));
		long cobble = stock.count(new ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
		if (max.level(Progression.FARM) > 0 && wheat != 100) {
			problems.add("storehouse: wheat limit 100 but " + wheat + " in store");
		}
		if (cobble != 0) {
			problems.add("storehouse: cobblestone switched off but " + cobble + " in store");
		}
		stock.limits.clear();
		stock.off.clear();
		// unloading: what leaves the stock is in the chests, nothing more and nothing less
		long inChests = 0;
		for (Container c : chests(level, max)) {
			for (int k = 0; k < c.getContainerSize(); k++) {
				inChests -= c.getItem(k).getCount();
			}
		}
		long before = stock.lines.stream().mapToLong(Line::count).sum();
		long moved = unload(level, max, stock);
		long after = stock.lines.stream().mapToLong(Line::count).sum();
		for (Container c : chests(level, max)) {
			for (int k = 0; k < c.getContainerSize(); k++) {
				inChests += c.getItem(k).getCount();
			}
		}
		if (before - after != moved || moved != inChests) {
			problems.add("storehouse: unload moved " + moved + ", stock went down " + (before - after) + ", chests got " + inChests);
		}
		// the save round trip: what is written is what comes back
		Store saved = stock.freeze();
		var ops = level.registryAccess().createSerializationContext(NbtOps.INSTANCE);
		Store back = Store.CODEC.parse(ops, Store.CODEC.encodeStart(ops, saved).getOrThrow()).getOrThrow();
		long a = saved.lines().stream().mapToLong(Line::count).sum();
		long b = back.lines().stream().mapToLong(Line::count).sum();
		if (a != b || saved.lines().size() != back.lines().size()) {
			problems.add("storehouse: save round trip " + a + " items in " + saved.lines().size() + " lines, back " + b + " in " + back.lines().size());
		}
		stock.save(server, max);
		updateFill(level, max, stock);
		report.append("Production: level 1 made ").append(day1).append(" items in a game day, ").append(day3).append(" in three (store ")
				.append(capacity).append(" stacks); level 5 with every milestone ").append(top).append(" items of ").append(kinds)
				.append(" kinds a day. Storehouse goods shown: ").append(fills).append(" blocks; unloaded ").append(moved)
				.append(" items into the chests.\n\n");
		return problems;
	}
}
