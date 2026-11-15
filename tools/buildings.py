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
}


class B:
    def __init__(self):
        self.b = {}

    def put(self, x, y, z, name, **p):
        self.b[(x, y, z)] = (name if ":" in name or name.startswith("{") else "minecraft:" + name,
                             {k: str(v).lower() for k, v in p.items()})


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


def house(b, style, x0, z0, facing_north=False):
    t = B()
    (house_norse if style == "frozen" else house_temperate)(t, x0, z0)
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
    if level >= 5:
        town_hall(b, style)
    else:
        (house_norse if style == "frozen" else house_temperate)(b)
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
    farm_plot_markers(b, s)
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
    snow(b, s, [(x, z) for x in range(-10, 36) for z in range(16 if level == 1 else 32) if (x * 73856093 ^ z * 19349663) % 11 == 0
                and not (4 <= x <= 10 and z <= 9) and (x, z) not in road])
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
    b.put(x1 - 1, 0, z0 + 1, "hay_block", axis="x")
    b.put(x1 - 2, 0, z0 + 1, "hay_block", axis="x")
    b.put(x1 - 1, 1, z0 + 1, "hay_block", axis="z")
    b.put(x0 + 2, 0, z1 - 1, "water_cauldron", level="3")
    b.put(x0 + 3, 0, z1 - 1, "water_cauldron", level="3")
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
    b.put(x0 - 2, 0, zc + 1, "composter", level="0")
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
    log = "stripped_spruce_log" if style == "frozen" else "stripped_oak_log"
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
            b.put(x, y, z, "deepslate_tile_stairs" if style == "frozen" else "brick_stairs", facing="north", **ST)
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
    log = "spruce_log" if style == "frozen" else "oak_log"
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


WALL = (-39, 52, -9, 40)  # x0, x1, z0, z1 of the palisade ring (village plot)


def walls(style, level):
    """Walls track. 1: log palisade with sharpened tops and a gate on the main road.
    2: inner walkway, corner watchtowers and gate towers with lanterns."""
    if level >= 3:
        return stone_walls(style, level)
    s = STYLES[style]
    b = B()
    w = s["wood"]
    log = "spruce_log" if style == "frozen" else "oak_log"
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
    # gate: fence gates under a log lintel
    for x in range(gx - 1, gx + 2):
        b.put(x, 0, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
        b.put(x, 1, z1, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
        b.put(x, 3, z1, "stripped_" + log, axis="x")
    for x in (gx - 2, gx + 2):
        for y in range(0, 5):
            b.put(x, y, z1, "stripped_" + log, axis="y")
    b.put(gx, 4, z1 + 1, "{color}_wall_banner", facing="south")
    if level >= 2:
        # walkway on the inside at y=2, reached from the towers
        inner = {(x, z0 + 1) for x in range(x0 + 1, x1)} | {(x, z1 - 1) for x in range(x0 + 1, x1) if abs(x - gx) > 2}
        inner |= {(x0 + 1, z) for z in range(z0 + 1, z1)} | {(x1 - 1, z) for z in range(z0 + 1, z1)}
        for x, z in inner:
            b.put(x, 2, z, w + "_slab", type="top", waterlogged="false")
        for cx, cz in ((x0, z0), (x1, z0), (x0, z1), (x1, z1), (gx - 3, z1), (gx + 3, z1)):
            tx = max(x0, min(x1 - 2, cx - 1))
            tz = max(z0, min(z1 - 2, cz - 1))
            for x in range(tx, tx + 3):
                for z in range(tz, tz + 3):
                    corner = x in (tx, tx + 2) and z in (tz, tz + 2)
                    for y in range(0, 6):
                        if corner:
                            b.put(x, y, z, log, axis="y")
                    b.put(x, 4, z, w + "_planks")
                    if not corner:
                        b.put(x, 5, z, w + "_fence")
                    b.put(x, 6, z, w + "_slab", type="bottom", waterlogged="false")
            for y in range(0, 4):
                b.put(tx + 1, y, tz + 2, log, axis="y")  # the ladder hangs on this
                b.put(tx + 1, y, tz + 1, "ladder", facing="north", waterlogged="false")
            b.put(tx + 1, 4, tz + 1, "air")
    return b.b


def road_cells():
    """The road network of the full village (fixed plots: roads lead to every plot, built or not).
    Returns {(x, z): kind} with kind 'core' (centre line) or 'side'."""
    cells = {}

    def strip(xs, zs, core_x=None, core_z=None):
        for x in xs:
            for z in zs:
                core = (core_x is not None and x == core_x) or (core_z is not None and z == core_z)
                if cells.get((x, z)) != "core":
                    cells[(x, z)] = "core" if core else "side"

    strip(range(0, 15), range(9, 17), core_x=7)                                  # square
    strip(range(6, 9), range(16, WALL[3] + 1), core_x=7)                         # main road to the gate
    strip(range(-14, 0), range(12, 14), core_z=12)                               # west road
    strip(range(-13, -10), range(9, 12), core_x=-12)                             # smithy
    strip(range(15, 43), range(12, 14), core_z=12)                               # east road
    strip([26], [11], core_x=26)                                                 # field gate
    strip([39], [10, 11], core_x=39)                                             # pasture gate
    strip([38], [14], core_x=38)                                                 # smokehouse
    strip([20], range(5, 12), core_x=20)                                         # farm barrel
    strip(range(-26, 35), range(18, 20), core_z=18)                              # street of houses
    for x in (-5, 19, 31, -26):                                                  # doors, woodcutter
        strip([x], [20, 21], core_x=x)
    for x, z in ((1, 12), (2, 12), (3, 12), (1, 13), (2, 13), (3, 13), (1, 14), (2, 14), (3, 14)):
        cells.pop((x, z), None)                                                  # the well
    return cells


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
    for (x, z), kind in cells.items():
        h = (x * 73856093 ^ z * 19349663) % 20
        if level == 1:
            if kind != "core" or h >= 13:
                continue
            b.put(x, -1, z, m[0] if h < 9 else m[1])
        elif level == 2:
            b.put(x, -1, z, m[1] if h < 4 else m[0])
        elif level == 3:
            b.put(x, -1, z, m[1] if h < 5 else m[2] if h < 8 else m[0])
        else:
            border = any((x + dx, z + dz) not in cells for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)))
            b.put(x, -1, z, m[1] if border else m[2] if h < 3 else m[0])
    return b.b


