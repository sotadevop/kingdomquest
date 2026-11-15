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


FARM = (13, 1, 9, 9)  # x0, z0, width, depth of the field


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


TAX_STORE = (11, 0, 11)
SMITH_STORE = (-9, 0, 2)
WOOD_STORE = (-12, 0, 16)


def street_lamp(b, s, x, z):
    b.put(x, 0, z, s["pole"])
    b.put(x, 1, z, s["pole"])
    b.put(x, 2, z, "lantern", hanging="false", waterlogged="false")


def hamlet(style, level):
    s = STYLES[style]
    b = B()
    w = s["wood"]
    hx, hy, hz = HEART
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
    path = {(hx, z) for z in range(9, 15)} | {(hx + dx, hz + dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)}
    path |= {(x, 10) for x in range(3, 13)}
    if level >= 2:
        # second house south-west, door facing the square; tax barrel by the fire
        house(b, style, -4, 16, facing_north=True)
        path |= {(x, 10) for x in range(-1, 3)} | {(-1, z) for z in range(10, 16)}
        b.put(*TAX_STORE, "barrel", facing="up", open="false")
        street_lamp(b, s, 0, 11)
    if level >= 3:
        house(b, style, 10, 16, facing_north=True)
        path |= {(13, z) for z in range(11, 16)}
        street_lamp(b, s, 12, 12)
        # market stall
        for x, z in ((4, 16), (7, 16), (4, 19), (7, 19)):
            b.put(x, 0, z - 1, s["pole"]); b.put(x, 1, z - 1, s["pole"])
        for x in range(4, 8):
            for z in range(15, 19):
                b.put(x, 2, z, "{color}_wool" if z == 18 else "white_wool")
        b.put(5, 0, 16, "barrel", facing="up", open="false")
        b.put(6, 0, 16, "barrel", facing="up", open="true")
    ground(b, s, path)
    snow(b, s, [(x, z) for x in range(-5, 23) for z in range(15 if level == 1 else 26) if (x * 73856093 ^ z * 19349663) % 11 == 0
                and not (4 <= x <= 10 and z <= 9)])
    return b.b


def pasture(b, s):
    """Farm level 2: fenced pasture east of the field with hay, a water trough and a lean-to shelter."""
    w = s["wood"]
    x0, x1, z0, z1 = 23, 31, 0, 9
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            if edge:
                if (x, z) == (x0, 5):
                    b.put(x, 0, z, w + "_fence_gate", facing="west", in_wall="false", open="false", powered="false")
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
    x0, x1, z0, z1 = 25, 29, 11, 14
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
    # fence with a gate facing the hamlet (west side)
    for x in range(x0 - 1, x0 + w + 1):
        for z in (z0 - 1, z0 + d):
            b.put(x, 0, z, s["wood"] + "_fence")
    for z in range(z0, z0 + d):
        for x in (x0 - 1, x0 + w):
            if (x, z) == (x0 - 1, zc):
                b.put(x, 0, z, s["wood"] + "_fence_gate", facing="west", in_wall="false", open="false", powered="false")
            else:
                b.put(x, 0, z, s["wood"] + "_fence")
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
    x0, x1, z0, z1 = -10, -2, 1, 8
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
    ground(b, s, {(x, z) for x in range(x0 + 2, x1) for z in (9, 10)})
    return b.b


def woodcutter(style, level):
    """Woodcutter add-on, west of the second house: log store, chopping block, shed; level 2 adds a sawmill."""
    s = STYLES[style]
    b = B()
    w = s["wood"]
    log = "spruce_log" if style == "frozen" else "oak_log"
    x0, x1, z0, z1 = -14, -7, 15, 23
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
    ground(b, s, {(x, 15) for x in range(x0 + 6, -4)})
    return b.b


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
        for level, secs in ((1, 120), (2, 150), (3, 180)):
            jobs.append((style, f"farm_{level}", farm(style, level), secs, STORE,
                         [["cow", 27, 0, 4], ["cow", 28, 0, 6], ["sheep", 26, 0, 7], ["sheep", 29, 0, 3], ["chicken", 25, 0, 5],
                          ["chicken", 26, 0, 3]] if level == 2 else None))
        for level, secs in ((1, 150), (2, 150)):
            jobs.append((style, f"smith_{level}", smithy(style, level), secs, SMITH_STORE, None))
            jobs.append((style, f"wood_{level}", woodcutter(style, level), secs, WOOD_STORE, None))
    for style, name, blocks, seconds, storage, animals in jobs:
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        with open(os.path.join(OUT, style, f"{name}.json"), "w") as f:
            json.dump(to_json(blocks, seconds, storage, animals), f, separators=(",", ":"))
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
