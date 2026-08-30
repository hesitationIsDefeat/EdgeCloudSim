package edu.boun.edgecloudsim.mobility.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

/**
 * ONAT: Centralized UAV mobility - the opposite design from {@link BasicUAVMobility}
 * (where every UAV independently decides its own next move). Here a single central
 * controller has full visibility of the scene (every UAV's current position, and every
 * mobile device's position/priority via {@link MobilityModel}, including SAR members'
 * weighted priority) and is the ONLY one that ever decides where a UAV goes next. UAVs
 * have no mobility logic of their own - they just carry out whatever alert (target
 * point) the controller most recently sent them; a UAV that receives no alert simply
 * stays where it is (see the NO policy). See {@link #runControlTick} for the supported
 * policies (NO, RANDOM, PRIORITY_KMEANS, APF, CAPACITY_FILL, ADAPTIVE_KMEANS).
 * <p>
 * Modeled as a single recurring controller "tick" per {@code uav_mobility_interval}
 * (instead of one self-scheduled move event per UAV like BasicUAVMobility), since one
 * controller pass already decides every UAV's alert together: this is both a more
 * faithful model of a real central controller AND cheaper (O(1) scheduled events per
 * tick instead of O(numUAVs)), while the per-tick work itself is still the same
 * O(numUAVs) (or O(numUAVs * numDevices) for scene-aware policies) any central
 * controller needs to do regardless of how it is scheduled.
 */
public class CentralizedUAVMobility extends UAVMobilityModel {
    // ONAT: Only used by this entity - distinct from SimSettings.EDGE_SERVER_MOVE (9001),
    // which centralized mobility never schedules (UAVs don't self-schedule their moves).
    private static final int CENTRAL_CONTROL_TICK = 9201;

    // ONAT: PRIORITY_KMEANS tuning - Lloyd's-algorithm iteration cap and early-stop
    // threshold (stop once no cluster center moves more than this many meters).
    private static final int KMEANS_MAX_ITERATIONS = 20;
    private static final double KMEANS_CONVERGENCE_EPSILON = 1.0;

    // ONAT: APF tuning. ATTRACTION/REPULSION_GAIN are alpha/beta from the formula.
    // SOFTENING avoids the inverse-cube singularity when a user or UAV is (near-)
    // colocated with UAV j (a standard technique, e.g. Plummer softening in
    // gravitational N-body simulation): distance is measured as sqrt(||.||^2 +
    // SOFTENING^2) instead of the raw (possibly ~0) Euclidean distance, so force
    // magnitude stays bounded instead of exploding to +Infinity/NaN.
    private static final double APF_ATTRACTION_GAIN = 50_000;
    private static final double APF_REPULSION_GAIN = 20_000;
    private static final double APF_SOFTENING_SQUARED = 10.0 * 10.0;
    // ONAT: Net force below this magnitude is treated as a local-minimum "deadlock"
    // (attraction/repulsion cancel out) rather than a genuine direction to move in -
    // the controller nudges that UAV randomly instead, one tick-scale advantage a
    // decentralized/asynchronous APF swarm can't rely on (see class javadoc).
    private static final double APF_DEADLOCK_FORCE_EPSILON = 1e-6;

    // ONAT: CAPACITY_FILL tuning. Grid discretizes the map into square cells of this size
    // (meters) for demand profiling. LOAD_FACTOR scales the per-tick per-UAV capacity
    // (see runCapacityAwareAllocation - capacity is derived from totalDemand/numOfUavs,
    // NOT a hardcoded absolute number, since a fixed capacity would need re-tuning every
    // time min/max_number_of_mobile_devices changes; a lone fixed value that happens to
    // be far smaller than the actual per-hotspot demand at the swept device count is
    // exactly what let one hotspot silently absorb every UAV). >1 spreads UAVs across
    // more hotspots (each UAV "counts for" more demand, so fewer are needed per cell);
    // <1 concentrates more UAVs per hotspot at the expense of covering fewer of them.
    // DISPERSION_GAIN is deliberately much smaller than APF_REPULSION_GAIN - it only
    // needs to keep multiple UAVs assigned to the same cell from colliding at the exact
    // same target point, not to spread them across the map.
    private static final double CAPACITY_FILL_GRID_CELL_SIZE = 100.0;
    private static final double CAPACITY_FILL_LOAD_FACTOR = 1.0;
    private static final double CAPACITY_FILL_DISPERSION_GAIN = 5_000;

