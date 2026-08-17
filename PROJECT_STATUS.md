# EdgeCloudSim — Project Status & Extension Guide

> Living reference document. Keep this up to date whenever a class is added, moved,
> or a new extension point is wired in. Written for both the maintainer and any AI
> assistant working on this repo — when in doubt, grep for the class names below
> rather than trusting line numbers, since the file evolves.

## 1. What this is

This is a fork of [EdgeCloudSim](https://github.com/CagataySonmez/EdgeCloudSim) (built on
CloudSim) extended with:
- **UAV / mobile edge servers**: edge hosts that physically move (`mobility/uav`,
  `edge_server/uav`, `edge_orchestrator/uav`, `network/uav`).
- **Task partitioning**: a task can be split into N sub-tasks ("children") offloaded
  together, with results re-aggregated (`edge_client/DefaultMobileDeviceManager`,
  `edge_client/Task`, `utils/TaskProperty`, `core/SimSettings`).
- **tutorial8's SAR scenario**: a second, independent population of mobile devices
  (Search & Rescue team members) sharing the world with normal users.
- A progression of tutorials (`tutorial1`..`tutorial8`) and older `sample_app1`..`sample_app5`
  that each layer on one more feature on top of vanilla EdgeCloudSim.

The codebase follows EdgeCloudSim's original **abstract-class-plus-factory** design:
`ScenarioFactory` is the single seam that plugs a scenario's concrete implementations
(mobility, network, orchestrator, servers, load generator) into the generic simulation
engine (`SimManager`). Every "add a new X" task below is really "write a new subclass
and return it from a `ScenarioFactory`".

## 2. High-level architecture

```mermaid
flowchart TB
    MainApp -->|constructs| SF[ScenarioFactory impl<br/>e.g. SampleScenarioFactory]
    MainApp -->|constructs| SimManager
    SimManager -->|asks factory for each component| SF
    SF --> MobilityModel
    SF --> LoadGeneratorModel
    SF --> NetworkModel
    SF --> EdgeOrchestrator
    SF --> EdgeServerManager
    SF --> CloudServerManager
    SF --> MobileServerManager
    SF --> MobileDeviceManager
    SF -.optional.-> UAVMobilityModel

    SimManager -->|schedules CREATE_TASK per device| LoadGeneratorModel
    LoadGeneratorModel -->|pre-built TaskProperty list| MobileDeviceManager
    MobileDeviceManager -->|submitTask| EdgeOrchestrator
    EdgeOrchestrator -->|getDeviceToOffload / getVmToOffload(s)| EdgeServerManager
    EdgeOrchestrator --> CloudServerManager
    EdgeOrchestrator --> MobileServerManager
    MobileDeviceManager -->|delay calc| NetworkModel
    NetworkModel -->|location lookup| MobilityModel
    UAVMobilityModel -->|moves| EdgeServerManager
    SimManager --> SimLogger
```

`SimSettings` is a singleton read by almost every class above (config properties +
`applications.xml` + `edge_devices.xml`), so it's the de-facto "global config bus."

## 3. Core framework classes (by package)

### `core/` — wiring & config
| Class | Role |
|---|---|
| `ScenarioFactory` (interface) | The extension seam. One method per pluggable component. **Every scenario implements this.** Has one `default` method (`getEdgeMobilityModel()` → `DefaultUAVMobility`, i.e. static/non-mobile edge servers) so old scenarios that predate UAVs still compile. |
| `SimManager` | Singleton `SimEntity`. Owns all component instances, drives the CloudSim event loop (`CREATE_TASK`, `CHECK_ALL_VM`, `GET_LOAD_LOG`, `PRINT_PROGRESS`, `STOP_SIMULATION`, `GET_UAV_LOCATION_LOG`), starts/stops datacenters, and is the global lookup (`SimManager.getInstance()....`) used everywhere else instead of dependency injection. |
| `SimSettings` | Singleton config loader/holder. Parses the `.properties` file, `edge_devices.xml`, `applications.xml` into typed getters. **Any new config knob goes here first** (comment in the file literally says so). Also owns constant IDs (`CLOUD_DATACENTER_ID`, `GENERIC_EDGE_DEVICE_ID`, ...) and the `VM_TYPES`/`NETWORK_DELAY_TYPES` enums. |

