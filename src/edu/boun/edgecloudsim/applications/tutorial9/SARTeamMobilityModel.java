package edu.boun.edgecloudsim.applications.tutorial9;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * ONAT:
 * SAR (Search &amp; Rescue) team mobility model (identical to tutorial8's).
 *
 * SAR members are grouped into fixed, non-overlapping teams of {@code teamSize}
 * members (4 by default). Before {@code entryTime} every SAR member sits at a
 * fixed staging point outside the area of interest - they have not entered the
 * scenario yet. From {@code entryTime} onwards, each team's reference point
 * alternates between:
 * - MOVE phase: picks a new target between {@code MIN_MOVE_DISTANCE} and
 *   {@code MAX_MOVE_DISTANCE} meters away and walks straight towards it at
 *   {@code moveSpeed}, stepping every few seconds like the other crowd mobility
 *   models ({@code moveDuration} only acts as a safety cap on this phase).
 * - STOP phase ({@code stopDuration} seconds): the team holds its position.
 *
 * Every member of a team follows the team's reference point, offset by a
 * small fixed formation vector so the team stays visibly clustered together.
 */
public class SARTeamMobilityModel extends MobilityModel {

    // ONAT: Seconds between position updates during the MOVE phase, same cadence used
    // by the other crowd mobility models (SampleMobilityModel / ConvergingMobilityModel).
    private static final double STEP_INTERVAL = 3.0;

    // ONAT: Each MOVE phase picks a new target this far away (meters) from the team's
    // current position.
    private static final double MIN_MOVE_DISTANCE = 50.0;
    private static final double MAX_MOVE_DISTANCE = 100.0;

    // ONAT: Small, fixed formation offsets (meters) applied around the team's reference
    // point so members of the same team stay visibly clustered. Cycled if the configured
    // team size differs from 6.
    private static final int[][] FORMATION_OFFSETS = {
            {0, 0}, {15, 0}, {0, 15}, {15, 15}, {-15, 0}, {0, -15},
    };

    private final int teamSize;
    private final double entryTime;
    private final double moveDuration;
    private final double stopDuration;
    private final double moveSpeed;
    private final double priorityFactor;

    private List<TreeMap<Double, Location>> treeMapArray;

    /**
     * @param numberOfSarMembers Total number of SAR members to model
     * @param simulationTime Duration of the simulation in seconds
     * @param teamSize Number of members per fixed team
     * @param entryTime Simulation time (seconds) at which SAR teams enter the scenario
     * @param moveDuration Safety cap (seconds) on how long a single MOVE phase may take
     * @param stopDuration Duration (seconds) of each STOP phase
     * @param moveSpeed Movement speed (m/s) during the MOVE phase
     * @param priorityFactor Relative weight of a SAR member vs. a normal user in UAV coverage policies
     */
    public SARTeamMobilityModel(int numberOfSarMembers, double simulationTime, int teamSize,
                                double entryTime, double moveDuration, double stopDuration, double moveSpeed,
                                double priorityFactor) {
        super(numberOfSarMembers, simulationTime);
        this.teamSize = Math.max(1, teamSize);
        this.entryTime = entryTime;
        this.moveDuration = moveDuration;
        this.stopDuration = stopDuration;
        this.moveSpeed = moveSpeed;
        this.priorityFactor = priorityFactor;
    }

    @Override
    public void initialize() {
        treeMapArray = new ArrayList<>();
        for (int i = 0; i < numberOfMobileDevices; i++)
            treeMapArray.add(new TreeMap<Double, Location>());

        // ONAT: Before SAR teams enter the scenario, every member sits at a fixed
        // staging point (outside the map corner) so they are not yet tracked by UAVs.
        int stagingX = (int) SimSettings.getInstance().getWesternBound();
        int stagingY = (int) SimSettings.getInstance().getSouthernBound();
        Location stagingLocation = new Location(0, 0, stagingX, stagingY);
        for (int i = 0; i < numberOfMobileDevices; i++)
            treeMapArray.get(i).put(SimSettings.CLIENT_ACTIVITY_START_TIME, stagingLocation);

        int numOfTeams = (int) Math.ceil((double) numberOfMobileDevices / teamSize);
        for (int team = 0; team < numOfTeams; team++) {
            int firstMember = team * teamSize;
            int lastMember = Math.min(firstMember + teamSize, numberOfMobileDevices) - 1;

            TreeMap<Double, Location> teamReference = buildTeamReferenceTrajectory();

            for (Map.Entry<Double, Location> e : teamReference.entrySet()) {
                for (int member = firstMember; member <= lastMember; member++) {
                    int[] offset = FORMATION_OFFSETS[(member - firstMember) % FORMATION_OFFSETS.length];
                    int x = clamp(e.getValue().getXPos() + offset[0],
                            SimSettings.getInstance().getWesternBound(), SimSettings.getInstance().getEasternBound());
                    int y = clamp(e.getValue().getYPos() + offset[1],
                            SimSettings.getInstance().getSouthernBound(), SimSettings.getInstance().getNorthernBound());
                    treeMapArray.get(member).put(e.getKey(), new Location(0, 0, x, y));
                }
            }
        }
    }

    /**
     * Builds a single team's reference-point trajectory: spawns at a random position at
     * {@code entryTime}, then alternates MOVE (head straight to a new nearby target) /
     * STOP (stationary) phases until the simulation ends.
     */
    private TreeMap<Double, Location> buildTeamReferenceTrajectory() {
        TreeMap<Double, Location> trajectory = new TreeMap<>();

        int x = SimUtils.getRandomNumber((int) SimSettings.getInstance().getWesternBound(), (int) SimSettings.getInstance().getEasternBound());
        int y = SimUtils.getRandomNumber((int) SimSettings.getInstance().getSouthernBound(), (int) SimSettings.getInstance().getNorthernBound());
        trajectory.put(entryTime, new Location(0, 0, x, y));

        double time = entryTime;
        while (time < simulationTime) {
            time = moveToNearbyTarget(trajectory, time);
            if (time >= simulationTime)
                break;

            time = Math.min(time + stopDuration, simulationTime);
            trajectory.put(time, trajectory.lastEntry().getValue());
        }

        return trajectory;
    }

    /**
     * MOVE phase: picks a target between {@code MIN_MOVE_DISTANCE} and
     * {@code MAX_MOVE_DISTANCE} meters away from the team's current position and walks
     * straight towards it in {@code STEP_INTERVAL} increments, until it is reached
     * (or {@code moveDuration}/{@code simulationTime} is hit as a safety cap).
     *
     * @return the simulation time at which the team stopped moving
     */
    private double moveToNearbyTarget(TreeMap<Double, Location> trajectory, double time) {
        Location start = trajectory.lastEntry().getValue();
        Location target = pickNearbyTarget(start);
        double totalDistance = distanceBetween(start, target);
        double moveEnd = Math.min(time + moveDuration, simulationTime);
        double travelledDistance = 0;

        while (time < moveEnd && travelledDistance < totalDistance) {
            double step = Math.min(STEP_INTERVAL, moveEnd - time);
            time += step;
            travelledDistance = Math.min(travelledDistance + moveSpeed * step, totalDistance);

            double fraction = totalDistance <= 0 ? 1 : travelledDistance / totalDistance;
            int newX = start.getXPos() + (int) Math.round(fraction * (target.getXPos() - start.getXPos()));
            int newY = start.getYPos() + (int) Math.round(fraction * (target.getYPos() - start.getYPos()));
            trajectory.put(time, new Location(0, 0, newX, newY));
        }

        return time;
    }

    /** Picks a random point {@code MIN_MOVE_DISTANCE}-{@code MAX_MOVE_DISTANCE} meters away from {@code from}, clamped to the map bounds. */
    private static Location pickNearbyTarget(Location from) {
        double distance = SimUtils.getRandomDoubleNumber(MIN_MOVE_DISTANCE, MAX_MOVE_DISTANCE);
        double angle = Math.random() * 2 * Math.PI;
        int targetX = clamp(from.getXPos() + (int) Math.round(distance * Math.cos(angle)),
                SimSettings.getInstance().getWesternBound(), SimSettings.getInstance().getEasternBound());
        int targetY = clamp(from.getYPos() + (int) Math.round(distance * Math.sin(angle)),
                SimSettings.getInstance().getSouthernBound(), SimSettings.getInstance().getNorthernBound());
        return new Location(0, 0, targetX, targetY);
    }

    private static double distanceBetween(Location a, Location b) {
        double dx = a.getXPos() - b.getXPos();
        double dy = a.getYPos() - b.getYPos();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int clamp(int value, double min, double max) {
        if (value < min) return (int) min;
        if (value > max) return (int) max;
        return value;
    }

    /**
     * Returns the current location of a SAR member at the specified time.
     *
     * @param deviceId Local SAR member identifier (0-based, relative to the SAR population)
     * @param time Simulation time when location is requested (in seconds)
     * @return Location object containing the member's position
     */
    @Override
    public Location getLocation(int deviceId, double time) {
        TreeMap<Double, Location> treeMap = treeMapArray.get(deviceId);
        Map.Entry<Double, Location> e = treeMap.floorEntry(time);

        if (e == null) {
            SimLogger.printLine("impossible is occurred! no location is found for the SAR member '" + deviceId + "' at " + time);
            System.exit(1);
        }

        return e.getValue();
    }

    // ONAT: Before entryTime the member is just sitting at the staging point, not
    // actually part of the scenario yet - callers that average/partition user
    // positions (e.g. UAV mobility) should not treat it as a real, present user.
    @Override
    public boolean isActive(int deviceId, double time) {
        return time >= entryTime;
    }

    // ONAT: Every SAR member (still an individual device id/location, just moving in
    // formation) shares the same configured priority weight.
    @Override
    public double getPriority(int deviceId, double time) {
        return priorityFactor;
    }
}
