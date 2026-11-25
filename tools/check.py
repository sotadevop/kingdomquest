"""Checks a whole settlement offline, the way a player would meet it.

Run: python3 tools/check.py [style ...] [--main N] [--quiet]

Every template of a style is put together on flat ground at each tier (all tracks at the highest level that
tier allows) and tested:

  overlap   two tracks claim the same block with different blocks
  support   ladders, banners, lanterns, doors, beds... that hang on nothing
  roads     roads paved under buildings, doors and work spots no road leads to
  walk      a half-block walk simulation from the heart: doors, beds, stores and rooms nobody can reach
  floating  blocks with no connection to the ground

Exit code 1 if anything is found. The steps and tiers are read from Progression.java, the worker posts from
Workers.java, so the check follows the mod.
"""
import os, re, sys
from collections import deque, defaultdict

sys.path.insert(0, os.path.dirname(__file__))
import buildings as bld

ROOT = os.path.join(os.path.dirname(__file__), "..")
JAVA = os.path.join(ROOT, "src", "main", "java", "io", "github", "sotadevop", "kingdomquest", "kingdom")
CONST = {"Settlement.MAIN": "main", "WALLS": "walls", "ROADS": "roads", "LAMPS": "lamps"}


def read_steps():
    """{track: [(building, level, minMain)]} and the track order, from Progression.java."""
    src = open(os.path.join(JAVA, "Progression.java")).read()
    steps = defaultdict(list)
    for m in re.finditer(r'new Step\(\s*([\w.]+|"\w+"),\s*"(\w+)",\s*(\d+),\s*"[^"]+",\s*(\d+)', src):
        track = m.group(1).strip('"')
        track = CONST.get(track, track.lower())
        steps[track].append((m.group(2), int(m.group(3)), int(m.group(4))))
    order = re.search(r"ORDER = List\.of\((.*?)\);", src, re.S).group(1)
    order = [CONST.get(t.strip().strip('"'), t.strip().strip('"').lower()) for t in order.split(",")]
    gate_min = [int(v) for v in re.search(r"GATE_MIN_WALLS = \{([\d, ]+)\}", src).group(1).split(",")]
    return steps, order, gate_min


def read_workers():
    """Worker posts of Workers.java, moved with their track's plot (RELOCATE, as Layout.java does)."""
    src = open(os.path.join(JAVA, "Workers.java")).read()
    out = []
    for m in re.finditer(r'new Spec\("(\w+)",\s*(\d+),\s*"([^"]+)",\s*"\w+",\s*(-?\d+),\s*(-?\d+)', src):
        dx, dz = bld.RELOCATE.get(m.group(1), (0, 0))
        out.append((m.group(1), int(m.group(2)), m.group(3), int(m.group(4)) + dx, int(m.group(5)) + dz))
    return out


STEPS, ORDER, GATE_MIN = read_steps()


def levels_at(main):
    """Every track at the highest level a settlement of this main level can have."""
    lv = {"main": main}
    for track, steps in STEPS.items():
        if track == "main":
            continue
        n = 0
        for building, level, min_main in steps:
            if min_main <= main and level == n + 1:
                n = level
        lv[track] = n
    if lv.get("gate"):
        while lv["gate"] and GATE_MIN[lv["gate"] - 1] > lv.get("walls", 0):
            lv["gate"] -= 1
    return lv


# ---------------------------------------------------------------- the world

class World:
    def __init__(self, style, levels):
        self.style, self.levels = style, levels
        self.blocks = {}                      # pos -> (name, props), later tracks on top
        self.by_track = {}                    # track -> {pos: (name, props)}
        self.storage = {}
        jobs = {j[1]: j for j in JOBS[style]}
        for track in ORDER:
            lvl = levels.get(track, 0)
            if not lvl or track == "dock":
                continue
            name = STEPS[track][lvl - 1][0]
            _, _, blocks, _, storage, _, _ = jobs[name]
            segs = bld.SEGS_OF.get(id(blocks))
            if segs:
                on = [any(levels.get(t, 0) >= l if l >= 0 else levels.get(t, 0) < -l for t, l in rules) for _, rules in bld.SEGDEF_OF[id(blocks)]]
                blocks = {p: v for p, v in blocks.items() if p not in segs or any(on[i] for i in segs[p])}
            self.by_track[track] = blocks
            self.blocks.update(blocks)
            if storage:
                self.storage[track] = storage
        self.owner = {}
        for track, blocks in self.by_track.items():
            for p in blocks:
                self.owner[p] = track

    def get(self, p):
        b = self.blocks.get(p)
        if b is not None:
            return b
        return GROUND if p[1] < 0 else AIR


