"""Packs rendered frames into TellyCat's assets: lossless WebP per frame plus clips.txt (name frames loop).

    python tools/pack_frames.py --frames tools/frames --assets tellycat/src/main/assets/cat

The destination folder is replaced. Loop flags come from each clip's meta.json (written by render.html).
Needs Pillow (pip install pillow).
"""
import argparse
import json
import pathlib
import shutil

from PIL import Image

HERE = pathlib.Path(__file__).resolve().parent


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--frames", default=str(HERE / "frames"))
    ap.add_argument("--assets", default=str(HERE.parent / "tellycat" / "src" / "main" / "assets" / "cat"))
    a = ap.parse_args()
    src, dst = pathlib.Path(a.frames), pathlib.Path(a.assets)
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    lines, total, frames = [], 0, 0
    for clip in sorted(p for p in src.iterdir() if p.is_dir()):
        pngs = sorted(clip.glob("*.png"))
        if not pngs:
            continue
        meta = clip / "meta.json"
        loop = json.loads(meta.read_text())["loop"] if meta.exists() else False
        (dst / clip.name).mkdir()
        for i, f in enumerate(pngs):
            out = dst / clip.name / f"{i:03d}.webp"
            Image.open(f).save(out, "WEBP", lossless=True, quality=100, method=4)
            total += out.stat().st_size
        frames += len(pngs)
        lines.append(f"{clip.name} {len(pngs)} {1 if loop else 0}")
    (dst / "clips.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
    print(f"{len(lines)} clips, {frames} frames, {total / 1e6:.2f} MB -> {dst}")


if __name__ == "__main__":
    main()
