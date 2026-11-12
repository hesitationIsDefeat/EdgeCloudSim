/*
 * Title:        EdgeCloudSim - tutorial19 mobility model
 *
 * Description:
 * Users walk at random until a world event starts near them; if gathering is enabled, some of
 * them then walk to the event, stay inside its radius while it lasts and walk back home once it
 * is over.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.applications.tutorial19;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.events.WorldEvent;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * ONAT: Like tutorial6's {@code SampleMobilityModel} - every user starts at a random place and
 * walks in a random direction every 3 s (2 m/s) - plus the pull of the world events:
 * <ol>
 *   <li>The first step at or after an event's start time, a user that is not already busy with
 *   another event, stands within the event's attraction radius and is lucky (probability =
 *   the event's participation) is drawn to it: it picks a point within 70% of the event's radius
 *   and walks there at event_gather_speed.</li>
 *   <li>Once there it keeps wandering at walking speed but never leaves 90% of the radius, so
 *   it stays inside the area where users request (see EventLoadGenerator).</li>
 *   <li>When the event ends the user walks straight back to the place it started from, at the
 *   same speed, and resumes the random walk there.</li>
 * </ol>
 * With gathering disabled this is just the random walk (events then only get the users that
 * happen to be inside their radius). The whole timeline of every user is generated up front, so
 * the model can answer the position of any user at any time (and a load generator can ask it
 * where a user is when it generates that user's requests).
 */
public class EventMobilityModel extends MobilityModel {
	// Seconds between two position updates and the random walk speed, as in tutorial6's SampleMobilityModel
	private static final double STEP = 3.0;
	private static final double WALK_SPEED = 2.0;
	// Where in an event's radius gathering users head for, and how far from the center they may wander
	private static final double TARGET_RADIUS_FRACTION = 0.7;
	private static final double HOVER_RADIUS_FRACTION = 0.9;

	private final List<WorldEvent> events;
	private final boolean gather;
	private final double gatherSpeed;

	private List<TreeMap<Double, Location>> timelines;

	/**
	 * @param events the world events users react to (order does not matter)
	 * @param gather whether users walk to the events
	 * @param gatherSpeed speed (m/s) of a user walking to or from an event, positive when gathering
	 */
	public EventMobilityModel(int _numberOfMobileDevices, double _simulationTime,
			List<WorldEvent> events, boolean gather, double gatherSpeed) {
		super(_numberOfMobileDevices, _simulationTime);
		if (gather && gatherSpeed <= 0)
			throw new IllegalArgumentException("event_gather_speed must be positive when users gather at events");
		this.events = new ArrayList<WorldEvent>(events);
		this.events.sort((a, b) -> Double.compare(a.getStartTime(), b.getStartTime()));
		this.gather = gather;
		this.gatherSpeed = gatherSpeed;
	}

	@Override
	public void initialize() {
		timelines = new ArrayList<TreeMap<Double, Location>>(numberOfMobileDevices);
		for (int deviceId = 0; deviceId < numberOfMobileDevices; deviceId++) {
			timelines.add(generateTimeline());
		}
	}

	private TreeMap<Double, Location> generateTimeline() {
		SimSettings settings = SimSettings.getInstance();
		double west = settings.getWesternBound();
		double east = settings.getEasternBound();
		double south = settings.getSouthernBound();
		double north = settings.getNorthernBound();

		TreeMap<Double, Location> timeline = new TreeMap<Double, Location>();

		double x = SimUtils.getRandomNumber((int) west, (int) east);
		double y = SimUtils.getRandomNumber((int) south, (int) north);
		final double homeX = x;
		final double homeY = y;
		timeline.put(SimSettings.CLIENT_ACTIVITY_START_TIME, toLocation(x, y));

		WorldEvent attending = null; // the event this user is at / on the way to
		double targetX = 0;
		double targetY = 0;
		boolean arrived = false; // reached its point in the event's radius (wanders around it from then on)
		boolean returningHome = false;
		boolean[] considered = new boolean[events.size()];

		double time = SimSettings.CLIENT_ACTIVITY_START_TIME;
		while (time < simulationTime) {
			time += STEP;

			if (gather) {
				if (attending != null && time >= attending.getEndTime()) {
					attending = null;
					returningHome = true;
				}

				// An event is considered once, at the first step inside its time window at which the
				// user is free - the user either goes or never does
				if (attending == null) {
					for (int e = 0; e < events.size(); e++) {
						WorldEvent event = events.get(e);
						if (considered[e] || !event.isActiveAt(time))
							continue;
						considered[e] = true;

						if (event.isInsideAttractionRadius(x, y) && SimUtils.RNG.nextDouble() < event.getParticipation()) {
							attending = event;
							arrived = false;
							returningHome = false;
							double angle = SimUtils.RNG.nextDouble() * 2 * Math.PI;
							double offset = Math.sqrt(SimUtils.RNG.nextDouble()) * TARGET_RADIUS_FRACTION * event.getRadius();
							targetX = event.getX() + offset * Math.cos(angle);
							targetY = event.getY() + offset * Math.sin(angle);
							break;
						}
					}
				}
			}

			double[] next;
			if (attending != null && !arrived) {
				next = moveTowards(x, y, targetX, targetY, gatherSpeed * STEP);
				arrived = next[0] == targetX && next[1] == targetY;
			} else if (attending != null) {
				next = hover(x, y, attending);
			} else if (returningHome) {
				next = moveTowards(x, y, homeX, homeY, gatherSpeed * STEP);
				returningHome = !(next[0] == homeX && next[1] == homeY);
			} else {
				next = randomStep(x, y, WALK_SPEED * STEP);
			}

			x = Math.max(west, Math.min(east, next[0]));
			y = Math.max(south, Math.min(north, next[1]));
			timeline.put(time, toLocation(x, y));
		}

		return timeline;
	}

	/** One step of at most maxStep meters from (x, y) straight to (targetX, targetY); lands on it if it is close enough. */
	private static double[] moveTowards(double x, double y, double targetX, double targetY, double maxStep) {
		double distance = Math.hypot(targetX - x, targetY - y);
		if (distance <= maxStep)
			return new double[] { targetX, targetY };
		return new double[] { x + (targetX - x) / distance * maxStep, y + (targetY - y) / distance * maxStep };
	}

	private static double[] randomStep(double x, double y, double stepLength) {
		double angle = SimUtils.RNG.nextDouble() * 2 * Math.PI;
		return new double[] { x + stepLength * Math.cos(angle), y + stepLength * Math.sin(angle) };
	}

	/** A random walk step that is pulled back inside HOVER_RADIUS_FRACTION of the event's radius if it left it. */
	private static double[] hover(double x, double y, WorldEvent event) {
		double[] next = randomStep(x, y, WALK_SPEED * STEP);
		double limit = HOVER_RADIUS_FRACTION * event.getRadius();
		double distance = event.distanceTo(next[0], next[1]);
		if (distance > limit) {
			next[0] = event.getX() + (next[0] - event.getX()) / distance * limit;
			next[1] = event.getY() + (next[1] - event.getY()) / distance * limit;
		}
		return next;
	}

	private static Location toLocation(double x, double y) {
		return new Location(0, 0, (int) Math.round(x), (int) Math.round(y));
	}

	@Override
	public Location getLocation(int deviceId, double time) {
		Map.Entry<Double, Location> entry = timelines.get(deviceId).floorEntry(time);

		if (entry == null) {
			SimLogger.printLine("impossible is occurred! no location is found for the device '" + deviceId + "' at " + time);
			System.exit(1);
		}

		return entry.getValue();
	}
}
