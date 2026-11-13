/*
 * Title:        EdgeCloudSim - World Event
 *
 * Description:
 * A WorldEvent is something that happens at a place and time of the simulated world (a fire, a
 * concert, an accident, ...) and makes the people around it ask for a specific service: for
 * the duration of the event, a user inside its radius sends the event's request - an
 * application or a stream from applications.xml/streams.xml - at the event's own rate.
 * The event can also attract the users around it (see the event_gather setting), which is what
 * makes UAVs that follow the demand move there.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.events;

import java.util.LinkedHashSet;
import java.util.Set;

import edu.boun.edgecloudsim.core.SimSettings;

/**
 * Immutable description of one world event. Distances are in meters, times in simulation
 * seconds. Create the events of a scenario once the settings are loaded, since the request
 * name is resolved against applications.xml/streams.xml right away.
 */
public class WorldEvent {
	private final String name;
	private final double startTime;
	private final double endTime;
	private final double x;
	private final double y;
	private final double radius;
	private final double attractionRadius;
	private final double participation;
	private final String requestName;
	private final double requestInterarrival;
	private final int requestTaskType;

	/**
	 * @param name label of the event (shown in the EVENTS.log and the heat-map video)
	 * @param startTime when the event starts (seconds)
	 * @param endTime when the event ends (seconds), after startTime
	 * @param x event center, x position (m)
	 * @param y event center, y position (m)
	 * @param radius users inside this distance of the center request while the event is active
	 * @param attractionRadius users up to this far from the center when the event starts may
	 *        walk to it (only if gathering is enabled); at least radius
	 * @param participation probability in [0, 1] that such a user actually goes
	 * @param requestName the application (applications.xml) or stream (streams.xml) the event's
	 *        users request
	 * @param requestInterarrival mean seconds between two requests of one user inside the radius
	 * @throws IllegalArgumentException if a value is out of range or requestName is unknown
	 */
	public WorldEvent(String name, double startTime, double endTime, double x, double y,
			double radius, double attractionRadius, double participation,
			String requestName, double requestInterarrival) {
		if (endTime <= startTime)
			throw new IllegalArgumentException("Event '" + name + "': end time must be after its start time");
		if (radius <= 0)
			throw new IllegalArgumentException("Event '" + name + "': radius must be positive");
		if (attractionRadius < radius)
			throw new IllegalArgumentException("Event '" + name + "': attraction radius must be at least its radius");
		if (participation < 0 || participation > 1)
			throw new IllegalArgumentException("Event '" + name + "': participation must be in [0, 1]");
		if (requestInterarrival <= 0)
			throw new IllegalArgumentException("Event '" + name + "': request interarrival must be positive");

		int taskType = SimSettings.getInstance().getTaskTypeByName(requestName);
		if (taskType < 0)
			throw new IllegalArgumentException("Event '" + name + "': unknown application/stream '" + requestName + "'");

		this.name = name;
		this.startTime = startTime;
		this.endTime = endTime;
		this.x = x;
		this.y = y;
		this.radius = radius;
		this.attractionRadius = attractionRadius;
		this.participation = participation;
		this.requestName = requestName;
		this.requestInterarrival = requestInterarrival;
		this.requestTaskType = taskType;
	}

	public String getName() {
		return name;
	}

	public double getStartTime() {
		return startTime;
	}

	public double getEndTime() {
		return endTime;
	}

	public double getX() {
		return x;
	}

	public double getY() {
		return y;
	}

	public double getRadius() {
		return radius;
	}

	public double getAttractionRadius() {
		return attractionRadius;
	}

	public double getParticipation() {
		return participation;
	}

	public String getRequestName() {
		return requestName;
	}

	public double getRequestInterarrival() {
		return requestInterarrival;
	}

	/** @return the task type (application, or stream - see SimSettings.getStreamTaskType()) the event's users request */
	public int getRequestTaskType() {
		return requestTaskType;
	}

	/** @return true from the start time (inclusive) until the end time (exclusive) */
	public boolean isActiveAt(double time) {
		return time >= startTime && time < endTime;
	}

	public double distanceTo(double posX, double posY) {
		return Math.hypot(posX - x, posY - y);
	}

	/** @return true if the position is within the radius inside which users request */
	public boolean isInsideRadius(double posX, double posY) {
		return distanceTo(posX, posY) <= radius;
	}

	/** @return true if the position is within the distance users may be drawn to the event from */
	public boolean isInsideAttractionRadius(double posX, double posY) {
		return distanceTo(posX, posY) <= attractionRadius;
	}

	/**
	 * One line of the EVENTS.log: index;name;start;end;x;y;radius;attractionRadius;request;services -
	 * services being the distinct service_index values the request needs, comma separated.
	 */
	public String toLogLine(int index) {
		Set<Integer> services = new LinkedHashSet<Integer>();
		for (int service : SimSettings.getInstance().getServiceIndicesOfTaskType(requestTaskType)) {
			services.add(service);
		}
		StringBuilder serviceList = new StringBuilder();
		for (int service : services) {
			if (serviceList.length() > 0)
				serviceList.append(',');
			serviceList.append(service);
		}

		String d = SimSettings.DELIMITER;
		return index + d + name + d + startTime + d + endTime + d + x + d + y + d + radius + d
				+ attractionRadius + d + requestName + d + serviceList;
	}
}
