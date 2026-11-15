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


def a_frame(b, s, x0, z0, length, width=5, open_front=True, crossed=False):
    """Hollow A-frame tent/hut, entrance at z0+length (south). width 5 or 7."""
    half = width // 2
    for z in range(z0, z0 + length):
        for i in range(half):
            mat = s["hem"] if i == 0 else s["canvas"]
            b.put(x0 + i, i, z, mat, facing="east", **ST)
            b.put(x0 + width - 1 - i, i, z, mat, facing="west", **ST)
        b.put(x0 + half, half, z, s["ridge"], type="bottom", waterlogged="false")
    # back gable closed
    for i in range(1, half + 1):
        for x in range(x0 + i, x0 + width - i):
            b.put(x, i - 1, z0, s["gable"])
    # ridge pole tips
    b.put(x0 + half, half, z0 - 1, s["pole"])
    b.put(x0 + half, half, z0 + length, s["pole"])
    if crossed:  # Norse carved gable ends: crossed boards above the ridge
        for zz in (z0 - 1, z0 + length):
            b.put(x0 + half - 1, half, zz, s["wood"] + "_fence")
            b.put(x0 + half + 1, half, zz, s["wood"] + "_fence")
    # entrance flaps
    tz = z0 + length
    for x in (x0 + 1, x0 + width - 2):
        b.put(x, 0, tz, s["wood"] + "_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")


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
        b.put(7, 1, 3, "lantern", hanging="true", waterlogged="false")
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
        b.put(7, 1, 3, "lantern", hanging="true", waterlogged="false")
        b.put(7, 1, 6, "lantern", hanging="true", waterlogged="false")
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
            b.put(7, 2, z, "lantern", hanging="true", waterlogged="false")
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
        b.put(x, 1, z1 + 1, "spruce_trapdoor", facing="south", half="top", open="false", powered="false", waterlogged="false")
        b.put(x, 2, z1 + 1, "azalea")
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
    # steps up to the veranda
    for i in range(F):
        b.put(cx, i, z1 + F - i, "jungle_stairs", facing="north", **ST)
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
    b.put(hx, hy, hz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(hx - 2, 0, hz, f"{w}_stairs", facing="west", **ST)
    b.put(hx - 2, 0, hz + 1, f"{w}_stairs", facing="west", **ST)
    b.put(hx + 2, 0, hz, f"{w}_stairs", facing="east", **ST)
    b.put(hx + 2, 0, hz + 1, f"{w}_stairs", facing="east", **ST)
    banner_pole(b, s, 2, 9, 3)
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
            b.put(x, 0, z, "sweet_berry_bush" if style == "frozen" else "rose_bush", **({"age": "3"} if style == "frozen" else {"half": "lower"}))
            if style != "frozen":
                b.put(x, 1, z, "rose_bush", half="upper")
    road = road_cells()
    del road
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
    """Farm level 3: small smokehouse south of the pasture where meat is cured over a smoker."""
    w = s["wood"]
    x0, x1, z0, z1 = 36, 40, 15, 18
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, "cobblestone" if edge else "packed_mud")
            if edge:
                corner = x in (x0, x1) and z in (z0, z1)
                for y in (1, 2):
                    b.put(x, y, z, s["pole"] if corner else (w + "_planks" if s["wood"] == "spruce" else "mud_bricks"))
    door = (x0 + 2, z0)
    b.put(door[0], 1, door[1], w + "_door", facing="north", half="lower", hinge="left", open="false", powered="false")
    b.put(door[0], 2, door[1], w + "_door", facing="north", half="upper", hinge="left", open="false", powered="false")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            b.put(x, 3, z, w + "_slab", type="bottom", waterlogged="false")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                b.put(x, 3, z, "cobblestone_slab", type="bottom", waterlogged="false")
    b.put(x0 + 2, 3, z1 - 1, "cobblestone")
    b.put(x0 + 2, 4, z1 - 1, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    b.put(x0 + 2, 1, z1 - 1, "smoker", facing="north", lit="true")
    b.put(x0 + 1, 1, z1 - 1, "barrel", facing="up", open="false")
    b.put(x0 + 3, 1, z1 - 1, "barrel", facing="up", open="false")
    for x in (x0 + 1, x0 + 3):
        b.put(x, 2, z0 + 1, "iron_chain", axis="y", waterlogged="false")


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


WALL = (-50, 72, -34, 50)  # x0, x1, z0, z1 of the palisade ring (village plot)


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
    for x, z in ring:
        if z == z1 and abs(x - gx) <= 1:
            continue
        h = 3 + ((x * 7 + z * 13) % 3 == 0)
        for y in range(0, h):
            b.put(x, y, z, log, axis="y")
        b.put(x, h, z, w + "_fence")
        b.put(x, -1, z, "coarse_dirt")
    # the gate opening stays free: gates are their own track
    if level >= 2:
        # walkway on the inside at y=2, reached from the towers
        inner = {(x, z0 + 1) for x in range(x0 + 1, x1)} | {(x, z1 - 1) for x in range(x0 + 1, x1) if abs(x - gx) > 2}
        inner |= {(x0 + 1, z) for z in range(z0 + 1, z1)} | {(x1 - 1, z) for z in range(z0 + 1, z1)}
        for x, z in inner:
            b.put(x, 2, z, w + "_slab", type="top", waterlogged="false")
        for cx, cz in ((x0, z0), (x1, z0), (x0, z1), (x1, z1), (gx - 4, z1), (gx + 4, z1)):
            # 5x5 watchtower straddling the wall: platform at 6 you can stand on, rails, roof, ladder inside
            tx = max(x0 - 2, min(x1 - 2, cx - 2))
            tz = max(z0 - 2, min(z1 - 2, cz - 2))
            b.anchor = (tx + 2, tz + 2)
            for x in range(tx, tx + 5):
                for z in range(tz, tz + 5):
                    corner = x in (tx, tx + 4) and z in (tz, tz + 4)
                    edge = x in (tx, tx + 4) or z in (tz, tz + 4)
                    for y in range(0, 10):
                        if corner:
                            b.put(x, y, z, log, axis="y")
                        elif y < 6 and (x, z) not in ((tx + 2, tz + 2), (tx + 2, tz + 3)):
                            b.put(x, y, z, "air")
                    b.put(x, 6, z, w + "_planks")
                    if edge and not corner:
                        b.put(x, 7, z, w + "_fence")
                    b.put(x, 10, z, w + "_slab", type="bottom", waterlogged="false")
            for y in range(0, 7):
                b.put(tx + 2, y, tz + 3, log, axis="y")  # the ladder hangs on this
                b.put(tx + 2, y, tz + 2, "ladder", facing="north", waterlogged="false")
            b.put(tx + 2, 6, tz + 2, "ladder", facing="north", waterlogged="false")
            b.put(tx + 2, 9, tz + 2, "lantern", hanging="true", waterlogged="false")
            b.anchor = None
    return b.b


# Road segments and when they appear: a segment is built once any of its (track, level) rules holds,
# so roads only lead to buildings that exist. The mod adds new segments when a building is finished.
SEGMENTS = [
    ("gate", [("main", 1)]),                       # the road out of the village and to the heart
    ("well", [("main", 4)]),
    ("square", [("main", 7)]),
    ("smith", [("smith", 1)]),
    ("street_w1", [("main", 5), ("wood", 1), ("brewery", 1)]),
    ("street_w2", [("wood", 1), ("brewery", 1)]),
    ("house2", [("main", 5)]),
    ("street_e1", [("main", 6), ("stables", 1)]),
    ("house3", [("main", 6)]),
    ("street_e2", [("main", 7), ("stables", 1)]),
    ("house4", [("main", 7)]),
    ("wood", [("wood", 1)]),
    ("farm", [("farm", 1)]),
    ("pasture", [("farm", 2)]),
    ("smokehouse", [("farm", 3)]),
    ("library", [("library", 1)]),
    ("brewery", [("brewery", 1)]),
    ("stables", [("stables", 1)]),
    ("barracks", [("barracks", 1)]),
    ("guild", [("guild", 1)]),
    ("apiary", [("apiary", 1), ("kennel", 1)]),
    ("kennel", [("kennel", 1)]),
    ("trophy", [("trophy", 1)]),
] + [(n, [(n, 1)]) for n in ("tavern", "market", "cartographer", "music", "workshop", "armorer", "tannery", "archaeology", "mine", "nether")]
SEG = {name: i for i, (name, _) in enumerate(SEGMENTS)}


def road_cells():
    """The full road network. Returns {(x, z): (kind, segment)}; kind 'core' (centre line) or 'side'."""
    cells = {}

    def strip(seg, xs, zs, core_x=None, core_z=None):
        for x in xs:
            for z in zs:
                core = (core_x is not None and x == core_x) or (core_z is not None and z == core_z)
                old = cells.get((x, z))
                if old is None or (core and old[0] != "core"):
                    cells[(x, z)] = ("core" if core else "side", old[1] if old else seg)

    strip("gate", range(6, 9), range(9, WALL[3] + 1), core_x=7)          # heart to the gate
    strip("well", range(4, 6), range(12, 15), core_z=13)                 # heart to the well
    strip("square", range(0, 15), range(9, 17), core_x=7)
    strip("smith", range(-14, 6), range(12, 14), core_z=12)              # west road
    strip("smith", range(-13, -10), range(9, 12), core_x=-12)
    strip("street_w1", range(-5, 6), range(18, 20), core_z=18)          # street of houses, west part
    strip("street_w2", range(-26, -5), range(18, 20), core_z=18)
    strip("house2", [-5], [20, 21], core_x=-5)
    strip("street_e1", range(9, 20), range(18, 20), core_z=18)
    strip("house3", [19], [20, 21], core_x=19)
    strip("street_e2", range(20, 35), range(18, 20), core_z=18)
    strip("house4", [31], [20, 21], core_x=31)
    strip("wood", [-26], [20, 21], core_x=-26)
    strip("farm", range(9, 27), range(12, 14), core_z=12)                # east road to the field gate
    strip("farm", [26], [11], core_x=26)
    strip("farm", [20], range(5, 12), core_x=20)                         # to the farm barrel
    strip("pasture", range(27, 43), range(12, 14), core_z=12)
    strip("pasture", [39], [10, 11], core_x=39)
    strip("smokehouse", [38], [14], core_x=38)
    strip("library", range(-28, -14), range(12, 14), core_z=12)
    strip("library", [-28], range(5, 12), core_x=-28)
    strip("brewery", [-17], range(20, 25), core_x=-17)
    strip("stables", range(35, 48), range(18, 20), core_z=18)
    strip("stables", [47], range(20, 26), core_x=47)
    strip("barracks", range(9, 17), range(31, 33), core_z=31)
    strip("guild", range(-17, -13), range(12, 14), core_z=12)
    strip("guild", [-16], range(-7, 12), core_x=-16)
    strip("apiary", range(43, 46), range(12, 14), core_z=12)
    strip("apiary", [45], range(10, 12), core_x=45)
    strip("apiary", range(45, 53), [11], core_z=11)
    strip("kennel", [52], range(12, 14), core_x=52)
    strip("trophy", range(-25, -14), range(12, 14), core_z=12)
    # roads to the trade buildings: from the nearest street to each door
    for name, ((x0, z0, x1, z1), door) in SHOPS.items():
        cx = (x0 + x1) // 2
        dz = z1 + 2 if door == "s" else z0 - 2
        target = 12 if door == "s" and dz < 12 else 18 if door == "n" and dz > 18 else 12
        lo, hi = sorted((dz, target))
        strip(name, [cx], range(lo, hi + 1), core_x=cx)
        a, c = sorted((cx, 7))
        strip(name, range(a, c + 1), [target], core_z=target)
    for x in (1, 2, 3):
        for z in (12, 13, 14):
            cells.pop((x, z), None)                                      # the well
    return cells


SEGS_OF = {}  # blocks dict id -> {pos: segment index}
DOCK_JOBS = []
FOLLOW = set()  # ids of block dicts whose blocks follow the terrain (roads, lamps, walls)
ANCHORS_OF = {}  # blocks dict id -> {pos: (x, z) anchor column}

ROAD_MATS = {
    1: ("dirt_path", "coarse_dirt"),
    2: ("dirt_path", "coarse_dirt"),
    3: ("cobblestone", "gravel", "mossy_cobblestone"),
    4: ("stone_bricks", "polished_andesite", "cracked_stone_bricks"),
}


def roads(style, level):
    """Roads track: 1 trodden path (centre line, gaps), 2 full dirt roads, 3 cobblestone and gravel,
    4 stone brick with polished andesite borders."""
    b = B()
    cells = road_cells()
    m = ROAD_MATS[level]
    for (x, z), (kind, seg) in cells.items():
        b.seg = SEG[seg]
        h = (x * 73856093 ^ z * 19349663) % 20
        if level == 1:
            if kind != "core":
                continue
            b.put(x, -1, z, m[0])
        elif level == 2:
            b.put(x, -1, z, m[0])
        elif level == 3:
            b.put(x, -1, z, m[1] if h < 5 else m[2] if h < 8 else m[0])
        else:
            border = any((x + dx, z + dz) not in cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            b.put(x, -1, z, m[1] if border else m[2] if h < 3 else m[0])
    SEGS_OF[id(b.b)] = b.segs
    FOLLOW.add(id(b.b))
    return b.b


# lamp posts: x, z and the direction their arm points (towards the road)
LAMP_SPOTS = [(5, 22, 1, 0, "gate"), (9, 22, -1, 0, "gate"), (5, 30, 1, 0, "gate"), (9, 30, -1, 0, "gate"),
              (5, 35, 1, 0, "gate"), (9, 35, -1, 0, "gate"),
              (-6, 11, 0, 1, "smith"), (19, 11, 0, 1, "farm"), (32, 11, 0, 1, "pasture"),
              (-10, 17, 0, 1, "street_w2"), (2, 17, 0, 1, "street_w1"), (13, 17, 0, 1, "street_e1"), (25, 17, 0, 1, "street_e2"),
              (0, 9, 1, 0, "square"), (14, 9, -1, 0, "square")]


def lamps(style, level):
    """Lamps track: 1 torches on posts, 2 lanterns on posts, 3 stone-footed posts with a hanging lantern on an arm."""
    s = STYLES[style]
    b = B()
    fence = s["wood"] + "_fence"
    for x, z, dx, dz, seg in LAMP_SPOTS:
        b.seg = SEG[seg]
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
            b.put(x + dx, 2, z + dz, "lantern", hanging="true", waterlogged="false")
            b.put(x, 4, z, "lantern", hanging="false", waterlogged="false")
    SEGS_OF[id(b.b)] = b.segs
    FOLLOW.add(id(b.b))
    return b.b


def stone_walls(style, level):
    """Walls 3: stone curtain wall, 5 high, crenellated, walkway behind the battlements, arched gate.
    Walls 4: adds square stone towers at the corners and beside the gate, with banners."""
    s = STYLES[style]
    b = B()
    frozen = style == "frozen"
    main, alt, crack = ("cobblestone", "mossy_cobblestone", "stone_bricks") if frozen else ("stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks")
    wall_top = "cobblestone_wall" if frozen else "stone_brick_wall"
    slab = "cobblestone_slab" if frozen else "stone_brick_slab"
    x0, x1, z0, z1 = WALL
    gx = HEART[0]
    H = 5

    def mat(x, y, z):
        h = (x * 73856093 ^ y * 19349663 ^ z * 83492791) % 20
        return alt if h < 3 else crack if h < 5 else main

    ring = [(x, z0) for x in range(x0, x1 + 1)] + [(x, z1) for x in range(x0, x1 + 1)]
    ring += [(x0, z) for z in range(z0 + 1, z1)] + [(x1, z) for z in range(z0 + 1, z1)]
    for x, z in ring:
        gate = z == z1 and abs(x - gx) <= 1
        for y in range(0, H):
            if gate and y < 4:
                continue
            b.put(x, y, z, mat(x, y, z))
        if (x + z) % 2 == 0:
            b.put(x, H, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
        b.put(x, -1, z, "cobblestone")
    # walkway behind the battlements
    inner = {(x, z0 + 1) for x in range(x0 + 1, x1)} | {(x, z1 - 1) for x in range(x0 + 1, x1)}
    inner |= {(x0 + 1, z) for z in range(z0 + 1, z1)} | {(x1 - 1, z) for z in range(z0 + 1, z1)}
    for x, z in inner:
        if not (z == z1 - 1 and abs(x - gx) <= 1):
            b.put(x, H - 1, z, slab, type="top", waterlogged="false")
    # stairs up to the walkway on both sides of the gate
    for i in range(H - 1):
        for x in (gx - 3 - i, gx + 3 + i):
            b.put(x, i, z1 - 2, main)
    if level >= 4:
        spots = [(x0, z0), (x1, z0), (x0, z1), (x1, z1), (gx - 4, z1), (gx + 4, z1), (x0, (z0 + z1) // 2), (x1, (z0 + z1) // 2)]
        for cx, cz in spots:
            tx0 = max(x0 - 1, min(x1 - 3, cx - 2))
            tz0 = max(z0 - 1, min(z1 - 3, cz - 2))
            if cz == z1:
                tz0 = z1 - 2
            for x in range(tx0, tx0 + 5):
                for z in range(tz0, tz0 + 5):
                    edge = x in (tx0, tx0 + 4) or z in (tz0, tz0 + 4)
                    for y in range(0, 9):
                        if edge:
                            b.put(x, y, z, mat(x, y, z))
                        elif y in (0, H - 1):
                            b.put(x, y, z, main)
                        else:
                            b.put(x, y, z, "air")
                    b.put(x, 9, z, main if not edge else mat(x, 9, z))
                    if edge and (x + z) % 2 == 0:
                        b.put(x, 10, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
            # doorway onto the walkway, ladder from the ground to the top
            lx, lz = tx0 + 2, tz0 + 2
            for y in range(1, 9):
                b.put(lx, y, lz + 1, main)
                b.put(lx, y, lz, "ladder", facing="north", waterlogged="false")
            b.put(lx, H - 1, lz, "ladder", facing="north", waterlogged="false")
            b.put(lx, 9, lz, "air")
            b.put(lx, 8, lz - 1, "lantern", hanging="true", waterlogged="false")
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


def stone_hut(b, x0, z0, x1, z1, walls_h, wall, corner, floor, door, roof_stairs, roof_slab):
    """Rectangular building with corner posts, a door on the given side ('n'/'s') at the middle, gable roof along x."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, wall if edge else floor)
            if edge:
                c = x in (x0, x1) and z in (z0, z1)
                for y in range(1, walls_h + 1):
                    b.put(x, y, z, corner if c else wall, **({"axis": "y"} if c and "log" in corner else {}))
    dx = (x0 + x1) // 2
    dz = z0 if door == "n" else z1
    facing = "north" if door == "n" else "south"
    porch(b, dx, dz - 1 if door == "n" else dz + 1, "spruce", facing="south" if door == "n" else "north")
    b.put(dx, 1, dz, "spruce_door", facing=facing, half="lower", hinge="left", open="false", powered="false")
    b.put(dx, 2, dz, "spruce_door", facing=facing, half="upper", hinge="left", open="false", powered="false")
    depth = z1 - z0 + 1
    half = depth // 2 + 1
    for x in range(x0 - 1, x1 + 2):
        for i in range(half):
            b.put(x, walls_h + 1 + i, z0 - 1 + i, roof_stairs, facing="south", **ST)
            b.put(x, walls_h + 1 + i, z1 + 1 - i, roof_stairs, facing="north", **ST)
        if depth % 2 == 1:
            b.put(x, walls_h + half, z0 - 1 + half, roof_slab, type="bottom", waterlogged="false")
    for xx in (x0, x1):
        for i in range(1, half):
            for z in range(z0 + i, z1 - i + 1):
                b.put(xx, walls_h + i, z, wall)


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
    stone_hut(b, x0, z0, x1, z1, 4, m["stone"], m["corner"], "spruce_planks", "s", m["roof"], m["slab"])
    for x in range(x0 + 2, x1 - 1, 2):
        b.put(x, 2, z0, "glass_pane", north="false", south="false", east="true", west="true", waterlogged="false")
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
    stone_hut(b, x0, z0, x1, z1, 3, m["stone"], m["corner"], "packed_mud", "n", m["roof"], m["slab"])
    cx = (x0 + x1) // 2
    for y in range(1, 9):
        b.put(x1 - 1, y, z1, "bricks")
    b.put(x1 - 1, 9, z1, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
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
        b.put(gx, 4, z1 + 1, "{color}_wall_banner", facing="south")
        return b.b
    # stone gatehouse: x gx-3..gx+3, z z1-1..z1+1, passage 3 wide and 4 high
    for x in range(gx - 3, gx + 4):
        for z in range(z1 - 1, z1 + 2):
            for y in range(0, 8):
                passage = abs(x - gx) <= 1 and y < 4
                b.put(x, y, z, "air" if passage else stone)
            if (x + z) % 2 == 0 and (x in (gx - 3, gx + 3) or z in (z1 - 1, z1 + 1)):
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
        # moat: two blocks wide, two deep, three blocks outside the wall; a plank drawbridge on chains at the gate
        ring = set()
        for o in (3, 4, 5):
            for x in range(x0 - o, x1 + o + 1):
                ring |= {(x, z0 - o), (x, z1 + o)}
            for z in range(z0 - o, z1 + o + 1):
                ring |= {(x0 - o, z), (x1 + o, z)}
        for x, z in ring:
            bridge = abs(x - gx) <= 1 and z > z1
            # water two blocks below the ground: too high to climb out without the bridge
            b.put(x, -1, z, "crimson_planks" if bridge else "air")  # crimson wood doesn't burn (lava trap)
            b.put(x, -2, z, "air")
            b.put(x, -3, z, "water", level="0")
            b.put(x, -4, z, "water", level="0")
            b.put(x, -5, z, "dirt")
        for x in range(gx - 1, gx + 2):
            for z in range(z1 + 2, z1 + 7):
                b.put(x, -1, z, "crimson_planks")
        # the lever inside the gatehouse raises and lowers the drawbridge (handled by the mod)
        b.put(gx + 2, 1, z1 - 2, "lever", face="wall", facing="north", powered="false")
        for x in (gx - 2, gx + 2):
            b.put(x, 0, z1 + 6, w + "_fence")
            for y in range(1, 5):
                b.put(x, y, z1 + 6, "iron_chain", axis="y", waterlogged="false")
    if level >= 4:
        # arrow traps: dispensers in the front face, a lever on the roof above each fires it
        for x in (gx - 2, gx + 2):
            b.put(x, 6, z1 + 1, "dispenser", facing="south", triggered="false")
            b.put(x, 8, z1 + 1, "lever", face="floor", facing="north", powered="false")
    return b.b


def motte(b, style):
    """Castle level 2: the keep stands on a raised stone-walled mound (motte), 4 blocks above the square,
    reached by a grand staircase. The keep moves 3 blocks north to make room for the stairs."""
    t = B()
    castle_keep(t, style, z_shift=-3)
    H = 4
    for (x, y, z), v in t.b.items():
        b.b[(x, y + H, z)] = v
    stone = "cobblestone" if style == "frozen" else "stone_bricks"
    wall_top = "cobblestone_wall" if style == "frozen" else "stone_brick_wall"
    x0, z0, x1, z1 = -4, -18, 18, 3
    cx = 7
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            stairs = z == z1 and abs(x - cx) <= 2
            for y in range(0, H):
                if (x, y + 0, z) in b.b:
                    continue
                b.put(x, y, z, (stone if not (x * 7 + y * 3 + z) % 9 == 0 else "mossy_" + stone) if edge else ("grass_block" if y == H - 1 else "dirt"),
                      **({"snowy": "false"} if not edge and y == H - 1 else {}))
            if edge and not stairs and (x + z) % 2 == 0 and (x, H, z) not in b.b:
                b.put(x, H, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    # grand staircase from the motte down to the square, lanterns on the newel posts
    for i in range(H):
        z = z1 + 1 + i
        y = H - 1 - i
        for x in range(cx - 2, cx + 3):
            b.put(x, y, z, stone.replace("bricks", "brick") + "_stairs" if stone == "stone_bricks" else "cobblestone_stairs", facing="north", **ST)
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


def castle_keep(b, style, z_shift=0):
    """Castle tier: a square stone keep replaces the town hall. Great hall with a carpet to the throne,
    upper floor, walkable battlements and four corner towers rising above the roof."""
    frozen = style == "frozen"
    stone, alt, wall_top = MAT[style]["stone"], MAT[style]["alt"], MAT[style]["top"]
    wood = "spruce"
    x0, z0, x1, z1 = -1, -12 + z_shift, 15, 4 + z_shift
    cx = (x0 + x1) // 2
    H = 12

    def mat(x, y, z):
        return alt if (x * 7 + y * 13 + z * 5) % 11 == 0 else stone

    def crenel(x, y, z):
        b.put(x, y, z, wall_top, up="true", north="none", south="none", east="none", west="none", waterlogged="false")

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            b.put(x, 0, z, mat(x, 0, z) if edge else "polished_andesite" if (x + z) % 2 else "stone_bricks")
            for y in range(1, H):
                if edge:
                    window = y in (3, 8) and ((x - x0) % 4 == 2 and z in (z0, z1) or (z - z0) % 4 == 2 and x in (x0, x1))
                    if window:
                        pane = dict(north="false", south="false", east="true", west="true") if z in (z0, z1) else dict(north="true", south="true", east="false", west="false")
                        b.put(x, y, z, "iron_bars", waterlogged="false", **pane)
                    else:
                        b.put(x, y, z, mat(x, y, z))
                else:
                    b.put(x, y, z, "air")
            if not edge:
                b.put(x, 6, z, wood + "_planks")
                b.put(x, H, z, stone)
            elif (x + z) % 2 == 0:
                crenel(x, H, z)
    # corner towers, 5x5, rising 6 above the battlements
    for tx, tz in ((x0 - 1, z0 - 1), (x1 - 3, z0 - 1), (x0 - 1, z1 - 3), (x1 - 3, z1 - 3)):
        for x in range(tx, tx + 5):
            for z in range(tz, tz + 5):
                edge = x in (tx, tx + 4) or z in (tz, tz + 4)
                for y in range(0, H + 6):
                    if edge:
                        b.put(x, y, z, mat(x, y, z))
                b.put(x, H + 6, z, stone)
                if edge and (x + z) % 2 == 0:
                    crenel(x, H + 7, z)
    # entrance: double door, porch, banners
    for x in (cx - 1, cx + 1):
        b.put(x, 1, z1, wood + "_door", facing="south", half="lower", hinge="left" if x < cx else "right", open="false", powered="false")
        b.put(x, 2, z1, wood + "_door", facing="south", half="upper", hinge="left" if x < cx else "right", open="false", powered="false")
    for y in (1, 2):
        b.put(cx, y, z1, stone)
    for y in (1, 2, 3):
        for x in (cx - 1, cx, cx + 1):
            if y == 3:
                b.put(x, y, z1, "chiseled_stone_bricks" if not frozen else stone)
    porch(b, cx, z1 + 1, wood, width=5)
    for x in (cx - 4, cx + 4):
        b.put(x, 5, z1 + 1, "{color}_wall_banner", facing="south")
    # great hall: carpet runner to the throne, tables, lanterns
    for z in range(z0 + 3, z1):
        b.put(cx, 1, z, "{color}_carpet")
    b.put(cx, 1, z0 + 2, wood + "_stairs", facing="south", **ST)
    b.put(cx - 1, 1, z0 + 2, stone)
    b.put(cx + 1, 1, z0 + 2, stone)
    b.put(cx, 3, z0 + 1, "{color}_wall_banner", facing="south")
    for z in range(z0 + 4, z1 - 2, 2):
        for x in (cx - 4, cx + 4):
            b.put(x, 1, z, wood + "_fence")
            b.put(x, 2, z, wood + "_pressure_plate", powered="false")
    for x in (cx - 5, cx + 5):
        for z in (z0 + 4, z1 - 4):
            b.put(x, 5, z, "lantern", hanging="true", waterlogged="false")
    # ladder to the upper floor and the battlements
    for y in range(1, H):
        b.put(x1 - 1, y, z0 + 6, "ladder", facing="west", waterlogged="false")
    b.put(x1 - 1, 6, z0 + 6, "air")
    b.put(x1 - 1, H, z0 + 6, "air")
    # upper floor: beds, chests, map table
    b.put(x0 + 2, 7, z0 + 2, "{color}_bed", facing="north", part="head", occupied="false")
    b.put(x0 + 2, 7, z0 + 3, "{color}_bed", facing="north", part="foot", occupied="false")
    b.put(x0 + 4, 7, z0 + 2, "chest", facing="south", type="single", waterlogged="false")
    b.put(cx, 7, z0 + 6, "cartography_table")
    b.put(cx, 10, z0 + 6, "lantern", hanging="true", waterlogged="false")


CELLAR = (3, -2, 11, 4)    # x0, z0, x1, z1 under the square side of the main building
CELLAR_STORE = (4, -5, -1)


def cellar(style, level):
    """Cellar track: a stone cellar under the ground beside the main building, reached by a hatch and a ladder.
    1 storeroom (chests, barrels, the cellar store); 2 treasury and armory (more storage, lanterns, banners)."""
    b = B()
    stone = "cobblestone" if style == "frozen" else "stone_bricks"
    x0, z0, x1, z1 = CELLAR
    top, bottom = -2, -6
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            edge = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
            for y in range(bottom, top + 1):
                b.put(x, y, z, stone if edge or y in (bottom, top) else "air")
    # stairway from the square down to a doorway in the cellar's south wall, with a railing around it
    sx = x0
    b.put(sx, bottom + 1, z1 + 1, "air")
    b.put(sx, bottom + 2, z1 + 1, "air")
    for i in range(5):
        z = z1 + 2 + i
        step = bottom + 1 + i
        b.put(sx, step, z, "stone_brick_stairs" if style != "frozen" else "cobblestone_stairs", facing="south", **ST)
        b.put(sx, step - 1, z, stone)
        for y in range(step + 1, 0):
            b.put(sx, y, z, "air")
        for y in range(bottom, -1):
            b.put(sx - 1, y, z, stone)
            b.put(sx + 1, y, z, stone)
        b.put(sx - 1, 0, z, "spruce_fence")
        b.put(sx + 1, 0, z, "spruce_fence")
    b.put(sx, 0, z1 + 1, "spruce_fence")
    b.put(sx - 1, 1, z1 + 6, "lantern", hanging="false", waterlogged="false")
    for x in range(x0, x1):
        b.put(x, bottom + 1, z0, "barrel", facing="up", open="false")
    b.put(CELLAR_STORE[0], CELLAR_STORE[1], CELLAR_STORE[2], "chest", facing="south", type="single", waterlogged="false")
    for x in (x0 + 2, x1 - 2):
        b.put(x, top - 1, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    if level >= 2:
        for z in range(z0 + 1, z1):
            b.put(x0, bottom + 1, z, "chest", facing="east", type="single", waterlogged="false")
        for x in range(x0 + 2, x1 - 1):
            b.put(x, bottom + 1, z1, "barrel", facing="up", open="false")
        b.put((x0 + x1) // 2, bottom + 1, (z0 + z1) // 2, "{color}_carpet")
        b.put((x0 + x1) // 2, bottom + 3, z0 - 1 + 1, "{color}_wall_banner", facing="south")
    return b.b


BARRACKS = (11, 33, 21, 37)  # door north
BARRACKS_STORE = (12, 0, 36)


def barracks(style, level):
    """Barracks add-on by the gate: stone hall with bunks and weapon racks, a training yard with dummies;
    level 2 adds a second floor of bunks and an armory. The guards (iron golems) are spawned by the mod."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = BARRACKS
    stone_hut(b, x0, z0, x1, z1, 4, m["stone"], m["corner"], "spruce_planks", "n", m["roof"], m["slab"])
    for x in range(x0 + 2, x1 - 1, 3):
        b.put(x, 1, z1 - 1, "white_bed", facing="south", part="head", occupied="false")
        b.put(x, 1, z1 - 2, "white_bed", facing="south", part="foot", occupied="false")
    b.put(*BARRACKS_STORE, "barrel", facing="up", open="false")
    for x in (x0 + 1, x1 - 1):
        b.put(x, 1, z0 + 1, "grindstone", face="floor", facing="north")
    b.put((x0 + x1) // 2, 3, (z0 + z1) // 2, "lantern", hanging="true", waterlogged="false")
    b.put(x1 - 1, 1, z0 + 2, "smithing_table")
    if level >= 2:
        b.put(x0 + 1, 1, z0 + 2, "chest", facing="east", type="single", waterlogged="false")
        b.put(x0 + 1, 1, z0 + 3, "chest", facing="east", type="single", waterlogged="false")
        for x in (x0 + 3, x1 - 3):
            b.put(x, 4, z0 - 1, "{color}_wall_banner", facing="north")
    return b.b


GUILD = (-21, -17, -11, -9)  # door south
GUILD_STORE = (-19, 0, -16)


def guild_hall(style, level):
    """Guild hall of the three allies. 1 swordfighter: hall with a training yard of dummies;
    2 archer: shooting range with targets; 3 mage: a small tower room with a brewing stand and enchanting books."""
    m = mats(style)
    b = B()
    x0, z0, x1, z1 = GUILD
    stone_hut(b, x0, z0, x1, z1, 4, m["stone"], m["corner"], "spruce_planks", "s", m["roof"], m["slab"])
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
    for block in gate(style, 4).items():
        b.b[block[0]] = block[1]
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
    stone_hut(b, x0, z0, x1, z1, 5, m["stone"], m["corner"], "polished_andesite", "s", m["roof"], m["slab"])
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


# ---------------- the other vanilla systems: one building each, with a worker (spawned by the mod) ----------------
# rect = (x0, z0, x1, z1), door side 'n' or 's'; store = barrel position; worker stands at the centre
SHOPS = {
    "tavern": ((22, -24, 34, -14), "s"),
    "market": ((38, -24, 52, -16), "s"),
    "cartographer": ((56, -22, 64, -14), "s"),
    "music": ((-42, -28, -32, -20), "s"),
    "workshop": ((-46, 30, -38, 38), "n"),
    "armorer": ((-24, 38, -16, 44), "n"),
    "tannery": ((26, 38, 34, 44), "n"),
    "archaeology": ((58, 24, 68, 32), "n"),
    "mine": ((60, 2, 68, 10), "s"),
    "nether": ((-47, 4, -39, 12), "s"),
}


def shop_store(name):
    (x0, z0, x1, z1), door = SHOPS[name]
    return (x0 + 1, 0, z0 + 1 if door == "s" else z1 - 1)


def shop(style, name, level):
    """A small building in the village style with the furniture of its trade; level 2 adds more of it."""
    m = mats(style)
    b = B()
    (x0, z0, x1, z1), door = SHOPS[name]
    h = 5 if name in ("tavern", "market", "music") else 4
    if name == "nether":
        stone_hut(b, x0, z0, x1, z1, h, "nether_bricks", "red_nether_bricks", "blackstone", door, "nether_brick_stairs", "nether_brick_slab")
    else:
        stone_hut(b, x0, z0, x1, z1, h, m["stone"] if name in ("mine", "armorer", "workshop") else m["wall"], m["corner"], "spruce_planks", door,
                  m["roof"], m["slab"])
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
        for y in range(-1, -21, -1):
            b.put(sx, y, sz, "ladder", facing="east", waterlogged="false")  # hangs on the rock to the west
        b.put(sx, 0, sz, "spruce_trapdoor", facing="north", half="top", open="true", powered="false", waterlogged="false")
        for x in range(sx, sx + 7):
            for y in (-20, -19):
                b.put(x, y, sz, "air")
            if (x - sx) % 3 == 0:
                for y in (-20, -19):
                    b.put(x, y, sz + 1, "spruce_fence")
                b.put(x, -18, sz, "spruce_planks")
                b.put(x, -19, sz - 1, "wall_torch", facing="south")
        b.put(sx + 6, -20, sz, "chest", facing="west", type="single", waterlogged="false")
    if level >= 2:
        b.put(x0 + 1, 1, front, "{color}_banner", rotation="0")
        b.put(x1 - 1, h - 1, front, "lantern", hanging="true", waterlogged="false")
        if name == "tavern":
            b.put(x0 + 1, 1, back, "{color}_bed", facing="north" if door == "s" else "south", part="head", occupied="false")
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
    for y in range(base, base + height):
        b.put(cx + 2, y, cz, "ladder", facing="west", waterlogged="false")
    b.put(cx + 2, base + height, cz, "air")
    for x in range(cx - 1, cx + 2):
        for z in range(cz - 1, cz + 2):
            b.put(x, base + height + 1, z, "iron_block")
    b.put(cx, base + height + 2, cz, "beacon")
    for x, z, f in ((cx, cz + 4, "south"), (cx, cz - 4, "north"), (cx + 4, cz, "east"), (cx - 4, cz, "west")):
        b.put(x, base + height - 3, z, "{color}_wall_banner", facing=f)


LAIR = (78, -14, 100, 8)  # outside the east wall


def lair(style, level):
    """Dragon lair outside the east wall: a crater of end stone ringed by obsidian pillars with end rods,
    a nest in the middle; level 2 adds purpur arches, chorus plants and the dragon egg pedestal (the egg is
    placed by the mod once the lord has it)."""
    b = B()
    x0, z0, x1, z1 = LAIR
    cx, cz = (x0 + x1) // 2, (z0 + z1) // 2
    r = (x1 - x0) // 2
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            d = ((x - cx) ** 2 + (z - cz) ** 2) ** 0.5
            if d <= r:
                b.put(x, -1, z, "end_stone" if d > 3 else "end_stone_bricks")
                if d <= 3:
                    b.put(x, -2, z, "obsidian")
    for i in range(8):
        import math
        a = math.pi * 2 * i / 8
        px, pz = cx + round(math.cos(a) * (r - 2)), cz + round(math.sin(a) * (r - 2))
        for y in range(0, 6 + (i % 3)):
            b.put(px, y, pz, "obsidian" if y % 4 else "crying_obsidian")
        b.put(px, 6 + (i % 3), pz, "end_rod", facing="up")
    b.put(cx, 0, cz, "end_stone_brick_wall", up="true", north="none", south="none", east="none", west="none", waterlogged="false")
    if level >= 2:
        for dx, dz in ((-6, 0), (6, 0), (0, -6), (0, 6)):
            for y in range(0, 4):
                b.put(cx + dx, y, cz + dz, "purpur_pillar", axis="y")
            b.put(cx + dx, 4, cz + dz, "purpur_slab", type="bottom", waterlogged="false")
        for dx, dz in ((-3, 5), (4, -4), (-5, -3)):
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


def to_json(blocks, seconds, storage=None, animals=None):
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
        row = [x, y, z, names[key]]
        if seg is not None or anc is not None:
            row.append(seg if seg is not None else -1)
        if anc is not None:
            row += [anc[0] + shift[0], anc[1] + shift[1]]
        out.append(row)
    j = {"size": list(size), "heart": list(heart), "seconds": seconds, "palette": palette, "blocks": out}
    if storage:
        j["storage"] = list(storage)
    if follow:
        j["follow"] = True
    if segs:
        j["segments"] = [{"name": n, "any": [[t, l] for t, l in rules]} for n, rules in SEGMENTS]
    if animals:
        dx, dz = heart[0] - HEART[0], heart[2] - HEART[2]
        j["animals"] = [[a[0], a[1] + dx, a[2], a[3] + dz] for a in animals]
    return j


SECONDS = {1: 60, 2: 90, 3: 150}

if __name__ == "__main__":
    jobs = []  # (style, id, blocks, seconds, storage, animals)
    STORE = (FARM[0] - 2, 0, FARM[1] + FARM[3] // 2 - 1)
    for style in STYLES:
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
            for level in (1, 2):
                jobs.append((style, f"{name}_{level}", shop(style, name, level), 160 + 40 * level, shop_store(name), None))
        for level in (1, 2):
            DOCK_JOBS.append((style, level))
        for level in (1, 2):
            jobs.append((style, f"apiary_{level}", apiary(style, level), 150, APIARY_STORE,
                         [["bee", 52, 1, 5], ["bee", 50, 1, 7], ["bee", 54, 1, 6]] if level == 1 else [["bee", 51, 1, 6], ["bee", 53, 1, 7]]))
            jobs.append((style, f"trophy_{level}", trophy_hall(style, level), 200, None, None))
        for level in (1, 2, 3):
            jobs.append((style, f"kennel_{level}", kennel(style, level), 120, None, None))
        jobs.append((style, "cellar_3", secret_vault(style, 3), 200, CELLAR_STORE, None))
        for level in (1, 2):
            jobs.append((style, f"barracks_{level}", barracks(style, level), 200, BARRACKS_STORE, None))
            jobs.append((style, f"library_{level}", library(style, level), 180, LIB_STORE, None))
            jobs.append((style, f"brewery_{level}", brewery(style, level), 180, BREW_STORE, None))
            jobs.append((style, f"stables_{level}", stables(style, level), 180, STABLE_STORE,
                         [["horse", 44, 0, 32], ["horse", 48, 0, 31]] if level == 1 else [["donkey", 50, 0, 32]]))
        for level in (1, 2, 3, 4):
            jobs.append((style, f"road_{level}", roads(style, level), 90 + 30 * level, None, None))
        for level in (1, 2, 3):
            jobs.append((style, f"lamp_{level}", lamps(style, level), 60 + 30 * level, None, None))
        for level, secs in ((1, 120), (2, 150), (3, 180)):
            jobs.append((style, f"farm_{level}", farm(style, level), secs, STORE,
                         [["cow", 39, 0, 4], ["cow", 40, 0, 6], ["sheep", 38, 0, 7], ["sheep", 41, 0, 3], ["chicken", 37, 0, 5],
                          ["chicken", 38, 0, 3]] if level == 2 else None))
        for level, secs in ((1, 150), (2, 150), (3, 180)):
            jobs.append((style, f"smith_{level}", smithy(style, level), secs, SMITH_STORE, None))
            jobs.append((style, f"wood_{level}", woodcutter(style, level), secs, WOOD_STORE, None))
    def apron(name):
        return 0 if name.startswith(("road_", "lamp_", "wall_1", "wall_2")) else 4 if name.startswith("wall_") else 8
    jobs = [j if len(j) == 7 else j + (apron(j[1]),) for j in jobs]
    for style, name, blocks, seconds, storage, animals, apron in jobs:
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, f"{name}.json"), "w") as f:
            j = to_json(blocks, seconds, storage, animals)
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
