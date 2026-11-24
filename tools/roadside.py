"""Buildings along the country roads between kingdoms and villages (design 19.6, 19.7, 19.11), written by
tools/buildings.py next to the settlement's own templates:

  waystation_1..3   the halfway stop, its level following the kingdom's road level: 1 a camp with a fire, benches and
                    a signpost; 2 a small inn with a bed, a stable with horses, a well and a horse trough; 3 a guarded
                    station: a wall round the yard, a watchtower with a signal fire and an arch over the road
  shrine            a wayside shrine (a German Bildstock and its cousins) at crossroads, road level 2
  bridge_tower      a gate tower over the end of a big river bridge (the Charles Bridge), road level 3
  watchtower        a lookout with a signal fire on a hill along a long road, road level 3

Frame of every roadside template: the road runs along z through x = -1..1, its surface at y = -1; y = 0 is the first
layer above the road. The mod places the template's origin on the road's middle cell and turns it with the road.
Each region builds in its own way: German stone and timber (temperate), carved Norse wood (frozen), Moorish
sandstone with horseshoe arches and zellige (arid), Scottish rubble stone and slate (highland), Southeast Asian
jungle wood and bamboo with flared roofs (wild).
"""
import buildings as bld
from buildings import B, ST, hall, small_roof, pick, wall_banner, PANE_X, PANE_Z, ZELLIGE

# the road's straight stretch through a waystation (the mod lays the road straight from -LEN to LEN)
STRAIGHT = 14

RS = {
    "temperate": dict(wood="spruce", dark="dark_oak", log="spruce_log", strip="stripped_spruce_log", post="stripped_dark_oak_log",
                      stone=("stone_bricks", "stone_bricks", "cobblestone", "mossy_stone_bricks", "andesite"), base="mossy_cobblestone",
                      slab="stone_brick_slab", stairs="stone_brick_stairs", wall="cobblestone_wall", trim="polished_andesite",
                      key="chiseled_stone_bricks", roof=("bricks", "brick_stairs", "brick_slab"), sign="spruce"),
    "frozen": dict(wood="spruce", dark="dark_oak", log="spruce_log", strip="stripped_spruce_log", post="dark_oak_log",
                   stone=("cobblestone", "cobblestone", "mossy_cobblestone", "andesite", "cobbled_deepslate"), base="cobbled_deepslate",
                   slab="cobblestone_slab", stairs="cobblestone_stairs", wall="cobblestone_wall", trim="polished_deepslate",
                   key="chiseled_deepslate", roof=("spruce_planks", "spruce_stairs", "dark_oak_slab"), sign="spruce"),
    "arid": dict(wood="acacia", dark="acacia", log="acacia_log", strip="stripped_acacia_log", post="stripped_acacia_log",
                 stone=("sandstone", "cut_sandstone", "smooth_sandstone", "sandstone"), base="cut_sandstone",
                 slab="smooth_sandstone_slab", stairs="smooth_sandstone_stairs", wall="sandstone_wall", trim="cut_sandstone",
                 key="chiseled_sandstone", roof=("smooth_sandstone", "smooth_sandstone_stairs", "smooth_sandstone_slab"), sign="acacia"),
    "highland": dict(wood="spruce", dark="dark_oak", log="spruce_log", strip="stripped_spruce_log", post="dark_oak_log",
                     stone=("cobblestone", "andesite", "stone", "mossy_cobblestone", "stone_bricks", "tuff"), base="mossy_cobblestone",
                     slab="stone_brick_slab", stairs="cobblestone_stairs", wall="cobblestone_wall", trim="polished_andesite",
                     key="chiseled_stone_bricks", roof=("deepslate_tiles", "deepslate_tile_stairs", "deepslate_tile_slab"), sign="dark_oak"),
    "wild": dict(wood="jungle", dark="mangrove", log="jungle_log", strip="stripped_jungle_log", post="jungle_log",
                 stone=("mossy_cobblestone", "cobblestone", "mossy_stone_bricks", "mossy_cobblestone"), base="mossy_cobblestone",
                 slab="mossy_cobblestone_slab", stairs="mossy_cobblestone_stairs", wall="mossy_cobblestone_wall", trim="mossy_stone_bricks",
                 key="chiseled_stone_bricks", roof=("spruce_planks", "spruce_stairs", "spruce_slab"), sign="bamboo"),
}
WALL = dict(up="true", north="none", south="none", east="none", west="none", waterlogged="false")
TOP = dict(half="top", shape="straight", waterlogged="false")
OPPOSITE = {"north": "south", "south": "north", "east": "west", "west": "east"}


def stone(b, style, x, y, z):
    b.put(x, y, z, pick(RS[style]["stone"], x, y, z))


def lantern(b, x, y, z, hanging=False):
    b.put(x, y, z, "lantern", hanging=str(hanging).lower(), waterlogged="false")


def stairs(b, name, x, y, z, facing, top=False):
    b.put(x, y, z, name, facing=facing, **(TOP if top else ST))


def log(b, name, x, y, z, axis="y"):
    b.put(x, y, z, name, axis=axis)


def signpost(b, style, x, z):
    """A signpost by the road: a post, a carved head with a sign on each side along the road (the mod writes the
    places and distances on them) and a lantern on top."""
    r = RS[style]
    b.put(x, 0, z, r["wood"] + "_fence")
    b.put(x, 1, z, r["wood"] + "_fence")
    log(b, r["strip"], x, 2, z)
    for dz, f in ((-1, "north"), (1, "south")):
        b.put(x, 2, z + dz, r["sign"] + "_wall_sign", facing=f, waterlogged="false")
    lantern(b, x, 3, z)


def ground_patch(b, style, cells):
    """Trodden ground: the region's path blocks mixed with gravel and coarse dirt, ragged at the edge."""
    path = bld.STYLES[style]["path"]
    for (x, z) in cells:
        h = bld.hsh(x, 7, z) % 7
        b.put(x, -1, z, (path[0], path[1], "coarse_dirt", path[0], "gravel", path[1], path[0])[h])


def ragged(x0, z0, x1, z1):
    """The cells of a rectangle with its corners and a few edge cells left out, so the trodden ground has no clean edge."""
    out = []
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = x in (x0, x1) or z in (z0, z1)
            corner = x in (x0, x1) and z in (z0, z1)
            if corner or edge and bld.hsh(x, 3, z) % 3 == 0:
                continue
            out.append((x, z))
    return out


