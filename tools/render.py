"""Isometric renderer for Minecraft structure .nbt files using vanilla block models.

Usage: python3 render.py <structure.nbt> <out.png> [--rot 0..3] [--scale N] [--ground]
"""
import json, math, os, sys, argparse
from functools import lru_cache
import nbtlib
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), "..", "ref", "assets", "assets", "minecraft")
GRASS_TINT = (124, 189, 107)
FOLIAGE_TINT = (119, 171, 47)
WATER_TINT = (63, 118, 228)
SPRUCE_TINT = (97, 153, 97)
BIRCH_TINT = (128, 167, 85)
SHADE = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.6, "west": 0.6}
SKIP = {"minecraft:air", "minecraft:cave_air", "minecraft:structure_void", "minecraft:jigsaw", "minecraft:barrier"}


def strip(name):
    return name.split(":", 1)[-1]


@lru_cache(maxsize=None)
def load_json(kind, name):
    path = os.path.join(ASSETS, kind, strip(name) + ".json")
    with open(path) as f:
        return json.load(f)


@lru_cache(maxsize=None)
def resolve_model(name):
    """Return (elements, textures) with parents merged."""
    m = load_json("models", name)
    elements, textures = m.get("elements"), dict(m.get("textures", {}))
    parent = m.get("parent")
    while parent and not strip(parent).startswith("builtin"):
        p = load_json("models", parent)
        if elements is None:
            elements = p.get("elements")
        for k, v in p.get("textures", {}).items():
            textures.setdefault(k, v)
        parent = p.get("parent")
    return elements, textures


def tex_ref(textures, ref, depth=0):
    if isinstance(ref, dict):
        ref = ref.get("sprite", "")
    while ref.startswith("#") and depth < 10:
        ref = textures.get(ref[1:], "")
        if isinstance(ref, dict):
            ref = ref.get("sprite", "")
        depth += 1
    return ref