# lamp posts: x, z and the direction their arm points (towards the road)
LAMP_SPOTS = [(5, 22, 1, 0), (9, 22, -1, 0), (5, 30, 1, 0), (9, 30, -1, 0), (5, 35, 1, 0), (9, 35, -1, 0),
              (-6, 11, 0, 1), (20, 11, 0, 1), (32, 11, 0, 1),
              (-10, 17, 0, 1), (2, 17, 0, 1), (13, 17, 0, 1), (25, 17, 0, 1), (0, 9, 1, 0), (14, 9, -1, 0)]


def lamps(style, level):
    """Lamps track: 1 torches on posts, 2 lanterns on posts, 3 stone-footed posts with a hanging lantern on an arm."""
    s = STYLES[style]
    b = B()
    fence = s["wood"] + "_fence"
    for x, z, dx, dz in LAMP_SPOTS:
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
    # gate: open arch with iron bars as a portcullis drawn up, banner above
    for x in range(gx - 1, gx + 2):
        b.put(x, 3, z1, "iron_bars", north="false", south="false", east="true", west="true", waterlogged="false")
    b.put(gx, 4, z1 + 1, "{color}_wall_banner", facing="south")
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
    frame = "stripped_spruce_log" if frozen else "stripped_oak_log"
    infill = "spruce_planks" if frozen else "calcite"
    roof = "spruce_stairs" if frozen else "brick_stairs"
    ridge = "spruce_slab" if frozen else "brick_slab"
    stone = "cobblestone" if frozen else "stone_bricks"
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


def normalize(blocks, extra=()):
    """Shifts a plot so no coordinate is negative (buildings may reach west/north of the base frame).
    Returns blocks, heart, size and the shifted extra points."""
    xs = [p[0] for p in blocks] + [0]
    zs = [p[2] for p in blocks] + [0]
    dx, dz = -min(xs), -min(zs)
    nb = {(x + dx, y, z + dz): v for (x, y, z), v in blocks.items()}
    size = (max(xs) + dx + 1, max(max(p[1] for p in blocks) + 1, 8), max(zs) + dz + 1)
    heart = (HEART[0] + dx, HEART[1], HEART[2] + dz)
    return nb, heart, size, [None if e is None else (e[0] + dx, e[1], e[2] + dz) for e in extra]


def to_json(blocks, seconds, storage=None, animals=None):
    blocks, heart, size, (storage,) = normalize(blocks, [storage])
    names, palette, out = {}, [], []
    for (x, y, z), (name, props) in sorted(blocks.items(), key=lambda kv: (kv[0][1], kv[0][2], kv[0][0])):
        key = (name, tuple(sorted(props.items())))
        if key not in names:
            names[key] = len(palette)
            e = {"Name": name}
            if props:
                e["Properties"] = props
            palette.append(e)
        out.append([x, y, z, names[key]])
    j = {"size": list(size), "heart": list(heart), "seconds": seconds, "palette": palette, "blocks": out}
    if storage:
        j["storage"] = list(storage)
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
        for level, secs in ((1, 300), (2, 300), (3, 420), (4, 480)):
            jobs.append((style, f"wall_{level}", walls(style, level), secs, None, None, 24))
        for level in (1, 2, 3, 4):
            jobs.append((style, f"road_{level}", roads(style, level), 90 + 30 * level, None, None))
        for level in (1, 2, 3):
            jobs.append((style, f"lamp_{level}", lamps(style, level), 60 + 30 * level, None, None))
        for level, secs in ((1, 120), (2, 150), (3, 180)):
            jobs.append((style, f"farm_{level}", farm(style, level), secs, STORE,
                         [["cow", 39, 0, 4], ["cow", 40, 0, 6], ["sheep", 38, 0, 7], ["sheep", 41, 0, 3], ["chicken", 37, 0, 5],
                          ["chicken", 38, 0, 3]] if level == 2 else None))
        for level, secs in ((1, 150), (2, 150)):
            jobs.append((style, f"smith_{level}", smithy(style, level), secs, SMITH_STORE, None))
            jobs.append((style, f"wood_{level}", woodcutter(style, level), secs, WOOD_STORE, None))
    jobs = [j if len(j) == 7 else j + (0,) for j in jobs]
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
    print(len(jobs), "templates")
