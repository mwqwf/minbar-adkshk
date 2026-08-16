"""قصّ الهوامش البيضاء من صور المصحف + تعديل الإحداثيات (mushaf_<riwaya>.jz)."""
import os, sys, gzip, json, threading, queue
from PIL import Image, ImageChops

SRC   = r"C:\Users\slxc\AppData\Local\Temp\mushaf-out"
DST   = r"C:\Users\slxc\AppData\Local\Temp\mushaf-cropped"
ASSETS= r"C:\Users\slxc\Documents\GitHub\MinbarAdkshk\minbar-adkshk\app\src\main\assets\quran"
PAD   = 6
RIW   = sys.argv[1:] or ["hafs", "warsh", "qalun"]

def bbox_of(path):
    im = Image.open(path).convert("RGB")
    bg = Image.new("RGB", im.size, (255, 255, 255))
    return ImageChops.difference(im, bg).getbbox(), im.size

def scan(riwaya):
    d = os.path.join(SRC, riwaya)
    files = sorted(f for f in os.listdir(d) if f.endswith(".webp"))
    q = queue.Queue(); [q.put(f) for f in files]
    res = {"box": None, "size": None}; lock = threading.Lock()
    def work():
        while True:
            try: f = q.get_nowait()
            except queue.Empty: return
            b, sz = bbox_of(os.path.join(d, f))
            with lock:
                res["size"] = sz
                if b:
                    res["box"] = b if res["box"] is None else (
                        min(res["box"][0], b[0]), min(res["box"][1], b[1]),
                        max(res["box"][2], b[2]), max(res["box"][3], b[3]))
    ts = [threading.Thread(target=work) for _ in range(12)]
    [t.start() for t in ts]; [t.join() for t in ts]
    W, H = res["size"]; x0, y0, x1, y1 = res["box"]
    box = (max(0, x0 - PAD), max(0, y0 - PAD), min(W, x1 + PAD), min(H, y1 + PAD))
    return box, (W, H), files

def crop_all(riwaya, box, files):
    d = os.path.join(SRC, riwaya); o = os.path.join(DST, riwaya)
    os.makedirs(o, exist_ok=True)
    q = queue.Queue(); [q.put(f) for f in files]
    def work():
        while True:
            try: f = q.get_nowait()
            except queue.Empty: return
            Image.open(os.path.join(d, f)).crop(box).save(
                os.path.join(o, f), "WEBP", quality=80, method=6)
    ts = [threading.Thread(target=work) for _ in range(12)]
    [t.start() for t in ts]; [t.join() for t in ts]

def remap(riwaya, box, size):
    W, H = size; x0, y0, x1, y1 = box; cw, ch = x1 - x0, y1 - y0
    p = os.path.join(ASSETS, "mushaf_%s.jz" % riwaya)
    j = json.loads(gzip.open(p, "rb").read())
    clamp = lambda v: 0 if v < 0 else (10000 if v > 10000 else v)
    for page in j["pages"]:
        for r in page:
            r[1] = clamp(int(round((r[1] / 10000 * W - x0) / cw * 10000)))
            r[2] = clamp(int(round((r[2] / 10000 * H - y0) / ch * 10000)))
            r[3] = clamp(int(round((r[3] / 10000 * W - x0) / cw * 10000)))
            r[4] = clamp(int(round((r[4] / 10000 * H - y0) / ch * 10000)))
    out = {"v": j["v"], "w": j["w"], "h": j["h"], "pw": cw, "ph": ch,
           "numbering": j["numbering"], "pages": j["pages"]}
    with gzip.open(p, "wb", compresslevel=9) as f:
        f.write(json.dumps(out, separators=(",", ":"), ensure_ascii=False).encode())
    return len(j["pages"])

if __name__ == "__main__":
    for r in RIW:
        box, size, files = scan(r)
        before = sum(os.path.getsize(os.path.join(SRC, r, f)) for f in files)
        crop_all(r, box, files)
        after = sum(os.path.getsize(os.path.join(DST, r, f)) for f in files)
        n = remap(r, box, size)
        print(f"{r}: box={box} crop={box[2]-box[0]}x{box[3]-box[1]} pages={n} "
              f"before={before/1048576:.1f}MB after={after/1048576:.1f}MB", flush=True)