    // ONAT: ADAPTIVE_KMEANS tuning (see runAdaptiveKMeans). "Gathered" is detected by
    // watching the scatter-phase K-Means centers across ticks instead of coupling to any
    // tutorial-specific mobility model internals (e.g. ConvergingMobilityModel's hardcoded
    // meeting-area coordinates) - this keeps the policy usable with any MobilityModel.
    // EPSILON is deliberately loose (well above a single captured device's own bounded
    // random-walk step) so a sparse cluster's residual jitter never blocks detection
    // forever. STABLE_DURATION is a real SIMULATED-TIME window (seconds), not a tick
    // count: a tick-count streak is unreliable since the controller's own tick cadence
    // (centralized_controller_interval) is decoupled from however often the underlying
    // MobilityModel actually emits a new position (e.g. ConvergingMobilityModel only
    // updates every TRAVEL_TIME=3s) - several consecutive controller ticks can trivially
    // see byte-identical positions (0 movement) purely because no new mobility update has
    // landed yet, which would false-trigger "gathered" seconds into the run, long before
    // users actually converge. Requiring a long, UNINTERRUPTED real-time window instead
    // means any genuine in-progress movement (which recurs every real mobility update)
    // resets the streak, no matter how the tick/update cadences happen to align.
    private static final double ADAPTIVE_GATHER_EPSILON = 20.0;
    private static final double ADAPTIVE_GATHER_STABLE_DURATION = 30.0;
    // ONAT: K=numOfUavs K-Means naturally produces several near-duplicate centers per
    // physical meeting area once users stop moving (there are usually far fewer distinct
    // real gathering spots than UAVs) - centers within this distance of each other are
    // merged into one saved "middle point".
    private static final double ADAPTIVE_MIDDLE_POINT_MERGE_DISTANCE = 250.0;

    private final String centralizedMobilityOption;
    private List<UAV> allUavs = List.of();

    // ONAT: ADAPTIVE_KMEANS phase-tracking state - see runAdaptiveKMeans. All null/false
    // until this policy's first control tick; a fresh CentralizedUAVMobility instance is
    // constructed per simulation run (SampleScenarioFactory.getEdgeMobilityModel()), so
    // there's no cross-run leakage to worry about.
    private double[] adaptivePrevCenterX;
    private double[] adaptivePrevCenterY;
    private double adaptiveStableSinceTime = -1; // -1 = not currently in an uninterrupted stable streak
    private List<double[]> adaptiveMiddlePoints; // null until the "gathered" breakpoint fires
    private boolean[] adaptiveActiveAtGather; // isActive(...) snapshot taken at gather time
    private boolean[] adaptiveParked; // per-UAV: true once parked at a middle point (never moves again)
    private boolean adaptiveSarArrived = false;

    public CentralizedUAVMobility(String centralizedMobilityOption) {
        super();
        this.centralizedMobilityOption = centralizedMobilityOption;
    }

    @Override
    public void initialize(EdgeServerManager edgeServerManager) {
        this.edgeServerManager = edgeServerManager;
    }

    @Override
    public void startEntity() {
        this.allUavs = this.edgeServerManager.getDatacenterList().stream()
                .flatMap(datacenter -> datacenter.getHostList().stream())
                .map(host -> (UAV) host)
                .toList();

        scheduleNextControlTick();
    }

    @Override
    public void processEvent(SimEvent event) {
        if (event.getTag() == CENTRAL_CONTROL_TICK) {
            runControlTick();
            scheduleNextControlTick();
        }
    }

    @Override
    protected void processMoveEvent(SimEvent event) {
        // ONAT: Unused under centralized mobility - no per-UAV move events are ever
        // scheduled (see processEvent/runControlTick), so this is never called.
    }

    // ONAT: One controller pass = (a) observing the full scene state (every UAV's
    // position, every mobile device's position/priority) and (b) alerting every UAV
    // with its next target, all within the same simulation tick. Kept as a single
    // in-process pass (rather than one SimEvent per UAV) since there is no
    // communication-latency model yet to justify that extra event overhead - a future
    // policy that needs per-UAV alert delay can schedule those explicitly.
    private void runControlTick() {
        switch (this.centralizedMobilityOption) {
            case "NO" -> {
                // ONAT: The controller sends no alerts this tick - every UAV stays put.
            }
            case "RANDOM" -> {
                for (UAV uav : allUavs) {
                    Location current = uav.getLocation();
                    int deltaMagnitude = RNG.nextInt((int) maxMoveDistance(uav)) + 1;
                    int deltaSign = RNG.nextBoolean() ? 1 : -1;
                    int delta = deltaMagnitude * deltaSign;

                    double targetX = current.getXPos();
                    double targetY = current.getYPos();
                    if (RNG.nextBoolean()) {
                        targetX += delta;
                    } else {
                        targetY += delta;
                    }
                    alertUav(uav, targetX, targetY);
                }
            }
            case "PRIORITY_KMEANS" -> runPriorityWeightedKMeans();
            case "APF" -> runCentralizedApf();
            case "CAPACITY_FILL" -> runCapacityAwareAllocation();
            case "ADAPTIVE_KMEANS" -> runAdaptiveKMeans();
            default -> SimLogger.printLine(String.format(
                    "ONAT: Unsupported centralized UAV mobility option: %s", this.centralizedMobilityOption));
        }
    }

