/*
 * Title:        EdgeCloudSim - Service Demand Tracker
 *
 * Description:
 * Tells the service-aware UAV mobility policies where each service is being requested right
 * now: the requests that were made in the last service_demand_window seconds (and, optionally,
 * the ones that will be made in the next service_demand_lookahead seconds), each as a weighted
 * point at the position of the device that made it, labelled with the service it needs.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.mobility.uav;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * ONAT: Demand of the services, read from the load generator's pre-generated request list
 * (every request of the whole simulation is known up front, see LoadGeneratorModel). The
 * default view - the last service_demand_window seconds - only contains requests that have
 * already been made, which is what a real controller could have observed (a request counts
 * whether or not it was served, so a service nobody hosts nearby still shows up); the optional
 * lookahead also reads requests that are about to be made, an oracle for comparison.
 * <p>
 * A request needs one service if it is for an application, and one per node if it is for a
 * stream ({@link SimSettings#getServiceIndicesOfTaskType}) - a stream therefore shows up as
 * demand for each of its services at the position of the device requesting it. The
 * services are numbered by their slot, the position in {@link #getServiceIndices()}.
 */
public class ServiceDemandTracker {
	/** The demand around one moment: parallel arrays, one entry per (request, needed service). */
	public static class Demand {
		public final double[] x;
		public final double[] y;
		/** Priority of the device that made the request (MobilityModel.getPriority), 1.0 normally. */
		public final double[] weight;
		/** Slot (see ServiceDemandTracker.getServiceIndices()) of the service the entry needs. */
		public final int[] serviceSlot;

		private Demand(int size) {
			this.x = new double[size];
			this.y = new double[size];
			this.weight = new double[size];
			this.serviceSlot = new int[size];
		}

		public int size() {
			return x.length;
		}
	}

	private static class Entry {
		private final double time;
		private final int deviceId;
		private final int serviceSlot;

		private Entry(double time, int deviceId, int serviceSlot) {
			this.time = time;
			this.deviceId = deviceId;
			this.serviceSlot = serviceSlot;
		}
	}

	private final int[] serviceIndices;
	private final double[] times;
	private final int[] deviceIds;
	private final int[] serviceSlots;

	/**
	 * @param requests every request of the simulation (LoadGeneratorModel.getTaskList()), in any order
	 */
	public ServiceDemandTracker(List<TaskProperty> requests) {
		SimSettings settings = SimSettings.getInstance();
		this.serviceIndices = settings.getDistinctServiceIndices();

		Map<Integer, Integer> slotOfService = new HashMap<Integer, Integer>();
		for (int slot = 0; slot < serviceIndices.length; slot++) {
			slotOfService.put(serviceIndices[slot], slot);
		}

		List<Entry> entries = new ArrayList<Entry>();
		for (TaskProperty request : requests) {
			for (int service : settings.getServiceIndicesOfTaskType(request.getTaskType())) {
				entries.add(new Entry(request.getStartTime(), request.getMobileDeviceId(), slotOfService.get(service)));
			}
		}
		entries.sort(Comparator.comparingDouble(entry -> entry.time));

		this.times = new double[entries.size()];
		this.deviceIds = new int[entries.size()];
		this.serviceSlots = new int[entries.size()];
		for (int i = 0; i < entries.size(); i++) {
			times[i] = entries.get(i).time;
			deviceIds[i] = entries.get(i).deviceId;
			serviceSlots[i] = entries.get(i).serviceSlot;
		}
	}

	/** @return the distinct service_index values, in the order their slots are numbered */
	public int[] getServiceIndices() {
		return serviceIndices;
	}

	/**
	 * The demand at the given moment: every entry whose request was made after now - window and
	 * up to now + lookahead, at the position the requesting device has NOW (that is where the
	 * demand is, and where a UAV has to be), skipping devices that are not part of the
	 * scenario yet.
	 *
	 * @param now current simulation time (seconds)
	 * @param window how far back to look (seconds)
	 * @param lookahead how far ahead to look as well (seconds), 0 = only requests already made
	 */
	public Demand snapshot(double now, double window, double lookahead, MobilityModel mobilityModel) {
		int from = firstIndexAfter(now - window);
		int to = firstIndexAfter(now + lookahead);

		int count = 0;
		int[] kept = new int[Math.max(0, to - from)];
		for (int i = from; i < to; i++) {
			if (mobilityModel.isActive(deviceIds[i], now))
				kept[count++] = i;
		}

		Demand demand = new Demand(count);
		for (int k = 0; k < count; k++) {
			int i = kept[k];
			Location location = mobilityModel.getLocation(deviceIds[i], now);
			demand.x[k] = location.getXPos();
			demand.y[k] = location.getYPos();
			demand.weight[k] = mobilityModel.getPriority(deviceIds[i], now);
			demand.serviceSlot[k] = serviceSlots[i];
		}
		return demand;
	}

	/** Index of the first entry with a time strictly after the given one (times is sorted). */
	private int firstIndexAfter(double time) {
		int low = 0;
		int high = times.length;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (times[middle] <= time)
				low = middle + 1;
			else
				high = middle;
		}
		return low;
	}
}
