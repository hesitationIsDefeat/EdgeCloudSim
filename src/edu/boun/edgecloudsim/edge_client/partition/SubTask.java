package edu.boun.edgecloudsim.edge_client.partition;

import edu.boun.edgecloudsim.edge_client.Task;
import org.cloudbus.cloudsim.UtilizationModel;

public class SubTask extends Task {

    // A field for the ID of the parent Task
    private int parentTaskId;

    /**
     * Constructor for Task with specified parameters.
     *
     * @param _mobileDeviceId     ID of the mobile device that generated this task
     * @param cloudletId          Unique identifier for this task/cloudlet
     * @param cloudletLength      Processing length required in Million Instructions (MI)
     * @param pesNumber           Number of processing elements required
     * @param cloudletFileSize    Input data size in bytes
     * @param cloudletOutputSize  Output data size in bytes
     * @param utilizationModelCpu CPU utilization model for this task
     * @param utilizationModelRam RAM utilization model for this task
     * @param utilizationModelBw  Bandwidth utilization model for this task
     */
    public SubTask(int _mobileDeviceId, int cloudletId, long cloudletLength, int pesNumber, long cloudletFileSize,
                   long cloudletOutputSize, UtilizationModel utilizationModelCpu, UtilizationModel utilizationModelRam,
                   UtilizationModel utilizationModelBw,
                   int parentTaskId) {
        super(_mobileDeviceId, cloudletId, cloudletLength, pesNumber, cloudletFileSize, cloudletOutputSize, utilizationModelCpu, utilizationModelRam, utilizationModelBw);

        this.parentTaskId = parentTaskId;
    }
}
