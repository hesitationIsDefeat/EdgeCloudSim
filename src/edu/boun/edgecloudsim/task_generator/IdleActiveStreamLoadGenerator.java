/*
 * Title:        EdgeCloudSim - Idle/Active Stream Load Generator implementation
 *
 * Description:
 * IdleActiveStreamLoadGenerator is IdleActiveLoadGenerator for scenarios that also define
 * STREAMS (streams.xml): DAGs whose nodes are applications. Every mobile device is assigned
 * ONE request type - either a plain application or a stream - by drawing from the
 * usage_percentage of the applications and of the streams together, then alternates active
 * (Poisson request arrivals) and idle periods using THAT type's own timing: an application's
 * values from applications.xml, or a stream's values from streams.xml.
 * An application can therefore be requested on its own (by the devices assigned to it) and
 * also be a node of the streams other devices request; as a node it only contributes its
 * workload (task length, data sizes, ...), never its own usage_percentage/active/idle values.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.task_generator;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.distribution.ExponentialDistribution;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import edu.boun.edgecloudsim.utils.StreamTaskProperty;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * Load generator for scenarios mixing plain applications and streams. With no streams
 * defined it behaves like {@link IdleActiveLoadGenerator}.
 *
 * @see StreamTaskProperty
 */
public class IdleActiveStreamLoadGenerator extends LoadGeneratorModel {
	/** The request type (application or stream task type) assigned to each mobile device */
	private int taskTypeOfDevices[];

	public IdleActiveStreamLoadGenerator(int _numberOfMobileDevices, double _simulationTime, String _simScenario) {
		super(_numberOfMobileDevices, _simulationTime, _simScenario);
	}

	@Override
	public void initializeModel() {
		SimSettings SS = SimSettings.getInstance();
		int numOfApplications = SS.getNumOfApplications();
		int numOfTaskTypes = SS.getNumOfTaskTypes();

		validateUsagePercentages(SS, numOfTaskTypes);

		taskList = new ArrayList<TaskProperty>();

		// An application is only sampled if some device can request it on its own or some
		// stream runs it as a node - an application used by neither never needs generators
		boolean[] sampled = new boolean[numOfApplications];
		for (int a = 0; a < numOfApplications; a++) {
			sampled[a] = SS.getTaskLookUpTable()[a][0] != 0;
		}
		for (int s = 0; s < SS.getNumOfStreams(); s++) {
			for (int[] layer : SS.getStreamNodeApplications(s)) {
				for (int application : layer) {
					sampled[application] = true;
				}
			}
		}

		// [application][0] = input size distribution, [1] = output size, [2] = task length
		ExponentialDistribution[][] expRngList = new ExponentialDistribution[numOfApplications][3];
		for (int a = 0; a < numOfApplications; a++) {
			if (!sampled[a])
				continue;
			expRngList[a][0] = new ExponentialDistribution(SS.getTaskLookUpTable()[a][5]);
			expRngList[a][1] = new ExponentialDistribution(SS.getTaskLookUpTable()[a][6]);
			expRngList[a][2] = new ExponentialDistribution(SS.getTaskLookUpTable()[a][7]);
		}

		taskTypeOfDevices = new int[numberOfMobileDevices];
		for (int i = 0; i < numberOfMobileDevices; i++) {
			int randomTaskType = selectTaskType(SS, numOfTaskTypes);
			if (randomTaskType == -1) {
				SimLogger.printLine("Critical Error: No valid task type assigned to device " + i + "!");
				continue;
			}
			taskTypeOfDevices[i] = randomTaskType;

			boolean isStream = SS.isStreamTaskType(randomTaskType);
			int streamIndex = isStream ? SS.getStreamIndex(randomTaskType) : -1;
			double poissonMean = isStream ? SS.getStreamPoissonInterarrival(streamIndex)
					: SS.getTaskLookUpTable()[randomTaskType][2];
			double activePeriod = isStream ? SS.getStreamActivePeriod(streamIndex)
					: SS.getTaskLookUpTable()[randomTaskType][3];
			double idlePeriod = isStream ? SS.getStreamIdlePeriod(streamIndex)
					: SS.getTaskLookUpTable()[randomTaskType][4];

			// Randomize the first active period so devices are not synchronized
			double activePeriodStartTime = SimUtils.getRandomDoubleNumber(
					SimSettings.CLIENT_ACTIVITY_START_TIME,
					SimSettings.CLIENT_ACTIVITY_START_TIME + activePeriod);
			double virtualTime = activePeriodStartTime;

			ExponentialDistribution rng = new ExponentialDistribution(poissonMean);

			while (virtualTime < simulationTime) {
				double interval = rng.sample();

				if (interval <= 0) {
					SimLogger.printLine("Warning: Invalid interval " + interval + " for device " + i + " at time " + virtualTime);
					continue;
				}

				virtualTime += interval;

				// Past the active period: skip to the start of the next one
				if (virtualTime > activePeriodStartTime + activePeriod) {
					activePeriodStartTime = activePeriodStartTime + activePeriod + idlePeriod;
					virtualTime = activePeriodStartTime;
					continue;
				}

				if (isStream)
					taskList.add(createStreamRequest(SS, i, streamIndex, virtualTime, expRngList));
				else
					taskList.add(new TaskProperty(i, randomTaskType, virtualTime, expRngList));
			}
		}
	}

