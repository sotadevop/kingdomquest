"""Renders a part of an assembled settlement (see check.py) as an isometric picture.

Run: KQ_FETCH=1 python3 tools/preview.py <style> <main level> <out.png> [track ...] [--rot 0..3] [--scale N] [--box x0,z0,x1,z1]
With tracks, the picture shows their area (plus a margin) with everything else that stands there.
"""
import os, sys

sys.path.insert(0, os.path.dirname(__file__))
import check
from render import render

JOIN = ("_fence", "_wall", "glass_pane", "iron_bars")


def connect(blocks):
    """Fences, walls, panes and bars join their neighbours, as the mod does when it places them."""
    out = {}
    for pos, (name, props) in blocks.items():
        n = check.short(name)
        if n.endswith(JOIN) and not n.endswith("_banner"):
            p = dict(props)
            wall = n.endswith("_wall")
            for d, (dx, dz) in check.DIRS.items():
                o = blocks.get((pos[0] + dx, pos[1], pos[2] + dz))
                on = o is not None and (check.mask(o)[0] == 0xFF and not check.short(o[0]).endswith("lantern") or check.short(o[0]).endswith("_fence_gate"))
                p[d] = ("low" if on else "none") if wall else str(on).lower()
            if wall:
                straight = (p["north"] != "none" and p["south"] != "none" and p["east"] == "none" and p["west"] == "none") or \
                           (p["east"] != "none" and p["west"] != "none" and p["north"] == "none" and p["south"] == "none")
                above = blocks.get((pos[0], pos[1] + 1, pos[2]))
                p["up"] = "false" if straight and above is None else "true"
            out[pos] = (name, p)
        else:
            out[pos] = (name, props)
    return out


def picture(world, box, rot=0, scale=3, cut_above=None):
    x0, z0, x1, z1 = box
    blocks = {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            blocks[(x, -1, z)] = ("minecraft:grass_block", {"snowy": "false"})
    for (x, y, z), (name, props) in world.blocks.items():
        if x0 <= x <= x1 and z0 <= z <= z1 and (cut_above is None or y <= cut_above):
            if check.short(name) == "air":
                blocks.pop((x, y, z), None)
            else:
                blocks[(x, y, z)] = (name.replace("{color}", "minecraft:blue"), props)
    blocks = connect(blocks)
    ys = [p[1] for p in blocks]
    shifted = {(x - x0, y - min(ys), z - z0): v for (x, y, z), v in blocks.items()}
    size = (x1 - x0 + 1, max(ys) - min(ys) + 1, z1 - z0 + 1)
    return render(shifted, size, rot, scale, bg=(170, 200, 230, 255))


if __name__ == "__main__":
    a = sys.argv[1:]
    opt = {}
    for k in ("--rot", "--scale", "--box", "--cut"):
        if k in a:
            i = a.index(k)
            opt[k] = a[i + 1]
            del a[i:i + 2]
    style, main, out, tracks = a[0], int(a[1]), a[2], a[3:]
    check.JOBS[style] = check.bld.build_jobs([style])
    world = check.World(style, check.levels_at(main))
    if "--box" in opt:
        box = tuple(int(v) for v in opt["--box"].split(","))
    else:
        ps = [p for t in (tracks or world.by_track) for p in world.by_track[t]]
        box = (min(p[0] for p in ps) - 3, min(p[2] for p in ps) - 3, max(p[0] for p in ps) + 3, max(p[2] for p in ps) + 3)
    img = picture(world, box, int(opt.get("--rot", 0)), int(opt.get("--scale", 3)), int(opt["--cut"]) if "--cut" in opt else None)
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    img.save(out)
    print(out, img.size)