AIR = ("minecraft:air", {})
GROUND = ("minecraft:dirt", {})
PLANTS = ("wheat", "carrots", "potatoes", "beetroots", "poppy", "dandelion", "cornflower", "oxeye_daisy", "allium", "azure_bluet",
          "lily_of_the_valley", "sweet_berry_bush", "rose_bush", "short_grass", "fern", "amethyst_cluster", "snow", "water", "lava")
OPEN_ENDS = ("_door", "_trapdoor", "_fence_gate", "_carpet", "_pressure_plate", "_banner", "_button", "torch", "ladder", "lever", "rail")


def short(name):
    return name.split(":", 1)[-1]


def mask(block):
    """Solid half-block cells of a block as 8 bits (bit = sx | sz<<1 | sy<<2), plus True if it is 1.5 high."""
    name, p = short(block[0]), block[1]
    if name == "air" or name in PLANTS or name.endswith(OPEN_ENDS):
        return 0, False
    if name.endswith("_slab"):
        return {"bottom": 0x0F, "top": 0xF0}.get(p.get("type"), 0xFF), False
    if name.endswith("_stairs"):
        f = p.get("facing", "north")
        side = {"east": 0b1010, "west": 0b0101, "south": 0b1100, "north": 0b0011}[f]
        return (0x0F | side << 4, False) if p.get("half", "bottom") == "bottom" else (0xF0 | side, False)
    if name.endswith("_fence") or name.endswith("_wall"):
        return 0xFF, True
    if name == "lantern" or name == "soul_lantern":
        return (0xFF if p.get("hanging") == "true" else 0x0F), False
    if name.endswith("campfire") or name.endswith("_bed") or name == "stonecutter":
        return 0x0F, False
    return 0xFF, False


class Grid:
    """Half-block collision grid of a world."""

    def __init__(self, world):
        self.w = world
        self.m = {}
        for pos, b in world.blocks.items():
            bits, tall = mask(b)
            self.m[pos] = self.m.get(pos, 0) | bits if pos in self.m and bits else bits if pos not in self.m else self.m[pos] | bits
            if tall:
                up = (pos[0], pos[1] + 1, pos[2])
                self.m[up] = self.m.get(up, mask(world.get(up))[0]) | 0x0F
        # blocks placed after a tall fence below them must keep its extra half
        self.ladders = {pos for pos, b in world.blocks.items() if short(b[0]) == "ladder"}
        if world.levels.get("cellar", 0) >= 3:  # the secret door, slid open
            for z in (1, 2):
                for y in (-5, -4):
                    self.m[(bld.CELLAR[0] - 1, y, z)] = 0

    def solid(self, X, Y, Z):
        b = (X >> 1, Y >> 1, Z >> 1)
        m = self.m.get(b)
        if m is None:
            return b[1] < 0
        return bool(m >> ((X & 1) | (Z & 1) << 1 | (Y & 1) << 2) & 1)

    def free(self, X, Y, Z, w):
        s = self.solid
        for dx in range(w):
            for dz in range(w):
                for dy in range(4):
                    if s(X + dx, Y + dy, Z + dz):
                        return False
        return True

    def supported(self, X, Y, Z, w):
        for dx in range(w):
            for dz in range(w):
                if self.solid(X + dx, Y - 1, Z + dz) or (X + dx >> 1, Y - 1 >> 1, Z + dz >> 1) in self.ladders:
                    return True
        return False

    def on_ladder(self, X, Y, Z, w):
        for dx in range(w):
            for dz in range(w):
                if (X + dx >> 1, Y >> 1, Z + dz >> 1) in self.ladders:
                    return True
        return False


# outside the walls (the tournament field, the outposts): the walk from the town doesn't go there (no road yet)
OUTSIDE_TRACKS = ("quarry", "lumbercamp", "minecamp")

