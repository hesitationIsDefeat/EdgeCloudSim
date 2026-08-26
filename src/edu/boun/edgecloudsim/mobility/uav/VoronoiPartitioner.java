package edu.boun.edgecloudsim.mobility.uav;

import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.SimUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ONAT: Shared cache for the VORONOI/VORONOI_&lt;factor&gt; UAV mobility policy.
 * <p>
 * Previously, every UAV independently recomputed the FULL nearest-UAV partition of
 * every mobile device (and its own cell's weighted centroid) from scratch on every one
 * of its own move events - the same result (which UAV each device is currently nearest
 * to) was recomputed once per UAV, even though it only depends on the current UAV
 * positions, not on which UAV is asking.
 * <p>
 * This class computes the partition/centroids for ALL UAVs in a single pass and caches
 * the result. It is refreshed on its own fixed cadence by {@link BasicUAVMobility}
 * (decoupled from any individual UAV's jittered move-event timing), so a UAV's move
 * event just looks up its own already-computed target instead of recomputing anything.
 */
class VoronoiPartitioner {
    /** ONAT: Centroid a UAV should move toward - plain x/y, not a real place/WLAN cell. */
    record Target(double x, double y) {
    }

    private Map<UAV, Target> targetByUav = Map.of();
    // ONAT: Simulation time the cache above was computed for - NaN before the first call.
    private double lastUpdateTime = Double.NaN;

    /**
     * Recomputes the partition at most once per distinct simulation time, no matter how
     * many UAVs' move events call this at that same instant: whichever UAV reaches here
     * first for a given {@code now} does the recompute, every other UAV at that same
     * {@code now} just reuses the already-fresh cache. This makes freshness a guarantee
     * of this class's own bookkeeping, instead of depending on how the discrete-event
     * queue happens to order a separate, same-tick "refresh" event against move events.
     */
    void ensureUpToDate(List<UAV> uavs, MobilityModel mobilityModel, int numOfMobileDevice, double now) {
        if (now == lastUpdateTime) return;
        recalculate(uavs, mobilityModel, numOfMobileDevice, now);
        lastUpdateTime = now;
    }

    private void recalculate(List<UAV> uavs, MobilityModel mobilityModel, int numOfMobileDevice, double now) {
        Map<UAV, double[]> accumulator = new HashMap<>(); // ONAT: uav -> [sumX, sumY, totalWeight]
        for (UAV uav : uavs) {
            accumulator.put(uav, new double[3]);
        }

        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            // ONAT: Devices that haven't entered yet (e.g. staged SAR members) don't claim a cell.
            if (!mobilityModel.isActive(deviceId, now)) continue;

            var deviceLoc = mobilityModel.getLocation(deviceId, now);

            UAV nearestUav = null;
            double nearestDistance = Double.MAX_VALUE;
            for (UAV uav : uavs) {
                double distance = SimUtils.getEuclideanDistance(uav.getLocation(), deviceLoc);
                if (distance < nearestDistance) {
                    nearestDistance = distance;
                    nearestUav = uav;
                }
            }

            if (nearestUav != null) {
                double weight = mobilityModel.getPriority(deviceId, now);
                double[] acc = accumulator.get(nearestUav);
                acc[0] += deviceLoc.getXPos() * weight;
                acc[1] += deviceLoc.getYPos() * weight;
                acc[2] += weight;
            }
        }

        Map<UAV, Target> newTargets = new HashMap<>();
        for (Map.Entry<UAV, double[]> entry : accumulator.entrySet()) {
            double[] acc = entry.getValue();
            if (acc[2] > 0) {
                newTargets.put(entry.getKey(), new Target(acc[0] / acc[2], acc[1] / acc[2]));
            }
        }

        this.targetByUav = newTargets;
    }

    /** Returns the cached centroid this UAV should move toward, or null if its cell is currently empty. */
    Target getTarget(UAV uav) {
        return targetByUav.get(uav);
    }
}
