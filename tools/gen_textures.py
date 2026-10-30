#!/usr/bin/env python3
"""Generate the Ore Sensor dial layers, their item models and a review preview.

The dial is concept A of the 2026-09-24 concept sheet, "vanilla compass, gold case": 16x16
textures at the vanilla item rate, hard pixels (alpha 0 or 255, no anti-aliasing, no
supersampling), every colour lifted from a named vanilla 1.20.1 texture and checked against it
(lift()). Each layer is drawn at runtime by SensorRenderer as its own item/generated model, all
on the same plane, so the draw order decides what is on top:
base -> sonar ring -> charge gauge -> needle tail -> needle tip -> lit lamp -> sample item.

  ore_sensor_base            gold case and its outline, the dark face with the rim's shadow
                             (upper left) and lit lip (lower right), the two grey ticks at 3 and
                             9 o'clock, the 4x4 black sample window, the four UNLIT gauge cells
                             under it and the two UNLIT lamps in the rim at 12 and 6 o'clock
                             (they double as the N/S marks)
  ore_sensor_needle_00..31   white 3-px stroke outside the window, frame i pointing 11.25 deg * i
                             clockwise from up, like the vanilla compass's frames; the renderer
                             tints it (red and pulsing when locked, grey at rest)
  ore_sensor_tail_00..31     white 1-px tail opposite frame i, just outside the window
  ore_sensor_lamp_up/down    the lit lamp, red: 12 o'clock = ore above, 6 o'clock = below
  ore_sensor_charge_00..03   one lit gauge cell each (amethyst lilac), left to right;
                             full bright with a slow 2.8 s brightness pulse (0.75 .. 1.00)
  ore_sensor_empty           the four gauge cells in red: NO_CHARGE, drawn with a pulsing alpha
  ore_sensor_sonar_00..02    cyan 1-px ring on the face, radius ~3.5 / ~4.5 / ~5.5

Geometry: x right, y down, pixel (x, y) spans [x, x+1) x [y, y+1), its centre is (x+0.5, y+0.5);
the dial centre is the continuous point (8.0, 8.0), the corner between pixels 7 and 8, so the
4x4 window (pixels 6..9) sits exactly in the middle. Light from the top left.

The concept skin (--skin concept) is the v0.0.1 dial instead: the 64x64 "face-arrow" dial of
2026-09-24, recovered, in the same 75 layers, with anti-aliased needle, tail, rings and glyphs
(see the concept section).

The compass_b skin (--skin compass_b) is this compass with chevron lamps: the N/S marks become plain
gold rim and the lamps are two 3-px chevrons on the upper face, ^ (ore above) on the right and
v (ore below) on the left; every other layer is the compass's (see the compass_b section).

Usage: python3 tools/gen_textures.py [--skin ID] [--scale N] [--preview [PATH]] [--client-jar PATH]
Deterministic, Pillow is the only dependency; it reads the vanilla textures straight out of a
client jar (palette check, the preview's hotbar): by default the Forge 1.20.1 client-extra jar the
palette was taken from; a 26.x client jar (gui/widgets.png became gui/sprites/hud/hotbar.png)
works too, for example NeoForge's ~/.gradle/caches/neoformruntime/artifacts/minecraft_26.3_client.jar.

  (no options)   the compass skin, the 1.0 art, as before: every layer's PNG and item/generated
                 model JSON under textures/item and models/item; deletes every other ore_sensor_*
                 texture/model there (the retired 64 px layers) so nothing stale reaches the jar;
                 textures/gui/ore_sensor.png (the sensor screen, see the GUI section);
                 src/main/resources/icon.png, the mod list icon (48x48, see ICON), only on a
                 branch that already ships that file; and a 12x preview at
                 build/dial_preview.png with the runtime tints applied. The sensor's own item
                 model, models/item/ore_sensor.json, is the branch's and is checked, never
                 written (see check_item_model). A file whose content would not change is not
                 rewritten, so a run over unchanged art leaves git status clean on every branch.
  --skin ID      the skin to generate (SKIN_ART: the art this script can draw; default compass).
                 compass writes where it always has; any other skin writes only its own layers,
                 to textures/item/skins/ID/ and models/item/skins/ID/ (the mod's
                 client.skin.Skins lists the built-in skins the mod loads). concept: the
                 v0.0.1 dial, 64x64 textures.
                 compass_b: the compass with chevron lamps, 16x16 textures.
  --scale N      write the skin's textures N times its native size, nearest neighbour (a 16 px
                 skin at --scale 2 is a 32 px skin); models and the preview are unchanged.
  --preview      the approval gate for new skin art: render only the preview sheet for the skin,
                 to PATH or build/dial_preview[_ID].png, and write nothing under src. Alex
                 approves the sheet before a skin enters any jar.

Preview, one row each, on the inventory slot grey, every cell 12x of 16 px whatever the skin's
native size (a compass texel is a 12 px block, a 64 px concept texel a 3 px block):
  1  DORMANT      grey needle at rest, no sample, with 0, 1, 17, 33 and 49 shards
  2  SEARCHING    sonar ring 0, 1, 2 at the start of its third of the sweep, gauge 3 of 4
  3  LOCKED       needle frame 5 (concept 6, compass_b 8, PREVIEW_LOCKED_FRAME): pulse high + top lamp,
                  pulse high + bottom lamp, lost (steady dim tip), pulse low
  4  NO_CHARGE    the red row at the pulse's high and low alpha
  5, 6            all 32 needle frames (red tip at pulse high + light tail), 16 per row
  7  GAUGE        1, 2, 3, 4 cells lit
  8  CHARGE PULSE no sample, 40 shards: brightness pulse low and high (all other rows at high)
  9  HOTBAR       DORMANT, SEARCHING ring 0, LOCKED top lamp, NO_CHARGE high in the first
                  four slots of the vanilla hotbar (gui/widgets.png) at GUI scale 2, as the
                  concept sheet does, then the same strip blown up 4x to inspect; a skin
                  finer than 16 px is box-filtered to 16 px first (the mipmapped icon)
Rows 2-4, 7 and 9 put a 4x4 diamond in the window as a stand-in for the sample item (the
renderer draws the real item there), each diamond texel size/16 px square, so it covers the
same 16 px window square on every skin.
"""

import argparse
import hashlib
import io
import json
import math
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
RESOURCES = ROOT / "src" / "main" / "resources"
ASSETS = RESOURCES / "assets" / "oresense"
TEXTURES = ASSETS / "textures" / "item"
MODELS = ASSETS / "models" / "item"
CLIENT_JAR = Path.home() / ".gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client-extra.jar"

SIZE = 16
CENTRE = 8.0
CLEAR = (0, 0, 0, 0)


# ---- vanilla palette -----------------------------------------------------
# Every colour is a pixel of the vanilla texture it is named after; lift() asserts it, so a
# typo cannot slip an off-palette tone in. Names and values are concept A's (concepts.py).

_vanilla = {}
_client_jar = CLIENT_JAR


def vanilla(path):
    """assets/minecraft/textures/<path>.png from the client jar, as RGBA."""
    if path not in _vanilla:
        with zipfile.ZipFile(_client_jar) as z, z.open(f"assets/minecraft/textures/{path}.png") as f:
            img = Image.open(f)
            img.load()
            _vanilla[path] = img.convert("RGBA")
    return _vanilla[path]


def rgb(hex_colour):
    """'#rrggbb' -> (r, g, b)."""
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5))


def rgba(hex_colour):
    return rgb(hex_colour) + (255,)


class Palette:
    """Concept A's tones, lifted from the vanilla textures on first use (lift() needs the jar)."""

    def __init__(self):
        # gold: gold_ingot, the vanilla clock's gold
        (self.G_HI, self.G_LIGHT, self.G_MID, self.G_LOW, self.G_DARK, self.G_EDGE) = lift(
            "item/gold_ingot", "#fdf55f", "#fad64a", "#e9b115", "#dc9613", "#b26411", "#752802")
        # compass face, rim shadow and lip, ticks; white for the tint-baked needle and tail
        (self.C_WHITE, self.C_M1, self.C_D1, self.C_FACE, self.C_D3) = lift(
            "item/compass_00", "#ffffff", "#646464", "#353535", "#2f2f2f", "#181717")
        # the compass needle's red: the lit lamps and the no-charge row
        self.RED = lift("item/compass_00", "#ff1414")
        # an unlit lamp: redstone dust's dark red
        self.LAMP_OFF = lift("item/redstone", "#720000")
        # amethyst shard: a lit gauge cell and an unlit one
        (self.AM_HI, self.AM_DARK) = lift("item/amethyst_shard", "#cfa0f3", "#54398a")
        # recovery compass: the sonar's cyans and the window's near-black
        (self.SON_HI, self.SON_MID, self.RC_BLACK) = lift(
            "item/recovery_compass_00", "#29dfeb", "#14bbc6", "#0d0d0d")
        # diamond, for the preview's stand-in sample only
        (self.D_WHITE, self.D_PALE, self.D_MID, self.D_DEEP, self.D_DARK) = lift(
            "item/diamond", "#ffffff", "#d5fff6", "#4aedd9", "#1aaaa7", "#11727a")


def pixels_of(img):
    return img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata()


def lift(tex, *hex_colours):
    """The colours as RGBA, each asserted to be an opaque pixel of the vanilla texture."""
    have = {p[:3] for p in pixels_of(vanilla(tex)) if p[3] == 255}
    out = []
    for h in hex_colours:
        c = rgba(h)
        if c[:3] not in have:
            raise AssertionError(f"{h} is not in {tex}.png")
        out.append(c)
    return tuple(out) if len(out) > 1 else out[0]


# ---- geometry ------------------------------------------------------------

def disc(cx, cy, r):
    """Pixels whose centre lies within r of (cx, cy)."""
    return {(x, y) for x in range(SIZE) for y in range(SIZE) if math.hypot(x + 0.5 - cx, y + 0.5 - cy) <= r}


def peel(mask, n):
    """Peels n one-pixel rings off a shape (a pixel is on the ring when a 4-neighbour is
    outside), vanilla's thin 8-connected outline; returns ([ring0, ring1, ...], core)."""
    rings, core = [], set(mask)
    for _ in range(n):
        edge = {p for p in core
                if any((p[0] + dx, p[1] + dy) not in core for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))}
        rings.append(edge)
        core -= edge
    return rings, core


def light(p):
    """How much pixel p faces the top-left light: +1 top-left .. -1 bottom-right."""
    dx, dy = p[0] + 0.5 - CENTRE, p[1] + 0.5 - CENTRE
    d = math.hypot(dx, dy) or 1.0
    return (-dx - dy) / (d * math.sqrt(2))


def distance(p):
    """Pixel p's centre from the dial centre (the same as p from (7.5, 7.5) in pixel indices)."""
    return math.hypot(p[0] + 0.5 - CENTRE, p[1] + 0.5 - CENTRE)


def clockwise_angle(dx, dy):
    """Degrees clockwise from straight up of the offset (dx, dy), 0..360."""
    return math.degrees(math.atan2(dx, -dy)) % 360.0


def neighbours4(p):
    return [(p[0] + dx, p[1] + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))]


