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

Usage: python3 tools/gen_textures.py [--preview PATH] [--client-jar PATH]
Deterministic, Pillow is the only dependency; it reads the vanilla textures straight out of
the Forge client-extra jar (palette check, the preview's hotbar and sample). It writes every
layer's PNG and item/generated model JSON, rewrites models/item/ore_sensor.json (display block
kept verbatim), deletes every other ore_sensor_* texture/model (the retired 64 px layers) so
nothing stale reaches the jar, writes textures/gui/ore_sensor.png (the sensor screen, see the
GUI section) and a 12x preview (default build/dial_preview.png) with the runtime tints applied.

Preview, one row each (12x, every texel a 12 px block, on the inventory slot grey):
  1  DORMANT      grey needle at rest, no sample, with 0, 1, 17, 33 and 49 shards
  2  SEARCHING    sonar ring 0, 1, 2 at the start of its third of the sweep, gauge 3 of 4
  3  LOCKED       needle frame 5: pulse high + top lamp, pulse high + bottom lamp, lost
                  (steady dim tip), pulse low
  4  NO_CHARGE    the red row at the pulse's high and low alpha
  5, 6            all 32 needle frames (red tip at pulse high + light tail), 16 per row
  7  GAUGE        1, 2, 3, 4 cells lit
  8  CHARGE PULSE no sample, 40 shards: brightness pulse low and high (all other rows at high)
  9  HOTBAR       DORMANT, SEARCHING ring 0, LOCKED top lamp, NO_CHARGE high in the first
                  four slots of the vanilla hotbar (gui/widgets.png) at GUI scale 2, as the
                  concept sheet does, then the same strip blown up 4x to inspect
Rows 2-4, 7 and 9 put a 4x4 diamond in the window as a stand-in for the sample item (the
renderer draws the real item there).
"""

import argparse
import json
import math
import zipfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "oresense"
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

def build_base(pal):
    """Concept A without the needle, sonar and sample, with both lamps and every gauge cell unlit."""
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
    for pixels in LAMPS.values():
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


def check_layers(layers, pal):
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
    for name, pixels in LAMPS.items():
        lamp = layers[f"ore_sensor_lamp_{name}"]
        if set(lamp) != set(pixels) or any(p not in BAND for p in lamp):
            raise AssertionError(f"lamp {name} is not in the gold band at {pixels}")
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

def layer_image(pixels):
    img = Image.new("RGBA", (SIZE, SIZE), CLEAR)
    for (x, y), colour in pixels.items():
        img.putpixel((x, y), colour)
    return img


def write_json(path, obj):
    path.write_text(json.dumps(obj, indent=1))


def update_item_model():
    path = MODELS / "ore_sensor.json"
    display = json.loads(path.read_text())["display"]
    write_json(path, {
        "parent": "builtin/entity",
        "gui_light": "front",
        "textures": {"particle": "oresense:item/ore_sensor_base"},
        "display": display,
    })


def write_assets(layers):
    """Writes every layer and its model, then deletes every other ore_sensor_* layer file (the
    retired 64 px set: needle, arrow_up/down, charge_04..07, sonar_03) so none reaches the jar."""
    TEXTURES.mkdir(parents=True, exist_ok=True)
    MODELS.mkdir(parents=True, exist_ok=True)
    for name, pixels in layers.items():
        layer_image(pixels).save(TEXTURES / f"{name}.png", format="PNG")
        write_json(MODELS / f"{name}.json", {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"oresense:item/{name}"},
        })
    removed = []
    for folder, suffix in ((TEXTURES, ".png"), (MODELS, ".json")):
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
# The canvas is 256x256 because GuiGraphics.blit's short form assumes a
# texture that size.

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
# OreSensorScreen draws ("Shards" ends at x 47, "Ore" at x 135).
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
    GUI_TEXTURES.mkdir(parents=True, exist_ok=True)
    path = GUI_TEXTURES / "ore_sensor.png"
    img.save(path, format="PNG")
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


def diamond_4(pal):
    """The concept sheet's 4x4 stand-in for a diamond sample, in the vanilla diamond tones."""
    return [
        [None, pal.D_PALE, pal.D_MID, None],
        [pal.D_PALE, pal.D_WHITE, pal.D_MID, pal.D_DEEP],
        [pal.D_MID, pal.D_MID, pal.D_DEEP, pal.D_DARK],
        [None, pal.D_DEEP, pal.D_DARK, None],
    ]


