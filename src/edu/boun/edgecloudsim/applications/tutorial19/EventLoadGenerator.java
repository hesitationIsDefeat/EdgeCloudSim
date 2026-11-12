/*
 * Title:        EdgeCloudSim - tutorial19 load generator
 *
 * Description:
 * Requests made because of world events: while an event is active, a user inside its radius
 * requests what the event is about. Optionally that is ALL the users request.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.applications.tutorial19;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.math3.distribution.ExponentialDistribution;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.events.WorldEvent;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.task_generator.IdleActiveStreamLoadGenerator;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.TaskProperty;

/**
 * ONAT: Adds the requests of the world events to the requests every device makes
 * ({@link IdleActiveStreamLoadGenerator}). For every event, every user makes requests of the
 * event's type as a Poisson process with the event's mean inter-arrival time from the event's
 * start to its end - but a request only counts if the user is inside the event's radius at
 * that moment (the position comes from the mobility model, which therefore has to be
 * initialized first - SimManager does that). So the crowd the event drew (see
 * {@link EventMobilityModel}) is what requests, and users who merely pass through its radius
 * request as well.
 * <p>
 * With event_requests_only=true the users make NO other request: the usage_percentage /
 * active / idle settings of the applications and streams are not used at all (and need not add
 * up to 100) - outside the events nobody requests anything, and inside one only that event's
 * request is made. With false, the normal requests continue and the events' come on top.
 * <p>
 * The events are also written to the EVENTS.log (see SimLogger.addEventLog), so they can be
 * drawn next to the users and UAVs.
 */
public class EventLoadGenerator extends IdleActiveStreamLoadGenerator {
	private final List<WorldEvent> events;
	private final MobilityModel mobilityModel;
	private final boolean eventRequestsOnly;

	/**
	 * @param events the world events
	 * @param mobilityModel already initialized (see class comment)
	 */
	public EventLoadGenerator(int _numberOfMobileDevices, double _simulationTime, String _simScenario,
			List<WorldEvent> events, MobilityModel mobilityModel) {
		super(_numberOfMobileDevices, _simulationTime, _simScenario);
		this.events = new ArrayList<WorldEvent>(events);
		this.mobilityModel = mobilityModel;
		this.eventRequestsOnly = SimSettings.getInstance().isEventRequestsOnly();
	}

	/** The applications events request directly need distributions as well. */
	@Override
	protected boolean[] getSampledApplications() {
		SimSettings settings = SimSettings.getInstance();
		boolean[] sampled = super.getSampledApplications();
		for (WorldEvent event : events) {
			if (!settings.isStreamTaskType(event.getRequestTaskType()))
				sampled[event.getRequestTaskType()] = true;
		}
		return sampled;
	}

	@Override
	public void initializeModel() {
		if (eventRequestsOnly) {
			taskList = new ArrayList<TaskProperty>();
			expRngList = createDistributions(getSampledApplications());
		} else {
			super.initializeModel();
		}

		for (int e = 0; e < events.size(); e++) {
			addEventRequests(events.get(e));
			SimLogger.getInstance().addEventLog(events.get(e).toLogLine(e));
		}
	}

	private void addEventRequests(WorldEvent event) {
		SimSettings settings = SimSettings.getInstance();
		double endTime = Math.min(event.getEndTime(), simulationTime);
		ExponentialDistribution interarrival = new ExponentialDistribution(event.getRequestInterarrival());

		for (int deviceId = 0; deviceId < numberOfMobileDevices; deviceId++) {
			double time = event.getStartTime();
			while (true) {
				time += interarrival.sample();
				if (time >= endTime)
					break;

				Location location = mobilityModel.getLocation(deviceId, time);
				if (!event.isInsideRadius(location.getXPos(), location.getYPos()))
					continue;

				if (settings.isStreamTaskType(event.getRequestTaskType()))
					taskList.add(createStreamRequest(settings, deviceId, settings.getStreamIndex(event.getRequestTaskType()), time, expRngList));
				else
					taskList.add(new TaskProperty(deviceId, event.getRequestTaskType(), time, expRngList));
			}
		}
	}

	/** Without event_requests_only a device has the request type its normal requests are of; otherwise it has none (-1). */
	@Override
	public int getTaskTypeOfDevice(int deviceId) {
		return eventRequestsOnly ? -1 : super.getTaskTypeOfDevice(deviceId);
	}
}
