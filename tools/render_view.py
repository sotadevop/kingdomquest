"""Renders a field test view (fieldtest/<site>_view_<name>.json, written by FieldTest) isometrically.

Usage: KQ_FETCH=1 python3 tools/render_view.py <view.json> <out.png> [--rot 0..3] [--scale N]
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(__file__))
from render import render  # noqa: E402

STATE = re.compile(r"Block\{([^}]+)\}(?:\[(.*)\])?")


def load(path):
    with open(path) as f:
        v = json.load(f)
    pal = []
    for s in v["palette"]:
        m = STATE.match(s)
        name = m.group(1)
        props = dict(kv.split("=", 1) for kv in m.group(2).split(",")) if m.group(2) else {}
        pal.append((name, props))
    blocks = {(x, y, z): pal[i] for x, y, z, i in v["blocks"]}
    return blocks, tuple(v["size"]), v.get("centre", "")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("view")
    ap.add_argument("out")
    ap.add_argument("--rot", type=int, default=0)
    ap.add_argument("--scale", type=int, default=2)
    a = ap.parse_args()
    blocks, size, centre = load(a.view)
    img = render(blocks, size, a.rot, a.scale, bg=(170, 200, 230, 255))
    img.save(a.out)
    print(a.out, img.size, len(blocks), "blocks, centre", centre)
