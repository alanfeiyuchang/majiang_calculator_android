#!/usr/bin/env python3
"""
Generate Google Play "7-inch tablet" and "10-inch tablet" screenshots.

The app is phone-only (portrait-locked, no tablet-specific layout), so
these just center a real phone screenshot on a larger gradient canvas
with a caption — the same accepted approach many phone-only apps use
to satisfy Play Console's tablet screenshot requirement. Same color
scheme as feature-graphic.png / the iOS promo screenshots.

Run from this directory: python3 make_tablet_screenshots.py
Raw source screenshots (real Chinese UI, captured on a Pixel-class
1080x1920 emulator) live in tablet-screenshots/raw_*.png.
"""
import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

TOP_COLOR = (40, 129, 90)
BOTTOM_COLOR = (12, 56, 35)
GOLD = (246, 212, 136)
WHITE = (255, 255, 255)

FONT_MED = "/System/Library/Fonts/STHeiti Medium.ttc"
FONT_LIGHT = "/System/Library/Fonts/STHeiti Light.ttc"

HERE = os.path.dirname(os.path.abspath(__file__))
RAW_DIR = os.path.join(HERE, "tablet-screenshots")

# (folder name, canvas size) — both 0.625 aspect (within Play's 9:16..16:9 range)
SIZES = [
    ("7-inch", (1200, 1920)),
    ("10-inch", (1600, 2560)),
]

SCREENS = [
    dict(raw="raw_1_scoring.png", out="1_scoring.png",
         caption="四川麻将 · 听牌计算器",
         headline="算番算钱，一步到位"),
    dict(raw="raw_2_settings.png", out="2_settings.png",
         caption="四川麻将 · 听牌计算器",
         headline="规则跟着牌桌走"),
]


def make_gradient(w, h):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for y in range(h):
        t = y / (h - 1)
        r = round(TOP_COLOR[0] + (BOTTOM_COLOR[0] - TOP_COLOR[0]) * t)
        g = round(TOP_COLOR[1] + (BOTTOM_COLOR[1] - TOP_COLOR[1]) * t)
        b = round(TOP_COLOR[2] + (BOTTOM_COLOR[2] - TOP_COLOR[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return im


def draw_tracked_text(draw, y, text, font, fill, tracking, center_x):
    widths = []
    for ch in text:
        bbox = font.getbbox(ch)
        widths.append(bbox[2] - bbox[0] if ch != " " else font.getbbox("一")[2] * 0.4)
    total = sum(widths) + tracking * (len(text) - 1)
    x = center_x - total / 2
    for ch, w in zip(text, widths):
        draw.text((x, y), ch, font=font, fill=fill)
        x += w + tracking


def fit_font(text, max_width, path, start, min_size=28):
    size = start
    while size > min_size:
        f = ImageFont.truetype(path, size)
        bbox = f.getbbox(text)
        if bbox[2] - bbox[0] <= max_width:
            return f
        size -= 2
    return ImageFont.truetype(path, min_size)


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def compose(canvas_w, canvas_h, raw_path, out_path, caption, headline):
    scale = canvas_w / 1200
    bg = make_gradient(canvas_w, canvas_h)
    draw = ImageDraw.Draw(bg)
    cx = canvas_w // 2

    cap_y = round(70 * scale)
    cap_font = ImageFont.truetype(FONT_MED, round(30 * scale))
    draw_tracked_text(draw, cap_y, caption, cap_font, GOLD, tracking=round(3 * scale), center_x=cx)

    headline_font = fit_font(headline, canvas_w - round(80 * scale), FONT_MED, start=round(56 * scale))
    hbbox = headline_font.getbbox(headline)
    head_y = round(130 * scale)
    draw.text((cx - (hbbox[2] - hbbox[0]) / 2 - hbbox[0], head_y), headline, font=headline_font, fill=WHITE)

    phone_top = round(230 * scale)
    margin_bottom = round(40 * scale)
    avail_h = canvas_h - phone_top - margin_bottom

    shot = Image.open(raw_path).convert("RGB")
    w, h = shot.size
    # fit by height (source is already 9:16-ish, taller than our remaining vertical budget)
    new_h = avail_h
    new_w = round(w * (new_h / h))
    max_w = canvas_w - round(80 * scale)
    if new_w > max_w:
        new_w = max_w
        new_h = round(h * (new_w / w))
    shot = shot.resize((new_w, new_h), Image.LANCZOS)

    phone_left = (canvas_w - new_w) // 2
    radius = round(40 * scale)

    shadow = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [phone_left - 4, phone_top + 8, phone_left + new_w + 4, phone_top + new_h + 14],
        radius=radius + 4, fill=(0, 0, 0, 90),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(round(14 * scale)))
    bg = Image.alpha_composite(bg.convert("RGBA"), shadow).convert("RGB")

    bg.paste(shot, (phone_left, phone_top), rounded_mask((new_w, new_h), radius))
    bg.save(out_path)
    print("wrote", out_path, bg.size)


if __name__ == "__main__":
    for folder, (w, h) in SIZES:
        out_dir = os.path.join(HERE, folder)
        os.makedirs(out_dir, exist_ok=True)
        for s in SCREENS:
            compose(
                w, h,
                os.path.join(RAW_DIR, s["raw"]),
                os.path.join(out_dir, s["out"]),
                s["caption"], s["headline"],
            )