# ---------------------------------------------------------------- waystation

def camp(b, style, level=1):
    """Level 1: a fire with benches round it under a small shelter, firewood, a hitching rail and the signpost."""
    r = RS[style]
    w = r["wood"]
    ground_patch(b, style, ragged(4, -4, 15, 2) + [(x, z) for x in (2, 3) for z in (-1, 0, 1)])
    fx, fz = 11, -1
    b.put(fx, 0, fz, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
    if style == "arid":   # cushions and a low table on carpets, under a striped awning
        for x in range(fx - 1, fx + 2):
            b.put(x, 0, fz - 2, "red_carpet" if x % 2 else "orange_carpet")
            b.put(x, 0, fz + 2, "orange_carpet" if x % 2 else "red_carpet")
        b.put(fx + 2, 0, fz, "acacia_trapdoor", facing="west", half="top", open="false", powered="false", waterlogged="false")
        b.put(fx + 2, 1, fz, "decorated_pot", facing="west", cracked="false", waterlogged="false")
    elif style == "frozen":   # log benches with furs
        for x in range(fx - 1, fx + 2):
            log(b, "spruce_log", x, 0, fz - 2, "x")
            b.put(x, 1, fz - 2, "white_carpet" if x != fx else "light_gray_carpet")
            log(b, "spruce_log", x, 0, fz + 2, "x")
    elif style == "highland":   # benches of dressed stone
        for x in range(fx - 1, fx + 2):
            stairs(b, "cobblestone_stairs", x, 0, fz - 2, "north")
            stairs(b, "cobblestone_stairs", x, 0, fz + 2, "south")
    elif style == "wild":   # bamboo mats and low stools
        for x in range(fx - 1, fx + 2):
            b.put(x, 0, fz - 2, "bamboo_mosaic_slab", type="bottom", waterlogged="false")
            b.put(x, 0, fz + 2, "bamboo_mosaic_slab", type="bottom", waterlogged="false")
    else:
        for x in range(fx - 1, fx + 2):
            stairs(b, w + "_stairs", x, 0, fz - 2, "north")
            stairs(b, w + "_stairs", x, 0, fz + 2, "south")
    for dx in (-1, 1):   # stones round the fire
        b.put(fx + dx, -1, fz, "cobblestone" if style != "arid" else "sandstone")
    # the shelter over the north bench: a windbreak behind it and a little roof (from level 2 the stable shelters)
    for x in (fx - 2, fx + 2) if level == 1 else ():
        for y in range(0, 5 if style != "arid" else 3):
            log(b, r["strip"], x, y, fz - 3)
    for x in range(fx - 1, fx + 2) if level == 1 else ():
        for y in range(0, 5 if style != "arid" else 2):
            if style == "arid":
                b.put(x, y, fz - 3, "acacia_fence")
            elif style == "highland":
                stone(b, style, x, y, fz - 3)
            elif style == "wild":
                b.put(x, y, fz - 3, "bamboo_block", axis="y")
            else:
                b.put(x, y, fz - 3, w + "_planks")
    if level > 1:
        pass
    elif style == "arid":
        for x in range(fx - 2, fx + 3):
            for z in range(fz - 3, fz):
                b.put(x, 3, z, "white_wool" if (x + z) % 2 else "orange_wool")
        for x in (fx - 2, fx + 2):
            b.put(x, 0, fz - 1, "acacia_fence")
            b.put(x, 1, fz - 1, "acacia_fence")
            b.put(x, 2, fz - 1, "acacia_fence")
    else:
        small_roof(b, style, fx - 3, fx + 3, fz - 1, fz - 5, 3)
    # firewood under its own lean-to, a chopping block, a barrel of supplies
    for x in (15, 16):
        for y in (0, 1):
            log(b, r["log"], x, y, -4, "z")
        log(b, r["log"], x, 0, -3, "z")
    log(b, r["strip"], 14, 0, -2)
    b.put(15, 0, 1, "barrel", facing="up", open="false")
    b.put(16, 0, 1, "hay_block", axis="y")
    # a hitching rail by the road for the riders' horses
    for z in (-4, -3, -2):
        b.put(5, 0, z, w + "_fence")
    log(b, r["strip"], 5, 1, -4)
    log(b, r["strip"], 5, 1, -2)
    b.put(5, 1, -3, w + "_fence")
    signpost(b, style, 3, 3)
    for y in (0, 1):
        b.put(6, y, 3, w + "_fence")
    lantern(b, 6, 2, 3)


def inn(b, style):
    """Level 2: a small inn of the region with a bed (a respawn point), a table, a hearth and a counter."""
    x0, z0, x1, z1 = 9, 4, 16, 10
    hall(b, style, x0, z0, x1, z1, 4, "n", chimney=(15, z1))
    w = bld.SHELF_WOOD[style]
    b.put(x1 - 1, 1, z1 - 2, "{color}_bed", facing="south", part="head", occupied="false")
    b.put(x1 - 1, 1, z1 - 3, "{color}_bed", facing="south", part="foot", occupied="false")
    b.put(x1 - 2, 1, z1 - 1, "barrel", facing="up", open="false")
    b.put(x1 - 1, 1, z1 - 4, "chest", facing="west", type="single", waterlogged="false")
    b.put(14, 1, z1 - 1, "smoker", facing="north", lit="false")    # the hearth under the chimney
    b.put(13, 1, z1 - 1, "cauldron")
    # the table with benches
    for x in (x0 + 2, x0 + 3):
        b.put(x, 1, z0 + 3, w + "_trapdoor", facing="north", half="top", open="false", powered="false", waterlogged="false")
        stairs(b, w + "_stairs", x, 1, z0 + 2, "north")
        stairs(b, w + "_stairs", x, 1, z0 + 4, "south")
    b.put(x0 + 2, 2, z0 + 3, "candle", candles="2", lit="true", waterlogged="false")
    # the host's counter by the door
    for z in (z0 + 1, z0 + 2):
        b.put(x0 + 1, 1, z, w + "_planks" if style != "arid" else "smooth_sandstone")
        b.put(x0 + 1, 2, z, w + "_slab" if style != "arid" else "acacia_slab", type="bottom", waterlogged="false")
    b.put(x0 + 1, 3, z0 + 1, w + "_shelf", facing="east")
    b.put(x0 + 1, 1, z0 + 4, "barrel", facing="east", open="false")
    b.put(12, 4, 7, "lantern", hanging="true", waterlogged="false")
    rug = bld.RUG[style]
    for x in (12, 13):
        b.put(x, 1, z1 - 2, rug[x % 2])
    if style == "arid":
        b.put(12, 3, 7, "lantern", hanging="false", waterlogged="false") if (12, 3, 7) in b.b else None


def stable(b, style):
    """Level 2: an open stable of three stalls with hay, water and a saddle rack; two horses stand in it."""
    r = RS[style]
    w = r["wood"]
    x0, x1, zb, zf = 6, 16, -12, -8
    for x in range(x0, x1 + 1):
        for z in range(zb, zf + 1):
            b.put(x, -1, z, "coarse_dirt" if (x * 3 + z) % 4 else "podzol", **({"snowy": "false"} if (x * 3 + z) % 4 == 0 else {}))
    for x in range(x0, x1 + 1, 5):
        for z in (zb, zf):
            for y in range(0, 3):
                log(b, r["post"] if style != "arid" else "stripped_acacia_log", x, y, z)
    for x in range(x0 + 1, x1):                       # back wall
        for y in range(0, 3):
            if style == "highland" or style == "arid":
                stone(b, style, x, y, zb)
            elif style == "wild":
                b.put(x, y, zb, "bamboo_planks")
            else:
                b.put(x, y, zb, w + "_planks")
    for z in range(zb + 1, zf):
        for x in (x0, x1):
            for y in range(0, 3):
                b.put(x, y, z, w + "_planks" if style not in ("highland", "arid") else pick(r["stone"], x, y, z))
    for x in (x0 + 5,):                             # stall partitions
        for z in (zb + 1, zb + 2, zb + 3):
            b.put(x, 0, z, w + "_fence")
    for x in range(x0 + 1, x1):
        if x == x0 + 5:
            continue
        if x in (x0 + 2, x0 + 7):
            b.put(x, 0, zf, w + "_fence_gate", facing="south", in_wall="false", open="false", powered="false")
        else:
            b.put(x, 0, zf, w + "_fence")
    for x in (x0 + 1, x0 + 6):
        b.put(x, 0, zb + 1, "hay_block", axis="x")
        b.put(x + 3, 0, zb + 1, "water_cauldron", level="3")
    b.put(x1 - 1, 0, zb + 1, "barrel", facing="up", open="false")
    b.put(x1 - 1, 1, zb + 1, bld.SHELF_WOOD[style] + "_shelf", facing="south")   # the saddle rack
    b.put(x1 - 2, 0, zb + 1, "hay_block", axis="y")
    for x in (x0 + 3, x0 + 8):
        lantern(b, x, 2, zb + 2, hanging=True)
    small_roof(b, style, x0 - 1, x1 + 1, zf + 1, zb - 1, 3)
    if style == "arid":                               # a flat roof on beams in the desert
        for x in range(x0, x1 + 1):
            b.put(x, 3, zb + 2, "stripped_acacia_log", axis="x")


def well(b, style, cx, cz):
    """A well: a stone ring round the water, two posts, a little roof and a bucket chain."""
    r = RS[style]
    for x in range(cx - 1, cx + 2):
        for z in range(cz - 1, cz + 2):
            b.put(x, -2, z, "cobblestone")
            if (x, z) == (cx, cz):
                b.put(x, -1, z, "water", level="0")
                continue
            b.put(x, -1, z, "cobblestone")
            if style == "arid":
                b.put(x, 0, z, "cut_sandstone" if (x + z) % 2 else "chiseled_sandstone")
            else:
                stone(b, style, x, 0, z)
    for x in (cx - 1, cx + 1):
        for y in (1, 2):
            b.put(x, y, cz, r["wood"] + "_fence")
    b.put(cx, 3, cz, r["strip"], axis="x")
    for x in (cx - 1, cx + 1):
        log(b, r["strip"], x, 3, cz, "x")
    for z, f in ((cz - 1, "south"), (cz + 1, "north")):
        for x in range(cx - 1, cx + 2):
            stairs(b, r["roof"][1], x, 3, z, f)
    b.put(cx, 4, cz, r["roof"][2], type="bottom", waterlogged="false")
    b.put(cx, 2, cz, "iron_chain", axis="y", waterlogged="false")


def trough(b, style, x, z0, z1):
    """A horse trough: water in a frame of logs along z from z0 to z1."""
    r = RS[style]
    for z in range(z0 - 1, z1 + 2):
        for dx in (-1, 0, 1):
            b.put(x + dx, -1, z, r["wood"] + "_planks")
            if dx == 0 and z0 <= z <= z1:
                b.put(x, 0, z, "water", level="0")
            else:
                log(b, r["strip"], x + dx, 0, z, "z" if dx else "x")


def watchtower(b, style, x0, z0, door="n", top=13):
    """A lookout tower 4 x 4 with a ladder inside; on its platform the signal fire burns when the realm is attacked.
    door 'n' or 'w': the side with the door. Returns the fire's position."""
    r = RS[style]
    w = r["wood"]
    x1, z1 = x0 + 3, z0 + 3
    lx, lz, lf = (x0 + 1, z1 - 1, "north") if door == "n" else (x1 - 1, z0 + 1, "west")
    dx_, dz_, df = (x0 + 2, z0, "north") if door == "n" else (x0, z0 + 2, "west")
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            b.put(x, -1, z, r["base"] if style != "arid" else "cut_sandstone")
            edge = x in (x0, x1) or z in (z0, z1)
            corner = x in (x0, x1) and z in (z0, z1)
            for y in range(0, top):
                if not edge:
                    if y == 0:
                        b.put(x, y, z, w + "_planks" if style != "arid" else "cut_sandstone")
                    continue
                if style == "wild":                     # open frame of jungle posts with bamboo walls below
                    if corner:
                        log(b, "jungle_log", x, y, z)
                    elif y <= 2 or y in (top - 1,):
                        b.put(x, y, z, "bamboo_block", axis="y") if y <= 2 else b.put(x, y, z, "stripped_mangrove_log", axis="x" if z in (z0, z1) else "z")
                    elif y % 3 == 0:
                        b.put(x, y, z, "bamboo_fence")
                    continue
                if style == "frozen":
                    ax = "x" if z in (z0, z1) else "z"
                    if corner:
                        ax = "x" if y % 2 else "z"
                    log(b, "spruce_log" if y % 2 else "stripped_spruce_log", x, y, z, ax)
                elif style == "temperate" and y >= 5:      # half-timbered upper storey
                    if corner or y in (5, 9, top - 1):
                        log(b, r["post"], x, y, z, "y" if corner else ("x" if z in (z0, z1) else "z"))
                    else:
                        b.put(x, y, z, "calcite")
                elif style == "arid":
                    b.put(x, y, z, "smooth_sandstone" if not corner else "cut_sandstone")
                    if y in (6, 7) and not corner:
                        b.put(x, y, z, pick(ZELLIGE, x, y, z), facing=pick(("north", "east", "south", "west"), z, x, y))
                else:
                    if corner:
                        b.put(x, y, z, r["trim"] if y % 2 else pick(r["stone"], x, y, z))
                    else:
                        stone(b, style, x, y, z)
    # windows: arrow slits / small windows on every side at two heights
    for y in (3, 7, top - 3):
        for (x, z, pane) in ((x0 + 1, z0, PANE_X), (x1 - 1, z1, PANE_X), (x0, z0 + 2, PANE_Z), (x1, z1 - 2, PANE_Z)):
            if style == "wild":
                continue
            b.put(x, y, z, "glass_pane", **pane) if style == "temperate" and y == 7 else b.put(x, y, z, "iron_bars", **pane)
    # door, ladder, platform
    for half, y in (("lower", 1), ("upper", 2)):
        b.put(dx_, y, dz_, w + "_door" if style != "wild" else "bamboo_door", facing=df, half=half, hinge="left", open="false", powered="false")
    b.put(dx_ + (0 if door == "n" else -1), 0, dz_ + (-1 if door == "n" else 0), w + "_stairs" if style != "arid" else "sandstone_stairs",
          facing=OPPOSITE[df], **ST)
    for y in range(1, top + 1):
        b.put(lx, y, lz, "ladder", facing=lf, waterlogged="false")
        if style == "wild":                             # the open frame gets a bamboo board behind the ladder
            b.put(lx + (1 if lf == "west" else 0), y, lz + (1 if lf == "north" else 0), "bamboo_block", axis="y")
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if (x, z) == (lx, lz):
                continue
            outer = x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)
            if outer and style in ("highland", "temperate", "arid", "frozen"):
                if style == "highland":                    # a corbelled parapet
                    f = "south" if z == z0 - 1 else "north" if z == z1 + 1 else "east" if x == x0 - 1 else "west"
                    stairs(b, "stone_brick_stairs", x, top - 1, z, OPPOSITE[f], top=True)
                b.put(x, top, z, (w + "_planks") if style in ("temperate", "frozen") else r["trim"])
            elif not outer:
                b.put(x, top, z, w + "_planks" if style not in ("arid", "highland") else r["trim"])
            elif style == "wild":
                b.put(x, top, z, "jungle_planks")
    # railing or merlons round the platform
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if not (x in (x0 - 1, x1 + 1) or z in (z0 - 1, z1 + 1)):
                continue
            corner = x in (x0 - 1, x1 + 1) and z in (z0 - 1, z1 + 1)
            if style in ("highland", "arid"):
                if corner or (x + z) % 2 == 0:
                    b.put(x, top + 1, z, r["trim"] if style == "highland" else "cut_sandstone")
                    if style == "arid":
                        b.put(x, top + 2, z, "sandstone_wall", **WALL)
                else:
                    b.put(x, top + 1, z, r["slab"], type="bottom", waterlogged="false")
            elif style == "wild":
                b.put(x, top + 1, z, "bamboo_fence")
            else:
                b.put(x, top + 1, z, (r["dark"] if style == "frozen" else w) + "_fence")
    if style == "frozen":                               # carved dragon heads at the corners
        for x, z, f in ((x0 - 1, z0 - 1, "north"), (x1 + 1, z0 - 1, "east"), (x1 + 1, z1 + 1, "south"), (x0 - 1, z1 + 1, "west")):
            stairs(b, "dark_oak_stairs", x, top + 2, z, f)
    if style == "temperate":
        for x, z in ((x0 - 1, z0 - 1), (x1 + 1, z1 + 1)):
            lantern(b, x, top + 2, z)
    fire = (x1 - 1, top + 1, z0 + 1) if door == "n" else (x0 + 1, top + 1, z1 - 1)
    b.put(*fire, *bld.SIGNAL_FIRE[:1], **bld.SIGNAL_FIRE[1])
    return fire


