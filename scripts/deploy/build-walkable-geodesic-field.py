#!/usr/bin/env python3
"""Bouw een SCHOON, DICHT geodesic-veld over de beloopbare ruimte van één map.

Motivatie (2026-06-14): de twee bestaande velden zijn elk half:
  * nav-graph (T3D PathNodes): schoon + verbonden, maar DUN (centerline) → off-route
    valt buiten de ±192 UU snap → euclidische fallback → laterale pull de void in.
  * play-trace (buggy bot-play): DICHT maar 81% void-debris; bovendien gebouwd uit bots
    die JUIST vóór de enemy-flag bleven vallen → mist dekking op het falende segment.

Dit script bouwt het veld primair uit een MENSELIJKE/schone demo-opname (json-recording-
session, losse JSON-frames met GameState.Players[]): een mens (+ meelopende bots) die de
volledige flag-run loopt — heen NAAR de enemy-flag én terug om te capturen — dekt de hele
route inclusief de approach die de bot zelf nog niet haalt. We unioneren met het schone
nav-graph-skelet (connectiviteitsgarantie) en CLEANEN:
  * drop voxels onder de walkable-floor (deep void-debris),
  * drop fall-edges (steile neerwaartse dz = ongecontroleerde val),
  * symmetriseer near-flat edges (lopen kan beide kanten op),
  * houd alleen de grootste samenhangende component (mid-air val-debris valt weg).

Output = exact het formaat van GeodesicField.load (Java): nodes=[ix,iy,iz,cx,cy,cz],
edges=[from,to,wUu>0]. Default schrijft naar <out>.new + valideert; --write overschrijft
het live veld (met .bak van het huidige).

Usage:
  python3 scripts/deploy/build-walkable-geodesic-field.py CTF-Face \
      --demo-dir <json-recording-session-dir> [--nav-field <f>] [--play-field <f>] \
      [--floor-z -2100] [--fall-drop 120] [--write]
"""
import argparse, glob, json, math, os, sys, heapq
from collections import defaultdict, deque

VOX = 96.0
BIAS = 1 << 20
MAX_STEP_UU = 250.0          # Java BuildGeodesicFieldMain.MAX_STEP_UU (filter respawns/teleports)
MAX_GAP_MS = 600.0           # Java MAX_FRAME_GAP_MS


def vkey(x, y, z):
    ix, iy, iz = math.floor(x / VOX), math.floor(y / VOX), math.floor(z / VOX)
    return ((ix + BIAS) << 42) | ((iy + BIAS) << 21) | (iz + BIAS), ix, iy, iz


def unpack(k):
    ix = (k >> 42) - BIAS
    iy = ((k >> 21) & ((1 << 21) - 1)) - BIAS
    iz = (k & ((1 << 21) - 1)) - BIAS
    return ix, iy, iz


class Acc:
    """Voxel-centroids + gerichte transitie-edges, bron-agnostisch."""
    def __init__(self):
        self.vox = defaultdict(lambda: [0.0, 0.0, 0.0, 0])   # key -> [sx,sy,sz,count]
        self.edges = defaultdict(int)                         # (kfrom,kto) -> count

    def add_point(self, x, y, z):
        k, *_ = vkey(x, y, z)
        v = self.vox[k]; v[0] += x; v[1] += y; v[2] += z; v[3] += 1
        return k

    def add_edge(self, kfrom, kto):
        if kfrom != kto:
            self.edges[(kfrom, kto)] += 1