# blocks: x0, x1, y0, y1, z0, z1: the wall ring with the moat and the landing beyond the gate
BOX = (bld.WALL[0] - 14, bld.WALL[1] + 14, -30, 50, bld.WALL[2] - 14, bld.WALL[3] + 16)


def walk(grid, start, w=1, jump=False):
    """Every position (half-block feet corner) a player of width w half-blocks reaches from start."""
    seen = set()
    queue = deque()

    def add(s):
        if s not in seen and BOX[0] * 2 <= s[0] <= BOX[1] * 2 and BOX[2] * 2 <= s[1] <= BOX[3] * 2 and BOX[4] * 2 <= s[2] <= BOX[5] * 2:
            seen.add(s)
            queue.append(s)

    add(start)
    free, sup, lad = grid.free, grid.supported, grid.on_ladder
    while queue:
        X, Y, Z = queue.popleft()
        for dX, dZ in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, nz = X + dX, Z + dZ
            if free(nx, Y, nz, w):
                if sup(nx, Y, nz, w) or lad(nx, Y, nz, w):
                    add((nx, Y, nz))
                else:
                    y = Y
                    while y > BOX[2] * 2 and free(nx, y - 1, nz, w) and not lad(nx, y, nz, w):
                        y -= 1
                    add((nx, y, nz))
            elif free(nx, Y + 1, nz, w) and free(X, Y + 1, Z, w):
                add((nx, Y + 1, nz))
            elif jump and free(X, Y + 2, Z, w) and free(X, Y + 1, Z, w) and free(nx, Y + 2, nz, w):
                add((nx, Y + 2, nz))
        if lad(X, Y, Z, w) or lad(X, Y - 1, Z, w):
            if free(X, Y + 1, Z, w) and lad(X, Y, Z, w):
                add((X, Y + 1, Z))
            if free(X, Y - 1, Z, w):
                add((X, Y - 1, Z))
    return seen


def cells_of(seen, w=1):
    """Blocks (x, y, z) whose space a reached player stands in."""
    out = set()
    for X, Y, Z in seen:
        for dx in range(w):
            for dz in range(w):
                out.add((X + dx >> 1, Y >> 1, Z + dz >> 1))
    return out


DIRS = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


# ---------------------------------------------------------------- checks

