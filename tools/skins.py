#!/usr/bin/env python3
"""Generates the NPC skins: every region x outfit x variant as a 64x64 player skin.

Regions follow the build styles (temperate = Germanic, frozen = Nordic, highland = Scottish, arid = Moorish,
wild = Southeast Asian): skin tones, hair and eye colours and clothing of the region. Outfits are per job; women's
outfits (7-9) use the slim player model (3-pixel arms).

Skin id in the mod: 100 + (region * OUTFITS + outfit) * VARIANTS + variant (see KingdomNpc.generated).
Output: src/main/resources/assets/kingdomquest/textures/entity/npc/<id>.png. Run after any change and commit the result.
"""
import os
import random
import zlib

from PIL import Image

REGIONS = ["temperate", "frozen", "highland", "arid", "wild"]
OUTFITS = ["guard", "peasant", "smith", "burgher", "scholar", "builder", "captain", "housewife", "worker_woman", "cook_woman"]
FEMALE = {7, 8, 9}
VARIANTS = 4
BASE = 100
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "kingdomquest", "textures", "entity", "npc")

SKIN = {
    "temperate": [(233, 196, 166), (222, 182, 150), (240, 207, 182), (226, 188, 158)],
    "frozen": [(246, 218, 199), (239, 206, 184), (250, 226, 208), (242, 212, 192)],
    "highland": [(241, 205, 184), (233, 193, 168), (246, 213, 192), (236, 198, 175)],
    "arid": [(196, 146, 106), (176, 126, 88), (160, 110, 76), (208, 160, 118)],
    "wild": [(194, 144, 98), (178, 128, 84), (206, 158, 110), (168, 118, 78)],
}
HAIR = {
    "temperate": [(104, 74, 47), (72, 50, 32), (150, 112, 70), (212, 178, 108)],
    "frozen": [(232, 204, 138), (240, 224, 176), (212, 150, 88), (178, 86, 42)],
    "highland": [(170, 74, 36), (130, 62, 36), (98, 68, 44), (58, 42, 30)],
    "arid": [(32, 26, 24), (54, 38, 28), (40, 30, 26), (28, 24, 24)],
    "wild": [(24, 22, 24), (32, 30, 38), (28, 24, 22), (36, 30, 28)],
}
EYES = {
    "temperate": [(70, 110, 170), (92, 64, 40), (80, 120, 80), (70, 110, 170)],
    "frozen": [(80, 130, 190), (110, 160, 210), (110, 120, 130), (80, 130, 190)],
    "highland": [(70, 120, 170), (80, 120, 80), (92, 64, 40), (70, 120, 170)],
    "arid": [(60, 40, 28), (72, 48, 30), (50, 34, 24), (60, 40, 28)],
    "wild": [(50, 34, 24), (40, 28, 20), (60, 40, 28), (50, 34, 24)],
}
# cloth colours of each region: wool and linen of the land
CLOTH = {
    "temperate": [(84, 106, 62), (128, 52, 42), (70, 82, 118), (122, 94, 62), (96, 70, 50)],
    "frozen": [(78, 96, 120), (112, 112, 118), (128, 56, 46), (88, 104, 84), (140, 128, 104)],
    "highland": [(62, 84, 58), (110, 46, 40), (58, 64, 96), (104, 88, 62), (82, 72, 60)],
    "arid": [(224, 214, 190), (46, 62, 120), (186, 120, 60), (150, 52, 44), (208, 190, 150)],
    "wild": [(170, 110, 40), (120, 40, 50), (40, 70, 110), (64, 110, 80), (200, 150, 70)],
}
LINEN = (214, 200, 168)
WHITE = (236, 232, 222)
LEATHER = (118, 80, 46)
DARK_LEATHER = (74, 52, 34)
BRASS = (196, 160, 72)


def clamp(c):
    return tuple(max(0, min(255, int(v))) for v in c)


def shade(c, f):
    return clamp((c[0] * f, c[1] * f, c[2] * f))


def mix(a, b, t):
    return clamp(tuple(a[i] * (1 - t) + b[i] * t for i in range(3)))


class Skin:
    def __init__(self, rnd, slim):
        self.img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        self.rnd = rnd
        self.slim = slim

    def put(self, x, y, c, grain=6):
        n = self.rnd.randint(-grain, grain)
        self.img.putpixel((x, y), clamp((c[0] + n, c[1] + n, c[2] + n)) + (255,))

    def cuboid(self, u, v, w, h, d, paint):
        """Paints a box of the player model; paint(face, x, y, fw, fh) gives (colour, grain)."""
        faces = {
            "top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h),
        }
        for face, (fx, fy, fw, fh) in faces.items():
            for y in range(fh):
                for x in range(fw):
                    c, g = paint(face, x, y, fw, fh)
                    if c is None:
                        continue
                    edge = face in ("right", "left") and 1 or 0
                    f = 0.93 if edge else 1.0
                    if face == "bottom":
                        f = 0.85
                    self.put(fx + x, fy + y, shade(c, f), g)


