package edu.boun.edgecloudsim.network.uav;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimUtils;
import org.cloudbus.cloudsim.Host;

public class UAVNetworkModel extends NetworkModel {
    private double poissonMean;
    private double avgTaskInputSize;
    private double avgTaskOutputSize;

    private static final int MAX_WLAN_BANDWIDTH = SimSettings.getInstance().getWlanBandwidth();
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

        // Safety check to prevent negative delay if system is overloaded (lambda > mu)
        if (mu <= lamda) return 10.0; // Return a high penalty latency

        double result = (double) 1 / (mu - lamda);
        result += propagationDelay;
        return result;
    }

    private int getBandwidthAtDistance(double distance) {
        if (distance <= (double) UAV.SERVICE_RADIUS / 3) {
            return MAX_WLAN_BANDWIDTH; // 100% speed
        } else if (distance <= (double) 2 * UAV.SERVICE_RADIUS / 3) {
            return 3 * MAX_WLAN_BANDWIDTH / 4; // 75% speed
        } else if (distance <= UAV.SERVICE_RADIUS) {
            return MAX_WLAN_BANDWIDTH / 2; // 50% speed
        } else {
            return MAX_WLAN_BANDWIDTH / 10; // Weak signal edge case
        }
    }

    @Override
    public double getUploadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        double result = 0;
        int numDatacenter = SimSettings.getInstance().getNumOfEdgeDatacenters();

        double currentDistance;
        int currentBandwidth = MAX_WLAN_BANDWIDTH;

        Host destHost = SimUtils.getHostFromId(destDeviceId);

        if (destHost instanceof UAV uav) {
            Location deviceLoc = task.getSubmittedLocation();
            Location uavLoc = uav.getLocation();

            currentDistance = SimUtils.getEuclideanDistance(deviceLoc, uavLoc);

            currentBandwidth = getBandwidthAtDistance(currentDistance);
        }
        return calculateMM1(0,
                currentBandwidth,
                poissonMean,
                avgTaskOutputSize,
                numberOfMobileDevices / numDatacenter);
    }

    @Override
    public double getDownloadDelay(int sourceDeviceId, int destDeviceId, Task task) {
        return getUploadDelay(sourceDeviceId, destDeviceId, task);
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
