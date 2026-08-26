# Managed ArgoCD Platform — Cinematic Presentation

## Overview

A 15-scene HTML5 canvas presentation for the **Scalable Managed ArgoCD Platform**.
Each scene is a standalone `scene{N}.html` file hosted inside a full-viewport `<iframe>` by `index.html`.

```
presentation/
├── index.html                  ← Host shell: iframe, scene nav dots, transitions
├── argocd-icon.svg             ← Shared ArgoCD icon used in all scenes
├── scene1.html  – scene15.html ← Individual scenes
└── PRESENTATION_README.md      ← This file
```

---

## Navigation

| Key | Action |
|---|---|
| `Space` / `→` (ArrowRight) | Next **step** within current scene |
| `←` (ArrowLeft) | Previous step; if at step 0 → previous **scene** |
| `↑` (ArrowUp) | Previous **scene** (jump, skips remaining steps) |
| `↓` (ArrowDown) | Next **scene** (jump, skips remaining steps) |
| Number keys `1`–`9` | Jump directly to that scene (when nav dots are focused) |
| Nav dots (bottom-right) | Click to jump to any scene |

Scenes communicate with the parent via `postMessage`:
- `{ type: 'scene-complete' }` → parent advances to next scene
- `{ type: 'scene-back' }` → parent goes to previous scene

---

## Shared Code Patterns

Every scene uses the same structural patterns. When modifying or creating scenes, follow these exactly.

### 1. Canvas Setup (DPR-aware)
```javascript
const canvas = document.getElementById('c');
const ctx    = canvas.getContext('2d');
let W = 0, H = 0;
function resize() {
  const dpr = window.devicePixelRatio || 1;
  W = window.innerWidth; H = window.innerHeight;
  canvas.width  = W * dpr; canvas.height = H * dpr;
  canvas.style.width = W + 'px'; canvas.style.height = H + 'px';
  ctx.scale(dpr, dpr);
}
resize(); window.addEventListener('resize', resize);
```
Always use `W` and `H` for layout calculations, not raw canvas dimensions.

### 2. ArgoCD Icon
```javascript
const _argoImg = new Image(); let _argoOC = null;
_argoImg.onload = () => {
  const oc = document.createElement('canvas'); oc.width=64; oc.height=64;
  oc.getContext('2d').drawImage(_argoImg,0,0,64,64); _argoOC=oc;
};
_argoImg.src = 'argocd-icon.svg';

// Draw icon + label side by side, centered at (cx, cy)
function drawArgoLabel(cx, cy, text, font, color, opacity, iconSz) {
  ctx.save(); ctx.font = font;
  const tw = ctx.measureText(text).width, gap = 5;
  const totalW = iconSz + gap + tw, ix = cx - totalW/2;
  ctx.globalAlpha = opacity; ctx.shadowBlur = 0;  // ← MUST reset shadowBlur inside
  if (_argoOC) ctx.drawImage(_argoOC, ix, cy-iconSz/2, iconSz, iconSz);
  ctx.fillStyle = color; ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
  ctx.fillText(text, ix+iconSz+gap, cy);
  ctx.restore();
}
```
**Important:** Always set `ctx.shadowBlur = 0` before drawing the SVG icon or glow bleeds into the icon image.

### 3. Rounded Rectangle Helper
```javascript
function rr(x, y, w, h, r) {
  ctx.beginPath();
  ctx.moveTo(x+r,y); ctx.lineTo(x+w-r,y); ctx.quadraticCurveTo(x+w,y,x+w,y+r);
  ctx.lineTo(x+w,y+h-r); ctx.quadraticCurveTo(x+w,y+h,x+w-r,y+h);
  ctx.lineTo(x+r,y+h); ctx.quadraticCurveTo(x,y+h,x,y+h-r);
  ctx.lineTo(x,y+r); ctx.quadraticCurveTo(x,y,x+r,y); ctx.closePath();
}
```