@lru_cache(maxsize=None)
def load_texture(ref, tint=None):
    path = os.path.join(ASSETS, "textures", strip(ref) + ".png")
    if not os.path.exists(path):
        return None
    img = Image.open(path).convert("RGBA")
    if img.height > img.width:  # animated strip: first frame
        img = img.crop((0, 0, img.width, img.width))
    if img.width != 16:
        img = img.resize((16, 16), Image.NEAREST)
    if tint:
        r, g, b, a = img.split()
        r = r.point(lambda v: v * tint[0] // 255)
        g = g.point(lambda v: v * tint[1] // 255)
        b = b.point(lambda v: v * tint[2] // 255)
        img = Image.merge("RGBA", (r, g, b, a))
    return img


def matches(cond, props):
    if "OR" in cond:
        return any(matches(c, props) for c in cond["OR"])
    if "AND" in cond:
        return all(matches(c, props) for c in cond["AND"])
    for k, v in cond.items():
        if str(props.get(k, "")) not in str(v).split("|"):
            return False
    return True


def models_for(name, props):
    try:
        bs = load_json("blockstates", name)
    except FileNotFoundError:
        return []
    out = []
    if "variants" in bs:
        for key, val in bs["variants"].items():
            ok = True
            if key:
                for part in key.split(","):
                    k, v = part.split("=")
                    if str(props.get(k)) != v:
                        ok = False
                        break
            if ok:
                out.append(val[0] if isinstance(val, list) else val)
                break
    for part in bs.get("multipart", []):
        if "when" not in part or matches(part["when"], props):
            a = part["apply"]
            out.append(a[0] if isinstance(a, list) else a)
    return out


def rot_point(p, rx, ry):
    x, y, z = p
    for _ in range((rx // 90) % 4):  # rotate around X axis: up -> north
        y, z = z, 16 - y
    for _ in range((ry // 90) % 4):  # rotate around Y axis clockwise seen from above
        x, z = 16 - z, x
    return (x, y, z)


ROT_DIR_X = {"up": "north", "north": "down", "down": "south", "south": "up", "east": "east", "west": "west"}
ROT_DIR_Y = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}


def rot_dir(d, rx, ry):
    for _ in range((rx // 90) % 4):
        d = ROT_DIR_X[d]
    for _ in range((ry // 90) % 4):
        d = ROT_DIR_Y[d]
    return d


def tint_for(name, tintindex):
    if tintindex is None:
        return None
    n = strip(name)
    if "spruce_leaves" in n:
        return SPRUCE_TINT
    if "birch_leaves" in n:
        return BIRCH_TINT
    if "leaves" in n or "vine" in n:
        return FOLIAGE_TINT
    if "water" in n:
        return WATER_TINT
    return GRASS_TINT


def face_corners(f, t, d):
    """Corners of a face in 1/16 units, ordered (top-left, top-right, bottom-left) in texture space."""
    x1, y1, z1 = f
    x2, y2, z2 = t
    if d == "up":
        return (x1, y2, z1), (x2, y2, z1), (x1, y2, z2)
    if d == "down":
        return (x1, y1, z2), (x2, y1, z2), (x1, y1, z1)
    if d == "north":
        return (x2, y2, z1), (x1, y2, z1), (x2, y1, z1)
    if d == "south":
        return (x1, y2, z2), (x2, y2, z2), (x1, y1, z2)
    if d == "west":
        return (x1, y2, z1), (x1, y2, z2), (x1, y1, z1)
    if d == "east":
        return (x2, y2, z2), (x2, y2, z1), (x2, y1, z2)


def default_uv(f, t, d):
    x1, y1, z1 = f
    x2, y2, z2 = t
    if d in ("up", "down"):
        return [x1, z1, x2, z2]
    if d in ("north", "south"):
        return [x1, 16 - y2, x2, 16 - y1]
    return [z1, 16 - y2, z2, 16 - y1]


def collect_faces(blocks, rot):
    """blocks: dict pos->(name, props). Returns list of faces (depth, pts3d, texture image, shade)."""
    faces = []
    solid = set()
    for pos, (name, props) in blocks.items():
        for mv in models_for(name, props):
            els, _ = resolve_model(mv["model"])
            if els and len(els) == 1 and els[0]["from"] == [0, 0, 0] and els[0]["to"] == [16, 16, 16]:
                tex = load_texture(tex_ref(resolve_model(mv["model"])[1], "#all")) if False else None
                solid.add(pos)
    for (bx, by, bz), (name, props) in blocks.items():
        mlist = models_for(name, props)
        drew = False
        for mv in mlist:
            els, textures = resolve_model(mv["model"])
            rx, ry = mv.get("x", 0), mv.get("y", 0)
            if not els:
                continue
            for el in els:
                f, t = el["from"], el["to"]
                for d, fd in el.get("faces", {}).items():
                    cull = fd.get("cullface")
                    wd = rot_dir(d, rx, ry)
                    if cull:
                        cd = rot_dir(cull, rx, ry)
                        off = {"up": (0, 1, 0), "down": (0, -1, 0), "north": (0, 0, -1), "south": (0, 0, 1), "east": (1, 0, 0), "west": (-1, 0, 0)}[cd]
                        if (bx + off[0], by + off[1], bz + off[2]) in solid and (bx, by, bz) in solid:
                            continue
                        if (bx + off[0], by + off[1], bz + off[2]) in solid:
                            continue
                    ref = tex_ref(textures, fd["texture"])
                    img = load_texture(ref, tint_for(name, fd.get("tintindex")))
                    if img is None:
                        continue
                    uv = fd.get("uv") or default_uv(f, t, d)
                    u1, v1, u2, v2 = uv
                    crop = img.crop((min(u1, u2), min(v1, v2), max(u1, u2) or 1, max(v1, v2) or 1)) if (u1 != u2 and v1 != v2) else img.crop((0, 0, 1, 1))
                    if u1 > u2:
                        crop = crop.transpose(Image.FLIP_LEFT_RIGHT)
                    if v1 > v2:
                        crop = crop.transpose(Image.FLIP_TOP_BOTTOM)
                    if fd.get("rotation"):
                        crop = crop.rotate(-fd["rotation"], expand=True)
                    c = face_corners(f, t, d)
                    pts = [rot_point(p, rx, ry) for p in c]
                    pts = [(bx * 16 + p[0], by * 16 + p[1], bz * 16 + p[2]) for p in pts]
                    faces.append((wd, pts, crop, (bx, by, bz)))
                    drew = True
        if not drew:
            faces.extend(fallback_faces(name, props, bx, by, bz))
    return faces


def fallback_faces(name, props, bx, by, bz):
    """Builtin-rendered blocks (chests, beds, banners, signs) and fluids: simple boxes."""
    n = strip(name)
    tex, box = None, ((0, 0, 0), (16, 16, 16))
    if "water" in n:
        tex = load_texture("block/water_still", WATER_TINT)
        box = ((0, 0, 0), (16, 14, 16))
    elif "lava" in n:
        tex = load_texture("block/lava_still")
    elif "chest" in n:
        tex = load_texture("block/oak_planks")
        box = ((1, 0, 1), (15, 14, 15))
    elif n.endswith("_bed"):
        color = n[: -len("_bed")]
        tex = load_texture(f"block/{color}_wool")
        box = ((0, 0, 0), (16, 9, 16))
    elif n.endswith("banner"):
        color = n.split("_wall_banner")[0].split("_banner")[0]
        cloth = load_texture(f"block/{color}_wool")
        pole = load_texture("block/oak_planks")
        out = []
        along_x = props.get("rotation", "0") in ("4", "5", "6", "12", "13", "14") or props.get("facing") in ("east", "west")
        if "wall" in n:
            parts = [(cloth, (1, -14, 14), (15, 14, 15))] if not along_x else [(cloth, (14, -14, 1), (15, 14, 15))]
        else:
            parts = [(pole, (7, 0, 7), (9, 30, 9))]
            parts.append((cloth, (1, 2, 9), (15, 28, 10)) if not along_x else (cloth, (9, 2, 1), (10, 28, 15)))
        for img, f, t in parts:
            for d in ("up", "north", "south", "east", "west"):
                c = face_corners(f, t, d)
                pts = [(bx * 16 + p[0], by * 16 + p[1], bz * 16 + p[2]) for p in c]
                out.append((d, pts, img, (bx, by, bz)))
        return out
    elif "sign" in n:
        tex = load_texture("block/oak_planks")
        box = ((2, 6, 7), (14, 14, 9))
    if tex is None:
        return []
    out = []
    f, t = box
    for d in ("up", "north", "south", "east", "west"):
        c = face_corners(f, t, d)
        pts = [(bx * 16 + p[0], by * 16 + p[1], bz * 16 + p[2]) for p in c]
        out.append((d, pts, tex, (bx, by, bz)))
    return out


def rotate_world(blocks, size, rot):
    """Rotate block positions around Y so different sides face the camera. Block states are rotated via 'facing'-less approach: we rotate the camera instead."""
    return blocks


def project(p, rot, size):
    x, y, z = p
    sx, sz = size[0] * 16, size[2] * 16
    for _ in range(rot):
        x, z = sz - z, x
        sx, sz = sz, sx
    return (x - z) * math.cos(math.radians(30)), (x + z) * 0.5 - y


def render(blocks, size, rot=0, scale=2, bg=(0, 0, 0, 0)):
    faces = collect_faces(blocks, rot)
    # camera looks from +x +z (after rotation) and above
    turn = {"east": "south", "south": "west", "west": "north", "north": "east", "up": "up", "down": "down"}
    items = []
    for d, pts, img, bpos in faces:
        for _ in range(rot):
            d = turn[d]
        if d not in ("up", "south", "east"):
            continue
        cx = sum(p[0] for p in pts[:3]) / 3
        cy = sum(p[1] for p in pts[:3]) / 3
        cz = sum(p[2] for p in pts[:3]) / 3
        # complete the 4th corner implicitly; depth along view direction
        rp = project((cx, cy, cz), rot, size)
        x, z = cx, cz
        sx, sz = size[0] * 16, size[2] * 16
        for _ in range(rot):
            x, z = sz - z, x
            sx, sz = sz, sx
        bxr, bzr = bpos[0], bpos[2]
        sbx, sbz = size[0], size[2]
        for _ in range(rot):
            bxr, bzr = sbz - 1 - bzr, bxr
            sbx, sbz = sbz, sbx
        depth = (bxr + bzr + bpos[1], x + z + cy)
        items.append((depth, d, pts, img))
    items.sort(key=lambda it: it[0])
    projs = []
    for depth, d, pts, img in items:
        q = [project(p, rot, size) for p in pts]
        projs.append(q)
    if not projs:
        return Image.new("RGBA", (10, 10), bg)
    xs = [p[0] for q in projs for p in q] + [q[1][0] + q[2][0] - q[0][0] for q in projs]
    ys = [p[1] for q in projs for p in q] + [q[1][1] + q[2][1] - q[0][1] for q in projs]
    minx, miny = min(xs), min(ys)
    W = int((max(xs) - minx) * scale) + 4
    H = int((max(ys) - miny) * scale) + 4
    canvas = Image.new("RGBA", (W, H), bg)
    for (depth, d, pts, img), q in zip(items, projs):
        o = ((q[0][0] - minx) * scale + 2, (q[0][1] - miny) * scale + 2)
        u = ((q[1][0] - q[0][0]) * scale, (q[1][1] - q[0][1]) * scale)
        v = ((q[2][0] - q[0][0]) * scale, (q[2][1] - q[0][1]) * scale)
        det = u[0] * v[1] - u[1] * v[0]
        if abs(det) < 1e-6:
            continue
        bx0 = math.floor(min(o[0], o[0] + u[0], o[0] + v[0], o[0] + u[0] + v[0]))
        by0 = math.floor(min(o[1], o[1] + u[1], o[1] + v[1], o[1] + u[1] + v[1]))
        bx1 = math.ceil(max(o[0], o[0] + u[0], o[0] + v[0], o[0] + u[0] + v[0]))
        by1 = math.ceil(max(o[1], o[1] + u[1], o[1] + v[1], o[1] + u[1] + v[1]))
        w, h = max(1, bx1 - bx0), max(1, by1 - by0)
        tw, th = img.size
        # inverse affine: output (X,Y) -> (a,b) in [0,1]^2 -> texture (a*tw, b*th)
        ia = v[1] / det
        ib = -v[0] / det
        ic = -u[1] / det
        id_ = u[0] / det
        ox, oy = o[0] - bx0, o[1] - by0
        A = ia * tw
        B = ib * tw
        C = -(ia * ox + ib * oy) * tw
        D = ic * th
        E = id_ * th
        F = -(ic * ox + id_ * oy) * th
        shade = SHADE.get(d, 1.0)
        src = img
        if shade != 1.0:
            r, g, b, a = img.split()
            src = Image.merge("RGBA", tuple(ch.point(lambda val: int(val * shade)) for ch in (r, g, b)) + (a,))
        # mask: transparent outside the parallelogram (PIL fills outside with 0 alpha)
        warped = src.transform((w, h), Image.AFFINE, (A, B, C, D, E, F), resample=Image.NEAREST, fillcolor=(0, 0, 0, 0))
        canvas.alpha_composite(warped, (bx0, by0))
    return canvas


def load_structure(path):
    nbt = nbtlib.load(path)
    root = nbt if "blocks" in nbt else nbt[""]
    palette = root["palette"] if "palette" in root else root["palettes"][0]
    pal = []
    for e in palette:
        props = {str(k): str(v) for k, v in (e.get("Properties") or e.get("properties") or {}).items()}
        pal.append((str(e["Name"] if "Name" in e else e["id"]), props))
    blocks = {}
    for b in root["blocks"]:
        name, props = pal[int(b["state"])]
        if name in SKIP:
            continue
        p = tuple(int(c) for c in b["pos"])
        blocks[p] = (name, props)
    size = tuple(int(c) for c in root["size"])
    return blocks, size


def add_ground(blocks, size, pad=2):
    out = dict(blocks)
    for x in range(-pad, size[0] + pad):
        for z in range(-pad, size[2] + pad):
            if (x, 0, z) not in out:
                out[(x, -1, z)] = ("minecraft:grass_block", {"snowy": "false"})
            else:
                out[(x, -1, z)] = ("minecraft:dirt", {})
    return out


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("nbt")
    ap.add_argument("out")
    ap.add_argument("--rot", type=int, default=0)
    ap.add_argument("--scale", type=int, default=2)
    ap.add_argument("--ground", action="store_true")
    a = ap.parse_args()
    blocks, size = load_structure(a.nbt)
    if a.ground:
        blocks = add_ground(blocks, size)
    img = render(blocks, size, a.rot, a.scale, bg=(170, 200, 230, 255))
    img.save(a.out)
    print(a.out, img.size, len(blocks), "blocks")