class Dial:
    """A 16x16 RGBA dial composited like the item sheet: every layer on the same plane, drawn
    in order, (r, g, b) times the tint, src-alpha over; alpha under 0.1 is discarded."""

    def __init__(self, layers):
        self.layers = layers
        self.px = {(x, y): (0.0, 0.0, 0.0, 0.0) for y in range(SIZE) for x in range(SIZE)}
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
        for j, row in enumerate(grid):
            for i, c in enumerate(row):
                if c is not None:
                    self.px[(6 + i, 6 + j)] = tuple(float(v) for v in c[:3]) + (1.0,)
        return self

    def image(self):
        img = Image.new("RGBA", (SIZE, SIZE), CLEAR)
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


def dormant(layers, charges=0, charge_brightness=1.0):
    return rest_needle(gauge(Dial(layers), charges, charge_brightness))


def searching(layers, pal, ring, charges=40):
    # SensorRenderer: phase p, ring k = floor(p * rings); ring k-1 fading behind ring k
    dial = Dial(layers)
    p = ring / len(SONAR)
    if ring > 0:
        dial.draw(sonar(ring - 1), PLAIN, SONAR_ALPHA[1] * (1 - p))
    dial.draw(sonar(ring), PLAIN, SONAR_ALPHA[0] * (1 - p))
    return rest_needle(gauge(dial, charges)).sample(diamond_4(pal))


def locked(layers, pal, frame, lamp=None, high=True, lost=False, charges=40):
    dial = gauge(Dial(layers), charges)
    b = 1.0 if high else PULSE_LOW
    dial.draw(tail(frame), LOCK_TAIL)
    dial.draw(needle(frame), LOST_TIP if lost else tuple(c * b for c in LOCK_TIP))
    if lamp:
        dial.draw(f"ore_sensor_lamp_{lamp}")
    return dial.sample(diamond_4(pal))


def no_charge(layers, pal, high):
    dial = Dial(layers).draw("ore_sensor_empty", PLAIN, EMPTY_ALPHA[1 if high else 0])
    return rest_needle(dial).sample(diamond_4(pal))


def needle_doc(layers, frame):
    return Dial(layers).draw(tail(frame), LOCK_TAIL).draw(needle(frame), LOCK_TIP)