def check_overlap(world, out):
    tracks = list(world.by_track)
    for i, a in enumerate(tracks):
        for b in tracks[i + 1:]:
            A, B = world.by_track[a], world.by_track[b]
            if len(B) < len(A):
                small, big = B, A
            else:
                small, big = A, B
            clash = [p for p, v in small.items() if p in big and short(big[p][0]) != short(v[0])]
            # a road or path under a floor is reported by the road check; air carved by one track through another is real
            if clash:
                p = sorted(clash)[len(clash) // 2]
                out.append(("overlap", f"{a} x {b}: {len(clash)} blocks, e.g. {p}: {short(A[p][0])} vs {short(B[p][0])}"))


def check_support(world, out):
    g = world.get
    bad = defaultdict(list)

    def full(p):
        return mask(g(p))[0] == 0xFF and short(g(p)[0]) not in ("lantern",)

    for pos, (name, p) in world.blocks.items():
        n = short(name)
        x, y, z = pos
        below, above = (x, y - 1, z), (x, y + 1, z)
        why = None
        if n == "ladder" or n.endswith("_wall_banner") or n == "wall_torch" or (n in ("lever",) or n.endswith("_button")) and p.get("face") == "wall":
            d = DIRS[p["facing"]]
            if not full((x - d[0], y, z - d[1])):
                why = "nothing behind it"
        elif n == "lantern":
            if p.get("hanging") == "true":
                a = short(g(above)[0])
                if not (mask(g(above))[0] & 0x0F or a.endswith(("_fence", "_wall", "chain", "_trapdoor"))):
                    why = "hangs on nothing"
            elif not mask(g(below))[0] & 0xF0 and not short(g(below)[0]).endswith(("_fence", "_wall")):
                why = "stands on nothing"
        elif n.endswith(("_carpet", "_pressure_plate", "_banner", "rail", "torch")) or n in ("lever",) or n in PLANTS and n not in ("water", "lava", "amethyst_cluster"):
            if not mask(g(below))[0] & 0xF0 and not (n == "rose_bush" and short(g(below)[0]) == n):
                why = "stands on nothing"
            elif n in PLANTS and n not in ("snow",) and short(g(below)[0]) not in ("dirt", "grass_block", "farmland", "coarse_dirt", "podzol", "rose_bush", "end_stone", "moss_block"):
                why = "is planted on " + short(g(below)[0])
        elif n.endswith("_door"):
            if p.get("half") == "lower" and (not full(below) or short(g(above)[0]) != n):
                why = "no floor under it or no upper half"
        elif n.endswith("_bed"):
            d = DIRS[p["facing"]]
            other = (x + d[0], y, z + d[1]) if p["part"] == "foot" else (x - d[0], y, z - d[1])
            if short(g(other)[0]) != n or g(other)[1].get("part") == p["part"]:
                why = "half a bed"
            elif not mask(g(below))[0] & 0xF0:
                why = "floats"
        elif n == "bell":
            ok = full(above) if p.get("attachment") == "ceiling" else mask(g(below))[0] & 0xF0
            if not ok:
                why = "hangs on nothing"
        if why:
            bad[(world.owner.get(pos), n, why)].append(pos)
    for (track, n, why), ps in sorted(bad.items(), key=str):
        out.append(("support", f"{track}: {len(ps)} x {n} {why}, e.g. {sorted(ps)[0]}"))


def doors_of(world):
    """(track, door block, the two blocks in front and behind) for every door's lower half."""
    for pos, (name, p) in world.blocks.items():
        if short(name).endswith("_door") and p.get("half") == "lower":
            d = DIRS[p["facing"]]
            x, y, z = pos
            yield world.owner[pos], pos, (x + d[0], y, z + d[1]), (x - d[0], y, z - d[1])


def road_plan(world):
    """Road cells of the full road network at this tier: {(x, z)}."""
    blocks = world.by_track.get("roads", {})
    return {(x, z) for (x, y, z) in blocks}


def check_roads(world, out):
    roads = road_plan(world)
    if not roads:
        return
    # 1. paved under something
    hit = defaultdict(list)
    for (x, z) in roads:
        for y in (-1, 0, 1):
            t = world.owner.get((x, y, z))
            b = world.blocks.get((x, y, z))
            if t and t not in ("roads", "lamps", "tram") and b and short(b[0]) != "air" and not short(b[0]).endswith("_fence_gate") and not (y == -1 and world.by_track["roads"].get((x, y, z)) == b):
                hit[t].append((x, y, z))
                break
    for t, ps in sorted(hit.items()):
        out.append(("roads", f"road runs through {t}: {len(ps)} cells, e.g. {sorted(ps)[len(ps) // 2]}"))
    # 2. one connected network
    start = (bld.HEART[0], bld.HEART[2] + 2)
    seen = {start} if start in roads else set()
    queue = deque(seen)
    while queue:
        x, z = queue.popleft()
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                n = (x + dx, z + dz)
                if n in roads and n not in seen:
                    seen.add(n)
                    queue.append(n)
    if len(seen) < len(roads):
        rest = sorted(roads - seen)
        out.append(("roads", f"{len(rest)} road cells not connected to the main road, e.g. {rest[len(rest) // 2]}"))
    # 3. every door has a road at its step
    for track, door, front, back in doors_of(world):
        if track in ("main",) and door[1] > 3:
            continue
        d = (front[0] - door[0], front[2] - door[2])
        near = any((door[0] + d[0] * i + e * d[1], door[2] + d[1] * i + e * d[0]) in seen for i in range(1, 8) for e in (-1, 0, 1))
        if not near and world.owner.get(door) not in ("cellar",):
            out.append(("roads", f"no road to the door of {track} at {door}"))


def check_walk(world, out, quiet=False, start=None):
    grid = Grid(world)
    start = start or (bld.HEART[0] * 2, 0, (bld.HEART[2] + 2) * 2)
    easy = walk(grid, start, 1, False)
    hop = walk(grid, start, 1, True)
    wide = walk(grid, start, 2, False)
    ce, ch, cw = cells_of(easy), cells_of(hop), cells_of(wide, 2)

    def state(p):
        return "ok" if p in cw else "tight" if p in ce else "jump" if p in ch else "no"

    def near(p, r=1):
        best = "no"
        rank = {"ok": 0, "tight": 1, "jump": 2, "no": 3}
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                for dy in (-1, 0, 1):
                    s = state((p[0] + dx, p[1] + dy, p[2] + dz))
                    if rank[s] < rank[best]:
                        best = s
        return best

    words = {"tight": "only fits a player squeezing through (under one block wide)", "jump": "only by jumping", "no": "can't be reached"}
    for track, door, front, back in doors_of(world):
        for label, p in (("door", door), ("inside", back)):
            s = state(p)
            if s in ("jump", "no"):
                out.append(("walk", f"{track}: {label} of the door at {door} {words[s]}"))
                break
    for pos, (name, p) in world.blocks.items():
        n = short(name)
        if n.endswith("_bed") and p.get("part") == "foot" or n in ("chest", "barrel", "enchanting_table", "anvil", "smithing_table",
                                                                   "brewing_stand", "cartography_table", "lectern", "blast_furnace", "grindstone"):
            s = near(pos)
            if s in ("jump", "no") and world.owner[pos] not in OUTSIDE_TRACKS:
                out.append(("walk", f"{world.owner[pos]}: {n} at {pos} {words[s]}"))
    for track, st in world.storage.items():
        if near(st) in ("jump", "no"):
            out.append(("walk", f"{track}: its store at {st} {words[near(st)]}"))
    for track, lvl, name, x, z in read_workers():
        if world.levels.get(track, 0) >= lvl and track != "dock" and track not in OUTSIDE_TRACKS:
            col = [(x, y, z) for y in range(-2, 9)]
            if not any(state(p) in ("ok", "tight") for p in col):
                out.append(("walk", f"{name} ({track}) stands at {x},{z}: a place nobody can walk to"))
    # ladder tops
    for pos in grid.ladders:
        up = (pos[0], pos[1] + 1, pos[2])
        if up not in grid.ladders and state(up) == "no" and state(pos) == "no":
            out.append(("walk", f"{world.owner[pos]}: ladder ending at {pos} can't be reached"))
    # rooms nobody can enter: standable, roofed places that were not reached
    cand = set()
    for pos in world.blocks:
        x, y, z = pos
        for Y in (2 * y + 1, 2 * y + 2):
            for sx in (0, 1):
                for sz in (0, 1):
                    s = (2 * x + sx, Y, 2 * z + sz)
                    if s not in hop and grid.free(*s, 1) and grid.solid(s[0], Y - 1, s[2]):
                        if any((x, yy, z) in world.blocks and mask(world.blocks[(x, yy, z)])[0] for yy in range((Y >> 1) + 2, (Y >> 1) + 6)):
                            cand.add(s)
    seen = set()
    for s in sorted(cand):
        if s in seen:
            continue
        comp, queue = [], deque([s])
        seen.add(s)
        while queue:
            c = queue.popleft()
            comp.append(c)
            for dX, dZ in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                for dY in (-1, 0, 1):
                    n = (c[0] + dX, c[1] + dY, c[2] + dZ)
                    if n in cand and n not in seen:
                        seen.add(n)
                        queue.append(n)
        floor = [short(world.get((c[0] >> 1, (c[1] - 1) >> 1, c[2] >> 1))[0]) for c in comp]
        furniture = sum(f in ("bookshelf", "barrel", "chest", "hay_block", "crafting_table", "furnace", "smoker", "red_nether_bricks",
                              "brewing_stand", "decorated_pot", "candle", "lectern", "copper_chest", "chiseled_bookshelf")
                        or f.endswith(("_log", "_shelf")) or f.startswith("potted_") for f in floor)  # tops of counters and shelves
        if len(comp) >= 12 and furniture * 2 < len(comp):
            xs, ys, zs = [c[0] >> 1 for c in comp], [c[1] >> 1 for c in comp], [c[2] >> 1 for c in comp]
            tr = defaultdict(int)
            for c in comp:
                tr[world.owner.get((c[0] >> 1, (c[1] - 1) >> 1, c[2] >> 1))] += 1
            who = max(tr, key=tr.get)
            if who in OUTSIDE_TRACKS:
                continue  # the tournament field lies outside the walls: the walk from the town doesn't go there
            out.append(("walk", f"{who}: closed-off space of {len(comp) // 4} blocks at x {min(xs)}..{max(xs)}, y {min(ys)}..{max(ys)}, "
                                f"z {min(zs)}..{max(zs)}"))
    return grid, easy


def check_floating(world, out):
    solid = {p for p, b in world.blocks.items() if mask(b)[0] or short(b[0]) in ("ladder",) or short(b[0]).endswith(("_door", "_trapdoor", "_fence_gate", "_banner"))}
    seen = set()
    queue = deque(p for p in solid if p[1] <= 0)
    seen.update(queue)
    while queue:
        x, y, z = queue.popleft()
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    n = (x + dx, y + dy, z + dz)
                    if n in solid and n not in seen:
                        seen.add(n)
                        queue.append(n)
    rest = solid - seen
    groups = defaultdict(list)
    for p in rest:
        groups[world.owner[p]].append(p)
    for t, ps in sorted(groups.items()):
        out.append(("floating", f"{t}: {len(ps)} blocks float, e.g. {sorted(ps)[0]} {short(world.blocks[sorted(ps)[0]][0])}"))


JOBS = {}


class Roadside:
    """One roadside template (tools/roadside.py) on flat ground, the road through x = -1..1: checked like a settlement."""

    def __init__(self, name, blocks):
        self.blocks = {p: v for p, v in blocks.items() if short(v[0]) != "air"}
        self.owner = {p: name for p in self.blocks}
        self.by_track = {name: self.blocks}
        self.storage = {}
        self.levels = {}

    def get(self, p):
        b = self.blocks.get(p)
        return b if b is not None else GROUND if p[1] < 0 else AIR


def check_roadside(styles):
    """Support, floating blocks and the walk from the road (every door, bed, chest reached) of each roadside building."""
    import roadside
    found = {}
    for style, name, blocks, _ in roadside.jobs():
        if style not in styles:
            continue
        world = Roadside(f"{style}/{name}", blocks)
        out = []
        check_support(world, out)
        check_floating(world, out)
        check_walk(world, out, True, (0, 0, -2 * roadside.STRAIGHT))
        for kind, text in out:
            found[(kind, text)] = [0]
    return found


def run(style, mains, quiet=False):
    global BOX
    if style not in JOBS:
        JOBS[style] = bld.build_jobs([style])
    # a round castle (<style>_round): its ring and box
    bld.set_shape("round" if style.endswith("_round") else "square")
    BOX = (bld.WALL[0] - 14, bld.WALL[1] + 14, -30, 50, bld.WALL[2] - 14, bld.WALL[3] + 16)
    found = {}
    for main in mains:
      lv = levels_at(main)
      # also without the side gates: their plugs keep the wall closed (user, 2026-11-25: a plug blocked the walkway)
      for levels in ([lv, dict(lv, postern=0)] if lv.get("postern") else [lv]):
        world = World(style, levels)
        out = []
        check_overlap(world, out)
        check_support(world, out)
        check_roads(world, out)
        check_walk(world, out, quiet)
        check_floating(world, out)
        for kind, text in out:
            found.setdefault((kind, text + ("" if levels is lv else " [no side gates]")), []).append(main)
    return found


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    mains = [int(sys.argv[sys.argv.index("--main") + 1])] if "--main" in sys.argv else [4, 5, 7, 8, 9, 10, 11]
    if "--main" in sys.argv:
        args = [a for a in args if a != str(mains[0])]
    total = 0
    for style in args or list(bld.STYLES) + [st + "_round" for st in bld.STYLES]:
        found = run(style, mains)
        print(f"== {style}: {len(found)} problems")
        for (kind, text), ms in sorted(found.items()):
            tiers = "all tiers" if len(ms) == len(mains) and len(mains) > 1 else "main " + ",".join(map(str, ms))
            print(f"  [{kind}] {text}  ({tiers})")
        total += len(found)
    found = check_roadside(args or list(bld.STYLES))
    print(f"== roadside: {len(found)} problems")
    for (kind, text) in sorted(found):
        print(f"  [{kind}] {text}")
    total += len(found)
    sys.exit(1 if total else 0)
