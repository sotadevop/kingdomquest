"""Generates the KingdomQuest building templates (JSON) for every style and level.

Run: python3 tools/buildings.py            -> writes src/main/resources/kingdomquest/buildings/<style>/<id>.json
     python3 tools/buildings.py --render   -> also renders previews into build/previews (needs ../ref assets, see render.py)

Plot: 15 x 15 (x 0..14, z 0..14), entrance faces south (+z). y=0 is the first layer above the ground,
y=-1 is the ground itself (paths). The heart (campfire) is always at HEART, so upgrades keep it in place.
The kingdom color is written as the token {color} in block names and swapped by the mod.
"""
import json, os, sys

HEART = (7, 0, 10)
PLOT = (15, 8, 15)
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "kingdomquest", "buildings")

STYLES = {
    # Central European: canvas tents, quartz canvas with a diorite hem
    "temperate": dict(canvas="smooth_quartz_stairs", hem="polished_diorite_stairs", ridge="smooth_quartz_slab",
                      gable="white_wool", pole="spruce_fence", wood="spruce", path=("dirt_path", "coarse_dirt"), snow=False),
    # Norse: wooden A-frame huts, cobblestone hem, spruce, braziers, snow on the ground
    "frozen": dict(canvas="spruce_stairs", hem="cobblestone_stairs", ridge="spruce_slab",
                   gable="spruce_planks", pole="spruce_fence", wood="spruce", path=("coarse_dirt", "podzol"), snow=True),
    # Moorish / North African: sandstone, acacia, flat roofs, terracotta accents
    "arid": dict(canvas="smooth_sandstone_stairs", hem="sandstone_stairs", ridge="smooth_sandstone_slab",
                 gable="orange_terracotta", pole="acacia_fence", wood="acacia", path=("dirt_path", "coarse_dirt"), snow=False),
    # Scottish Highlands: grey stone, dark slate roofs, spruce
    "highland": dict(canvas="dark_oak_stairs", hem="cobblestone_stairs", ridge="dark_oak_slab",
                     gable="cobblestone", pole="spruce_fence", wood="spruce", path=("coarse_dirt", "gravel"), snow=False),
    # Southeast Asian: jungle wood and bamboo on stilts, steep leafy roofs
    "wild": dict(canvas="bamboo_mosaic_stairs", hem="jungle_stairs", ridge="bamboo_mosaic_slab",
                 gable="bamboo_planks", pole="bamboo_fence", wood="jungle", path=("dirt_path", "rooted_dirt"), snow=False),
}

# per style building materials for the bigger buildings
MAT = {
    "temperate": dict(roof="brick_stairs", roof_slab="brick_slab", wall="calcite", frame="stripped_oak_log", stone="stone_bricks",
                      alt="mossy_stone_bricks", top="stone_brick_wall", log="oak_log"),
    "frozen": dict(roof="spruce_stairs", roof_slab="spruce_slab", wall="spruce_planks", frame="stripped_spruce_log", stone="cobblestone",
                   alt="mossy_cobblestone", top="cobblestone_wall", log="spruce_log"),
    "arid": dict(roof="smooth_sandstone_stairs", roof_slab="smooth_sandstone_slab", wall="smooth_sandstone", frame="stripped_acacia_log",
                 stone="sandstone", alt="chiseled_sandstone", top="sandstone_wall", log="acacia_log"),
    "highland": dict(roof="deepslate_tile_stairs", roof_slab="deepslate_tile_slab", wall="cobblestone", frame="stripped_spruce_log",
                     stone="stone_bricks", alt="cracked_stone_bricks", top="cobblestone_wall", log="spruce_log"),
    "wild": dict(roof="jungle_stairs", roof_slab="jungle_slab", wall="bamboo_planks", frame="stripped_jungle_log", stone="mossy_cobblestone",
                 alt="cobblestone", top="mossy_cobblestone_wall", log="jungle_log"),
}


class B:
    def __init__(self):
        self.b = {}
        self.seg = None  # road/lamp segment of the next blocks (see SEGMENTS), None = always
        self.segs = {}
        self.anchor = None  # terrain-following templates: column whose ground height this block follows
        self.anchors = {}

    def put(self, x, y, z, name, **p):
        self.b[(x, y, z)] = (name if ":" in name or name.startswith("{") else "minecraft:" + name,
                             {k: str(v).lower() for k, v in p.items()})
        if self.seg is not None:
            self.segs[(x, y, z)] = self.seg
        if self.anchor is not None:
            self.anchors[(x, y, z)] = self.anchor


ST = dict(half="bottom", shape="straight", waterlogged="false")


def a_frame(b, s, x0, z0, length, width=5, open_front=True, crossed=False, rise=1):
    """Hollow A-frame tent/hut, entrance at z0+length (south). width 5 or 7. {rise} blocks of upright side wall
    under the slopes, so you can stand inside without lanterns in your face."""
    half = width // 2
    for z in range(z0, z0 + length):
        for y in range(rise):
            b.put(x0, y, z, s["gable"])
            b.put(x0 + width - 1, y, z, s["gable"])
        for i in range(half):
            mat = s["hem"] if i == 0 else s["canvas"]
            b.put(x0 + i, i + rise, z, mat, facing="east", **ST)
            b.put(x0 + width - 1 - i, i + rise, z, mat, facing="west", **ST)
        b.put(x0 + half, half + rise, z, s["ridge"], type="bottom", waterlogged="false")
    # back gable closed
    for y in range(rise):
        for x in range(x0, x0 + width):
            b.put(x, y, z0, s["gable"])
    for i in range(1, half + 1):
        for x in range(x0 + i, x0 + width - i):
            b.put(x, i - 1 + rise, z0, s["gable"])
    # ridge pole tips
    b.put(x0 + half, half + rise, z0 - 1, s["pole"])
    b.put(x0 + half, half + rise, z0 + length, s["pole"])
    if crossed:  # Norse carved gable ends: crossed boards above the ridge
        for zz in (z0 - 1, z0 + length):
            b.put(x0 + half - 1, half + rise, zz, s["wood"] + "_fence")
            b.put(x0 + half + 1, half + rise, zz, s["wood"] + "_fence")
    # entrance flaps
    tz = z0 + length
    for x in (x0 + 1, x0 + width - 2):
        for y in range(rise + 1):
            b.put(x, y, tz, s["wood"] + "_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")


def awning(b, s, x0, z0, w, d, h=2):
    """Lean-to: posts at the corners, canvas slab roof."""
    for x in (x0, x0 + w - 1):
        for z in (z0, z0 + d - 1):
            for y in range(h):
                b.put(x, y, z, s["pole"])
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            b.put(x, h, z, s["ridge"], type="bottom", waterlogged="false")