def arch(b, style, zc):
    """Level 3: the arch over the road (a Roman road gate in the region's way), full road width and tall enough for a
    rider; the kingdom's banner hangs in the middle on both sides, lanterns light the way through."""
    r = RS[style]
    zs = (zc - 1, zc, zc + 1)
    clear = [(x, y, z) for x in range(-3, 4) for y in range(0, 5) for z in zs]
    for p in clear:
        b.put(*p, "air")
    if style == "arid":
        # a Moorish horseshoe arch of sandstone, a zellige frame, merlons on top
        for x in list(range(-6, -2)) + list(range(3, 7)):
            for z in zs:
                for y in range(0, 8):
                    b.put(x, y, z, "cut_sandstone" if y == 0 or abs(x) == 6 else "smooth_sandstone")
        for x in range(-2, 3):
            for z in zs:
                for y in range(5, 8):
                    b.put(x, y, z, "smooth_sandstone")
        for z in zs:   # the horseshoe: wider above the imposts than at the foot, closing to a point
            for y in (0, 1, 2, 3):
                b.put(-3, y, z, "smooth_sandstone")
                b.put(3, y, z, "smooth_sandstone")
            stairs(b, "smooth_sandstone_stairs", -3, 4, z, "west", top=True)
            stairs(b, "smooth_sandstone_stairs", 3, 4, z, "east", top=True)
            stairs(b, "smooth_sandstone_stairs", -2, 5, z, "west", top=True)
            stairs(b, "smooth_sandstone_stairs", 2, 5, z, "east", top=True)
            stairs(b, "smooth_sandstone_stairs", -1, 6, z, "west", top=True)
            stairs(b, "smooth_sandstone_stairs", 1, 6, z, "east", top=True)
            for x in (-1, 0, 1):
                b.put(x, 5, z, "air")
            b.put(0, 6, z, "air")
        for z, f in ((zc - 1, "north"), (zc + 1, "south")):
            for x in range(-5, 6):
                if abs(x) >= 3:
                    b.put(x, 4, z, pick(ZELLIGE, x, 4, z), facing=f)
                b.put(x, 8, z, pick(ZELLIGE, x, 8, z), facing=f)
            b.put(0, 7, z, "chiseled_sandstone")
        for x in range(-6, 7):
            for z in zs:
                b.put(x, 9, z, "cut_sandstone")
                if (x + z) % 2 == 0 or abs(x) == 6:
                    b.put(x, 10, z, "sandstone_wall", **WALL)
        ban_y, lan = 6, [(-3, 3), (3, 3)]
    elif style == "frozen":
        # carved Norse wood: crossing log posts, two lintels, crossed bargeboards with dragon heads on a shingle roof
        for xs in ((-5, -4), (4, 5)):
            for x in xs:
                for z in (zc - 1, zc + 1):
                    for y in range(0, 6):
                        log(b, "spruce_log" if (y + x) % 2 else "stripped_spruce_log", x, y, z)
                b.put(x, 0, zc, "cobbled_deepslate")
                for y in range(1, 6):    # carved bands between the posts
                    b.put(x, y, zc, "dark_oak_wood" if y % 2 else "spruce_planks", **({"axis": "y"} if y % 2 else {}))
        for x in range(-6, 7):
            for z in zs:
                log(b, "dark_oak_log", x, 5, z, "x")
                log(b, "stripped_spruce_log", x, 6, z, "x")
        for x in (-3, 3):
            for z in zs:
                stairs(b, "dark_oak_stairs", x, 4, z, "west" if x < 0 else "east", top=True)
        for z, f in ((zc - 2, "south"), (zc + 2, "north")):   # the shingle roof, ridge along x
            for x in range(-6, 7):
                stairs(b, "spruce_stairs" if x % 2 else "dark_oak_stairs", x, 7, z, f)
        for x in range(-6, 7):
            for z in zs:
                if z != zc:
                    stairs(b, "dark_oak_stairs" if x % 2 else "spruce_stairs", x, 8, z, "south" if z < zc else "north")
            b.put(x, 8, zc, "spruce_planks")
            b.put(x, 9, zc, "dark_oak_slab", type="bottom", waterlogged="false")
        for x, f in ((-7, "west"), (7, "east")):              # bargeboards crossing over the ridge into dragon heads
            for z in (zc - 2, zc + 2):
                stairs(b, "dark_oak_stairs", x, 7, z, "north" if z > zc else "south")
            for z in (zc - 1, zc + 1):
                stairs(b, "dark_oak_stairs", x, 8, z, "north" if z > zc else "south")
            b.put(x, 9, zc, "dark_oak_planks")
            stairs(b, "dark_oak_stairs", x, 10, zc, f)
        ban_y, lan = 5, [(-3, 3), (3, 3)]
    elif style == "highland":
        # a Scottish rubble-stone arch: a round arch with a carved keystone, crow-stepped parapet, slate copes
        for x in list(range(-6, -2)) + list(range(3, 7)):
            for z in zs:
                for y in range(0, 8):
                    b.put(x, y, z, r["base"] if y == 0 else ("polished_andesite" if abs(x) == 6 and y % 2 else pick(r["stone"], x, y, z)))
        for x in range(-2, 3):
            for z in zs:
                for y in range(5, 8):
                    stone(b, style, x, y, z)
        for z in zs:
            stairs(b, "stone_brick_stairs", -2, 5, z, "west", top=True)
            stairs(b, "stone_brick_stairs", 2, 5, z, "east", top=True)
            for x in (-1, 0, 1):
                b.put(x, 5, z, "air")
            stairs(b, "stone_brick_stairs", -3, 4, z, "west", top=True)
            stairs(b, "stone_brick_stairs", 3, 4, z, "east", top=True)
            b.put(0, 6, z, "chiseled_stone_bricks")
            stairs(b, "stone_brick_stairs", -1, 6, z, "west", top=True)
            stairs(b, "stone_brick_stairs", 1, 6, z, "east", top=True)
        for x in range(-6, 7):        # crow steps rising to the middle
            step = 8 + max(0, 3 - abs(x) // 2)
            for z in zs:
                for y in range(8, step):
                    stone(b, style, x, y, z)
                b.put(x, step, z, "deepslate_tile_slab", type="bottom", waterlogged="false")
        ban_y, lan = 7, [(-3, 3), (3, 3)]
    elif style == "wild":
        # a Southeast Asian gate: red-lacquered posts and beams, a two-tier flared roof with upturned ends
        for x in (-4, 4):
            for z in (zc - 1, zc + 1):
                b.put(x, -1, z, "mossy_cobblestone")
                for y in range(0, 6):
                    log(b, "jungle_log" if y < 5 else "stripped_mangrove_log", x, y, z)
            b.put(x, 0, zc, "mossy_stone_bricks")
            b.put(x, 1, zc, "bamboo_fence")
        for x in range(-6, 7):
            for z in zs:
                log(b, "stripped_mangrove_log", x, 5, z, "x")
            b.put(x, 6, zc, "mangrove_planks")
        for x in (-3, 3):
            for z in (zc - 1, zc + 1):
                b.put(x, 4, z, "mangrove_trapdoor", facing="north" if z < zc else "south", half="top", open="false", powered="false",
                      waterlogged="false")
        for z, f in ((zc - 2, "south"), (zc + 2, "north")):    # lower tier
            for x in range(-6, 7):
                stairs(b, "spruce_stairs", x, 6, z, f)
        for x in range(-5, 6):                                # upper tier
            for z in zs:
                if z != zc:
                    stairs(b, "spruce_stairs", x, 7, z, "south" if z < zc else "north")
            b.put(x, 7, zc, "spruce_planks")
            b.put(x, 8, zc, "spruce_slab", type="bottom", waterlogged="false")
        for x, f in ((-7, "west"), (7, "east")):              # upturned ends
            for z in (zc - 2, zc + 2):
                stairs(b, "spruce_stairs", x, 7, z, f)
        for x, f in ((-6, "west"), (6, "east")):
            stairs(b, "spruce_stairs", x, 8, zc, f)
        ban_y, lan = 5, [(-3, 2), (3, 2)]
    else:
        # temperate: a German road gate of stone with a half-timbered gate room under a tiled roof
        for x in list(range(-6, -3)) + list(range(4, 7)):
            for z in zs:
                for y in range(0, 5):
                    b.put(x, y, z, r["base"] if y == 0 else ("polished_andesite" if abs(x) == 6 and y % 2 == 0 else pick(r["stone"], x, y, z)))
        for x in (-3, 3):
            for z in zs:
                stairs(b, "stone_brick_stairs", x, 4, z, "west" if x < 0 else "east", top=True)
        for x in range(-6, 7):
            for z in zs:
                b.put(x, 5, z, "spruce_planks" if z == zc else "stripped_dark_oak_log", **({} if z == zc else {"axis": "x"}))
        for x in range(-6, 7):        # the gate room: posts, white infill, windows
            for z in (zc - 1, zc + 1):
                for y in (6, 7):
                    if abs(x) in (6, 3, 0):
                        log(b, "stripped_dark_oak_log", x, y, z)
                    elif y == 7 and abs(x) in (1, 2, 4, 5) and abs(x) != 2:
                        b.put(x, y, z, "glass_pane", **PANE_X)
                    else:
                        b.put(x, y, z, "calcite")
            for y in (6, 7):
                for z in (zc,):
                    if abs(x) == 6:
                        b.put(x, y, z, "calcite")
            log(b, "stripped_dark_oak_log", x, 8, zc - 1, "x")
            log(b, "stripped_dark_oak_log", x, 8, zc + 1, "x")
        for z, f in ((zc - 2, "south"), (zc + 2, "north")):
            for x in range(-7, 8):
                stairs(b, "brick_stairs", x, 8, z, f)
        for x in range(-7, 8):
            for z in (zc - 1, zc + 1):
                stairs(b, "brick_stairs", x, 9, z, "south" if z < zc else "north")
            b.put(x, 9, zc, "bricks")
            b.put(x, 10, zc, "brick_slab", type="bottom", waterlogged="false")
        for x in (-6, 6):              # framed gables
            log(b, "stripped_dark_oak_log", x, 8, zc)
            b.put(x, 9, zc, "calcite")
        for x in (-6, 6):
            b.put(x, 6, zc, "calcite")
            b.put(x, 7, zc, "calcite")
        for x in range(-5, 6):         # the gate room's inside is plastered shut (no stair up to it)
            for y in (6, 7, 8):
                if (x, y, zc) not in b.b:
                    b.put(x, y, zc, "calcite")
        ban_y, lan = 5, [(-3, 3), (3, 3)]
    # the kingdom's banner in the middle on both faces, lanterns either side of the way
    for z, f in ((zc - 2, "north"), (zc + 2, "south")):
        wall_banner(b, 0, ban_y, z, f)
    for (x, y) in lan:
        lantern(b, x, y, zc, hanging=True)


def enclosure(b, style):
    """Level 3: a wall round the yard (a palisade in the north and the jungle), the gate toward the road."""
    r = RS[style]
    x0, x1, z0, z1 = 4, 23, -14, 13
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if not (x in (x0, x1) or z in (z0, z1)):
                continue
            if x == x0 and -2 <= z <= 2 or x == x0 and 9 <= z <= 11:
                continue        # the gate into the yard, and the arch's pier
            corner = x in (x0, x1) and z in (z0, z1)
            post = corner or (x + z) % 6 == 0
            if style in ("frozen", "wild"):
                for y in range(0, 3 if not post else 4):
                    log(b, "spruce_log" if style == "frozen" else "bamboo_block", x, y, z)
                if style == "frozen" and post:
                    stairs(b, "dark_oak_stairs", x, 3, z, "north" if z == z1 else "south" if z == z0 else "east")
            else:
                b.put(x, 0, z, r["base"])
                b.put(x, 1, z, pick(r["stone"], x, 1, z))
                if post:
                    b.put(x, 2, z, r["trim"])
                    lantern(b, x, 3, z) if corner else b.put(x, 3, z, r["slab"], type="bottom", waterlogged="false")
                else:
                    b.put(x, 2, z, r["wall"], **WALL)
    for z in (-3, 3):      # gate posts with lanterns
        for y in range(0, 3):
            log(b, r["post"], x0, y, z)
        lantern(b, x0, 3, z)


def waystation(style, level):
    b = B()
    camp(b, style, level)
    if level >= 2:
        inn(b, style)
        stable(b, style)
        well(b, style, 18, 0)
        trough(b, style, 19, -11, -9)
    if level >= 3:
        enclosure(b, style)
        watchtower(b, style, 19, 3, "n")
        arch(b, style, 10)
    # nothing of the station on the road itself: the mod lays it (the arch passes over it)
    for (x, y, z) in list(b.b):
        if -1 <= x <= 1 and y < 0:
            del b.b[(x, y, z)]
    bld.hang_lanterns(b.b)
    return b.b


WAYSTATION_ANIMALS = {2: [["horse", 8, 0, -10], ["horse", 13, 0, -10]], 3: [["horse", 8, 0, -10], ["horse", 13, 0, -10]]}
# where the station's guards stand (level 3): under the arch, at the tower door
WAYSTATION_GUARDS = [(2, 0, 8), (21, 0, 1)]


# ---------------------------------------------------------------- along the road

def shrine(style):
    """A wayside shrine beside the road (faces it from x = 3): a German Bildstock, a carved Norse post, a Moorish
    niche, a Highland wayside cross, a Southeast Asian spirit house. A lantern always burns in its niche."""
    b = B()
    r = RS[style]
    x = 3
    if style == "temperate":
        b.put(x, 0, 0, "polished_andesite")
        b.put(x, 1, 0, "andesite_wall", **WALL)
        b.put(x, 2, 0, "chiseled_stone_bricks")
        for z in (-1, 1):
            stairs(b, "stone_brick_stairs", x, 3, z, "south" if z > 0 else "north", top=True)
        b.put(x + 1, 3, 0, "stone_bricks")
        lantern(b, x, 3, 0)
        for z, f in ((-1, "south"), (1, "north")):
            stairs(b, "brick_stairs", x, 4, z, f)
        stairs(b, "brick_stairs", x + 1, 4, 0, "west")
        b.put(x, 4, 0, "bricks")
        b.put(x, 5, 0, "brick_slab", type="bottom", waterlogged="false")
        b.put(x - 1, 0, 0, "stone_brick_slab", type="bottom", waterlogged="false")
        for z in (-1, 1):
            b.put(x, 0, z, "potted_red_tulip" if z < 0 else "potted_cornflower")
    elif style == "frozen":
        b.put(x, 0, 0, "cobbled_deepslate")
        for y in (1, 2):
            log(b, "dark_oak_log", x, y, 0)
        b.put(x + 1, 3, 0, "dark_oak_planks")
        lantern(b, x, 3, 0)
        for z, f in ((-1, "north"), (1, "south")):
            b.put(x, 3, z, "spruce_trapdoor", facing=f, half="bottom", open="true", powered="false", waterlogged="false")
            stairs(b, "dark_oak_stairs", x, 4, z, "south" if z < 0 else "north")
        b.put(x, 4, 0, "spruce_planks")
        stairs(b, "dark_oak_stairs", x, 5, 0, "west")
        b.put(x, 0, -1, "chiseled_deepslate")
        b.put(x, 0, 1, "polished_deepslate")
    elif style == "arid":
        for z in (-1, 0, 1):
            for y in range(0, 4):
                if z == 0 and y in (1, 2):
                    continue
                b.put(x + 1, y, z, "smooth_sandstone")
                if z != 0:
                    b.put(x, y, z, "cut_sandstone" if y < 3 else "smooth_sandstone")
        b.put(x, 0, 0, "chiseled_sandstone")
        lantern(b, x, 1, 0)
        stairs(b, "smooth_sandstone_stairs", x, 2, 0, "west", top=True)
        b.put(x, 3, 0, pick(ZELLIGE, x, 3, 0), facing="west")
        b.put(x + 1, 1, 0, pick(ZELLIGE, x, 1, 1), facing="west")
        b.put(x + 1, 2, 0, "smooth_sandstone")
        for z in (-1, 0, 1):
            b.put(x, 4, z, "smooth_sandstone_slab", type="bottom", waterlogged="false")
            b.put(x + 1, 4, z, "smooth_sandstone_slab", type="bottom", waterlogged="false")
        b.put(x + 1, 4, 0, "cut_sandstone")
        b.put(x + 1, 5, 0, "sandstone_wall", **WALL)
        b.put(x - 1, 0, 0, "decorated_pot", facing="west", cracked="false", waterlogged="false")
    elif style == "highland":
        for dx in (0, 1):
            for z in (-1, 0, 1):
                if dx == 0 and z != 0:
                    b.put(x + dx, 0, z, "mossy_cobblestone" if z < 0 else "cobblestone")
                elif dx == 1:
                    b.put(x + dx, 0, z, "cobblestone" if z else "mossy_cobblestone")
        b.put(x, 0, 0, "chiseled_stone_bricks")
        b.put(x + 1, 1, 0, "stone_bricks")
        for y in (2, 3, 4):
            b.put(x + 1, y, 0, "stone_brick_wall", **WALL)
        for z in (-1, 1):
            b.put(x + 1, 4, z, "stone_brick_wall", **WALL)
        b.put(x + 1, 5, 0, "stone_brick_wall", **WALL)
        lantern(b, x, 1, 0)
        b.put(x, 1, 1, "mossy_cobblestone_slab", type="bottom", waterlogged="false")
        b.put(x, 1, -1, "cobblestone_slab", type="bottom", waterlogged="false")
    else:  # wild: a spirit house on its pillar
        b.put(x, 0, 0, "mossy_stone_bricks")
        log(b, "jungle_log", x, 1, 0)
        for z in (-1, 0, 1):
            b.put(x, 2, z, "jungle_slab", type="top", waterlogged="false")
            b.put(x + 1, 2, z, "jungle_slab", type="top", waterlogged="false")
        b.put(x + 1, 3, 0, "stripped_mangrove_log", axis="y")
        b.put(x + 1, 3, -1, "bamboo_fence")
        b.put(x + 1, 3, 1, "bamboo_fence")
        lantern(b, x, 3, 0)
        b.put(x, 3, -1, "candle", candles="2", lit="true", waterlogged="false")
        b.put(x, 3, 1, "potted_red_tulip")
        for z, f in ((-1, "south"), (1, "north")):
            for dx in (0, 1):
                stairs(b, "spruce_stairs", x + dx, 4, z, f)
        b.put(x, 4, 0, "mangrove_planks")
        b.put(x + 1, 4, 0, "mangrove_planks")
        for z, f in ((-2, "north"), (2, "south")):
            stairs(b, "spruce_stairs", x, 4, z, f)
            stairs(b, "spruce_stairs", x + 1, 4, z, f)
        b.put(x, 5, 0, "spruce_slab", type="bottom", waterlogged="false")
        b.put(x + 1, 5, 0, "spruce_slab", type="bottom", waterlogged="false")
    for z in range(-1, 2):          # a little trodden ground in front
        if (x - 1, 0, z) not in b.b:
            b.put(x - 1, -1, z, "coarse_dirt" if z else bld.STYLES[style]["path"][0])
    return b.b


def bridge_tower(style):
    """A gate tower over the end of a big bridge (the Charles Bridge in Prague): a passage the road's and the
    railings' width, a room over it, the region's crown; the kingdom's banner on both faces. Stands on the deck
    (y = -1 is the deck's surface); the mod carries its outer walls down to the riverbed."""
    b = B()
    r = RS[style]
    H = 10
    for x in range(-4, 5):
        for z in range(-2, 3):
            inner = -2 <= x <= 2
            for y in range(-1 if not inner else 0, H):
                if inner and y <= 4:
                    b.put(x, y, z, "air")
                    continue
                if style == "frozen" or style == "wild":
                    corner = abs(x) == 4 and abs(z) == 2
                    if y <= 4 or corner:
                        b.put(x, y, z, pick(r["stone"], x, y, z) if not corner or y < 2 else ("spruce_log" if style == "frozen" else "jungle_log"),
                              **({"axis": "y"} if corner and y >= 2 else {}))
                    else:
                        b.put(x, y, z, "spruce_planks" if style == "frozen" else "bamboo_planks")
                elif style == "arid":
                    b.put(x, y, z, "cut_sandstone" if abs(x) == 4 and abs(z) == 2 else "smooth_sandstone")
                elif style == "temperate" and y >= 6:
                    edge = abs(x) == 4 or abs(z) == 2
                    if edge and (abs(x) in (4, 0) and abs(z) == 2 or abs(z) in (2, 0) and abs(x) == 4 or y in (6, H - 1)):
                        log(b, "stripped_dark_oak_log", x, y, z, "y" if y not in (6, H - 1) else ("x" if abs(z) == 2 else "z"))
                    elif edge:
                        b.put(x, y, z, "calcite")
                    else:
                        b.put(x, y, z, "spruce_planks")
                else:
                    corner = abs(x) == 4 and abs(z) == 2
                    b.put(x, y, z, (r["trim"] if y % 2 else pick(r["stone"], x, y, z)) if corner else pick(r["stone"], x, y, z))
            if not inner:
                b.put(x, -1, z, r["base"] if style != "arid" else "cut_sandstone")
    for z in range(-2, 3):           # the round head of the passage
        for x, f in ((-2, "west"), (2, "east")):
            stairs(b, (r["stairs"] if style != "frozen" else "dark_oak_stairs"), x, 4, z, f, top=True)
    # windows on both faces, and the kingdom's banner over the passage
    for z, f in ((-2, "north"), (2, "south")):
        for x in (-2, 2):
            b.put(x, 7, z, "glass_pane" if style == "temperate" else "iron_bars", **PANE_X)
        if style == "arid":
            for x in range(-3, 4):
                b.put(x, 8, z, pick(ZELLIGE, x, 8, z), facing=f)
        out = z - 1 if f == "north" else z + 1
        wall_banner(b, 0, 6, out, f)
        lantern(b, 0, 4, z, hanging=True)
    # crown
    top = H
    if style in ("temperate", "frozen", "highland", "wild"):
        if style == "highland":
            for x in range(-5, 6):
                for z in range(-3, 4):
                    if abs(x) == 5 or abs(z) == 3:
                        stairs(b, "stone_brick_stairs", x, top - 1, z, "east" if x == -5 else "west" if x == 5 else "south" if z == -3 else "north",
                               top=True)
                        b.put(x, top, z, r["trim"] if (x + z) % 2 == 0 else "cobblestone")
                        if (x + z) % 2 == 0:
                            b.put(x, top + 1, z, "stone_brick_slab", type="bottom", waterlogged="false")
                    else:
                        b.put(x, top, z, "stone_bricks")
            log(b, "spruce_log", 0, top + 1, 0)
            log(b, "spruce_log", 0, top + 2, 0)
            b.put(0, top + 3, 0, "spruce_fence")
        else:
            blk, st, cap = ("bricks", "brick_stairs", "brick_slab") if style == "temperate" else \
                ("spruce_planks", "spruce_stairs", "dark_oak_slab") if style == "frozen" else ("spruce_planks", "spruce_stairs", "spruce_slab")
            for i in range(0, 4):    # a pyramid roof, solid under its stairs
                for x in range(-5 + i, 6 - i):
                    for z in range(-3 + i, 4 - i):
                        ring = abs(x) == 5 - i or abs(z) == 3 - i
                        if not ring:
                            b.put(x, top + i, z, blk)
                            continue
                        f = "east" if x == -5 + i else "west" if x == 5 - i else "south" if z == -3 + i else "north"
                        stairs(b, st, x, top + i, z, f)
            for x in range(-1, 2):
                b.put(x, top + 3, 0, blk)
                b.put(x, top + 4, 0, cap, type="bottom", waterlogged="false")
            if style == "wild":
                for x, f in ((-6, "west"), (6, "east")):
                    stairs(b, st, x, top, 0, f)
    else:   # arid: merlons
        for x in range(-4, 5):
            for z in range(-2, 3):
                b.put(x, top, z, "cut_sandstone")
                if (abs(x) == 4 or abs(z) == 2) and (x + z) % 2 == 0:
                    b.put(x, top + 1, z, "sandstone_wall", **WALL)
    bld.hang_lanterns(b.b)
    return b.b


def watchtower_alone(style):
    """A lookout on a hill along a long road, 3 blocks off its edge, with a path to its door."""
    b = B()
    watchtower(b, style, 4, -2, "w")
    ground_patch(b, style, [(2, z) for z in (-1, 0, 1)] + [(3, 0), (3, -1)])
    bld.hang_lanterns(b.b)
    return b.b


def jobs():
    """(style, name, blocks, animals) of every roadside template."""
    out = []
    for style in bld.STYLES:
        for level in (1, 2, 3):
            out.append((style, f"waystation_{level}", waystation(style, level), WAYSTATION_ANIMALS.get(level)))
        out.append((style, "shrine", shrine(style), None))
        out.append((style, "bridge_tower", bridge_tower(style), None))
        out.append((style, "watchtower", watchtower_alone(style), None))
    return out


def picture(blocks, rot=0, scale=3, road=True):
    """The template on a meadow with the road through it (for looking at it, see __main__)."""
    from render import render
    xs = [p[0] for p in blocks] + [-3, 3]
    zs = [p[2] for p in blocks]
    x0, x1, z0, z1 = min(xs) - 2, max(xs) + 2, min(zs) - 2, max(zs) + 2
    world = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            world[(x, -1, z)] = ("minecraft:dirt_path", {}) if road and -1 <= x <= 1 else ("minecraft:grass_block", {"snowy": "false"})
            world[(x, -2, z)] = ("minecraft:dirt", {})
    for p, (n, pr) in blocks.items():
        if n.endswith(":air"):
            world.pop(p, None)
        else:
            world[p] = (n.replace("{color}", "minecraft:blue"), pr)
    import preview
    world = preview.connect(world)
    ys = [p[1] for p in world]
    shifted = {(x - x0, y - min(ys), z - z0): v for (x, y, z), v in world.items()}
    size = (x1 - x0 + 1, max(ys) - min(ys) + 1, z1 - z0 + 1)
    return render(shifted, size, rot, scale, bg=(170, 200, 230, 255))


if __name__ == "__main__":
    # python3 tools/roadside.py <out dir> [style] [name] [--rot N]: renders roadside templates (KQ_FETCH=1 fetches assets)
    import os, sys
    a = [v for v in sys.argv[1:] if not v.startswith("--")]
    rot = int(sys.argv[sys.argv.index("--rot") + 1]) if "--rot" in sys.argv else 0
    if "--rot" in sys.argv:
        a.remove(str(rot))
    out = a[0]
    os.makedirs(out, exist_ok=True)
    for style, name, blocks, _ in jobs():
        if len(a) > 1 and style != a[1] or len(a) > 2 and name != a[2]:
            continue
        img = picture(blocks, rot, 3 if name.startswith("waystation") else 5, road=name != "watchtower" or True)
        img.save(os.path.join(out, f"{style}_{name}_r{rot}.png"))
        print(style, name, img.size)
