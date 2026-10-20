"""Serves a folder of cat animations (one HTML file per clip) to tools/render.html and saves the frames it posts.

    python tools/frame_server.py --src art/soot --out tools/frames
    then open http://127.0.0.1:8765/ in Chrome/Edge and wait for "DONE".

Listens on 127.0.0.1 only. Each clip ends up as <out>/<clip>/000.png, 001.png, ... plus meta.json
({"frames": n, "loop": bool, "duration": s}). Then run tools/pack_frames.py.
"""
import argparse
import http.server
import json
import pathlib
import re

HERE = pathlib.Path(__file__).resolve().parent
SAVE_PNG = re.compile(r"^/save/([a-z0-9_]+)/(\d{1,4})\.png$")
SAVE_META = re.compile(r"^/save/([a-z0-9_]+)/meta\.json$")


def make_handler(src: pathlib.Path, out: pathlib.Path):
    class Handler(http.server.SimpleHTTPRequestHandler):
        def translate_path(self, path):
            path = path.split("?", 1)[0]
            if path in ("/", "/render.html"):
                return str(HERE / "render.html")
            name = path.lstrip("/")
            if re.fullmatch(r"[a-z0-9_]+\.html", name):
                return str(src / name)
            return str(HERE / "__nothing__")

        def do_GET(self):
            if self.path.split("?", 1)[0] == "/clips.json":
                clips = sorted(p.stem for p in src.glob("*.html") if re.fullmatch(r"[a-z0-9_]+", p.stem))
                body = json.dumps(clips).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            super().do_GET()

        def do_POST(self):
            data = self.rfile.read(int(self.headers.get("Content-Length", 0)))
            m = SAVE_PNG.match(self.path)
            if m and data.startswith(b"\x89PNG"):
                d = out / m.group(1)
                d.mkdir(parents=True, exist_ok=True)
                (d / f"{int(m.group(2)):03d}.png").write_bytes(data)
            elif SAVE_META.match(self.path):
                d = out / SAVE_META.match(self.path).group(1)
                d.mkdir(parents=True, exist_ok=True)
                json.loads(data)  # must be valid JSON
                (d / "meta.json").write_bytes(data)
            else:
                self.send_error(400)
                return
            self.send_response(204)
            self.end_headers()

        def log_message(self, *a):
            pass

    return Handler


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", default=str(HERE.parent / "art" / "soot"), help="folder of <clip>.html files")
    ap.add_argument("--out", default=str(HERE / "frames"), help="where rendered PNG frames go")
    ap.add_argument("--port", type=int, default=8765)
    a = ap.parse_args()
    src, out = pathlib.Path(a.src).resolve(), pathlib.Path(a.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    print(f"serving {src} on http://127.0.0.1:{a.port}/  ->  frames in {out}", flush=True)
    http.server.ThreadingHTTPServer(("127.0.0.1", a.port), make_handler(src, out)).serve_forever()


if __name__ == "__main__":
    main()
