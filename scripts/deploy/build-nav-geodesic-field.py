#!/usr/bin/env python3
"""Bouw een SCHOON geodesic-veld uit de UT99 nav-graaf (T3D PathNodes etc.) i.p.v. buggy bot-play.
Output = zelfde formaat als de play-trace geodesic.json (Java GeodesicField loader)."""
import re, json, math, sys, collections, heapq

T3D = sys.argv[1] if len(sys.argv) > 1 else "/tmp/facenav/MyLevel.t3d"
OUT = sys.argv[2] if len(sys.argv) > 2 else "/tmp/facenav/CTF-Face.navgeodesic.json"
VOX = 96.0
MAX_REACH = 700.0     # max 3D-afstand om twee nav-nodes te verbinden (mapper-spacing)
KNN = 6               # elk node verbindt met max K dichtstbijzijnde binnen MAX_REACH

NAVCLASSES = {'PathNode','InventorySpot','PlayerStart','JumpSpot','JumpDest',
              'Teleporter','LiftExit','LiftCenter','FlagBase','DefensePoint'}

t = open(T3D).read()
blocks = re.split(r'(?=Begin Actor Class=)', t)
navpts = []          # (x,y,z, cls)
flagpts = []
for b in blocks:
    m = re.match(r'Begin Actor Class=(\w+)', b)
    if not m or m.group(1) not in NAVCLASSES:
        continue
    loc = re.search(r'\bLocation=\(([^)]*)\)', b)
    if not loc:
        continue
    d = {}
    for kv in loc.group(1).split(','):
        if '=' in kv:
            k, v = kv.split('='); d[k.strip()] = float(v)
    p = (d.get('X', 0.0), d.get('Y', 0.0), d.get('Z', 0.0))
    navpts.append((p, m.group(1)))
    if m.group(1) == 'FlagBase':
        flagpts.append(p)

print(f"nav-nodes geparsed: {len(navpts)}  flagbases: {len(flagpts)}")

# proximity-edges (3D, bidirectioneel) tussen nav-nodes: KNN binnen MAX_REACH
def dist(a, b): return math.dist(a, b)
raw_edges = []
for i, (pi, _) in enumerate(navpts):
    cand = []
    for j, (pj, _) in enumerate(navpts):
        if i == j: continue
        dd = dist(pi, pj)
        if dd <= MAX_REACH:
            cand.append((dd, j))
    cand.sort()
    for dd, j in cand[:KNN]:
        raw_edges.append((i, j, dd))
print(f"proximity raw-edges (gericht): {len(raw_edges)}")

# densify elke edge @VOX → tussenpunten op de veilige route
points = []   # alle wereldpunten die voxels worden
seg_chains = []  # lijst van (lijst-van-puntindices) per edge, voor edge-opbouw
def add_pt(p):
    points.append(p); return len(points) - 1
for (i, j, dd) in raw_edges:
    a = navpts[i][0]; b = navpts[j][0]
    n = max(1, int(dd // VOX))
    chain = []
    for s in range(n + 1):
        f = s / n
        chain.append(add_pt((a[0]+(b[0]-a[0])*f, a[1]+(b[1]-a[1])*f, a[2]+(b[2]-a[2])*f)))
    seg_chains.append(chain)
# ook de kale nav-nodes zelf (voor het geval geisoleerd)
navpt_idx = [add_pt(p) for (p, _) in navpts]

# voxelize: voxel-key -> node-id, centroid = mean van punten in voxel
def vkey(p): return (round(p[0]/VOX), round(p[1]/VOX), round(p[2]/VOX))
vox_pts = collections.defaultdict(list)
for idx, p in enumerate(points):
    vox_pts[vkey(p)].append(p)
voxlist = list(vox_pts.keys())
vox_id = {k: n for n, k in enumerate(voxlist)}
centroids = []
for k in voxlist:
    ps = vox_pts[k]
    centroids.append((sum(q[0] for q in ps)/len(ps), sum(q[1] for q in ps)/len(ps), sum(q[2] for q in ps)/len(ps)))

# edges: langs elke densified chain → opeenvolgende voxel-paren (bidirectioneel)
edge_set = {}
def pt_vox(pi): return vox_id[vkey(points[pi])]
for chain in seg_chains:
    for s in range(len(chain)-1):
        u = pt_vox(chain[s]); v = pt_vox(chain[s+1])
        if u == v: continue
        w = dist(centroids[u], centroids[v])
        for (a, b) in ((u, v), (v, u)):
            if edge_set.get((a, b), 1e9) > w:
                edge_set[(a, b)] = w

nodes = [[k[0], k[1], k[2], round(centroids[i][0],1), round(centroids[i][1],1), round(centroids[i][2],1)]
         for i, k in enumerate(voxlist)]
edges = [[a, b, round(w,1)] for (a, b), w in edge_set.items()]

# --- VALIDATIE ---
zmin = min(n[5] for n in nodes); zmax = max(n[5] for n in nodes)
voidn = sum(1 for n in nodes if n[5] < -2250)
# flag↔flag bereikbaarheid (ongericht BFS over edges)
adj = collections.defaultdict(list)
for a, b, w in edges: adj[a].append(b)
def nearest_node(p):
    return min(range(len(nodes)), key=lambda i: (nodes[i][3]-p[0])**2+(nodes[i][4]-p[1])**2+(nodes[i][5]-p[2])**2)
fconn = "n.v.t."
if len(flagpts) == 2:
    f0 = nearest_node(flagpts[0]); f1 = nearest_node(flagpts[1])
    seen = {f0}; dq = collections.deque([f0])
    while dq:
        x = dq.popleft()
        for y in adj[x]:
            if y not in seen: seen.add(y); dq.append(y)
    fconn = f"flag0->flag1 verbonden: {f1 in seen} (nodes bereikbaar vanaf flag0: {len(seen)}/{len(nodes)})"

out = {"version": 1, "map": "CTF-Face", "voxel_size_uu": VOX,
       "built_from": {"nav_graph_t3d": True, "nav_nodes": len(navpts), "source": "ucc T3D PathNode-graaf"},
       "nodes": nodes, "edges": edges}
json.dump(out, open(OUT, "w"))
print(f"VELD: {len(nodes)} nodes, {len(edges)} edges (gericht)")
print(f"Z-bereik centroids: {zmin:.0f}..{zmax:.0f} (speelvloer -2200..1250); void-nodes(Z<-2250): {voidn}")
print(fconn)
print(f"geschreven: {OUT} ({__import__('os').path.getsize(OUT)} bytes)")