DISC_R = 7.2
(OUTLINE, BAND), FACE = peel(disc(CENTRE, CENTRE, DISC_R), 2)   # outline, gold band, face
WINDOW = frozenset((x, y) for x in range(6, 10) for y in range(6, 10))
TICKS = ((3, 8), (12, 8))              # E and W on the needle's horizontal axis
# the lamps are set into the gold band at 12 (ore above) and 6 o'clock (ore below); unlit
# they are the N and S marks. In the rim, never on the needle's track: a lit lamp on the
# face read as a longer needle at GUI scale 2 (concept sheet)
LAMPS = {"up": ((7, 2), (8, 2)), "down": ((7, 13), (8, 13))}
GAUGE = ((6, 11), (7, 11), (8, 11), (9, 11))   # left to right, 16 shards per cell
MAX_CHARGES = 64                               # OreSensorItem.MAX_CHARGES

NEEDLE_FRAMES = 32                     # 11.25 degrees apart, like the vanilla compass
# The needle is the pixels at t = NEEDLE_T along (sin a, -cos a) from the dial centre, floored,
# deduplicated, minus the window and anything off the face. Three samples on 2.6..5.4 make the
# 3-px stroke; sampling the whole range densely instead takes every pixel the line crosses,
# a 4-connected stair of 4-5 px with an L at every step. 3.8 and 4.9 are the middles of the
# ranges (3.61..4.00, 4.82..5.00, in 0.01 steps) where no frame has an L, the four
# axis frames are straight 3-px lines and only the four exact diagonals lose their root to
# the window (2 px). The concept's own radii (2.5, 3.6, 4.8) put an L into 8 frames.
NEEDLE_T = (2.6, 3.8, 4.9)
NEEDLE_ANGLE_TOLERANCE = 9.0           # self-check: stroke centroid vs the frame's angle
TAIL_T = (2.6, 3.4)                    # the tail: the first face pixel along this stretch
TAIL_STEPS = 80

# sonar rings: face pixels (not the window, not the gauge cells) whose centre distance lies in
# (inner, outer]. Ring 1 stops at 4.9 so the diagonal pixels at 4.95 go to ring 2: the ring
# then steps diagonally at 45 degrees instead of turning a 3-px corner, and ring 2 keeps the
# four diagonal arcs the rim leaves room for
SONAR = ((3.0, 4.0), (4.0, 4.9), (4.9, 6.0))
SONAR_SHADE = -0.3                     # light above this: SON_HI, else SON_MID

# Runtime tints and alphas (SensorRenderer): (r, g, b) multiply the texture, alpha blends.
GREY = (0.62, 0.62, 0.66)              # needle tip and tail at rest (not LOCKED)
LOCK_TIP = (0.93, 0.14, 0.10)          # times the pulse b
LOCK_TAIL = (0.85, 0.85, 0.85)
LOST_TIP = (0.5, 0.08, 0.06)           # locked vein out of range: steady dim red
PULSE_LOW = 0.55                       # b swings 0.55 .. 1.0
CHARGE_PULSE_LOW = 0.75                # full-bright cells: 2.8 s sine pulse up to 1.0
PLAIN = (1.0, 1.0, 1.0)
EMPTY_ALPHA = (0.35, 1.0)              # NO_CHARGE row: low, high
SONAR_ALPHA = (0.85, 0.30)             # current ring, previous ring; both times (1 - phase)
DISCARD_ALPHA = 0.1   # rendertype_entity_translucent_cull.fsh drops a < 0.1
LOCKED_FRAME = 5                       # the LOCKED preview cells' frame (56.25 degrees)


def needle(i):
    return f"ore_sensor_needle_{i:02d}"


def tail(i):
    return f"ore_sensor_tail_{i:02d}"


def charge(i):
    return f"ore_sensor_charge_{i:02d}"


def sonar(k):
    return f"ore_sensor_sonar_{k:02d}"


def frame_angle(i):
    return 360.0 * i / NEEDLE_FRAMES


def along(degrees, ts):
    """The pixels at distances ts along the ray `degrees` clockwise from up, in order, deduplicated."""
    a = math.radians(degrees)
    ux, uy = math.sin(a), -math.cos(a)
    out = []
    for t in ts:
        p = (math.floor(CENTRE + ux * t), math.floor(CENTRE + uy * t))
        if p not in out:
            out.append(p)
    return out


def on_dial_face(p):
    return p in FACE and p not in WINDOW


def needle_pixels(i):
    return [p for p in along(frame_angle(i), NEEDLE_T) if on_dial_face(p)]


def tail_pixels(i):
    lo, hi = TAIL_T
    ts = [lo + (hi - lo) * k / TAIL_STEPS for k in range(TAIL_STEPS + 1)]
    return [p for p in along(frame_angle(i) + 180.0, ts) if on_dial_face(p)][:1]


def sonar_pixels(k):
    inner, outer = SONAR[k]
    return {p for p in FACE if inner < distance(p) <= outer and p not in WINDOW and p not in GAUGE}


# ---- layers --------------------------------------------------------------

def build_base(pal, lamps=LAMPS):
    """Concept A without the needle, sonar and sample, with both lamps and every gauge cell unlit.
    `lamps` holds the unlit lamps' pixels: the compass's N/S marks by default; with none the band
    painter's plain gold rim stays there (the compass_b skin, see its section)."""
    base = {}
    for p in OUTLINE:
        base[p] = pal.G_DARK if p[1] <= 6 else pal.G_EDGE        # the clock's outline
    for p in BAND:
        l = light(p)
        base[p] = pal.G_HI if l > 0.55 else pal.G_LIGHT if l > 0.0 else pal.G_MID if l > -0.6 else pal.G_LOW
    for p in FACE:
        base[p] = pal.C_FACE
    # the rim's shadow on the upper left of the face, the lit lip on the lower right
    for p in FACE:
        if any(q in BAND for q in neighbours4(p)):
            l = light(p)
            if l > 0.35:
                base[p] = pal.C_D3
            elif l < -0.35:
                base[p] = pal.C_D1
    for p in WINDOW:
        base[p] = pal.RC_BLACK
    for p in TICKS:
        base[p] = pal.C_M1
    for pixels in lamps.values():
        for p in pixels:
            base[p] = pal.LAMP_OFF
    for p in GAUGE:
        base[p] = pal.AM_DARK
    return base


def build_layers(pal):
    layers = {"ore_sensor_base": build_base(pal)}
    for i in range(NEEDLE_FRAMES):
        layers[needle(i)] = {p: pal.C_WHITE for p in needle_pixels(i)}
        layers[tail(i)] = {p: pal.C_WHITE for p in tail_pixels(i)}
    for name, pixels in LAMPS.items():
        layers[f"ore_sensor_lamp_{name}"] = {p: pal.RED for p in pixels}
    for i, p in enumerate(GAUGE):
        layers[charge(i)] = {p: pal.AM_HI}
    layers["ore_sensor_empty"] = {p: pal.RED for p in GAUGE}
    for k in range(len(SONAR)):
        layers[sonar(k)] = {p: pal.SON_HI if light(p) > SONAR_SHADE else pal.SON_MID for p in sonar_pixels(k)}
    return layers


def layer_names():
    return (["ore_sensor_base"]
            + [needle(i) for i in range(NEEDLE_FRAMES)] + [tail(i) for i in range(NEEDLE_FRAMES)]
            + ["ore_sensor_lamp_up", "ore_sensor_lamp_down"]
            + [charge(i) for i in range(len(GAUGE))] + ["ore_sensor_empty"]
            + [sonar(k) for k in range(len(SONAR))])


# ---- self-checks ---------------------------------------------------------

def centroid_angle(pixels):
    sx = sum(x + 0.5 - CENTRE for x, _ in pixels) / len(pixels)
    sy = sum(y + 0.5 - CENTRE for _, y in pixels) / len(pixels)
    return clockwise_angle(sx, sy)


def angle_off(got, want):
    return abs((got - want + 180.0) % 360.0 - 180.0)


def has_corner(pixels):
    """True when a pixel has both a horizontal and a vertical neighbour in the set: an L."""
    s = set(pixels)
    return any(((x - 1, y) in s or (x + 1, y) in s) and ((x, y - 1) in s or (x, y + 1) in s) for x, y in s)


def check_layers(layers, pal, lamps=LAMPS, lamp_area=("the gold band", BAND)):
    """The compass checks. `lamps` (name -> pixels) and `lamp_area` (what to call it, the pixels)
    say where the skin's lamps are: the compass's in the gold band by default; the compass_b skin
    passes its chevrons on the face (check_compass_b)."""
    if sorted(layers) != sorted(layer_names()):
        raise AssertionError(f"layer set {sorted(layers)} is not the {len(layer_names())} the renderer loads")
    for name, pixels in layers.items():
        if not pixels:
            raise AssertionError(f"{name} is empty")
        off = [p for p in pixels if not (0 <= p[0] < SIZE and 0 <= p[1] < SIZE)]
        if off:
            raise AssertionError(f"{name} has pixels off the {SIZE}x{SIZE} canvas: {off}")
        soft = [p for p, c in pixels.items() if c[3] != 255]
        if soft:
            raise AssertionError(f"{name} has partial alpha at {soft}")

    base = layers["ore_sensor_base"]
    if set(base) != OUTLINE | BAND | FACE:
        raise AssertionError("the base is not exactly the case disc")
    for name, pixels in layers.items():
        stray = [p for p in pixels if p not in base]
        if stray:
            raise AssertionError(f"{name} draws where the base is transparent: {stray}")

    worst = 0.0
    for i in range(NEEDLE_FRAMES):
        tip, tl = needle_pixels(i), tail_pixels(i)
        want = frame_angle(i)
        if not 2 <= len(tip) <= 3:
            raise AssertionError(f"{needle(i)} has {len(tip)} pixels, want 2..3: {tip}")
        if any(max(abs(a[0] - b[0]), abs(a[1] - b[1])) != 1 for a, b in zip(tip, tip[1:])):
            raise AssertionError(f"{needle(i)} is not one unbroken stroke: {tip}")
        if has_corner(tip):
            raise AssertionError(f"{needle(i)} folds into an L: {tip}")
        off = angle_off(centroid_angle(tip), want)
        worst = max(worst, off)
        if off > NEEDLE_ANGLE_TOLERANCE:
            raise AssertionError(f"{needle(i)} points {off:.1f} deg away from {want}")
        if len(tl) != 1:
            raise AssertionError(f"{tail(i)} has {len(tl)} pixels, want 1")
        if angle_off(centroid_angle(tl), want + 180.0) > 45.0:
            raise AssertionError(f"{tail(i)} at {tl} is not opposite the needle")
        for p in tip + tl:
            if not on_dial_face(p):
                raise AssertionError(f"frame {i} pixel {p} is off the face or on the window")
        if set(tip) & set(tl):
            raise AssertionError(f"frame {i}: the tail overlaps the needle")
    straight = {0: [(8, 5), (8, 4), (8, 3)], 8: [(10, 8), (11, 8), (12, 8)],
                16: [(8, 10), (8, 11), (8, 12)], 24: [(5, 8), (4, 8), (3, 8)]}
    for i, want in straight.items():
        if needle_pixels(i) != want:
            raise AssertionError(f"{needle(i)} is {needle_pixels(i)}, want the straight line {want}")
    if tail_pixels(0) != [(8, 10)]:
        raise AssertionError(f"{tail(0)} is {tail_pixels(0)}, want the concept's (8, 10)")

    rim = OUTLINE | BAND
    where, area = lamp_area
    for name, pixels in lamps.items():
        lamp = layers[f"ore_sensor_lamp_{name}"]
        if set(lamp) != set(pixels) or any(p not in area for p in lamp):
            raise AssertionError(f"lamp {name} is not in {where} at {pixels}")
        if any(base[p] != pal.LAMP_OFF for p in pixels):
            raise AssertionError(f"the base's unlit lamp {name} is not under the lit one")
    for i, p in enumerate(GAUGE):
        if set(layers[charge(i)]) != {p} or base[p] != pal.AM_DARK:
            raise AssertionError(f"{charge(i)} is not the gauge cell {p} over an unlit cell")
    if set(layers["ore_sensor_empty"]) != set(GAUGE):
        raise AssertionError("the no-charge row is not the gauge row")
    if [p[0] for p in GAUGE] != sorted(p[0] for p in GAUGE):
        raise AssertionError("gauge cells are not left to right")

    radii = []
    for k in range(len(SONAR)):
        ring = layers[sonar(k)]
        bad = [p for p in ring if p not in FACE or p in WINDOW or p in rim or p in GAUGE]
        if bad:
            raise AssertionError(f"{sonar(k)} leaves the face at {bad}")
        if has_corner(ring):
            raise AssertionError(f"{sonar(k)} turns a 3-px corner")
        # the whole band is symmetric both ways; the ring is that band less the gauge cells
        inner, outer = SONAR[k]
        band = {p for p in FACE if inner < distance(p) <= outer and p not in WINDOW}
        if (band != {(SIZE - 1 - x, y) for x, y in band} or band != {(x, SIZE - 1 - y) for x, y in band}
                or set(ring) != band - set(GAUGE)):
            raise AssertionError(f"{sonar(k)} is not the symmetric band less the gauge cells")
        radii.append(sum(distance(p) for p in ring) / len(ring))
    if radii != sorted(radii):
        raise AssertionError(f"sonar rings do not grow: {radii}")
    return worst, radii


