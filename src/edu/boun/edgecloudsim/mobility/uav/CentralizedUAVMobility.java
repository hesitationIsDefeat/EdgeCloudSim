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
 * policies (NO, RANDOM, PRIORITY_KMEANS).
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

    private final String centralizedMobilityOption;
    private List<UAV> allUavs = List.of();

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

        double[] sumX = new double[numOfUavs];
        double[] sumY = new double[numOfUavs];
        double[] sumWeight = new double[numOfUavs];
        for (int iteration = 0; iteration < KMEANS_MAX_ITERATIONS; iteration++) {
            Arrays.fill(sumX, 0);
            Arrays.fill(sumY, 0);
            Arrays.fill(sumWeight, 0);

            for (int d = 0; d < activeCount; d++) {
                int nearest = nearestCenter(centerX, centerY, deviceX[d], deviceY[d]);
                sumX[nearest] += deviceX[d] * deviceWeight[d];
                sumY[nearest] += deviceY[d] * deviceWeight[d];
                sumWeight[nearest] += deviceWeight[d];
            }

            double maxShift = 0;
            for (int u = 0; u < numOfUavs; u++) {
                if (sumWeight[u] <= 0) continue; // ONAT: empty cluster - keep its previous center

                double newX = sumX[u] / sumWeight[u];
                double newY = sumY[u] / sumWeight[u];
                maxShift = Math.max(maxShift, Math.hypot(newX - centerX[u], newY - centerY[u]));
                centerX[u] = newX;
                centerY[u] = newY;
            }

            if (maxShift < KMEANS_CONVERGENCE_EPSILON) break; // ONAT: converged early
        }

        for (int u = 0; u < numOfUavs; u++) {
            alertUav(allUavs.get(u), centerX[u], centerY[u]);
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