    // ONAT: Priority-weighted K-Means (Lloyd's algorithm), K = number of UAVs. Unlike
    // VORONOI (BasicUAVMobility/VoronoiPartitioner - ONE nearest-UAV partition pass using
    // each UAV's actual, slow-moving position as its cell "center"), this repeatedly
    // reassigns devices to the nearest of K virtual cluster centers and re-averages each
    // cluster's priority-weighted centroid ON VIRTUAL CENTERS, several times, before ever
    // moving a single UAV - only a central controller with a full, synchronous snapshot
    // of the scene can afford to do that. Cluster i is seeded at UAV i's current position
    // (so cluster identity maps back to a UAV without solving a separate assignment
    // problem), and each UAV is finally alerted with its own cluster's converged centroid.
    private void runPriorityWeightedKMeans() {
        int numOfUavs = allUavs.size();
        if (numOfUavs == 0) return;

        double now = CloudSim.clock();
        MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();
        int numOfMobileDevice = SimManager.getInstance().getNumOfMobileDevice();

        int activeCount = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (mobilityModel.isActive(deviceId, now)) activeCount++;
        }
        if (activeCount == 0) return; // ONAT: nothing to cluster around - every UAV stays put this tick

        double[] deviceX = new double[activeCount];
        double[] deviceY = new double[activeCount];
        double[] deviceWeight = new double[activeCount];
        int i = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (!mobilityModel.isActive(deviceId, now)) continue;
            Location loc = mobilityModel.getLocation(deviceId, now);
            deviceX[i] = loc.getXPos();
            deviceY[i] = loc.getYPos();
            deviceWeight[i] = mobilityModel.getPriority(deviceId, now);
            i++;
        }

        // ONAT: cluster centers, seeded at each UAV's current position (index i <-> UAV i).
        double[] centerX = new double[numOfUavs];
        double[] centerY = new double[numOfUavs];
        for (int u = 0; u < numOfUavs; u++) {
            Location loc = allUavs.get(u).getLocation();
            centerX[u] = loc.getXPos();
            centerY[u] = loc.getYPos();
        }

        runLloydKMeans(deviceX, deviceY, deviceWeight, centerX, centerY);

        for (int u = 0; u < numOfUavs; u++) {
            alertUav(allUavs.get(u), centerX[u], centerY[u]);
        }
    }

    // ONAT: Shared Lloyd's-algorithm iteration loop (PRIORITY_KMEANS and ADAPTIVE_KMEANS
    // both use this) - mutates centerX/centerY (already seeded by the caller) in place.
    // Passing a uniform weight array (all 1.0) reduces this to plain, unweighted K-Means,
    // which is exactly what ADAPTIVE_KMEANS needs (its spec: SAR priority isn't considered).
    private static void runLloydKMeans(double[] pointX, double[] pointY, double[] pointWeight,
                                        double[] centerX, double[] centerY) {
        int numOfCenters = centerX.length;
        double[] sumX = new double[numOfCenters];
        double[] sumY = new double[numOfCenters];
        double[] sumWeight = new double[numOfCenters];
        for (int iteration = 0; iteration < KMEANS_MAX_ITERATIONS; iteration++) {
            Arrays.fill(sumX, 0);
            Arrays.fill(sumY, 0);
            Arrays.fill(sumWeight, 0);

            for (int p = 0; p < pointX.length; p++) {
                int nearest = nearestCenter(centerX, centerY, pointX[p], pointY[p]);
                sumX[nearest] += pointX[p] * pointWeight[p];
                sumY[nearest] += pointY[p] * pointWeight[p];
                sumWeight[nearest] += pointWeight[p];
            }

            double maxShift = 0;
            for (int c = 0; c < numOfCenters; c++) {
                if (sumWeight[c] <= 0) continue; // ONAT: empty cluster - keep its previous center

                double newX = sumX[c] / sumWeight[c];
                double newY = sumY[c] / sumWeight[c];
                maxShift = Math.max(maxShift, Math.hypot(newX - centerX[c], newY - centerY[c]));
                centerX[c] = newX;
                centerY[c] = newY;
            }

            if (maxShift < KMEANS_CONVERGENCE_EPSILON) break; // ONAT: converged early
        }
    }

    // ONAT: Index of the cluster center nearest to (x, y) - squared distance is enough
    // since we only ever compare, never need the actual magnitude.
    private static int nearestCenter(double[] centerX, double[] centerY, double x, double y) {
        int nearest = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < centerX.length; i++) {
            double dx = centerX[i] - x;
            double dy = centerY[i] - y;
            double distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    // ONAT: Centralized Artificial Potential Fields:
    // F_j = sum_i alpha*w_i*(x_i-U_j)/||x_i-U_j||^3 - sum_{k!=j} beta*(U_k-U_j)/||U_k-U_j||^3
    // i.e. every active user attracts every UAV with inverse-square strength proportional
    // to its priority weight, and every other UAV repels it with inverse-square strength -
    // both terms share the physically-standard inverse-square-law form F = k*r_vector/|r|^3
    // (Newtonian gravity/Coulomb's law), just with opposite sign/gain. Unlike a real
    // decentralized APF swarm (each UAV only sensing/reacting locally, potentially on
    // stale or inconsistent neighbor positions), the controller computes every UAV's net
    // force from ONE consistent global snapshot, in a single pass (no Lloyd-style
    // iteration needed, unlike PRIORITY_KMEANS) - eliminating the coordination deadlocks
    // (e.g. two UAVs "negotiating" via asynchronous updates and oscillating/blocking each
    // other) that a truly decentralized version would be prone to.
    private void runCentralizedApf() {
        int numOfUavs = allUavs.size();
        if (numOfUavs == 0) return;

        double now = CloudSim.clock();
        MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();
        int numOfMobileDevice = SimManager.getInstance().getNumOfMobileDevice();

        int activeCount = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (mobilityModel.isActive(deviceId, now)) activeCount++;
        }

        double[] deviceX = new double[activeCount];
        double[] deviceY = new double[activeCount];
        double[] deviceWeight = new double[activeCount];
        int i = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (!mobilityModel.isActive(deviceId, now)) continue;
            Location loc = mobilityModel.getLocation(deviceId, now);
            deviceX[i] = loc.getXPos();
            deviceY[i] = loc.getYPos();
            deviceWeight[i] = mobilityModel.getPriority(deviceId, now);
            i++;
        }

        double[] uavX = new double[numOfUavs];
        double[] uavY = new double[numOfUavs];
        for (int u = 0; u < numOfUavs; u++) {
            Location loc = allUavs.get(u).getLocation();
            uavX[u] = loc.getXPos();
            uavY[u] = loc.getYPos();
        }

        for (int j = 0; j < numOfUavs; j++) {
            double forceX = 0;
            double forceY = 0;

            for (int d = 0; d < activeCount; d++) {
                double dx = deviceX[d] - uavX[j];
                double dy = deviceY[d] - uavY[j];
                double cubedDistance = cubedSoftenedDistance(dx, dy);
                double scale = APF_ATTRACTION_GAIN * deviceWeight[d] / cubedDistance;
                forceX += scale * dx;
                forceY += scale * dy;
            }

            for (int k = 0; k < numOfUavs; k++) {
                if (k == j) continue;

                double dx = uavX[k] - uavX[j];
                double dy = uavY[k] - uavY[j];
                if (dx == 0 && dy == 0) {
                    // ONAT: exactly co-located with another UAV (e.g. both converged onto the
                    // same attraction basin) - the repulsion DIRECTION is undefined at zero
                    // separation, so scale*dx/scale*dy would silently be zero regardless of how
                    // large "scale" is, permanently locking the pair together with no restoring
                    // force. Substitute a random unit direction so the pair still repels - this
                    // is what actually breaks a UAV pile-up, not just a tuning/gain issue.
                    double angle = RNG.nextDouble() * 2 * Math.PI;
                    dx = Math.cos(angle);
                    dy = Math.sin(angle);
                }
                double cubedDistance = cubedSoftenedDistance(dx, dy);
                double scale = APF_REPULSION_GAIN / cubedDistance;
                forceX -= scale * dx;
                forceY -= scale * dy;
            }

            UAV uav = allUavs.get(j);
            if (Math.hypot(forceX, forceY) < APF_DEADLOCK_FORCE_EPSILON) {
                // ONAT: attraction/repulsion (near-)cancel out - random nudge to escape
                // this local minimum instead of trusting a near-zero, noise-dominated force.
                int deltaMagnitude = RNG.nextInt((int) maxMoveDistance(uav)) + 1;
                int deltaSign = RNG.nextBoolean() ? 1 : -1;
                int delta = deltaMagnitude * deltaSign;

                double targetX = uavX[j] + (RNG.nextBoolean() ? delta : 0);
                double targetY = uavY[j] + (RNG.nextBoolean() ? 0 : delta);
                alertUav(uav, targetX, targetY);
            } else {
                // ONAT: alertUav caps the actual displacement to maxMoveDistance(uav), so
                // only the DIRECTION of (forceX, forceY) matters once its magnitude exceeds
                // that cap - same "move at full speed toward the net force" behavior as the
                // decentralized LOCAL_FORCE policy in BasicUAVMobility.
                alertUav(uav, uavX[j] + forceX, uavY[j] + forceY);
            }
        }
    }

    // ONAT: Capacity-Aware Priority Allocation ("Demand-Fill"): unlike APF/PRIORITY_KMEANS
    // (every UAV chases a force/centroid derived from ALL nearby users, so a single dense
    // crowd can trap far more UAVs than it actually needs - see the APF bug-fix note above),
    // this policy explicitly bounds how much demand a single UAV is credited with covering.
    // The map is discretized into a grid; each cell's demand is the summed priority-weight
    // of the active users inside it. Cells are filled greedily, most-demanding first, each
    // pulling in just enough of the nearest still-unassigned UAVs to exhaust its demand
    // (capped by a per-UAV capacity - see below - per UAV assigned) before moving on to
    // the next cell - so once a hotspot has "enough" coverage, additional UAVs are freed
    // to serve the next-highest-demand cell (e.g. a smaller, more distant SAR cluster)
    // instead of piling up on the biggest crowd. UAVs left unassigned once every cell with
    // demand has enough coverage (or every UAV is spoken for) get no alert this tick, same
    // as NO.
    private void runCapacityAwareAllocation() {
        int numOfUavs = allUavs.size();
        if (numOfUavs == 0) return;

        double now = CloudSim.clock();
        MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();
        int numOfMobileDevice = SimManager.getInstance().getNumOfMobileDevice();

        double westernBound = SimSettings.getInstance().getWesternBound();
        double southernBound = SimSettings.getInstance().getSouthernBound();
        int numCols = (int) Math.ceil((SimSettings.getInstance().getEasternBound() - westernBound)
                / CAPACITY_FILL_GRID_CELL_SIZE);
        int numRows = (int) Math.ceil((SimSettings.getInstance().getNorthernBound() - southernBound)
                / CAPACITY_FILL_GRID_CELL_SIZE);
        double[] cellDemand = new double[numCols * numRows];

        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (!mobilityModel.isActive(deviceId, now)) continue;
            Location loc = mobilityModel.getLocation(deviceId, now);
            int col = clampIndex((int) ((loc.getXPos() - westernBound) / CAPACITY_FILL_GRID_CELL_SIZE), numCols);
            int row = clampIndex((int) ((loc.getYPos() - southernBound) / CAPACITY_FILL_GRID_CELL_SIZE), numRows);
            cellDemand[row * numCols + col] += mobilityModel.getPriority(deviceId, now);
        }

        double totalDemand = Arrays.stream(cellDemand).sum();
        // ONAT: derived from THIS tick's actual total demand, not a hardcoded constant -
        // exactly enough combined UAV capacity to cover total demand when LOAD_FACTOR=1,
        // so a single hotspot can only absorb every UAV if it truly holds ~all the demand.
        double capacityPerUav = totalDemand / numOfUavs * CAPACITY_FILL_LOAD_FACTOR;

        // ONAT: highest-demand cell first, ties broken by index (irrelevant to correctness).
        Integer[] cellsByDemand = new Integer[cellDemand.length];
        for (int c = 0; c < cellsByDemand.length; c++) cellsByDemand[c] = c;
        Arrays.sort(cellsByDemand, (a, b) -> Double.compare(cellDemand[b], cellDemand[a]));

        double[] uavX = new double[numOfUavs];
        double[] uavY = new double[numOfUavs];
        for (int u = 0; u < numOfUavs; u++) {
            Location loc = allUavs.get(u).getLocation();
            uavX[u] = loc.getXPos();
            uavY[u] = loc.getYPos();
        }

        // ONAT: -1 = not yet assigned to any cell this tick.
        int[] assignedCell = new int[numOfUavs];
        Arrays.fill(assignedCell, -1);
        int unassignedCount = numOfUavs;

        for (int cellIndex : cellsByDemand) {
            if (unassignedCount == 0) break;
            double remainingDemand = cellDemand[cellIndex];
            if (remainingDemand <= 0) break; // ONAT: no demand left at all - nothing further to cover

            double cellCenterX = westernBound + (cellIndex % numCols + 0.5) * CAPACITY_FILL_GRID_CELL_SIZE;
            double cellCenterY = southernBound + (cellIndex / numCols + 0.5) * CAPACITY_FILL_GRID_CELL_SIZE;

            while (remainingDemand > 0 && unassignedCount > 0) {
                int nearest = -1;
                double bestDistanceSquared = Double.MAX_VALUE;
                for (int u = 0; u < numOfUavs; u++) {
                    if (assignedCell[u] != -1) continue;
                    double dx = uavX[u] - cellCenterX;
                    double dy = uavY[u] - cellCenterY;
                    double distanceSquared = dx * dx + dy * dy;
                    if (distanceSquared < bestDistanceSquared) {
                        bestDistanceSquared = distanceSquared;
                        nearest = u;
                    }
                }
                assignedCell[nearest] = cellIndex;
                unassignedCount--;
                remainingDemand -= capacityPerUav;
            }
        }

        for (int j = 0; j < numOfUavs; j++) {
            int cellIndex = assignedCell[j];
            if (cellIndex == -1) continue; // ONAT: no unmet demand to send this UAV to - stays put

            double targetX = westernBound + (cellIndex % numCols + 0.5) * CAPACITY_FILL_GRID_CELL_SIZE;
            double targetY = southernBound + (cellIndex / numCols + 0.5) * CAPACITY_FILL_GRID_CELL_SIZE;

            // ONAT: micro-dispersion - nudge apart from other UAVs sharing this same cell so
            // they hover in a small constellation instead of colliding at the exact center.
            double dispersionX = 0;
            double dispersionY = 0;
            for (int k = 0; k < numOfUavs; k++) {
                if (k == j || assignedCell[k] != cellIndex) continue;

                double dx = uavX[j] - uavX[k];
                double dy = uavY[j] - uavY[k];
                if (dx == 0 && dy == 0) {
                    // ONAT: exact overlap - direction is undefined, substitute a random one
                    // (same fix as the APF co-location bug described above).
                    double angle = RNG.nextDouble() * 2 * Math.PI;
                    dx = Math.cos(angle);
                    dy = Math.sin(angle);
                }
                double scale = CAPACITY_FILL_DISPERSION_GAIN / cubedSoftenedDistance(dx, dy);
                dispersionX += scale * dx;
                dispersionY += scale * dy;
            }

            alertUav(allUavs.get(j), targetX + dispersionX, targetY + dispersionY);
        }
    }

    // ONAT: Adaptive K-Means: a state machine that switches strategy across the
    // scenario's two lifecycle breakpoints (users converging onto their meeting areas,
    // then SAR members entering) instead of running one fixed formula all simulation
    // long like PRIORITY_KMEANS/APF/CAPACITY_FILL. Per the spec, SAR priority weighting
    // is intentionally NOT used anywhere in this policy (every K-Means pass below uses a
    // uniform weight of 1.0) - unlike PRIORITY_KMEANS/APF/CAPACITY_FILL which all lean on
    // MobilityModel.getPriority.
    // Phase SCATTERED (adaptiveMiddlePoints == null): behaves exactly like PRIORITY_KMEANS
    // (K = numOfUavs, unweighted) - see runAdaptiveScatterPhase.
    // Phase GATHERED (middle points saved, SAR not yet seen): the meeting-area centroids
    // are frozen and saved; UAVs simply hold their already-converged positions (no new
    // alert) until SAR arrives - re-running K-Means would be pointless since the now
    // near-stationary crowd wouldn't move the centroids anyway.
    // Phase SAR_ARRIVED: one UAV per saved middle point is parked there permanently (the
    // UAV nearest to that point - see parkUavsAtMiddlePoints), and every remaining UAV is
    // continuously re-clustered (K = numOfUavs - numOfMiddlePoints, unweighted) onto
    // whichever devices only became active after the gather breakpoint - see
    // runAdaptiveSarPhase.
    private void runAdaptiveKMeans() {
        int numOfUavs = allUavs.size();
        if (numOfUavs == 0) return;

        double now = CloudSim.clock();
        MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();
        int numOfMobileDevice = SimManager.getInstance().getNumOfMobileDevice();

        if (adaptiveMiddlePoints == null) {
            runAdaptiveScatterPhase(numOfUavs, mobilityModel, numOfMobileDevice, now);
            return;
        }

        if (!adaptiveSarArrived) {
            // ONAT: "SAR users enter the scene" breakpoint, detected generically as "some
            // device that was inactive at gather-time just became active" - no hardcoded
            // SAR device-id range/knowledge needed here.
            for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
                if (mobilityModel.isActive(deviceId, now) && !adaptiveActiveAtGather[deviceId]) {
                    adaptiveSarArrived = true;
                    parkUavsAtMiddlePoints(numOfUavs);
                    break;
                }
            }
            if (!adaptiveSarArrived) return; // ONAT: gathered, SAR not here yet - hold position
        }

        runAdaptiveSarPhase(numOfUavs, mobilityModel, numOfMobileDevice, now);
    }

    // ONAT: Unweighted K-Means (K = numOfUavs) over every active device, identical to
    // PRIORITY_KMEANS but with priority ignored (weight forced to 1.0) - before SAR arrive
    // every active device is a normal user anyway, so this only ever clusters the crowd.
    // Also tracks whether the resulting centers have stopped moving across ticks; once
    // stable for ADAPTIVE_GATHER_STABLE_DURATION simulated seconds, saves deduplicated
    // "middle points" and a snapshot of which devices were active at that moment (see
    // class javadoc).
    private void runAdaptiveScatterPhase(int numOfUavs, MobilityModel mobilityModel,
                                          int numOfMobileDevice, double now) {
        int activeCount = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (mobilityModel.isActive(deviceId, now)) activeCount++;
        }
        if (activeCount == 0) return; // ONAT: nothing to cluster around - every UAV stays put this tick

        double[] deviceX = new double[activeCount];
        double[] deviceY = new double[activeCount];
        double[] deviceWeight = new double[activeCount]; // ONAT: uniform - SAR priority ignored
        int i = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (!mobilityModel.isActive(deviceId, now)) continue;
            Location loc = mobilityModel.getLocation(deviceId, now);
            deviceX[i] = loc.getXPos();
            deviceY[i] = loc.getYPos();
            deviceWeight[i] = 1.0;
            i++;
        }

        double[] centerX = new double[numOfUavs];
        double[] centerY = new double[numOfUavs];
        for (int u = 0; u < numOfUavs; u++) {
            Location loc = allUavs.get(u).getLocation();
            centerX[u] = loc.getXPos();
            centerY[u] = loc.getYPos();
        }

        runLloydKMeans(deviceX, deviceY, deviceWeight, centerX, centerY);

        if (adaptivePrevCenterX != null) {
            double maxShiftSincePrevTick = 0;
            for (int u = 0; u < numOfUavs; u++) {
                maxShiftSincePrevTick = Math.max(maxShiftSincePrevTick, Math.hypot(
                        centerX[u] - adaptivePrevCenterX[u], centerY[u] - adaptivePrevCenterY[u]));
            }
            if (maxShiftSincePrevTick >= ADAPTIVE_GATHER_EPSILON) {
                adaptiveStableSinceTime = -1; // ONAT: real movement - streak broken
            } else if (adaptiveStableSinceTime < 0) {
                adaptiveStableSinceTime = now; // ONAT: first quiet tick of a new streak
            }
        }
        adaptivePrevCenterX = centerX.clone();
        adaptivePrevCenterY = centerY.clone();

        if (adaptiveStableSinceTime >= 0 && now - adaptiveStableSinceTime >= ADAPTIVE_GATHER_STABLE_DURATION) {
            adaptiveMiddlePoints = mergeNearbyPoints(centerX, centerY);
            adaptiveActiveAtGather = new boolean[numOfMobileDevice];
            for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
                adaptiveActiveAtGather[deviceId] = mobilityModel.isActive(deviceId, now);
            }
        }

        for (int u = 0; u < numOfUavs; u++) {
            alertUav(allUavs.get(u), centerX[u], centerY[u]);
        }
    }

    // ONAT: Greedily merges cluster centers within ADAPTIVE_MIDDLE_POINT_MERGE_DISTANCE of
    // each other into a single averaged point - collapses K=numOfUavs near-duplicate
    // centers down to the true (usually much smaller) number of distinct physical
    // gathering spots.
    private static List<double[]> mergeNearbyPoints(double[] pointX, double[] pointY) {
        int n = pointX.length;
        boolean[] merged = new boolean[n];
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (merged[i]) continue;
            double sumX = pointX[i];
            double sumY = pointY[i];
            int count = 1;
            merged[i] = true;
            for (int j = i + 1; j < n; j++) {
                if (merged[j]) continue;
                if (Math.hypot(pointX[j] - pointX[i], pointY[j] - pointY[i]) <= ADAPTIVE_MIDDLE_POINT_MERGE_DISTANCE) {
                    sumX += pointX[j];
                    sumY += pointY[j];
                    count++;
                    merged[j] = true;
                }
            }
            result.add(new double[]{sumX / count, sumY / count});
        }
        return result;
    }

    // ONAT: One-time (per simulation run) assignment: the UAV nearest each saved middle
    // point is sent there and flagged in adaptiveParked so future ticks never alert it
    // again ("will not move", per spec). Always leaves numOfUavs - adaptiveMiddlePoints.size()
    // UAVs free, since middle points are a merge of exactly numOfUavs seed clusters.
    private void parkUavsAtMiddlePoints(int numOfUavs) {
        adaptiveParked = new boolean[numOfUavs];
        boolean[] uavTaken = new boolean[numOfUavs];
        for (double[] middlePoint : adaptiveMiddlePoints) {
            int nearest = -1;
            double bestDistanceSquared = Double.MAX_VALUE;
            for (int u = 0; u < numOfUavs; u++) {
                if (uavTaken[u]) continue;
                Location loc = allUavs.get(u).getLocation();
                double dx = loc.getXPos() - middlePoint[0];
                double dy = loc.getYPos() - middlePoint[1];
                double distanceSquared = dx * dx + dy * dy;
                if (distanceSquared < bestDistanceSquared) {
                    bestDistanceSquared = distanceSquared;
                    nearest = u;
                }
            }
            if (nearest == -1) break; // ONAT: more middle points than UAVs - shouldn't happen, guard anyway

            uavTaken[nearest] = true;
            adaptiveParked[nearest] = true;
            alertUav(allUavs.get(nearest), middlePoint[0], middlePoint[1]);
        }
    }

    // ONAT: Every tick from SAR-arrival onward - unweighted K-Means (K = number of
    // non-parked UAVs) over exactly the devices that only became active after the gather
    // breakpoint (this policy's generic stand-in for "SAR users", see class javadoc).
    // Re-seeded from each free UAV's CURRENT position every tick (like PRIORITY_KMEANS),
    // since unlike the crowd, SAR members keep moving for the rest of the simulation.
    private void runAdaptiveSarPhase(int numOfUavs, MobilityModel mobilityModel,
                                      int numOfMobileDevice, double now) {
        List<Integer> freeUavIndices = new ArrayList<>();
        for (int u = 0; u < numOfUavs; u++) {
            if (!adaptiveParked[u]) freeUavIndices.add(u);
        }
        if (freeUavIndices.isEmpty()) return;

        int newArrivalCount = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (mobilityModel.isActive(deviceId, now) && !adaptiveActiveAtGather[deviceId]) newArrivalCount++;
        }
        if (newArrivalCount == 0) return;

        double[] deviceX = new double[newArrivalCount];
        double[] deviceY = new double[newArrivalCount];
        double[] deviceWeight = new double[newArrivalCount]; // ONAT: uniform - SAR priority ignored
        int i = 0;
        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            if (!mobilityModel.isActive(deviceId, now) || adaptiveActiveAtGather[deviceId]) continue;
            Location loc = mobilityModel.getLocation(deviceId, now);
            deviceX[i] = loc.getXPos();
            deviceY[i] = loc.getYPos();
            deviceWeight[i] = 1.0;
            i++;
        }

        int numOfFreeUavs = freeUavIndices.size();
        double[] centerX = new double[numOfFreeUavs];
        double[] centerY = new double[numOfFreeUavs];
        for (int c = 0; c < numOfFreeUavs; c++) {
            Location loc = allUavs.get(freeUavIndices.get(c)).getLocation();
            centerX[c] = loc.getXPos();
            centerY[c] = loc.getYPos();
        }

        runLloydKMeans(deviceX, deviceY, deviceWeight, centerX, centerY);

        for (int c = 0; c < numOfFreeUavs; c++) {
            alertUav(allUavs.get(freeUavIndices.get(c)), centerX[c], centerY[c]);
        }
    }

    // ONAT: Clamps a grid coordinate index into [0, count) - a device sitting exactly on
    // the eastern/northern map bound would otherwise compute an out-of-range index.
    private static int clampIndex(int index, int count) {
        return Math.max(0, Math.min(count - 1, index));
    }

    // ONAT: sqrt(dx^2+dy^2+SOFTENING^2)^3, i.e. the softened ||r||^3 denominator shared by
    // both APF force terms - never zero, so it never produces NaN/Infinity even when two
    // points coincide exactly.
    private static double cubedSoftenedDistance(double dx, double dy) {
        double softenedDistance = Math.sqrt(dx * dx + dy * dy + APF_SOFTENING_SQUARED);
        return softenedDistance * softenedDistance * softenedDistance;
    }

    // ONAT: Applies the controller's alert - moves the UAV toward (targetX, targetY),
    // capped by its own max speed, then clamps to the map bounds. Physical-movement rules
    // are identical to BasicUAVMobility's so centralized and decentralized policies stay
    // directly comparable; only the target-selection logic above differs per policy.
    private void alertUav(UAV uav, double targetX, double targetY) {
        Location current = uav.getLocation();
        double vectorX = targetX - current.getXPos();
        double vectorY = targetY - current.getYPos();
        double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

        int newX = current.getXPos();
        int newY = current.getYPos();
        if (distanceToTarget > 0) {
            double ratio = Math.min(1.0, maxMoveDistance(uav) / distanceToTarget);
            newX += (int) (vectorX * ratio);
            newY += (int) (vectorY * ratio);
        }

        double easternBound = SimSettings.getInstance().getEasternBound();
        double westernBound = SimSettings.getInstance().getWesternBound();
        double northernBound = SimSettings.getInstance().getNorthernBound();
        double southernBound = SimSettings.getInstance().getSouthernBound();

        newX = (int) Math.max(westernBound, Math.min(easternBound, newX));
        newY = (int) Math.max(southernBound, Math.min(northernBound, newY));

        uav.setPlace(new Location(current.getServingWlanId(), current.getPlaceTypeIndex(), newX, newY));
    }

    // ONAT: Distance a UAV can cover between two controller decisions - based on the
    // controller's OWN decision cadence (centralized_controller_interval), not UAV.
    // getMaxMoveDistance() (which is tied to uav_mobility_interval, the decentralized
    // per-UAV self-scheduling cadence BasicUAVMobility uses instead).
    private static double maxMoveDistance(UAV uav) {
        return uav.getSpeed() * SimSettings.getInstance().getCentralizedControllerInterval();
    }

    private void scheduleNextControlTick() {
        schedule(getId(), SimSettings.getInstance().getCentralizedControllerInterval(), CENTRAL_CONTROL_TICK);
    }
}