# ---- output --------------------------------------------------------------

def layer_image(pixels, size):
    """One layer as a size x size RGBA image, size being the skin's native texture size."""
    img = Image.new("RGBA", (size, size), CLEAR)
    for (x, y), colour in pixels.items():
        img.putpixel((x, y), colour)
    return img


def write_bytes(path, data):
    """Writes data unless the file already holds exactly it (no rewrite, no new mtime)."""
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return True


def write_json(path, obj):
    write_bytes(path, json.dumps(obj, indent=1).encode("utf-8"))


def write_png(path, img):
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return write_bytes(path, buf.getvalue())


def check_item_model(layers):
    """models/item/ore_sensor.json, the sensor's own item model, is the branch's and is never
    written: its parent says how the loader draws the dial (builtin/entity where the item's
    BEWLR draws it, as on Forge 1.20.1 and Fabric 1.21.1; minecraft:item/generated plus layer0
    on 26.x, where the special model renderer does), and its gui_light, textures and display
    block go with that. All this script knows about it is the compass layer names, so it only
    checks that every oresense:item/ore_sensor_* texture the model names is one of `layers`,
    the compass layers this run writes; otherwise the retired-file sweep in write_assets would
    leave the model pointing at a deleted texture. Runs before anything is written. Returns the
    model's parent, for the log."""
    path = MODELS / "ore_sensor.json"
    model = json.loads(path.read_text())
    for key, ref in sorted(model.get("textures", {}).items()):
        name = ref.removeprefix("oresense:item/")
        if name != ref and name.startswith("ore_sensor_") and name not in layers:
            raise SystemExit(f"{path}: texture {key} = {ref} is not a compass layer; "
                             f"fix the item model by hand, this script does not write it")
    return model["parent"]


DEFAULT_SKIN = "compass"


def skin_dirs(skin):
    """(texture folder, model folder, texture reference prefix) of a skin: compass at the 1.0
    paths, so resource packs that retexture the sensor keep working, the rest under skins/ID/."""
    if skin == DEFAULT_SKIN:
        return TEXTURES, MODELS, "oresense:item/"
    return TEXTURES / "skins" / skin, MODELS / "skins" / skin, f"oresense:item/skins/{skin}/"


def write_assets(layers, size, skin=DEFAULT_SKIN, scale=1):
    """Writes every layer (scaled up `scale` times, nearest neighbour) and its model into the
    skin's folders, then deletes every other ore_sensor_* layer file there (for compass, the
    retired 64 px set: needle, arrow_up/down, charge_04..07, sonar_03) so none reaches the jar.
    The skins/ subfolders are not touched by a compass run (the glob is not recursive). A layer
    is `size` px square, the skin's native size (SKIN_ART), before the scaling."""
    textures, models, ref = skin_dirs(skin)
    textures.mkdir(parents=True, exist_ok=True)
    models.mkdir(parents=True, exist_ok=True)
    for name, pixels in layers.items():
        img = layer_image(pixels, size)
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        write_png(textures / f"{name}.png", img)
        write_json(models / f"{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{ref}{name}"},
        })
    removed = []
    for folder, suffix in ((textures, ".png"), (models, ".json")):
        for path in sorted(folder.glob(f"ore_sensor_*{suffix}")):
            if path.stem not in layers:
                path.unlink()
                removed.append(path.name)
    return removed


# ---- GUI background ------------------------------------------------------
# textures/gui/ore_sensor.png, drawn by OreSensorScreen: vanilla's hopper
# layout (a 176x133 panel: title, one row of slots, player inventory, hotbar)
# with only the two slots OreSensorMenu has, the charges and the sample. The
# colours are the palette of 1.20.1's textures/gui/container/hopper.png, so
# everything but the top slot row matches it pixel for pixel.
# The 256x256 canvas matches the texture dimensions used by OreSensorScreen.
# The visible panel occupies only 176x133 pixels.

GUI_TEXTURES = ASSETS / "textures" / "gui"
GUI_CANVAS = 256
PANEL_SIZE = (176, 133)          # OreSensorScreen imageWidth x imageHeight
PANEL_OUTLINE = rgb("#000000")
PANEL_BODY = rgb("#c6c6c6")
PANEL_LIGHT = rgb("#ffffff")     # bevel along the top and left
PANEL_SHADOW = rgb("#555555")    # bevel along the bottom and right
PANEL_BEVEL = 2                  # px, inside the 1-px outline
# Vanilla's rounded corners, a 4x4 stencil each, copied from hopper.png:
# '.' transparent, K outline, L light, S shadow, B body. The top-left and
# bottom-right corners are cut 2 px deep, the other two 3 px; each corner is
# its opposite turned 180 degrees with light and shadow swapped.
PANEL_CORNERS = {
    # (on the right, at the bottom): rows, top to bottom
    (False, False): ("..KK",
                     ".KLL",
                     "KLLL",
                     "KLLL"),
    (True, False): ("K...",
                    "LK..",
                    "LBK.",
                    "BSSK"),
    (False, True): ("KLLB",
                    ".KBS",
                    "..KS",
                    "...K"),
    (True, True): ("SSSK",
                   "SSSK",
                   "SSK.",
                   "KK.."),
}
CORNER_KEY = {".": None, "K": PANEL_OUTLINE, "L": PANEL_LIGHT, "S": PANEL_SHADOW,
              "B": PANEL_BODY}

SLOT_FRAME = 18                  # px square; slots sit 18 px apart, frames touching
SLOT_DARK = rgb("#373737")       # top and left edge
SLOT_BODY = rgb("#8b8b8b")       # interior, and the two corners where dark meets light
SLOT_LIGHT = rgb("#ffffff")      # bottom and right edge
# Item positions of OreSensorMenu's slots (its addSlot calls). A slot's frame
# starts 1 px up and left of its item. The charges (amethyst shards) sit on the
# left and the sample on the right, each with room on its left for the label
# OreSensorScreen draws ("Shards" ends at x 47, "Sample" at x 135).
SAMPLE_SLOT = (140, 20)
CHARGE_SLOT = (52, 20)
INVENTORY_SLOTS = (8, 51)        # first of 9 columns x 3 rows
HOTBAR_SLOTS = (8, 109)          # first of 9 columns


def slot_items():
    yield CHARGE_SLOT
    yield SAMPLE_SLOT
    for row in range(3):
        for col in range(9):
            yield INVENTORY_SLOTS[0] + SLOT_FRAME * col, INVENTORY_SLOTS[1] + SLOT_FRAME * row
    for col in range(9):
        yield HOTBAR_SLOTS[0] + SLOT_FRAME * col, HOTBAR_SLOTS[1]


def slot_frames():
    return [(x - 1, y - 1) for x, y in slot_items()]


def panel_colour(x, y):
    """Outline, bevels and body; where the light and shadow bevels cross (top
    right, bottom left) the corner stencils decide."""
    w, h = PANEL_SIZE
    if x in (0, w - 1) or y in (0, h - 1):
        return PANEL_OUTLINE
    if x <= PANEL_BEVEL or y <= PANEL_BEVEL:
        return PANEL_LIGHT
    if x >= w - 1 - PANEL_BEVEL or y >= h - 1 - PANEL_BEVEL:
        return PANEL_SHADOW
    return PANEL_BODY


def slot_frame_colour(i, j):
    """Pixel (i, j) of an 18x18 frame: dark top and left edge, light bottom and
    right edge, the two corners where they meet in the slot grey."""
    last = SLOT_FRAME - 1
    if (i, j) in ((last, 0), (0, last)):
        return SLOT_BODY
    if i == 0 or j == 0:
        return SLOT_DARK
    if i == last or j == last:
        return SLOT_LIGHT
    return SLOT_BODY


def build_gui(frames):
    pixels = {}
    w, h = PANEL_SIZE
    for y in range(h):
        for x in range(w):
            pixels[(x, y)] = panel_colour(x, y)
    for (right, bottom), rows in PANEL_CORNERS.items():
        ox = w - len(rows[0]) if right else 0
        oy = h - len(rows) if bottom else 0
        for j, row in enumerate(rows):
            for i, key in enumerate(row):
                pixels[(ox + i, oy + j)] = CORNER_KEY[key]
    for fx, fy in frames:
        for j in range(SLOT_FRAME):
            for i in range(SLOT_FRAME):
                pixels[(fx + i, fy + j)] = slot_frame_colour(i, j)
    return {p: colour + (255,) for p, colour in pixels.items() if colour is not None}


def check_gui(frames):
    """Every frame lies on the panel body, inside the bevels, and no two overlap."""
    w, h = PANEL_SIZE
    lo = PANEL_BEVEL + 1
    taken = set()
    for fx, fy in frames:
        if not (lo <= fx and fx + SLOT_FRAME <= w - lo and lo <= fy and fy + SLOT_FRAME <= h - lo):
            raise AssertionError(f"slot frame at {(fx, fy)} leaves the panel body")
        cells = {(fx + i, fy + j) for j in range(SLOT_FRAME) for i in range(SLOT_FRAME)}
        if cells & taken:
            raise AssertionError(f"slot frame at {(fx, fy)} overlaps another")
        taken |= cells


def write_gui(pixels):
    img = Image.new("RGBA", (GUI_CANVAS, GUI_CANVAS), (0, 0, 0, 0))
    for (x, y), colour in pixels.items():
        img.putpixel((x, y), colour)
    path = GUI_TEXTURES / "ore_sensor.png"
    write_png(path, img)
    return path