def scan_demo(acc, demo_dir, stats):
    files = sorted(glob.glob(os.path.join(demo_dir, "*.json")))
    print(f"[demo] {len(files)} frames in {demo_dir}")
    tracks = {}   # name -> (tMillis, x, y, z, key)
    for n, fp in enumerate(files):
        if n % 5000 == 0 and n:
            print(f"[demo]   {n}/{len(files)} ...")
        try:
            d = json.load(open(fp))
        except Exception:
            continue
        t = float(os.path.splitext(os.path.basename(fp))[0])   # filename = tMillis
        for p in d.get("Players", []):
            try:
                if str(p.get("bIsSpectator")) == "True":
                    continue
                nm = p.get("Name")
                loc = p.get("Location")
                if not nm or not loc:
                    continue
                alive = float(p.get("Health", 0)) > 0
            except (TypeError, ValueError):
                continue
            if not alive:
                tracks.pop(nm, None)            # dood → track breken (respawn = nieuwe)
                continue
            try:
                x, y, z = (float(v) for v in loc.split(","))
            except ValueError:
                continue
            k = acc.add_point(x, y, z)
            stats["pts"] += 1
            prev = tracks.get(nm)
            if prev:
                dt = t - prev[0]
                dx, dy, dz = x - prev[1], y - prev[2], z - prev[3]
                dist = math.sqrt(dx * dx + dy * dy + dz * dz)
                if 0 < dt <= MAX_GAP_MS and dist <= MAX_STEP_UU and prev[4] != k:
                    acc.add_edge(prev[4], k)
                    stats["trans"] += 1
            tracks[nm] = (t, x, y, z, k)


def scan_field(acc, path, stats, tag):
    """Voeg een bestaand geodesic.json (nodes+edges) toe als bron."""
    f = json.load(open(path))
    nodes = f["nodes"]
    keys = []
    for nd in nodes:
        ix, iy, iz, cx, cy, cz = nd
        k = ((int(ix) + BIAS) << 42) | ((int(iy) + BIAS) << 21) | (int(iz) + BIAS)
        v = acc.vox[k]; v[0] += cx; v[1] += cy; v[2] += cz; v[3] += 1
        keys.append(k)
        stats["pts"] += 1
    for e in f["edges"]:
        a, b = int(e[0]), int(e[1])
        acc.add_edge(keys[a], keys[b])
        stats["trans"] += 1
    print(f"[{tag}] {len(nodes)} nodes, {len(f['edges'])} edges toegevoegd uit {os.path.basename(path)}")


def build(acc, floor_z, fall_drop, flat, min_trans):
    """Cleane node/edge-lijsten + de grootste samenhangende component."""
    # 1. centroids per voxel, drop deep void (onder de walkable-floor)
    centroid = {}
    for k, v in acc.vox.items():
        cz = v[2] / v[3]
        if cz < floor_z:
            continue
        centroid[k] = (v[0] / v[3], v[1] / v[3], cz)

    # 2. edges: drop fall-edges (steile neerwaartse dz), endpoints moeten walkable zijn
    kept = {}   # (kfrom,kto) -> weight
    dropped_fall = dropped_floor = 0
    for (kf, kt), cnt in acc.edges.items():
        if cnt < min_trans:
            continue
        if kf not in centroid or kt not in centroid:
            dropped_floor += 1
            continue
        cf, ct = centroid[kf], centroid[kt]
        dz = ct[2] - cf[2]
        if dz < -fall_drop:           # ongecontroleerde val → weg
            dropped_fall += 1
            continue
        w = max(1.0, math.dist(cf, ct))
        if kept.get((kf, kt), 1e18) > w:
            kept[(kf, kt)] = w
        if abs(dz) <= flat:           # near-flat → lopen kan beide kanten op
            if kept.get((kt, kf), 1e18) > w:
                kept[(kt, kf)] = w

    # 3. grootste zwak-samenhangende component (undirected reachability)
    adj = defaultdict(list)
    for (kf, kt) in kept:
        adj[kf].append(kt)
        adj[kt].append(kf)
    seen, best = set(), set()
    for start in centroid:
        if start in seen:
            continue
        comp, dq = set(), deque([start])
        seen.add(start)
        while dq:
            x = dq.popleft(); comp.add(x)
            for y in adj[x]:
                if y not in seen:
                    seen.add(y); dq.append(y)
        if len(comp) > len(best):
            best = comp

    # 4. re-index + materialiseer
    keys = [k for k in centroid if k in best]
    idx = {k: i for i, k in enumerate(keys)}
    nodes = []
    for k in keys:
        ix, iy, iz = unpack(k)
        cx, cy, cz = centroid[k]
        nodes.append([ix, iy, iz, round(cx, 1), round(cy, 1), round(cz, 1)])
    edges = []
    for (kf, kt), w in kept.items():
        if kf in idx and kt in idx:
            edges.append([idx[kf], idx[kt], round(w, 1)])
    print(f"[clean] floor-drop edges={dropped_floor} fall-drop edges={dropped_fall} "
          f"| grootste component {len(best)}/{len(centroid)} nodes")
    return nodes, edges