### 4. Step-based Lerp Animation
```javascript
const A = { field1: 0, field2: 0 };  // all scalars 0→1

function tickAnim() {
  const sp = 0.035;  // speed: 0.02 (slow) to 0.06 (fast)
  A.field1 += ((step >= 1 ? 1 : 0) - A.field1) * sp;
  A.field2 += ((step >= 2 ? 1 : 0) - A.field2) * sp;
}
```
- Lerp target is `1` when condition is met, `0` otherwise
- Back-navigation is free — values re-lerp to 0 automatically
- Multiply `sp` by 1.2–1.8 for faster individual elements

### 5. Bezier Particle System
```javascript
class Particle {
  constructor(c) {
    this.col = c.col; this.p = 0; this.alive = true;
    this.spd = 0.006 + Math.random() * 0.005;  // speed (0.0018 = very slow)
    this.r   = 1.6 + Math.random() * 0.9;      // radius
    // bxSign: -1 = left-lean arc (upward/request), +1 = right-lean (downward/response)
  }
  // Positions via quadratic bezier: u*u*start + 2*u*p*ctrl + p*p*end
}
```
**Connection definition:**
```javascript
const CONNS = [
  { from:'nodeA', to:'nodeB', minS:0, maxS:3, col:'#60A5FA', rate:0.014, bxSign:-1 },
  // minS/maxS: step range when particles spawn
  // rate: probability per frame (0.01 = sparse, 0.03 = dense)
  // speed: optional custom speed override (omit for default)
];
```

### 6. Advance / Navigation Pattern
```javascript
let step = 0, MAX_STEPS = 7;  // steps are 0 to MAX_STEPS-1

function advance() {
  if (step >= MAX_STEPS - 1) {
    window.parent.postMessage({ type: 'scene-complete' }, '*');
    return;
  }
  step++;
  updateUI();
}

document.addEventListener('keydown', e => {
  if (e.code==='Space'||e.code==='ArrowRight') { e.preventDefault(); advance(); return; }
  if (e.code==='ArrowLeft' && step===0) { window.parent.postMessage({type:'scene-back'},'*'); return; }
  if (e.code==='ArrowLeft' && step>0)   { step--; particles=[]; updateUI(); }
  // ArrowUp/Down injected globally — do not add manually
});
```

### 7. Responsive Font Sizes
```javascript
// Pattern: Math.min(max_px, Math.max(min_px, W * scale_factor))
const titleFs = Math.min(52, Math.max(28, W * 0.034));
const bodyFs  = Math.min(14, Math.max(10, W * 0.010));
const descFs  = Math.min(11, Math.max(9,  W * 0.008));
```

---

## Scene Reference

### Scene 1 — Intro (`scene1.html`)
**Type:** Cinematic reveal · **Steps:** 3 (0–2) · **MAX_STEPS:** 3

| Step | Content |
|---|---|
| 0 | ArgoCD icon fades in at center with 3 expanding ring pulses and radial glow halo |
| 1 | "Scalable Managed ArgoCD" title fades in below icon |
| 2 | "GitOps built for scale" tagline fades in (muted blue, 58% opacity) |

