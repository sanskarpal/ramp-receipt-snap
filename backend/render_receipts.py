"""Renders the synthetic sample receipts in samples.json to PNGs bundled with
the app (app/src/main/assets/sample_receipts/), dated relative to today so the
parser's 90-day window accepts them. Rerun before a demo to refresh dates.

These are clean synthetic images: good for a demo, not an accuracy benchmark.
"""
from __future__ import annotations

import json
import random
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = Path(__file__).parent
OUT = HERE.parent / "app/src/main/assets/sample_receipts"
FONT = "/System/Library/Fonts/Menlo.ttc"
W = 620


def money(cents: int) -> str:
    return f"${cents / 100:,.2f}"


def render(s: dict) -> Image.Image:
    body = ImageFont.truetype(FONT, 24)
    big = ImageFont.truetype(FONT, 38)
    day = datetime.now(ZoneInfo("America/Los_Angeles")).date() - timedelta(days=s["day_offset"])
    sub = sum(c for _, c in s["items"])
    total = sub + s["tax"]

    rows: list[tuple[str, str, ImageFont.FreeTypeFont, str]] = [(s["merchant"], "", big, "center")]
    rows += [(a, "", body, "center") for a in s["address"]]
    rows += [("", "", body, "left"), (f"{day:%m/%d/%Y}  {s['time']}", "", body, "left"), ("-" * 34, "", body, "left")]
    rows += [(name, money(c), body, "split") for name, c in s["items"]]
    rows += [("-" * 34, "", body, "left"), ("SUBTOTAL", money(sub), body, "split"), ("TAX", money(s["tax"]), body, "split")]
    rows += [("TOTAL", money(total), big if s.get("split_total") else body, "split")]
    if s.get("tip_line"):
        rows += [("", "", body, "left"), ("TIP", "__________", body, "split"), ("SIGNATURE", "__________", body, "split")]
    rows += [("", "", body, "left"), ("VISA ****4242", "", body, "left"), ("THANK YOU!", "", body, "center")]

    line_h = 40
    img = Image.new("L", (W, 80 + line_h * len(rows)), 250)
    d = ImageDraw.Draw(img)
    y = 40
    for left, right, font, mode in rows:
        if mode == "center":
            d.text((W / 2, y), left, font=font, fill=20, anchor="ma")
        elif mode == "split":
            d.text((40, y), left, font=font, fill=20)
            d.text((W - 40, y), right, font=font, fill=20, anchor="ra")
        else:
            d.text((40, y), left, font=font, fill=20)
        y += line_h + (14 if font is big else 0)
    # a little paper texture and blur so it isn't a perfect vector render
    rnd = random.Random(s["file"])
    for _ in range(2500):
        img.putpixel((rnd.randrange(W), rnd.randrange(img.height)), rnd.randint(200, 245))
    return img.filter(ImageFilter.GaussianBlur(0.6)).rotate(rnd.uniform(-1.2, 1.2), fillcolor=235, expand=True)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for s in json.loads((HERE / "samples.json").read_text())["receipts"]:
        render(s).convert("RGB").save(OUT / s["file"])
        print("wrote", OUT / s["file"])


if __name__ == "__main__":
    main()
