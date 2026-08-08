package edu.boun.edgecloudsim.applications.tutorial6;

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
 * Converging crowd mobility model.
 *
 * Every device starts at a uniformly random position, like {@link SampleMobilityModel}.
 * Each device is then assigned to one of 3 hardcoded "meeting areas" scattered
 * roughly equally around the simulation area. Devices move towards their
 * assigned area at a constant speed; once a device enters the area's capture
 * radius it is permanently "captured" and never leaves - for the remainder of
 * the simulation it only performs a small random walk bounded within the
 * area's circle.
 *
 * Device-to-area assignment is controlled by the "meeting_point_assignment_policy"
 * config property:
 * - ROUND_ROBIN: devices are split into 3 equal, deterministic groups (device i -> area i % 3)
 * - CLOSEST: each device is assigned to whichever of the 3 areas is closest to
 *            its random starting position
 */
public class ConvergingMobilityModel extends MobilityModel {

    // ONAT: Hardcoded meeting area centers, arranged as an equilateral triangle
    // scattered roughly equally around the (0,0)-(1000,1000) simulation area.
    private static final double[][] MEETING_AREAS = {
            {500, 850}, // Area 0 - top
            {197, 325}, // Area 1 - bottom-left
            {803, 325}, // Area 2 - bottom-right
    };

    // ONAT: Once a device gets within this distance of its assigned area's
    // center it is considered "captured" and confined within this radius
    // for the rest of the simulation.
    private static final double MEETING_AREA_RADIUS = 100.0;

    public static final String ASSIGNMENT_ROUND_ROBIN = "ROUND_ROBIN";
    public static final String ASSIGNMENT_CLOSEST = "CLOSEST";

    private static final double SPEED = 2.0;
    private static final double TRAVEL_TIME = 3.0;

    private final String assignmentPolicy;

    private List<TreeMap<Double, Location>> treeMapArray;

    /**
     * @param _numberOfMobileDevices Total number of mobile devices to model
     * @param _simulationTime Duration of the simulation in seconds
     * @param _assignmentPolicy Policy used to assign devices to meeting areas (ROUND_ROBIN or CLOSEST)
     */
    public ConvergingMobilityModel(int _numberOfMobileDevices, double _simulationTime, String _assignmentPolicy) {
        super(_numberOfMobileDevices, _simulationTime);
        this.assignmentPolicy = _assignmentPolicy;
    }

    @Override
    public void initialize() {
        treeMapArray = new ArrayList<>();
        int[] targetArea = new int[numberOfMobileDevices];

        // Assign each device a random starting position and build its timeline map
        for (int i = 0; i < numberOfMobileDevices; i++) {
            treeMapArray.add(i, new TreeMap<Double, Location>());

            int x_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getWesternBound(), (int) SimSettings.getInstance().getEasternBound());
            int y_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getSouthernBound(), (int) SimSettings.getInstance().getNorthernBound());

            treeMapArray.get(i).put(SimSettings.CLIENT_ACTIVITY_START_TIME, new Location(0, 0, x_pos, y_pos));