# ---- preview -------------------------------------------------------------

PREVIEW_SCALE = 12
PREVIEW_GAP = 12
PREVIEW_LABEL_W = 200
PREVIEW_CELL_BG = rgba("#8b8b8b")      # vanilla inventory slot grey
PREVIEW_PAGE_BG = rgb("#1e1e1e")
PREVIEW_TEXT = rgb("#e8e8e8")
HOTBAR_WORLD = (0x80, 0x80, 0x80, 255)  # stand-in for the world behind the hotbar
HOTBAR_ZOOM = 4                         # the inspection copy of the 2x strip
SAMPLE_AT = (6, 6)                      # SensorRenderer's WINDOW_X, WINDOW_Y: the 4x4 window, of 16


class Art:
    """A skin's layers, their native texture size (compass 16, concept 64) and the needle frame
    its LOCKED preview cells show. The preview draws every skin at its own size and scales it to
    the same sheet: a cell is PREVIEW_SCALE x 16 px wide whatever the skin, so a 64 px texel is a
    3 px block where a 16 px one is 12."""

    def __init__(self, layers, size, locked_frame=LOCKED_FRAME):
        if size % SIZE or (PREVIEW_SCALE * SIZE) % size:
            raise AssertionError(f"a skin's native size must be a multiple of {SIZE} dividing "
                                 f"{PREVIEW_SCALE * SIZE}, not {size}")
        self.layers = layers
        self.size = size
        self.locked_frame = locked_frame


def diamond_4(pal):
    """The concept sheet's 4x4 stand-in for a diamond sample, in the vanilla diamond tones."""
    return [
        [None, pal.D_PALE, pal.D_MID, None],
        [pal.D_PALE, pal.D_WHITE, pal.D_MID, pal.D_DEEP],
        [pal.D_MID, pal.D_MID, pal.D_DEEP, pal.D_DARK],
        [None, pal.D_DEEP, pal.D_DARK, None],
    ]


class Dial:
    """A skin's RGBA dial at its native size, composited like the item sheet: every layer on the
    same plane, drawn in order, (r, g, b) times the tint, src-alpha over; alpha under 0.1 is
    discarded."""

    def __init__(self, art):
        self.layers = art.layers
        self.size = art.size
        self.px = {(x, y): (0.0, 0.0, 0.0, 0.0) for y in range(self.size) for x in range(self.size)}
        self.draw("ore_sensor_base")

    def draw(self, name, tint=PLAIN, alpha=1.0):
        for p, (r, g, b, a) in self.layers[name].items():
            sa = a / 255 * alpha
            if sa < DISCARD_ALPHA:
                continue
            dr, dg, db, da = self.px[p]
            src = (r * tint[0], g * tint[1], b * tint[2])
            out_a = sa + da * (1 - sa)
            self.px[p] = tuple(s * sa + d * (1 - sa) for s, d in zip(src, (dr, dg, db))) + (out_a,)
        return self

    def sample(self, grid):
        """The stand-in sample where the renderer draws the real one, the window square at
        SAMPLE_AT of 16 px: one grid cell per 16 px texel, size/16 texels square at this size."""
        s = self.size // SIZE
        for j, row in enumerate(grid):
            for i, c in enumerate(row):
                if c is not None:
                    colour = tuple(float(ch) for ch in c[:3]) + (1.0,)
                    for v in range(s):
                        for u in range(s):
                            self.px[((SAMPLE_AT[0] + i) * s + u, (SAMPLE_AT[1] + j) * s + v)] = colour
        return self

    def image(self):
        img = Image.new("RGBA", (self.size, self.size), CLEAR)
        for (x, y), (r, g, b, a) in self.px.items():
            if a > 0:
                img.putpixel((x, y), (round(r), round(g), round(b), round(255 * a)))
        return img


def rest_needle(dial):
    """DORMANT / SEARCHING / NO_CHARGE: grey tail and tip, straight up."""
    return dial.draw(tail(0), GREY).draw(needle(0), GREY)