### `mobility/` — device movement
| Class | Role |
|---|---|
| `MobilityModel` (abstract) | `initialize()` + `getLocation(deviceId, time)`. Base for **mobile user** movement. |
| `NomadicMobility` | Discrete place-to-place movement (devices "teleport" between hotspots after exponential dwell time), reading place types from `edge_devices.xml`. |
| `mobility/uav/UAVMobilityModel` (abstract `SimEntity`) | Base for **edge-server (UAV) movement**. Event-driven (`SimSettings.EDGE_SERVER_MOVE` tag) rather than pure `getLocation()` lookup — moves are scheduled and applied to `UAV.setPlace()`. |
| `mobility/uav/DefaultUAVMobility` | No-op implementation — makes edge servers static. Returned by `ScenarioFactory.getEdgeMobilityModel()`'s default method, so it's what every pre-UAV tutorial (1–5) effectively gets. |
| `mobility/uav/BasicUAVMobility` | The real UAV policy engine. Constructor takes a policy name string; `processMoveEvent` is a big `switch` over policy names: `NO`, `RANDOM`, `LOCAL` (chase centroid of users in `SERVICE_RADIUS`), `LOCAL_FORCE` (LOCAL + inverse-square repulsion from other UAVs so they spread out, tuned by private `COORDINATION_RADIUS`/`REPULSION_GAIN`), `VORONOI` (decentralized centroidal-Voronoi coverage control — each UAV partitions ALL users by nearest-UAV using only every UAV's current position, then chases its own cell's centroid; no `SERVICE_RADIUS` cap since the partition itself prevents overlap), `ASSIGNED_LOCAL` (fixed round-robin user→UAV assignment, chases assigned group's centroid regardless of range), `GLOBAL` (declared, currently a no-op). Caches `allUavs` once in `startEntity()`. |
| Application-level mobility models (not in `mobility/`, live under `applications/tutorialN/`) | e.g. `tutorial6.ConvergingMobilityModel` (crowd converges onto 3 hardcoded meeting areas, `ROUND_ROBIN`/`CLOSEST` assignment), `tutorial8.SARTeamMobilityModel` (fixed teams, staged entry, MOVE/STOP cycling, formation offsets), `tutorial8.CombinedMobilityModel` (delegates a `deviceId` range to one sub-model each — the pattern to copy for any "second population"). |

### `task_generator/` — workload generation
| Class | Role |
|---|---|
| `LoadGeneratorModel` (abstract) | Pre-computes the **entire** task timeline before the sim starts (`initializeModel()` → `taskList: List<TaskProperty>`), plus `getTaskTypeOfDevice(deviceId)`. |
| `IdleActiveLoadGenerator` | Default: assigns each device one task type (weighted by `usage_percentage`), alternates active (Poisson task arrivals) / idle periods per `applications.xml`. |
| `applications/tutorial8/SARAwareLoadGenerator` | Pattern for **population-aware** generation: splits `applications.xml` app indices into disjoint subsets per population (normal vs SAR), normalizes `usage_percentage` independently within each subset, and delays SAR task generation until `sar_entry_time`. Copy this pattern for any new device population with its own app catalogue. |

### `network/` — delay modeling
| Class | Role |
|---|---|
| `NetworkModel` (abstract) | `getUploadDelay`/`getDownloadDelay(sourceId, destId, Task)` + upload/download start/finish hooks used for queue-state bookkeeping. |
| `MM1Queue` | Classic M/M/1 queueing-theory delay model (WLAN/MAN/WAN/GSM tiers) — the "default" network model used by tutorial1-5. |
| `network/uav/UAVNetworkModel` | M/M/1-style WLAN delay specialized for the UAV scenarios; returns `0.0` (treated as "link unavailable/reject") instead of a negative delay when the queue is saturated. |

### `edge_orchestrator/` — placement decisions
| Class | Role |
|---|---|
| `EdgeOrchestrator` (abstract `SimEntity`) | `initialize()`, `getDeviceToOffload(Task)` (cloud vs edge vs mobile, returns a device-type constant), `getVmToOffload(Task, deviceId)` (pick one VM), and `getVmsToOffload(List<Task>, deviceId)` (**batch** selection for sibling sub-tasks of a partitioned task — default impl just loops `getVmToOffload`; override this if your placement needs to see the whole batch's cumulative load before committing, as `UAVEdgeOrchestrator` does). |
| `BasicEdgeOrchestrator` | Implements `FIRST_FIT`/`NEXT_FIT`/`BEST_FIT`/`WORST_FIT`/`RANDOM_FIT` VM selection, location-aware (`selectVmOnHost`) or global-load-balanced (`selectVmOnLoadBalancer`, only in `TWO_TIER_WITH_EO` scenario) placement on the edge tier, worst-fit-by-capacity on cloud. Policy name comes from `orchestrator_policies` config and is read via `this.policy`. |
| `edge_orchestrator/uav/UAVEdgeOrchestrator` | Always offloads to the (generic) edge tier; picks the least-loaded **UAV whose `SERVICE_RADIUS` covers the requesting user's location**. Overrides `getVmsToOffload` to reserve predicted load per UAV across a whole sibling batch before binding any of them (avoids over-committing one UAV with several sub-tasks from the same parent task). |

### `edge_server/` — edge (and UAV) infrastructure
| Class | Role |
|---|---|
| `EdgeServerManager` (abstract) | Owns `List<Datacenter>` + per-host `List<EdgeVM>`; `startDatacenters()`/`terminateDatacenters()`/`getAvgUtilization()`. |
| `DefaultEdgeServerManager` | Parses `edge_devices.xml` → CloudSim `Datacenter`/`Host`/`Vm` objects; assigns each host a `Location`, applies a random position jitter (`HOST_POSITION_OFFSET_RANGE`) so co-located hosts don't overlap exactly; uses `EdgeVmAllocationPolicy_Custom`. |
| `EdgeHost` | CloudSim `Host` subclass + a `Location`. |
| `EdgeVM` | CloudSim `Vm` subclass carrying its `VM_TYPES` tag. |
| `EdgeVmAllocationPolicy_Custom` | VM→host allocation policy for edge datacenters. |
| `edge_server/uav/UAV` (extends `EdgeHost`) | Adds `mobilityInterval`/`speed`/`maxMoveDistance`, `SERVICE_RADIUS` (static, **shared by all UAVs**, currently `100`), `isUserInRange(Location)` (bounding-box, not circular!), `getVm()`/`getCurrentLoad()` convenience accessors. **This is the class that makes an edge host "a UAV"** — orchestrators/mobility code do `instanceof UAV` / cast to `UAV` to decide UAV-specific behavior; plain `EdgeHost` instances (tutorial1-5) are unaffected. |

### `cloud_server/` — cloud infrastructure (mirrors `edge_server/`)
`CloudServerManager` (abstract) → `DefaultCloudServerManager` (XML-driven, single `localDatacenter`), `CloudVM`, `CloudVmAllocationPolicy_Custom`.

### `edge_client/` — mobile-device-side logic
| Class | Role |
|---|---|
| `MobileDeviceManager` (abstract, extends CloudSim `DatacenterBroker`) | `submitTask(TaskProperty)` is the entry point every task passes through; `getCpuUtilizationModel()`. |
| `DefaultMobileDeviceManager` | The real task lifecycle state machine: upload → (optional) execution on assigned VM → download → logging, PLUS **task partitioning**: `submitTask` branches on `TaskProperty.isPartitionable()` into `submitPartitionableTask(...)`, which creates a `parentTaskId`, splits the task into N children via `splitTaskProperty` (currently **always an equal split** — see §7 gotchas), calls `EdgeOrchestrator.getVmsToOffload` once for the whole sibling batch, and tracks completion/failure of the whole batch in an internal `PartitionState` (upload/download delay sums, `completedChildren`/`totalChildren`, `failed` flag) so the **parent** task gets exactly one aggregated log entry (see `SimLogger.taskEnded`). Any partial sibling failure fails the whole partition (`failPartition(...)`). |
| `Task` (extends CloudSim `Cloudlet`) | Adds `submittedLocation`, `creationTime`, `type`, `mobileDeviceId`, `hostIndex`/`vmIndex`/`datacenterId`, and partition bookkeeping: `partitionChild`, `parentTaskId`, `childIndex`, `childCount`. |
| `CpuUtilizationModel_Custom` | Looks up per-app, per-tier (`EDGE_VM`/`CLOUD_VM`/`MOBILE_VM`) CPU utilization from `applications.xml` (`vm_utilization_on_*`) via `SimSettings.getTaskLookUpTable()`; `predictUtilization(vmType)` is what orchestrators call *before* binding a task to estimate fit. |
| `edge_client/mobile_processing_unit/` (`MobileServerManager`, `DefaultMobileServerManager`, `MobileHost`, `MobileVM`, `MobileVmAllocationPolicy_Custom`) | Same abstract-manager/XML-driven pattern as edge/cloud, but models the **mobile device's own** local processing tier (used when an orchestrator decides to run a task locally instead of offloading). |

### `utils/` — shared data & helpers
| Class | Role |
|---|---|
| `Location` | `(xPos, yPos, servingWlanId, placeTypeIndex)`. |
| `TaskProperty` | Immutable descriptor produced by a `LoadGeneratorModel` for one task instance: timing, size, `taskType`, `mobileDeviceId`, `partitionable`/`partitionCount` (looked up from `SimSettings` at construction time). |
| `SimLogger` | Singleton; buffers per-task and per-VM/location logs, writes `.log`/`.csv` files at `simStopped()`; also the destination for `addUavLocationLog(time, uavId, x, y)` → `<prefix>_UAV_LOCATIONS.log`. |
| `SimUtils` | RNG (`RNG` field), `getRandomNumber`, `getEuclideanDistance`, time-diff formatting. |
| `PoissonDistr` | Poisson sampling helper used by load generators. |

## 4. Applications / tutorials — what each demonstrates

| Folder | Adds on top of the previous one |
|---|---|
| `sample_app1`..`sample_app4` | Original upstream EdgeCloudSim samples (single/two-tier, load balancing, mobility, multiple VM types). Largely superseded by `tutorial1`-`tutorial5` in this fork but kept for reference. |
| `sample_app5` | Vehicular/game-theory/multi-armed-bandit + Weka-based ML orchestrator experiments (`GameTheoryHelper`, `MultiArmedBanditHelper`, `WekaWrapper`, `Vehicular*` classes) — the most complex, semi-independent sample; treat as a separate mini-framework rather than part of the core wiring above. |
| `tutorial1` | Minimal baseline scenario (single app, `SampleMobilityModel` random walk, static edge hosts). |
| `tutorial2`..`tutorial5` | Incrementally add: mobile-device local processing tier, multiple applications, custom mobility/load-generator variants, edge-orchestrator policy variety — see each folder's `README.md` for specifics. |
| `tutorial6` | Introduces **UAV mobile edge servers** (`mobility/uav`, `edge_server/uav`, `edge_orchestrator/uav`, `network/uav`) + `ConvergingMobilityModel` (crowd-converges-on-3-areas user mobility). |
| `tutorial7` | Adds **task partitioning** on top of tutorial6 (sweeps `task_partition_policies` in `MainApp`; `applications.xml` apps carry `partitionable`/`partition_count`/`partition_strategy`). |
| `tutorial8` | Adds the **SAR (Search & Rescue) scenario**: a second fixed-size device population with its own mobility (`SARTeamMobilityModel`), its own reserved app subset (`SARAwareLoadGenerator`), sharing the device-id space with normal users via `CombinedMobilityModel`. See [/memories/repo/tutorial8_sar_scenario.md](/memories/repo/tutorial8_sar_scenario.md) for the deep-dive notes on this scenario (build/run commands, plotting scripts, the `LOCAL_FORCE` UAV policy, known pre-existing script bugs). |

Each tutorial folder has its own `MainApp` (sweep loop over device counts / scenarios /
orchestrator policies / [task-partition policies] / [UAV mobility options]) and its own
`SampleScenarioFactory` wiring — **copy the nearest existing tutorial** when starting a
new experiment rather than editing core classes in place.

## 5. Config files reference

Each tutorial's `scripts/tutorialN/config/` has three files, all parsed by `SimSettings.initialize(...)`:

- **`default_config.properties`** — flat key=value. Comma-separated values become sweep
  axes in `MainApp` (e.g. `orchestrator_policies`, `simulation_scenarios`,
  `task_partition_policies` [tutorial7+], `uav_mobility_options` [tutorial6+]). New scalar
  knobs (like tutorial8's `sar_*` keys) should default to a harmless value (0/empty) so
  older tutorials that don't set them are unaffected — see `SimSettings` SAR fields for
  the pattern.
- **`applications.xml`** — one `<application name="...">` per task type, parsed into
  `SimSettings.getTaskLookUpTable()` (a `double[appIndex][columnIndex]` table). Columns
  (mandatory): `usage_percentage`, `prob_cloud_selection`, `poisson_interarrival`,
  `delay_sensitivity`, `active_period`, `idle_period`, `data_upload`, `data_download`,
  `task_length`, `required_core`; optional: `vm_utilization_on_edge/cloud/mobile`.
  Optional partitioning tags (tutorial7+): `partitionable`, `partition_count`,
  `partition_strategy` (only `EQUAL` is actually implemented — see §7).
- **`edge_devices.xml`** — `<datacenter>` → `<host>` → `<VM>` hierarchy consumed by
  `DefaultEdgeServerManager`/`DefaultCloudServerManager` to build CloudSim
  hosts/VMs, plus per-datacenter `Location`/place-type/attractiveness info consumed by
  mobility models.

## 6. Cookbook — "How do I add a new ___?"

For every item, **start from the closest existing tutorial's classes** (usually
`tutorial8` for anything UAV/SAR/partitioning-related) and copy-paste-rename rather than
modifying shared/core classes, unless the feature is generic enough to belong in `core`/
abstract base classes.

### 6.1 New mobility policy for *normal mobile users*
1. Create a class extending `mobility.MobilityModel` (e.g. under
   `applications/tutorialN/`, following `ConvergingMobilityModel`'s pattern), implementing
   `initialize()` and `getLocation(deviceId, time)`.
2. Wire it in your scenario's `ScenarioFactory.getMobilityModel()`.
3. If it needs new config (assignment policy name, speeds, radii, ...), add
   properties/getters to `SimSettings` (default to a value that doesn't break other
   scenarios) and read them in the `properties` file.
4. If users should be logged/plotted, no extra work needed — `SimManager`'s
   `GET_LOAD_LOG`/location logging already iterates `0..numOfMobileDevice-1` generically.

### 6.2 New mobility policy for *UAVs / mobile edge servers*
1. Add a new case to `mobility.uav.BasicUAVMobility.processMoveEvent`'s `switch`
   (preferred, keeps all policies in one place and reuses `allUavs`/bounds-clamping), OR
   write a whole new `UAVMobilityModel` subclass if the policy needs fundamentally
   different state/events.
2. Register the new policy name string in the relevant tutorial's
   `default_config.properties` (`uav_mobility_options=...`).
3. If you build/maintain Python plotting for it: `scripts/tutorial8/python/config.py`'s
   `scenario_types`/`legends`/`colors`/`*_markers` lists are the single source other
   tutorial8 plot scripts (including `plotUserLocationHeatmapVideo.py`) derive from — add
   the new policy there. **The MATLAB scripts under `scripts/tutorialN/matlab` are NOT
   wired to the UAV-mobility axis at all** (pre-existing gap) — don't assume they pick up
   new policies automatically.
4. Remember `UAV.SERVICE_RADIUS` and `BasicUAVMobility`'s `COORDINATION_RADIUS`/
   `REPULSION_GAIN` are currently hardcoded/private constants, not per-policy config.
5. `VORONOI` is the reference example of a policy that recomputes global partitioning
   per-UAV from `allUavs` each move event (O(numUAVs) per user) rather than using a fixed
   `SERVICE_RADIUS` cutoff — copy it if a new policy needs full-coverage partitioning
   instead of a local reaction radius.

### 6.3 New task-partitioning policy/strategy
Current state (important gotcha): `task_partition_policies` in `.properties` is only a
**binary NO / not-NO switch** — `SimSettings.isTaskPartitionable(taskType)` returns
`false` for policy `"NO"` and otherwise just returns the per-app `partitionable` flag from
`applications.xml`, regardless of the policy's actual name. Similarly, `partition_strategy`
is parsed and stored (`SimSettings.getTaskPartitionStrategy`) but **not read by
anything** — `DefaultMobileDeviceManager.splitTaskProperty` always does an equal split.
To add a *real* new strategy:
1. Add the new strategy name as a case somewhere `DefaultMobileDeviceManager.
   splitTaskProperty(...)` builds `lengthParts`/`uploadParts`/`downloadParts` — branch on
   `SimSettings.getInstance().getTaskPartitionStrategy(taskType)` instead of always
   calling the equal-split `splitValue(...)`.
2. If the policy should affect *how many* children or *whether* a task partitions (not
   just how work is divided), extend `SimSettings.getTaskPartitionPolicy()`/
   `isTaskPartitionable`/`getTaskPartitionCount` to branch on the current policy string,
   not just check `"NO"`.
3. If sibling sub-tasks need coordinated placement, that's already supported via
   `EdgeOrchestrator.getVmsToOffload(List<Task>, deviceId)` — override it (see
   `UAVEdgeOrchestrator`) rather than re-deriving batch-aware placement.
4. Register the new policy name in `task_partition_policies=...` in the tutorial's
   `default_config.properties` (only meaningful from tutorial7 onward, where `MainApp`
   sweeps this axis and calls `SS.setTaskPartitionPolicy(...)`).

### 6.4 New task/application type
1. Add an `<application name="...">` block to `applications.xml` with the mandatory
   fields (see §5); optionally `partitionable`/`partition_count`/`partition_strategy`.
2. Nothing else in Java needs to change for a "normal" app — `IdleActiveLoadGenerator`
   picks it up automatically via `SimSettings.getTaskLookUpTable()`. Only write a custom
   `LoadGeneratorModel` if the new app needs bespoke arrival semantics (see §6.5's
   `SARAwareLoadGenerator` pattern) or a custom `CpuUtilizationModel`/orchestrator logic if
   it needs non-standard resource behavior.
3. If the app is meant to be reserved for a specific device subpopulation (like SAR), see
   §6.5.

### 6.5 New user type / device subpopulation (the "SAR" pattern)
Follow `applications/tutorial8`'s three-file pattern exactly:
1. **Mobility**: write a `MobilityModel` for the new population (own movement rules,
   entry/staging behavior if it joins later), then a `Combined*MobilityModel` that owns
   both sub-models and dispatches `getLocation(deviceId, time)` by splitting the
   `deviceId` range (`[0, numPopulationA)` → model A, `[numPopulationA, total)` → model
   B, offsetting the id before delegating). This is what lets every other subsystem (UAV
   tracking, network delay, logging) treat the whole thing as "just more device ids" with
   zero changes elsewhere.
2. **Load generation**: write a `LoadGeneratorModel` that restricts each population to its
   own reserved subset of `applications.xml` entries (matched by name, e.g. a
   `..._application_names` comma-list config key) and normalizes `usage_percentage`
   independently per subset. Gate task generation start time per population if it should
   enter later (compare `CloudSim.clock()`/task start time against an entry-time config
   value).
3. **Config**: add population size / entry-time / behavior knobs to `SimSettings`,
   defaulting to 0/empty so other tutorials are unaffected (copy the `SAR_*` fields
   exactly).
4. **`MainApp` / `ScenarioFactory`**: compute
   `totalNumOfMobileDevices = sweptPopulationCount + newPopulationCount` and pass that
   total to `SimManager` (so UAV tracking/logging, which iterate
   `0..numOfMobileDevice-1`, cover everyone), but pass the two counts **separately** into
   your `ScenarioFactory`/`LoadGeneratorModel`/`CombinedMobilityModel` constructors so
   each can compute its own id-range split.
5. Everything downstream (network model, orchestrator, UAV mobility, logging) needs **no
   changes** as long as it only ever asks `SimManager.getInstance().getMobilityModel()
   .getLocation(deviceId, time)` / iterates `0..numOfMobileDevice-1` generically — don't
   special-case the new population id range anywhere outside the mobility/load-generator
   layer.

### 6.6 New edge-orchestrator (VM placement) policy
1. Either add a new policy branch inside `BasicEdgeOrchestrator` (if it's a variant of
   FIRST/NEXT/BEST/WORST/RANDOM-fit), or write a new `EdgeOrchestrator` subclass (like
   `UAVEdgeOrchestrator`) if the placement logic is fundamentally different (e.g. needs
   location/range awareness, energy modeling, ML-based decisions à la `sample_app5`).
2. Override `getDeviceToOffload` (cloud/edge/mobile choice) and `getVmToOffload`
   (specific VM/host). If your scenario uses task partitioning, also override
   `getVmsToOffload(List<Task>, deviceId)` for batch-aware placement (default just calls
   `getVmToOffload` per task, blind to siblings).
3. Register the policy name string in `orchestrator_policies=...` and read it via
   `this.policy` inside your orchestrator (set by the `EdgeOrchestrator(policy,
   simScenario)` constructor).
4. Wire the class in `ScenarioFactory.getEdgeOrchestrator()`.

### 6.7 New network delay model
Extend `network.NetworkModel`, implement `initialize()`/`getUploadDelay`/
`getDownloadDelay` (+ the `*Started`/`*Finished` bookkeeping hooks used for queue state),
wire via `ScenarioFactory.getNetworkModel()`. `UAVNetworkModel` is the best starting
template if the scenario has UAV edge hosts.

### 6.8 New VM/host resource tier or allocation policy
Edge/cloud/mobile each follow the same trio: `XServerManager` (abstract, lifecycle) →
`DefaultXServerManager` (XML-driven concrete) → `XVmAllocationPolicy_Custom` (VM→host
binding policy). To change *how* VMs are packed onto hosts, edit/extend the relevant
`*VmAllocationPolicy_Custom`; to change *what* hosts/VMs exist, edit `edge_devices.xml`
(no code change needed) or `DefaultEdgeServerManager`/`DefaultCloudServerManager` if the
shape of the XML itself needs to change.

## 7. Known gotchas / pre-existing quirks (verified in code)

- **`partition_strategy` is parsed but unused.** `DefaultMobileDeviceManager.
  splitTaskProperty` always splits length/upload/download equally among children,
  regardless of the `partition_strategy` XML value (`EQUAL` is the only value anything
  ever sets, and it's not even checked). See §6.3 before assuming a different strategy
  name changes behavior.
- **`task_partition_policies` is a NO/not-NO toggle, not a real policy enum.** Any policy
  name other than `"NO"` currently behaves identically (whatever `applications.xml`'s
  per-app `partitionable` says). See §6.3.
- **`UAV.isUserInRange` is a square (bounding-box) check**, not a circle, despite being
  named/used alongside `SERVICE_RADIUS`. `UAVEdgeOrchestrator`'s own range check
  (`SimUtils.getEuclideanDistance(...) > SERVICE_RADIUS`) *is* circular — the two range
  checks in the codebase are inconsistent with each other.
- **`UAV.SERVICE_RADIUS` is `public static`** — shared by every UAV instance; there's no
  per-UAV service radius today.
- **MATLAB plotting scripts under `scripts/tutorialN/matlab` are not wired to the
  UAV-mobility axis** (they key off `getConfiguration(5)/(6)` = orchestrator policies
  only) and tutorial6's matlab `getConfiguration.m` had a stale `tutorial1`-copy-paste
  filename bug (fixed during the tutorial8 work — verify similar staleness before trusting
  other tutorials' matlab scripts blindly).
- **UAV location logging is core, not UAV-specific**: `SimManager` always schedules
  `GET_UAV_LOCATION_LOG` and calls `SimLogger.addUavLocationLog`, but it's a harmless
  no-op unless edge hosts are actually `UAV` instances (checked via `instanceof`) —
  tutorial1-5 (plain `EdgeHost`) simply never populate `_UAV_LOCATIONS.log`.
- **`DefaultUAVMobility` sets `edgeServerManager = null`** in `initialize()` — fine since
  it never uses it (no-op `processMoveEvent`), but don't copy this pattern into a real
  policy.
- See [/memories/repo/tutorial8_sar_scenario.md](/memories/repo/tutorial8_sar_scenario.md)
  for tutorial8-specific build/run commands and additional history (e.g. the
  `LOCAL_FORCE` UAV policy's tuning constants, tutorial6 `compile.sh`/matlab bugs found
  and fixed).

## 8. Build & run

Each tutorial is self-contained under `scripts/tutorialN/`:
```bash
cd scripts/tutorialN && sh compile.sh   # javac, outputs to bin/
```
Run manually from repo root (adjust package/tutorial folder name):
```bash
java -classpath 'bin:lib/cloudsim-7.0.0-alpha.jar:lib/commons-math3-3.6.1.jar:lib/colt.jar' \
  edu.boun.edgecloudsim.applications.tutorialN.MainApp \
  <config.properties> <edge_devices.xml> <applications.xml> <output_folder> <iteration>
```
Or use `run_scenarios.sh`/`runner.sh` in the same folder for batch sweeps. Results land
in `sim_results/tutorialN/iteN/`; per-tutorial Python/MATLAB plotting scripts live under
`scripts/tutorialN/python|matlab`.