def gauge_only(layers, pal, cells):
    return rest_needle(gauge(Dial(layers), cells * MAX_CHARGES // len(GAUGE))).sample(diamond_4(pal))


def enlarged(img):
    cell = Image.new("RGBA", (SIZE, SIZE), PREVIEW_CELL_BG)
    cell.alpha_composite(img)
    return cell.resize((SIZE * PREVIEW_SCALE,) * 2, Image.NEAREST)


def hotbar_strip(items):
    """The vanilla hotbar's first four slots (widgets.png) at GUI scale 2, items in them
    (the concept sheet's hotbar_strip)."""
    w = vanilla("gui/widgets")
    bar = Image.new("RGBA", (83, 22), HOTBAR_WORLD)
    strip = Image.new("RGBA", (83, 22), CLEAR)
    strip.paste(w.crop((0, 0, 81, 22)), (0, 0))
    strip.paste(w.crop((180, 0, 182, 22)), (81, 0))
    bar.alpha_composite(strip)
    bar = bar.resize((166, 44), Image.NEAREST)
    for i, it in enumerate(items):
        icon = it.resize((32, 32), Image.NEAREST)
        bar.alpha_composite(icon, (2 * (3 + 20 * i), 6))
    return bar


def preview_rows(layers, pal):
    hotbar = hotbar_strip([dormant(layers).image(), searching(layers, pal, 0).image(),
                           locked(layers, pal, LOCKED_FRAME, "up").image(),
                           no_charge(layers, pal, True).image()])
    zoom = hotbar.resize((hotbar.width * HOTBAR_ZOOM, hotbar.height * HOTBAR_ZOOM), Image.NEAREST)
    half = NEEDLE_FRAMES // 2
    return [
        ("DORMANT, no sample:\n0, 1, 17, 33, 49 shards", [
            enlarged(dormant(layers, charges).image()) for charges in (0, 1, 17, 33, 49)]),
        ("SEARCHING:\nring 0, 1, 2", [enlarged(searching(layers, pal, k).image()) for k in range(len(SONAR))]),
        ("LOCKED frame 5:\nup lamp, down lamp,\nlost, pulse low", [
            enlarged(locked(layers, pal, LOCKED_FRAME, "up").image()),
            enlarged(locked(layers, pal, LOCKED_FRAME, "down").image()),
            enlarged(locked(layers, pal, LOCKED_FRAME, lost=True).image()),
            enlarged(locked(layers, pal, LOCKED_FRAME, "up", high=False).image())]),
        ("NO_CHARGE:\npulse high, low", [enlarged(no_charge(layers, pal, h).image()) for h in (True, False)]),
        ("needle 00-15", [enlarged(needle_doc(layers, i).image()) for i in range(half)]),
        ("needle 16-31", [enlarged(needle_doc(layers, i).image()) for i in range(half, NEEDLE_FRAMES)]),
        ("GAUGE: 1-4 cells", [enlarged(gauge_only(layers, pal, n).image()) for n in range(1, len(GAUGE) + 1)]),
        ("CHARGE PULSE:\nno sample, 40 shards\npulse low, high", [
            enlarged(dormant(layers, 40, b).image()) for b in (CHARGE_PULSE_LOW, 1.0)]),
        ("HOTBAR, GUI scale 2\n(then 4x)", [hotbar, zoom]),
    ]


def write_preview(layers, pal, path):
    rows = preview_rows(layers, pal)
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


def main():
    global _client_jar
    parser = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    parser.add_argument("--preview", type=Path, default=ROOT / "build" / "dial_preview.png",
                        help="where to write the 12x review preview")
    parser.add_argument("--client-jar", type=Path, default=CLIENT_JAR,
                        help="the Forge client-extra jar holding the vanilla textures")
    args = parser.parse_args()
    _client_jar = args.client_jar

    pal = Palette()
    layers = build_layers(pal)
    worst, radii = check_layers(layers, pal)
    frames = slot_frames()
    check_gui(frames)
    removed = write_assets(layers)
    update_item_model()
    write_preview(layers, pal, args.preview)
    gui = write_gui(build_gui(frames))
    for name, pixels in layers.items():
        if name.startswith(("ore_sensor_needle_", "ore_sensor_tail_")):
            continue
        print(f"  {name:<24} {len(pixels):4d} px")
    counts = [len(layers[needle(i)]) for i in range(NEEDLE_FRAMES)]
    print(f"  needle frames: {NEEDLE_FRAMES} x (2..3 px), sizes {counts}; tails 1 px each")
    print(f"needle centroids within {worst:.1f} deg of their frame angle")
    print("sonar ring mean radii: " + ", ".join(f"{r:.2f}" for r in radii))
    print(f"wrote {len(layers)} {SIZE}x{SIZE} textures + models under {ASSETS}")
    print(f"removed {len(removed)} retired files" + (f": {', '.join(removed)}" if removed else ""))
    print(f"preview: {args.preview}")
    print(f"sensor screen: {len(frames)} slot frames on a {PANEL_SIZE[0]}x{PANEL_SIZE[1]} panel, "
          f"{GUI_CANVAS}x{GUI_CANVAS} canvas: {gui}")


if __name__ == "__main__":
    main()
