package edu.boun.edgecloudsim.edge_client.partition;

import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.utils.SimLogger;
import org.cloudbus.cloudsim.UtilizationModel;

import java.util.ArrayList;
import java.util.List;

public class PartititionableTask extends Task {

    // A list of partition ratios for each SubTask respectively
    private final List<Integer> partitionRatios;

    // A list of SubTasks
    private final List<SubTask> subTasks;

    // A count variable for the amount of completed subtasks
    private int completedSubTaskCount;

    // A boolean to check if any SubTask failed
    private boolean isAnyFailed;

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
    public PartititionableTask(int _mobileDeviceId, int cloudletId, long cloudletLength, int pesNumber,
                               long cloudletFileSize, long cloudletOutputSize, UtilizationModel utilizationModelCpu,
                               UtilizationModel utilizationModelRam, UtilizationModel utilizationModelBw,
                               List<Integer> partitionRatios) {
        super(_mobileDeviceId, cloudletId, cloudletLength, pesNumber, cloudletFileSize, cloudletOutputSize, utilizationModelCpu, utilizationModelRam, utilizationModelBw);

        this.partitionRatios = partitionRatios;
        this.subTasks = new ArrayList<>(partitionRatios.size());
        this.completedSubTaskCount = 0;
        this.isAnyFailed = false;

        init();
    }

    /**
     * A function to handle further initialization in the constructor after the fields are assigned
     */
    private void init() {
        checkRatioSum();

        generateSubtasks();
    }

    /**
     * A function to check if the partition ratios add up to a 100, and exists if not
     */
    private void checkRatioSum() {
        int sum = this.partitionRatios.stream().reduce(0, Integer::sum);
        if (sum != 100) {
            SimLogger.printLine("WARNING: Partition ratios for Task %d do not sum to 100. Sum: %d".formatted(getCloudletId(), sum));
            System.exit(1);
        }
    }

    /**
     * A function to generate the SubTasks
     */
    private void generateSubtasks() {
        // Total values of the task
        long totalLength = getCloudletLength();
        long totalFileSize = getCloudletFileSize();
        long totalOutputSize = getCloudletOutputSize();

        // Total partitioned values of the task
        long partitionedLength = 0;
        long partitionedFileSize = 0;
        long partitionedOutputSize = 0;

        // SubTask values
        long subLength;
        long subFileSize;
        long subOutputSize;

        // SubTask ID
        int subTaskId;

        // Iterate over the subtasks, excluding the last one
        for (int subTaskIndex = 0; subTaskIndex < this.subTasks.size() - 1; subTaskIndex++) {
            int ratio = partitionRatios.get(subTaskIndex);

            // Calculate the values for the SubTask
            subLength = (totalLength * ratio) / 100;
            subFileSize = (totalFileSize * ratio) / 100;
            subOutputSize = (totalOutputSize * ratio) / 100;

            // Add the values of the SubTask to the overall partitioned values
            partitionedLength += subLength;
            partitionedFileSize += subFileSize;
            partitionedOutputSize += subOutputSize;

            // Obtain the ID for the SubTask
            subTaskId = generateSubTaskId(subTaskIndex);

            // Create the SubTask
            SubTask subTask = new SubTask(
                    getMobileDeviceId(),
                    subTaskId,
                    subLength,
                    getNumberOfPes(),
                    subFileSize,
                    subOutputSize,
                    getUtilizationModelCpu(),
                    getUtilizationModelRam(),
                    getUtilizationModelBw(),
                    getCloudletId()
            );

            // Add the SubTask to the list
            this.subTasks.add(subTask);
        }

        // Handle the last SubTask separately to pick up the potential leftovers from integer division
        subLength = totalLength - partitionedLength;
        subFileSize = totalFileSize - partitionedFileSize;
        subOutputSize = totalOutputSize - partitionedOutputSize;

        subTaskId = this.subTasks.size() - 1;

        SubTask subTask = new SubTask(
                getMobileDeviceId(),
                subTaskId,
                subLength,
                getNumberOfPes(),
                subFileSize,
                subOutputSize,
                getUtilizationModelCpu(),
                getUtilizationModelRam(),
                getUtilizationModelBw(),
                getCloudletId()
        );

        this.subTasks.add(subTask);
    }


    /**
     * A function to generate ID for the SubTasks
     *
     * @param subTaskIndex Index of the SubTask
     * @return SubTask ID
     */
    private int generateSubTaskId(int subTaskIndex) {
        return (getCloudletId() * 10000) + (subTaskIndex);
    }

    /**
     * A thread-safe function for reporting the result of a SubTask
     *
     * @param result Result of the SubTask
     */
    public synchronized void reportSubTaskResult(boolean result) {
        completedSubTaskCount++;
        if (!result) {
            isAnyFailed = true;
        }
    }

    // GETTERS AND SETTERS

    /**
     * A getter function for the SubTask list
     *
     * @return A copy of the SubTask list
     */
    public List<SubTask> getSubTasks() {
        return new ArrayList<>(this.subTasks);
    }

    /**
     * A getter function for the reconstruction state of the Task
     *
     * @return True if fully reconstruction, else false
     */
    public boolean isFullyReconstructed() {
        return this.completedSubTaskCount >= this.subTasks.size();
    }

    /**
     * A getter function for the overall success of the Task
     *
     * @return True if all SubTasks reconstructed and were successful, else false
     */
    public boolean isSuccess() {
        return isFullyReconstructed() && !isAnyFailed;
    }
}
