"""Render the approved icon paths to SVG and transparent PNGs."""
from pathlib import Path
import subprocess

OUT = Path(__file__).resolve().parent
PALETTES = {
    "light": ("#16242B", "#A6C1D6", "#C8DEEC", "#7CA4BD", "#EDF0F3"),
    "dark": ("#D5E5F0", "#638299", "#ABCADA", "#3D596F", "#2B3037"),
}
SIZES = (16, 20, 32, 512)


def run(args):
    subprocess.run(args, check=True)


def path(d, fill, stroke="none", width=5):
    return (
        f'<path d="{d}" fill="{fill}" stroke="{stroke}" '
        f'stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def rect(x, y, width, height, fill, stroke, radius=7):
    return (
        f'<rect x="{x}" y="{y}" width="{width}" height="{height}" '
        f'rx="{radius}" fill="{fill}" stroke="{stroke}" stroke-width="5"/>'
    )


def letter_b(x, y, width, height, fill):
    # The counters remain transparent vector cutouts at all raster sizes.
    return (
        f'<path transform="translate({x} {y}) scale({width / 24} {height / 36})" '
        f'fill="{fill}" fill-rule="evenodd" '
        'd="M0 0H12C26 0 27 15 17 17C29 20 26 36 12 36H0ZM7 6V14H12C19 14 19 6 12 6ZM7 21V30H13C21 30 21 21 13 21Z"/>'
    )


def bend_file(ink, metal, light, shade):
    badge = shade if ink == PALETTES["dark"][0] else light
    back = path("M17 30L79 17Q85 16 87 23L103 104L39 115Z", shade, ink)
    front = rect(29, 21, 72, 93, metal, ink)
    seams = path("M30 48Q54 53 77 50M30 88Q63 95 100 88", "none", ink, 3)
    tab = rect(79, 10, 39, 48, badge, ink, 5)
    b_mark = letter_b(87, 18, 25, 33, ink)
    limb_seam = path("M44 68H74", "none", ink, 5)
    return back + front + seams + tab + b_mark + limb_seam


def idea_catalog(ink, metal):
    book = path("M11 28Q39 18 64 32Q89 18 117 28V105Q89 96 64 109Q39 96 11 105Z", metal, ink)
    spine = path("M64 33V107", "none", ink)
    # B begins the first left-page entry, followed by a short continuation stroke.
    first_entry = letter_b(21, 35, 25, 35, ink) + path("M51 53L55 54", "none", ink, 4)
    page_lines = path(
        "M22 81Q38 77 53 84M76 51Q91 44 105 48M76 81Q91 74 105 78",
        "none",
        ink,
        4,
    )
    return book + spine + first_entry + page_lines


def write_art(name, theme, body, ink, background):
    dark_suffix = "_dark" if theme == "dark" else ""
    svg = OUT / f"{name}{dark_suffix}.svg"
    svg.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">{body}</svg>\n'
    )

    rendered = {}
    for size in SIZES:
        png = OUT / f"{name}-{theme}-{size}.png"
        run(["rsvg-convert", "-w", str(size), "-h", str(size), str(svg), "-o", str(png)])
        rendered[size] = png

    tile = OUT / f"{name}-{theme}-preview.png"
    args = [
        "magick", "-size", "440x360", f"canvas:{background}", "-font", "Helvetica",
        "-fill", ink, "-pointsize", "18", "-gravity", "NorthWest",
        "-annotate", "+18+15", f"{name} / {theme}",
        "(", str(rendered[512]), "-resize", "200x200", ")", "-geometry", "+120+55", "-composite",
    ]
    for x, size in ((70, 16), (205, 20), (340, 32)):
        args += [
            str(rendered[size]), "-geometry", f"+{x}+280", "-composite",
            "-pointsize", "14", "-annotate", f"+{x - 10}+332", f"{size} px",
        ]
    run(args + [str(tile)])
    return tile


def main():
    tiles = {"bend": {}, "idea-catalog": {}}
    for theme, (ink, metal, light, shade, background) in PALETTES.items():
        tiles["bend"][theme] = write_art(
            "bend", theme, bend_file(ink, metal, light, shade), ink, background
        )
        tiles["idea-catalog"][theme] = write_art(
            "idea-catalog", theme, idea_catalog(ink, metal), ink, background
        )

    for name, theme_tiles in tiles.items():
        run([
            "magick", str(theme_tiles["light"]), str(theme_tiles["dark"]), "+append",
            str(OUT / f"{name}-comparison.png"),
        ])
    run([
        "magick", str(OUT / "bend-comparison.png"), str(OUT / "idea-catalog-comparison.png"),
        "-append", str(OUT / "comparison.png"),
    ])


if __name__ == "__main__":
    main()