# ----------------------------- validatie (Java-parity query) -----------------------------
class Field:
    def __init__(self, nodes, edges):
        self.cx = [n[3] for n in nodes]
        self.cy = [n[4] for n in nodes]
        self.cz = [n[5] for n in nodes]
        self.byvox = {((int(n[0]) + BIAS) << 42) | ((int(n[1]) + BIAS) << 21) | (int(n[2]) + BIAS): i
                      for i, n in enumerate(nodes)}
        self.rev = defaultdict(list)         # reverse-graaf: to -> [(from,w)]
        for a, b, w in edges:
            self.rev[b].append((a, w))
        self.n = len(nodes)
        self._cache = {}

    def _nearest(self, x, y, z):
        ix, iy, iz = math.floor(x / VOX), math.floor(y / VOX), math.floor(z / VOX)
        best, bd = -1, 1e18
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                for dz in range(-2, 3):
                    k = ((ix + dx + BIAS) << 42) | ((iy + dy + BIAS) << 21) | (iz + dz + BIAS)
                    nidx = self.byvox.get(k)
                    if nidx is None:
                        continue
                    d = math.dist((x, y, z), (self.cx[nidx], self.cy[nidx], self.cz[nidx]))
                    if d < bd:
                        bd, best = d, nidx
        return best

    def _dijkstra(self, target):
        if target in self._cache:
            return self._cache[target]
        dist = [math.inf] * self.n
        dist[target] = 0.0
        pq = [(0.0, target)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist[u]:
                continue
            for v, w in self.rev[u]:
                nd = d + w
                if nd < dist[v]:
                    dist[v] = nd
                    heapq.heappush(pq, (nd, v))
        self._cache[target] = dist
        return dist

    def distance(self, frm, to):
        t = self._nearest(*to)
        if t < 0:
            return math.nan
        dist = self._dijkstra(t)
        ix, iy, iz = math.floor(frm[0] / VOX), math.floor(frm[1] / VOX), math.floor(frm[2] / VOX)
        best = math.inf
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                for dz in range(-2, 3):
                    k = ((ix + dx + BIAS) << 42) | ((iy + dy + BIAS) << 21) | (iz + dz + BIAS)
                    nidx = self.byvox.get(k)
                    if nidx is None or not math.isfinite(dist[nidx]):
                        continue
                    tot = math.dist(frm, (self.cx[nidx], self.cy[nidx], self.cz[nidx])) + dist[nidx]
                    if tot < best:
                        best = tot
        if not math.isfinite(best):
            return math.nan
        return best + math.dist(to, (self.cx[t], self.cy[t], self.cz[t]))


def validate(nodes, edges, map_json, flags):
    zs = [n[5] for n in nodes]
    xs = [n[3] for n in nodes]
    void = sum(1 for z in zs if z < -2250)
    print(f"\n=== VALIDATIE ===")
    print(f"nodes={len(nodes)} edges={len(edges)}")
    print(f"Z-bereik {min(zs):.0f}..{max(zs):.0f}  X-bereik {min(xs):.0f}..{max(xs):.0f}  void(Z<-2250)={void}")
    fld = Field(nodes, edges)
    # flag↔flag beide richtingen
    if flags:
        (f0, f1) = flags
        eucl = math.dist(f0, f1)
        d01 = fld.distance(f0, f1)
        d10 = fld.distance(f1, f0)
        print(f"flag0->flag1 geo={d01:.0f} (eucl {eucl:.0f}, ratio {d01/eucl:.2f})  "
              f"flag1->flag0 geo={d10:.0f}")
    # spawn cross-team connectiviteit (zoals Java sanity)
    mj = json.load(open(map_json))
    sp = mj.get("spawn_points", [])
    t0 = [s["location"] for s in sp if s.get("team") == 0]
    t1 = [s["location"] for s in sp if s.get("team") == 1]
    pairs = conn = 0
    ratios = []
    for s0 in t0:
        for s1 in t1:
            pairs += 1
            g = fld.distance(s0, s1)
            if math.isfinite(g):
                conn += 1
                e = math.dist(s0, s1)
                if e > 1e-9:
                    ratios.append(g / e)
    ratios.sort()
    med = ratios[len(ratios) // 2] if ratios else float("nan")
    print(f"spawn cross-team: {conn}/{pairs} verbonden ({100*conn/pairs:.0f}%), mediane geo/eucl-ratio {med:.2f}")
    return void == 0 and conn / max(1, pairs) >= 0.9


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("map")
    ap.add_argument("--demo-dir", action="append", default=[])
    ap.add_argument("--nav-field", default=None)
    ap.add_argument("--play-field", default=None)
    ap.add_argument("--floor-z", type=float, default=-2100.0)
    ap.add_argument("--fall-drop", type=float, default=120.0)
    ap.add_argument("--flat", type=float, default=40.0)
    ap.add_argument("--min-trans", type=int, default=1)
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    geo_dir = os.path.join(root, "resources/config/geodesic")
    map_json = os.path.join(root, "resources/config/maps", args.map + ".json")
    live = os.path.join(geo_dir, args.map + ".geodesic.json")
    nav_field = args.nav_field or live   # default: het huidige (schone nav-graph) veld als skelet

    acc = Acc()
    stats = {"pts": 0, "trans": 0}
    for dd in args.demo_dir:
        scan_demo(acc, dd, stats)
    if nav_field and os.path.exists(nav_field):
        scan_field(acc, nav_field, stats, "nav")
    if args.play_field and os.path.exists(args.play_field):
        scan_field(acc, args.play_field, stats, "play")
    print(f"[acc] punten={stats['pts']} transities={stats['trans']} ruwe-voxels={len(acc.vox)}")

    nodes, edges = build(acc, args.floor_z, args.fall_drop, args.flat, args.min_trans)

    # flag-posities uit CTF-Face hardcoded fallback? Nee: lees uit map_json indien aanwezig,
    # anders uit de aanroep. Hier: bekende CTF-Face flag-bases (uit Flags-veld van de demo).
    flags = None
    if args.map == "CTF-Face":
        flags = ((6488.1, 438.6, -1956.9), (-1013.4, -592.1, -1968.9))

    ok = validate(nodes, edges, map_json, flags)

    out = {"version": 1, "map": args.map, "voxel_size_uu": VOX,
           "built_from": {"walkable_demo": True, "demo_dirs": args.demo_dir,
                          "nav_field": bool(nav_field), "play_field": bool(args.play_field),
                          "points": stats["pts"], "transitions": stats["trans"],
                          "floor_z": args.floor_z, "fall_drop": args.fall_drop},
           "nodes": nodes, "edges": edges}
    target = live if args.write else live + ".new"
    if args.write and os.path.exists(live):
        bak = live + ".prewalkable.bak"
        if not os.path.exists(bak):
            os.rename(live, bak)
            print(f"[write] huidige veld → {os.path.basename(bak)}")
    json.dump(out, open(target, "w"))
    print(f"\n[{'WRITE' if args.write else 'dry'}] geschreven: {target} ({os.path.getsize(target)} bytes)")
    if not ok:
        print("[!] VALIDATIE FAALT (void!=0 of <90% spawn-connectiviteit) — niet activeren zonder fix")
        sys.exit(1)
    print("[ok] validatie groen")


if __name__ == "__main__":
    main()