def lit_cells(charges):
    """SensorRenderer: ceil(charges * 4 / 64) cells lit."""
    return -(-max(0, min(charges, MAX_CHARGES)) * len(GAUGE) // MAX_CHARGES)


def gauge(dial, charges, brightness=1.0):
    for i in range(lit_cells(charges)):
        dial.draw(charge(i), (brightness,) * 3)
    return dial


def dormant(art, charges=0, charge_brightness=1.0):
    return rest_needle(gauge(Dial(art), charges, charge_brightness))


def searching(art, pal, ring, charges=40):
    # SensorRenderer: phase p, ring k = floor(p * rings); ring k-1 fading behind ring k
    dial = Dial(art)
    p = ring / len(SONAR)
    if ring > 0:
        dial.draw(sonar(ring - 1), PLAIN, SONAR_ALPHA[1] * (1 - p))
    dial.draw(sonar(ring), PLAIN, SONAR_ALPHA[0] * (1 - p))
    return rest_needle(gauge(dial, charges)).sample(diamond_4(pal))


def locked(art, pal, frame, lamp=None, high=True, lost=False, charges=40):
    dial = gauge(Dial(art), charges)
    b = 1.0 if high else PULSE_LOW
    dial.draw(tail(frame), LOCK_TAIL)
    dial.draw(needle(frame), LOST_TIP if lost else tuple(c * b for c in LOCK_TIP))
    if lamp:
        dial.draw(f"ore_sensor_lamp_{lamp}")
    return dial.sample(diamond_4(pal))


def no_charge(art, pal, high):
    dial = Dial(art).draw("ore_sensor_empty", PLAIN, EMPTY_ALPHA[1 if high else 0])
    return rest_needle(dial).sample(diamond_4(pal))


def needle_doc(art, frame):
    return Dial(art).draw(tail(frame), LOCK_TAIL).draw(needle(frame), LOCK_TIP)


def gauge_only(art, pal, cells):
    return rest_needle(gauge(Dial(art), cells * MAX_CHARGES // len(GAUGE))).sample(diamond_4(pal))


def enlarged(img):
    """A dial on the slot grey, PREVIEW_SCALE x 16 px square whatever its native size."""
    cell = Image.new("RGBA", img.size, PREVIEW_CELL_BG)
    cell.alpha_composite(img)
    return cell.resize((SIZE * PREVIEW_SCALE,) * 2, Image.NEAREST)


def hotbar_texture():
    """The 182x22 vanilla hotbar: the top left of gui/widgets.png up to 1.20.1, its own sprite
    gui/sprites/hud/hotbar.png from 1.20.2 on (a 26.x client jar)."""
    try:
        return vanilla("gui/widgets").crop((0, 0, 182, 22))
    except KeyError:
        return vanilla("gui/sprites/hud/hotbar")


def hotbar_strip(items):
    """The vanilla hotbar's first four slots at GUI scale 2, items in them (the concept sheet's
    hotbar_strip). An item finer than 16 px is box-filtered to 16 px first, what the mipmapped
    block atlas shows of it at GUI scale 1 (premultiplied, like a mip level), then drawn 2x."""
    w = hotbar_texture()
    bar = Image.new("RGBA", (83, 22), HOTBAR_WORLD)
    strip = Image.new("RGBA", (83, 22), CLEAR)
    strip.paste(w.crop((0, 0, 81, 22)), (0, 0))
    strip.paste(w.crop((180, 0, 182, 22)), (81, 0))
    bar.alpha_composite(strip)
    bar = bar.resize((166, 44), Image.NEAREST)
    for i, it in enumerate(items):
        if it.size != (SIZE, SIZE):
            it = it.resize((SIZE, SIZE), Image.BOX)
        icon = it.resize((32, 32), Image.NEAREST)
        bar.alpha_composite(icon, (2 * (3 + 20 * i), 6))
    return bar


def preview_rows(art, pal):
    hotbar = hotbar_strip([dormant(art).image(), searching(art, pal, 0).image(),
                           locked(art, pal, art.locked_frame, "up").image(),
                           no_charge(art, pal, True).image()])
    zoom = hotbar.resize((hotbar.width * HOTBAR_ZOOM, hotbar.height * HOTBAR_ZOOM), Image.NEAREST)
    half = NEEDLE_FRAMES // 2
    return [
        ("DORMANT, no sample:\n0, 1, 17, 33, 49 shards", [
            enlarged(dormant(art, charges).image()) for charges in (0, 1, 17, 33, 49)]),
        ("SEARCHING:\nring 0, 1, 2", [enlarged(searching(art, pal, k).image()) for k in range(len(SONAR))]),
        (f"LOCKED frame {art.locked_frame}:\nup lamp, down lamp,\nlost, pulse low", [
            enlarged(locked(art, pal, art.locked_frame, "up").image()),
            enlarged(locked(art, pal, art.locked_frame, "down").image()),
            enlarged(locked(art, pal, art.locked_frame, lost=True).image()),
            enlarged(locked(art, pal, art.locked_frame, "up", high=False).image())]),
        ("NO_CHARGE:\npulse high, low", [enlarged(no_charge(art, pal, h).image()) for h in (True, False)]),
        ("needle 00-15", [enlarged(needle_doc(art, i).image()) for i in range(half)]),
        ("needle 16-31", [enlarged(needle_doc(art, i).image()) for i in range(half, NEEDLE_FRAMES)]),
        ("GAUGE: 1-4 cells", [enlarged(gauge_only(art, pal, n).image()) for n in range(1, len(GAUGE) + 1)]),
        ("CHARGE PULSE:\nno sample, 40 shards\npulse low, high", [
            enlarged(dormant(art, 40, b).image()) for b in (CHARGE_PULSE_LOW, 1.0)]),
        ("HOTBAR, GUI scale 2\n(then 4x)", [hotbar, zoom]),
    ]


# ---- mod list icon --------------------------------------------------------
# src/main/resources/icon.png, neoforge.mods.toml's iconFile (NeoForge 26.2+ lists mods with a
# square icon, drawn 24 px, apart from the logoFile banner): the DORMANT dial composite (base,
# needle frame 0 and tail in the rest grey, no gauge cell lit) at ICON_SCALE x nearest neighbour,
# 48x48, so NeoForge's 24 px is an exact 2:1 step down and stays crisp (iconBlur=false).
# Only a branch that ships the file gets it: a loader without a square-icon slot lists the mod
# with logo.png alone (Forge 1.20.1's mods.toml logoFile, Fabric 1.21.1's fabric.mod.json icon),
# and has no icon.png, so the run must not create one there.

ICON_SCALE = 3
ICON = RESOURCES / "icon.png"


def write_icon(art):
    """Refreshes ICON from the art and returns its size, where the branch ships it (the file
    exists); returns None and writes nothing where it does not."""
    if not ICON.exists():
        return None
    img = dormant(art).image().resize((art.size * ICON_SCALE,) * 2, Image.NEAREST)
    write_png(ICON, img)
    return img.size


def write_preview(art, pal, path):
    rows = preview_rows(art, pal)
    width = PREVIEW_LABEL_W + max(sum(img.width + PREVIEW_GAP for img in row) for _, row in rows) + PREVIEW_GAP
    height = PREVIEW_GAP + sum(max(img.height for img in row) + PREVIEW_GAP for _, row in rows)
    page = Image.new("RGB", (width, height), PREVIEW_PAGE_BG)
    draw = ImageDraw.Draw(page)
    try:
        font = ImageFont.load_default(size=16)
    except TypeError:
        font = ImageFont.load_default()
    y = PREVIEW_GAP
    for label, row in rows:
        draw.multiline_text((PREVIEW_GAP, y), label, font=font, fill=PREVIEW_TEXT)
        x = PREVIEW_LABEL_W
        for img in row:
            page.paste(img.convert("RGB"), (x, y))
            x += img.width + PREVIEW_GAP
        y += max(img.height for img in row) + PREVIEW_GAP
    path.parent.mkdir(parents=True, exist_ok=True)
    page.save(path, format="PNG")


# ---- concept skin: the v0.0.1 dial, 64 px -----------------------------------
# The 64x64 "face-arrow" dial of 2026-09-24 (jar oresense-0.1.0, the last art before the 16 px
# compass), recovered 2026-10-22 (release-1.1.0/concept/recovered/: DESIGN64.md, its generator
# gen_textures_64px.py and the 23 textures that generator draws). The geometry code below is
# that generator's, copied (CL_ constants, cl_ functions), laid out in LayerSkin's 75 layers:
#   ore_sensor_base           gold bevelled rim, two-tone face, cardinal and minor ticks, bezel,
#                             glass with its glint, the two dim arrow glyphs flanking 12 o'clock
#                             (down on the left, up on the right) and, new, the four gauge cells
#                             unlit
#   ore_sensor_needle_00..31  white tapered pointer, frame i at 11.25 deg * i clockwise from up;
#                             the even frames are the 64 px dial's 16 frames
#   ore_sensor_tail_00..31    new: a stub, the needle quad's first CL_TAIL_END - 14 px (distance 14
#                             to 15, same taper) mirrored through the centre, as the compass skin's
#                             short tail; the 64 px dial drew the whole opposite frame as its tail,
#                             which at rest ran straight through the gauge (LayerSkin draws the tail
#                             over the gauge, in a separate tinted layer)
#   ore_sensor_lamp_up/down   the lit glyph in the dial's red (its arrow_up / arrow_down)
#   ore_sensor_charge_00..03  one gauge cell each, in the compass gauge's amethyst lilac
#   ore_sensor_empty          the four gauge cells in the dial's red
#   ore_sensor_sonar_00..02   the 64 px dial's first three rings (LayerSkin sweeps three)
# Geometry: x right, y down, pixel centre = integer + 0.5, dial centre (32, 32), light from the
# top left. Needle, tail, rings and glyphs are rasterised with 4x4 supersampling, so their edges
# carry coverage alpha (they lie inside the opaque base); the base's outer edge is hard (alpha 0
# or 255) so the extruded edge of the in-hand model is clean. The renderer draws the sample in
# the 16 px window square (6, 6, 4 of 16), here pixels 24..39: over the glass and the bezel's
# inner corners, where pass 3 (recovered DESIGN3.md) drew the sample on this dial.

CONCEPT = "concept"
CL_SIZE = 64
CL_CENTRE = 32.0
CL_LIGHT = (-0.707, -0.707)   # top-left; lit = n . CL_LIGHT
CL_SUPERSAMPLE = 4            # 4x4 samples per pixel for the overlay layers and the glyphs

# Radial regions, by the pixel centre's distance r from CL_CENTRE.
CL_DIAL_R = 28.0       # r > 28: transparent
CL_FACE_OUTER = 23.0   # 23 < r <= 28: rim
CL_FACE_INNER = 13.0   # 13 < r <= 23: face
CL_GLASS_R = 10.0      # 10 < r <= 13: bezel ring; r <= 10: glass

# (minimum lit, colour) bands, brightest first; below the last band -> the dark colour
CL_RIM_BANDS = ((0.45, rgb("#fce796")), (0.0, rgb("#e6b83c")), (-0.45, rgb("#c69426")))
CL_RIM_DARK = rgb("#8c6418")
CL_BEZEL_BANDS = ((0.45, rgb("#6e7686")), (-0.45, rgb("#262a34")))
CL_BEZEL_DARK = rgb("#14161c")

CL_FACE_LIT = rgb("#40475a")
CL_FACE_SHADE = rgb("#333846")
CL_FACE_SPLIT = 2.0                 # dx + dy <= 2 is the lit half
CL_TICK_CARDINAL = rgb("#aab2c0")
CL_CARDINAL_TICK_R = (19.0, 23.0)   # 19 <= r < 23
CL_CARDINAL_TICK_HALF = 1.0         # |dx| <= 1 (N/S) or |dy| <= 1 (E/W): 2 px wide
CL_TICK_MINOR = rgb("#5b6272")
CL_MINOR_TICK_R = 20.5
CL_MINOR_TICK_SIZE = 1.0
CL_MINOR_TICK_DIRS = ((-0.707, 0.707), (0.707, 0.707))   # SW, SE; the upper two are the lamps

CL_GLASS = rgb("#111723")
CL_GLINT = rgb("#2b3d55")
CL_GLINT_R = (6.0, 8.5)
CL_GLINT_LIT = 0.6

CL_WHITE = (255, 255, 255)
CL_RED = rgb("#ff3b30")        # the lit lamps, and the gauge row on NO_CHARGE
CL_UNLIT = rgb("#282d3a")      # an unlit lamp glyph, and an unlit gauge cell
CL_LAMP_RADIUS = 18.0
CL_LAMP_ANGLE = 42.0           # degrees either side of 12 o'clock
CL_GLYPH_HALF = (3.0, 3.5)     # glyph-local origin = centre - CL_GLYPH_HALF (6 wide, 7 tall)
# Glyphs as unions of convex polygons in glyph-local coordinates. The head is 3 rows tall with
# 45-degree sides, so it steps 2 -> 4 -> 6 px wide, one step per row, and only the shaft is 2 px
# wide. DOWN is UP flipped top to bottom.
CL_UP_GLYPH = (
    ((3, 0), (6, 3), (0, 3)),             # head, apex up
    ((2, 3), (4, 3), (4, 7), (2, 7)),     # shaft
)
CL_DOWN_GLYPH = (
    ((2, 0), (4, 0), (4, 4), (2, 4)),     # shaft
    ((0, 4), (6, 4), (3, 7)),             # head, apex down
)
CL_LAMPS = {
    # name: (glyph, side of 12 o'clock)
    "up": (CL_UP_GLYPH, +1),
    "down": (CL_DOWN_GLYPH, -1),
}

CL_NEEDLE_BASE = (14.0, 2.5)   # (distance from centre, half-width across the needle)
CL_NEEDLE_TIP = (22.5, 0.6)
# The tail (new, this skin only): the needle's base segment, 14 .. CL_TAIL_END from the centre with
# the needle's own taper, mirrored through the centre. 15.0 makes the resting stub exactly pixel
# row 46 (r 14..15 straight down), so row 47 stays bare face between it and the gauge (rows 48..51):
# one clear pixel, as the compass skin's stub tail stops short of its bar. CL_TAIL_MAX_R bounds
# every tail pixel's centre (check_concept), which keeps every tail frame off the gauge (r >= 16.5).
CL_TAIL_END = 15.0
CL_TAIL_MAX_R = 16.5
CL_ANGLE_TOLERANCE = 6.0       # self-check: coverage-weighted centroid vs the frame's angle

CL_SONAR_R0 = 14.5
CL_SONAR_STEP = 2.5
CL_SONAR_HALF_WIDTH = 0.9
# The 64 px renderer drew its white rings tinted (0.55, 0.85, 1.0). LayerSkin draws every skin's
# rings untinted (alpha only: compass bakes its cyan into the texture), so the tint is baked in
# here: the same pale blue on screen, over the recovered rings' coverage alpha pixel for pixel.
CL_SONAR_TINT = (0.55, 0.85, 1.0)
CL_SONAR = tuple(round(255 * t) for t in CL_SONAR_TINT)   # (140, 217, 255)

# The charge gauge (new: the 64 px dial predates charges): four 4x4 cells side by side, one
# continuous bar x 24..39, rows 48..51, under the glass on the lower face, left to right =
# charge_00..03, symmetric about the dial's vertical axis (x <-> 63 - x). Each cell is one texel of
# the 16 px mip the hotbar shows (x and y multiples of 4), so the bar reads as one row of four
# texels there (x 6..9, row 12 of 16; the compass skin's gauge is x 6..9, row 11, one row higher
# only because this dial's bezel fills row 11). Rows 48..51 are the only
# grid rows that keep every cell pixel centre at r >= 14.5 (a full pixel of face to the bezel,
# r <= 13; row 44's centre pixels are bezel) and at r <= 22.0 (a full pixel to the rim, r > 23):
# r 16.51 .. 20.89 here. The cells cover the top row of the 6 o'clock cardinal tick (x 31..32,
# row 51; the tick is rows 51..54), which then shows 3 px tall straight under the bar's middle.
# Nothing of the resting needle reaches the bar: the tip points up and the stub tail (CL_TAIL_END)
# ends at row 46, a clear face row above it; LayerSkin draws the tail over the gauge.
CL_GAUGE_Y = 48
CL_GAUGE_X = (24, 28, 32, 36)  # one continuous bar, symmetric about the vertical axis, grid-aligned
CL_CELL = 4
CL_GAUGE_GRID = CL_SIZE // SIZE   # the hotbar mip's texel, in 64 px pixels
CL_GAUGE_R = (14.5, 22.0)         # every cell pixel centre's r: a full face pixel to bezel and rim
CL_GAUGE = tuple(tuple((x0 + i, CL_GAUGE_Y + j) for j in range(CL_CELL) for i in range(CL_CELL))
                 for x0 in CL_GAUGE_X)

# sha256 of the recovered textures' RGBA pixels (Image.tobytes(): 64x64, row by row, 4 bytes a
# pixel; transparent pixels are 0, 0, 0, 0), taken from release-1.1.0/concept/recovered/
# textures_64px/ on 2026-10-22, grouped: check_concept compares the layers drawn here against
# them, so the recovered art is asserted pixel for pixel wherever this script runs. A digest is of
# recovered files as they are, never of anything this skin adds ("base" is checked against the
# 64 px dial before the gauge, cl_build_dial), so moving the gauge cells or retinting a layer
# leaves every digest valid. Recomputing one (only where recovered/ exists): one sha256 fed
# Image.open(f).convert("RGBA").tobytes() of each of the group's files in the order listed (for
# "sonar", .getchannel("A").tobytes() of that).
CL_RECOVERED = {
    # ore_sensor_base
    "base": "fc68e414df15bb7dd31db2695fc4b5707c6ed835e24c0205e3e4f28672a0d0e5",
    # ore_sensor_arrow_up then ore_sensor_arrow_down
    "lamps": "0ec60c45a308c55611fa9411ddc98e889d4a465c132b7b0d1fa8b4bc0a802604",
    # ore_sensor_needle_00..15
    "needles": "4f9b641ef4e70a68a8f60605ac8d0f124b7ecb2ac240293debabe579177934f2",
    # the alpha channel (Image.getchannel("A")) of ore_sensor_sonar_00..02
    "sonar": "22c03daf7542d0601db46571f088405c113cf57fa9247fbdf4c69421bcc4069e",
}


def cl_offset(x, y):
    """Pixel (x, y)'s centre relative to the dial centre."""
    return x + 0.5 - CL_CENTRE, y + 0.5 - CL_CENTRE


def cl_radius(x, y):
    return math.hypot(*cl_offset(x, y))


def cl_lit(dx, dy):
    return (dx * CL_LIGHT[0] + dy * CL_LIGHT[1]) / math.hypot(dx, dy)


def cl_on_face(p):
    return CL_FACE_INNER < cl_radius(*p) <= CL_FACE_OUTER


def cl_banded(value, bands, dark):
    for floor, colour in bands:
        if value >= floor:
            return colour
    return dark


def cl_inside_convex(poly, px, py):
    """True when (px, py) is inside or on the convex polygon."""
    sign = 0
    for (ax, ay), (bx, by) in zip(poly, poly[1:] + poly[:1]):
        cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax)
        if cross:
            if sign == 0:
                sign = 1 if cross > 0 else -1
            elif (cross > 0) != (sign > 0):
                return False
    return True


def cl_coverage(inside):
    """Fraction of each pixel's 4x4 sample points that `inside(px, py)` accepts."""
    subs = [(i + 0.5) / CL_SUPERSAMPLE for i in range(CL_SUPERSAMPLE)]
    out = {}
    for y in range(CL_SIZE):
        for x in range(CL_SIZE):
            hits = sum(1 for sy in subs for sx in subs if inside(x + sx, y + sy))
            if hits:
                out[(x, y)] = hits / CL_SUPERSAMPLE ** 2
    return out


def cl_lamp_origin(side):
    """Top-left corner of a lamp's glyph box: its centre is CL_CENTRE + 18 * (sin +-42, -cos 42),
    snapped so the box lies on whole pixels. Every glyph pixel then sees the same 4x4 sample
    pattern, so each glyph is left-right symmetric and DOWN rasterises as the exact mirror of UP."""
    a = math.radians(CL_LAMP_ANGLE)
    cx = CL_CENTRE + side * CL_LAMP_RADIUS * math.sin(a)
    cy = CL_CENTRE - CL_LAMP_RADIUS * math.cos(a)
    return round(cx - CL_GLYPH_HALF[0]), round(cy - CL_GLYPH_HALF[1])


def cl_lamp_coverage(name):
    glyph, side = CL_LAMPS[name]
    ox, oy = cl_lamp_origin(side)
    polys = [tuple((ox + gx, oy + gy) for gx, gy in poly) for poly in glyph]
    cov = cl_coverage(lambda px, py: any(cl_inside_convex(poly, px, py) for poly in polys))
    off_face = [p for p in cov if not cl_on_face(p)]
    if off_face:
        raise AssertionError(f"concept {name} lamp pixels {off_face} are off the face annulus")
    return cov


def cl_needle_quad(degrees):
    """The tapered needle pointing `degrees` clockwise from up: base centre C + 14 d, half-width
    2.5 across; tip centre C + 22.5 d, half-width 0.6."""
    theta = math.radians(degrees)
    d = (math.sin(theta), -math.cos(theta))    # along the needle
    p = (math.cos(theta), math.sin(theta))     # across it

    def corner(dist_half, side):
        dist, half = dist_half
        return (CL_CENTRE + dist * d[0] + side * half * p[0],
                CL_CENTRE + dist * d[1] + side * half * p[1])

    return (corner(CL_NEEDLE_BASE, 1), corner(CL_NEEDLE_TIP, 1),
            corner(CL_NEEDLE_TIP, -1), corner(CL_NEEDLE_BASE, -1))


def cl_mirrored(quad):
    """The quad mirrored through the dial centre (a half turn)."""
    return tuple((2 * CL_CENTRE - x, 2 * CL_CENTRE - y) for x, y in quad)


def cl_tail_quad(degrees):
    """The tail of the needle pointing `degrees`: the needle quad's base segment, distance 14 to
    CL_TAIL_END with the needle's own taper (half-width 2.5 down to 2.28 at 15), mirrored
    through the centre. Its corners lie on the needle quad's edges, so the stub is a piece of the
    opposite needle frame, as the 64 px dial's tail was the whole of it."""
    base, tip, tip_other, base_other = cl_needle_quad(degrees)
    t = (CL_TAIL_END - CL_NEEDLE_BASE[0]) / (CL_NEEDLE_TIP[0] - CL_NEEDLE_BASE[0])

    def towards(a, b):
        return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)

    return cl_mirrored((base, towards(base, tip), towards(base_other, tip_other), base_other))


def cl_quad_coverage(quad):
    return cl_coverage(lambda px, py: cl_inside_convex(quad, px, py))


def cl_sonar_coverage(ring):
    ring_r = CL_SONAR_R0 + CL_SONAR_STEP * ring

    def inside(px, py):
        r = math.hypot(px - CL_CENTRE, py - CL_CENTRE)
        return abs(r - ring_r) <= CL_SONAR_HALF_WIDTH and r <= CL_FACE_OUTER

    # the base's regions are per pixel centre, so a ring pixel must also be a face pixel
    return {p: c for p, c in cl_coverage(inside).items() if cl_radius(*p) <= CL_FACE_OUTER}


def cl_face_colour(dx, dy, r):
    if CL_CARDINAL_TICK_R[0] <= r < CL_CARDINAL_TICK_R[1] and (
            abs(dx) <= CL_CARDINAL_TICK_HALF or abs(dy) <= CL_CARDINAL_TICK_HALF):
        return CL_TICK_CARDINAL
    for ux, uy in CL_MINOR_TICK_DIRS:
        if math.hypot(dx - CL_MINOR_TICK_R * ux, dy - CL_MINOR_TICK_R * uy) <= CL_MINOR_TICK_SIZE:
            return CL_TICK_MINOR
    return CL_FACE_LIT if dx + dy <= CL_FACE_SPLIT else CL_FACE_SHADE


def cl_base_colour(x, y):
    dx, dy = cl_offset(x, y)
    r = math.hypot(dx, dy)
    if r > CL_DIAL_R:
        return None
    if r > CL_FACE_OUTER:
        return cl_banded(cl_lit(dx, dy), CL_RIM_BANDS, CL_RIM_DARK)
    if r > CL_FACE_INNER:
        return cl_face_colour(dx, dy, r)
    if r > CL_GLASS_R:
        return cl_banded(cl_lit(dx, dy), CL_BEZEL_BANDS, CL_BEZEL_DARK)
    if CL_GLINT_R[0] <= r <= CL_GLINT_R[1] and cl_lit(dx, dy) >= CL_GLINT_LIT:
        return CL_GLINT
    return CL_GLASS


def cl_mix(under, over, amount):
    return tuple(round(u + (o - u) * amount) for u, o in zip(under, over))


def cl_tinted(cov, colour):
    """A layer of `colour` whose alpha is the coverage."""
    return {p: colour + (round(255 * c),) for p, c in cov.items()}


def cl_build_dial(lamps):
    """The 64 px dial's base as it was (recovered ore_sensor_base.png): rim, face, ticks, bezel,
    glass, glint, both glyphs unlit."""
    base = {}
    for y in range(CL_SIZE):
        for x in range(CL_SIZE):
            colour = cl_base_colour(x, y)
            if colour is not None:
                base[(x, y)] = colour
    # the unlit lamps are painted into the face, anti-aliased against it
    for name, cov in lamps.items():
        for p, amount in cov.items():
            if base[p] not in (CL_FACE_LIT, CL_FACE_SHADE):
                raise AssertionError(f"concept {name} lamp pixel {p} lands on a tick or another lamp")
            base[p] = cl_mix(base[p], CL_UNLIT, amount)
    return {p: colour + (255,) for p, colour in base.items()}


def cl_build_base(dial):
    """ore_sensor_base: the 64 px dial with the four gauge cells unlit on its face (the bare face,
    or the 6 o'clock cardinal tick's top row, which the bar's middle covers; see CL_GAUGE_Y)."""
    base = dict(dial)
    for i, cell in enumerate(CL_GAUGE):
        for p in cell:
            if p not in base or base[p][:3] not in (CL_FACE_LIT, CL_FACE_SHADE, CL_TICK_CARDINAL):
                raise AssertionError(f"concept gauge cell {i} pixel {p} is not on the face or its 6 o'clock tick")
            if base[p][:3] == CL_TICK_CARDINAL and not (abs(cl_offset(*p)[0]) <= CL_CARDINAL_TICK_HALF
                                                         and cl_offset(*p)[1] > 0):
                raise AssertionError(f"concept gauge cell {i} pixel {p} covers a tick other than 6 o'clock's")
            base[p] = CL_UNLIT + (255,)
    return base


def build_concept(pal):
    lamps = {name: cl_lamp_coverage(name) for name in CL_LAMPS}
    layers = {"ore_sensor_base": cl_build_base(cl_build_dial(lamps))}
    for i in range(NEEDLE_FRAMES):
        quad = cl_needle_quad(frame_angle(i))
        layers[needle(i)] = cl_tinted(cl_quad_coverage(quad), CL_WHITE)
        layers[tail(i)] = cl_tinted(cl_quad_coverage(cl_tail_quad(frame_angle(i))), CL_WHITE)
    for name, cov in lamps.items():
        layers[f"ore_sensor_lamp_{name}"] = cl_tinted(cov, CL_RED)
    for i, cell in enumerate(CL_GAUGE):
        layers[charge(i)] = {p: pal.AM_HI for p in cell}
    layers["ore_sensor_empty"] = {p: CL_RED + (255,) for cell in CL_GAUGE for p in cell}
    for k in range(len(SONAR)):              # LayerSkin's three rings (layer_names())
        layers[sonar(k)] = cl_tinted(cl_sonar_coverage(k), CL_SONAR)
    return layers


def cl_centroid_angle(pixels):
    """Coverage-weighted centroid direction, degrees clockwise from straight up."""
    total = sum(c[3] for c in pixels.values())
    sx = sum(cl_offset(x, y)[0] * c[3] for (x, y), c in pixels.items()) / total
    sy = sum(cl_offset(x, y)[1] * c[3] for (x, y), c in pixels.items()) / total
    return clockwise_angle(sx, sy)


def cl_expect(group, images, alpha_only=False):
    """Asserts that `images` hash to the recovered dial's digest for `group` (CL_RECOVERED)."""
    h = hashlib.sha256()
    for img in images:
        h.update(img.getchannel("A").tobytes() if alpha_only else img.tobytes())
    if h.hexdigest() != CL_RECOVERED[group]:
        raise AssertionError(f"concept {group} differ from the recovered 64 px dial (sha256 {h.hexdigest()})")


def check_concept(layers, pal):
    if sorted(layers) != sorted(layer_names()):
        raise AssertionError(f"concept layer set {sorted(layers)} is not the {len(layer_names())} LayerSkin loads")
    for name, pixels in layers.items():
        if not pixels:
            raise AssertionError(f"concept {name} is empty")
        off = [p for p in pixels if not (0 <= p[0] < CL_SIZE and 0 <= p[1] < CL_SIZE)]
        if off:
            raise AssertionError(f"concept {name} has pixels off the {CL_SIZE}x{CL_SIZE} canvas: {off}")
        clear = [p for p, c in pixels.items() if not 0 < c[3] <= 255]
        if clear:
            raise AssertionError(f"concept {name} lists pixels without alpha: {clear}")

    # the silhouette: the dial disc, hard-edged
    base = layers["ore_sensor_base"]
    disc = {(x, y) for y in range(CL_SIZE) for x in range(CL_SIZE) if cl_radius(x, y) <= CL_DIAL_R}
    if set(base) != disc:
        raise AssertionError("the concept base is not exactly the dial disc (r <= 28)")
    partial = [p for p, c in base.items() if c[3] != 255]
    if partial:
        raise AssertionError(f"the concept base outline has partial alpha at {partial}")
    for name, pixels in layers.items():
        stray = [p for p in pixels if p not in base]
        if stray:
            raise AssertionError(f"concept {name} draws where the base is transparent: {stray}")

    # the recovered dial, pixel for pixel: the base outside the gauge cells (the dial drawn here
    # is the recovered base, and the layer is that dial with only the cells changed), the lamps
    # (its arrows), the even needle frames (its 16 frames) and the rings' coverage (its first three)
    cells = [p for cell in CL_GAUGE for p in cell]
    dial = cl_build_dial({name: cl_lamp_coverage(name) for name in CL_LAMPS})
    cl_expect("base", [layer_image(dial, CL_SIZE)])
    changed = sorted(p for p in set(dial) | set(base) if dial.get(p) != base.get(p) and p not in cells)
    if changed:
        raise AssertionError(f"the concept base differs from the recovered dial outside the gauge cells at {changed}")
    cl_expect("lamps", [layer_image(layers[f"ore_sensor_lamp_{n}"], CL_SIZE) for n in ("up", "down")])
    cl_expect("needles", [layer_image(layers[needle(2 * i)], CL_SIZE) for i in range(NEEDLE_FRAMES // 2)])
    cl_expect("sonar", [layer_image(layers[sonar(k)], CL_SIZE) for k in range(len(SONAR))], alpha_only=True)

    # needle and tail frames point where LayerSkin expects them
    worst = 0.0
    for i in range(NEEDLE_FRAMES):
        for name, want in ((needle(i), frame_angle(i)), (tail(i), frame_angle(i) + 180.0)):
            off = angle_off(cl_centroid_angle(layers[name]), want)
            if off > CL_ANGLE_TOLERANCE:
                raise AssertionError(f"concept {name} points {off:.1f} deg away from {want % 360.0}")
            worst = max(worst, off)
        if any(c[:3] != CL_WHITE for name in (needle(i), tail(i)) for c in layers[name].values()):
            raise AssertionError(f"concept frame {i}: the needle or tail is not baked white")
    # ...and are one shape each: the quads' turns and mirrors map the 4x4 sample grid onto itself
    # (the centre is a pixel corner), so they hold pixel for pixel. A quarter turn is 8 frames;
    # frame -i is frame i mirrored left to right. With the even needle frames pinned to the
    # recovered dial above, this ties every odd needle frame to frames 1 and 3. The tail is a stub
    # (CL_TAIL_END), no longer the whole opposite needle: its frames turn and mirror the same way,
    # each lies inside the opposite needle frame (a piece of it, never more opaque: the stub is the
    # needle quad's base segment mirrored), and none reaches past CL_TAIL_MAX_R.
    def turned(pixels):                      # a quarter turn clockwise about the centre
        return {(CL_SIZE - 1 - y, x): c for (x, y), c in pixels.items()}

    def mirrored(pixels):                    # left to right
        return {(CL_SIZE - 1 - x, y): c for (x, y), c in pixels.items()}

    for i in range(NEEDLE_FRAMES):
        for layer in (needle, tail):
            if layers[layer((i + NEEDLE_FRAMES // 4) % NEEDLE_FRAMES)] != turned(layers[layer(i)]):
                raise AssertionError(f"concept {layer((i + NEEDLE_FRAMES // 4) % NEEDLE_FRAMES)} is not "
                                     f"{layer(i)} turned 90 deg")
            if layers[layer(-i % NEEDLE_FRAMES)] != mirrored(layers[layer(i)]):
                raise AssertionError(f"concept {layer(-i % NEEDLE_FRAMES)} is not {layer(i)} mirrored")
        opposite = layers[needle((i + NEEDLE_FRAMES // 2) % NEEDLE_FRAMES)]
        outside = [p for p, c in layers[tail(i)].items() if p not in opposite or c[3] > opposite[p][3]]
        if outside:
            raise AssertionError(f"concept {tail(i)} is not a piece of the opposite needle frame at {outside}")
        far = [p for p in layers[tail(i)] if cl_radius(*p) > CL_TAIL_MAX_R]
        if far:
            raise AssertionError(f"concept {tail(i)} reaches past r {CL_TAIL_MAX_R} at {far}")

    # lamps: on the face, red, disjoint, DOWN the exact mirror of UP
    up, down = layers["ore_sensor_lamp_up"], layers["ore_sensor_lamp_down"]
    for name, lamp in (("up", up), ("down", down)):
        if any(not cl_on_face(p) for p in lamp) or any(c[:3] != CL_RED for c in lamp.values()):
            raise AssertionError(f"concept lamp {name} is not a red glyph on the face")
    if set(up) & set(down):
        raise AssertionError(f"the concept lamps overlap at {sorted(set(up) & set(down))}")
    (ux, uy), (dx, dy) = cl_lamp_origin(CL_LAMPS["up"][1]), cl_lamp_origin(CL_LAMPS["down"][1])
    bottom_row = round(2 * CL_GLYPH_HALF[1]) - 1
    if {(ux + x - dx, uy + bottom_row - (y - dy)): c for (x, y), c in down.items()} != up:
        raise AssertionError("the concept down lamp is not the up lamp flipped top to bottom")
    if ux <= CL_CENTRE or dx + 2 * CL_GLYPH_HALF[0] >= CL_CENTRE or uy + 2 * CL_GLYPH_HALF[1] >= CL_CENTRE:
        raise AssertionError("the concept lamps are not up on the right, down on the left, in the upper half")

    # rings: on the face only (never on the rim or the bezel), the baked tint, growing
    radii = []
    for k in range(len(SONAR)):
        ring = layers[sonar(k)]
        bad = [p for p in ring if not cl_on_face(p)]
        if bad:
            raise AssertionError(f"concept {sonar(k)} leaves the face at {bad}")
        if any(c[:3] != CL_SONAR for c in ring.values()):
            raise AssertionError(f"concept {sonar(k)} is not the baked sonar tint {CL_SONAR}")
        radii.append(sum(cl_radius(*p) * c[3] for p, c in ring.items()) / sum(c[3] for c in ring.values()))
    if radii != sorted(radii):
        raise AssertionError(f"concept sonar rings do not grow: {radii}")

    # gauge: four 4x4 cells left to right in one continuous bar, on the face a full pixel off the
    # bezel and the rim, symmetric, on the hotbar mip's texel grid, unlit in the base, lit one at a
    # time, red together on NO_CHARGE, and clear of the resting needle and tail that LayerSkin
    # draws over them (and of every tail frame: the stub never reaches the bar)
    if len(CL_GAUGE) != len(GAUGE) or len(set(cells)) != len(cells):
        raise AssertionError("the concept gauge is not four separate cells")
    for i, cell in enumerate(CL_GAUGE):
        x0, y0 = min(x for x, _ in cell), min(y for _, y in cell)
        if x0 % CL_GAUGE_GRID or y0 % CL_GAUGE_GRID or CL_CELL != CL_GAUGE_GRID:
            raise AssertionError(f"concept gauge cell {i} at ({x0}, {y0}) is not one {CL_GAUGE_GRID} px texel of the 16 px mip")
        if i and (x0 != max(x for x, _ in CL_GAUGE[i - 1]) + 1 or {y for _, y in cell} != {y for _, y in CL_GAUGE[0]}):
            raise AssertionError(f"concept gauge cell {i} does not continue the bar from cell {i - 1}")
    if {(CL_SIZE - 1 - x, y) for x, y in cells} != set(cells):
        raise AssertionError("the concept gauge bar is not symmetric about the dial's vertical axis")
    near = [(p, round(cl_radius(*p), 2)) for p in cells if not CL_GAUGE_R[0] <= cl_radius(*p) <= CL_GAUGE_R[1]]
    if near:
        raise AssertionError(f"concept gauge pixels within a pixel of the bezel or the rim: {near}")
    for i, cell in enumerate(CL_GAUGE):
        xs, ys = {x for x, _ in cell}, {y for _, y in cell}
        if len(cell) != CL_CELL ** 2 or len(xs) != CL_CELL or len(ys) != CL_CELL:
            raise AssertionError(f"concept gauge cell {i} is not a {CL_CELL}x{CL_CELL} square")
        off = [p for p in cell if not cl_on_face(p)]
        if off:
            raise AssertionError(f"concept gauge cell {i} leaves the face annulus at {off}")
        if any(base[p][:3] != CL_UNLIT for p in cell):
            raise AssertionError(f"the concept base does not hold gauge cell {i} unlit")
        if layers[charge(i)] != {p: pal.AM_HI for p in cell}:
            raise AssertionError(f"concept {charge(i)} is not cell {i} in the compass gauge's lilac")
    if [min(x for x, _ in cell) for cell in CL_GAUGE] != sorted(min(x for x, _ in cell) for cell in CL_GAUGE):
        raise AssertionError("concept gauge cells are not left to right")
    if layers["ore_sensor_empty"] != {p: CL_RED + (255,) for p in cells}:
        raise AssertionError("the concept no-charge row is not the four gauge cells in red")
    # no tail frame touches the bar, not even at a corner (one clear pixel between them, every
    # tail pixel counted whatever its alpha, as the compass skin's stub stops short of its bar),
    # nor does the resting tip (frame 0, pointing up); the needle frames around 6 o'clock (14..18)
    # cross the bar, as the compass skin's needle crosses its own
    def touching(pixels):
        return sorted(p for p in pixels if any((p[0] + u, p[1] + v) in cells for u in (-1, 0, 1) for v in (-1, 0, 1)))

    for name in [tail(i) for i in range(NEEDLE_FRAMES)] + [needle(0)]:
        if touching(layers[name]):
            raise AssertionError(f"concept {name} touches the gauge bar at {touching(layers[name])}")
    return worst, radii


# ---- compass_b skin: the compass with chevron lamps -----------------------------
# Alex's third skin (2026-10-22, release-1.1.0/ARROWS-SKIN.md): the compass skin with the
# concept dial's idea for the lamps, an arrow for each direction on the upper face, in place of
# the two lamps in the rim. 16 px; every layer but three is the compass's, byte for byte:
#   ore_sensor_base           the compass base with ten pixels changed: the four N/S mark pixels
#                             (7, 2), (8, 2), (7, 13), (8, 13) are plain gold rim, what the band
#                             painter draws there (build_base without the marks), and the six
#                             chevron pixels are the unlit lamp red, LAMP_OFF, as the marks were
#   ore_sensor_lamp_up        ^ on the upper-right face: peak (11, 5), feet (10, 6) and (12, 6)
#   ore_sensor_lamp_down      v on the upper-left face: feet (3, 5) and (5, 5), point (4, 6): the
#                             up chevron mirrored left to right (x -> 15 - x), its two rows swapped
# Both lit in the compass's lamp red, RED; the renderer pulses them like the compass lamps.

COMPASS_B = "compass_b"
ARROW_LAMPS = {
    "up": ((11, 5), (10, 6), (12, 6)),     # ^: the peak, then its two feet one row down
    "down": ((3, 5), (5, 5), (4, 6)),      # v: the two feet, then the point one row down
}
ARROW_AREA = ("the face outside the window", FACE - WINDOW)
GOLD_BAND_TONES = ("G_HI", "G_LIGHT", "G_MID", "G_LOW")   # build_base's band colours


def build_compass_b(pal):
    """The compass layers with the base's ten pixels and the two lamp layers changed."""
    layers = build_layers(pal)
    rim = build_base(pal, lamps={})                 # the compass base before the N/S marks
    base = dict(layers["ore_sensor_base"])
    for pixels in LAMPS.values():
        for p in pixels:
            base[p] = rim[p]                        # the old marks: plain gold rim
    for pixels in ARROW_LAMPS.values():
        for p in pixels:
            base[p] = pal.LAMP_OFF                  # the chevrons, unlit
    layers["ore_sensor_base"] = base
    for name, pixels in ARROW_LAMPS.items():
        layers[f"ore_sensor_lamp_{name}"] = {p: pal.RED for p in pixels}
    return layers


def check_compass_b(layers, pal):
    """The compass checks on the compass_b layers, then ARROWS-SKIN.md's."""
    # every compass check, with the lamps where this skin has them
    worst, radii = check_layers(layers, pal, ARROW_LAMPS, ARROW_AREA)
    if pal.LAMP_OFF != (114, 0, 0, 255) or pal.RED != (255, 20, 20, 255):
        raise AssertionError(f"the compass's unlit and lit lamp reds are {pal.LAMP_OFF} and {pal.RED}, "
                             f"not the brief's (114, 0, 0) and (255, 20, 20)")

    # every layer but the base and the two lamps is the compass's, byte for byte
    compass = build_layers(pal)
    own = ("ore_sensor_base", "ore_sensor_lamp_up", "ore_sensor_lamp_down")
    for name in layer_names():
        if name in own:
            continue
        if (layers[name] != compass[name]
                or layer_image(layers[name], SIZE).tobytes() != layer_image(compass[name], SIZE).tobytes()):
            raise AssertionError(f"compass_b {name} is not the compass's {name}")

    # the base: the compass's but for exactly ten pixels, the old marks plain gold rim, the
    # chevrons unlit
    marks = [p for pixels in LAMPS.values() for p in pixels]
    chevrons = [p for pixels in ARROW_LAMPS.values() for p in pixels]
    if len(set(marks)) != 4 or len(set(chevrons)) != 6 or set(marks) & set(chevrons):
        raise AssertionError("the compass_b skin does not change four mark pixels and six chevron pixels")
    rim = build_base(pal, lamps={})
    base, old = layers["ore_sensor_base"], compass["ore_sensor_base"]
    changed = {p: base.get(p) for p in set(base) | set(old) if base.get(p) != old.get(p)}
    want = {p: rim[p] for p in marks} | {p: pal.LAMP_OFF for p in chevrons}
    if changed != want:
        raise AssertionError(f"the compass_b base differs from the compass base at {sorted(changed)}, "
                             f"want exactly {sorted(want)} with the rim and unlit-lamp colours")
    gold = {getattr(pal, tone) for tone in GOLD_BAND_TONES}
    for p in marks:
        if p not in BAND or base[p] not in gold:
            raise AssertionError(f"the old mark pixel {p} is not plain gold rim: {base[p]}")

    # the lamps: exactly the six chevron pixels, lit red, each chevron its shape and side
    for name, pixels in ARROW_LAMPS.items():
        if layers[f"ore_sensor_lamp_{name}"] != {p: pal.RED for p in pixels}:
            raise AssertionError(f"compass_b lamp {name} is not {pixels} in the lamp red")
    up, down = ARROW_LAMPS["up"], ARROW_LAMPS["down"]
    (px, py), feet = up[0], set(up[1:])
    if feet != {(px - 1, py + 1), (px + 1, py + 1)}:
        raise AssertionError(f"the up lamp {up} is not a ^ chevron")
    (qx, qy), feet = down[-1], set(down[:-1])
    if feet != {(qx - 1, qy - 1), (qx + 1, qy - 1)}:
        raise AssertionError(f"the down lamp {down} is not a v chevron")
    top, bottom = min(y for _, y in up), max(y for _, y in up)
    if {(SIZE - 1 - x, top + bottom - y) for x, y in up} != set(down):
        raise AssertionError("the down lamp is not the up lamp mirrored left to right with its rows swapped")
    if not (all(x + 0.5 > CENTRE and y + 0.5 < CENTRE for x, y in up)
            and all(x + 0.5 < CENTRE and y + 0.5 < CENTRE for x, y in down)):
        raise AssertionError("the compass_b lamps are not up on the upper right, down on the upper left")
    # on the face of the compass base: present, not the window, the rim, a tick or a gauge cell
    for p in chevrons:
        if p not in base or p not in FACE or p in WINDOW or p in OUTLINE | BAND or p in TICKS or p in GAUGE:
            raise AssertionError(f"chevron pixel {p} is not bare face of the compass base")
    # the approved chevrons (ARROWS-SKIN.md, the "inside, shifted" mock-up row), pinned
    approved = {"up": {(11, 5), (10, 6), (12, 6)}, "down": {(3, 5), (5, 5), (4, 6)}}
    if {name: set(pixels) for name, pixels in ARROW_LAMPS.items()} != approved:
        raise AssertionError(f"the compass_b lamps {ARROW_LAMPS} are not the approved chevrons {approved}")
    return worst, radii


# ---- skins ---------------------------------------------------------------
# The art this script can draw: skin id -> (native texture size, layer builder, self-check). The
# layer set is the one LayerSkin loads (layer_names()). A new skin goes through --preview for
# Alex's approval, and only then is written and added to Skins in the mod: "concept" (the v0.0.1
# dial, 64 px, recovered; see its section) and "compass_b" (the compass with chevron lamps, 16 px;
# see its section) are at that gate. The preview sheet draws every skin at its native size (Art)
# on the same 12x-of-16-px cells.

SKIN_ART = {
    "compass": (SIZE, build_layers, check_layers),
    CONCEPT: (CL_SIZE, build_concept, check_concept),
    COMPASS_B: (SIZE, build_compass_b, check_compass_b),
}
# The LOCKED preview cells' needle frame where a skin's differs from LOCKED_FRAME (5, 56.25 deg):
# concept's lamps sit about 42 deg either side of 12 o'clock, so its tip runs into the lit up
# lamp at frames 3..5 (and the down lamp at 27..29), which the needle rows still show; frame 6
# (67.5 deg) is the first clear one and the angle the 64 px dial's own sheet showed LOCKED at.
# Compass B's chevrons sit on the face the needle sweeps: its tip covers the up chevron at frames 5
# and 6 and the down one at 26..28, and at frame 5 the red tip on the unlit ^ makes the down-lamp
# cell read as both lamps lit; frame 8 (90 deg) is the first frame clockwise from 5 whose tip and
# tail neither cover nor touch either chevron.
PREVIEW_LOCKED_FRAME = {CONCEPT: 6, COMPASS_B: 8}


def default_preview(skin):
    name = "dial_preview.png" if skin == DEFAULT_SKIN else f"dial_preview_{skin}.png"
    return ROOT / "build" / name


def main():
    global _client_jar
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--skin", default=DEFAULT_SKIN,
                        help=f"the skin to generate: one of {', '.join(SKIN_ART)} (default {DEFAULT_SKIN})")
    parser.add_argument("--scale", type=int, default=1,
                        help="write the textures this many times their native size, nearest neighbour")
    parser.add_argument("--preview", type=Path, nargs="?", const=True, default=None,
                        help="only render the skin's 12x preview sheet (to PATH, default build/dial_preview[_ID].png) "
                             "and write nothing under src")
    parser.add_argument("--client-jar", type=Path, default=CLIENT_JAR,
                        help="a client jar holding the vanilla textures (the Forge 1.20.1 client-extra jar, or a 26.x client jar)")
    args = parser.parse_args()
    _client_jar = args.client_jar
    if args.skin not in SKIN_ART:
        parser.error(f"no art for skin '{args.skin}' in this script (have: {', '.join(SKIN_ART)}); "
                     f"a new skin's builder goes into SKIN_ART first")
    if args.scale < 1:
        parser.error("--scale must be 1 or more")
    preview_only = args.preview is not None
    preview = args.preview if isinstance(args.preview, Path) else default_preview(args.skin)

    pal = Palette()
    native, build, check = SKIN_ART[args.skin]
    layers = build(pal)
    worst, radii = check(layers, pal)
    art = Art(layers, native, PREVIEW_LOCKED_FRAME.get(args.skin, LOCKED_FRAME))
    if preview_only:
        write_preview(art, pal, preview)
        print(f"preview of skin {args.skin} (nothing under src written): {preview}")
        return

    if args.skin == DEFAULT_SKIN:
        item_parent = check_item_model(layers)
    removed = write_assets(layers, native, args.skin, args.scale)
    textures, _, _ = skin_dirs(args.skin)
    for name, pixels in layers.items():
        if name.startswith(("ore_sensor_needle_", "ore_sensor_tail_")):
            continue
        print(f"  {name:<24} {len(pixels):4d} px")
    counts = [len(layers[needle(i)]) for i in range(NEEDLE_FRAMES)]
    tails = sorted({len(layers[tail(i)]) for i in range(NEEDLE_FRAMES)})
    tail_sizes = f"tails {tails[0]} px each" if len(tails) == 1 else f"tails {tails[0]}..{tails[-1]} px"
    print(f"  needle frames: {NEEDLE_FRAMES} x ({min(counts)}..{max(counts)} px), sizes {counts}; {tail_sizes}")
    print(f"needle centroids within {worst:.1f} deg of their frame angle")
    print("sonar ring mean radii: " + ", ".join(f"{r:.2f}" for r in radii))
    size = native * args.scale
    print(f"skin {args.skin}: {len(layers)} {size}x{size} textures + models under {textures}")
    print(f"removed {len(removed)} retired files" + (f": {', '.join(removed)}" if removed else ""))
    if args.skin == DEFAULT_SKIN:
        # the sensor screen and the mod icon belong to the mod, not to a skin: only the default
        # run writes them (the icon only where the branch ships one); the item model is the
        # branch's, checked above and never written
        frames = slot_frames()
        check_gui(frames)
        gui = write_gui(build_gui(frames))
        icon = write_icon(art)
        print(f"item model: {MODELS / 'ore_sensor.json'} (parent {item_parent}) checked, not written")
        print(f"sensor screen: {len(frames)} slot frames on a {PANEL_SIZE[0]}x{PANEL_SIZE[1]} panel, "
              f"{GUI_CANVAS}x{GUI_CANVAS} canvas: {gui}")
        if icon:
            print(f"mod list icon: {icon[0]}x{icon[1]} ({ICON_SCALE}x the dormant dial): {ICON}")
        else:
            print(f"mod list icon: none, this branch ships no {ICON.name} (nothing written)")
    write_preview(art, pal, preview)
    print(f"preview: {preview}")


if __name__ == "__main__":
    main()
