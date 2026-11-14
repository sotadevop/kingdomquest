"""Encampment tent level 1, Temperate (Central European) style.

Coordinates: x east, y up, z south. y=0 is the first layer above the ground.
The camp faces south: tent entrance at the front (high z), campfire in front of it.
"""
import sys
from render import render, add_ground
import nbtlib
from nbtlib.tag import Compound, List, Int, String

COLOR = sys.argv[1] if len(sys.argv) > 1 else "blue"

B = {}


def put(x, y, z, name, **props):
    B[(x, y, z)] = ("minecraft:" + name, {k: str(v).lower() for k, v in props.items()})


# --- tent: A-frame canvas along z, 5 wide (x 1..5), 5 long (z 1..5), hollow inside ---
X0 = 1
CANVAS_STAIRS = "smooth_quartz_stairs"
for z in range(1, 6):
    st = dict(half="bottom", shape="straight", waterlogged="false")
    put(X0 + 0, 0, z, "polished_diorite_stairs", facing="east", **st)  # dirtier hem at the bottom
    put(X0 + 1, 1, z, CANVAS_STAIRS, facing="east", **st)
    put(X0 + 4, 0, z, "polished_diorite_stairs", facing="west", **st)
    put(X0 + 3, 1, z, CANVAS_STAIRS, facing="west", **st)
    put(X0 + 2, 2, z, "smooth_quartz_slab", type="bottom", waterlogged="false")
# back gable closed with canvas (wool), a darker hem at the bottom
put(X0 + 1, 0, 1, "white_wool")
put(X0 + 2, 0, 1, "white_wool")
put(X0 + 3, 0, 1, "white_wool")
put(X0 + 2, 1, 1, "white_wool")
# ridge pole tips sticking out front and back
put(X0 + 2, 2, 0, "spruce_fence")
put(X0 + 2, 2, 6, "spruce_fence")
# front flaps: open trapdoors at the entrance edges (canvas pulled aside)
put(X0 + 1, 0, 6, "spruce_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
put(X0 + 3, 0, 6, "spruce_trapdoor", facing="south", half="bottom", open="true", powered="false", waterlogged="false")
# guy ropes / pegs at the sides
for z in (1, 5):
    put(X0 - 1, 0, z, "tripwire_hook", facing="west", attached="false", powered="false")
    put(X0 + 5, 0, z, "tripwire_hook", facing="east", attached="false", powered="false")

# --- inside ---
put(X0 + 2, 1, 2, "lantern", hanging="true", waterlogged="false")
put(X0 + 1, 0, 2, f"{COLOR}_bed", facing="north", part="head", occupied="false")  # the one color accent
put(X0 + 1, 0, 3, f"{COLOR}_bed", facing="north", part="foot", occupied="false")
put(X0 + 3, 0, 2, "barrel", facing="up", open="false")
put(X0 + 3, 0, 3, "chest", facing="west", type="single", waterlogged="false")

# --- camp in front ---
FX, FZ = X0 + 2, 9
put(FX, 0, FZ, "campfire", facing="south", lit="true", signal_fire="false", waterlogged="false")
# seats around the fire: a split-log bench (slabs on a stair base) and a stool
st = dict(half="bottom", shape="straight", waterlogged="false")
put(FX - 2, 0, FZ, "spruce_stairs", facing="west", **st)
put(FX - 2, 0, FZ + 1, "spruce_stairs", facing="west", **st)
put(FX + 2, 0, FZ - 1, "spruce_slab", type="bottom", waterlogged="false")
put(FX + 2, 0, FZ, "spruce_slab", type="bottom", waterlogged="false")
put(FX + 1, 0, FZ + 2, "oak_trapdoor", facing="north", half="top", open="false", powered="false", waterlogged="false")
# banner pole: fence post with the kingdom banner on top
put(X0 + 6, 0, 7, "spruce_fence")
put(X0 + 6, 1, 7, "spruce_fence")
put(X0 + 6, 2, 7, f"{COLOR}_banner", rotation="8")
# work corner: crafting table, chopping block, neat firewood stack
put(X0 - 2, 0, 5, "crafting_table")
put(X0 - 2, 0, 7, "oak_log", axis="y")
put(X0 - 2, 1, 7, "oak_pressure_plate", powered="false")
for z in (8, 9):
    put(X0 - 2, 0, z, "spruce_log", axis="x")
put(X0 - 2, 1, 8, "spruce_log", axis="x")
put(X0 + 6, 0, 3, "barrel", facing="up", open="false")
put(X0 + 6, 0, 2, "composter", level="0")
put(X0 + 6, 1, 3, "flower_pot")

# ground: grass with a worn path and fire ring
W, D = X0 + 9, 13
ground = {}
worn = set()
for z in range(6, 12):
    worn.add((X0 + 2, z))
for dx in range(-1, 2):
    for dz in range(-1, 2):
        worn.add((FX + dx, FZ + dz))
for (x, z) in worn:
    ground[(x, z)] = "dirt_path" if (x + z) % 3 else "coarse_dirt"
ground[(FX, FZ)] = "gravel"
ground[(FX - 1, FZ - 1)] = "cobblestone"
ground[(FX + 1, FZ + 1)] = "cobblestone"

size = (W, 4, D)
blocks = add_ground(B, size, pad=1)
for (x, z), name in ground.items():
    blocks[(x, -1, z)] = ("minecraft:" + name, {})

if __name__ == "__main__":
    import os
    os.makedirs("../renders", exist_ok=True)
    for rot in range(4):
        img = render(blocks, size, rot, 4, bg=(170, 200, 230, 255))
        img.save(f"../renders/tent1_{COLOR}_rot{rot}.png")
        print(f"tent1_{COLOR}_rot{rot}.png", img.size)