	/**
	 * A stream's own timing/usage decide how often it is requested; every node then gets its
	 * workload sampled exactly like a standalone request of the node's application would
	 * (including that application's partitioning, if it is partitionable).
	 */
	private StreamTaskProperty createStreamRequest(SimSettings SS, int deviceId, int streamIndex,
			double startTime, ExponentialDistribution[][] expRngList) {
		int[][] nodeApplications = SS.getStreamNodeApplications(streamIndex);
		List<List<TaskProperty>> nodeLayers = new ArrayList<List<TaskProperty>>(nodeApplications.length);
		for (int[] layer : nodeApplications) {
			List<TaskProperty> nodes = new ArrayList<TaskProperty>(layer.length);
			for (int application : layer) {
				nodes.add(new TaskProperty(deviceId, application, startTime, expRngList));
			}
			nodeLayers.add(nodes);
		}
		return new StreamTaskProperty(startTime, deviceId, streamIndex, nodeLayers);
	}

	/** Weighted random pick among every application followed by every stream. */
	private int selectTaskType(SimSettings SS, int numOfTaskTypes) {
		double taskTypeSelector = SimUtils.getRandomDoubleNumber(0, 100);
		double taskTypePercentage = 0;
		for (int t = 0; t < numOfTaskTypes; t++) {
			taskTypePercentage += getUsagePercentage(SS, t);
			if (taskTypeSelector <= taskTypePercentage)
				return t;
		}
		return -1;
	}

	private double getUsagePercentage(SimSettings SS, int taskType) {
		if (SS.isStreamTaskType(taskType))
			return SS.getStreamUsagePercentage(SS.getStreamIndex(taskType));
		return SS.getTaskLookUpTable()[taskType][0];
	}

	/**
	 * Devices are split between applications and streams, so the two usage_percentage
	 * families have to share one 100% - otherwise devices would silently be left without a
	 * request type (or the shares would not mean what the files say).
	 */
	private void validateUsagePercentages(SimSettings SS, int numOfTaskTypes) {
		double total = 0;
		for (int t = 0; t < numOfTaskTypes; t++) {
			total += getUsagePercentage(SS, t);
		}
		if (Math.abs(total - 100.0) > 1e-6) {
			SimLogger.printLine("The usage_percentage of the applications (applications.xml) and the streams (streams.xml) must add up to 100, but they add up to "
					+ total + ". Terminating simulation...");
			System.exit(1);
		}
	}

	@Override
	public int getTaskTypeOfDevice(int deviceId) {
		return taskTypeOfDevices[deviceId];
	}
}
