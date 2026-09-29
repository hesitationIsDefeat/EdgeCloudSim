package edu.boun.edgecloudsim.network.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimUtils;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.core.CloudSim;

public class UAVNetworkModel extends NetworkModel {
    private double poissonMean;
    private double avgTaskInputSize;
    private double avgTaskOutputSize;

    private static final int MAX_WLAN_BANDWIDTH = SimSettings.getInstance().getWlanBandwidth();

    /** Devices within this range of a UAV are treated as contending for its bandwidth */
    private static final double CO_LOCATION_RANGE = 50.0; // m
    /**
     * Constructs a new NetworkModel instance with the specified parameters.
     *
     * @param _numberOfMobileDevices the total number of mobile devices in the simulation
     * @param _simScenario           the simulation scenario identifier used for configuration
     */
    public UAVNetworkModel(int _numberOfMobileDevices, String _simScenario) {
        super(_numberOfMobileDevices, _simScenario);
    }

    @Override
    public void initialize() {
        poissonMean = 0;
        avgTaskInputSize = 0;
        avgTaskOutputSize = 0;

        double numOfTaskType = 0;
        SimSettings SS = SimSettings.getInstance();
        for (int i = 0; i < SimSettings.getInstance().getTaskLookUpTable().length; i++) {
            double weight = SS.getTaskLookUpTable()[i][0] / (double) 100;
            if (weight != 0) {
                poissonMean += (SS.getTaskLookUpTable()[i][2]) * weight;
                avgTaskInputSize += SS.getTaskLookUpTable()[i][5] * weight;
                avgTaskOutputSize += SS.getTaskLookUpTable()[i][6] * weight;
                numOfTaskType++;
            }
        }

        poissonMean = poissonMean / numOfTaskType;
        avgTaskInputSize = avgTaskInputSize / numOfTaskType;
        avgTaskOutputSize = avgTaskOutputSize / numOfTaskType;
    }

    private double calculateMM1(double propagationDelay, int bandwidth /*Kbps*/, double PoissonMean, double avgTaskSize /*KB*/, int deviceCount) {
        double Bps = 0, mu = 0, lamda = 0;
        avgTaskSize = avgTaskSize * (double) 1000; // KB -> Bytes
        Bps = bandwidth * (double) 1000 / (double) 8; // Kbps -> Bytes/sec
        lamda = ((double) 1 / (double) PoissonMean) * (double) deviceCount;
        mu = Bps / avgTaskSize;

        // Safety check to prevent negative delay if the network is overloaded.
        // Returning zero causes the caller to treat the link as unavailable and
        // reject the task instead of injecting a synthetic penalty latency.
        if (mu <= lamda) return 0.0;

        double result = (double) 1 / (mu - lamda);
        result += propagationDelay;
        return result;
    }

    private int getBandwidthAtDistance(double distance) {
        if (distance <= 25) {
            // ONAT: Near-field
            return MAX_WLAN_BANDWIDTH;
        } else if (distance <= 75.0) {
            // ONAT: Mid-range
            return (int) (0.8 * MAX_WLAN_BANDWIDTH);
        } else if (distance <= 125.0) {
            // ONAT: Far-range
            return (int) (0.5 * MAX_WLAN_BANDWIDTH);
        } else if (distance <= UAV.SERVICE_RADIUS) {
            // ONAT: Edge of coverage
            return (int) (0.2 * MAX_WLAN_BANDWIDTH);
        } else {
            // ONAT: Out of range
            return 0;
        }
    }

    /**
     * Finds the UAV physically closest to the given location, regardless of
     * SERVICE_RADIUS or current load. Used as a stand-in for "the UAV this device's
     * radio would associate with" when the orchestrator hasn't chosen a target VM yet
     * (see getUploadDelay) - association is driven by proximity, not by the
     * orchestrator's load-balancing decision, so this is independent of (and doesn't
     * duplicate) EdgeOrchestrator VM-selection policies.
     */
    private UAV findNearestUAV(Location location) {
        UAV nearest = null;
        double minDistance = Double.MAX_VALUE;
        for (Datacenter dc : SimManager.getInstance().getEdgeServerManager().getDatacenterList()) {
            for (Host h : dc.getHostList()) {
                if (h instanceof UAV uav) {
                    double distance = SimUtils.getEuclideanDistance(location, uav.getLocation());
                    if (distance < minDistance) {
                        minDistance = distance;
                        nearest = uav;
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Counts mobile devices currently within CO_LOCATION_RANGE of the given UAV location.
     * Replaces the flat numberOfMobileDevices/numDatacenter average with an actual
     * proximity-based count, since devices no longer snap to discrete grid cells.
     */
    private int getDeviceCount(Location uavLocation, double time) {
        int deviceCount = 0;
        for (int i = 0; i < numberOfMobileDevices; i++) {
            Location location = SimManager.getInstance().getMobilityModel().getLocation(i, time);
            if (SimUtils.getEuclideanDistance(location, uavLocation) <= CO_LOCATION_RANGE)
                deviceCount++;
        }
        return deviceCount;
    }

    @Override
    public double getUploadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        double currentDistance;
        int currentBandwidth = MAX_WLAN_BANDWIDTH;
        int deviceCount = numberOfMobileDevices / SimSettings.getInstance().getNumOfEdgeDatacenters();

        Host destHost = SimUtils.getHostFromId(destDeviceId);
        UAV uav = null;
        if (destHost instanceof UAV) {
            // Caller already knows the bound host (e.g. download delay, or a
            // partitioned task's pre-selected sibling VM) - use it directly.
            uav = (UAV) destHost;
        } else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            // Upload delay for a not-yet-partitioned task is computed before the
            // orchestrator binds a VM, so destDeviceId is just the generic-edge
            // sentinel here, not a real host ID. Approximate with the nearest UAV,
            // since WLAN association is driven by radio proximity, not by the
            // orchestrator's (possibly load-based) VM placement decision.
            uav = findNearestUAV(task.getSubmittedLocation());
        }

        if (uav != null) {
            Location deviceLoc = task.getSubmittedLocation();
            Location uavLoc = uav.getLocation();

            currentDistance = SimUtils.getEuclideanDistance(deviceLoc, uavLoc);

            currentBandwidth = getBandwidthAtDistance(currentDistance);
            deviceCount = getDeviceCount(uavLoc, CloudSim.clock());
        }
        return calculateMM1(0,
                currentBandwidth,
                poissonMean,
                avgTaskOutputSize,
                deviceCount);
    }

    @Override
    public double getDownloadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        // getUploadDelay resolves its target host from its *second* parameter;
        // sourceDeviceId here is the already-bound host ID and destDeviceId is the
        // mobile device ID, i.e. reversed from getUploadDelay's own parameter order -
        // swap them so the host ID (not the device ID) is what gets looked up.
        return getUploadDelay(destDeviceId, sourceDeviceId, task);
    }

    @Override
    public void uploadStarted(Location accessPointLocation, int destDeviceId) {

    }

    @Override
    public void uploadFinished(Location accessPointLocation, int destDeviceId) {

    }

    @Override
    public void downloadStarted(Location accessPointLocation, int sourceDeviceId) {

    }

    @Override
    public void downloadFinished(Location accessPointLocation, int sourceDeviceId) {

    }
}
