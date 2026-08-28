package edu.boun.edgecloudsim.applications.tutorial10;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.math3.distribution.ExponentialDistribution;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * ONAT:
 * Population-aware load generator for tutorial10 (identical to tutorial8's). Behaves like
 * {@link edu.boun.edgecloudsim.task_generator.IdleActiveLoadGenerator}, but
 * separates devices into two independent populations that each pick their
 * application only from their own reserved app subset:
 * - Normal users, ids [0, numOfNormalUsers): pick from every application NOT
 *   listed in {@code sar_application_names} (e.g. TEXT_MESSAGE, PHOTO_MESSAGE).
 * - SAR members, ids [numOfNormalUsers, numberOfMobileDevices): pick only
 *   from {@code sar_application_names} (e.g. DISASTER_MAP_FUSION,
 *   SITUATION_AWARENESS_ALERTS), and only start generating tasks after
 *   {@code sarEntryTime} - they haven't joined the scenario before that.
 *
 * Usage percentages in applications.xml are normalized independently within
 * each population's own app subset, not globally across all apps.
 */
public class SARAwareLoadGenerator extends LoadGeneratorModel {
    private final int numOfNormalUsers;
    private final double sarEntryTime;

    private int[] taskTypeOfDevices;

    public SARAwareLoadGenerator(int numOfNormalUsers, int numOfSarMembers, double simulationTime,
                                  String simScenario, double sarEntryTime) {
        super(numOfNormalUsers + numOfSarMembers, simulationTime, simScenario);
        this.numOfNormalUsers = numOfNormalUsers;
        this.sarEntryTime = sarEntryTime;
    }

    @Override
    public void initializeModel() {
        taskList = new ArrayList<TaskProperty>();
        taskTypeOfDevices = new int[numberOfMobileDevices];

        double[][] taskLookUpTable = SimSettings.getInstance().getTaskLookUpTable();
        Set<String> sarAppNames = new HashSet<>(Arrays.asList(SimSettings.getInstance().getSarApplicationNames()));

        ExponentialDistribution[][] expRngList = new ExponentialDistribution[taskLookUpTable.length][3];
        for (int i = 0; i < taskLookUpTable.length; i++) {
            if (taskLookUpTable[i][0] == 0)
                continue;
            expRngList[i][0] = new ExponentialDistribution(taskLookUpTable[i][5]);
            expRngList[i][1] = new ExponentialDistribution(taskLookUpTable[i][6]);
            expRngList[i][2] = new ExponentialDistribution(taskLookUpTable[i][7]);
        }

        int[] normalAppIndices = collectAppIndices(taskLookUpTable, sarAppNames, false);
        int[] sarAppIndices = collectAppIndices(taskLookUpTable, sarAppNames, true);

        // Normal users start immediately, like the plain IdleActiveLoadGenerator
        for (int i = 0; i < numOfNormalUsers; i++)
            generateTasksForDevice(i, normalAppIndices, taskLookUpTable, expRngList, SimSettings.CLIENT_ACTIVITY_START_TIME);

        // SAR members only start generating tasks once they enter the scenario
        for (int i = numOfNormalUsers; i < numberOfMobileDevices; i++)
            generateTasksForDevice(i, sarAppIndices, taskLookUpTable, expRngList, sarEntryTime);
    }

    /** Collects app indices belonging to the SAR group (or, if !sarGroup, everything else). */
    private int[] collectAppIndices(double[][] taskLookUpTable, Set<String> sarAppNames, boolean sarGroup) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < taskLookUpTable.length; i++) {
            if (taskLookUpTable[i][0] == 0)
                continue;
            boolean isSarApp = sarAppNames.contains(SimSettings.getInstance().getTaskName(i));
            if (isSarApp == sarGroup)
                indices.add(i);
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = indices.get(i);
        return result;
    }

    private void generateTasksForDevice(int deviceId, int[] candidateAppIndices, double[][] taskLookUpTable,
                                         ExponentialDistribution[][] expRngList, double earliestStartTime) {
        if (candidateAppIndices.length == 0) {
            SimLogger.printLine("Critical Error: No application is reserved for device " + deviceId + "!");
            return;
        }

        int taskType = selectTaskType(candidateAppIndices, taskLookUpTable);
        taskTypeOfDevices[deviceId] = taskType;

        double poissonMean = taskLookUpTable[taskType][2];
        double activePeriod = taskLookUpTable[taskType][3];
        double idlePeriod = taskLookUpTable[taskType][4];

        double activePeriodStartTime = SimUtils.getRandomDoubleNumber(earliestStartTime, earliestStartTime + activePeriod);
        double virtualTime = activePeriodStartTime;

        ExponentialDistribution rng = new ExponentialDistribution(poissonMean);

        while (virtualTime < simulationTime) {
            double interval = rng.sample();
            if (interval <= 0)
                continue;

            virtualTime += interval;

            if (virtualTime > activePeriodStartTime + activePeriod) {
                activePeriodStartTime = activePeriodStartTime + activePeriod + idlePeriod;
                virtualTime = activePeriodStartTime;
                continue;
            }

            taskList.add(new TaskProperty(deviceId, taskType, virtualTime, expRngList));
        }
    }

    /** Weighted random selection restricted to the given candidate application indices. */
    private int selectTaskType(int[] candidateAppIndices, double[][] taskLookUpTable) {
        double totalWeight = 0;
        for (int index : candidateAppIndices)
            totalWeight += taskLookUpTable[index][0];

        double selector = SimUtils.getRandomDoubleNumber(0, totalWeight);
        double cumulative = 0;
        for (int index : candidateAppIndices) {
            cumulative += taskLookUpTable[index][0];
            if (selector <= cumulative)
                return index;
        }

        return candidateAppIndices[candidateAppIndices.length - 1];
    }

    @Override
    public int getTaskTypeOfDevice(int deviceId) {
        return taskTypeOfDevices[deviceId];
    }
}
