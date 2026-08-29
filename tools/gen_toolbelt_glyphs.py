#!/usr/bin/env python3
"""Generate the four BlackBerry Classic / Q20 tool-belt key glyphs
(send / menu-logo / back / end) as standalone assets.

Outputs under art/toolbelt-glyphs/:
  svg/<name>-black.svg     svg/<name>-white.svg          (96 viewBox, 512 render)
  png/<name>-black.png     png/<name>-white.png          (512x512, transparent)
  vector-xml/ic_toolbelt_<name>_black.xml / _white.xml   (24dp VectorDrawable)

send / back / end are drawn to match the key etchings on the device.
'logo' embeds the BlackBerry mark from the user-supplied blackberry.svg,
scaled to the 96 grid.
"""
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "art" / "toolbelt-glyphs"

STROKE_W = 10

# Official BlackBerry mark (viewBox 0 0 32 32), from the supplied blackberry.svg.
BB_LOGO_D = (
    "M10.375 7.306c0 1.463-1.025 3.069-4.531 3.069h-4.381l1.313-5.55h4.238"
    "c2.631 0 3.363 1.456 3.363 2.481zM18.262 4.825h-4.238l-1.169 5.55h4.381"
    "c3.363 0 4.381-1.606 4.381-3.069 0.006-1.025-0.725-2.481-3.356-2.481z"
    "M5.55 13.006h-4.238l-1.313 5.55h4.381c3.506 0 4.531-1.462 4.531-3.069"
    "0-1.019-0.731-2.481-3.363-2.481zM16.806 13.006h-4.237l-1.169 5.55h4.381"
    "c3.363 0 4.381-1.462 4.381-3.069 0-1.019-0.731-2.481-3.356-2.481z"
    "M28.637 9.644h-4.238l-1.169 5.55h4.381c3.363 0 4.381-1.463 4.381-3.069"
    "0.006-1.019-0.725-2.481-3.356-2.481zM26.887 18.262h-4.238l-1.169 5.55h4.381"
    "c3.506 0 4.381-1.462 4.381-3.069 0-1.019-0.725-2.481-3.356-2.481z"
    "M15.050 21.625h-4.238l-1.169 5.55h4.381c3.506 0 4.381-1.606 4.381-3.069"
    "0.006-1.019-0.725-2.481-3.356-2.481z"
)

GLYPHS = {
    # Send / answer: shallow rounded "cup" bracket, opening up
    "answer": [
        ("stroke", "M23,39 v7 a9,9 0 0 0 9,9 h32 a9,9 0 0 0 9,-9 v-7"),
    ],
    # End / hang-up: the same cup flipped over, plus the bar across the opening
    "hangup": [
        ("stroke", "M23,39 v7 a9,9 0 0 0 9,9 h32 a9,9 0 0 0 9,-9 v-7"),
        ("stroke", "M37,39 H59"),
    ],
    # Back: same arrow as the earlier pass, rotated 90 so the head points left
    "back": [
        ("stroke", "M64,74 V38 A17,17 0 0 0 30,38 V55"),
        ("stroke", "M19,45 L30,58 L41,45"),
    ],
    # BlackBerry mark: the 7 tiles from the supplied blackberry.svg, drawn as
    # separate fills (a single combined path cancels the overlapping tiles).
    "logo": [("fill", "M" + s) for s in BB_LOGO_D.split("M") if s],
}

# Group transform applied around pivot (48,48) unless noted.
GROUP_TF = {
    "hangup": "rotate(180 48 48)",
    "back": "rotate(90 48 48)",
    "logo": "translate(4.6 4.7) scale(2.6)",   # fit the full mark on the 96 grid
}
GROUP_TF_XML = {
    "hangup": [("rotation", 180.0), ("pivotX", 48.0), ("pivotY", 48.0)],
    "back": [("rotation", 90.0), ("pivotX", 48.0), ("pivotY", 48.0)],
    "logo": [("scaleX", 2.6), ("scaleY", 2.6), ("translateX", 4.6),
             ("translateY", 4.7)],
}


def frags_svg(frags, color):
    out = []
    for kind, d in ((f[0], f[1]) for f in frags):
        if kind == "stroke":
            out.append(f'<path d="{d}" fill="none" stroke="{color}" '
                       f'stroke-width="{STROKE_W}" stroke-linecap="round" '
                       f'stroke-linejoin="round"/>')
        else:  # fill / raw
            out.append(f'<path d="{d}" fill="{color}"/>')
    return "\n    ".join(out)


def write_svg(name, frags, color, tag):
    body = frags_svg(frags, color)
    tf = GROUP_TF.get(name)
    inner = f'<g transform="{tf}">\n    {body}\n  </g>' if tf else body
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 96 96" '
           f'width="512" height="512">\n  {inner}\n</svg>\n')
    p = OUT / "svg" / f"{name}-{tag}.svg"
    p.write_text(svg)
    return p


def write_png(svg_path, name, tag):
    p = OUT / "png" / f"{name}-{tag}.png"
    subprocess.run(["google-chrome", "--headless", "--disable-gpu", "--hide-scrollbars",
                    f"--screenshot={p}", "--window-size=512,512",
                    "--default-background-color=00000000",
                    svg_path.resolve().as_uri()],
                   check=True, stderr=subprocess.DEVNULL)
    return p


def write_xml(name, frags, hexcolor, tag):
    lines = ['<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="24dp" android:height="24dp"',
             '    android:viewportWidth="96" android:viewportHeight="96">']

    def emit(indent):
        for kind, d in ((f[0], f[1]) for f in frags):
            if kind == "stroke":
                lines.append(f'{indent}<path android:pathData="{d}"')
                lines.append(f'{indent}    android:strokeColor="{hexcolor}" '
                             f'android:strokeWidth="{STROKE_W}"')
                lines.append(f'{indent}    android:strokeLineCap="round" '
                             f'android:strokeLineJoin="round"/>')
            else:
                lines.append(f'{indent}<path android:pathData="{d}"')
                lines.append(f'{indent}    android:fillColor="{hexcolor}"/>')

    if name in GROUP_TF_XML:
        attrs = " ".join(f'android:{k}="{v}"' for k, v in GROUP_TF_XML[name])
        lines.append(f'    <group {attrs}>')
        emit("        ")
        lines.append('    </group>')
    else:
        emit("    ")
    lines.append('</vector>\n')
    p = OUT / "vector-xml" / f"ic_toolbelt_{name}_{tag}.xml"
    p.write_text("\n".join(lines))
    return p


def main():
    for sub in ("svg", "png", "vector-xml"):
        (OUT / sub).mkdir(parents=True, exist_ok=True)
    for tag, css, axml in [("black", "#000000", "#FF000000"),
                           ("white", "#FFFFFF", "#FFFFFFFF")]:
        for name, frags in GLYPHS.items():
            s = write_svg(name, frags, css, tag)
            write_png(s, name, tag)
            write_xml(name, frags, axml, tag)
            print(f"{name:8} {tag}")


if __name__ == "__main__":
    main()