def camp_common(b, s, level):
    hx, hy, hz = HEART
    b.put(hx, hy, hz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    w = s["wood"]
    b.put(hx - 2, 0, hz, f"{w}_stairs", facing="west", **ST)
    b.put(hx - 2, 0, hz + 1, f"{w}_stairs", facing="west", **ST)
    b.put(hx + 2, 0, hz - 1, f"{w}_slab", type="bottom", waterlogged="false")
    b.put(hx + 2, 0, hz, f"{w}_slab", type="bottom", waterlogged="false")
    b.put(hx + 1, 0, hz + 2, "oak_trapdoor", facing="north", half="top", open="false", powered="false", waterlogged="false")
    if s["snow"]:  # braziers flanking the fire
        for x in (hx - 3, hx + 3):
            b.put(x, 0, hz - 2, "cobblestone_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
            b.put(x, 1, hz - 2, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")


def banner_pole(b, s, x, z, h=2):
    for y in range(h):
        b.put(x, y, z, s["pole"])
    b.put(x, h, z, "{color}_banner", rotation="8")


def interior(b, x_bed, z_bed, x_store, z_store):
    b.put(x_bed, 0, z_bed, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x_bed, 0, z_bed + 1, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x_store, 0, z_store, "barrel", facing="up", open="false")
    b.put(x_store, 0, z_store + 1, "chest", facing="west", type="single", waterlogged="false")


def ground(b, s, cells):
    for i, (x, z) in enumerate(sorted(cells)):
        b.put(x, -1, z, s["path"][0] if (x * 7 + z * 3) % 4 else s["path"][1])


def snow(b, s, spots):
    if s["snow"]:
        for x, z in spots:
            if (x, 0, z) not in b.b:
                b.put(x, 0, z, "snow", layers="1")


def tent(style, level):
    s = STYLES[style]
    b = B()
    w = s["wood"]
    norse = style == "frozen"
    hx, hy, hz = HEART
    path = {(hx, z) for z in range(7, 15)} | {(hx + dx, hz + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)}
    if level == 1:
        a_frame(b, s, 5, 2, 5, crossed=norse)
        b.put(7, 2, 3, "lantern", hanging="true", waterlogged="false")
        interior(b, 6, 3, 8, 3)
        banner_pole(b, s, 11, 8)
        b.put(2, 0, 7, "crafting_table")
        b.put(2, 0, 9, "oak_log", axis="y")
        for z in (10, 11):
            b.put(2, 0, z, f"{w}_log", axis="x")
        b.put(2, 1, 10, f"{w}_log", axis="x")
        b.put(11, 0, 4, "barrel", facing="up", open="false")
        b.put(11, 0, 3, "composter", level="0")
    elif level == 2:
        a_frame(b, s, 5, 1, 7, crossed=norse)
        b.put(7, 2, 3, "lantern", hanging="true", waterlogged="false")
        b.put(7, 2, 6, "lantern", hanging="true", waterlogged="false")
        interior(b, 6, 2, 8, 2)
        b.put(6, 0, 5, f"{w}_stairs", facing="east", **ST)
        b.put(8, 0, 5, "barrel", facing="up", open="false")
        # workshop awning on the east side
        awning(b, s, 11, 2, 3, 4)
        b.put(12, 0, 3, "crafting_table")
        b.put(12, 0, 4, "furnace", facing="west", lit="false")
        b.put(13, 0, 3, "barrel", facing="up", open="false")
        banner_pole(b, s, 12, 9)
        # woodpile and chopping block on the west side
        b.put(2, 0, 8, "oak_log", axis="y")
        for z in (9, 10, 11):
            b.put(2, 0, z, f"{w}_log", axis="x")
        b.put(2, 1, 10, f"{w}_log", axis="x")
        b.put(2, 0, 4, "hay_block", axis="y")
        b.put(2, 0, 5, "composter", level="0")
        path |= {(x, 7) for x in range(9, 13)}
    else:
        a_frame(b, s, 4, 1, 7, width=7, crossed=norse)
        for z in (3, 6):
            b.put(7, 3, z, "lantern", hanging="true", waterlogged="false")
        interior(b, 5, 2, 9, 2)
        b.put(5, 0, 5, f"{w}_stairs", facing="east", **ST)
        b.put(6, 0, 5, f"{w}_trapdoor", facing="north", half="top", open="false", powered="false", waterlogged="false")
        b.put(9, 0, 5, "barrel", facing="up", open="false")
        b.put(9, 0, 6, "lectern", facing="west", has_book="false", powered="false")
        # workshop awning east
        awning(b, s, 11, 2, 4, 4)
        b.put(12, 0, 3, "crafting_table")
        b.put(12, 0, 4, "furnace", facing="west", lit="false")
        b.put(13, 0, 3, "smoker", facing="west", lit="false")
        b.put(13, 0, 4, "grindstone", face="floor", facing="west")
        # supply lean-to west
        awning(b, s, 0, 2, 3, 4)
        for z in (3, 4):
            b.put(1, 0, z, "barrel", facing="up", open="false")
        b.put(1, 1, 3, "barrel", facing="up", open="false")
        b.put(0, 0, 8, "oak_log", axis="y")
        for z in (9, 10, 11):
            b.put(1, 0, z, f"{w}_log", axis="x")
        b.put(1, 1, 10, f"{w}_log", axis="x")
        banner_pole(b, s, 5, 9, 3)
        banner_pole(b, s, 9, 9, 3)
        b.put(12, 0, 10, "hay_block", axis="y")
        b.put(12, 0, 11, "composter", level="0")
        path |= {(x, 7) for x in range(10, 14)} | {(x, 8) for x in range(2, 5)}
    camp_common(b, s, level)
    ground(b, s, path)
    snow(b, s, [(x, z) for x in range(15) for z in range(15) if (x * 73856093 ^ z * 19349663) % 11 == 0])
    return b.b


# ---------------- Hamlet ----------------
HAMLET_SIZE = (23, 12, 15)
FARM_SIZE = (32, 12, 15)  # farm add-on reaches further east (pasture, smokehouse)


def porch(b, cx, z, wood, width=3, facing="north"):
    """Steps up to a door one block above the road (the house floor sits on a stone base)."""
    for x in range(cx - width // 2, cx + width // 2 + 1):
        b.put(x, 0, z, wood + "_stairs", facing=facing, **ST)


def house_temperate(b, x0=4, z0=0):
    """Central European half-timbered house (Fachwerk): cobblestone base, oak frame, calcite plaster, red tile roof."""
    w, d = 7, 9
    x1, z1 = x0 + w - 1, z0 + d - 1
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if not edge:
                b.put(x, 0, z, "spruce_planks")
                continue
            b.put(x, 0, z, "mossy_cobblestone" if (x * 3 + z) % 5 == 0 else "cobblestone")
            for y in (1, 2, 3):
                post = (x in (x0, x0 + 3, x1) and z in (z0, z1)) or (z in (z0, z0 + 4, z1) and x in (x0, x1))
                if post:
                    b.put(x, y, z, "stripped_oak_log", axis="y")
                elif y == 3:
                    b.put(x, y, z, "stripped_oak_log", axis="x" if z in (z0, z1) else "z")
                else:
                    b.put(x, y, z, "calcite")
    # windows (front, sides, back) with flower boxes under the front ones
    for x in (x0 + 1, x1 - 1):
        b.put(x, 2, z1, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
        b.put(x, 0, z1 + 1, "flowering_azalea_leaves", distance="7", persistent="true", waterlogged="false")
        for sx in (x - 1, x + 1):
            if sx not in (x0 + 2, x0 + 4):
                b.put(sx, 2, z1 + 1, "spruce_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
    for z in (z0 + 2, z0 + 6):
        for x in (x0, x1):
            b.put(x, 2, z, "glass_pane", north="true", south="true", east="false", west="false", waterlogged="false")
    b.put(x0 + 5, 2, z0, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
    # door
    cx = x0 + 3
    porch(b, cx, z1 + 1, "spruce")
    b.put(cx, 1, z1, "oak_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(cx, 2, z1, "oak_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    # steep gable roof, ridge along z, 1 block overhang all around
    for z in range(z0 - 1, z1 + 2):
        for i in range(4):
            b.put(x0 - 1 + i, 3 + i, z, "brick_stairs", facing="east", **ST)
            b.put(x1 + 1 - i, 3 + i, z, "brick_stairs", facing="west", **ST)
        b.put(cx, 7, z, "brick_slab", type="bottom", waterlogged="false")
        # eave trim: upside-down stairs under the overhang
        b.put(x0 - 1, 2, z, "spruce_stairs", facing="west", half="top", shape="straight", waterlogged="false") if z in (z0 - 1, z1 + 1) else None
    # gable walls
    for zz in (z0, z1):
        for y in range(4, 7):
            for x in range(x0 + y - 3, x1 - y + 4):
                b.put(x, y, zz, "stripped_oak_log" if x == cx else "calcite", **({"axis": "y"} if x == cx else {}))
    b.put(cx - 1, 5, z1, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
    # chimney with smoke
    for y in range(1, 9):
        b.put(x1 - 1, y, z0 + 2, "cobblestone")
    b.put(x1 - 1, 9, z0 + 2, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 3, "furnace", facing="west", lit="false")
    # interior
    b.put(x0 + 1, 1, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 1, 1, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(cx, 1, z0 + 4, "spruce_fence")
    b.put(cx, 2, z0 + 4, "spruce_pressure_plate", powered="false")
    b.put(cx - 1, 1, z0 + 4, "spruce_stairs", facing="east", **ST)
    b.put(cx + 1, 1, z0 + 4, "spruce_stairs", facing="west", **ST)
    b.put(x1 - 1, 1, z0 + 6, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 7, "barrel", facing="up", open="false")
    b.put(x0 + 1, 1, z0 + 6, "crafting_table")
    b.put(cx, 6, z0 + 4, "lantern", hanging="true", waterlogged="false")
    b.put(x0 + 1, 1, z0 + 7, "white_carpet")


def house_norse(b, x0=4, z0=0):
    """Norse longhouse: stone footing, low spruce log walls, steep spruce roof to the ground, carved gable boards."""
    w, d = 7, 9
    x1, z1 = x0 + w - 1, z0 + d - 1
    cx = x0 + 3
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, ("cobblestone" if (x + z) % 3 else "mossy_cobblestone") if edge else "spruce_planks")
            if edge:
                for y in (1, 2):
                    corner = x in (x0, x1) and z in (z0, z1)
                    b.put(x, y, z, "spruce_log", axis="y" if corner else ("x" if z in (z0, z1) else "z"))
    # roof: from the wall top straight to the ridge, overhang at the gables
    for z in range(z0 - 1, z1 + 2):
        for i in range(4):
            b.put(x0 - 1 + i, 2 + i, z, "spruce_stairs", facing="east", **ST)
            b.put(x1 + 1 - i, 2 + i, z, "spruce_stairs", facing="west", **ST)
        b.put(cx, 6, z, "dark_oak_slab", type="bottom", waterlogged="false")
    for zz in (z0, z1):
        for y in range(3, 6):
            for x in range(x0 + y - 2, x1 - y + 3):
                b.put(x, y, zz, "stripped_spruce_log" if x == cx else "spruce_planks", **({"axis": "y"} if x == cx else {}))
        # crossed carved boards over the gable
        b.put(cx - 1, 6, zz - 1 if zz == z0 else zz + 1, "spruce_fence")
        b.put(cx + 1, 6, zz - 1 if zz == z0 else zz + 1, "spruce_fence")
    porch(b, cx, z1 + 1, "spruce")
    b.put(cx, 1, z1, "spruce_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(cx, 2, z1, "spruce_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    for z in (z0 + 3, z0 + 5):
        for x in (x0, x1):
            b.put(x, 2, z, "spruce_trapdoor", facing="west" if x == x0 else "east", half="top", open="false", powered="false", waterlogged="false")
    # long hearth in the middle of the hall
    for z in (z0 + 3, z0 + 4, z0 + 5):
        b.put(cx, 0, z, "cobblestone")
    b.put(cx, 1, z0 + 4, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(x0 + 1, 1, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 1, 1, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    for z in range(z0 + 4, z0 + 8):
        b.put(x0 + 1, 1, z, "spruce_slab", type="bottom", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 1, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 2, "barrel", facing="up", open="false")
    b.put(x1 - 1, 1, z0 + 6, "crafting_table")
    b.put(x1 - 1, 1, z0 + 7, "furnace", facing="west", lit="false")
    b.put(cx, 5, z0 + 2, "lantern", hanging="true", waterlogged="false")
    b.put(cx, 5, z0 + 6, "lantern", hanging="true", waterlogged="false")
    for x in (cx - 2, cx + 2):
        b.put(x, 0, z1 + 1, "cobblestone_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
        b.put(x, 1, z1 + 1, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")


def house_arid(b, x0=4, z0=0):
    """Moorish house: thick sandstone walls, flat roof with a low parapet, arched door, terracotta and acacia accents."""
    w, d = 7, 9
    x1, z1 = x0 + w - 1, z0 + d - 1
    cx = x0 + 3
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, "cut_sandstone" if edge else "orange_terracotta" if (x + z) % 2 else "white_terracotta")
            if edge:
                for y in range(1, 5):
                    b.put(x, y, z, "smooth_sandstone")
            if not edge:
                b.put(x, 5, z, "smooth_sandstone_slab", type="bottom", waterlogged="false")
            elif (x + z) % 2 == 0:
                b.put(x, 5, z, "sandstone_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
            else:
                b.put(x, 5, z, "smooth_sandstone")
            if not edge:
                b.put(x, 4, z, "smooth_sandstone")
    for z in (z0 + 2, z0 + 6):
        for x in (x0, x1):
            b.put(x, 2, z, "acacia_trapdoor", facing="west" if x == x0 else "east", half="top", open="true", powered="false", waterlogged="false")
    porch(b, cx, z1 + 1, "acacia")
    b.put(cx, 1, z1, "acacia_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(cx, 2, z1, "acacia_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    b.put(cx, 3, z1, "smooth_sandstone_stairs", facing="south", half="top", shape="straight", waterlogged="false")
    for x in (cx - 1, cx + 1):
        b.put(x, 3, z1 + 1, "acacia_slab", type="top", waterlogged="false")
    b.put(x0 + 1, 1, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 1, 1, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x1 - 1, 1, z0 + 1, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 2, "barrel", facing="up", open="false")
    b.put(cx, 1, z0 + 4, "decorated_pot", facing="south", cracked="false", waterlogged="false")
    b.put(cx, 3, z0 + 4, "lantern", hanging="true", waterlogged="false")
    b.put(x0 + 1, 1, z1 - 1, "furnace", facing="east", lit="false")


def house_highland(b, x0=4, z0=0):
    """Scottish highland cottage: low grey stone walls, small windows, steep dark slate roof, chimneys at both gables."""
    w, d = 7, 9
    x1, z1 = x0 + w - 1, z0 + d - 1
    cx = x0 + 3
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, ("cobblestone" if (x + z) % 3 else "mossy_cobblestone") if edge else "spruce_planks")
            if edge:
                for y in (1, 2, 3):
                    b.put(x, y, z, "stone" if (x * 3 + y + z) % 5 == 0 else "cobblestone" if (x + y) % 2 else "andesite")
    for x in (x0 + 1, x1 - 1):
        b.put(x, 2, z1, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
    for z in (z0 + 3, z0 + 5):
        for x in (x0, x1):
            b.put(x, 2, z, "glass_pane", north="true", south="true", east="false", west="false", waterlogged="false")
    porch(b, cx, z1 + 1, "spruce")
    b.put(cx, 1, z1, "spruce_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(cx, 2, z1, "spruce_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    for z in range(z0 - 1, z1 + 2):
        for i in range(4):
            b.put(x0 - 1 + i, 3 + i, z, "deepslate_tile_stairs", facing="east", **ST)
            b.put(x1 + 1 - i, 3 + i, z, "deepslate_tile_stairs", facing="west", **ST)
        b.put(cx, 7, z, "deepslate_tile_slab", type="bottom", waterlogged="false")
    for zz in (z0, z1):
        for y in range(4, 7):
            for x in range(x0 + y - 3, x1 - y + 4):
                b.put(x, y, zz, "cobblestone")
    for zz in (z0, z1):
        for y in range(1, 9):
            if zz == z0:
                b.put(cx, y, zz, "cobblestone")
    b.put(cx, 9, z0, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(cx, 1, z0 + 1, "furnace", facing="south", lit="false")
    b.put(x0 + 1, 1, z1 - 2, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 1, 1, z1 - 1, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x1 - 1, 1, z0 + 1, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 2, "barrel", facing="up", open="false")
    b.put(cx, 5, z0 + 4, "lantern", hanging="true", waterlogged="false")
    b.put(x1 - 1, 1, z1 - 1, "crafting_table")


def house_wild(b, x0=4, z0=0):
    """Southeast Asian stilt house: bamboo walls on jungle log stilts, veranda, steep jungle roof, ladder up."""
    w, d = 7, 9
    x1, z1 = x0 + w - 1, z0 + d - 1
    cx = x0 + 3
    F = 2  # floor height
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            stilt = x in (x0, x1, cx) and z in (z0, z0 + 4, z1)
            if stilt:
                for y in range(0, F):
                    b.put(x, y, z, "jungle_log", axis="y")
            b.put(x, F, z, "jungle_planks")
            edge = x in (x0, x1) or z in (z0, z1 - 2)
            if edge and z <= z1 - 2:
                for y in range(F + 1, F + 4):
                    corner = x in (x0, x1) and z in (z0, z1 - 2)
                    b.put(x, y, z, "stripped_jungle_log" if corner else "bamboo_planks", **({"axis": "y"} if corner else {}))
    # veranda in front with a railing
    for x in range(x0, x1 + 1):
        if x != cx:
            b.put(x, F + 1, z1, "bamboo_fence")
    for z in (z1 - 1, z1):
        for x in (x0, x1):
            b.put(x, F + 1, z, "bamboo_fence")
    b.put(cx, F + 1, z1 - 2, "jungle_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(cx, F + 2, z1 - 2, "jungle_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    for x in (x0 + 1, x1 - 1):
        b.put(x, F + 2, z1 - 2, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
    # steps up to the veranda: one for every block the floor is raised, the floor itself is the last
    for i in range(F + 1):
        b.put(cx, i, z1 + F + 1 - i, "jungle_stairs", facing="north", **ST)
        for y in range(i):
            b.put(cx, y, z1 + F + 1 - i, "jungle_planks")
    # steep roof with wide eaves
    for z in range(z0 - 1, z1 + 2):
        for i in range(5):
            b.put(x0 - 2 + i, F + 3 + i, z, "jungle_stairs", facing="east", **ST)
            b.put(x1 + 2 - i, F + 3 + i, z, "jungle_stairs", facing="west", **ST)
        b.put(cx, F + 8, z, "jungle_slab", type="bottom", waterlogged="false")
    for zz in (z0, z1 - 2):
        for y in range(F + 4, F + 8):
            for x in range(x0 + y - F - 3, x1 - y + F + 4):
                b.put(x, y, zz, "bamboo_planks")
    b.put(x0 + 1, F + 1, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 1, F + 1, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x1 - 1, F + 1, z0 + 1, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, F + 1, z0 + 2, "barrel", facing="up", open="false")
    b.put(cx, F + 5, z0 + 3, "lantern", hanging="true", waterlogged="false")


FARM = (22, 1, 9, 9)  # x0, z0, width, depth of the field


def farm_plot_markers(b, s):
    x0, z0, w, d = FARM
    for x in (x0 - 1, x0 + w):
        for z in (z0 - 1, z0 + d):
            b.put(x, 0, z, s["pole"])


FLIP = {"north": "south", "south": "north"}


def mirror_into(b, blocks, za, zb):
    """Copies blocks into b mirrored along z between za and zb (front becomes back)."""
    for (x, y, z), (name, props) in blocks.items():
        p = dict(props)
        if "facing" in p:
            p["facing"] = FLIP.get(p["facing"], p["facing"])
        if "north" in p and "south" in p:
            p["north"], p["south"] = props["south"], props["north"]
        if "hinge" in p:
            p["hinge"] = "right" if p["hinge"] == "left" else "left"
        b.b[(x, y, za + zb - z)] = (name, p)


HOUSES = {"temperate": None, "frozen": None, "arid": house_arid, "highland": house_highland, "wild": house_wild}


def house_fn(style):
    return {"frozen": house_norse, "arid": house_arid, "highland": house_highland, "wild": house_wild}.get(style, house_temperate)


def house(b, style, x0, z0, facing_north=False):
    t = B()
    house_fn(style)(t, x0, z0)
    if facing_north:
        mirror_into(b, t.b, z0, z0 + 8)
    else:
        b.b.update(t.b)


TAX_STORE = (12, 0, 9)
SMITH_STORE = (-15, 0, 2)
WOOD_STORE = (-28, 0, 22)


def street_lamp(b, s, x, z):
    b.put(x, 0, z, s["pole"])
    b.put(x, 1, z, s["pole"])
    b.put(x, 2, z, "lantern", hanging="false", waterlogged="false")


def hamlet(style, level):
    s = STYLES[style]
    b = B()
    w = s["wood"]
    hx, hy, hz = HEART
    if level >= 8:
        motte(b, style)
        donjon(b, style)
    elif level >= 7:
        motte(b, style)
    elif level >= 6:
        castle_keep(b, style)
    elif level >= 5:
        town_hall(b, style, 1, -4)
    else:
        house_fn(style)(b, 4, -3)
    # the forecourt: trodden ground from the door's steps to where the high street begins
    if level <= 5 and style != "wild":
        ground(b, s, {(x, 7) for x in ((6, 7, 8) if level < 5 else (5, 6, 7, 8, 9))})
    elif level == 6:
        for x in range(5, 10):
            for z in (6, 7):
                b.put(x, -1, z, "cobblestone" if (x * 7 + z * 3) % 4 else "gravel")
    b.put(hx, hy, hz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(hx - 2, 0, hz, f"{w}_stairs", facing="west", **ST)
    b.put(hx - 2, 0, hz + 1, f"{w}_stairs", facing="west", **ST)
    b.put(hx + 2, 0, hz, f"{w}_stairs", facing="east", **ST)
    b.put(hx + 2, 0, hz + 1, f"{w}_stairs", facing="east", **ST)
    banner_pole(b, s, 0, 10, 3)
    # well
    for x in (1, 3):
        for z in (12, 14):
            b.put(x, 0, z, "cobblestone_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
            b.put(x, 1, z, s["pole"])
    for x in (1, 2, 3):
        for z in (12, 13, 14):
            if (x, z) != (2, 13):
                b.put(x, -1, z, "cobblestone")
            b.put(x, 2, z, s["ridge"], type="bottom", waterlogged="false")
    b.put(2, -1, 13, "water", level="0")
    b.put(2, -2, 13, "cobblestone")
    if level >= 2:
        # second house across the street, door facing the square; tax barrel by the fire
        house(b, style, -8, 22, facing_north=True)
        b.put(*TAX_STORE, "barrel", facing="up", open="false")
    if level >= 3:
        house(b, style, 16, 22, facing_north=True)
        # market stall on the east side of the square
        for x, z in ((11, 12), (13, 12), (11, 14), (13, 14)):
            b.put(x, 0, z, s["pole"])
            b.put(x, 1, z, s["pole"])
        for x in range(11, 14):
            for z in range(12, 15):
                b.put(x, 2, z, "{color}_wool" if z == 14 else "white_wool")
        b.put(12, 0, 12, "barrel", facing="up", open="false")
        b.put(12, 0, 13, "barrel", facing="up", open="true")
    if level >= 4:
        # Village: fourth house, bell on the square, flower beds
        house(b, style, 28, 22, facing_north=True)
        b.put(hx + 1, 0, hz - 2, "bell", attachment="floor", facing="south", powered="false")
        for x, z in ((9, 15), (5, 15)):
            b.put(x, -1, z, "grass_block", snowy="false")  # the bed's own soil: a road or regrade next to it can't take it away
            b.put(x, 0, z, "sweet_berry_bush" if style == "frozen" else "rose_bush", **({"age": "3"} if style == "frozen" else {"half": "lower"}))
            if style != "frozen":
                b.put(x, 1, z, "rose_bush", half="upper")
    return b.b


def pasture(b, s):
    """Farm level 2: fenced pasture east of the field with hay, a water trough and a lean-to shelter."""
    w = s["wood"]
    x0, x1, z0, z1 = 35, 43, 0, 9
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if edge:
                if (x, z) == (39, z1):
                    b.put(x, 0, z, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
                else:
                    b.put(x, 0, z, w + "_fence")
            elif (x * 7 + z * 3) % 6 == 0:
                b.put(x, -1, z, "coarse_dirt")
    # hay and troughs at least 2 blocks from the fence, so nothing can jump out
    b.put(x0 + 5, 0, z0 + 4, "hay_block", axis="x")
    b.put(x0 + 6, 0, z0 + 4, "hay_block", axis="x")
    b.put(x0 + 3, 0, z1 - 3, "water_cauldron", level="3")
    b.put(x0 + 4, 0, z1 - 3, "water_cauldron", level="3")
    # lean-to shelter in the north-west corner
    for x in (x0 + 1, x0 + 3):
        b.put(x, 0, z0 + 1, s["pole"])
        b.put(x, 1, z0 + 1, s["pole"])
    for x in range(x0, x0 + 5):
        b.put(x, 2, z0 + 1, w + "_slab", type="bottom", waterlogged="false")
        b.put(x, 2, z0, w + "_stairs", facing="south", **ST)


def smokehouse(b, s):
    """Farm level 3: small smokehouse between the farm road and the street, where meat is cured over a smoker."""
    w = s["wood"]
    x0, x1, z0, z1 = 36, 40, 13, 16
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, "cobblestone" if edge else "packed_mud")
            if edge:
                corner = x in (x0, x1) and z in (z0, z1)
                for y in (1, 2):
                    b.put(x, y, z, s["pole"] if corner else (w + "_planks" if s["wood"] == "spruce" else "mud_bricks"))
    door = (x0 + 2, z1)
    porch(b, door[0], z1 + 1, w, width=1)
    b.put(door[0], 1, door[1], w + "_door", facing="south", half="lower", hinge="left", open="false", powered="false")
    b.put(door[0], 2, door[1], w + "_door", facing="south", half="upper", hinge="left", open="false", powered="false")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            b.put(x, 3, z, w + "_slab", type="bottom", waterlogged="false")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                b.put(x, 3, z, "cobblestone_slab", type="bottom", waterlogged="false")
    b.put(x0 + 2, 3, z0 + 1, "cobblestone")
    b.put(x0 + 2, 4, z0 + 1, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(x0 + 2, 1, z0 + 1, "smoker", facing="south", lit="true")
    b.put(x0 + 1, 1, z0 + 1, "barrel", facing="up", open="false")
    b.put(x0 + 3, 1, z0 + 1, "barrel", facing="up", open="false")
    for x in (x0 + 1, x0 + 3):
        b.put(x, 2, z1 - 1, "iron_chain", axis="y", waterlogged="false")


def farm(style, level):
    s = STYLES[style]
    b = B()
    x0, z0, w, d = FARM
    zc = z0 + d // 2
    for x in range(x0, x0 + w):
        for z in range(z0, z0 + d):
            if z == zc:
                b.put(x, -1, z, "water", level="0")
                b.put(x, -2, z, "dirt")
                continue
            b.put(x, -1, z, "farmland", moisture="7")
            crop = ("wheat", "carrots", "potatoes")[(z - z0) % 3 if z < zc else (z - zc) % 3]
            b.put(x, 0, z, crop, age="3")
    # fence with a gate on the road side (south)
    for x in range(x0 - 1, x0 + w + 1):
        for z in (z0 - 1, z0 + d):
            b.put(x, 0, z, s["wood"] + "_fence")
    for z in range(z0, z0 + d):
        for x in (x0 - 1, x0 + w):
            b.put(x, 0, z, s["wood"] + "_fence")
    b.put(x0 + w // 2, 0, z0 + d, s["wood"] + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
    # scarecrow
    sx, sz = x0 + 2, z0 + 1
    b.put(sx, -1, sz, "coarse_dirt")
    b.put(sx, 0, sz, s["wood"] + "_fence")
    b.put(sx, 1, sz, "hay_block", axis="y")
    b.put(sx, 2, sz, "carved_pumpkin", facing="south")
    # storehouse barrel: the farmer puts the harvest in here
    b.put(x0 - 2, 0, zc - 1, "barrel", facing="up", open="false")
    b.put(x0 - 2, 0, zc - 2, "composter", level="0")
    if level >= 2:
        pasture(b, s)
    if level >= 3:
        smokehouse(b, s)
    return b.b


def smithy(style, level):
    """Blacksmith add-on, west of the square: open-fronted forge under a lean-to roof, stone back wall, chimney."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    x0, x1, z0, z1 = -16, -8, 1, 8
    log = MAT[style]["frame"]
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            b.put(x, -1, z, "stone_bricks" if (x + z) % 3 else "cobblestone")
    # back wall and side walls (open front)
    for x in range(x0, x1 + 1):
        for y in range(0, 4):
            b.put(x, y, z0, "cobblestone" if (x * 5 + y) % 4 else "stone_bricks")
    for z in range(z0 + 1, z1 + 1):
        for x in (x0, x1):
            for y in range(0, 3 if level >= 2 else 1):
                b.put(x, y, z, "cobblestone" if (z + y) % 3 else "mossy_cobblestone")
    for x in (x0, x0 + 4, x1):
        for y in range(0, 3):
            b.put(x, y, z1, log, axis="y")
    for x in range(x0, x1 + 1):
        b.put(x, 3, z1, log, axis="x")
    # lean-to roof, high at the back
    for x in range(x0 - 1, x1 + 2):
        for i, z in enumerate(range(z0 - 1, z1 + 2)):
            y = 5 - i // 3
            b.put(x, y, z, MAT[style]["roof"], facing="north", **ST)
    # forge: chimney, lava, blast furnace, anvil, smithing table, grindstone, quench trough
    cx = x0 + 5
    for y in range(0, 8):
        b.put(cx, y, z0, "bricks")
    b.put(cx, 8, z0, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(cx, 0, z0 + 1, "lava_cauldron")
    b.put(cx - 1, 0, z0 + 1, "blast_furnace", facing="south", lit="true")
    b.put(cx + 1, 0, z0 + 1, "furnace", facing="south", lit="true")
    b.put(cx, 0, z0 + 4, "anvil", facing="east")
    b.put(x0 + 2, 0, z0 + 1, "smithing_table")
    b.put(x1 - 1, 0, z0 + 1, "grindstone", face="floor", facing="south")
    b.put(x1 - 1, 0, z0 + 4, "water_cauldron", level="3")
    b.put(*SMITH_STORE, "barrel", facing="up", open="false")
    b.put(x0 + 1, 0, z0 + 3, "chest", facing="east", type="single", waterlogged="false")
    b.put(cx, 3, z0 + 3, "lantern", hanging="true", waterlogged="false")
    if level >= 3:
        # the citadel smith: a netherite anvil corner and an end rod lamp for the elytra work
        b.put(x0 + 3, 0, z0 + 6, "netherite_block")
        b.put(x0 + 3, 1, z0 + 6, "smithing_table")
        b.put(x1 - 2, 2, z0 + 3, "end_rod", facing="up")
        b.put(x1 - 3, 0, z0 + 6, "purpur_block")
    if level >= 2:
        # second anvil, iron bars window, tool rack, kingdom banner on the front post
        b.put(cx - 2, 0, z0 + 5, "chipped_anvil", facing="south")
        for x in (x0 + 2, x1 - 2):
            b.put(x, 2, z0, "iron_bars", north="false", south="false", east="true", west="true", waterlogged="false")
        b.put(x0 + 1, 1, z0 + 5, w + "_trapdoor", facing="east", half="top", open="false", powered="false", waterlogged="false")
        b.put(x0 + 4, 2, z1 + 1, "{color}_wall_banner", facing="south")
    return b.b


def woodcutter(style, level):
    """Woodcutter add-on, west of the second house: log store, chopping block, shed; level 2 adds a sawmill."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    log = MAT[style]["log"]
    x0, x1, z0, z1 = -30, -23, 21, 29
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if (x * 3 + z) % 5 == 0:
                b.put(x, -1, z, "coarse_dirt")
    # open shed: posts, slab roof
    for x in (x0, x0 + 4):
        for z in (z0, z0 + 4):
            for y in range(0, 3):
                b.put(x, y, z, log, axis="y")
    for x in range(x0 - 1, x0 + 6):
        for z in range(z0 - 1, z0 + 6):
            b.put(x, 3, z, w + "_slab", type="bottom", waterlogged="false")
    # log piles inside the shed
    for x in range(x0 + 1, x0 + 4):
        for y in range(0, 2 if x != x0 + 2 else 3):
            b.put(x, y, z0 + 1, log, axis="x")
            b.put(x, y, z0 + 2, log, axis="x")
    b.put(*WOOD_STORE, "barrel", facing="up", open="false")
    # chopping block and stacked logs outside
    b.put(x0 + 6, 0, z0 + 6, "stripped_" + log, axis="y")
    for z in range(z0 + 7, z1):
        b.put(x1, 0, z, log, axis="z")
        if z % 2:
            b.put(x1, 1, z, log, axis="z")
    b.put(x0 + 2, 0, z1 - 1, "composter", level="0")
    if level >= 2:
        # sawmill: stonecutter under a small roof, plank stacks
        sx, sz = x0 + 1, z1 - 3
        b.put(sx, 0, sz, "stonecutter", facing="east")
        for x in (sx - 1, sx + 2):
            for y in range(0, 3):
                b.put(x, y, sz - 1, log, axis="y")
                b.put(x, y, sz + 1, log, axis="y")
        for x in range(sx - 1, sx + 3):
            for z in range(sz - 1, sz + 2):
                b.put(x, 3, z, w + "_slab", type="bottom", waterlogged="false")
        b.put(sx + 1, 0, sz, w + "_planks")
        b.put(sx + 1, 1, sz, w + "_slab", type="bottom", waterlogged="false")
    return b.b


WALL = (-56, 76, -46, 50)  # x0, x1, z0, z1 of the palisade ring (village plot); room for the lair in the north


def inward(cx, cz):
    """Direction from a wall tower at (cx, cz) on the ring into the settlement: (dx, dz), both set for a corner."""
    x0, x1, z0, z1 = WALL
    return (1 if cx == x0 else -1 if cx == x1 else 0, 1 if cz == z0 else -1 if cz == z1 else 0)


GATE_TOWER = 6  # the towers flanking the gate stand this far from its middle, clear of the gatehouse


def tower_spots(level):
    """Where the wall towers stand (ring coordinates): corners and gate from the first towers on, the stone wall's
    last level adds towers along the long sides."""
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    spots = [(x0, z0), (x1, z0), (x0, z1), (x1, z1), (gx - GATE_TOWER, z1), (gx + GATE_TOWER, z1)]
    if level >= 4:
        spots += [(x0, (z0 + z1) // 2), (x1, (z0 + z1) // 2), ((x0 + x1) // 2, z0), (x0 + 33, z0), (x1 - 33, z0), (x0 + 33, z1), (x1 - 33, z1)]
    return spots


def walls(style, level):
    """Walls track. 1: log palisade with sharpened tops and a gate on the main road.
    2: inner walkway, corner watchtowers and gate towers with lanterns."""
    if level >= 3:
        return stone_walls(style, level)
    s = STYLES[style]
    b = B()
    FOLLOW.add(id(b.b))
    ANCHORS_OF[id(b.b)] = b.anchors
    w = s["wood"]
    log = MAT[style]["log"]
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    ring = [(x, z0) for x in range(x0, x1 + 1)] + [(x, z1) for x in range(x0, x1 + 1)]
    ring += [(x0, z) for z in range(z0 + 1, z1)] + [(x1, z) for z in range(z0 + 1, z1)]
    ring_set = set(ring)
    for x, z in ring:
        if z == z1 and abs(x - gx) <= 2:
            continue  # the gate and its posts
        h = 3 + ((x * 7 + z * 13) % 3 == 0)
        for y in range(0, h):
            b.put(x, y, z, log, axis="y")
        b.put(x, h, z, w + "_fence")
        if not (z == z1 and abs(x - gx) <= 2):
            b.put(x, -1, z, "coarse_dirt")
    # the gate opening stays free: gates are their own track
    if level >= 2:
        # walkway on the inside at y=2, reached from the towers
        inner = {(x, z0 + 1) for x in range(x0 + 1, x1)} | {(x, z1 - 1) for x in range(x0 + 1, x1) if abs(x - gx) > 2}
        inner |= {(x0 + 1, z) for z in range(z0 + 1, z1)} | {(x1 - 1, z) for z in range(z0 + 1, z1)}
        for x, z in inner:
            b.put(x, 2, z, w + "_slab", type="top", waterlogged="false")
        for cx, cz in tower_spots(level):
            # 5x5 watchtower straddling the wall, which runs on through it: corner posts, a platform at 6 with
            # rails and a roof, and a ladder on the inside of the wall
            tx = max(x0 - 2, min(x1 - 2, cx - 2))
            tz = max(z0 - 2, min(z1 - 2, cz - 2))
            ccx, ccz = tx + 2, tz + 2
            b.anchor = (ccx, ccz)
            for x in range(tx, tx + 5):
                for z in range(tz, tz + 5):
                    corner = x in (tx, tx + 4) and z in (tz, tz + 4)
                    edge = x in (tx, tx + 4) or z in (tz, tz + 4)
                    for y in range(0, 10):
                        if corner or (x, z) in ring_set and y < 6:
                            b.put(x, y, z, log, axis="y")
                        elif y < 6:
                            b.put(x, y, z, "air")
                        else:
                            b.b.pop((x, y, z), None)
                    b.put(x, 6, z, w + "_planks")
                    if edge and not corner:
                        b.put(x, 7, z, w + "_fence")
                    b.put(x, 10, z, w + "_slab", type="bottom", waterlogged="false")
            dx, dz = inward(cx, cz)
            lx, lz = ccx + dx, ccz + dz
            fx, fz = (dx, 0) if dx else (0, dz)  # the ladder leans on the wall behind it
            for y in range(0, 7):
                b.put(lx, y, lz, "ladder", facing={(1, 0): "east", (-1, 0): "west", (0, 1): "south", (0, -1): "north"}[(fx, fz)], waterlogged="false")
            b.put(ccx, 9, ccz, "lantern", hanging="true", waterlogged="false")
            b.anchor = None
    return b.b


# ---------------- roads ----------------
# The streets are laid out by hand (ROAD_TRUNKS); the lane to every door, gate and work yard is then routed around
# everything that is ever built, so no road runs through a building and no door is left without one. A street or
# lane is built once something it leads to exists: each piece carries the (track, level) rules of what lies on it
# or beyond it. tools/check.py verifies the result at every tier.
#   name, (x from, to), (z from, to), centre line ('x' or 'z', value), the piece it branches off, its own rules
ROAD_TRUNKS = [
    ("gate", (6, 8), (8, 50), ("x", 7), None, [("main", 1)]),                 # the high street: heart to the gate
    ("plaza", (0, 14), (8, 16), ("x", 7), "gate", [("main", 7)]),
    ("cross_w1", (-8, 5), (18, 19), ("z", 18), "gate", [("main", 5)]),        # the street of houses
    ("cross_w2", (-21, -9), (18, 19), ("z", 18), "cross_w1", []),
    ("cross_w3", (-44, -22), (18, 19), ("z", 18), "cross_w2", []),
    ("cross_e1", (9, 22), (18, 19), ("z", 18), "gate", [("main", 6)]),
    ("cross_e2", (23, 34), (18, 19), ("z", 18), "cross_e1", [("main", 7)]),
    ("cross_e3", (35, 45), (18, 19), ("z", 18), "cross_e2", []),
    ("farm_1", (9, 27), (11, 12), ("z", 11), "gate", []),                     # the farm road, east of the square
    ("farm_2", (28, 40), (11, 12), ("z", 11), "farm_1", []),
    ("farm_3", (41, 53), (11, 12), ("z", 11), "farm_2", []),
    ("farm_4", (54, 66), (11, 12), ("z", 11), "farm_3", []),
    ("castle_w", (-6, -5), (-8, 17), ("x", -5), "cross_w1", []),              # lanes past the castle to the north side
    ("north_w", (-37, -5), (-10, -9), ("z", -9), "castle_w", []),
    ("castle_e", (19, 19), (-8, 10), ("x", 19), "farm_1", []),
    ("north_e1", (19, 43), (-10, -9), ("z", -9), "castle_e", []),
    ("north_link", (44, 45), (-10, 10), ("x", 44), "farm_3", []),
    ("north_e2", (46, 63), (-10, -9), ("z", -9), "north_link", []),
]
# places without a door that still need a way to them: track -> [(level, (x, z))], after the spacing moves
ROAD_GOALS = {
    "smith": [(1, (-12, 10))], "wood": [(1, (-31, 20))], "farm": [(1, (26, 11)), (1, (20, 5)), (2, (39, 10))],
    "stables": [(1, (47, 25))], "apiary": [(1, (52, 11))], "kennel": [(1, (52, 22))], "lair": [(1, (9, -23))],
    "cellar": [(1, (3, 11))],
}
ROAD_PLAN = {}   # style -> the plan of that style's roads
SEGS_OF = {}     # blocks dict id -> {pos: [segment index, ...]}
SEGDEF_OF = {}   # blocks dict id -> [(segment name, rules)]
DOCK_JOBS = []
FOLLOW = set()  # ids of block dicts whose blocks follow the terrain (roads, lamps, walls)
ANCHORS_OF = {}  # blocks dict id -> {pos: (x, z) anchor column}


def track_of(name):
    t = name.rsplit("_", 1)[0]
    return {"tent": "main", "hamlet": "main", "village": "main", "town": "main", "castle": "main", "citadel": "main",
            "wall": "walls", "road": "roads", "lamp": "lamps"}.get(t, t)


def plan_roads(style, jobs):
    """Lays out the style's road network around its buildings. jobs: the finished (moved) templates of the style.
    Returns dict(cells={(x, z): (kind, [segment names])}, segments=[(name, rules)], lamps=[(x, z, dx, dz, segment)])."""
    import heapq
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    hard = set()      # cells a road may never use
    eaves = set()     # cells under a roof's edge: fine for a road, not for a lamp post
    doors = []        # (track, level, door cell, (dx, dz))
    for j in jobs:
        name, blocks = j[1], j[2]
        track, level = track_of(name), int(name.rsplit("_", 1)[1])
        if name.startswith(("tent_", "road_", "lamp_", "dock_")):
            continue
        if track == "main":
            level += {"hamlet": 3, "village": 6, "town": 7, "castle": 8, "citadel": 10}[name.rsplit("_", 1)[0]]
        for (x, y, z), (bn, props) in blocks.items():
            if bn == "minecraft:snow" or bn == "minecraft:air" and y != -1:
                continue  # (air at ground level is a stairwell or a moat: no road there either)
            if -1 <= y <= 2:
                hard.add((x, z))
            elif 3 <= y <= 5:
                eaves.add((x, z))
            if bn.endswith("_door") and props.get("half") == "lower" and y <= 3:
                d = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}[props["facing"]]
                doors.append((track, level, (x, z), d))
    for x in range(gx - 1, gx + 2):
        for z in range(z1 - 3, z1 + 1):
            hard.discard((x, z))  # the gate's passage
    hard.discard((HEART[0], HEART[2]))
    hard.add((HEART[0], HEART[2]))
    lo_x, hi_x, lo_z, hi_z = x0 + 3, x1 - 3, z0 + 3, z1 - 3
    near = {(x + dx, z + dz) for (x, z) in hard for dx in (-1, 0, 1) for dz in (-1, 0, 1)} - hard

    cells = {}   # (x, z) -> [kind, [segments]]
    rules = {}
    parent = {}
    for name, (xa, xb), (za, zb), (axis, line), par, own in ROAD_TRUNKS:
        rules[name] = list(own)
        parent[name] = par
        for x in range(xa, xb + 1):
            for z in range(za, zb + 1):
                if (x, z) in hard:
                    continue
                core = (x if axis == "x" else z) == line
                if (x, z) in cells:
                    if core:
                        cells[(x, z)][0] = "core"
                    cells[(x, z)][1].append(name)  # shared by two streets: there as soon as either is
                    continue
                cells[(x, z)] = ["core" if core else "side", [name]]
    # a lane ends at a street; the square is only paved at the village tier, so lanes cross it to the street beyond
    trunk = {c for c, v in cells.items() if v[1] != ["plaza"]}

    goals = []
    seen = set()
    for track, level, (x, z), (dx, dz) in sorted(doors):
        if track == "main" and (x, z) == (HEART[0], z) and abs(z - HEART[2]) < 12 and abs(x - HEART[0]) <= 1:
            continue  # the main building's own door: its forecourt reaches the high street
        for i in range(1, 7):
            c = (x + dx * i, z + dz * i)
            if c not in hard:
                if (track, c) not in seen:
                    seen.add((track, c))
                    goals.append((track, level, c))
                break
    for track, extra in ROAD_GOALS.items():
        for level, c in extra:
            goals.append((track, level, c))

    def add_rule(piece, rule):
        while piece is not None:
            if rule not in rules[piece]:
                rules[piece].append(rule)
            piece = parent.get(piece)

    lanes = set()
    for track, level, goal in goals:
        seg = f"{track}_{level}_{goal[0]}_{goal[1]}".replace("-", "m")
        if goal in hard:
            raise SystemExit(f"{style}: the road goal of {track} at {goal} is inside a building")
        # Dijkstra from the goal to the nearest street, straight lines preferred, away from walls where possible
        start = (goal[0], goal[1], 0, 0)
        dist = {start: 0.0}
        prev = {}
        heap = [(0.0, start)]
        end = None
        while heap:
            d, cur = heapq.heappop(heap)
            if d > dist.get(cur, 1e9):
                continue
            x, z, px, pz = cur
            if (x, z) in trunk:
                end = cur
                break
            for ddx, ddz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                n = (x + ddx, z + ddz)
                if n in hard or not (lo_x <= n[0] <= hi_x and lo_z <= n[1] <= hi_z):
                    continue
                step = 0.55 if n in lanes or n in trunk else 1.0
                step += 1.5 if n in near else 0.0
                step += 1.2 if (px, pz) != (0, 0) and (px, pz) != (ddx, ddz) else 0.0
                nxt = (n[0], n[1], ddx, ddz)
                if d + step < dist.get(nxt, 1e9):
                    dist[nxt] = d + step
                    prev[nxt] = cur
                    heapq.heappush(heap, (d + step, nxt))
        if end is None:
            raise SystemExit(f"{style}: no way for a road from {track} at {goal} to the streets")
        rule = (track, level)
        add_rule([n for n in cells[(end[0], end[1])][1] if n != "plaza"][0], rule)
        if goal in cells:
            cells[goal][0] = "core"  # the first road level only treads centre lines: a doorstep is always one
        cur = end
        while cur in prev:
            cur = prev[cur]
            c = (cur[0], cur[1])
            if c in cells:
                if seg not in cells[c][1] and c not in trunk:
                    cells[c][1].append(seg)
                    if cells[c][1][0] != "plaza":
                        cells[c][0] = "core"
            else:
                cells[c] = ["core", [seg]]
            lanes.add(c)
        rules[seg] = [rule]
        parent[seg] = None

    # the centre lines must hang together (the first road level only treads those): link them through the streets
    core = {c for c, v in cells.items() if v[0] == "core"}
    start = (gx, HEART[2] + 2)
    comp = {start}
    queue = [start]
    while queue:
        x, z = queue.pop()
        for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if n in core and n not in comp:
                comp.add(n)
                queue.append(n)
    changed = True
    while changed:
        changed = False
        rest = core - comp
        if not rest:
            break
        # breadth-first from the connected part through any road cell to the nearest loose centre cell
        back = {c: None for c in comp}
        queue = sorted(comp)
        found = None
        i = 0
        while i < len(queue) and found is None:
            x, z = queue[i]
            i += 1
            for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if n in cells and n not in back and cells[n][1] != ["plaza"]:
                    back[n] = (x, z)
                    if n in rest:
                        found = n
                        break
                    queue.append(n)
        if found is None:
            break
        c = found
        while c is not None and c not in comp:
            cells[c][0] = "core"
            core.add(c)
            c = back[c]
        queue = [found]
        comp.add(found)
        while queue:
            x, z = queue.pop()
            for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
                if n in core and n not in comp:
                    comp.add(n)
                    queue.append(n)
        changed = True

    # lamp posts along the streets: every 13th block of a centre line, on alternating sides, never on a road,
    # in a doorway or against a wall
    lamps = []
    goal_cells = {g for _, _, g in goals}
    taken = set()
    for name, (xa, xb), (za, zb), (axis, line), par, own in ROAD_TRUNKS:
        if name == "plaza" or (xb - xa + 1) * (zb - za + 1) < 16:
            continue
        along = range(za, zb + 1) if axis == "x" else range(xa, xb + 1)
        side = 1
        for n, v in enumerate(along):
            # one post every 13 blocks, sides alternating: a lantern's light reaches ~14 blocks, and since 1.18
            # hostile mobs only spawn in complete darkness, so the streets stay safe with half the clutter
            if n % 13 != 6:
                continue
            for sgn in (side, -side):
                for off in (1, 2, 3):
                    c = (line + sgn * off, v) if axis == "x" else (v, line + sgn * off)
                    if c not in cells:
                        break
                else:
                    continue
                d = (-sgn, 0) if axis == "x" else (0, -sgn)
                ok = c not in hard and c not in eaves and c not in goal_cells and (c[0] + d[0], c[1] + d[1]) in cells and \
                    all((c[0] + ex, c[1] + ez) not in taken and (c[0] + ex, c[1] + ez) not in goal_cells for ex in (-1, 0, 1) for ez in (-1, 0, 1)) and \
                    lo_x <= c[0] <= hi_x and lo_z <= c[1] <= hi_z and \
                    not any((c[0] + ex, c[1] + ez) in hard for ex, ez in ((1, 0), (-1, 0), (0, 1), (0, -1)))
                if ok:
                    lamps.append((c[0], c[1], d[0], d[1], name))
                    taken.add(c)
                    side = -sgn
                    break
    for c, d in (((-1, 8), (1, 0)), ((15, 8), (-1, 0)), ((-1, 16), (1, 0)), ((15, 16), (-1, 0))):
        if c not in hard and c not in cells and not any((c[0] + ex, c[1] + ez) in hard for ex, ez in ((1, 0), (-1, 0), (0, 1), (0, -1))):
            lamps.append((c[0], c[1], d[0], d[1], "plaza"))
    names = [t[0] for t in ROAD_TRUNKS] + sorted(k for k in rules if k not in {t[0] for t in ROAD_TRUNKS})
    segments = [(n, sorted(rules[n])) for n in names]
    plan = dict(cells={c: (v[0], v[1]) for c, v in cells.items()}, segments=segments, lamps=lamps, goals=goals, hard=hard)
    ROAD_PLAN[style] = plan
    return plan


ROAD_MATS = {
    1: ("dirt_path", "coarse_dirt"),
    2: ("dirt_path", "coarse_dirt"),
    3: ("cobblestone", "gravel", "mossy_cobblestone"),
    4: ("stone_bricks", "polished_andesite", "cracked_stone_bricks"),
}


def roads(style, level):
    """Roads track: 1 trodden path (centre line only), 2 full dirt roads, 3 cobblestone and gravel,
    4 stone brick with polished andesite borders."""
    b = B()
    plan = ROAD_PLAN[style]
    cells = plan["cells"]
    seg = {name: i for i, (name, _) in enumerate(plan["segments"])}
    m = ROAD_MATS[level]
    segs = {}
    for (x, z), (kind, names) in cells.items():
        h = (x * 73856093 ^ z * 19349663) % 20
        if level == 1:
            if kind != "core":
                continue
            block = m[0]
        elif level == 2:
            block = m[0] if h >= 2 else m[1]
        elif level == 3:
            block = m[1] if h < 5 else m[2] if h < 8 else m[0]
        else:
            border = any((x + dx, z + dz) not in cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            block = m[1] if border and kind != "core" else m[2] if h < 3 else m[0]
        b.put(x, -1, z, block)
        segs[(x, -1, z)] = [seg[n] for n in names]
    SEGS_OF[id(b.b)] = segs
    SEGDEF_OF[id(b.b)] = plan["segments"]
    FOLLOW.add(id(b.b))
    return b.b


def seg_trunks():
    return {t[0] for t in ROAD_TRUNKS}


def lamps(style, level):
    """Lamps track: 1 torches on posts, 2 lanterns on posts, 3 stone-footed posts with a hanging lantern on an arm."""
    s = STYLES[style]
    b = B()
    fence = s["wood"] + "_fence"
    plan = ROAD_PLAN[style]
    seg = {name: i for i, (name, _) in enumerate(plan["segments"])}
    segs = {}
    for x, z, dx, dz, name in plan["lamps"]:
        before = set(b.b)
        if level == 1:
            b.put(x, 0, z, fence)
            b.put(x, 1, z, "torch")
        elif level == 2:
            b.put(x, 0, z, fence)
            b.put(x, 1, z, fence)
            b.put(x, 2, z, "lantern", hanging="false", waterlogged="false")
        else:
            b.put(x, 0, z, "stone_brick_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
            for y in (1, 2, 3):
                b.put(x, y, z, fence)
            b.put(x + dx, 3, z + dz, fence)
            b.put(x + dx, 2, z + dz, "lantern", hanging="true", waterlogged="false")  # one lantern per post, on the arm
        for p in set(b.b) - before:
            segs[p] = [seg[name]]
    SEGS_OF[id(b.b)] = segs
    SEGDEF_OF[id(b.b)] = plan["segments"]
    FOLLOW.add(id(b.b))
    return b.b


def stairs_of(block):
    return {"stone_bricks": "stone_brick_stairs", "mossy_stone_bricks": "mossy_stone_brick_stairs", "nether_bricks": "nether_brick_stairs",
            "bricks": "brick_stairs", "mud_bricks": "mud_brick_stairs"}.get(block, block + "_stairs")


def stone_walls(style, level):
    """Walls 3: stone curtain wall with a parapet and battlements, a walkway behind them and stairs up to it beside
    the gate. Walls 4: square towers at the corners, beside the gate and along the long sides. Each tower has a door
    at the ground, doors onto the walkway, a ladder inside up to the roof, arrow slits and the kingdom's banner."""
    b = B()
    # follows the land in 4-block sections: each section stands on the ground at its middle, so on a mountain
    # the wall steps up and down with the hills instead of floating over valleys
    FOLLOW.add(id(b.b))
    ANCHORS_OF[id(b.b)] = b.anchors
    frozen = style == "frozen"
    main, alt, crack = ("cobblestone", "mossy_cobblestone", "stone_bricks") if frozen else ("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks")
    wall_top = "cobblestone_wall" if frozen else "stone_brick_wall"
    slab = "cobblestone_slab" if frozen else "stone_brick_slab"
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    H = 5
    POST = dict(up="true", north="none", south="none", east="none", west="none", waterlogged="false")

    def mat(x, y, z):
        h = (x * 73856093 ^ y * 19349663 ^ z * 83492791) % 20
        return alt if h < 3 else crack if h < 5 else main

    def section(x, z):
        """Anchor column of the wall section that the ring cell (x, z) belongs to."""
        if z in (z0, z1):
            return (min(x1, x0 + (x - x0) // 4 * 4 + 2), z)
        return (x, min(z1, z0 + (z - z0) // 4 * 4 + 2))

    ring = [(x, z0) for x in range(x0, x1 + 1)] + [(x, z1) for x in range(x0, x1 + 1)]
    ring += [(x0, z) for z in range(z0 + 1, z1)] + [(x1, z) for z in range(z0 + 1, z1)]
    for x, z in ring:
        passage = z == z1 and abs(x - gx) <= 1
        house = z == z1 and abs(x - gx) <= 3  # where the gatehouse stands: plain stone, so both fit together
        b.anchor = section(x, z)
        for y in range(-6, -1):
            b.put(x, y, z, main)  # foundation: fills under a section where the ground dips
        for y in range(0, H + 1):
            if passage and y < 4:
                continue
            b.put(x, y, z, main if house else mat(x, y, z))
        if (x + z) % 2 == 0 and not house:
            b.put(x, H + 1, z, wall_top, **POST)
        if not passage:
            b.put(x, -1, z, "cobblestone")
    # walkway behind the battlements
    inner = {(x, z0 + 1) for x in range(x0 + 1, x1)} | {(x, z1 - 1) for x in range(x0 + 1, x1) if abs(x - gx) > 3}
    inner |= {(x0 + 1, z) for z in range(z0 + 1, z1)} | {(x1 - 1, z) for z in range(z0 + 1, z1)}
    for x, z in inner:
        ox = x0 if x == x0 + 1 else x1 if x == x1 - 1 else x
        oz = z0 if z == z0 + 1 else z1 if z == z1 - 1 else z
        b.anchor = section(ox, oz)
        b.put(x, H - 1, z, slab, type="top", waterlogged="false")
    if level == 3:
        # stairs up to the walkway on both sides of the gate (the towers of the next level take their place)
        for i in range(H):
            for x, f in ((gx - 5 - i, "west"), (gx + 5 + i, "east")):
                b.anchor = section(x, z1)
                b.put(x, i, z1 - 2, stairs_of(main), facing=f, **ST)
                for y in range(0, i):
                    b.put(x, y, z1 - 2, main)
    else:
        for cx, cz in tower_spots(level):
            tx0 = max(x0 - 1, min(x1 - 3, cx - 2))
            tz0 = max(z0 - 1, min(z1 - 3, cz - 2))
            if cz == z1:
                tz0 = z1 - 2
            b.anchor = (tx0 + 2, tz0 + 2)
            dx, dz = inward(cx, cz)
            mx, mz = tx0 + 2, tz0 + 2
            # door at the ground, on the side that looks into the settlement
            face = (mx, tz0 if dz < 0 else tz0 + 4) if dz else (tx0 if dx < 0 else tx0 + 4, mz)
            # doors onto the walkway wherever it meets the tower
            doors = set()
            for x in range(tx0, tx0 + 5):
                for z in range(tz0, tz0 + 5):
                    corner = x in (tx0, tx0 + 4) and z in (tz0, tz0 + 4)
                    edge = x in (tx0, tx0 + 4) or z in (tz0, tz0 + 4)
                    if edge and not corner:
                        ox, oz = (x - 1 if x == tx0 else x + 1 if x == tx0 + 4 else x), (z - 1 if z == tz0 else z + 1 if z == tz0 + 4 else z)
                        if (ox, oz) in inner:
                            doors.add((x, z))
            # the ladder stands in a corner no door opens into, on the east or west wall
            lx = lz = side = None
            for cz_ in (tz0 + 1, tz0 + 3):
                for cx_, wx, f in ((tx0 + 1, tx0, "east"), (tx0 + 3, tx0 + 4, "west")):
                    if lx is None and (wx, cz_) not in doors and (cx_, tz0 if cz_ == tz0 + 1 else tz0 + 4) not in doors:
                        lx, lz, side = cx_, cz_, f
            for x in range(tx0, tx0 + 5):
                for z in range(tz0, tz0 + 5):
                    edge = x in (tx0, tx0 + 4) or z in (tz0, tz0 + 4)
                    for y in range(-6, 0):
                        b.put(x, y, z, main)  # tower foundation
                    for y in range(0, 9):
                        if edge:
                            b.put(x, y, z, mat(x, y, z))
                        elif y in (0, H - 1) and (x, z) != (lx, lz):
                            b.put(x, y, z, main)
                        else:
                            b.put(x, y, z, "air")
                    b.put(x, 9, z, main if not edge else mat(x, 9, z))
                    if edge:
                        b.put(x, 10, z, mat(x, 10, z))
                        if (x + z) % 2 == 0:
                            b.put(x, 11, z, wall_top, **POST)
            b.put(lx, 0, lz, main)
            for y in range(1, 10):
                b.put(lx, y, lz, "ladder", facing=side, waterlogged="false")
            for y in (1, 2):
                b.put(face[0], y, face[1], "air")
            for x, z in doors:
                for y in (H, H + 1):
                    b.put(x, y, z, "air")
            # arrow slits on the other faces, the banner on the face that looks out
            out_face = (mx, tz0 + 4 if dz < 0 else tz0) if dz else (tx0 + 4 if dx < 0 else tx0, mz)
            for fx, fz in ((mx, tz0), (mx, tz0 + 4), (tx0, mz), (tx0 + 4, mz)):
                if (fx, fz) in doors or (fx, fz) == face:
                    continue
                if (fx, fz) == out_face:
                    ox, oz = fx + (fx == tx0 + 4) - (fx == tx0), fz + (fz == tz0 + 4) - (fz == tz0)
                    b.put(ox, 7, oz, "{color}_wall_banner", facing="east" if ox > fx else "west" if ox < fx else "south" if oz > fz else "north")
                else:
                    b.put(fx, 6, fz, "iron_bars", **(PANE_X if fz in (tz0, tz0 + 4) else PANE_Z))
            b.put(mx, 8, mz, "lantern", hanging="true", waterlogged="false")
    b.anchor = None
    return b.b


def town_hall(b, style, x0=1, z0=-1):
    """Town level: the first house becomes a town hall. Stone ground floor, timber-framed (temperate) or
    log (frozen) upper floor, steep roof and a clock tower with a bell over the entrance."""
    frozen = style == "frozen"
    W, D = 13, 10
    x1, z1 = x0 + W - 1, z0 + D - 1
    cx = x0 + W // 2
    m = MAT[style]
    frame, infill, roof, ridge, stone = m["frame"], m["wall"], m["roof"], m["roof_slab"], m["stone"]
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, stone if edge else "spruce_planks")
            if not edge:
                b.put(x, 4, z, "spruce_planks")
                continue
            for y in (1, 2, 3):
                b.put(x, y, z, stone if not (y == 2 and (x - x0) % 3 == 1 and z in (z0, z1) or y == 2 and (z - z0) % 3 == 1 and x in (x0, x1))
                      else "glass_pane", **({} if y != 2 or not ((x - x0) % 3 == 1 and z in (z0, z1) or (z - z0) % 3 == 1 and x in (x0, x1))
                                            else dict(north="false", south="false", east="true", west="true", waterlogged="false")
                                            if z in (z0, z1) else dict(north="true", south="true", east="false", west="false", waterlogged="false")))
            b.put(x, 4, z, frame, axis="x" if z in (z0, z1) else "z")
            for y in (5, 6, 7):
                post = (x - x0) % 3 == 0 and z in (z0, z1) or (z - z0) % 3 == 0 and x in (x0, x1)
                if post:
                    b.put(x, y, z, frame, axis="y")
                elif y == 6 and not frozen:
                    b.put(x, y, z, "glass_pane", **(dict(north="false", south="false", east="true", west="true", waterlogged="false")
                                                     if z in (z0, z1) else dict(north="true", south="true", east="false", west="false", waterlogged="false")))
                else:
                    b.put(x, y, z, infill)
            b.put(x, 8, z, frame, axis="x" if z in (z0, z1) else "z")
    # roof, ridge along x, gables east and west
    half = D // 2
    for x in range(x0 - 1, x1 + 2):
        for i in range(half + 1):
            b.put(x, 8 + i, z0 - 1 + i, roof, facing="south", **ST)
            b.put(x, 8 + i, z1 + 1 - i, roof, facing="north", **ST)
    for xx in (x0, x1):
        for i in range(1, half + 1):
            for z in range(z0 + i, z1 - i + 1):
                b.put(xx, 8 + i, z, infill)
    for x in range(x0 - 1, x1 + 2):
        b.put(x, 8 + half + 1, z0 + half - 1, ridge, type="bottom", waterlogged="false")
        b.put(x, 8 + half + 1, z0 + half, ridge, type="bottom", waterlogged="false")
    # clock tower over the entrance (south side)
    tx0, tz0 = cx - 1, z1 - 2
    for y in range(9, 18):
        for x in range(tx0, tx0 + 3):
            for z in range(tz0, tz0 + 3):
                edge = x in (tx0, tx0 + 2) or z in (tz0, tz0 + 2)
                if edge and not (y in (14, 15) and (x == cx or z == tz0 + 1)):
                    b.put(x, y, z, stone if y < 14 else frame, **({} if y < 14 else {"axis": "y"}))
    b.put(cx, 13, tz0 + 1, "spruce_planks")
    b.put(cx, 16, tz0 + 1, frame, **({"axis": "x"} if "log" in frame else {}))
    b.put(cx, 15, tz0 + 1, "bell", attachment="ceiling", facing="south", powered="false")
    for x in range(tx0 - 1, tx0 + 4):
        for z in range(tz0 - 1, tz0 + 4):
            b.put(x, 18, z, "dark_oak_slab" if frozen else "deepslate_tile_slab", type="bottom", waterlogged="false")
    b.put(cx, 19, tz0 + 1, "lightning_rod", facing="up", powered="false", waterlogged="false")
    b.put(cx, 12, z1 + 1, "{color}_wall_banner", facing="south")
    # entrance and inside
    porch(b, cx, z1 + 1, "spruce", width=5)
    for x in (cx - 1, cx, cx + 1):
        if x != cx:
            b.put(x, 1, z1, "spruce_door", facing="south", half="lower", hinge="left" if x < cx else "right", open="false", powered="false")
            b.put(x, 2, z1, "spruce_door", facing="south", half="upper", hinge="left" if x < cx else "right", open="false", powered="false")
        else:
            b.put(x, 1, z1, stone)
            b.put(x, 2, z1, stone)
    for x in range(x0 + 2, x1 - 1):
        b.put(x, 1, z0 + 2, "spruce_stairs", facing="south", **ST)
    b.put(cx, 1, z0 + 4, "lectern", facing="south", has_book="false", powered="false")
    for x in (x0 + 2, x1 - 2):
        b.put(x, 3, z0 + 5, "lantern", hanging="true", waterlogged="false")
    b.put(x0 + 1, 1, z0 + 1, "chest", facing="east", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 1, "cartography_table")
    b.put(x0 + 1, 1, z1 - 1, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x0 + 1, 1, z1 - 2, "{color}_bed", facing="north", part="head", occupied="false")
    for y in range(1, 5):
        b.put(x1 - 1, y, z1 - 1, "ladder", facing="west", waterlogged="false")
    for x in range(cx - 2, cx + 3):  # the council table upstairs
        b.put(x, 5, z0 + 3, "spruce_slab", type="top", waterlogged="false")
        b.put(x, 5, z0 + 2, "spruce_stairs", facing="south", **ST)
    b.put(cx, 7, z0 + 4, "lantern", hanging="true", waterlogged="false")
    b.put(x0 + 1, 5, z0 + 1, "bookshelf")
    b.put(x0 + 1, 6, z0 + 1, "bookshelf")
    b.put(x0 + 1, 5, z0 + 2, "chest", facing="east", type="single", waterlogged="false")


def hsh(x, y, z):
    return (x * 73856093 ^ y * 19349663 ^ z * 83492791) & 0x7FFFFFFF


def pick(seq, x, y, z):
    return seq[hsh(x, y, z) % len(seq)]


# What the bigger buildings of each style are made of. "base" is the foundation course, "stone" the masonry of
# stone ground floors, "plaster" the fill between the timbers; lists are mixed block by block so no wall is one flat texture.
KIT = {
    "temperate": dict(base=("cobblestone", "cobblestone", "mossy_cobblestone", "stone_bricks"),
                      stone=("stone_bricks", "stone_bricks", "stone_bricks", "cobblestone", "mossy_stone_bricks", "andesite"),
                      plaster=("calcite",), plank="spruce_planks", door="spruce_door", shutter="spruce_trapdoor",
                      bush="flowering_azalea_leaves", flat=False, chimney="bricks"),
    "frozen": dict(base=("cobblestone", "cobblestone", "mossy_cobblestone"),
                   stone=("cobblestone", "cobblestone", "stone", "mossy_cobblestone", "andesite"),
                   plaster=("spruce_planks",), plank="spruce_planks", door="spruce_door", shutter="spruce_trapdoor",
                   bush=None, flat=False, chimney="cobblestone", logwall="spruce_log", ridge="dark_oak_slab"),
    "arid": dict(base=("cut_sandstone", "cut_sandstone", "sandstone"),
                 stone=("sandstone", "sandstone", "cut_sandstone", "smooth_sandstone"),
                 plaster=("smooth_sandstone",), plank="acacia_planks", door="acacia_door", shutter="acacia_trapdoor",
                 bush=None, flat=True, chimney="sandstone", post="cut_sandstone"),  # stone piers, timber only for the roof beams
    "highland": dict(base=("cobblestone", "mossy_cobblestone", "andesite"),
                     stone=("stone_bricks", "stone_bricks", "cobblestone", "andesite", "cracked_stone_bricks"),
                     plaster=("cobblestone", "cobblestone", "andesite", "stone"), plank="spruce_planks", door="spruce_door",
                     shutter="spruce_trapdoor", bush=None, flat=False, chimney="cobblestone"),
    "wild": dict(base=("mossy_cobblestone", "mossy_cobblestone", "cobblestone"),
                 stone=("mossy_cobblestone", "cobblestone", "mossy_stone_bricks", "cobblestone"),
                 plaster=("bamboo_planks",), plank="jungle_planks", door="jungle_door", shutter="jungle_trapdoor",
                 bush="azalea_leaves", flat=False, chimney="mud_bricks"),
}
NETHER_KIT = dict(base=("blackstone", "blackstone", "polished_blackstone_bricks"),
                  stone=("nether_bricks", "nether_bricks", "cracked_nether_bricks"), plaster=("nether_bricks",),
                  plank="blackstone", door="crimson_door", shutter="crimson_trapdoor", bush=None, flat=False, chimney="nether_bricks")
PANE_X = dict(north="false", south="false", east="true", west="true", waterlogged="false")
PANE_Z = dict(north="true", south="true", east="false", west="false", waterlogged="false")


def posts_along(lo, hi, door=None):
    """Timber posts of a wall from lo to hi: the corners, a pair framing the door, and one in every span of five or more."""
    posts = [lo, hi]
    if door is not None and door - 1 > lo and door + 1 < hi:
        posts += [door - 1, door + 1]
    elif (hi - lo) % 2 == 0:
        posts.append((lo + hi) // 2)
    posts = sorted(set(posts))
    out = set(posts)
    for a, c in zip(posts, posts[1:]):
        if c - a - 1 >= 5:
            out.add((a + c) // 2)
    return sorted(out)


def window_cells(posts, skip=None):
    """The window cells of each bay between posts: one in a narrow bay, the middle two of a wide one."""
    cells = []
    for a, c in zip(posts, posts[1:]):
        bay = [v for v in range(a + 1, c)]
        if not bay or skip is not None and skip in bay:
            continue
        n = len(bay)
        cells.append(bay if n <= 2 else bay[n // 2 - (0 if n % 2 else 1): n // 2 + 1])
    return cells


def hall(b, style, x0, z0, x1, z1, h, door, kind="timber", floor=None, base_h=None, chimney=None, windows_back=True):
    """A building of the village in its style, used for every workshop, shop and hall: a stone foundation, timber
    posts with fill between them (kind 'timber') or a masonry ground floor (kind 'stone'), windows in every bay with
    shutters on the door side, a door with steps, and a roof with eaves on all sides (flat with a parapet in the
    desert). With base_h the building has two storeys: the upper floor's beams lie at base_h + 1.
    The roof reaches one block past the walls on every side, nothing else does."""
    nether = kind == "nether"
    k = NETHER_KIT if nether else KIT[style]
    m = MAT[style]
    frame = "red_nether_bricks" if nether else k.get("post", m["frame"])
    roof, roof_slab = ("nether_brick_stairs", "nether_brick_slab") if nether else (m["roof"], k.get("ridge", m["roof_slab"]))
    log = "log" in frame
    cx = (x0 + x1) // 2
    dz = z0 if door == "n" else z1
    out = -1 if door == "n" else 1
    facing = "north" if door == "n" else "south"
    stone_kind = kind in ("stone", "nether")

    def beam(x, y, z, axis):
        b.put(x, y, z, frame, **({"axis": axis} if log else {}))

    def fill(x, y, z, along):
        upper = base_h is not None and y > base_h
        if stone_kind and not upper:
            b.put(x, y, z, pick(k["stone"], x, y, z))
        elif k.get("logwall") and not upper and not stone_kind:
            b.put(x, y, z, k["logwall"], axis=along)
        else:
            b.put(x, y, z, pick(k["plaster"], x, y, z))

    px = posts_along(x0, x1, cx)
    pz = posts_along(z0, z1)
    rows = [2, 3] if (base_h or h) >= 5 else [2]
    if base_h is not None:
        rows += [base_h + 3]
    plates = {h} | ({base_h + 1} if base_h is not None else set())
    if k["flat"]:
        plates = {base_h + 1} if base_h is not None else set()
    # foundation and floor
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, pick(k["base"], x, 0, z) if edge else (floor or k["plank"]))
    # walls
    for y in range(1, h + 1):
        for x in range(x0, x1 + 1):
            for z in (z0, z1):
                if x in px:
                    beam(x, y, z, "y")
                elif y in plates:
                    beam(x, y, z, "x")
                else:
                    fill(x, y, z, "x")
        for z in range(z0 + 1, z1):
            for x in (x0, x1):
                if z in pz:
                    beam(x, y, z, "y")
                elif y in plates:
                    beam(x, y, z, "z")
                else:
                    fill(x, y, z, "z")
    # windows
    front = window_cells(px, cx)
    for z in (z0, z1):
        if z != dz and not windows_back:
            continue
        for group in front:
            for x in group:
                for y in rows:
                    b.put(x, y, z, "glass_pane", **PANE_X)
            if z == dz:
                for y in [2] + ([base_h + 3] if base_h is not None else []):
                    for x in (group[0] - 1, group[-1] + 1):
                        if x != cx and x0 <= x <= x1:
                            b.put(x, y, z + out, k["shutter"], facing=facing, half="bottom", open="true", powered="false", waterlogged="false")
                if k["bush"]:
                    for x in group:
                        b.put(x, 0, z + out, k["bush"], distance="7", persistent="true", waterlogged="false")
    for x in (x0, x1):
        for group in window_cells(pz):
            for z in group:
                for y in rows:
                    b.put(x, y, z, "glass_pane", **PANE_Z)
    # door, steps and a lantern beside it
    porch(b, cx, dz + out, k["plank"].replace("_planks", "") if not nether else "blackstone", facing="south" if door == "n" else "north")
    for half, y in (("lower", 1), ("upper", 2)):
        b.put(cx, y, dz, k["door"], facing=facing, half=half, hinge="left", open="false", powered="false")
    if h >= 4 and 3 not in plates:
        beam(cx, 3, dz, "x")
    # roof
    depth = z1 - z0 + 1
    if k["flat"]:
        top = h + 1
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                edge = x in (x0, x1) or z in (z0, z1)
                if not edge:
                    b.put(x, top, z, "smooth_sandstone")
                elif (x + z) % 2 == 0 or (x in (x0, x1) and z in (z0, z1)):
                    b.put(x, top, z, "cut_sandstone")
                    b.put(x, top + 1, z, "sandstone_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
                else:
                    b.put(x, top, z, "cut_sandstone")
        for x in px[1:-1]:  # roof beams poke out of the long walls
            for z, o in ((z0, -1), (z1, 1)):
                b.put(x, h, z + o, m["frame"], axis="z")
        for x in (cx - 1, cx, cx + 1):  # awning over the door
            b.put(x, 3, dz + out, m["frame"].replace("stripped_", "").replace("_log", "_slab"), type="top", waterlogged="false")
        peak = top
    else:
        half = depth // 2 + 1
        for x in range(x0 - 1, x1 + 2):
            for i in range(half):
                b.put(x, h + i, z0 - 1 + i, roof, facing="south", **ST)
                b.put(x, h + i, z1 + 1 - i, roof, facing="north", **ST)
            if depth % 2 == 1:
                b.put(x, h + half - 1, z0 - 1 + half, roof_slab, type="bottom", waterlogged="false")
        mid = (z0 + z1) // 2
        for xx in (x0, x1):
            for i in range(1, half - (depth % 2)):
                for z in range(z0 + i, z1 - i + 1):
                    if depth % 2 == 1 and z == mid:
                        beam(xx, h + i, z, "y")
                    else:
                        b.put(xx, h + i, z, pick(k["plaster"], xx, h + i, z))
            if half >= 5 and depth % 2 == 1:
                b.put(xx, h + 2, mid, "glass_pane", **PANE_Z)
        # tie beams across the hall at every post: the lanterns hang on them
        for x in px[1:-1]:
            for z in range(z0 + 1, z1):
                beam(x, h, z, "z")
        peak = h + half - 1
    if chimney:
        chx, chz = chimney
        for y in range(1, peak + 2):
            b.put(chx, y, chz, k["chimney"])
        b.put(chx, peak + 2, chz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    return peak


def hang_lanterns(blocks):
    """A hanging lantern with nothing above it gets a chain up to the next beam or roof block."""
    for (x, y, z), (name, p) in list(blocks.items()):
        if name == "minecraft:lantern" and p.get("hanging") == "true":
            gap = []
            for yy in range(y + 1, y + 9):
                above = blocks.get((x, yy, z))
                if above is not None and above[0] != "minecraft:air":
                    for g in gap:
                        blocks[g] = ("minecraft:iron_chain", {"axis": "y", "waterlogged": "false"})
                    break
                gap.append((x, yy, z))
            else:
                blocks[(x, y, z)] = ("minecraft:lantern", {"hanging": "false", "waterlogged": "false"})
                # nothing to hang on: it stands on what is below (checked by tools/check.py)


def mats(style):
    m = MAT[style]
    return dict(wall=m["wall"], corner=m["frame"], stone=m["stone"], roof=m["roof"], slab=m["roof_slab"])


LIB = (-33, -6, -23, 4)      # x0, z0, x1, z1 (door south)
BREW = (-21, 25, -13, 31)    # door north
STABLE = (41, 26, 53, 34)    # door north
LIB_STORE = (-31, 0, 3)
BREW_STORE = (-19, 0, 30)
STABLE_STORE = (43, 0, 27)


def library(style, level):
    """Library add-on (enchanting): stone hall with bookshelves and an enchanting table; level 2 a reading room
    with more shelves (a full 15-shelf ring around the table), lecterns and lanterns."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = LIB
    hall(b, style, x0, z0, x1, z1, 4, "s", kind="stone", windows_back=False)
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2 - 1
    b.put(cx, 1, cz, "enchanting_table")
    ring = [(cx + dx, cz + dz) for dx in range(-2, 3) for dz in range(-2, 3) if max(abs(dx), abs(dz)) == 2 and (dx, dz) != (0, 2)]
    shelves = ring if level >= 2 else [(x, z) for x, z in ring if z == cz - 2]
    for x, z in shelves:
        b.put(x, 2, z, "bookshelf")
        b.put(x, 1, z, "bookshelf" if level >= 2 else "spruce_planks")
    for x in range(x0 + 1, x1):
        b.put(x, 1, z0 + 1, "bookshelf")
        b.put(x, 2, z0 + 1, "bookshelf" if level >= 2 else "air")
    b.put(*LIB_STORE, "chest", facing="east", type="single", waterlogged="false")
    b.put(x1 - 1, 1, (z0 + z1) // 2 + 1, "lectern", facing="west", has_book="false", powered="false")
    if level >= 2:
        b.put(x0 + 1, 1, (z0 + z1) // 2 + 1, "lectern", facing="east", has_book="false", powered="false")
        for x in (x0 + 2, x1 - 2):
            b.put(x, 4, cz, "lantern", hanging="true", waterlogged="false")
        b.put(cx, 1, z1 - 1, "{color}_carpet")
    b.put(cx, 4, cz, "lantern", hanging="true", waterlogged="false")
    return b.b


def brewery(style, level):
    """Brewery add-on: small stone house with brewing stands, cauldrons, a herb garden; level 2 adds a cellar room."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = BREW
    hall(b, style, x0, z0, x1, z1, 3, "n", kind="stone", floor="packed_mud", chimney=(x1 - 1, z1))
    cx = (x0 + x1) // 2
    b.put(cx - 2, 1, z1 - 1, "brewing_stand", has_bottle_0="false", has_bottle_1="false", has_bottle_2="false")
    b.put(cx - 1, 1, z1 - 1, "water_cauldron", level="3")
    b.put(cx + 1, 1, z1 - 1, "cauldron")
    b.put(*BREW_STORE, "barrel", facing="up", open="false")
    b.put(x0 + 1, 1, z0 + 1, "barrel", facing="up", open="false")
    for x in range(x0 + 1, x1):
        b.put(x, 3, z0 + 1, "spruce_trapdoor", facing="south", half="top", open="false", powered="false", waterlogged="false")
    # herb garden in front
    for x in range(x0, x0 + 3):
        b.put(x, -1, z0 - 2, "farmland", moisture="7")
        b.put(x, 0, z0 - 2, "sweet_berry_bush" if style == "frozen" else "beetroots", age="3")
    if level >= 2:
        b.put(cx + 2, 1, z1 - 1, "brewing_stand", has_bottle_0="false", has_bottle_1="false", has_bottle_2="false")
        for x in range(x0 + 1, x1):
            b.put(x, 0, z1 - 2, "barrel", facing="up", open="false") if x % 2 else None
        for x in (x0 + 2, x1 - 2):
            b.put(x, 3, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    return b.b


def stables(style, level):
    """Stables add-on: long timber barn with stalls, hay and a paddock; level 2 a second row of stalls and a tack room."""
    m = mats(style)
    b = B()
    w = STYLES[style]["wood"]
    x0, z0, x1, z1 = STABLE
    log = MAT[style]["log"]
    for x in range(x0, x1 + 1):
        for z in range(z0, z0 + 5):
            b.put(x, -1, z, "coarse_dirt" if (x + z) % 3 else "dirt")
            post = (x - x0) % 3 == 0 and z in (z0, z0 + 4)
            if post:
                for y in range(0, 3):
                    b.put(x, y, z, log, axis="y")
            elif z == z0 + 4 and x not in (x0, x1):
                b.put(x, 0, z, w + "_fence")
            elif z == z0:
                b.put(x, 0, z, w + "_fence") if (x - x0) % 3 != 1 else b.put(x, 0, z, w + "_fence_gate", facing="north", in_wall="false", open="false", powered="false")
        for i, z in enumerate(range(z0 - 1, z0 + 6)):
            y = 3 + min(i, 6 - i) // 1 // 2
            b.put(x, y, z, m["roof"], facing="south" if i < 3 else "north", **ST) if i != 3 else b.put(x, y, z, m["slab"], type="bottom", waterlogged="false")
    for x in range(x0 + 2, x1 - 1, 3):
        b.put(x, 0, z0 + 2, "hay_block", axis="y")
    b.put(*STABLE_STORE, "barrel", facing="up", open="false")
    # paddock behind (south)
    for x in range(x0, x1 + 1):
        for z in range(z0 + 5, z1 + 1):
            if x in (x0, x1) or z == z1:
                b.put(x, 0, z, w + "_fence")
    b.put(x0 + 3, 0, z1 - 3, "water_cauldron", level="3")
    if level >= 2:
        for x in range(x0 + 5, x1 - 2, 4):
            b.put(x, 0, z1 - 3, "hay_block", axis="x")
        b.put(x1 - 1, 0, z0 + 1, "chest", facing="west", type="single", waterlogged="false")
        b.put(x1 - 1, 1, z0 + 3, "{color}_wool")
    return b.b


# distance from the wall -> depth of the moat there: scarp, bottom, then the bank rising away from the castle
MOAT_PROFILE = {3: 3, 4: 5, 5: 5, 6: 5, 7: 4, 8: 3, 9: 2, 10: 1}


def gate(style, level):
    """Gate track. 1 wooden gate (fence gates under a log lintel); 2 stone gatehouse with a portcullis;
    3 moat around the walls with a drawbridge; 4 arrow traps: dispensers above the gate, fired by levers on the roof."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    log = MAT[style]["log"]
    stone = "cobblestone" if style == "frozen" else "stone_bricks"
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    if level == 1:
        for x in range(gx - 1, gx + 2):
            b.put(x, 0, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
            b.put(x, 1, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
            b.put(x, 3, z1, "stripped_" + log, axis="x")
        for x in (gx - 2, gx + 2):
            for y in range(0, 5):
                b.put(x, y, z1, "stripped_" + log, axis="y")
        b.put(gx, 3, z1 + 1, "{color}_wall_banner", facing="south")
        return b.b
    # stone gatehouse: x gx-3..gx+3, z z1-1..z1+1, passage 3 wide and 4 high
    for x in range(gx - 3, gx + 4):
        for z in range(z1 - 1, z1 + 2):
            for y in range(0, 8):
                passage = abs(x - gx) <= 1 and y < 4
                b.put(x, y, z, "air" if passage else stone)
            if (x + z) % 2 == 0 and (x in (gx - 3, gx + 3) or z in (z1 - 1, z1 + 1)) and (x, z) != (gx - 2, z1 - 1):
                b.put(x, 8, z, "stone_brick_wall" if style != "frozen" else "cobblestone_wall", up="true", north="none", south="none",
                      east="none", west="none", waterlogged="false")
    for x in range(gx - 1, gx + 2):
        b.put(x, 3, z1 + 1, "iron_bars", north="false", south="false", east="true", west="true", waterlogged="false")  # portcullis, raised
        b.put(x, 0, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
        b.put(x, 1, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
    for y in range(0, 8):
        b.put(gx - 2, y, z1 - 2, "ladder", facing="north", waterlogged="false")  # up to the roof, on the inner face
    b.put(gx, 6, z1 + 2, "{color}_wall_banner", facing="south")
    if level >= 3:
        # dry moat, three blocks outside the wall: a steep scarp on the castle side (too high to climb), a flat
        # bottom five deep, and on the far side a bank that rises one block per block like a natural hollow.
        # In front of the gate it keeps its full depth right up to the landing: only the drawbridge crosses it.
        ring = {}
        for o, depth in MOAT_PROFILE.items():
            cells = set()
            for x in range(x0 - o, x1 + o + 1):
                cells |= {(x, z0 - o), (x, z1 + o)}
            for z in range(z0 - o, z1 + o + 1):
                cells |= {(x0 - o, z), (x1 + o, z)}
            for x, z in cells:
                front = z > z1 and abs(x - gx) <= 4
                if front and o > 6:
                    continue  # the landing stands here
                ring[(x, z)] = 5 if front else depth
        # the ditch follows the land: every column is dug from the ground just outside it (its anchor, beyond the
        # bank, where nothing is ever dug), so it runs unbroken around the whole wall, over hills too. In front of
        # the gate it is tied to the gate's own level (anchor = the heart column, which the mod reads as "level").
        ANCHORS_OF[id(b.b)] = b.anchors
        far = max(MOAT_PROFILE) + 4
        for (x, z), depth in sorted(ring.items()):
            if z > z1 and abs(x - gx) <= 6:
                b.anchor = (HEART[0], HEART[2])
            else:
                b.anchor = (x0 - far if x < x0 else x1 + far if x > x1 else x, z0 - far if z < z0 else z1 + far if z > z1 else z)
            for y in range(-depth, 0):
                b.put(x, y, z, "air")
            b.put(x, -depth - 1, z, "coarse_dirt")
        b.anchor = None
        # drawbridge: five wide over the moat (crimson wood doesn't burn: lava trap), a fixed sill in front of the gate
        for x in range(gx - 2, gx + 3):
            for z in range(z1 + 2, z1 + 7):
                b.put(x, -1, z, "crimson_planks")
        # landing platform on the far side, with posts, chains up to the bridge and lanterns
        for x in range(gx - 4, gx + 5):
            for z in range(z1 + 7, z1 + 11):
                b.put(x, -1, z, stone)
                for y in range(-5, -1):
                    if z == z1 + 7:
                        b.put(x, y, z, stone)  # the outer moat wall under the platform edge
        wall_top = "stone_brick_wall" if style != "frozen" else "cobblestone_wall"
        for x in (gx - 4, gx + 4):
            for z in range(z1 + 7, z1 + 11):
                b.put(x, 0, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
        for x in (gx - 3, gx + 3):
            for y in range(0, 4):
                b.put(x, y, z1 + 7, "stripped_" + log, axis="y")
            b.put(x, 4, z1 + 7, "lantern", hanging="false", waterlogged="false")
            for y in range(4, 7):
                b.put(x, y, z1 + 2, "iron_chain", axis="y", waterlogged="false")
        # the lever inside the gatehouse raises and lowers the drawbridge (handled by the mod)
        b.put(gx + 2, 1, z1 - 2, "lever", face="wall", facing="north", powered="false")
    if level >= 4:
        # arrow slits: dispensers in the front face; while the lever on the roof is on, the mod fires volleys from them
        for x in (gx - 3, gx - 1, gx + 1, gx + 3):
            b.put(x, 5, z1 + 1, "dispenser", facing="south", triggered="false")
        b.put(gx - 2, 8, z1, "lever", face="floor", facing="south", powered="false")
    return b.b


def motte(b, style):
    """Castle level 2: the keep stands on a raised stone-walled mound (motte), 4 blocks above the square,
    reached by a grand staircase. The keep moves 3 blocks north to make room for the stairs."""
    t = B()
    castle_keep(t, style, z_shift=-3)
    H = 4
    for (x, y, z), v in t.b.items():
        b.b[(x, y + H, z)] = v
    stone, mossy, wall_top = MAT[style]["stone"], MAT[style]["alt"], MAT[style]["top"]
    x0, z0, x1, z1 = -4, -18, 18, 3
    cx = 7
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            stairs = z == z1 and abs(x - cx) <= 2
            for y in range(0, H):
                if (x, y + 0, z) in b.b:
                    continue
                b.put(x, y, z, (stone if not (x * 7 + y * 3 + z) % 9 == 0 else mossy) if edge else ("grass_block" if y == H - 1 else "dirt"),
                      **({"snowy": "false"} if not edge and y == H - 1 else {}))
            if edge and not stairs and (x + z) % 2 == 0 and (x, H, z) not in b.b:
                b.put(x, H, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    # grand staircase from the motte down to the square, lanterns on the newel posts
    for i in range(H):
        z = z1 + 1 + i
        y = H - 1 - i
        for x in range(cx - 2, cx + 3):
            b.put(x, y, z, stairs_of(stone), facing="north", **ST)
            for yy in range(0, y):
                b.put(x, yy, z, stone)
        for x in (cx - 3, cx + 3):
            for yy in range(0, y + 1):
                b.put(x, yy, z, stone)
    for x in (cx - 3, cx + 3):
        b.put(x, H, z1, stone)
        b.put(x, H + 1, z1, "lantern", hanging="false", waterlogged="false")
    for x in range(cx - 2, cx + 3):
        b.put(x, H, z1, "air")


KEEP_TRIM = {"temperate": "polished_andesite", "frozen": "stone_bricks", "arid": "cut_sandstone", "highland": "polished_andesite",
             "wild": "mossy_stone_bricks"}


def castle_keep(b, style, z_shift=0):
    """Castle tier: a square stone keep replaces the town hall. Rough stone at the foot, a band at each floor,
    arrow loops, a wall-walk on corbels behind battlements, and four corner turrets. Inside: the great hall with a
    carpet to the throne and the lord's floor above. The turrets are the stairs: a door on every floor, a ladder
    from the hall to the roof and on to the turret's top."""
    k = KIT[style]
    stone, alt, wall_top = MAT[style]["stone"], MAT[style]["alt"], MAT[style]["top"]
    trim = KEEP_TRIM[style]
    wood = "spruce"
    x0, z0, x1, z1 = -1, -12 + z_shift, 15, 4 + z_shift
    cx = (x0 + x1) // 2
    H = 12
    POST = dict(up="true", north="none", south="none", east="none", west="none", waterlogged="false")

    def mat(x, y, z):
        if y <= 1:
            return pick(k["stone"], x, y, z)
        return alt if hsh(x, y, z) % 11 == 0 else stone

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, mat(x, 0, z) if edge else "polished_andesite" if (x + z) % 2 else "stone_bricks")
            for y in range(1, H):
                if edge:
                    loop = y in (3, 4, 8, 9) and ((x - x0) % 4 == 2 and z in (z0, z1) or (z - z0) % 4 == 2 and x in (x0, x1))
                    if loop:
                        b.put(x, y, z, "iron_bars", **(PANE_X if z in (z0, z1) else PANE_Z))
                    else:
                        b.put(x, y, z, trim if y in (6, H - 1) else mat(x, y, z))
                else:
                    b.put(x, y, z, "air")
            b.put(x, H, z, stone)  # the roof and the wall-walk
            if not edge:
                b.put(x, 6, z, wood + "_planks")
    # the wall-walk juts out on corbels between the turrets and carries the battlements
    for i in range(x0 + 4, x1 - 3):
        for (x, z, f) in ((i, z0 - 1, "south"), (i, z1 + 1, "north")):
            b.put(x, H - 1, z, stairs_of(stone), facing=f, half="top", shape="straight", waterlogged="false")
            b.put(x, H, z, stone)
            b.put(x, H + 1, z, stone if i % 2 else wall_top, **({} if i % 2 else POST))
    for i in range(z0 + 4, z1 - 3):
        for (x, z, f) in ((x0 - 1, i, "east"), (x1 + 1, i, "west")):
            b.put(x, H - 1, z, stairs_of(stone), facing=f, half="top", shape="straight", waterlogged="false")
            b.put(x, H, z, stone)
            b.put(x, H + 1, z, stone if i % 2 else wall_top, **({} if i % 2 else POST))
    # corner turrets, 5x5, rising 6 above the roof
    T = H + 6
    for west, north in ((True, True), (False, True), (True, False), (False, False)):
        tx = x0 - 1 if west else x1 - 3
        tz = z0 - 1 if north else z1 - 3
        ladder = (tx + 3 if west else tx + 1, tz + 1 if north else tz + 3)   # leans on the turret's wall inside the keep
        door = (tx + 2, tz + 4 if north else tz)                             # doorway in the wall that faces down the hall
        for x in range(tx, tx + 5):
            for z in range(tz, tz + 5):
                edge = x in (tx, tx + 4) or z in (tz, tz + 4)
                for y in range(0, T):
                    if edge:
                        b.put(x, y, z, trim if y in (6, H - 1, T - 1) else mat(x, y, z))
                    elif y > H:
                        b.put(x, y, z, "air")
                if edge:
                    b.put(x, T, z, stone)
                    b.put(x, T + 1, z, stone)
                    if (x + z) % 2 == 0:
                        b.put(x, T + 2, z, wall_top, **POST)
                else:
                    for y in (6, H, T):
                        b.put(x, y, z, stone)  # landings
        for y in (1, 2, 7, 8, H + 1, H + 2):
            b.put(door[0], y, door[1], "air")
        for y in range(1, T + 1):
            b.put(ladder[0], y, ladder[1], "ladder", facing="west" if west else "east", waterlogged="false")
        # arrow loop high in each outer face, the kingdom's banner on a pole at the top
        ox, oz = (tx if west else tx + 4), (tz if north else tz + 4)
        b.put(ox, H + 3, tz + 2, "iron_bars", **PANE_Z)
        b.put(tx + 2, H + 3, oz, "iron_bars", **PANE_X)
        px, pz = (tx + 1 if west else tx + 3), (tz + 3 if north else tz + 1)
        b.put(px, T + 1, pz, wood + "_fence")
        b.put(px, T + 2, pz, wood + "_fence")
        b.put(px, T + 3, pz, "{color}_banner", rotation="0" if north else "8")
    # entrance: an archway three wide with a portcullis drawn up into it, steps, banners and lanterns
    for x in (cx - 1, cx, cx + 1):
        for y in (1, 2, 3):
            b.put(x, y, z1, "air")
        b.put(x, 4, z1, "chiseled_stone_bricks" if style in ("temperate", "highland") else stone)
        b.put(x, 0, z1, "polished_andesite")
    b.put(cx - 1, 3, z1, stairs_of(stone), facing="west", half="top", shape="straight", waterlogged="false")
    b.put(cx + 1, 3, z1, stairs_of(stone), facing="east", half="top", shape="straight", waterlogged="false")
    b.put(cx, 3, z1, "iron_bars", **PANE_X)
    porch(b, cx, z1 + 1, wood, width=5)
    for x in (cx - 4, cx + 4):
        b.put(x, 5, z1 + 1, "{color}_wall_banner", facing="south")
    for x in (cx - 2, cx + 2):
        b.put(x, 3, z1 + 1, "lantern", hanging="true", waterlogged="false")
        b.put(x, 4, z1 + 1, stairs_of(stone), facing="north", half="top", shape="straight", waterlogged="false")
    # great hall: carpet runner to the throne on its dais, tables with benches, chandeliers
    for z in range(z0 + 4, z1):
        b.put(cx, 1, z, "{color}_carpet")
    for x in (cx - 1, cx, cx + 1):
        b.put(x, 1, z0 + 2, stone)
        b.put(x, 1, z0 + 3, stairs_of(stone), facing="north", **ST)
    b.put(cx, 2, z0 + 2, wood + "_stairs", facing="south", **ST)
    b.put(cx, 4, z0 + 1, "{color}_wall_banner", facing="south")
    for z in range(z0 + 5, z1 - 3, 3):
        for x in (cx - 4, cx + 4):
            b.put(x, 1, z, wood + "_fence")
            b.put(x, 2, z, wood + "_pressure_plate", powered="false")
            b.put(x - 1, 1, z, wood + "_stairs", facing="east", **ST)
            b.put(x + 1, 1, z, wood + "_stairs", facing="west", **ST)
    for x in (cx - 4, cx + 4):
        for z in (z0 + 5, z1 - 5):
            b.put(x, 5, z, "lantern", hanging="true", waterlogged="false")
    # the lord's floor: bed, chests, map table, a rug
    b.put(cx - 1, 7, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(cx - 1, 7, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(cx + 1, 7, z0 + 1, "chest", facing="south", type="single", waterlogged="false")
    b.put(cx + 2, 7, z0 + 1, "chest", facing="south", type="single", waterlogged="false")
    b.put(cx, 7, z0 + 7, "cartography_table")
    b.put(cx - 4, 7, z0 + 7, "bookshelf")
    b.put(cx - 4, 7, z0 + 8, "lectern", facing="east", has_book="false", powered="false")
    for x in (cx - 3, cx + 3):
        for z in (z0 + 5, z1 - 5):
            b.put(x, H - 1, z, "lantern", hanging="true", waterlogged="false")


CELLAR = (3, -2, 11, 4)    # x0, z0, x1, z1 under the square side of the main building
CELLAR_STORE = (4, -5, -1)


def cellar(style, level):
    """Cellar track: a stone cellar under the main building. Its entrance is a low stone cellar door beside the
    building, as on an old farmhouse: steps lead down from the square to a doorway in the cellar's south wall.
    (It can't open from inside: the building above changes with every tier, the cellar stays.)
    1 storeroom (barrels, the cellar store); 2 treasury and armory (chests, more barrels, a rug and a banner)."""
    b = B()
    stone = MAT[style]["stone"]
    x0, z0, x1, z1 = CELLAR
    top, bottom = -2, -6
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            edge = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
            for y in range(bottom, top + 1):
                b.put(x, y, z, stone if edge or y in (bottom, top) else "air")
    # the doorway is three high: coming up the first step you need the room above your head
    sx = x0
    for y in range(bottom + 1, bottom + 4):
        b.put(sx, y, z1 + 1, "air")
    for i in range(5):
        z = z1 + 2 + i
        step = bottom + 1 + i
        b.put(sx, step, z, stairs_of(stone), facing="south", **ST)
        b.put(sx, step - 1, z, stone)
        for y in range(step + 1, 1):
            b.put(sx, y, z, "air")
        for y in range(bottom, 1):
            b.put(sx - 1, y, z, stone)  # the stairwell's walls, one block above the ground
            b.put(sx + 1, y, z, stone)
        if i < 3:
            for x in (sx - 1, sx, sx + 1):
                b.put(x, 1, z, stone)   # roofed over; open above the top two steps, or you would hit your head
    b.put(sx, 0, z1 + 1, stone)
    b.put(sx, 1, z1 + 1, stone)
    b.put(sx - 1, 1, z1 + 6, "lantern", hanging="false", waterlogged="false")
    for x in range(x0 + 1, x1):
        b.put(x, bottom + 1, z0, "barrel", facing="up", open="false")
    b.put(CELLAR_STORE[0], CELLAR_STORE[1], CELLAR_STORE[2], "chest", facing="south", type="single", waterlogged="false")
    for x in (x0 + 2, x1 - 2):
        b.put(x, top - 1, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    if level >= 2:
        for z in range(z0 + 1, z1):
            b.put(x1, bottom + 1, z, "chest", facing="west", type="single", waterlogged="false")
        for x in range(x0 + 3, x1 - 1):
            b.put(x, bottom + 1, z1, "barrel", facing="up", open="false")
        b.put((x0 + x1) // 2, bottom + 1, (z0 + z1) // 2, "{color}_carpet")
        b.put((x0 + x1) // 2, bottom + 3, z0, "{color}_wall_banner", facing="south")
    return b.b


BARRACKS = (11, 33, 21, 37)  # door north
BARRACKS_STORE = (12, 0, 36)


def barracks(style, level):
    """Barracks add-on by the gate: stone hall with bunks and weapon racks, a training yard with dummies;
    level 2 adds a second floor of bunks and an armory. The guards (iron golems) are spawned by the mod."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = BARRACKS
    hall(b, style, x0, z0, x1, z1, 8 if level >= 3 else 4, "n", kind="stone", base_h=4 if level >= 3 else None)
    if level >= 3:  # bunk floor upstairs for the larger guard
        upper_floor(b, x0, z0, x1, z1, 5, x0 + 1, (z0 + z1) // 2)
        for x in range(x0 + 3, x1, 3):
            b.put(x, 6, z1 - 1, "{color}_bed", facing="south", part="head", occupied="false")
            b.put(x, 6, z1 - 2, "{color}_bed", facing="south", part="foot", occupied="false")
        b.put((x0 + x1) // 2, 7, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    for x in range(x0 + 2, x1 - 1, 3):
        b.put(x, 1, z1 - 1, "white_bed", facing="south", part="head", occupied="false")
        b.put(x, 1, z1 - 2, "white_bed", facing="south", part="foot", occupied="false")
    b.put(*BARRACKS_STORE, "barrel", facing="up", open="false")
    for x in (x0 + 1, x1 - 1):
        b.put(x, 1, z0 + 1, "grindstone", face="floor", facing="north")
    b.put((x0 + x1) // 2, 3, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 2, "smithing_table")
    if level >= 2:
        b.put(x1 - 1, 1, z0 + 3, "chest", facing="west", type="single", waterlogged="false")
        b.put(x0 + 1, 1, z0 + 2, "chest", facing="east", type="single", waterlogged="false")
        b.put(x0 + 1, 1, z0 + 3, "chest", facing="east", type="single", waterlogged="false")
        for x in (x0 + 3, x1 - 3):
            b.put(x, 4, z0 - 1, "{color}_wall_banner", facing="north")
    return b.b


GUILD = (-21, -17, -11, -9)  # door south
GUILD_STORE = (-19, 0, -16)


def guild_level(style, level):
    """Guild upgrades 4-6 (better gear for sword, archer, mage): the full hall plus a trophy of each upgrade by the door."""
    b = B()
    b.b = dict(guild_hall(style, 3))
    x0, z0, x1, z1 = GUILD
    cx = (x0 + x1) // 2
    marks = [("anvil", dict(facing="east")), ("fletching_table", {}), ("enchanting_table", {})]
    for i in range(level - 3):
        block, props = marks[i]
        b.put(x0 + 2 + i * 3, 1, z0 + 1, block, **props)
    b.put(cx, 4, z0 - 1, "{color}_wall_banner", facing="north")
    return b.b


def guild_hall(style, level):
    """Guild hall of the three allies. 1 swordfighter: hall with a training yard of dummies;
    2 archer: shooting range with targets; 3 mage: a small tower room with a brewing stand and enchanting books."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = GUILD
    hall(b, style, x0, z0, x1, z1, 4, "s", kind="stone")
    cx = (x0 + x1) // 2
    b.put(*GUILD_STORE, "barrel", facing="up", open="false")
    b.put(cx, 3, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    for x in (x0 + 2, x1 - 2):
        b.put(x, 4, z1 + 1, "{color}_wall_banner", facing="south")
    # training yard west of the hall: straw dummies (fence + hay + carved pumpkin)
    for i, z in enumerate((z0 + 1, z0 + 4, z0 + 7)):
        dx = x0 - 4
        b.put(dx, 0, z, STYLES[style]["wood"] + "_fence")
        b.put(dx, 1, z, "hay_block", axis="y")
        b.put(dx, 2, z, "carved_pumpkin", facing="east")
    if level >= 2:
        # shooting range east: targets on posts
        for z in (z0 + 1, z0 + 4, z0 + 7):
            b.put(x1 + 6, 0, z, "hay_block", axis="y")
            b.put(x1 + 6, 1, z, "target", power="0")
        b.put(x1 - 1, 1, z0 + 1, "fletching_table")
    if level >= 3:
        # mage room: brewing stand, enchanting books, amethyst
        b.put(x0 + 1, 1, z0 + 1, "brewing_stand", has_bottle_0="false", has_bottle_1="false", has_bottle_2="false")
        b.put(x0 + 2, 1, z0 + 1, "bookshelf")
        b.put(x0 + 3, 1, z0 + 1, "bookshelf")
        b.put(x0 + 1, 1, z0 + 2, "amethyst_cluster", facing="up", waterlogged="false")
        b.put(x0 + 1, 3, z0 + 3, "{color}_wall_banner", facing="east")
    return b.b


def lava_trap(style, level):
    """Gate level 5: a murder hole above the gate. Three pistons over a stone channel on the gatehouse roof and a
    lever beside them: pulled, the mod pours lava over the front of the gate for eight seconds."""
    b = B()
    g4 = gate(style, 4)
    b.b.update(g4)
    ANCHORS_OF[id(b.b)] = ANCHORS_OF.pop(id(g4), {})  # the moat keeps following the land (it used to lose its anchors here)
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    for x in range(gx - 1, gx + 2):
        b.put(x, 8, z1 + 1, "air")
        b.put(x, 9, z1 + 1, "piston", facing="down", extended="false")
        b.put(x, 10, z1 + 1, "stone_bricks" if style != "frozen" else "cobblestone")
    b.put(gx, 8, z1 - 1, "lever", face="floor", facing="south", powered="false")
    return b.b


def secret_vault(style, level):
    """Cellar level 3: a hidden vault behind the cellar's west wall. A button on the wall slides the 2x2 secret
    door open for five seconds (the mod moves it, with piston sounds)."""
    b = B()
    for k, v in cellar(style, 2).items():
        b.b[k] = v
    stone = MAT[style]["stone"]
    x0, z0, x1, z1 = CELLAR
    top, bottom = -2, -6
    vx0, vx1 = x0 - 7, x0 - 2
    for x in range(vx0 - 1, vx1 + 1):
        for z in range(z0 - 1, z1 + 2):
            edge = x == vx0 - 1 or z in (z0 - 1, z1 + 1)
            for y in range(bottom, top + 1):
                b.put(x, y, z, stone if edge or y in (bottom, top) else "air")
    for z in range(z0, z1 + 1, 2):
        b.put(vx0, bottom + 1, z, "chest", facing="east", type="single", waterlogged="false")
    b.put(vx0 + 2, bottom + 1, z1, "gold_block")
    b.put(vx0 + 3, bottom + 1, z1, "{color}_carpet")
    b.put((vx0 + vx1) // 2, top - 1, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    # the secret door: two blocks wide, two high, part of the cellar wall (x0 - 1); the button sits next to it
    for z in (1, 2):
        for y in (bottom + 1, bottom + 2):
            b.put(x0 - 1, y, z, stone)
    b.put(x0, bottom + 2, 3, "stone_button", face="wall", facing="east", powered="false")
    return b.b


APIARY = (47, 2, 57, 10)
APIARY_STORE = (48, 0, 3)
KENNEL = (48, 14, 56, 20)
TROPHY = (-34, 8, -26, 16)  # door east
# pedestal tops (base frame); the mod puts a trophy block on each once its advancement is done
TROPHY_SPOTS = [(-33, 2, 9), (-31, 2, 9), (-29, 2, 9), (-27, 2, 9), (-33, 2, 15), (-31, 2, 15), (-29, 2, 15), (-27, 2, 15),
                (-33, 2, 12), (-30, 2, 12)]


def apiary(style, level):
    """Apiary add-on: a flower meadow behind a low fence with bee nests on posts; level 2 adds beehives and a
    campfire whose smoke keeps the bees calm when you harvest."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    x0, z0, x1, z1 = APIARY
    flowers = ["poppy", "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet", "lily_of_the_valley"]
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if edge:
                if (x, z) == ((x0 + x1) // 2, z1):
                    b.put(x, 0, z, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
                else:
                    b.put(x, 0, z, w + "_fence")
            elif (x * 5 + z * 3) % 3 != 0:
                b.put(x, -1, z, "grass_block", snowy="false")  # the meadow's own soil: flowers can't stand on sand
                b.put(x, 0, z, flowers[(x * 7 + z) % len(flowers)])
    for x, z in ((x0 + 2, z0 + 2), (x1 - 2, z0 + 2)):
        b.put(x, 0, z, w + "_fence")
        b.put(x, 1, z, w + "_fence")
        b.put(x, 2, z, "bee_nest", facing="south", honey_level="0")
    b.put(*APIARY_STORE, "barrel", facing="up", open="false")
    if level >= 2:
        for x in (x0 + 3, x0 + 5, x0 + 7):
            b.put(x, 0, z1 - 2, "beehive", facing="south", honey_level="0")
            if x == x0 + 5:
                b.put(x, -1, z1 - 3, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
                b.b.pop((x, 0, z1 - 3), None)
    return b.b


def kennel(style, level):
    """Kennel add-on: a small timber shelter for the kingdom's pets (dog, then cat, then parrot) with a bowl,
    a bed and a perch. The pets themselves are spawned and tamed to the lord by the mod."""
    m = mats(style)
    s = STYLES[style]
    b = B()
    w = s["wood"]
    x0, z0, x1, z1 = KENNEL
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if (x + z) % 3 == 0:
                b.put(x, -1, z, "podzol", snowy="false")
            if x in (x0, x1) and z in (z0, z1):
                for y in range(0, 3):
                    b.put(x, y, z, m["corner"], axis="y")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            b.put(x, 3, z, w + "_slab", type="bottom", waterlogged="false")
    for x in range(x0 + 1, x1):
        b.put(x, 0, z0, w + "_planks")
        b.put(x, 1, z0, w + "_planks")
    b.put(x0 + 2, 0, z0 + 2, "{color}_carpet")
    b.put(x0 + 3, 0, z0 + 2, "cauldron")
    b.put(x0 + 2, 2, z0 + 1, "lantern", hanging="true", waterlogged="false")
    if level >= 2:
        b.put(x1 - 2, 0, z0 + 2, "{color}_bed", facing="north", part="head", occupied="false")
        b.put(x1 - 2, 0, z0 + 3, "{color}_bed", facing="north", part="foot", occupied="false")
    if level >= 3:
        b.put(x1 - 1, 0, z1 - 1, w + "_fence")
        b.put(x1 - 1, 1, z1 - 1, w + "_fence")
        b.put(x1 - 1, 2, z1 - 1, w + "_fence")
    return b.b


def trophy_hall(style, level):
    """Trophy hall: a stone hall with pedestals along the walls. Each pedestal shows a trophy once the lord has
    reached its milestone (dragon head, wither skull, beacon, netherite, ...). Level 2 adds banners and lanterns."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = TROPHY
    hall(b, style, x0, z0, x1, z1, 5, "s", kind="stone", floor="polished_andesite")
    for x, y, z in TROPHY_SPOTS:
        b.put(x, 0, z, "polished_deepslate")
        b.put(x, 1, z, "polished_deepslate_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
        b.put(x, y, z, "air")
    for x in range(x0 + 1, x1):
        if x % 2 == 0:
            b.put(x, 0, (z0 + z1) // 2 + 1, "{color}_carpet")
    b.put((x0 + x1) // 2, 4, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    if level >= 2:
        for x in (x0 + 2, x1 - 2):
            b.put(x, 4, z0 + 1, "{color}_wall_banner", facing="south")
            b.put(x, 3, z1 - 1, "lantern", hanging="true", waterlogged="false")
    return b.b


ROYAL = (22, -42, 38, -30)  # the lord's own house, north-east of the keep; door south


def royal_house(style, level):
    """The lord's own house: the biggest house of the settlement, two storeys of stone and timber. Downstairs a hall
    with a hearth, a long table and a library wall; a real staircase leads up to the bedchamber with a double bed,
    wardrobes and a writing desk. Level 2 furnishes it richly: carpets, more books, banners and gold."""
    b = B()
    w = STYLES[style]["wood"]
    x0, z0, x1, z1 = ROYAL
    cx = (x0 + x1) // 2
    hearth = (x1 - 1, z0 + 6)
    hall(b, style, x0, z0, x1, z1, 9, "s", kind="stone", base_h=4, chimney=hearth, windows_back=False)
    # the upper floor, open over the staircase along the west wall
    steps = [(x0 + 1, z1 - 2 - i) for i in range(5)]
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            if (x, z) == hearth:
                continue
            b.put(x, 5, z, "air" if (x, z) in steps else w + "_planks")
    for i, (x, z) in enumerate(steps):
        b.put(x, 1 + i, z, w + "_stairs", facing="north", **ST)
        for y in range(1, 1 + i):
            b.put(x, y, z, w + "_planks")
    for z in range(z1 - 6, z1 - 1):  # a rail beside the stairwell upstairs
        b.put(x0 + 2, 6, z, w + "_fence")
    # downstairs: the hall
    b.put(hearth[0] - 1, 1, hearth[1], "campfire", facing="west", lit="true", signal_fire="false", waterlogged="false")
    for z in (hearth[1] - 1, hearth[1] + 1):
        b.put(hearth[0], 1, z, "stone_bricks")
    for z in range(z1 - 1, z0 + 3, -1):
        b.put(cx, 1, z, "{color}_carpet")
    for x in range(cx + 3, cx + 6):  # the long table with benches
        b.put(x, 1, z0 + 6, w + "_slab", type="top", waterlogged="false")
        b.put(x, 1, z0 + 5, w + "_stairs", facing="north", **ST)
        b.put(x, 1, z0 + 7, w + "_stairs", facing="south", **ST)
    for x in range(x0 + 2, cx - 1):  # the library wall
        b.put(x, 1, z0 + 1, "bookshelf")
        b.put(x, 2, z0 + 1, "bookshelf")
    b.put(cx - 1, 1, z0 + 1, "lectern", facing="south", has_book="false", powered="false")
    for x in (x1 - 1, x1 - 2):
        b.put(x, 1, z0 + 1, "chest", facing="south", type="single", waterlogged="false")
    b.put(x1 - 1, 1, z1 - 1, "barrel", facing="up", open="false")
    b.put(x1 - 2, 1, z1 - 1, "crafting_table")
    b.put(x1 - 3, 1, z1 - 1, "furnace", facing="north", lit="false")
    for x in (cx - 4, cx + 4):
        b.put(x, 4, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    # upstairs: the bedchamber
    for x in (cx, cx + 1):
        b.put(x, 6, z0 + 1, "{color}_bed", facing="north", part="head", occupied="false")
        b.put(x, 6, z0 + 2, "{color}_bed", facing="north", part="foot", occupied="false")
    for x in (cx - 1, cx + 2):
        b.put(x, 6, z0 + 1, "bookshelf")
        b.put(x, 7, z0 + 1, "lantern", hanging="false", waterlogged="false")
    for x in range(cx - 1, cx + 3):
        for z in range(z0 + 3, z0 + 6):
            b.put(x, 6, z, "{color}_carpet")
    for z in (z1 - 2, z1 - 3):
        b.put(x1 - 1, 6, z, "chest", facing="west", type="single", waterlogged="false")
    b.put(x1 - 1, 6, z0 + 2, "lectern", facing="west", has_book="false", powered="false")
    b.put(x1 - 1, 6, z0 + 1, "bookshelf")
    b.put(cx, 9, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    if level >= 2:
        for x in range(x0 + 4, cx - 1):
            b.put(x, 6, z0 + 1, "bookshelf")
            b.put(x, 7, z0 + 1, "bookshelf")
        b.put(x0 + 3, 6, z0 + 1, "enchanting_table")
        for x in range(cx - 3, cx + 5):
            for z in range(z0 + 6, z0 + 9):
                if (x, 6, z) not in b.b and (x, z) != hearth:
                    b.put(x, 6, z, "{color}_carpet")
        for z in (z0 + 2, z1 - 2):
            b.put(x1 - 1, 1, z, "gold_block")
            b.put(x1 - 1, 2, z, "lantern", hanging="false", waterlogged="false")
        b.put(x0 + 3, 1, z1 - 1, "jukebox", has_record="false")
        b.put(x0 + 4, 1, z1 - 1, "anvil", facing="north")
        for x in (cx - 5, cx + 5):
            if (x, 6, z1 + 1) not in b.b:
                b.put(x, 6, z1 + 1, "{color}_wall_banner", facing="south")
        for x in (cx - 4, cx + 4):
            b.put(x, 9, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    return b.b


# ---------------- the other vanilla systems: one building each, with a worker (spawned by the mod) ----------------
# rect = (x0, z0, x1, z1), door side 'n' or 's'; store = barrel position; worker stands at the centre
SHOPS = {
    "tavern": ((22, -24, 34, -14), "s"),
    "market": ((39, -24, 53, -16), "s"),
    "cartographer": ((58, -22, 66, -14), "s"),
    "music": ((-42, -28, -32, -20), "s"),
    "workshop": ((-46, 30, -38, 38), "n"),
    "armorer": ((-24, 38, -16, 44), "n"),
    "tannery": ((26, 38, 34, 44), "n"),
    "archaeology": ((58, 24, 68, 32), "n"),
    "mine": ((61, 2, 69, 10), "s"),
    "nether": ((-47, 4, -39, 12), "s"),
}


def shop_store(name):
    (x0, z0, x1, z1), door = SHOPS[name]
    return (x0 + 1, 0, z0 + 1 if door == "s" else z1 - 1)


def upper_floor(b, x0, z0, x1, z1, y, ladder_x, ladder_z):
    """A plank floor at height y inside the walls, with a ladder hole; the ladder leans on the inner wall to the west."""
    for x in range(x0 + 1, x1):
        for z in range(z0 + 1, z1):
            b.put(x, y, z, "spruce_planks")
    for yy in range(1, y + 1):
        b.put(ladder_x, yy, ladder_z, "ladder", facing="east", waterlogged="false")


def gasthaus(style, level):
    """The tavern as a German inn ("Gasthaus"), after the half-timbered houses of Burg Eltz and the old towns on the
    German Timber-Frame Road (pilot of the detailed building style, 2026-11-18).

    Level 1: a rubble-stone ground floor (stone bricks, cobblestone, andesite and tuff mixed, quoins at the corners),
    arched door, windows with stone sills and shutters, a slate roof (deepslate tiles) with timber-framed gables.
    Level 2: outdoor seating under the eaves, the inn's hanging sign on an iron bracket, banner, lanterns.
    Level 3: a timber-framed upper storey that juts out over the street on brackets (a jetty), with braces beside
    every window, an oriel over the door, a steep slate roof with two dormers, and guest rooms.
    Footprint (22, -24)..(34, -14), door south at x 28 like the old tavern: storage, innkeeper and roads unchanged."""
    b = B()
    x0, z0, x1, z1 = 22, -24, 34, -14
    fz = -15                    # the ground floor's front wall; the strip in front of it lies under the eaves / jetty
    cx = 28
    upper = level >= 3
    STONE = ("stone_bricks", "stone_bricks", "cobblestone", "andesite", "tuff_bricks", "mossy_stone_bricks", "stone_bricks", "cobblestone")
    BEAM, BEAM_ST = "dark_oak_log", "dark_oak_stairs"
    FILL = ("calcite", "calcite", "calcite", "polished_diorite")
    SLATE = ("deepslate_tiles",)
    SLATE_ST = ("deepslate_tile_stairs",)

    def stone(x, y, z):
        b.put(x, y, z, pick(STONE, x, y, z))

    def beam(x, y, z, axis="y"):
        b.put(x, y, z, BEAM, axis=axis)

    def plaster(x, y, z):
        b.put(x, y, z, pick(FILL, x, y, z))

    # ---- ground floor: stone, 4 high, floor of spruce planks on a stone course ----
    gh = 4
    for x in range(x0, x1 + 1):
        for z in range(z0, fz + 1):
            edge = x in (x0, x1) or z in (z0, fz)
            b.put(x, 0, z, pick(("cobblestone", "stone_bricks", "mossy_cobblestone"), x, 0, z) if edge else "spruce_planks")
            if not edge:
                continue
            for y in range(1, gh + 1):
                corner = x in (x0, x1) and z in (z0, fz)
                if corner:  # quoins: dressed stone alternating at the corners
                    b.put(x, y, z, "polished_andesite" if y % 2 else "stone_bricks")
                else:
                    stone(x, y, z)
    # the strip under the eaves in front: a raised stone terrace, one step up from the street
    for x in range(x0, x1 + 1):
        b.put(x, 0, z1, pick(("stone_bricks", "cobblestone", "andesite"), x, 0, z1))
    b.put(cx, 0, z1, "stone_brick_stairs", facing="north", **ST)
    # windows: pairs in the front, singles on the sides; stone sills outside in front, shutters
    front_windows = (24, 25, 31, 32)
    for x in front_windows:
        for y in (2, 3):
            b.put(x, y, fz, "glass_pane", **PANE_X)
        if level >= 2:
            b.put(x, 1, z1, "stone_brick_slab", type="top", waterlogged="false")
        b.put(x, 4, fz, "stone_brick_stairs", facing="north", half="top", shape="straight", waterlogged="false")
    for x in (23, 26, 30, 33):
        b.put(x, 2, z1, "spruce_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
    for z in (-21, -18):
        for xx in (x0, x1):
            for y in (2, 3):
                b.put(xx, y, z, "glass_pane", **PANE_Z)
    for x in (25, 31):
        for y in (2, 3):
            b.put(x, y, z0, "glass_pane", **PANE_X)
    # arched door with a keystone
    for half, y in (("lower", 1), ("upper", 2)):
        b.put(cx, y, fz, "spruce_door", facing="south", half=half, hinge="left", open="false", powered="false")
    b.put(cx - 1, 3, fz, "stone_brick_stairs", facing="east", half="top", shape="straight", waterlogged="false")
    b.put(cx + 1, 3, fz, "stone_brick_stairs", facing="west", half="top", shape="straight", waterlogged="false")
    b.put(cx, 3, fz, "chiseled_stone_bricks")
    for x in (cx - 1, cx + 1):
        b.put(x, 1, fz, "polished_andesite")
        b.put(x, 2, fz, "polished_andesite")

    # ---- upper storey (level 3): timber frame, jutting one block out over the front ----
    if upper:
        floor_y, top_y = gh + 1, gh + 6          # floor/jetty beam at 5, panels 6..9, top plate 10
        for x in range(x0 + 1, x1):
            for z in range(z0 + 1, fz + 1):
                b.put(x, floor_y, z, "spruce_planks")
        for x in range(x0, x1 + 1):              # jetty sill over the street, joists' ends below it
            beam(x, floor_y, z1, "x")
            beam(x, floor_y, z0, "x")
            if x % 2 == 0:
                b.put(x, floor_y - 1, z1, BEAM_ST, facing="north", half="top", shape="straight", waterlogged="false")
        for z in range(z0, z1 + 1):
            beam(x0, floor_y, z, "z")
            beam(x1, floor_y, z, "z")

        def frame(cells, posts, along, windows):
            """One timber-framed wall, mostly white: posts every 3, sill and plate, windows in the bays listed, and
            only at the two ends a head and a foot brace beside the corner post (the 'Mann' figure of German
            framing). Heavy bracing everywhere read as a dark wall in the renders."""
            lo_dir, hi_dir = ("west", "east") if along == "x" else ("north", "south")
            pane = PANE_X if along == "x" else PANE_Z
            at = {u: (x, z) for u, x, z in cells}
            for u, x, z in cells:
                for y in range(floor_y + 1, top_y + 1):
                    if u in posts:
                        beam(x, y, z)
                    elif y == top_y:
                        beam(x, y, z, along)
                    else:
                        plaster(x, y, z)
            bays = [[u for u in at if a < u < c] for a, c in zip(posts, posts[1:])]
            for i, bay in enumerate(bays):
                if i in windows:
                    for u in bay:
                        for y in (floor_y + 2, floor_y + 3):
                            b.put(at[u][0], y, at[u][1], "glass_pane", **pane)
                elif i in (0, len(bays) - 1) and bay:
                    u = bay[0] if i == 0 else bay[-1]
                    toward = lo_dir if i == 0 else hi_dir
                    b.put(at[u][0], top_y - 1, at[u][1], BEAM_ST, facing=toward, half="top", shape="straight", waterlogged="false")
                    b.put(at[u][0], floor_y + 1, at[u][1], BEAM_ST, facing=toward, **ST)

        posts_x = [22, 25, 28, 31, 34]
        frame([(x, x, z1) for x in range(x0, x1 + 1)], posts_x, "x", {1, 2})
        frame([(x, x, z0) for x in range(x0, x1 + 1)], posts_x, "x", {1, 2})
        posts_z = [-24, -21, -18, -15, -14]
        frame([(z, x0, z) for z in range(z0, z1 + 1)], posts_z, "z", {1})
        frame([(z, x1, z) for z in range(z0, z1 + 1)], posts_z, "z", {1})
        # oriel (Erker) over the door, on stone corbels, under the eaves
        for x in (cx - 1, cx, cx + 1):
            b.put(x, floor_y, z1 + 1, "stone_brick_stairs", facing="north", half="top", shape="straight", waterlogged="false")
            for y in range(floor_y + 1, top_y + 1):
                if x != cx or y == top_y:
                    beam(x, y, z1 + 1)
                elif y == floor_y + 1:
                    plaster(x, y, z1 + 1)
                else:
                    b.put(x, y, z1 + 1, "glass_pane", **PANE_X)
            for y in range(floor_y + 1, top_y):
                b.put(x, y, z1, "air")       # the oriel opens into the room
        b.put(cx, floor_y - 1, z1 + 1, "stone_brick_stairs", facing="north", half="top", shape="straight", waterlogged="false")
        wall_top = top_y
    else:
        wall_top = gh
        for x in range(x0, x1 + 1):          # timber wall plate on the stone
            beam(x, gh + 1, z0, "x")
            beam(x, gh + 1, fz, "x")
        for z in range(z0, fz + 1):
            beam(x0, gh + 1, z, "z")
            beam(x1, gh + 1, z, "z")
        wall_top = gh + 1

    # ---- slate roof, ridge along x; steep (2 up per row near the eaves) on the storeyed inn ----
    front_eave = z1 + (1 if upper else 0)
    back_eave = z0 - 1
    rows = front_eave - back_eave + 1
    half_rows = rows // 2
    rises = [2, 2, 2, 2, 1, 1, 1, 1] if upper else [1] * 8
    base = wall_top + 1
    hz = {}
    for i in range(half_rows + 1):
        hz[i] = base + sum(rises[:i])
    roof_y = {}
    roof_lo = {}   # lowest roof block of each row: the gable stops below it
    for x in range(x0 - 1, x1 + 2):
        for i in range(half_rows):
            for z, facing in ((front_eave - i, "north"), (back_eave + i, "south")):
                lo = hz[i - 1] + 1 if i else hz[i]
                for y in range(lo, hz[i]):
                    b.put(x, y, z, pick(SLATE, x, y, z))
                b.put(x, hz[i], z, pick(SLATE_ST, x, hz[i], z), facing=facing, **ST)
                roof_y[z] = hz[i]
                roof_lo[z] = lo
        if rows % 2 == 1:
            zc = back_eave + half_rows
            for y in range(hz[half_rows - 1] + 1, hz[half_rows] + 1):
                b.put(x, y, zc, pick(SLATE, x, y, zc))
            b.put(x, hz[half_rows] + 1, zc, "deepslate_tile_slab", type="bottom", waterlogged="false")
            roof_y[zc] = hz[half_rows] + 1
            roof_lo[zc] = hz[half_rows - 1] + 1
    # gables: timber-framed triangles with a small window
    for xx in (x0, x1):
        for z in range(z0, (z1 if upper else fz) + 1):
            top_here = roof_lo.get(z, base) - 1
            for y in range(wall_top + 1, top_here + 1):
                if z in (z0, z1 if upper else fz, -19) or (y - wall_top) % 3 == 0:
                    beam(xx, y, z, "y" if z in (z0, z1 if upper else fz, -19) else "z")
                else:
                    plaster(xx, y, z)
        for y in (wall_top + 2, wall_top + 3):
            for z in (-21, -17):
                if roof_y.get(z, 0) - 1 > y:
                    b.put(xx, y, z, "glass_pane", **PANE_Z)
    # chimney of rough stone through the back slope, with a cap
    chx, chz = 32, z0 + 1
    peak = max(roof_y.values())
    for y in range(1, peak + 3):
        stone(chx, y, chz)
    b.put(chx, peak + 3, chz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    # ---- inside: hearth, bar, tables ----
    back = z0 + 1
    b.put(chx, 1, chz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(chx - 1, 1, chz, "smoker", facing="south", lit="true")
    for x in (chx - 1, chx + 1):
        b.put(x, 3, chz, "stone_brick_stairs", facing="south", half="top", shape="straight", waterlogged="false")
    b.put(*shop_store("tavern"), "barrel", facing="up", open="false")
    for x in (24, 25):
        b.put(x, 1, back, "barrel", facing="up", open="false")
        b.put(x, 2, back, "barrel", facing="south", open="false")
    for x in range(23, 27):  # the bar
        b.put(x, 1, back + 2, "spruce_planks" if x == 26 else "barrel", **({} if x == 26 else dict(facing="up", open="false")))
        b.put(x, 2, back + 2, "spruce_slab", type="bottom", waterlogged="false")
    for tx in (27, 30):      # tables with benches
        for tz in (-19, -17):
            b.put(tx, 1, tz, "spruce_fence")
            b.put(tx, 2, tz, "spruce_pressure_plate", powered="false")
            b.put(tx - 1, 1, tz, "spruce_stairs", facing="east", **ST)
            b.put(tx + 1, 1, tz, "spruce_stairs", facing="west", **ST)
    for x in (26, 30):        # tie beams carry the lanterns
        for z in range(z0 + 1, fz):
            beam(x, gh, z, "z")
        b.put(x, gh - 1, -19, "lantern", hanging="true", waterlogged="false")
    # ---- level 2: outdoor seating, the inn's sign on an iron bracket, banner ----
    if level >= 2:
        for x in (23, 33):
            b.put(x, 1, z1, "barrel", facing="up", open="false")
            b.put(x, 2, z1, "potted_red_tulip" if x == 23 else "potted_cornflower")
        for x in (24, 32):
            b.put(x, 1, z1, "spruce_stairs", facing="west" if x == 24 else "east", **ST)
        b.put(26, gh, z1, "iron_bars", east="true", west="true", north="true", south="false", waterlogged="false")
        b.put(26, gh - 1, z1, "dark_oak_hanging_sign", rotation="4", attached="false", waterlogged="false")
        b.put(cx + 2, gh, z1, "lantern", hanging="true", waterlogged="false")
        b.put(cx - 2, gh, z1, "lantern", hanging="true", waterlogged="false")
        b.put(x0 + 1, 1, fz - 1, "{color}_banner", rotation="0")
    # ---- level 3: guest rooms upstairs, a ladder in the corner ----
    if upper:
        fy = gh + 2
        for yy in range(1, gh + 2):
            b.put(x0 + 1, yy, -20, "ladder", facing="east", waterlogged="false")
        for x in range(25, x1, 3):
            b.put(x, fy, back, "{color}_bed", facing="north", part="head", occupied="false")
            b.put(x, fy, back + 1, "{color}_bed", facing="north", part="foot", occupied="false")
        for z in range(z0 + 1, z1):           # a tie beam across the guest floor carries its lantern
            beam(cx, gh + 6, z, "z")
        b.put(cx, gh + 5, -19, "lantern", hanging="true", waterlogged="false")
    return b.b



def shop(style, name, level):
    """A small building in the village style with the furniture of its trade; level 2 adds more of it."""
    if name == "tavern" and style == "temperate":
        return gasthaus(style, level)  # pilot of the detailed style (see gasthaus)
    m = mats(style)
    b = B()
    (x0, z0, x1, z1), door = SHOPS[name]
    h = 5 if name in ("tavern", "market", "music") else 4
    base_h = h
    if level >= 3:
        h += 4  # upper floor
    kind = "nether" if name == "nether" else "stone" if name in ("mine", "armorer", "workshop") else "timber"
    hall(b, style, x0, z0, x1, z1, h, door, kind=kind, base_h=base_h if level >= 3 else None,
         chimney=(x1 - 1, z0 if door == "s" else z1) if name in ("tavern", "armorer", "tannery") else None)
    back = z0 + 1 if door == "s" else z1 - 1
    front = z1 - 1 if door == "s" else z0 + 1
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    b.put(*shop_store(name), "barrel", facing="up", open="false")
    b.put(cx, h - 1, cz, "lantern", hanging="true", waterlogged="false")
    fb = "north" if door == "s" else "south"
    inside = {
        "tavern": [("barrel", (x0 + 2, back), dict(facing="up", open="false")), ("barrel", (x0 + 3, back), dict(facing="up", open="false")),
                   ("smoker", (x1 - 1, back), dict(facing=fb, lit="true")), ("campfire", (x1 - 2, back), dict(facing=fb, lit="true", signal_fire="false", waterlogged="false"))],
        "market": [("composter", (x0 + 2, back), dict(level="0")), ("barrel", (x0 + 3, back), dict(facing="up", open="true")),
                   ("smithing_table", (x1 - 2, back), {}), ("cartography_table", (x1 - 3, back), {}), ("loom", (x1 - 4, back), dict(facing=fb))],
        "cartographer": [("cartography_table", (x0 + 2, back), {}), ("lectern", (x1 - 1, back), dict(facing=fb, has_book="false", powered="false"))],
        "music": [("jukebox", (cx, back), dict(has_record="false")), ("note_block", (x0 + 2, back), dict(instrument="harp", note="0", powered="false")),
                  ("note_block", (x1 - 2, back), dict(instrument="bell", note="12", powered="false"))],
        "workshop": [("crafter", (x0 + 2, back), dict(orientation="north_up", crafting="false", triggered="false")),
                     ("copper_block", (x1 - 2, back), {}), ("redstone_lamp", (x1 - 1, back), dict(lit="false"))],
        "armorer": [("blast_furnace", (x0 + 2, back), dict(facing=fb, lit="true")), ("smithing_table", (x1 - 2, back), {}),
                    ("anvil", (cx, back), dict(facing="east"))],
        "tannery": [("cauldron", (x0 + 2, back), {}), ("water_cauldron", (x0 + 3, back), dict(level="3")), ("smoker", (x1 - 2, back), dict(facing=fb, lit="false"))],
        "archaeology": [("decorated_pot", (x0 + 2, back), dict(facing=fb, cracked="false", waterlogged="false")),
                        ("decorated_pot", (x0 + 4, back), dict(facing=fb, cracked="false", waterlogged="false")),
                        ("suspicious_sand", (x1 - 2, back), dict(dusted="0")), ("chiseled_bookshelf", (x1 - 3, back), dict(facing=fb))],
        "nether": [("gold_block", (cx, back), {}), ("crying_obsidian", (x0 + 1, back), {}),
                   ("soul_campfire", (x1 - 1, back), dict(facing=fb, lit="true", signal_fire="false", waterlogged="false")),
                   ("respawn_anchor", (x0 + 2, back), dict(charges="2"))],
        "mine": [("rail", (cx, front), dict(shape="north_south", waterlogged="false")), ("chest", (x1 - 1, back), dict(facing=fb, type="single", waterlogged="false"))],
    }[name]
    for block, (x, z), props in inside:
        b.put(x, 1, z, block, **props)
    if name == "tavern":
        for x in range(x0 + 2, x1 - 1, 3):
            b.put(x, 1, cz, "spruce_fence")
            b.put(x, 2, cz, "spruce_pressure_plate", powered="false")
            b.put(x - 1, 1, cz, "spruce_stairs", facing="east", **ST)
            b.put(x + 1, 1, cz, "spruce_stairs", facing="west", **ST)
        b.put(cx, h - 1, cz - 2, "lantern", hanging="true", waterlogged="false")
    if name == "mine":
        # shaft with a ladder down to a lit gallery
        sx, sz = x0 + 2, cz
        b.put(sx, 0, sz, "spruce_trapdoor", facing="north", half="top", open="true", powered="false", waterlogged="false")
        for x in range(sx, sx + 7):
            for y in (-20, -19):
                b.put(x, y, sz, "air")
            if (x - sx) % 3 == 0:
                for y in (-20, -19):
                    b.put(x, y, sz + 1, "spruce_fence")
                if x != sx:
                    b.put(x, -18, sz, "spruce_planks")
                b.put(x, -19, sz - 1, "wall_torch", facing="south")
        b.put(sx + 6, -20, sz, "chest", facing="west", type="single", waterlogged="false")
        for y in range(-1, -21, -1):
            b.put(sx - 1, y, sz, "stone" if y < -3 else "cobblestone")      # the shaft's wall: the ladder hangs on it
            b.put(sx, y, sz, "ladder", facing="east", waterlogged="false")
    if level >= 2:
        b.put(x0 + 1, 1, front, "{color}_banner", rotation="0")
        b.put(x1 - 1, h - 1, front, "lantern", hanging="true", waterlogged="false")
        if name == "tavern":
            b.put(x0 + 1, 1, back, "{color}_bed", facing="north" if door == "s" else "south", part="head", occupied="false")
            b.put(x0 + 1, 1, back + (1 if door == "s" else -1), "{color}_bed", facing="north" if door == "s" else "south", part="foot", occupied="false")
    if level >= 3:
        fy = base_h + 1
        upper_floor(b, x0, z0, x1, z1, fy, x0 + 1, cz)
        b.put(cx, base_h, cz, "lantern", hanging="true", waterlogged="false")
        bf = "north" if door == "s" else "south"
        for x in range(x0 + 3, x1, 3):
            if name == "tavern":  # guest rooms
                b.put(x, fy + 1, back, "{color}_bed", facing=bf, part="head", occupied="false")
                b.put(x, fy + 1, back + (1 if door == "s" else -1), "{color}_bed", facing=bf, part="foot", occupied="false")
            else:  # warehouse
                b.put(x, fy + 1, back, "barrel", facing="up", open="false")
                b.put(x, fy + 2, back, "barrel", facing="up", open="false")
                b.put(x, fy + 1, front, "chest", facing=bf, type="single", waterlogged="false")
    return b.b


def dock(style, level):
    """Dock at the nearest water (placed by the mod on the shore): a pier into the water on log posts, a little
    roof at the end with a barrel; level 2 is longer, with a side jetty and a fish drying rack.
    Local frame: the shore is at z = 0, the water lies toward +z, y = 0 is the water surface."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    log = MAT[style]["log"]
    L = 10 if level == 1 else 15
    for z in range(0, L):
        for x in (-1, 0, 1):
            b.put(x, 0, z, w + "_planks")
        if z % 4 == 0:
            for x in (-2, 2):
                for y in range(-3, 2):
                    b.put(x, y, z, log, axis="y")
                if z in (0, L - 4):
                    b.put(x, 2, z, "lantern", hanging="false", waterlogged="false")
    for z in range(L - 4, L):
        for x in (-2, -1, 0, 1, 2):
            b.put(x, 4, z, w + "_slab", type="bottom", waterlogged="false")
    for x in (-2, 2):
        for z in (L - 4, L - 1):
            for y in range(1, 4):
                b.put(x, y, z, w + "_fence")
    b.put(-1, 1, L - 2, "barrel", facing="up", open="false")
    b.put(1, 1, L - 1, "composter", level="0")
    if level >= 2:
        for x in range(2, 8):
            b.put(x, 0, L // 2, w + "_planks")
        for x in (4, 6):
            b.put(x, 1, L // 2 - 1, w + "_fence")
            b.put(x, 2, L // 2 - 1, w + "_fence")
        for x in range(4, 7):
            b.put(x, 3, L // 2 - 1, w + "_slab", type="bottom", waterlogged="false")
    return b.b


def donjon(b, style):
    """Citadel: a tall square tower rises from the keep's roof (motte frame: keep at y 4, roof at 16), with
    windows, battlements, the kingdom's banners and a beacon on a small iron pyramid at the very top."""
    stone, alt, top = MAT[style]["stone"], MAT[style]["alt"], MAT[style]["top"]
    cx, cz = 7, -7
    base, height = 17, 18
    for x in range(cx - 3, cx + 4):
        for z in range(cz - 3, cz + 4):
            edge = x in (cx - 3, cx + 3) or z in (cz - 3, cz + 3)
            for y in range(base, base + height):
                if edge:
                    window = y % 5 == 2 and (x == cx or z == cz)
                    b.put(x, y, z, "iron_bars" if window else (alt if (x + y + z) % 7 == 0 else stone),
                          **(dict(north="true", south="true", east="false", west="false", waterlogged="false") if window and x != cx
                             else dict(north="false", south="false", east="true", west="true", waterlogged="false") if window else {}))
                else:
                    b.put(x, y, z, "air")
            b.put(x, base + height, z, stone)
            if edge and (x + z) % 2 == 0:
                b.put(x, base + height + 1, z, top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    for y in (base, base + 1):
        b.put(cx, y, cz + 3, "air")  # doorway from the keep's roof
    # beside the window column: a ladder can't hold on to iron bars
    for y in range(base, base + height):
        b.put(cx + 2, y, cz - 1, "ladder", facing="west", waterlogged="false")
    b.put(cx + 2, base + height, cz - 1, "air")
    for x in range(cx - 1, cx + 2):
        for z in range(cz - 1, cz + 2):
            b.put(x, base + height + 1, z, "iron_block")
    b.put(cx, base + height + 2, cz, "beacon")
    for x, z, f in ((cx, cz + 4, "south"), (cx, cz - 4, "north"), (cx + 4, cz, "east"), (cx - 4, cz, "west")):
        b.put(x, base + height - 3, z, "{color}_wall_banner", facing=f)


LAIR = (1, -40, 17, -24)  # north of the castle, inside the walls


def lair(style, level):
    """Dragon lair north of the castle, inside the walls: a round court of end stone behind a low stone ring wall
    with a gate to the south, a stone path to the castle, obsidian pillars with end rods and a nest in the middle;
    level 2 adds purpur arches, chorus plants and the dragon egg pedestal (the egg is placed by the mod)."""
    import math
    b = B()
    x0, z0, x1, z1 = LAIR
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    r = (x1 - x0) // 2
    stone = "cobblestone" if style == "frozen" else "stone_bricks"
    wall_top = "cobblestone_wall" if style == "frozen" else "stone_brick_wall"
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if d <= r + 0.5:
                b.put(x, -1, z, "end_stone" if d > 3 else "end_stone_bricks")
                if d <= 3:
                    b.put(x, -2, z, "obsidian")
            gate = z > cz and abs(x - cx) <= 1
            if r - 0.5 < d <= r + 0.5 and not gate:
                b.put(x, 0, z, stone if (x * 7 + z * 3) % 6 else "mossy_" + stone)
                b.put(x, 1, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    # the gate posts and the path south to the castle
    for x in (cx - 2, cx + 2):
        for y in range(0, 3):
            b.put(x, y, z1, stone)
        b.put(x, 3, z1, "lantern", hanging="false", waterlogged="false")
    for i in range(8):
        a = math.pi * 2 * i / 8
        px, pz = cx + round(math.cos(a) * (r - 3)), cz + round(math.sin(a) * (r - 3))
        for y in range(0, 5 + (i % 3)):
            b.put(px, y, pz, "obsidian" if y % 4 else "crying_obsidian")
        b.put(px, 5 + (i % 3), pz, "end_rod", facing="up")
    b.put(cx, 0, cz, "end_stone_brick_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    if level >= 2:
        for dx, dz in ((-3, 0), (3, 0), (0, -3), (0, 3)):
            for y in range(0, 4):
                b.put(cx + dx, y, cz + dz, "purpur_pillar", axis="y")
            b.put(cx + dx, 4, cz + dz, "purpur_slab", type="bottom", waterlogged="false")
        for dx, dz in ((-2, 2), (2, -2), (-2, -2)):
            b.put(cx + dx, 0, cz + dz, "chorus_flower", age="5")
        b.put(cx, 1, cz, "air")
    return b.b


def normalize(blocks, extra=()):
    """Shifts a plot so no coordinate is negative (buildings may reach west/north of the base frame).
    Returns blocks, heart, size and the shifted extra points."""
    # the plot is the blocks' bounding box; the heart may lie outside it (add-ons far from the square),
    # so levelling an add-on only touches its own area
    xs = [p[0] for p in blocks]
    zs = [p[2] for p in blocks]
    dx, dz = -min(xs), -min(zs)
    nb = {(x + dx, y, z + dz): v for (x, y, z), v in blocks.items()}
    size = (max(xs) + dx + 1, max(max(p[1] for p in blocks) + 1, 8), max(zs) + dz + 1)
    heart = (HEART[0] + dx, HEART[1], HEART[2] + dz)
    return nb, heart, size, [None if e is None else (e[0] + dx, e[1], e[2] + dz) for e in extra]


def to_json(blocks, seconds, storage=None, animals=None, pads=None):
    segs = SEGS_OF.get(id(blocks), {})
    anchors = ANCHORS_OF.get(id(blocks), {})
    follow = id(blocks) in FOLLOW
    orig = blocks
    blocks, heart, size, (storage,) = normalize(blocks, [storage])
    shift = (heart[0] - HEART[0], heart[2] - HEART[2])
    names, palette, out = {}, [], []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        key = (name, tuple(sorted(props.items())))
        if key not in names:
            names[key] = len(palette)
            e = {"Name": name}
            if props:
                e["Properties"] = props
            palette.append(e)
        seg = segs.get((x - shift[0], y, z - shift[1]))
        anc = anchors.get((x - shift[0], y, z - shift[1]))
        for one in (seg or [None]):  # a block shared by several lanes is listed once for each
            row = [x, y, z, names[key]]
            if one is not None or anc is not None:
                row.append(one if one is not None else -1)
            if anc is not None:
                row += [anc[0] + shift[0], anc[1] + shift[1]]
            out.append(row)
    j = {"size": list(size), "heart": list(heart), "seconds": seconds, "palette": palette, "blocks": out}
    if storage:
        j["storage"] = list(storage)
    if pads:
        j["pads"] = [[a + shift[0], c + shift[1], e + shift[0], g + shift[1]] for a, c, e, g in pads]
    if follow:
        j["follow"] = True
    if segs:
        j["segments"] = [{"name": n, "any": [[t, l] for t, l in rules]} for n, rules in SEGDEF_OF[id(orig)]]
    if animals:
        dx, dz = heart[0] - HEART[0], heart[2] - HEART[2]
        j["animals"] = [[a[0], a[1] + dx, a[2], a[3] + dz] for a in animals]
    return j


SECONDS = {1: 60, 2: 90, 3: 150}

def build_jobs(styles=None):
    """Every template of every style: (style, id, blocks, seconds, storage, animals, apron), spacing moves applied."""
    jobs = []
    del DOCK_JOBS[:]
    MOVE = {"brewery": (1, 1), "wood": (-3, 0), "guild": (-2, -3), "barracks": (0, 2)}  # spacing (see tools/check.py)

    def moved(j):
        style, name, blocks, seconds, storage, animals = j[:6]
        d = MOVE.get(name.rsplit("_", 1)[0])
        if not d:
            return j
        nb = {(x + d[0], y, z + d[1]): v for (x, y, z), v in blocks.items()}
        for reg in (SEGS_OF, ANCHORS_OF):
            if id(blocks) in reg:
                reg[id(nb)] = {(x + d[0], y, z + d[1]): v for (x, y, z), v in reg[id(blocks)].items()}
        if id(blocks) in FOLLOW:
            FOLLOW.add(id(nb))
        st = None if storage is None else (storage[0] + d[0], storage[1], storage[2] + d[1])
        an = None if animals is None else [[a[0], a[1] + d[0], a[2], a[3] + d[1]] for a in animals]
        return (style, name, nb, seconds, st, an) + tuple(j[6:])
    STORE = (FARM[0] - 2, 0, FARM[1] + FARM[3] // 2 - 1)
    for style in (styles or STYLES):
        first = len(jobs)
        for level in (1, 2, 3):
            jobs.append((style, f"tent_{level}", tent(style, level), SECONDS[level], None, None))
        for level, secs in ((1, 240), (2, 200), (3, 200)):
            jobs.append((style, f"hamlet_{level}", hamlet(style, level), secs, TAX_STORE if level >= 2 else None, None))
        jobs.append((style, "village_1", hamlet(style, 4), 300, TAX_STORE, None))
        jobs.append((style, "town_1", hamlet(style, 5), 420, TAX_STORE, None))
        jobs.append((style, "castle_1", hamlet(style, 6), 600, TAX_STORE, None))
        jobs.append((style, "castle_2", hamlet(style, 7), 720, TAX_STORE, None))
        jobs.append((style, "citadel_1", hamlet(style, 8), 900, TAX_STORE, None))
        for level in (1, 2):
            jobs.append((style, f"lair_{level}", lair(style, level), 300, None, None))
        for level in (1, 2):
            jobs.append((style, f"cellar_{level}", cellar(style, level), 150, CELLAR_STORE, None))
        for level, secs in ((1, 300), (2, 300), (3, 420), (4, 480)):
            jobs.append((style, f"wall_{level}", walls(style, level), secs, None, None))
        for level in (1, 2, 3, 4):
            jobs.append((style, f"gate_{level}", gate(style, level), 120 + 60 * level, None, None, 6))
        for level in (1, 2, 3):
            jobs.append((style, f"guild_{level}", guild_hall(style, level), 200, GUILD_STORE, None))
        jobs.append((style, "gate_5", lava_trap(style, 5), 240, None, None))
        for name in SHOPS:
            for level in ((1, 2, 3) if name in ("tavern", "market") else (1, 2)):
                jobs.append((style, f"{name}_{level}", shop(style, name, level), 160 + 40 * level, shop_store(name), None))
        for level in (1, 2):
            DOCK_JOBS.append((style, level))
        for level in (1, 2):
            jobs.append((style, f"apiary_{level}", apiary(style, level), 150, APIARY_STORE,
                         [["bee", 52, 1, 5], ["bee", 50, 1, 7], ["bee", 54, 1, 6]] if level == 1 else [["bee", 51, 1, 6], ["bee", 53, 1, 7]]))
            jobs.append((style, f"trophy_{level}", trophy_hall(style, level), 200, None, None))
            jobs.append((style, f"royal_{level}", royal_house(style, level), 360, None, None))
        for level in (1, 2, 3):
            jobs.append((style, f"kennel_{level}", kennel(style, level), 120, None, None))
        jobs.append((style, "cellar_3", secret_vault(style, 3), 200, CELLAR_STORE, None))
        jobs.append((style, "barracks_3", barracks(style, 3), 260, BARRACKS_STORE, None))
        for level in (4, 5, 6):
            jobs.append((style, f"guild_{level}", guild_level(style, level), 220, GUILD_STORE, None))
        for level in (1, 2):
            jobs.append((style, f"barracks_{level}", barracks(style, level), 200, BARRACKS_STORE, None))
            jobs.append((style, f"library_{level}", library(style, level), 180, LIB_STORE, None))
            jobs.append((style, f"brewery_{level}", brewery(style, level), 180, BREW_STORE, None))
            jobs.append((style, f"stables_{level}", stables(style, level), 180, STABLE_STORE,
                         [["horse", 44, 0, 32], ["horse", 48, 0, 31]] if level == 1 else [["donkey", 50, 0, 32]]))
        for level, secs in ((1, 120), (2, 150), (3, 180)):
            jobs.append((style, f"farm_{level}", farm(style, level), secs, STORE,
                         [["cow", 39, 0, 4], ["cow", 40, 0, 6], ["sheep", 38, 0, 7], ["sheep", 41, 0, 3], ["chicken", 37, 0, 5],
                          ["chicken", 38, 0, 3]] if level == 2 else None))
        for level, secs in ((1, 150), (2, 150), (3, 180)):
            jobs.append((style, f"smith_{level}", smithy(style, level), secs, SMITH_STORE, None))
            jobs.append((style, f"wood_{level}", woodcutter(style, level), secs, WOOD_STORE, None))
        # the style's buildings are done: light them, apply the spacing moves, then lay the roads around them
        mine = jobs[first:]
        for j in mine:
            hang_lanterns(j[2])
        mine = [moved(j) for j in mine]
        plan_roads(style, mine)
        for level in (1, 2, 3, 4):
            mine.append((style, f"road_{level}", roads(style, level), 90 + 30 * level, None, None))
        for level in (1, 2, 3):
            mine.append((style, f"lamp_{level}", lamps(style, level), 60 + 30 * level, None, None))
        jobs[first:] = mine

    def apron(name):
            # 14: the 1:1 slope reaches 11 blocks up, so cliffs next to a building are cut back into a natural slope
        return 0 if name.startswith(("road_", "lamp_", "wall_1", "wall_2")) else 4 if name.startswith("wall_") else 14
    return [j if len(j) == 7 else j + (apron(j[1]),) for j in jobs]


if __name__ == "__main__":
    jobs = build_jobs()
    # The plot of a track: the footprints of all its levels. Every level levels the whole plot, so the land is
    # shaped once, for the biggest version, and an upgrade never has to cut into what was built around it.
    NO_PLOT = ("walls", "roads", "lamps", "gate", "dock", "cellar")
    plots = {}
    for style, name, blocks, *_ in jobs:
        if name.startswith("tent_") or track_of(name) in NO_PLOT:
            continue
        cells = [(x, z) for (x, y, z), v in blocks.items() if y >= 0 and v[0] != "minecraft:air"]
        if cells:
            rect = (min(c[0] for c in cells), min(c[1] for c in cells), max(c[0] for c in cells), max(c[1] for c in cells))
            rects = plots.setdefault((style, track_of(name)), [])
            if rect not in rects:
                rects.append(rect)
    for style, name, blocks, seconds, storage, animals, apron in jobs:
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, f"{name}.json"), "w") as f:
            j = to_json(blocks, seconds, storage, animals, None if name.startswith("tent_") else plots.get((style, track_of(name))))
            if apron:
                j["apron"] = apron
            json.dump(j, f, separators=(",", ":"))
        if "--render" in sys.argv and (len(sys.argv) < 3 or name in sys.argv[2:]):
            sys.path.insert(0, os.path.dirname(__file__))
            from render import render, add_ground
            nb, _, size, _ = normalize(blocks)
            colored = {p: (n.replace("{color}", "minecraft:blue"), pr) for p, (n, pr) in nb.items()}
            g = add_ground({p: v for p, v in colored.items() if p[1] >= 0}, size, pad=0)
            for p, v in colored.items():
                if p[1] < 0:
                    g[p] = v
            os.makedirs("build/previews", exist_ok=True)
            render(g, size, 0, 3, bg=(170, 200, 230, 255)).save(f"build/previews/{style}_{name}.png")
    for style, level in DOCK_JOBS:
        blocks = dock(style, level)
        nb, heart, size, _ = normalize(blocks)
        # the dock's own origin (shore point 0,0,0) is its heart
        xs = [p[0] for p in blocks]
        zs = [p[2] for p in blocks]
        heart = (-min(xs), 0, -min(zs))
        names, palette, out = {}, [], []
        for (x, y, z), (name, props) in sorted(nb.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
            key = (name, tuple(sorted(props.items())))
            if key not in names:
                names[key] = len(palette)
                e = {"Name": name}
                if props:
                    e["Properties"] = props
                palette.append(e)
            out.append([x, y, z, names[key]])
        j = {"size": list(size), "heart": list(heart), "seconds": 150, "palette": palette, "blocks": out, "anchored": True,
             "storage": [-1 + heart[0], 1, (10 if level == 1 else 15) - 2 + heart[2]]}
        with open(os.path.join(OUT, style, f"dock_{level}.json"), "w") as f:
            json.dump(j, f, separators=(",", ":"))
    print(len(jobs) + len(DOCK_JOBS), "templates")
