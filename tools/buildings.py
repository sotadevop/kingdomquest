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


def to_json(blocks, seconds):
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
    return {"size": list(PLOT), "heart": list(HEART), "seconds": seconds, "palette": palette, "blocks": out}


SECONDS = {1: 60, 2: 90, 3: 150}

if __name__ == "__main__":
    for style in STYLES:
        os.makedirs(os.path.join(OUT, style), exist_ok=True)
        for level in (1, 2, 3):
            blocks = tent(style, level)
            with open(os.path.join(OUT, style, f"tent_{level}.json"), "w") as f:
                json.dump(to_json(blocks, SECONDS[level]), f, separators=(",", ":"))
            if "--render" in sys.argv:
                sys.path.insert(0, os.path.dirname(__file__))
                from render import render, add_ground
                colored = {p: (n.replace("{color}", "minecraft:blue"), pr) for p, (n, pr) in blocks.items()}
                g = add_ground({p: v for p, v in colored.items() if p[1] >= 0}, PLOT, pad=0)
                if style == "frozen":
                    g = {p: (("minecraft:grass_block", {"snowy": "true"}) if v[0] == "minecraft:grass_block" else v) for p, v in g.items()}
                for p, v in colored.items():
                    if p[1] < 0:
                        g[p] = v
                os.makedirs("build/previews", exist_ok=True)
                render(g, PLOT, 0, 3, bg=(170, 200, 230, 255)).save(f"build/previews/{style}_tent_{level}.png")
        print(style, "ok")
