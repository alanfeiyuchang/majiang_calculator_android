#!/usr/bin/env python3
"""
Regenerate the Google Play feature graphic (1024x500) from the shared
app icon. Same color scheme as the iOS repo's compose_promo.py
(app store/screenshots/compose_promo.py) so the two stores' art stays
consistent. Run from this directory:

    python3 make_feature_graphic.py
"""
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1024, 500
TOP_COLOR = (40, 129, 90)
BOTTOM_COLOR = (12, 56, 35)
GOLD = (246, 212, 136)
WHITE = (255, 255, 255)

FONT_MED = "/System/Library/Fonts/STHeiti Medium.ttc"
FONT_LIGHT = "/System/Library/Fonts/STHeiti Light.ttc"

TITLE = "听牌计算器"
TAGLINE = "拍照识别 · 算番算钱 · 四川麻将"


def make_gradient(w, h, top, bottom):
    im = Image.new("RGB", (w, h))
    px = im.load()
    for y in range(h):
        t = y / (h - 1)
        r = round(top[0] + (bottom[0] - top[0]) * t)
        g = round(top[1] + (bottom[1] - top[1]) * t)
        b = round(top[2] + (bottom[2] - top[2]) * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    return im


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def fit_font(text, max_width, path, start=92, min_size=36):
    size = start
    while size > min_size:
        f = ImageFont.truetype(path, size)
        bbox = f.getbbox(text)
        if bbox[2] - bbox[0] <= max_width:
            return f
        size -= 2
    return ImageFont.truetype(path, min_size)


def main():
    bg = make_gradient(W, H, TOP_COLOR, BOTTOM_COLOR)

    icon = Image.open("icon-512.png").convert("RGBA")
    icon_size = 300
    icon = icon.resize((icon_size, icon_size), Image.LANCZOS)
    icon_x, icon_y = 70, (H - icon_size) // 2

    shadow = Image.new("RGBA", bg.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle(
        [icon_x - 6, icon_y + 10, icon_x + icon_size + 6, icon_y + icon_size + 16],
        radius=68, fill=(0, 0, 0, 120),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    bg = Image.alpha_composite(bg.convert("RGBA"), shadow).convert("RGB")
    bg.paste(icon, (icon_x, icon_y), rounded_mask((icon_size, icon_size), 64))

    draw = ImageDraw.Draw(bg)
    text_x = icon_x + icon_size + 60

    title_font = fit_font(TITLE, W - text_x - 40, FONT_MED, start=92)
    tbbox = title_font.getbbox(TITLE)
    title_y = 150
    draw.text((text_x, title_y), TITLE, font=title_font, fill=WHITE)

    tag_font = fit_font(TAGLINE, W - text_x - 40, FONT_LIGHT, start=42)
    draw.text((text_x, title_y + (tbbox[3] - tbbox[1]) + 30), TAGLINE, font=tag_font, fill=GOLD)

    bg.save("feature-graphic.png")
    print("wrote feature-graphic.png", bg.size)


if __name__ == "__main__":
    main()