def tartan(x, y, base):
    a = (x % 4 in (0,)) or (y % 4 == 0)
    b = (x % 4 == 2) and (y % 4 == 2)
    if a and (y % 4 == 0 and x % 4 == 0):
        return shade(base, 0.55)
    if a:
        return shade(base, 0.75)
    if b:
        return mix(base, (200, 40, 40), 0.5)
    return base


def make(region, outfit, variant):
    rnd = random.Random(zlib.crc32(f"{region}|{outfit}|{variant}".encode()))
    female = outfit in FEMALE
    sk = Skin(rnd, female)
    skin = SKIN[region][variant % 4]
    hair = HAIR[region][(variant * 3 + outfit) % 4]
    eye = EYES[region][(variant + outfit) % 4]
    cloth = CLOTH[region]
    c1 = cloth[(outfit + variant) % len(cloth)]
    c2 = cloth[(outfit * 2 + variant + 2) % len(cloth)]
    lip = shade(skin, 0.82)
    beard = not female and variant in (1, 3) and outfit not in (0,)
    freckles = region == "highland" and variant in (0, 2)

    # ---- head covering
    cover = None  # colour of a coif, headwrap or headscarf
    cover_kind = None
    if female and (outfit in (7, 9) or region == "arid"):
        cover_kind = "scarf" if region in ("arid",) else "coif"
        cover = WHITE if cover_kind == "coif" else cloth[(variant + 1) % len(cloth)]
    elif region == "arid" and outfit in (1, 3, 5) and not female:
        cover_kind = "wrap"
        cover = [WHITE, (210, 196, 160), (60, 70, 120), WHITE][variant]
    long_hair = female and cover_kind is None

    def hair_at(face, x, y):
        """Hair (or covering) colour on the head, or None for skin."""
        if cover_kind == "coif" or cover_kind == "scarf":
            if face in ("top", "back", "left", "right"):
                return cover
            if face == "front" and (y <= 1 or x in (0, 7) and y <= 6):
                return cover
            return None
        if cover_kind == "wrap":
            if face == "top":
                return cover
            if face == "front" and y <= 1:
                return cover if y == 0 else shade(cover, 0.85)
            if face in ("left", "right") and y <= 3:
                return cover
            if face == "back" and y <= 4:
                return cover
            if face == "back" and y <= 5:
                return hair
        if face == "top":
            return hair
        if face == "front":
            if y == 0 or y == 1 and (x in (0, 7) or (x + variant) % 5 == 0):
                return hair
            if long_hair and x in (0, 7) and y <= 6:
                return hair
            return None
        if face in ("left", "right"):
            if long_hair:
                return hair
            front_edge = (x == 7) if face == "right" else (x == 0)  # side columns next to the face
            if y <= 2 or y == 3 and not front_edge:
                return hair
            return None
        if face == "back":
            return hair if y <= (7 if long_hair else 5) else None
        return None

    def head(face, x, y, fw, fh):
        h = hair_at(face, x, y)
        if h is not None:
            g = 4 if h in (WHITE, cover) else 7
            # strands: a darker streak now and then
            if h == hair and (x + y * 3 + variant) % 7 == 0:
                h = shade(h, 0.82)
            return h, g
        c = skin
        if face == "bottom":
            return shade(skin, 0.9), 2
        if face == "front":
            if y == 3 and x in (1, 2, 5, 6):
                return shade(hair, 0.9) if cover_kind != "coif" else shade(hair, 0.9), 3
            if y == 4:
                if x in (1, 6):
                    return (240, 240, 240), 2
                if x in (2, 5):
                    return eye, 4
            if y == 5 and x in (3, 4):
                return shade(skin, 0.9), 2
            if y == 6 and x in (3, 4):
                return lip, 2
            if beard and (y == 7 or y == 6 and x not in (3, 4)):
                return shade(hair, 0.9), 6
            if freckles and y == 5 and x in (1, 2, 5, 6) and (x + variant) % 2 == 0:
                return shade(skin, 0.85), 2
            if y == 7:
                return shade(skin, 0.95), 2
        if face in ("left", "right") and beard and y >= 6:
            fx = x if face == "left" else 7 - x
            if fx <= 3:
                return shade(hair, 0.9), 6
        return c, 3

    # ---- body, arms and legs by outfit
    tunic, sleeve, legs, boots, belt = c1, c1, (86, 72, 58), DARK_LEATHER, LEATHER
    apron = None
    robe = False
    skirt = None
    short_sleeves = False
    rolled = False
    trim = None
    if outfit == 0:  # guard: padded gambeson (under the armour)
        tunic = sleeve = (156, 136, 104)
        legs = (82, 72, 62)
    elif outfit == 1:  # peasant
        tunic = sleeve = LINEN if variant % 2 == 0 else c1
        legs = (112, 86, 60)
        belt = (150, 130, 90)
        short_sleeves = region in ("arid", "wild")
    elif outfit == 2:  # smith
        tunic = sleeve = (88, 80, 72)
        apron = LEATHER
        rolled = True
        legs = (70, 62, 54)
    elif outfit == 3:  # burgher
        tunic, sleeve, legs = c1, c1, c2
        trim = shade(c1, 0.7)
    elif outfit == 4:  # scholar: a long robe
        tunic = sleeve = [(52, 48, 70), (96, 70, 48), (40, 40, 44), (70, 40, 40)][variant]
        robe = True
        belt = (160, 140, 100)
    elif outfit == 5:  # builder
        tunic = sleeve = (178, 158, 120)
        legs = (98, 82, 60)
        belt = LEATHER
        rolled = True
    elif outfit == 6:  # captain
        tunic = sleeve = c1
        legs = (60, 50, 44)
        trim = WHITE
        boots = (50, 36, 26)
    elif outfit == 7:  # housewife: a kirtle
        tunic = sleeve = c1
        skirt = c1
    elif outfit == 8:  # working woman: kirtle and apron
        tunic = sleeve = c2
        skirt = c2
        apron = LINEN
        rolled = variant % 2 == 1
    elif outfit == 9:  # baker, innkeeper: white apron
        tunic = sleeve = c1
        skirt = c1
        apron = WHITE
    if region == "arid" and outfit in (1, 3) and not female:
        robe = True  # a long robe, as worn there
        tunic = sleeve = [WHITE, (214, 200, 160), (200, 190, 170), c1][variant]
    fur = region == "frozen" and outfit not in (0, 2)
    barefoot = region == "wild" and outfit in (1, 8) and variant % 2 == 0
    sarong = region == "wild" and outfit in (1, 3, 7, 8)
    kilt = region == "highland" and outfit in (1, 6) and not female

    def body(face, x, y, fw, fh):
        if face == "top":
            return (fur and (230, 222, 200)) or tunic, 6
        if face == "bottom":
            return (skirt or (robe and tunic) or legs), 6
        if long_hair and face == "back" and y <= 4 and 1 <= x <= 6:
            return hair, 7
        if fur and y == 0:
            return (226, 218, 196), 10
        if trim is not None and face == "front" and x in (3, 4) and y <= 7:
            return trim, 4
        if apron is not None and face == "front" and y >= (3 if outfit == 2 else 5):
            return apron, 6
        if not female and not robe and y == 8:
            if face == "front" and x in (3, 4):
                return BRASS, 3
            return belt, 4
        if robe and y == 8:
            return belt, 4
        if outfit == 0 and y % 3 == 1:
            return shade(tunic, 0.82), 4  # the quilting
        if region == "highland" and female and y <= 3 and (face in ("front", "back") and (x <= 2 or x >= 5) or face in ("left", "right")):
            return tartan(x, y, c2), 4  # the shawl
        return tunic, 8

    def arm(face, x, y, fw, fh):
        if face == "bottom":
            return skin, 2
        if face == "top":
            return (fur and (230, 222, 200)) or sleeve, 6
        hand = y >= 10
        if hand:
            return skin, 3
        if short_sleeves and y >= 4 or rolled and y >= 5:
            return skin, 3
        if fur and y in (8, 9):
            return (226, 218, 196), 10
        if trim is not None and y == 9:
            return trim, 4
        return sleeve, 8

    def leg(face, x, y, fw, fh):
        if face == "top":
            return (skirt or (robe and tunic) or legs), 6
        if face == "bottom":
            return (skin if barefoot else boots), 3
        if barefoot and y >= 10:
            return skin, 3
        if skirt is not None:
            if y == 11:
                return boots, 4
            if apron is not None and face == "front" and y <= 8:
                return apron, 6
            if sarong:
                return tartan(x + 1, y, skirt) if y <= 10 else boots, 5
            return skirt, 8
        if robe:
            if y >= 11:
                return boots, 4
            return tunic, 8
        if apron is not None and face == "front" and y <= 7:
            return apron, 6
        if kilt:
            if y <= 4:
                return tartan(x, y, c2), 4
            if y <= 8:
                return (196, 184, 156), 6  # hose
        if sarong and y <= 9:
            return tartan(x + 2, y, c2), 5
        if y >= 9:
            if fur and y == 9:
                return (226, 218, 196), 10
            return boots, 4
        return legs, 8

    aw = 3 if female else 4
    sk.cuboid(0, 0, 8, 8, 8, head)
    sk.cuboid(16, 16, 8, 12, 4, body)
    sk.cuboid(40, 16, aw, 12, 4, arm)
    sk.cuboid(32, 48, aw, 12, 4, arm)
    sk.cuboid(0, 16, 4, 12, 4, leg)
    sk.cuboid(16, 48, 4, 12, 4, leg)
    return sk.img


def skin_id(region, outfit, variant):
    return BASE + (region * len(OUTFITS) + outfit) * VARIANTS + variant


def main():
    os.makedirs(OUT, exist_ok=True)
    n = 0
    for r, region in enumerate(REGIONS):
        for o in range(len(OUTFITS)):
            for v in range(VARIANTS):
                img = make(region, o, v)
                img.save(os.path.join(OUT, f"{skin_id(r, o, v)}.png"), optimize=True)
                n += 1
    print(n, "skins written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
