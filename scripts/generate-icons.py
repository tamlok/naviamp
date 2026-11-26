#!/usr/bin/env python3
"""Regenerate Navo artwork from design/navo.png. Requires Pillow 12.3.0."""

import io
import struct
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps/android/src/main/res"
DESKTOP = ROOT / "platforms/desktop/src/desktopMain/resources/icons"
TVOS = ROOT / "apps/tvos/NaviampTV/Assets.xcassets/Naviamp Brand.brandassets"
LANCZOS = Image.Resampling.LANCZOS


def square(image, size):
    return image.resize((size, size), LANCZOS)


def save_png(image, path):
    image.save(path, format="PNG", optimize=True)


def centered(image, size, box, background=(0, 0, 0, 0)):
    canvas = Image.new("RGBA", size, background)
    fitted = ImageOps.contain(image, box, LANCZOS).convert("RGBA")
    canvas.alpha_composite(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return canvas


def save_icns(master, path):
    # Keep small native representations: Pillow's ICNS writer omits icp4/5/6.
    entries = (
        (b"icp4", 16), (b"icp5", 32), (b"icp6", 64),
        (b"ic07", 128), (b"ic08", 256), (b"ic09", 512), (b"ic10", 1024),
        (b"ic11", 32), (b"ic12", 64), (b"ic13", 256), (b"ic14", 512),
    )
    payloads = {}
    chunks = []
    for kind, size in entries:
        if size not in payloads:
            stream = io.BytesIO()
            save_png(square(master, size), stream)
            payloads[size] = stream.getvalue()
        payload = payloads[size]
        chunks.append(kind + struct.pack(">I", len(payload) + 8) + payload)
    body = b"".join(chunks)
    path.write_bytes(b"icns" + struct.pack(">I", len(body) + 8) + body)


def television_assets(master, mark):
    # Preserve the launcher banner's layout; use the bundled font on every OS.
    banner = Image.new("RGB", (1280, 720), "#101116")
    banner.paste(square(master, 336), (472, 72))
    font = ImageFont.truetype(
        str(ROOT / "core/ui/src/commonMain/composeResources/font/nunito_sans.ttf"), 136,
    )
    font.set_variation_by_axes([600, 100, 12, 500])
    name = (ROOT / "APPLICATION_NAME").read_text(encoding="utf-8").strip()
    ImageDraw.Draw(banner).text((640, 576), name, font=font, fill="white", anchor="ms")
    save_png(banner.resize((320, 180), LANCZOS), ANDROID / "drawable-xhdpi/navo_tv_banner.png")

    # Native tvOS parallax requires separate transparent mark and opaque layers.
    cropped_mark = mark.crop(mark.getchannel("A").getbbox())
    for stack, base, scales in (
        ("App Icon.imagestack", (400, 240), (1, 2)),
        ("App Store Icon.imagestack", (1280, 768), (1,)),
    ):
        for scale in scales:
            size = (base[0] * scale, base[1] * scale)
            suffix = "@2x" if scale == 2 else ""
            layer = centered(cropped_mark, size, (round(size[0] * 0.6), round(size[1] * 0.7)))
            save_png(layer, TVOS / stack / f"Mark.imagestacklayer/Content.imageset/navo{suffix}.png")
            save_png(
                Image.new("RGB", size, "black"),
                TVOS / stack / f"Background.imagestacklayer/Content.imageset/navo_background{suffix}.png",
            )
    for imageset, width in (("Top Shelf.imageset", 1920), ("Top Shelf Wide.imageset", 2320)):
        for scale in (1, 2):
            height = 720 * scale
            suffix = "@2x" if scale == 2 else ""
            canvas = centered(
                cropped_mark, (width * scale, height), (height, round(height * 0.68)),
                (16, 17, 22, 255),
            )
            save_png(canvas.convert("RGB"), TVOS / imageset / f"navo{suffix}.png")


def main():
    with Image.open(ROOT / "design/navo.png") as source:
        if source.width != source.height:
            raise ValueError("The master icon must be square")
        # Preserve the supplied black presentation, including behind alpha edges.
        master = Image.new("RGBA", source.size, "black")
        master.alpha_composite(source.convert("RGBA"))
        master = master.convert("RGB")

    for path in (
        ROOT / "core/ui/src/commonMain/composeResources/drawable/navo.png",
        ROOT / "readme-assets/navo.png",
        DESKTOP / "navo.png",
    ):
        save_png(square(master, 512), path)

    # Key only the native silhouettes, never the main color artwork. Threshold
    # near-black source noise before resampling to keep small masks clean.
    red, green, blue = master.split()
    coverage = ImageChops.lighter(ImageChops.lighter(red, green), blue).point(
        lambda value: 255 if value >= 32 else 0,
    )
    monochrome = Image.new("RGBA", master.size, "white")
    monochrome.putalpha(coverage)
    mark = master.convert("RGBA")
    mark.putalpha(coverage)

    for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
        save_png(square(master, size), ANDROID / f"mipmap-{density}/navo.png")
    # Android: 108dp canvas, centered 66dp safe square, at 4 pixels per dp.
    for name, image in (("foreground", master), ("monochrome", monochrome)):
        save_png(centered(image, (432, 432), (264, 264)), ANDROID / f"drawable-nodpi/navo_{name}.png")
    save_png(square(monochrome, 96), ANDROID / "drawable-nodpi/navo_notification.png")

    save_png(square(master, 1024), ROOT / "apps/ios/iosApp/Assets.xcassets/AppIcon.appiconset/navo.png")
    square(master, 256).save(
        DESKTOP / "navo.ico", format="ICO",
        sizes=[(size, size) for size in (16, 24, 32, 48, 64, 128, 256)],
    )
    save_icns(master, DESKTOP / "navo.icns")
    television_assets(master, mark)
    print("Generated Navo shared, Android, desktop, iOS, and tvOS icons from design/navo.png")


if __name__ == "__main__":
    main()
