/*
 * Title:        EdgeCloudSim - Custom Space Shared Cloudlet Scheduler
 *
 * Description:
 * CloudletSchedulerSpaceShared whose finish estimate for a cloudlet that starts
 * running on submit is never shorter than CloudSim's own event spacing, so that
 * very short tasks cannot get stuck in the VM's exec list.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.core;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.core.CloudSim;

/**
 * ONAT: Datacenter.processCloudletSubmit() first runs updateCloudletProcessing() and then
 * schedules the finish event of a cloudlet that starts running right away at exactly the
 * scheduler's estimated finish time. But updateCloudletProcessing() silently skips any
 * update requested less than CloudSim.getMinTimeBetweenEvents() after the previous one, and
 * the update that the submit just ran counts as that previous one. A task whose service time
 * is shorter than that window (< 0.01 s with the 0.01 granularity every MainApp uses) therefore
 * has its finish event dropped and stays in the exec list - still counted by the
 * orchestrators' admission check - until some later submit happens to trigger another update.
 * On a VM that admits one task at a time (e.g. vm_utilization_on_edge > 50 on a 1-core UAV VM)
 * nothing else is ever admitted, so the VM stays blocked for the rest of the run; on busier VMs
 * it only delays that task's completion by an arbitrary amount.
 *
 * Flooring the estimate just above that window moves the event out of it. Any other update
 * that happens in between re-arms the next finish event itself, so the floor only needs to
 * cover the submit-time event. Only tasks with a service time below the floor are affected,
 * and only by completing (at most ~0.011 s) later.
 */
public class CloudletSchedulerSpaceShared_Custom extends CloudletSchedulerSpaceShared {
	// Margin above CloudSim.getMinTimeBetweenEvents() that keeps the event clear of the
	// skipped window regardless of floating point rounding
	private static final double FINISH_EVENT_MARGIN = 0.001;

	@Override
	public double cloudletSubmit(Cloudlet cl, double fileTransferTime) {
		double estimatedFinishTime = super.cloudletSubmit(cl, fileTransferTime);

		// 0 means the cloudlet was queued behind a running one - no finish event is scheduled for it
		if (estimatedFinishTime > 0.0)
			return Math.max(estimatedFinishTime, CloudSim.getMinTimeBetweenEvents() + FINISH_EVENT_MARGIN);

		return estimatedFinishTime;
	}
}