**Layout:** Icon at `H*0.40`, title at `H*0.57`, tagline at `H*0.64`, all centered.  
**Background:** 120 ambient drifting star particles (blue + purple, very low opacity).  
**Key elements:** `RING_PHASES = [0, 0.33, 0.67]` — three staggered ring pulses.  
**No scene-label** (it's a title card).

---

### Scene 2 — The Opportunity (`scene2.html`)
**Type:** Data reveal · **Steps:** 6 (0–5) · **MAX_STEPS:** 6

| Step | Content |
|---|---|
| 0 | Hero stat: "1000s" (large glowing text) + two subtitle lines |
| 1 | **Individual Cluster Owners** card appears (blue `#60A5FA`) |
| 2 | **Managed Service Platform Teams** card appears (amber `#F59E0B`) |
| 3 | **Service Tenants** card appears (coral `#F87171`) |
| 4 | Coverage bars fill simultaneously: P1=82%, P2=46%, P3=13% |
| 5 | Bottom statement: *"One platform. No team needs to build or maintain their own deployment tooling."* |

**Layout:** Hero at `H*0.15`, three cards at `H*0.560`, bottom statement at `H*0.875`.  
**Card columns:** rx = 0.20, 0.50, 0.80. Width = `Math.min(370, W*0.265)`.  
**Coverage bars:** Controlled by `A.bars` scalar; bar fill = `p.fill * A.bars`.  
**Persona descriptions:**
- P1: "Own and manage their clusters / Deploy workloads directly / Single- or multi-region"
- P2: "Operate clusters to provision / services for other teams / Manage controllers & lifecycle"
- P3: "Deploy into provisioned compute / Own a namespace, not the cluster / Bounded to assigned partition"

---

### Scene 3 — The Scaling Problem (`scene3.html`)
**Original name:** "When ArgoCD Becomes the Bottleneck" · **Steps:** 7 · **MAX_STEPS:** 7

Shows the exponential growth problem of a single ArgoCD instance — memory pressure, slow reconciliation, and eventual failure as application count grows.

**Narrative steps (from LABELS):** Single ArgoCD under load → teams onboarding → memory climbing → OOM events.  
**Narrative cards (step 5):** *"One ArgoCD. Everything depends on it."*  
**Narrative card (step 6):** *"What happens when everything keeps growing?"*

---

### Scene 4 — Inside ArgoCD (`scene4.html`)
**Original name:** "Inside ArgoCD" · **Steps:** 7 · **MAX_STEPS:** 7

Visualises the internal components of a single ArgoCD instance.

**Node positions (rx, ry):**
- Git Repos: rx=0.22/0.50/0.78, ry=0.11 (step 0); rx=0.08/0.92, ry=0.14 (step 2)
- API Server: rx=0.50, ry=0.31 — *"Request entry point"*
- Redis: rx=0.50, ry=0.46 — *"Shared cache & state"*
- App Controller: rx=0.50, ry=0.61 — *"Reconciliation Engine"* (fades at step 5)
- Repo Server: rx=0.27, ry=0.68 — *"Git · Manifests"*
- etcd: rx=0.73, ry=0.68 — *"Shared store"*
- Shards (step 5): Shard 1 at rx=0.35, Shard 2 at rx=0.65 (both ry=0.61)
- K8s Clusters: rx=0.22/0.50/0.78, ry=0.88–0.90

---

### Scene 5 — Multiple Control Planes (`scene5.html`)
**Original name:** "Multiple Control Planes" · **Steps:** 6 · **MAX_STEPS:** 6

Transitions from single ArgoCD to the multi-CP model with Platform Service routing.

**Key nodes (rx, ry):**
- ArgoCD (hot, fades): rx=0.50, ry=0.47
- CP-1/CP-2/CP-3: rx=0.22/0.50/0.78, ry=0.55 (step 2)
- User: rx=0.50, ry=0.09 (step 3)
- Platform Service: rx=0.50, ry=0.28 — *"Smart routing · Unified API"* (step 4)

**LABELS:** 'One ArgoCD — managing everything, under pressure' → 'What if we had multiple ArgoCD instances instead?' → 'Three control planes — each owns a bounded slice' → 'Which control plane does the user connect to?' → 'Managed ArgoCD Platform — one entry point, smart routing'  
**Narrative:** *"One entry point. Smart routing. Each control plane sees only its slice."*

---

### Scene 6 — Plugin Generator (`scene6.html`)
**Original name:** "Plugin Generator" · **Steps:** 7 · **MAX_STEPS:** 7

Explains the ApplicationSet Plugin Generator mechanism — how the platform API drives application generation.

**Key nodes:**
- API Server: rx=0.50, ry=0.09 — *"Returns parameter sets"*
- ApplicationSet (Plugin Generator): rx=0.50, ry=0.41
- Applications (step 3): rx=0.28/0.50/0.72, ry=0.60
- Additional apps (step 5): rx=0.14/0.86, ry=0.58

**LABELS:** 'ApplicationSet Plugin Generator — how it works' → 'ArgoCD polls your API server periodically' → 'Your API returns a list of parameter sets' → 'One Application is created per parameter set' → 'Each Application reconciles to its destination cluster' → 'API data changes → ArgoCD adapts on the next poll'  
**Narrative:** *"Your API is the source of truth."*  
**API call pill:** `POST /api/v1/getparams.execute`

---

### Scene 7 — Platform Architecture (`scene7.html`)
**Original name:** "Platform Architecture" · **Steps:** 7 · **MAX_STEPS:** 7

Full platform architecture — PostgreSQL as source of truth, Platform Service as plugin generator, ApplicationSets, and Control Planes executing to clusters.

**Key nodes (rx, ry):**
- PostgreSQL: rx=0.50, ry=0.09 — *"Source of truth"*
- Platform Service: rx=0.50, ry=0.235 — *"Spring Boot — plugin generator"*
- ApplicationSets: rx=0.50, ry=0.475 — *"app · cluster · project partitions"*
- CP-1/CP-2/CP-3: rx=0.22/0.50/0.78, ry=0.79

**LABELS:** 'How the Platform Works — one entry point for everything' → 'User-owned state lives in PostgreSQL: clusters, projects, applications' → 'Managed ArgoCD has ApplicationSets — each polls the Platform Service' → 'Platform Service responds with parameter sets — one app per entry' → 'Based on the response, Managed ArgoCD creates Applications on Control Planes' → 'Control Planes reconcile user applications to their destination clusters'  
**Narrative:** *"Platform Service is the source of truth. Managed ArgoCD orchestrates. Control Planes execute."*

---

### Scene 8 — Git-Driven Infrastructure (`scene8.html`)
**Type:** Git push animation · **Steps:** 7 · **MAX_STEPS:** 7

Shows how adding a new control plane to the pool requires only a Git push — no manual provisioning.

**Layout:** Left panel = GitHub file tree. Right panel = Managed ArgoCD box with 3 ApplicationSets.

**Git panel (`GIT_BOX`):** `x1=0.04, y1=0.10, x2=0.37, y2=0.82`  
**Argo box (`ARGO_BOX`):** `x1=0.41, y1=0.10, x2=0.97, y2=0.66`

**File tree entries:**
- `managed/applicationsets/` (directory)
- `├── control-planes.yaml` (blue `#60A5FA`)
- `├── cluster-partitions.yaml` (purple `#A78BFA`)
- `└── application-partitions.yaml` (teal `#34D399`)
- `control-planes/values/` (directory)
- `├── default.yaml` (grey)
- `└── cp-3/default.yaml` (amber `#FBBF24`, NEW badge, appears step 1)

**ApplicationSet nodes (rx, ry):**
- control-planes AppSet: rx=0.620, ry=0.250
- cluster-partitions AppSet: rx=0.620, ry=0.390
- application-partitions AppSet: rx=0.620, ry=0.530

**CP nodes:** CP-1 at rx=0.490, CP-2 at rx=0.660, CP-3 at rx=0.830 (all ry=0.840)

**LABELS:** 'ApplicationSet definitions stored in Git — the single source of truth' → 'Admin adds cp-3/default.yaml to Git and pushes — no manual cluster steps' → 'control-planes ApplicationSet detects the new cluster via label selector' → 'Application "control-plane-cp-3" is created by the ApplicationSet' → 'Helm chart installs ArgoCD on CP-3 and registers it to the pool' → 'Three control planes managed from Git — ready to accept clusters'  
**Narrative:** *"One Git push. A new control plane joins the pool."*

**Animated scalars:** `A.gitFiles, A.cp3File, A.gitPush, A.detected, A.appCreated, A.helmDeploy, A.cp3Online`

---

### Scene 9 — Cluster & App Lifecycle (`scene9.html`)
**Type:** API flow animation · **Steps:** 8 · **MAX_STEPS:** 8

Shows the end-to-end lifecycle: cluster onboarding via POST /clusters (with intelligent CP assignment), then application creation that follows the cluster.

**Key nodes (rx, ry):**
- Platform Service: rx=0.18, ry=0.22 (amber `#FBBF24`)
- CP-1: rx=0.38, ry=0.50 · CP-2: rx=0.60, ry=0.50 · CP-3: rx=0.82, ry=0.50
- Cluster CL-1: rx=0.18, ry=0.52 (amber → teal on assignment)
- Assignment box: x=0.04*W to 0.32*W, y=0.62H to 0.86H
- APP-1 (under CP-1): rx=0.38, ry=0.78
- APP-2 (under CP-2): rx=0.60, ry=0.78

**LABELS:**
0. 'Platform Service manages the CP pool — clusters and applications register through it'
1. 'POST /clusters — onboard CL-1 with parameters: region, load, availability…'
2. 'Assignment algorithm evaluates the CP pool using the provided parameters'
3. 'CL-1 is assigned to CP-2 — the best match based on the parameters'
4. 'POST /applications — create an application bound to CL-1'
5. 'App derives CP-2 via CL-1 → CP-2 relationship; generation bumped → AppSet reconciles'
6. 'APP-2 deployed to CP-2 — application follows its cluster'

**Narrative:** *"Cluster assignment is intelligent. Applications follow their cluster. Region, load, capacity — any parameter can drive placement."*  
**Assignment params shown:** region, load, capacity, availability

**Animated scalars:** `A.plat, A.cpPool, A.reqCluster, A.clIncoming, A.clAssigned, A.assignLine, A.reqApp, A.appDeploy, A.genBump`

---

### Scene 10 — The Growing Pain (`scene10.html`)
**Original name:** "The Growing Pain" · **Steps:** 8 · **MAX_STEPS:** 8

Demonstrates the single-ApplicationSet scaling problem — as teams onboard, poll response grows until OOMKilled.

**Counter display (bottom-right):** Shows app count and response size per step:
- Step progression leads to: 25 apps → `OUT OF MEMORY` / `💥`

**LABELS:**
0. 'One ApplicationSet — all apps, all clusters, no limits'
1. 'Managed ArgoCD polls Platform Service for the full application list'
2. (team growth)
3. 'Adoption grows — more teams onboard, more apps registered'
4. 'Response payload hits 98 KB and climbing — every poll cycle'

**Narrative:** *"One ApplicationSet. Every app. Every cluster. As the platform grows — the poll fails."*

---

### Scene 11 — Partition the Load (`scene11.html`)
**Original name:** "Partition the Load" · **Steps:** 8 · **MAX_STEPS:** 8

Recovery from OOM: splitting into multiple ApplicationSets, each polling for its own partition.

**LABELS:**
0. 'The ApplicationSet controller is down — OOMKilled'
1. 'Platform Service restarts — the system recovers'
2. 'Solution: split into multiple ApplicationSets'
3. 'Apps redistribute — each AppSet owns its partition'
4. 'Each partition polls independently — smaller requests'
5. 'Response per partition: ~170 KB  (was ~512 KB total)'
6. 'All partitions running — system is healthy and scalable'

**Narrative:** *"Partition the load. Each ApplicationSet polls for less. The platform scales."*  
**Before/After counter:** "Before (1 AppSet): ~512 KB" vs per-partition size

---

### Scene 12 — The Self-Assembling Hierarchy (`scene12.html`)
**Original name:** "The Self-Assembling Hierarchy" · **Steps:** 10 · **MAX_STEPS:** 10

Shows the 4-level hierarchical ApplicationSet structure: L1 root → L2 partition apps → L3 partition AppSets → L4 actual applications.

**Node levels:**
- L1: Root ApplicationSet (top center, step 0)
- L2: App-P1 (Partition-1), App-P2 (Partition-2) — step 3
- L3: AppSet-P1 (polls partition), AppSet-P2 (polls partition) — step 4
- L4: 4 actual application nodes — step 6
- CPs: CP-1 at rx=0.28, CP-2 at rx=0.72 (ry=0.905, step 0)

---

### Scene 13 — Event-Driven Regeneration (`scene13.html`)
**Type:** Event flow animation · **Steps:** 7 · **MAX_STEPS:** 7

Shows how Redis cache + generation bumps ensure only the affected partition's ApplicationSet reconciles when data changes.

**Node positions (rx, ry):**
- PostgreSQL: rx=0.50, ry=0.085
- Redis: rx=0.50, ry=0.280
- Platform Service: rx=0.50, ry=0.462
- L1 AppSet: rx=0.50, ry=0.625 (inside Argo box)
- part001: rx=0.22, ry=0.840 · part002: rx=0.50, ry=0.840 · part003: rx=0.78, ry=0.840

**ArgoCD box bounds:** `x1=0.07, y1=0.555, x2=0.93, y2=0.955`

**Redis cache entry states:**
- partition-001: always `HIT` (green)
- partition-002: `HIT` → `EVICTED` (step 2) → `REPOPULATED` (step 3+)
- partition-003: always `HIT` (green)

**Platform Service status label:**
- Step 0: "Cache HIT — serving cached response" (green)
- Step 1: "Writing to PostgreSQL — generation bumped" (amber)
- Step 2: "Evicting partition-002 cache key" (red)
- Step 3: "Cache MISS for partition-002 — querying PostgreSQL" (teal)
- Step 4+: "Cache HIT — serving cached response" (green)

**Particle connections:**
- part001/003 → Platform (slow, `speed:0.0018`, `rate:0.004`) — 5-min polling
- L1 AppSet → Platform (faster, `rate:0.018`) — 10-sec polling
- "polls 10 s" badge on L1 AppSet → Platform line
- "polls 5 min" badge on partition AppSet → Platform lines

**LABELS:**
0. 'Steady state — AppSets poll Platform Service — Platform Service hits Redis — PostgreSQL untouched'
1. 'New application registered — Platform Service writes to PostgreSQL — partition-002 generation bumped 41 → 42'
2. 'PartitionChangedEvent fires — Platform Service evicts partition-002 cache key — partition-001 and 003 untouched'
3. 'L1 AppSet polls — Platform Service checks Redis — partition-002 MISS — queries PostgreSQL — cache repopulated'
4. 'Only partition-002 AppSet reconciles — generates new applications on the control plane'
5. 'Partition-001 and Partition-003 — zero DB queries — zero reconciliations — cache entries remain valid'

---

### Scene 14 — Failover (`scene14.html`)
**Type:** CP failover animation · **Steps:** 7 · **MAX_STEPS:** 7

Demonstrates control plane failover — CP-1 goes offline, clusters and applications automatically migrate to CP-2 via a single database update.

**CP containers:** CP-1 and CP-2 each contain 2 rows × 2 app boxes (APP-1 through APP-8).  
**Cluster nodes:** UCL-A, UCL-B (under CP-1) and UCL-C, UCL-D (under CP-2) — connected by dashed management lines.  
**App labels:** `APP-${cpIndex*2 + slotIndex + 1}` (APP-1 through APP-8)

**Cluster colors:** UCL-A `#60A5FA` · UCL-B `#A78BFA` · UCL-C `#34D399` · UCL-D `#C084FC`

**LABELS:**
0. 'CP-1 and CP-2 each manage two clusters — applications in sync'
1. 'CP-1 is degrading — applications showing sync errors'
2. 'CP-1 is offline — applications enter Unknown state'
3. 'Failover initiated — cluster.control_plane_id updated in the database'
4. 'ApplicationSets reconcile — applications migrate, clusters reassigned to CP-2'
5. 'CP-2 now manages all four clusters — zero manual intervention'

**Narrative:** *"One database update. Full failover. No ArgoCD YAML. No manual sync. Just data."*

**Bezier arc migration:** Apps fly from CP-1 container to CP-2 via `appPos(cpIdx, slotIdx)` function.  
**No recovery step** — scene ends at failover completion (by design).

---

### Scene 15 — Summary (`scene15.html`)
**Type:** Static — no animation, no steps

Single draw call on load (`document.fonts.ready`) and on resize. No `requestAnimationFrame` loop.

**Layout (top to bottom):**
| Element | Y position | Details |
|---|---|---|
| ArgoCD icon + "What We Built" | `H*0.105` | Icon 32px, title font ~36px |
| Tagline | `H*0.105 + titleFs*0.72` | "A scalable, Git-driven GitOps platform..." |
| Pillar row 1 (3 cards) | `H*0.225` | Git-Driven · Partitioned & Scalable · Event-Driven |
| Pillar row 2 (2 cards) | `H*0.225 + cardH + 12` | Intelligent Placement · Fault-Tolerant |
| Separator line | `row2Y + cardH + H*0.052` | 80% width, 16% opacity |
| Persona row | `sepY + H*0.050` | Three personas with `·` separators |
| Closing line 1 | `personaY + H*0.072` | "One GitOps platform. 1000s of clusters." |
| Closing line 2 | `line1Y + H*0.046` | "No team needs to build or maintain..." |

**Five pillars:**
| Title | Description | Color |
|---|---|---|
| Git-Driven | AppSets and cluster config managed from Git | `#F87171` |
| Partitioned & Scalable | Workloads distributed across a CP pool | `#60A5FA` |
| Event-Driven | Efficient reconciliation via Redis cache | `#A78BFA` |
| Intelligent Placement | CP assigned by region, load, and capacity | `#34D399` |
| Fault-Tolerant | Automatic failover and cluster re-association | `#F59E0B` |

**Navigation on this scene:** ArrowLeft → `scene-back`. No forward navigation (last scene).  
**Hint:** "← ArrowLeft to go back" (bottom center, always visible).

---

## How to Add a New Scene

1. **Create** `sceneN.html` following the shared patterns above.
2. **Add scene label** in top-left: `<div id="scene-label">Scene N &nbsp;/&nbsp; Title</div>`
3. **Add to index.html** — extend `SCENE_TITLES` array with `'Scene N / Title'`.
4. **ArrowUp/Down** navigation is auto-injected via the global listener added to every scene — do **not** add it manually; it will be duplicated.

## How to Insert a Scene Mid-Sequence

1. Use bash `cp` to shift affected files: `cp sceneN.html scene(N+1).html` (work in reverse order).
2. Use `sed -i '' 's/Scene N /Scene N+1 /g' scene(N+1).html` for each shifted file.
3. Create the new `sceneN.html`.
4. Update `SCENE_TITLES` in `index.html`.

## Color Palette

| Use | Color |
|---|---|
| Background | `#080B12` |
| Blue (CP, AppSets, P1) | `#60A5FA` |
| Purple (partition AppSets) | `#A78BFA` |
| Teal (success, teal AppSets) | `#34D399` / `#2DD4BF` |
| Amber (warning, Platform Service) | `#F59E0B` / `#FBBF24` |
| Red (error, Git) | `#F87171` / `#EF4444` |
| Muted text | `#94A3B8` / `#64748B` |
| Card background | `rgba(4,10,24,0.97)` |
| Border muted | `rgba(148,163,184,0.55)` |