            targetArea[i] = ASSIGNMENT_CLOSEST.equals(assignmentPolicy)
                    ? getClosestAreaIndex(x_pos, y_pos)
                    : i % MEETING_AREAS.length; // ROUND_ROBIN (default)
        }

        // Generate the complete movement trajectory for each device
        for (int i = 0; i < numberOfMobileDevices; i++) {
            TreeMap<Double, Location> treeMap = treeMapArray.get(i);
            double targetX = MEETING_AREAS[targetArea[i]][0];
            double targetY = MEETING_AREAS[targetArea[i]][1];

            boolean captured = false;

            while (treeMap.lastKey() < SimSettings.getInstance().getSimulationTime()) {
                Location lastLoc = treeMap.lastEntry().getValue();
                int currentX = lastLoc.getXPos();
                int currentY = lastLoc.getYPos();

                int newX;
                int newY;

                if (!captured) {
                    // Approach phase: step directly towards the assigned meeting area
                    double stepDistance = SPEED * TRAVEL_TIME;
                    double vectorX = targetX - currentX;
                    double vectorY = targetY - currentY;
                    double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

                    if (distanceToTarget <= stepDistance) {
                        newX = (int) Math.round(targetX);
                        newY = (int) Math.round(targetY);
                    } else {
                        double angle = Math.atan2(vectorY, vectorX);
                        newX = currentX + (int) Math.round(stepDistance * Math.cos(angle));
                        newY = currentY + (int) Math.round(stepDistance * Math.sin(angle));
                    }

                    newX = clamp(newX, SimSettings.getInstance().getWesternBound(), SimSettings.getInstance().getEasternBound());
                    newY = clamp(newY, SimSettings.getInstance().getSouthernBound(), SimSettings.getInstance().getNorthernBound());

                    if (distance(newX, newY, targetX, targetY) <= MEETING_AREA_RADIUS) {
                        captured = true;
                    }
                } else {
                    // Captured phase: device never leaves - bounded random walk inside the area's circle
                    int[] boundedStep = randomStepWithinCircle(currentX, currentY, targetX, targetY);
                    newX = boundedStep[0];
                    newY = boundedStep[1];
                }

                treeMap.put(treeMap.lastKey() + TRAVEL_TIME, new Location(0, 0, newX, newY));
            }
        }
    }

    /**
     * Picks a random direction and steps the device by SPEED*TRAVEL_TIME, resampling
     * the direction (up to a fixed number of attempts) until the resulting point stays
     * within the meeting area's capture radius. Falls back to clamping the point onto
     * the circle boundary if no valid direction is found, guaranteeing the device never
     * leaves the meeting area once captured.
     */
    private int[] randomStepWithinCircle(int currentX, int currentY, double centerX, double centerY) {
        double stepDistance = SPEED * TRAVEL_TIME;
        int candidateX = currentX;
        int candidateY = currentY;

        final int MAX_ATTEMPTS = 20;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            double angle = Math.random() * 2 * Math.PI;
            candidateX = currentX + (int) Math.round(stepDistance * Math.cos(angle));
            candidateY = currentY + (int) Math.round(stepDistance * Math.sin(angle));

            if (distance(candidateX, candidateY, centerX, centerY) <= MEETING_AREA_RADIUS) {
                return new int[]{candidateX, candidateY};
            }
        }

        // Fallback: clamp the last candidate back onto the circle boundary so the
        // device is guaranteed to remain within the meeting area.
        double vectorX = candidateX - centerX;
        double vectorY = candidateY - centerY;
        double norm = Math.sqrt(vectorX * vectorX + vectorY * vectorY);
        if (norm == 0) {
            return new int[]{(int) Math.round(centerX), (int) Math.round(centerY)};
        }
        int boundedX = (int) Math.round(centerX + vectorX / norm * MEETING_AREA_RADIUS);
        int boundedY = (int) Math.round(centerY + vectorY / norm * MEETING_AREA_RADIUS);
        return new int[]{boundedX, boundedY};
    }

    private static int getClosestAreaIndex(double x, double y) {
        int closest = 0;
        double minDistance = Double.MAX_VALUE;
        for (int a = 0; a < MEETING_AREAS.length; a++) {
            double dist = distance(x, y, MEETING_AREAS[a][0], MEETING_AREAS[a][1]);
            if (dist < minDistance) {
                minDistance = dist;
                closest = a;
            }
        }
        return closest;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private static int clamp(int value, double min, double max) {
        if (value < min) return (int) min;
        if (value > max) return (int) max;
        return value;
    }

    /**
     * Returns the current location of a mobile device at the specified time.
     * Uses the pre-generated timeline to find the appropriate location entry
     * that was active at the requested time.
     *
     * @param deviceId Unique identifier of the mobile device
     * @param time Simulation time when location is requested (in seconds)
     * @return Location object containing the device's position and serving edge server
     */
    @Override
    public Location getLocation(int deviceId, double time) {
        TreeMap<Double, Location> treeMap = treeMapArray.get(deviceId);

        Map.Entry<Double, Location> e = treeMap.floorEntry(time);

        if (e == null) {
            SimLogger.printLine("impossible is occurred! no location is found for the device '" + deviceId + "' at " + time);
            System.exit(1);
        }

        return e.getValue();
    }
}
