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

import java.util.HashMap;
import java.util.Map;

/**
 * ONAT: WLAN model between mobile devices and UAVs, driven entirely by what actually
 * happens in the simulation instead of configured averages:
 * <ul>
 *   <li>Payload: an upload carries the task's own input size and a download its own output
 *   size (what the task was really generated with), not the application's mean sizes.</li>
 *   <li>Bandwidth: derived from the device's and the UAV's positions at the moment of the
 *   transfer (see {@link #getBandwidthAtDistance(double)}).</li>
 *   <li>Contention: every UAV's WLAN is one shared first-come-first-served link. A transfer
 *   waits behind the transfers that were actually started on that UAV and are still in
 *   progress, so the queueing delay follows the real arrival times and sizes instead of
 *   an M/M/1 estimate built from the mean inter-arrival time and the number of devices
 *   standing nearby.</li>
 * </ul>
 * A transfer that cannot take place (no UAV link, or the device is outside the UAV's service
 * radius) returns 0.0, which the callers treat as a rejection.
 */
public class UAVNetworkModel extends NetworkModel {
    private static final int MAX_WLAN_BANDWIDTH = SimSettings.getInstance().getWlanBandwidth();

    // Sizes are whole KB (the sampled sizes are truncated to long), so a sub-KB payload shows
    // up as 0 - treat it as the smallest representable payload, since a 0 s delay would be
    // read by the callers as a rejected transfer.
    private static final long MIN_PAYLOAD_KB = 1;

    // Simulation time at which each UAV's WLAN link (keyed by host id) finishes the transfers
    // started so far, i.e. when a transfer arriving now could actually start.
    private final Map<Integer, Double> linkFreeAt = new HashMap<Integer, Double>();

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
        linkFreeAt.clear();
    }

    private int getBandwidthAtDistance(double distance) {
        if (distance > UAV.SERVICE_RADIUS) {
            return 0; // Out of range cutoff
        }
        
        double referenceDistance = 25.0; // Near-field boundary
        if (distance <= referenceDistance) {
            return MAX_WLAN_BANDWIDTH;
        }

        // 1. Calculate SNR based on 1/d^2 (Free Space Path Loss)
        // Assume a strong baseline linear SNR of 1000 (~30 dB) at 25 meters
        double baseSnr = 1000.0; 
        double currentSnr = baseSnr * Math.pow(referenceDistance / distance, 2);
        
        // 2. Calculate Bandwidth capacity using Shannon-Hartley: C = B * log2(1 + SNR)
        // We use Math.log() divided by Math.log(2) to get base-2 logarithm
        double maxCapacity = Math.log(1 + baseSnr) / Math.log(2);
        double currentCapacity = Math.log(1 + currentSnr) / Math.log(2);
        
        // 3. Find the ratio and apply it to the max bandwidth
        double capacityRatio = currentCapacity / maxCapacity;
        
        return (int) (MAX_WLAN_BANDWIDTH * capacityRatio);
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
     * Where the device is right now (not where the task was submitted): a download happens
     * after the device has moved on, and the UAVs move too, so the distance has to be taken
     * from the positions at the moment of the transfer.
     */
    private Location getCurrentDeviceLocation(Task task) {
        return SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(), CloudSim.clock());
    }

    /**
     * Computes how long moving payloadKB between the device and the given UAV takes right
     * now and books that time on the UAV's shared link, so the transfers that follow queue
     * behind it. The callers (DefaultMobileDeviceManager) ask for the delay once per real
     * transfer and start the transfer whenever the result is positive, so booking it here is
     * equivalent to starting the transfer.
     *
     * @return the delay in seconds (waiting for the link plus transmission), or 0.0 if
     *         there is no UAV link to carry it or the device is out of the UAV's range
     */
    private double getTransferDelay(UAV uav, Location deviceLocation, long payloadKB) {
        if (uav == null)
            return 0.0;

        int bandwidth /*Kbps*/ = getBandwidthAtDistance(SimUtils.getEuclideanDistance(deviceLocation, uav.getLocation()));
        if (bandwidth <= 0)
            return 0.0;

        double now = CloudSim.clock();
        double transmissionTime = Math.max(payloadKB, MIN_PAYLOAD_KB) * 8.0 / bandwidth; // KB -> Kb, Kb / Kbps = s
        double transferStart = Math.max(now, linkFreeAt.getOrDefault(uav.getId(), 0.0));
        double transferEnd = transferStart + transmissionTime;
        linkFreeAt.put(uav.getId(), transferEnd);

        return transferEnd - now;
    }

    @Override
    public double getUploadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        Location deviceLocation = getCurrentDeviceLocation(task);

        Host destHost = SimUtils.getHostFromId(destDeviceId);
        UAV uav = null;
        if (destHost instanceof UAV) {
            // Caller already knows the bound host (e.g. a partitioned task's
            // pre-selected sibling VM) - use it directly.
            uav = (UAV) destHost;
        } else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
            // Upload delay for a not-yet-partitioned task is computed before the
            // orchestrator binds a VM, so destDeviceId is just the generic-edge
            // sentinel here, not a real host ID. Approximate with the nearest UAV,
            // since WLAN association is driven by radio proximity, not by the
            // orchestrator's (possibly load-based) VM placement decision.
            uav = findNearestUAV(deviceLocation);
        }

        return getTransferDelay(uav, deviceLocation, task.getCloudletFileSize());
    }

    @Override
    public double getDownloadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        // Unlike getUploadDelay, the first parameter is the already-bound host (the UAV
        // sending the result back) and the second is the mobile device receiving it.
        Host sourceHost = SimUtils.getHostFromId(sourceDeviceId);
        UAV uav = (sourceHost instanceof UAV) ? (UAV) sourceHost : null;

        return getTransferDelay(uav, getCurrentDeviceLocation(task), task.getCloudletOutputSize());
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
