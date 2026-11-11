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
  `edge_client/Task`, `utils/TaskProperty`, `core/SimSettings`). A partitioned task can
  also be a **DAG** (tutorial15+): sub-tasks are grouped into dependent LAYERS
  (`applications.xml`'s `dag_layers`) instead of one flat independent batch - layer L
  only starts once every sub-task in layer L-1 has completed, while sub-tasks within the
  same layer run in parallel. See §6.3b. Layer transitions incur a simulated UAV relay
  delay (`uav_bandwidth` config, `NetworkModel.getInterLayerDelay`) only for the output
  that has to move to a *different* UAV than the one that produced it - see §6.3c.
- **Soft task-timeout**: any task (plain or partitioned/DAG) still in flight
  (uploading/processing/downloading) `task_timeout` seconds after its own creation gets
  ONE `TASK_STATUS.TIMED_OUT` outcome logged, but keeps running to completion unaffected
  - it is NOT cancelled. Disabled by default (`task_timeout<=0`). See §6.9.
- **Streams** (tutorial18+): a request can be a *stream* (`streams.xml`) instead of a plain
  application - a DAG (`dag_layers`, e.g. `1,2,1`) whose nodes are **applications/services**
  instead of sub-tasks. Layer L starts once every node of layer L-1 has completed - and, like a
  DAG application's layers, after the finished layer's output has been relayed between UAVs
  (§6.3c) - the nodes of a layer run in parallel, each node is offloaded to a UAV hosting its
  service (or dropped, which fails the whole stream). A stream has its OWN `usage_percentage`/active/idle/
  `poisson_interarrival`/`task_timeout`; the applications it runs ignore theirs while they are
  its nodes. An application can still be requested on its own, and can itself be a DAG of
  sub-tasks. See §6.11.
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
| `SimSettings` | Singleton config loader/holder. Parses the `.properties` file, `edge_devices.xml`, `applications.xml` (and, via the 4-argument `initialize(...)` overload, an optional `streams.xml` - tutorial18+; the 3-argument form every older tutorial calls means "no streams") into typed getters. **Any new config knob goes here first** (comment in the file literally says so). Also owns constant IDs (`CLOUD_DATACENTER_ID`, `GENERIC_EDGE_DEVICE_ID`, ...) and the `VM_TYPES`/`NETWORK_DELAY_TYPES` enums. |
| `CloudletSchedulerSpaceShared_Custom` | The per-VM cloudlet scheduler every edge/cloud/mobile VM is created with (CloudSim's `CloudletSchedulerSpaceShared` + a floor on the submit-time finish estimate so very short tasks can't get stuck in the exec list - see §7). Use it for any new VM-creating manager. |

### `mobility/` — device movement
| Class | Role |
|---|---|
| `MobilityModel` (abstract) | `initialize()` + `getLocation(deviceId, time)` + `isActive(deviceId, time)` (default `true`; override to report a device as not-yet-present, e.g. a staged population before its entry time — see `tutorial8.SARTeamMobilityModel`) + `getPriority(deviceId, time)` (default `1.0`; relative weight this device's position should carry when location-consuming code centroids/averages multiple users together, e.g. `4.0` = counts as 4 ordinary users — see `tutorial8.SARTeamMobilityModel`/`VORONOI`). Base for **mobile user** movement. |
| `NomadicMobility` | Discrete place-to-place movement (devices "teleport" between hotspots after exponential dwell time), reading place types from `edge_devices.xml`. |
| `mobility/uav/UAVMobilityModel` (abstract `SimEntity`) | Base for **edge-server (UAV) movement**. Event-driven (`SimSettings.EDGE_SERVER_MOVE` tag) rather than pure `getLocation()` lookup — moves are scheduled and applied to `UAV.setPlace()`. |
| `mobility/uav/DefaultUAVMobility` | No-op implementation — makes edge servers static. Returned by `ScenarioFactory.getEdgeMobilityModel()`'s default method, so it's what every pre-UAV tutorial (1–5) effectively gets. |
| `mobility/uav/BasicUAVMobility` | The real UAV policy engine. Constructor takes a policy name string; `processMoveEvent` first checks `isVoronoiPolicy(uavMobilityOption)` (true for `"VORONOI"` or any `"VORONOI_<factor>"` variant - see §6.2/§6.5) and otherwise falls through to a `switch`: `NO`, `RANDOM`, `LOCAL` (chase centroid of *active* users in `SERVICE_RADIUS`), `LOCAL_FORCE` (LOCAL + inverse-square repulsion from other UAVs so they spread out, tuned by private `COORDINATION_RADIUS`/`REPULSION_GAIN`), `ASSIGNED_LOCAL` (fixed round-robin user→UAV assignment, chases assigned group's centroid regardless of range), `GLOBAL` (declared, currently a no-op). The VORONOI branch: each UAV partitions only *active* users within its own `SERVICE_RADIUS` by nearest-UAV, considering only competing UAVs close enough to possibly be nearer (bounded by `2 * SERVICE_RADIUS`, via the triangle inequality - see `VoronoiPartitioner.recalculate`), then chases its own cell's centroid, weighted by each user's `MobilityModel.getPriority(...)` so e.g. SAR members can pull the centroid harder than normal users without affecting the nearest-UAV partition itself. A device outside every UAV's `SERVICE_RADIUS` is left unclaimed (nobody's cell) rather than handed to whichever UAV is globally nearest - every decentralized policy's decision is provably a function of purely local information only, never full-scenario state. All of `LOCAL`/`LOCAL_FORCE`/VORONOI/`ASSIGNED_LOCAL` skip devices where `MobilityModel.isActive(deviceId, time)` is false, so a staged population (e.g. SAR members before `sar_entry_time`) can't trap/skew a UAV. Caches `allUavs` once in `startEntity()`. Only tutorial8 sweeps `LOCAL_FORCE`/`VORONOI` (`NO`/`RANDOM` too, plus `VORONOI_2`/`VORONOI_4` in tutorial9); `LOCAL`/`ASSIGNED_LOCAL` remain available in the shared switch (tutorial6/7 still use them) but were dropped from tutorial8/9's `uav_mobility_options`/plotting config. |
| Application-level mobility models (not in `mobility/`, live under `applications/tutorialN/`) | e.g. `tutorial6.ConvergingMobilityModel` (crowd converges onto 3 hardcoded meeting areas, `ROUND_ROBIN`/`CLOSEST` assignment), `tutorial8.SARTeamMobilityModel` (fixed teams, staged entry, MOVE/STOP cycling, formation offsets), `tutorial8.CombinedMobilityModel` (delegates a `deviceId` range to one sub-model each — the pattern to copy for any "second population"). |


### `task_generator/` — workload generation
| Class | Role |
|---|---|
| `LoadGeneratorModel` (abstract) | Pre-computes the **entire** task timeline before the sim starts (`initializeModel()` → `taskList: List<TaskProperty>`), plus `getTaskTypeOfDevice(deviceId)`. |
| `IdleActiveLoadGenerator` | Default: assigns each device one task type (weighted by `usage_percentage`), alternates active (Poisson task arrivals) / idle periods per `applications.xml`. |
| `task_generator/IdleActiveStreamLoadGenerator` | (tutorial18+) `IdleActiveLoadGenerator` for scenarios that also define **streams** (§6.11): every device is assigned ONE request type - an application or a stream - by drawing from the applications' and the streams' `usage_percentage` together (they must add up to 100, else the sim exits with a clear message), then alternates active/idle using THAT type's own `poisson_interarrival`/`active_period`/`idle_period`. A stream request is a `utils/StreamTaskProperty` carrying one pre-sampled `TaskProperty` per node (sampled exactly like a standalone request of the node's app, so an app that is only a stream node - `usage_percentage` 0 - still gets its size/length distributions). With no streams defined it behaves like `IdleActiveLoadGenerator`. |
| `applications/tutorial8/SARAwareLoadGenerator` | Pattern for **population-aware** generation: splits `applications.xml` app indices into disjoint subsets per population (normal vs SAR), normalizes `usage_percentage` independently within each subset, and delays SAR task generation until `sar_entry_time`. Copy this pattern for any new device population with its own app catalogue. |

### `network/` — delay modeling
| Class | Role |
|---|---|
| `NetworkModel` (abstract) | `getUploadDelay`/`getDownloadDelay(sourceId, destId, Task)` + upload/download start/finish hooks used for queue-state bookkeeping. |
| `MM1Queue` | Classic M/M/1 queueing-theory delay model (WLAN/MAN/WAN/GSM tiers) — the "default" network model used by tutorial1-5. |
| `network/uav/UAVNetworkModel` | WLAN delay for the UAV scenarios, driven by real simulation data rather than configured averages: each UAV's WLAN is one shared FCFS link, and a transfer takes the task's **own** payload (`getCloudletFileSize()` for an upload, `getCloudletOutputSize()` for a download; a sub-KB/0 KB payload counts as 1 KB so it is never mistaken for a rejection) divided by the bandwidth at the device↔UAV distance **at transfer time** (Shannon-scaled, see `getBandwidthAtDistance`), plus the wait behind the transfers already booked on that UAV's link (`linkFreeAt`). Returns `0.0` (treated as "link unavailable/reject") when there is no UAV link or the device is outside `UAV.SERVICE_RADIUS`. The delay getters book the transfer themselves (the `*Started`/`*Finished` hooks are no-ops) - `DefaultMobileDeviceManager` asks once per real transfer and always starts it when the result is positive. |

### `edge_orchestrator/` — placement decisions
| Class | Role |
|---|---|
| `EdgeOrchestrator` (abstract `SimEntity`) | `initialize()`, `getDeviceToOffload(Task)` (cloud vs edge vs mobile, returns a device-type constant), `getVmToOffload(Task, deviceId)` (pick one VM), and `getVmsToOffload(List<Task>, deviceId)` (**batch** selection for sibling sub-tasks of a partitioned task — default impl just loops `getVmToOffload`; override this if your placement needs to see the whole batch's cumulative load before committing, as `UAVEdgeOrchestrator` does). |
| `BasicEdgeOrchestrator` | Implements `FIRST_FIT`/`NEXT_FIT`/`BEST_FIT`/`WORST_FIT`/`RANDOM_FIT` VM selection, location-aware (`selectVmOnHost`) or global-load-balanced (`selectVmOnLoadBalancer`, only in `TWO_TIER_WITH_EO` scenario) placement on the edge tier, worst-fit-by-capacity on cloud. Policy name comes from `orchestrator_policies` config and is read via `this.policy`. |
| `edge_orchestrator/uav/UAVEdgeOrchestrator` | Always offloads to the (generic) edge tier; picks the least-loaded **UAV whose `SERVICE_RADIUS` covers the requesting user's location AND that is declared capable of the task's app** (`UAV.canServeTaskType()` — see §6.10). Overrides `getVmsToOffload` to reserve predicted load per UAV across a whole sibling batch before binding any of them (avoids over-committing one UAV with several sub-tasks from the same parent task). |

### `edge_server/` — edge (and UAV) infrastructure
| Class | Role |
|---|---|
| `EdgeServerManager` (abstract) | Owns `List<Datacenter>` + per-host `List<EdgeVM>`; `startDatacenters()`/`terminateDatacenters()`/`getAvgUtilization()`. |
| `DefaultEdgeServerManager` | Parses `edge_devices.xml` → CloudSim `Datacenter`/`Host`/`Vm` objects; assigns each host a `Location`, applies a random position jitter (`HOST_POSITION_OFFSET_RANGE`) so co-located hosts don't overlap exactly; uses `EdgeVmAllocationPolicy_Custom`. |
| `EdgeHost` | CloudSim `Host` subclass + a `Location`. |
| `EdgeVM` | CloudSim `Vm` subclass carrying its `VM_TYPES` tag. |
| `EdgeVmAllocationPolicy_Custom` | VM→host allocation policy for edge datacenters. |
| `edge_server/uav/UAV` (extends `EdgeHost`) | Adds `mobilityInterval`/`speed`/`maxMoveDistance`, `SERVICE_RADIUS` (static, **shared by all UAVs**, currently `100`), `isUserInRange(Location)` (bounding-box, not circular!), `getVm()`/`getCurrentLoad()` convenience accessors, and `canServeTaskType(serviceIndex)` (tutorial17+ — true when unrestricted, i.e. neither `edge_devices.xml` (`service_capability` tag) nor a placement policy gave this host a capability list, or when `serviceIndex` is in that host's list; see §6.10). A second constructor takes the capability list explicitly (tutorial17's `SampleEdgeServerManager` passes it the `ServicePlacementPolicy` result); the original constructor still reads it from `edge_devices.xml`. **This is the class that makes an edge host "a UAV"** — orchestrators/mobility code do `instanceof UAV` / cast to `UAV` to decide UAV-specific behavior; plain `EdgeHost` instances (tutorial1-5) are unaffected. |
| `edge_server/uav/ServicePlacementPolicy` | (tutorial17+) Decides which services (distinct `applications.xml` `service_index` values) every UAV hosts, from a policy name (`service_placement_policies` in `default_config.properties`). `place(numOfUAVs)` returns, per UAV id, its `service_index` list. Policies are a `switch` on the name, like the UAV mobility options: `SINGLE` (one service per UAV, spread as evenly as possible, UAV `i` hosts service `i % #services` — round-robin rather than contiguous blocks, since consecutive UAV ids share a datacenter/start area) and `ALL` (every UAV hosts every service). Reads the services from `SimSettings`, so per-service attributes added to `applications.xml` later (priority, size, popularity) can feed new policies without changing callers. See §6.10. |

### `cloud_server/` — cloud infrastructure (mirrors `edge_server/`)
`CloudServerManager` (abstract) → `DefaultCloudServerManager` (XML-driven, single `localDatacenter`), `CloudVM`, `CloudVmAllocationPolicy_Custom`.

### `edge_client/` — mobile-device-side logic
| Class | Role |
|---|---|
| `MobileDeviceManager` (abstract, extends CloudSim `DatacenterBroker`) | `submitTask(TaskProperty)` is the entry point every task passes through; `getCpuUtilizationModel()`. |
| `DefaultMobileDeviceManager` | The real task lifecycle state machine: upload → (optional) execution on assigned VM → download → logging, PLUS **task partitioning**: `submitTask` branches on `TaskProperty.isPartitionable()`, then on `SimSettings.isTaskDag(taskType)`, into either `submitPartitionableTask(...)` (flat) or `submitDagTask(...)` (layered DAG, tutorial15+ — see §6.3b). Both create a `parentTaskId`, split the task into N children (`splitTaskProperty`/`splitTaskPropertyIntoDagLayers`, currently **always an equal split** — see §7 gotchas), and submit sibling batches (one per layer, for a DAG) via the shared `submitChildTaskBatch` (calls `EdgeOrchestrator.getVmsToOffload` once per batch), tracking completion/failure in an internal `PartitionState` (upload/download delay sums, `completedChildren`/`totalChildren`, `failed` flag, plus `isDag`/`remainingLayers` for the DAG path) so the **parent** task gets exactly one aggregated log entry (see `SimLogger.taskEnded`). Any sibling failure fails the whole partition/DAG (`failPartition(...)`). |
| `DefaultMobileDeviceManager` (streams, tutorial18+) | `submitTask` first checks for a `StreamTaskProperty` and routes it to `submitStream`: the stream is the ONE unit logged in the statistics (like a partitioned task's parent), tracked in a `StreamState` (`streamStateMap`); `releaseStreamLayer` builds every node of the next layer (`planStreamNode` - a plain `Task` or a `PreparedPartition`), has the orchestrator choose all their UAVs at once, works out the inter-layer relay delay with the same `getInterLayerRelayDelay` the sub-task DAG uses, and `startStreamLayer` then starts the nodes on those UAVs (`submitPlainTaskToVm` / `startPartitionedTask`, the latter also what `submitApplicationTask` now uses for a standalone partitioned request, with no preselected VMs) - minus the app's own `task_timeout`; `onStreamNodeEnded` (a `SimLogger.StreamNodeListener`, registered in `initialize()`) advances the layers, completes the stream after the last one or releases it when a node failed, and after a timeout waits for the running nodes before logging `TIMED_OUT` (`timeoutStream`). See §6.11. |
| `Task` (extends CloudSim `Cloudlet`) | Adds `submittedLocation`, `creationTime`, `type`, `mobileDeviceId`, `hostIndex`/`vmIndex`/`datacenterId`, and partition bookkeeping: `partitionChild`, `parentTaskId`, `childIndex`, `childCount`. |
| `CpuUtilizationModel_Custom` | Looks up per-app, per-tier (`EDGE_VM`/`CLOUD_VM`/`MOBILE_VM`) CPU utilization from `applications.xml` (`vm_utilization_on_*`) via `SimSettings.getTaskLookUpTable()`; `predictUtilization(vmType)` is what orchestrators call *before* binding a task to estimate fit. |
| `edge_client/mobile_processing_unit/` (`MobileServerManager`, `DefaultMobileServerManager`, `MobileHost`, `MobileVM`, `MobileVmAllocationPolicy_Custom`) | Same abstract-manager/XML-driven pattern as edge/cloud, but models the **mobile device's own** local processing tier (used when an orchestrator decides to run a task locally instead of offloading). |

### `utils/` — shared data & helpers
| Class | Role |
|---|---|
| `Location` | `(xPos, yPos, servingWlanId, placeTypeIndex)`. |
| `TaskProperty` | Immutable descriptor produced by a `LoadGeneratorModel` for one task instance: timing, size, `taskType`, `mobileDeviceId`, `partitionable`/`partitionCount` (looked up from `SimSettings` at construction time). |
| `StreamTaskProperty` (extends `TaskProperty`) | (tutorial18+) One request of a **stream** (§6.11): `getStreamIndex()` + `getNodeLayers()` (one pre-sampled `TaskProperty` per node, per layer, in dependency order). Its own `taskType` is the stream's task type (`SimSettings.getStreamTaskType`) - streams are numbered right after the applications - and its length/upload/download are only informational sums over its nodes. `DefaultMobileDeviceManager.submitTask` dispatches on `instanceof StreamTaskProperty`. |
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
| `tutorial9` | A full copy of tutorial8's wiring (same 6 Java classes, package `edu.boun.edgecloudsim.applications.tutorial9`, `APPLICATION_FOLDER = "tutorial9"`) whose `default_config.properties` sweeps **only VORONOI policy variants** (`uav_mobility_options=VORONOI,VORONOI_2,VORONOI_4`) to compare SAR priority factors under one batch run - see §6.2 and the `tutorial8_sar_scenario` memory note for how `VORONOI_<factor>` naming works. |
| `tutorial15` | A copy of tutorial14's wiring (normal users only - no SAR, centralized UAV mobility fixed to `PRIORITY_KMEANS`, same `SARAwareLoadGenerator`/`CombinedMobilityModel` structural parity) demonstrating **DAG-structured task partitioning**: `applications.xml`'s single `DAG_WORKFLOW` app sets `dag_layers=1,2,1` (1 entry sub-task -> 2 parallel -> 1 final sub-task) and `dag_partition_weights=0.1, (0.4, 0.4), 0.1`, swept against `task_partition_policies=NO,FULL,FULL_LAYERED,FULL_WEIGHTED,FULL_(0.1,(0.2,0.6),0.1),PARTITION_4` (`NO` runs it as one flat unpartitioned task; `FULL` runs the real layered DAG split EQUALLY across every sub-task regardless of layer; `FULL_LAYERED` runs the SAME real layered DAG but split equally across layers first and then equally again within each layer, via a `partition_strategy` override - see §6.3b; `FULL_WEIGHTED` runs the SAME real layered DAG but split per the app's explicit `dag_partition_weights`, via a `partition_strategy` override to `DAG_PARTITION_STRATEGY_WEIGHTED` - see §6.3d; `FULL_(<weights>)` runs the SAME real layered DAG but split per weights encoded directly in the policy name itself, ignoring the app's own `dag_partition_weights` for that run - see §6.3d point 5; `PARTITION_4` forces a flat 4-way split ignoring `dag_layers` entirely). See §6.3b/§6.3d. Users converge onto a **single meeting zone at the map center** (`CombinedMobilityModel` passes `ConvergingMobilityModel`'s new 4-arg constructor a one-entry `{500,500}` area, instead of the default 3 areas every other tutorial sharing that class still uses), and `max_number_of_mobile_devices=1000` (other tutorials in this family default to 800). |
| `tutorial16` | **Single-UAV queue study** (see §7 for the M/M/1/K reading): the same single-center user mobility and `PRIORITY_KMEANS` UAV mobility as tutorial15, but exactly ONE UAV (`edge_devices.xml`: one host, one 1-core 2500-MIPS VM) and ONE non-partitionable app (`QUEUE_TASK`). It sweeps the number of users (`min`/`max_number_of_mobile_devices`) and, like tutorial15's `task_partition_policies`, a list of **VM utilization policies**: `vm_utilization_policies=UTILIZATION_10,UTILIZATION_30,UTILIZATION_50,...` in `default_config.properties` — each entry is a policy name `UTILIZATION_<percent>` (decimals allowed) whose value `MainApp` writes into every app's `vm_utilization_on_edge` for that run via `SimSettings.setEdgeVmUtilization()` (applications.xml's own value is only a placeholder), so several utilizations land in the same plot as separate lines (same "numeric parameter encoded in the policy name" convention as `PARTITION_<N>`, §6.2 point 8). A missing list or an entry that isn't `UTILIZATION_<percent>` aborts the run up front. Results go to `sim_results/tutorial16/ite<N>/SIMRESULT_<scenario>_<orchestrator>_UTILIZATION_<percent>_<users>DEVICES_*`. No SAR and no partitioning, so it uses the core `IdleActiveLoadGenerator` and tutorial6's `ConvergingMobilityModel` directly instead of tutorial15's `SARAwareLoadGenerator`/`CombinedMobilityModel`. With the default 1 task per user per 100 s and mean 1 s service the offered load is users/100; `deep_file_log_enabled=true` so per-task `*_SUCCESS.log`/`*_FAIL.log` and a 0.3 s `*_VM_LOAD.log` are written. `python/` is tutorial15's plotting folder copied as-is (`plot.sh` + `plotAvg*.py`), adapted only so the scenarios/legends come from `vm_utilization_policies` (legend "30% Utilization"; x axis is the number of clients). |
| `tutorial17` | Compares **service placement policies** (§6.10): which application services each UAV hosts is decided by a `ServicePlacementPolicy` swept via `service_placement_policies` in `default_config.properties` (currently `SINGLE,ALL`) instead of hard-coded per-host `service_capability` lists. `applications.xml` defines 4 otherwise-identical apps (`SERVICE_APP_0..3`, each `usage_percentage=25`), one per explicit `service_index` (0-3); `edge_devices.xml` (16 UAVs, tutorial10's 4-datacenter/4-host-each corner layout) declares no `service_capability` at all. `SINGLE` gives each UAV exactly one service (16 UAVs / 4 services = 4 UAVs per service, interleaved so every datacenter's UAVs host different services), `ALL` gives every UAV every service. `UAVEdgeOrchestrator`/`UAV.canServeTaskType()` enforce the result on every placement decision - a task whose service is hosted by no in-range, non-full UAV is rejected (`REJECTED_DUE_TO_VM_CAPACITY`). `MainApp` sweeps placement policy as the innermost loop (inside the UAV mobility option), `SIMRESULT_*` file names gain a `_<PLACEMENT>` segment after the mobility option, and the python plot lines are the placement policies (`config.py` holds the first `uav_mobility_options` entry fixed, and warns if more are listed). Users move with a plain, non-converging random walk (tutorial6's `SampleMobilityModel`, reused directly - no `ConvergingMobilityModel`/meeting zone), while `uav_mobility_options` sweeps every real `CentralizedUAVMobility` policy EXCEPT `NO`/`RANDOM`: `PRIORITY_KMEANS,APF,CAPACITY_FILL,ADAPTIVE_KMEANS` (see §6.2). No SAR, no partitioning - uses the core `IdleActiveLoadGenerator` directly, same non-SAR structure as tutorial16. |
| `tutorial18` | tutorial17's service-placement scenario (same random-walk users, 16-UAV `edge_devices.xml`, `SINGLE,ALL` placement sweep, centralized UAV mobility) plus **streams** (§6.11). Takes a 6th `MainApp` argument, `streams.xml` (`runner.sh`/`run_scenarios.sh`/`simulation.list` carry it as a 4th `;` field). `applications.xml`: `SERVICE_APP_0` (plain, `usage_percentage` 20, with its own `task_timeout` that only applies standalone), `SERVICE_APP_1` (`usage_percentage` 20 and itself a `dag_layers=1,2,1` DAG of sub-tasks, so it runs as that DAG both alone and as a stream node), `SERVICE_APP_2`/`SERVICE_APP_3` (`usage_percentage` 0 - stream nodes only). `streams.xml`: `SURVEILLANCE_STREAM` (30%, `1,2,1`: `SERVICE_APP_0, (SERVICE_APP_1, SERVICE_APP_2), SERVICE_APP_3`, own 20 s timeout, 60 s active / 30 s idle) and `MONITORING_STREAM` (30%, `1,1`, no timeout, always active); applications' 40% + streams' 60% = 100. Uses `IdleActiveStreamLoadGenerator`. Statistics gain one `<STREAM_NAME>_GENERIC.log` per stream next to the per-app ones (only apps/streams with `usage_percentage` > 0 get a file, so the stream-only `SERVICE_APP_2/3` have none), `ALL_APPS_GENERIC.log` aggregates ONLY the applications requested on their own and `ALL_STREAMS_GENERIC.log` only the streams (§6.11 point 4). `python/` is tutorial17's plotting folder made group-aware: every plot is produced twice, into `<output>/applications/` (the overall `ALL_APPS` plot + one per standalone application) and `<output>/streams/` (overall `ALL_STREAMS` + one per stream) - see `config.get_plot_groups()`; applications that are only stream nodes get no application plots, and a stream's nodes never count in the application plots. |

Each tutorial folder has its own `MainApp` (sweep loop over device counts / scenarios /
orchestrator policies / [task-partition policies] / [UAV mobility options]) and its own
`SampleScenarioFactory` wiring — **copy the nearest existing tutorial** when starting a
new experiment rather than editing core classes in place.

## 5. Config files reference

Each tutorial's `scripts/tutorialN/config/` has three files (four from tutorial18 on: see `streams.xml` below), all parsed by `SimSettings.initialize(...)`:

- **`default_config.properties`** — flat key=value. Comma-separated values become sweep
  axes in `MainApp` (e.g. `orchestrator_policies`, `simulation_scenarios`,
  `task_partition_policies` [tutorial7+], `uav_mobility_options` [tutorial6+],
  `vm_utilization_policies` [tutorial16 - `UTILIZATION_<percent>` entries, see §4],
  `service_placement_policies` [tutorial17 - `SINGLE`/`ALL`, see §6.10; defaults to `ALL`]). New scalar
  knobs (like tutorial8's `sar_*` keys) should default to a harmless value (0/empty) so
  older tutorials that don't set them are unaffected — see `SimSettings` SAR fields for
  the pattern.
- **`applications.xml`** — one `<application name="...">` per task type, parsed into
  `SimSettings.getTaskLookUpTable()` (a `double[appIndex][columnIndex]` table). Columns
  (mandatory): `usage_percentage`, `prob_cloud_selection`, `poisson_interarrival`,
  `delay_sensitivity`, `active_period`, `idle_period`, `data_upload`, `data_download`,
  `task_length`, `required_core`; optional: `vm_utilization_on_edge/cloud/mobile`.
  Optional partitioning tags (tutorial7+): `partitionable`, `partition_count`,
  `partition_strategy` (`EQUAL` is the only value a *flat* partitioned task's split ever
  reads - still unimplemented there, see §7; a *DAG* task's split additionally supports
  `SimSettings.DAG_PARTITION_STRATEGY_LAYER_EQUAL` - see §6.3b). Optional DAG tag
  (tutorial15+): `dag_layers` (comma list, e.g. `1,3,2,1` — number of parallel sub-tasks
  per layer, in dependency order); when present it implies `partitionable=true` and
  overrides `partition_count` with the sum of every layer — see §6.3b. Optional
  `task_timeout` (seconds, per-app — different apps can have different urgency):
  `<=0`/absent (the default) disables hard task-timeout cancellation for that app
  entirely — see §6.9. Optional `service_index` (integer, tutorial17+): the label an
  edge host's `edge_devices.xml` `service_capability` list references to restrict a UAV
  to this app — defaults to the app's own 0-based position in this file (the same value
  as `Task.getTaskType()`/`SimSettings.getTaskServiceIndex()`) when absent — see §6.10.
- **`edge_devices.xml`** — `<datacenter>` → `<host>` → `<VM>` hierarchy consumed by
  `DefaultEdgeServerManager`/`DefaultCloudServerManager` to build CloudSim
  hosts/VMs, plus per-datacenter `Location`/place-type/attractiveness info consumed by
  mobility models. Optional per-`<host>` tag (tutorial17+, UAV scenarios only):
  `service_capability` (comma list of `service_index` values, e.g. `0,2`) restricts that
  host's UAV to running only the listed app(s) — absent/empty (the default) leaves the
  UAV UNRESTRICTED (can run every app), preserving the original behavior for every
  edge_devices.xml that doesn't set it. A scenario that computes capabilities with a
  `ServicePlacementPolicy` (tutorial17) ignores this tag — see §6.10.
- **`streams.xml`** (optional, tutorial18+; passed to the 4-argument `SimSettings.initialize`,
  parsed AFTER `applications.xml` since nodes refer to applications by name) — one
  `<stream name="...">` per stream, with only the fields a stream needs: mandatory
  `usage_percentage`, `poisson_interarrival`, `active_period`, `idle_period`, `dag_layers`
  (`1,2,1`), `dag_services` (the application of each node, per layer: a bare name for a
  one-node layer, a parenthesized list for a wider one - `SERVICE_APP_0, (SERVICE_APP_1,
  SERVICE_APP_2), SERVICE_APP_3`; the same grammar as `dag_partition_weights`); optional
  `task_timeout` (seconds, soft, for the whole stream). A stream's name must be unique across
  streams AND applications (it names the `<name>_GENERIC.log` file). Parse errors (unknown
  application, layer/node count mismatch, ...) terminate the simulation with a message.
  See §6.11.

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
6. If the policy averages/partitions user positions, skip devices where
   `MobilityModel.isActive(deviceId, time)` is false (see §6.5) so a staged
   subpopulation can't be mistaken for real, present users.
7. `scripts/tutorial8/python/plotGenericLine.py`'s `plot_generic_line` saves figures as
   `<metric_name>_<app_type>.pdf` (pass a short PascalCase `metric_name`, e.g.
   `'FailedTask'`) rather than `<row_offset>_<column_offset>_<app_type>.pdf` — always pass
   `metric_name` from new caller scripts so filenames stay descriptive.
8. **Encoding a numeric parameter in the policy name itself** (rather than a single global
   scalar config) is how tutorial9 sweeps several SAR priority factors in one run: policy
   names matching `BasicUAVMobility.isVoronoiPolicy(name)` (`"VORONOI"` or
   `"VORONOI_<factor>"`, e.g. `"VORONOI_2"`) all route to the *same* VORONOI branch in
   `processMoveEvent` (the factor never affects the algorithm itself, only
   `MobilityModel.getPriority(...)`'s weighting - see §6.5); `SampleScenarioFactory.
   getMobilityModel()` calls `BasicUAVMobility.parseExplicitPriorityFactor(name)` to
   extract the override (`null` for bare `"VORONOI"`, which then falls back to the
   configured `sar_priority_factor`). Copy this pattern (parse-suffix-from-policy-name,
   fall back to a scalar config default) for any new UAV policy that needs a per-sweep-
   entry numeric knob without a new Java class per value.

### 6.3 New task-partitioning policy/strategy
Current state (important gotcha): `task_partition_policies` in `.properties` is mostly a
**binary NO / not-NO switch** — `SimSettings.isTaskPartitionable(taskType)` returns
`false` for policy `"NO"`, `true` if a partition-count override is active (see
`setPartitionCountOverride`/tutorial14's `PARTITION_<N>` policies, which force
partitioning regardless of `applications.xml`), and otherwise just returns the per-app
`partitionable` flag from `applications.xml`. `partition_strategy`
is parsed and stored (`SimSettings.getTaskPartitionStrategy`), and read via a per-run
override (`setTaskPartitionStrategyOverride`/tutorial15's `FULL_LAYERED` policy) for the
*DAG* split path (`DefaultMobileDeviceManager.splitTaskPropertyIntoDagLayers` - see
§6.3b) — but the *flat* split path (`splitTaskProperty`) still always does an equal
split regardless of `partition_strategy`. To add a *real* new flat-split strategy:
1. Add the new strategy name as a case somewhere `DefaultMobileDeviceManager.
   splitTaskProperty(...)` builds `lengthParts`/`uploadParts`/`downloadParts` — branch on
   `SimSettings.getInstance().getTaskPartitionStrategy(taskType)` instead of always
   calling the equal-split `splitValue(...)`.
2. If the policy should affect *how many* children (not just how work is divided),
   extend `SimSettings.getTaskPartitionPolicy()`/`getTaskPartitionCount` to branch on the
   current policy string further (see tutorial14's `PARTITION_<N>` naming convention).
3. If sibling sub-tasks need coordinated placement, that's already supported via
   `EdgeOrchestrator.getVmsToOffload(List<Task>, deviceId)` — override it (see
   `UAVEdgeOrchestrator`) rather than re-deriving batch-aware placement.
4. Register the new policy name in `task_partition_policies=...` in the tutorial's
   `default_config.properties` (only meaningful from tutorial7 onward, where `MainApp`
   sweeps this axis and calls `SS.setTaskPartitionPolicy(...)`).

### 6.3b New DAG-structured partitioned task (tutorial15 pattern)
A DAG task is a partitioned task whose children are grouped into dependent **layers**
instead of one flat, mutually independent batch: layer L only starts once every sub-task
in layer L-1 has completed; sub-tasks within the same layer run in parallel (no
dependencies from 2+ layers back - a "simple" DAG).
1. **Data/config**: add `<dag_layers>L0,L1,...</dag_layers>` to the app's
   `applications.xml` block (e.g. `1,2,1`) — `SimSettings.parseApplicationsXML` parses
   it into `taskDagLayers[i]` (per-layer sub-task counts), forces `taskPartitionable[i] =
   true`, and overrides `taskPartitionCount[i]` with the sum across every layer. Read back
   via `SimSettings.isTaskDag(taskType)`/`getTaskDagLayers(taskType)`. Do not also set
   `partitionable`/`partition_count` on a DAG app — `dag_layers` takes precedence.
2. **Runtime**: `DefaultMobileDeviceManager.submitTask` checks
   `SimSettings.isTaskDag(taskType)` (only reachable when `isPartitionable()` is also
   true) and routes to `submitDagTask` instead of `submitPartitionableTask`.
   `submitDagTask` splits the task's total length/upload/download EQUALLY across every
   sub-task in every layer (`splitTaskPropertyIntoDagLayers` — same EQUAL-split
   convention as the flat path's `splitTaskProperty`), then submits only the FIRST
   layer via the shared `submitChildTaskBatch` helper (VM selection + upload start, also
   used by the flat path). `PartitionState` gained `isDag`/`totalDagChildCount`/
   `remainingLayers` fields; every sibling in the running layer still completes through
   the ordinary per-child upload → execute → download → `RESPONSE_RECEIVED_BY_MOBILE_
   DEVICE` lifecycle, but that event handler now checks
   `partitionState.remainingLayers` once the running layer's `completedChildren` reaches
   `totalChildren` — if non-empty, it pops and submits the next layer (via
   `buildChildTasks` + `submitChildTaskBatch` again); only once every layer is exhausted
   does it finalize the parent task (`taskEnded`) exactly like the flat path. A failure in
   any sub-task (VM capacity/bandwidth/mobility) fails the whole DAG immediately
   (`failPartition`), same semantics as flat partitioning — there is no partial-DAG
   success.
3. `task.getChildCount()` (read by `CpuUtilizationModel_Custom` to divide
   `vm_utilization_on_*` per sub-task) is set to the DAG's TOTAL sub-task count across
   every layer for every child, regardless of which layer it's in — so e.g. `dag_layers=
   1,2,1` (4 total) divides the raw utilization by 4 for every sub-task, exactly as a
   flat 4-way partition would.
4. `task_partition_policies=NO` still disables partitioning entirely for a DAG app too
   (`isTaskPartitionable` returns `false` before `isTaskDag` is ever consulted) — the task
   runs as one big non-partitioned Cloudlet, same NO/not-NO switch as flat partitioning
   (see §6.3's gotcha).
5. **Two interchangeable split strategies** control HOW `splitTaskPropertyIntoDagLayers`
   divides the task's total length/upload/download, selected by
   `SimSettings.getTaskPartitionStrategy(taskType)` (applications.xml's own
   `partition_strategy` value, or a per-run override — see below):
   - `"EQUAL"` (the default/only value anything sets in `applications.xml`): splits the
     total equally across every sub-task in the WHOLE DAG regardless of layer — a
     sub-task in a 1-wide layer gets the same share as one in a 3-wide layer.
   - `SimSettings.DAG_PARTITION_STRATEGY_LAYER_EQUAL` (`"LAYER_EQUAL"`): splits the total
     equally across LAYERS first, then splits each layer's own share equally again
     across that layer's sub-tasks (`splitTaskPropertyIntoDagLayersByLayerThenSubtask`) —
     a sub-task in a narrower layer gets a bigger individual share than one in a wider
     layer, since every layer (not every sub-task) gets an equal cut of the total.
   `tutorial15` demonstrates sweeping between the two WITHOUT editing `applications.xml`
   per run, via a `setTaskPartitionStrategyOverride(String)`/`currentTaskPartitionStrategyOverride`
   mechanism on `SimSettings` (same override-then-fallback-to-XML convention as
   `setPartitionCountOverride`): `MainApp`'s `FULL_LAYERED` policy entry calls
   `SS.setTaskPartitionStrategyOverride(SimSettings.DAG_PARTITION_STRATEGY_LAYER_EQUAL)`
   (every other policy clears it with `null`, falling back to the app's own `EQUAL`). Copy
   this override pattern for any further DAG split strategy that should be sweepable
   per-run rather than baked into one fixed `applications.xml` value.

### 6.3c DAG inter-layer (UAV relay) delay (tutorial15)
Simulates the network cost of relaying a finished DAG layer's output data to wherever
the next layer executes, so layer transitions aren't instantaneous.
1. **Config**: `uav_bandwidth=<Mbps>` in `default_config.properties` (same Mbps-in/
   Kbps-stored convention as `wlan_bandwidth`/`wan_bandwidth`/`gsm_bandwidth`) — parsed
   into `SimSettings.BANDWITH_UAV`, read via `getUavBandwidth()`. `<=0` (including the
   default when the key is absent) disables the delay entirely (treated as 0/
   instantaneous).
2. **Formula**: `NetworkModel.getInterLayerDelay(dataSizeKB, bandwidthKbps)` is a
   concrete (non-abstract) method on the base class — deliberately simple
   (`dataSizeKB * 8 / bandwidthKbps`, no queueing) and isolated in one place so it can be
   swapped for a more realistic model later without touching call sites.
3. **Runtime**: only the output that actually has to change UAV is relayed. `PartitionState`
   gained `currentLayerOutputKbByHost` (UAV host id → KB), which accumulates every
   sub-task's `getCloudletOutputSize()` under the UAV it ran on (`getAssociatedHostId()`)
   as the running layer's children complete (cleared in `submitChildTaskBatch`, i.e. at the
   start of each layer). Once a layer finishes, the handler builds the next layer's
   children and **selects their UAVs/VMs right away** (`getVmsToOffload`; the choice is
   carried to `submitChildTaskBatch` via `PendingLayerSubmission.selectedVMs`), then
   `getInterLayerRelayDelay` works out the delay: for every distinct destination UAV the
   next layer runs on, the layer's output produced on the *other* UAVs goes through
   `getInterLayerDelay(remoteKB, getUavBandwidth())`; destinations receive in parallel, so
   the delay is the slowest one, and it is **0 when everything already sits on the
   destination UAV(s)** (e.g. a single-UAV scenario never pays it). A positive delay is
   logged (`SimLogger.addInterLayerDelay`); either way a `SUBMIT_NEXT_DAG_LAYER` event is
   scheduled after that delay (rather than submitting synchronously) — the handler
   re-checks `partitionStateMap`/`partitionState.failed` before submitting, so a failure
   during the relay delay still correctly discards the remaining layers.
4. **Logging**: new `NETWORK_DELAY_TYPES.UAV_DELAY`, accumulated per-task in
   `LogItem.uavDelay` (via `addUavDelay`, `+=` not overwrite, since a DAG can have
   multiple layer transitions) and folded into `LogItem.getNetworkDelay()`'s total — so it
   counts toward "overall network delay" automatically. Separately plottable via
   `SimLogger`'s `uavDelay`/`uavUsage` arrays: averaged into the console summary line
   ("UAV delay: ...") and appended as a TRAILING field on `genericResult5`'s CSV line
   (existing column positions for other tutorials are untouched).

### 6.3d Explicit per-node DAG partition weights (`dag_partition_weights`)
Lets a DAG app give each node of each layer an explicit fraction of the task's total
length/upload/download, instead of an even EQUAL/LAYER_EQUAL split (§6.3b).
1. **Config**: `<dag_partition_weights>` in `applications.xml`, e.g. for a
   `dag_layers=1,2,1` app: `0.1, (0.4, 0.4), 0.1` — a layer with exactly one node is a
   bare number; a layer with 2+ nodes MUST be parenthesized with exactly that many
   comma-separated values, in the same per-layer order as `dag_layers`. Requires
   `dag_layers` to also be set (error otherwise). Every value across the WHOLE dag must
   sum to 1.0 (checked at parse time, small epsilon tolerance) and every individual
   value must be positive — `SimSettings.parseDagPartitionWeights()` throws
   `IllegalArgumentException` (caught by `parseApplicationsXML`'s try/catch, which
   terminates the simulation with a clear message) on any mismatch: wrong layer count,
   wrong per-layer node count, non-positive value, or sum != 1.0. Optional — omitting the
   tag leaves an app's existing `partition_strategy` (EQUAL/LAYER_EQUAL) behavior
   unchanged.
2. **Precedence (strategy-gated, not automatic)**: the weights are only CONSULTED when
   `SimSettings.getTaskPartitionStrategy(taskType)` (the app's own `partition_strategy`,
   or a per-run override) resolves to `SimSettings.DAG_PARTITION_STRATEGY_WEIGHTED`
   (`"WEIGHTED"`) — `DefaultMobileDeviceManager.splitTaskPropertyIntoDagLayers` checks the
   strategy string first, dispatching to `splitTaskPropertyIntoDagLayersByWeight` only in
   that case (throwing `IllegalStateException` if that strategy is selected but the app
   defines no `dag_partition_weights`). An app can therefore define
   `dag_partition_weights` WITHOUT changing its existing `EQUAL`/`LAYER_EQUAL` runs —
   tutorial15 demonstrates this via a third `"FULL_WEIGHTED"` policy (alongside `"FULL"`/
   `"FULL_LAYERED"`) that overrides the strategy to `DAG_PARTITION_STRATEGY_WEIGHTED` for
   that one sweep entry only — see `MainApp.FULL_WEIGHTED_POLICY` /
   `setTaskPartitionStrategyOverride()`; `"FULL"`/`"FULL_LAYERED"` are completely
   unaffected by `DAG_WORKFLOW` also defining `dag_partition_weights`.
3. **Split mechanism**: `splitTaskPropertyIntoDagLayersByWeight` flattens the per-layer
   weights (in dependency order) into one list and splits the task's total length/
   upload/download in a single shot via `splitValueByWeights` — each part is
   `floor(total * weight)`, then any leftover units lost to flooring are handed out one
   at a time to the parts with the largest fractional remainder first (largest-remainder
   / Hare-Niemeyer apportionment), exactly conserving the original total (same
   exact-total guarantee as the EQUAL/LAYER_EQUAL strategies' `splitValue`).
4. Smoke-tested: valid weights (`0.1, (0.4, 0.4), 0.1`) produce exactly proportional
   per-node lengths (verified via temporary debug instrumentation, e.g. task total 10203
   -> parts `[1020, 5102, 3061, 1020]`, summing back to 10203 exactly); an invalid sum
   (1.1) and a wrong per-layer node count both cleanly terminate the simulation with a
   descriptive `IllegalArgumentException` instead of a silent miscalculation or crash.
   tutorial15's `DAG_WORKFLOW` now sets `dag_partition_weights` live (`0.1, (0.4, 0.4),
   0.1`) and sweeps `task_partition_policies=NO,FULL,FULL_LAYERED,FULL_WEIGHTED,
   FULL_(0.1,(0.2,0.6),0.1),PARTITION_4` — confirmed `FULL`/`FULL_LAYERED` are numerically
   unaffected (only statistical run-to-run noise from independent sampling) and
   `FULL_WEIGHTED`/`FULL_(...)` both run end-to-end with no exceptions.
5. **Policy-name-encoded weights override** (`"FULL_(<weights>)"`, e.g.
   `"FULL_(0.1,(0.2,0.6),0.1)"`): same "encode a parameter in the policy name"
   convention as `PARTITION_<N>`/tutorial9's `VORONOI_<factor>` (§6.2 point 8) — lets a
   sweep entry supply its OWN weight values directly, instead of only ever reading the
   app's applications.xml `dag_partition_weights`. `SimSettings.
   setTaskPartitionWeightsOverride(String)` stores the raw weights text (same syntax as
   the XML tag) and EAGERLY validates it (right when the override is set, against every
   currently DAG-enabled task type) — this is deliberately NOT lazy, so a malformed
   override fails immediately at sweep-entry setup with a clear `IllegalArgumentException`,
   rather than only once a DAG task happens to be submitted deep into a run.
   `DefaultMobileDeviceManager.splitTaskPropertyIntoDagLayers`'s `WEIGHTED` branch checks
   `SimSettings.isTaskPartitionWeightsOverrideActive()` FIRST — if active, uses
   `getTaskPartitionWeightsOverride(taskType)` instead of the app's own
   `getTaskDagPartitionWeights(taskType)`. Because `task_partition_policies` itself is a
   comma-separated list, and a weights spec contains its own commas/parens,
   `SimSettings` now splits that property with a paren-aware top-level tokenizer
   (`splitTopLevelCommaList()`, shared with `parseDagPartitionWeights()`) instead of a
   naive `.split(",")`, so an entry like `"FULL_(0.1,(0.2,0.6),0.1)"` survives as ONE
   policy string. tutorial15's `MainApp` detects this via a `"FULL_("`-prefix/`")`-suffix
   check (`FULL_WEIGHTED_PREFIX`), extracts the inner text, and calls both
   `setTaskPartitionStrategyOverride(DAG_PARTITION_STRATEGY_WEIGHTED)` and
   `setTaskPartitionWeightsOverride(innerText)` for that one sweep entry — every other
   policy clears both overrides. Smoke-tested: the override's weights
   (`[0.1, 0.2, 0.6, 0.1]`) were confirmed (via temporary debug instrumentation) to be
   used instead of the app's own XML weights (`[0.1, 0.4, 0.4, 0.1]`, used unchanged by
   `"FULL_WEIGHTED"` in the same run); a bad-sum and a wrong-node-count override both
   correctly aborted immediately with a descriptive error.

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

### 6.9 Soft task-timeout (feature/timeout)
Any task — plain or partitioned/DAG — still in flight (uploading, processing on a VM, or
downloading its result) `task_timeout` seconds after its own creation gets ONE
`SimLogger.TASK_STATUS.TIMED_OUT` outcome logged (counted in `failedTaskDuetoTimeout` and
printed in the "Mobility/WLAN Range/Network/**Timeout**" console summary line) — but the
task itself is **not** cancelled or stopped in any way: its upload/processing/download
keeps running to completion exactly as if the deadline had never been reached, consuming
network/VM resources normally throughout. This models a realistic timeout (a client gives
up waiting and reports an error, but the backend has no way to know that and keeps
computing/transmitting the already-in-flight work) rather than an instant kill switch.
This is a **core-engine, opt-in, PER-APPLICATION** feature — `task_timeout` is an optional
`applications.xml` tag (like `max_delay_requirement`), parsed into `SimSettings`'s
dedicated `taskTimeout[]` array (NOT the generic `taskLookUpTable` columns, since it has
its own `isTaskTimeoutEnabled(taskType)` helper, same convention as the partitioning
fields). Defaults to `0` (disabled) for any app that doesn't set it, so different apps in
the same `applications.xml` can have different urgency/deadlines, and no existing
tutorial's behavior changes unless its apps explicitly set `task_timeout > 0`.
- **Mechanism** (`DefaultMobileDeviceManager`): `armTimeout(topLevelTaskId, taskType)`
  schedules a `TASK_TIMEOUT` event `SimSettings.getTaskTimeout(taskType)` seconds after a
  task's creation (called once per plain task in `submitTask` with `task.getTaskType()`,
  or once per partitioned/DAG task in `submitPartitionableTask`/`submitDagTask` with
  `edgeTask.getTaskType()` — `topLevelTaskId` is a plain task's own cloudlet id, or a
  partitioned task's `parentTaskId`), recording the top-level id in `pendingTimeouts`
  (map to a VM-tier hint used only for logging, updated via `updateTimeoutVmTypeHint`
  as the task moves from uploading-to-cloud/edge to actually being bound to a VM —
  nothing is tracked per-child, since nothing needs to be undone). When `TASK_TIMEOUT`
  fires (`processTaskTimeout`): if the top-level id is no longer in `pendingTimeouts`
  (already resolved naturally before the deadline), it's a no-op; otherwise exactly ONE
  `TIMED_OUT` outcome is logged for the top-level task — via a `TIMED_OUT` branch in the
  existing `failPartition(...)` for a partitioned/DAG task (same "exactly one log entry
  for the parent" convention as every other partition failure reason — its children's
  `PartitionState.failed` flag, already set by `failPartition`, is reused as-is to
  silently no-op their own later pipeline-advancement/parent-finalization **without**
  skipping their network bookkeeping calls, which remain unconditional for exactly this
  reason), or directly via `SimLogger.timedOut(...)` for a plain task (whose own id is
  added to `timedOutTaskIds` so its later natural terminal event skips re-logging it,
  while every other call in that event — `uploadFinished`/`downloadFinished`/VM
  binding/`taskExecuted` — still runs exactly as normal). A DAG/partitioned task has only
  ONE `task_timeout` (read once from its `edgeTask.getTaskType()` at the top-level
  submission) applied to the WHOLE job, not per-child/per-layer.
- Adding a NEW terminal outcome (success or any failure reason) for a task type always
  requires remembering to resolve its timeout tracking too (`resolveTimeout(topLevelId)`
  for a plain task, or let `failPartition(...)` do it for a partitioned one) — otherwise
  a `pendingTimeouts` entry leaks for the rest of the run and could log a stale/incorrect
  TIMED_OUT outcome for a task id that has already resolved through a different path.

### 6.10 Per-UAV service-capability restriction & service placement policies (tutorial17 pattern)
Restricts a UAV to running only a declared subset of applications, instead of every UAV
being able to run every app (the pre-existing, still-default behavior). The subset is
either declared per host in `edge_devices.xml` (step 1) or computed by a service
placement policy (step 5, what tutorial17 uses).
1. **Config**: `applications.xml`'s optional `<service_index>` (integer) labels an app
   for this purpose - defaults to that app's own 0-based position in the file (the same
   value as `Task.getTaskType()`) when absent, so existing `applications.xml` files need
   no change. `edge_devices.xml`'s optional `<service_capability>` tag on any `<host>` is
   a comma list of these `service_index` values (e.g. `0,2`) - the host's UAV may ONLY
   run app(s) in that list. A host that doesn't set the tag remains UNRESTRICTED (runs
   every app), so every pre-tutorial17 `edge_devices.xml` is unaffected.
2. **Parsing** (`SimSettings`): `taskServiceIndex[]` (parsed in `parseApplicationsXML`,
   exposed via `getTaskServiceIndex(taskType)`) and `edgeHostServiceCapability` (a
   `List<int[]>` parsed in `parseEdgeDevicesXML`, exposed via
   `getHostServiceCapability(hostId)`) - the list's index `hostId` relies on
   `parseEdgeDevicesXML` traversing `<datacenter>` → `<host>` in the exact same document
   order, incrementing one global counter, as every `EdgeServerManager` implementation's
   own `hostIdCounter` when it later constructs the matching `UAV` object - so index i
   here always corresponds to the host actually assigned id i. A null/missing entry
   means unrestricted.
3. **Enforcement**: `UAV`'s original constructor reads its own capability list once via
   `SimSettings.getHostServiceCapability(id)` (every tutorial's `SampleEdgeServerManager`
   already passes its own `hostIdCounter` as the UAV's `id`); a second constructor takes
   the list explicitly (step 5). `UAV.canServeTaskType(serviceIndex)` returns `true`
   unconditionally when unrestricted, else checks membership. `UAVEdgeOrchestrator.getUAVToOffloadTo`
   skips any UAV for which `canServeTaskType(SimSettings.getTaskServiceIndex(task.
   getTaskType()))` is `false`, alongside its existing range/capacity checks - a task
   with NO capable, in-range, non-full UAV is rejected exactly like any other "no
   suitable VM" case (`REJECTED_DUE_TO_VM_CAPACITY`, no new failure reason needed).
4. **Not mobility-aware**: no `BasicUAVMobility`/`CentralizedUAVMobility` policy knows
   about a UAV's service-capability restriction - they still move every UAV based purely
   on device positions/priorities, oblivious to which app a nearby device is about to
   request. A capability-restricted UAV can therefore still end up far from the one
   device that needed it; see `tutorial17` for a scenario deliberately built to surface
   this (see §4).
5. **Service placement policy** (tutorial17): instead of hard-coding `service_capability`
   per host, `default_config.properties`'s `service_placement_policies` (comma list,
   parsed into `SimSettings.getServicePlacementPolicies()`, defaults to `ALL`) names the
   policies to compare. `edge_server/uav/ServicePlacementPolicy` turns a policy name into
   `int[][] place(numOfUAVs)` (per UAV id, the `service_index` values it hosts; supported:
   `SINGLE`, `ALL`, see §3). tutorial17's `SampleEdgeServerManager` is built with the
   policy, calls `place(SimSettings.getNumOfEdgeHosts())` in `startDatacenters()`, logs the
   resulting placement, and passes each UAV its own entry through the explicit-capability
   `UAV` constructor (so `edge_devices.xml` carries no `service_capability` tag there).
   `MainApp` validates every listed policy name up front and sweeps the policy as its
   innermost loop; the policy name is part of the `SIMRESULT_*` file name
   (`..._<MOBILITY>_<PLACEMENT>_<N>DEVICES_...`). To add a policy: add a name to
   `ServicePlacementPolicy.SUPPORTED_POLICIES` and a `case` in `place()`. Later
   per-service attributes (priority, size, popularity) should be parsed from
   `applications.xml` into `SimSettings` (next to `taskServiceIndex`) and read there by the
   new policy cases.

### 6.11 Streams: DAGs whose nodes are applications/services (tutorial18 pattern)
A *stream* is a request type next to the plain applications: a DAG like `dag_layers` (§6.3b),
but each node is an **application** (a service) instead of a sub-task. A scenario can mix
plain applications (themselves possibly DAGs of sub-tasks) and streams freely; an application
may be requested by itself (devices assigned to it) and/or be a node of a stream.
1. **Config** (`streams.xml`, see §5): per stream `usage_percentage`, `poisson_interarrival`,
   `active_period`, `idle_period`, optional `task_timeout`, `dag_layers`, `dag_services`.
   `SimSettings.parseStreamsXML` validates them and exposes `getNumOfStreams()`,
   `getStream*(streamIndex)`, `getStreamNodeApplications(streamIndex)` (`int[layer][node]` =
   applications.xml index). Streams are **task types numbered right after the applications**:
   `getStreamTaskType(i) = numApplications + i`; `getNumOfTaskTypes()` (apps + streams) is the
   size for any per-type statistics, `getTaskName`/`isTaskTypeUsed` accept stream task types.
   `getTaskLookUpTable()` is still applications only, so nothing indexed by app type changes.
2. **Which request a device makes**: `IdleActiveStreamLoadGenerator` draws ONE type per device
   from the applications' and streams' `usage_percentage` together (must total 100) and then
   uses THAT type's own timing. An application that is only a stream node has
   `usage_percentage` 0 (its other tags are required by the parser but never used).
   **An application's `usage_percentage`/`poisson_interarrival`/`active_period`/`idle_period`/
   `task_timeout` never affect a stream it is a node of** - only its workload (length, data
   sizes, utilization, partitioning, ...) does.
3. **Execution** (`DefaultMobileDeviceManager.submitStream`): the stream request is logged as
   ONE task of the stream's task type. Layers are *released* one at a time
   (`releaseStreamLayer`), exactly like a DAG application's sub-task layers (§6.3b/§6.3c): every
   node's task(s) are built (a plain `Task`, or a partitioned node's first sibling batch via
   `preparePartitionedTask`) WITHOUT registering anything, the orchestrator chooses the UAVs of
   the whole layer in ONE `getVmsToOffload` call (`UAVEdgeOrchestrator`/`UAV.canServeTaskType`,
   §6.10), the relay delay is worked out, and only after it does `startStreamLayer` register and
   start the nodes on those UAVs (`submitPlainTaskToVm`, or `startPartitionedTask` with the
   preselected VMs - so a node that is itself a DAG/flat partition runs as exactly that). A
   node with no UAV (null) is rejected (`REJECTED_DUE_TO_VM_CAPACITY` - "dropped"), and **any
   failing node fails the whole stream** (no partial success, like a DAG's sub-task), with the
   node's own failure reason. Because UAVs are chosen when the layer is released, a plain
   stream node is placed BEFORE it uploads (a standalone plain request is placed after) and a
   device outside every capable UAV's range is "no UAV", not a bandwidth rejection.
4. **Inter-layer relay delay** (identical to §6.3c): a completed node reports its output (KB per
   UAV: a plain node's `getCloudletOutputSize()` on its UAV, a partitioned node's last layer's
   `currentLayerOutputKbByHost`) into `StreamState.layerOutputKbByHost` via
   `recordStreamNodeOutput`. When the layer is done, `getInterLayerRelayDelay` - the very method
   the sub-task DAG uses - takes, per destination UAV of the next layer, the output produced
   elsewhere divided by `uav_bandwidth`; the slowest destination decides, 0 when everything
   already sits on the destination UAVs (and for the first layer). A positive delay is logged on
   the stream (`SimLogger.addInterLayerDelay`) as UAV delay, folded into its network delay, and
   the layer starts after it (`SUBMIT_NEXT_STREAM_LAYER`, which carries the prepared nodes).
5. **Logging** (`SimLogger.addStreamNodeLog`): a node's `LogItem` is flagged with its stream id
   and, once it ends, is NOT added to the statistics - its network delays (WLAN up/down, UAV
   relay of a nested DAG) are added to the stream's entry, a failure becomes the stream's
   outcome, and the registered `StreamNodeListener` (the manager) is told. A node still running
   when the sim stops is not counted as uncompleted (its stream is). Per-stream statistics get
   their own `<STREAM_NAME>_GENERIC.log` (`numOfAppTypes` in `SimLogger` is now apps + streams).
   Every per-type statistics array has 3 extra aggregate slots after the task types
   (`numOfStatSlots`): all requests (console summary only - no file), the standalone
   applications only (`ALL_APPS_GENERIC.log` - identical to the old all-requests total in any
   scenario without streams) and the streams only (`ALL_STREAMS_GENERIC.log`, written only when
   streams exist); `getGenericFileLabel(slot)` decides which slots get a file.
6. **Timeout**: the stream's own `task_timeout` is armed (`pendingTimeouts`). A timeout never
   cuts a running node short, and unlike a plain task's soft timeout (§6.9) the stream is NOT
   logged at the timeout instant: if nodes of the running layer are still executing when it
   fires (`StreamState.timedOut`, `SimLogger.markStreamTimeoutPending` so a failing node can no
   longer become the stream's outcome), those nodes - including a node that is itself a whole
   sub-task DAG - run to their end, no further layer starts, and the stream is logged
   `TIMED_OUT` when the last of them has ended, whether it succeeded or failed. If no node is
   running at that moment (the stream is between layers, e.g. during a relay delay) it is logged
   `TIMED_OUT` immediately. Nodes never arm the application's `task_timeout`.
7. **Not modelled (extension points)**: a stream node is requested from the device's location
   at the moment its layer is released (not after the relay delay, as for a DAG's sub-tasks).
8. **New scenario**: copy `applications/tutorial18` (`MainApp` takes `streams.xml` as an extra
   argument and calls the 4-argument `initialize`; the factory returns
   `IdleActiveStreamLoadGenerator`) and `scripts/tutorial18`.

## 7. Known gotchas / pre-existing quirks (verified in code)

- **Stream node placement is only as good as the service availability around the device.**
  With `SINGLE` placement a stream needing several different services at once almost never
  finds all of them within `UAV.SERVICE_RADIUS` of the device - expect near-0% completion for
  wide streams (tutorial18's `SURVEILLANCE_STREAM` needs all 4 services); that is the model
  working, not a bug. Stream statistics are per *request*, so a stream failing early is one
  failed request however many nodes it had.
- **`usage_percentage` of applications + streams must total 100** when a scenario uses
  `IdleActiveStreamLoadGenerator` (it exits otherwise, naming the sum) - a stream-only
  application therefore needs `usage_percentage` 0, not a share of the 100.
- **`partition_strategy` is still parsed-but-unused for FLAT partitioned tasks.**
  `DefaultMobileDeviceManager.splitTaskProperty` always splits length/upload/download
  equally among children, regardless of the `partition_strategy` XML value - `EQUAL` is
  the only value any flat (non-DAG) app's `applications.xml` sets, and it's not even
  checked there. A *DAG* task's split (`splitTaskPropertyIntoDagLayers`) DOES read
  `partition_strategy` (plus a per-run override, see §6.3b) - don't assume this gotcha
  still applies to `dag_layers` apps.
- **`task_partition_policies` is a NO/not-NO toggle, not a real policy enum.** Any policy
  name other than `"NO"` currently behaves identically (whatever `applications.xml`'s
  per-app `partitionable` says). See §6.3.
- **A DAG task's two split strategies only change per-sub-task SIZE, never the layer
  structure/dependencies themselves** - `"EQUAL"` and `DAG_PARTITION_STRATEGY_LAYER_EQUAL`
  (tutorial15's `FULL_LAYERED`) both still submit the exact same `dag_layers` dependency
  waves; only how much length/upload/download each sub-task carries differs. See §6.3b.
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
- **How a VM's queue is bounded (verified in `CloudletSchedulerSpaceShared` +
  `UAVEdgeOrchestrator`).** A task queues only when every PE of its VM is busy
  (`currentCpus - usedPes < numberOfPes`) - `vm_utilization_on_edge` plays no part in
  that and never changes execution speed. It only feeds the orchestrators' admission
  check `predictUtilization <= 100 - getTotalUtilizationOfCpu(now)`, and
  `getTotalUtilizationOfCpu` sums the EXEC list only, so `BasicEdgeOrchestrator` and the
  sample orchestrators never see the waiting list and place no bound on queue depth.
  `UAVEdgeOrchestrator` (tutorial6-16) also adds the waiting list and the load already
  reserved in the same batch, so on a 1-core UAV VM at most `floor(100 / u)` tasks (1
  running + the rest queued) are in the system (u=30 -> 3, u=100 -> 1, u>100 -> none);
  anything else is `REJECTED_DUE_TO_VM_CAPACITY`. With Poisson arrivals and exponential
  lengths that is an M/M/1/K queue with K=floor(100/u) - tutorial16 reproduces the
  textbook blocking `rho^K(1-rho)/(1-rho^(K+1))` and mean wait to within a few percent.
  There is no queue-delay metric: the wait is folded into the processing time
  (`serviceTime - networkDelay`). `Cloudlet.getWaitingTime()` exists but nothing logs it.
- **CloudSim drops the finish event of very short tasks (fixed everywhere via
  `core/CloudletSchedulerSpaceShared_Custom`).**
  `Datacenter.processCloudletSubmit` runs `updateCloudletProcessing()` and then schedules
  the finish event of a cloudlet that starts running right away at its raw estimated
  finish time, but `updateCloudletProcessing()` silently skips any update requested less
  than `CloudSim.getMinTimeBetweenEvents()` (0.01 s in every MainApp) after the previous
  one. A task whose service time is below that window (~1% of tasks with exponential
  lengths at 1 s mean service) was never completed and kept occupying the exec list until
  some later submit triggered another update. On a VM that admits one task at a time
  (`vm_utilization_on_edge` > 50 on a 1-core UAV) nothing else is ever admitted, so the VM
  deadlocked for the rest of the run (~98% rejected instead of ~50%); on busier VMs it
  only delayed that task's completion by an arbitrary amount. Every VM creation site
  (`DefaultEdgeServerManager`, `DefaultCloudServerManager`, every tutorial's
  `Sample*ServerManager`, sample_app3/5's own managers) now passes
  `CloudletSchedulerSpaceShared_Custom`, which floors the submit-time finish estimate at
  `minTimeBetweenEvents + 0.001`, so only tasks with a service time below ~11 ms are
  touched, and only by completing a few ms later. **Any new manager that creates a VM
  must use it instead of `CloudletSchedulerSpaceShared`.**
- **Server utilization averages skip the warm-up period.** `SimLogger.simStopped()` only
  averages `VmLoadLogItem` samples taken at or after `warm_up_period` into the GENERIC
  logs' VM-load columns and the console summary, matching the task statistics (a
  warm-up-inclusive average used to understate it, e.g. 39.5 vs 43.8 in a tutorial16
  run). `*_VM_LOAD.log` still contains the whole time series, warm-up included. Results
  generated before this change include the warm-up samples.
- **UAV WLAN delay uses real data (changed).** `UAVNetworkModel` no longer derives the
  delay from M/M/1 on mean task sizes/inter-arrival times and a count of nearby devices.
  Payload, device↔UAV distance and link backlog all come from what really happened (see
  the class table), so network delay now follows each task's own size, and a device that
  walked out of the UAV's radius while its task was queued fails the *download* (failure
  reason 3 / `UNFINISHED_DUE_TO_BANDWIDTH`) - the old model measured that distance from the
  stale submit-time position. Results generated before this change are not comparable.
- **Warm-up now also covers tasks rejected before upload.** `SimLogger.LogItem` used to
  set a task's warm-up flag only in `taskStarted()`, which is never reached when the task
  is rejected earlier (e.g. out of every UAV's service radius ->
  `REJECTED_DUE_TO_BANDWIDTH`), so those tasks counted as failures (start time 0.0) in the
  reported "percentage of failed tasks" even during warm-up. The flag is now initialised
  from the clock when the log entry is created (`taskStarted()` still overrides it), so
  results generated before this fix over-count early failures in UAV tutorials.
- **A plain task that times out no longer crashes the run when its cloudlet later
  returns (fixed; tutorial8-12 apps with `task_timeout` used to die on the first one).**
  `DefaultMobileDeviceManager.processTaskTimeout` logs `TIMED_OUT` through
  `SimLogger.timedOut`, whose `recordLog` removes the task's `LogItem`, while the cloudlet
  keeps running (§6.9). Its later `processCloudletReturn`/`submitTaskToVm` calls
  (`SimLogger.taskExecuted/setDownloadDelay/taskAssigned`) used to hit `taskMap.get(id) ==
  null` -> `NullPointerException`; those three `SimLogger` methods now ignore a task that
  has already been recorded. Any NEW per-task `SimLogger` mutator that can be reached after
  a timeout must be null-tolerant the same way.
- **Tutorial1-5 and the sample apps no longer initialize** (pre-existing):
  `SimSettings.initialize` reads `uav_mobility_options` unconditionally
  (`prop.getProperty(...).split(",")`) but their `default_config*.properties` don't define
  it -> `NullPointerException` at `SimSettings.java:315`. Adding any
  `uav_mobility_options=...` line is enough (those scenarios don't use it).
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
