package edu.boun.edgecloudsim.mobility.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

public class BasicUAVMobility extends UAVMobilityModel{
    private String uavMobilityOption;

    // ONAT: LOCAL_FORCE tuning - UAVs farther apart than this don't repel each other
    private static final double COORDINATION_RADIUS = 2 * UAV.SERVICE_RADIUS;
    // ONAT: LOCAL_FORCE tuning - inverse-square repulsion strength between UAVs
    private static final double REPULSION_GAIN = 20000;

    // ONAT: VORONOI policy variants are named "VORONOI" (uses the configured
    // sar_priority_factor) or "VORONOI_<factor>" (e.g. "VORONOI_2" explicitly overrides
    // the SAR priority factor to 2.0, regardless of sar_priority_factor) - a single
    // MainApp sweep can then compare multiple priority factors just by listing more
    // names in uav_mobility_options, with no new Java code per factor.
    private static final Pattern VORONOI_VARIANT_PATTERN = Pattern.compile("VORONOI(?:_(\\d+(?:\\.\\d+)?))?");

    /** ONAT: true for "VORONOI" or any "VORONOI_<factor>" variant. */
    public static boolean isVoronoiPolicy(String policyName) {
        return policyName != null && VORONOI_VARIANT_PATTERN.matcher(policyName).matches();
    }

    /**
     * ONAT: Explicit SAR priority factor encoded in a "VORONOI_<factor>" policy name,
     * or null for bare "VORONOI" (caller should fall back to the configured
     * sar_priority_factor in that case).
     */
    public static Double parseExplicitPriorityFactor(String policyName) {
        Matcher matcher = VORONOI_VARIANT_PATTERN.matcher(policyName);
        if (!matcher.matches())
            throw new IllegalArgumentException("Not a VORONOI policy variant: " + policyName);

        String factor = matcher.group(1);
        return factor == null ? null : Double.parseDouble(factor);
    }

    // ONAT: Fixed, non-overlapping group of mobile device IDs assigned to each
    // UAV under the ASSIGNED_LOCAL policy. Populated once at startEntity() and
    // never updated afterwards - membership does not depend on distance.
    private final Map<UAV, List<Integer>> assignedUsers = new HashMap<>();

    // ONAT: All UAVs in the scenario, cached once so LOCAL_FORCE can cheaply
    // check other UAVs' positions on every move event without re-walking the
    // datacenter/host tree.
    private List<UAV> allUavs = Collections.emptyList();

    public BasicUAVMobility(String uavMobilityOption) {
        super();
        this.uavMobilityOption = uavMobilityOption;
    }
    @Override
    public void initialize(EdgeServerManager edgeServerManager) {
        this.edgeServerManager = edgeServerManager;
    }

    // ONAT: Create first edge server move events
    @Override
    public void startEntity() {
        this.allUavs = this.edgeServerManager.getDatacenterList().stream()
                .flatMap(datacenter -> datacenter.getHostList().stream())
                .map(uav -> (UAV) uav)
                .toList();

        if (this.uavMobilityOption.equals("ASSIGNED_LOCAL")) {
            assignUsersToUAVs(this.allUavs);
        }

        this.allUavs.forEach(this::scheduleNextMoveEvent);
    }

    // ONAT: Splits every mobile device into equal-sized (round-robin), non-
    // overlapping groups and permanently assigns one group to each UAV. Unlike
    // LOCAL - which only reacts to whichever users currently happen to be
    // within SERVICE_RADIUS - a UAV here keeps chasing the average position of
    // its own assigned users even after some of them wander out of range.
    private void assignUsersToUAVs(List<UAV> uavs) {
        int numOfMobileDevice = SimManager.getInstance().getNumOfMobileDevice();
        int numOfUavs = uavs.size();
        if (numOfUavs == 0) return;

        for (UAV uav : uavs) {
            assignedUsers.put(uav, new ArrayList<>());
        }

        for (int deviceId = 0; deviceId < numOfMobileDevice; deviceId++) {
            UAV owner = uavs.get(deviceId % numOfUavs);
            assignedUsers.get(owner).add(deviceId);
        }
    }

    @Override
    protected void processMoveEvent(SimEvent event) {
        UAV uav = (UAV) event.getData();
        Location currentLocation = uav.getLocation();
        int newX = currentLocation.getXPos();
        int newY = currentLocation.getYPos();

        // ONAT: Every "VORONOI"/"VORONOI_<factor>" variant runs the exact same algorithm -
        // the factor only affects how SAR members are weighted inside MobilityModel.getPriority(...),
        // set up once in SampleScenarioFactory, so no per-variant branching is needed here.
        if (isVoronoiPolicy(this.uavMobilityOption)) {
            // ONAT: Decentralized centroidal-Voronoi coverage control (Cortes et al.):
            // each UAV independently partitions ALL users by nearest UAV (using only
            // every UAV's current position, no user-level coordination or controller)
            // and chases the centroid of its own cell. Unlike LOCAL_FORCE this
            // isn't capped by SERVICE_RADIUS - the nearest-UAV partition itself is what
            // prevents overlap/convergence, since a user belongs to exactly one cell.
            // The centroid is weighted by MobilityModel.getPriority(...) so a higher-
            // priority user (e.g. a SAR member) pulls the centroid more than an
            // ordinary one, without needing a central authority to enforce it.
            double sumX = 0;
            double sumY = 0;
            double totalWeight = 0;
            int userCount = 0;
            double now = CloudSim.clock();
            MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();

            for (int mobileDeviceId = 0; mobileDeviceId < SimManager.getInstance().getNumOfMobileDevice(); mobileDeviceId++) {
                // ONAT: Devices that haven't entered yet don't get a Voronoi cell claimed
                // by anyone, so a stationary staged population can't trap a UAV.
                if (!mobilityModel.isActive(mobileDeviceId, now)) continue;

                Location deviceLoc = mobilityModel.getLocation(mobileDeviceId, now);

                UAV nearestUav = uav;
                double nearestDistance = SimUtils.getEuclideanDistance(currentLocation, deviceLoc);
                for (UAV other : allUavs) {
                    if (other == uav) continue;
                    double distance = SimUtils.getEuclideanDistance(other.getLocation(), deviceLoc);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearestUav = other;
                    }
                }

                if (nearestUav == uav) {
                    double weight = mobilityModel.getPriority(mobileDeviceId, now);
                    sumX += deviceLoc.getXPos() * weight;
                    sumY += deviceLoc.getYPos() * weight;
                    totalWeight += weight;
                    userCount++;
                }
            }

            if (userCount > 0 && totalWeight > 0) {
                double targetX = sumX / totalWeight;
                double targetY = sumY / totalWeight;

                double vectorX = targetX - currentLocation.getXPos();
                double vectorY = targetY - currentLocation.getYPos();
                double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

                double maxSpeed = uav.getMaxMoveDistance();

                // ONAT: Cap the movement speed
                if (distanceToTarget > maxSpeed) {
                    double ratio = maxSpeed / distanceToTarget;
                    newX += (int) (vectorX * ratio);
                    newY += (int) (vectorY * ratio);
                } else {
                    newX = (int) targetX;
                    newY = (int) targetY;
                }
            } else {
                // ONAT: Empty Voronoi cell (no user is closest to this UAV) - random walk like LOCAL
                int deltaMagnitude = RNG.nextInt((int) uav.getMaxMoveDistance()) + 1;
                int deltaSign = RNG.nextBoolean() ? 1 : -1;
                int delta = deltaMagnitude * deltaSign;

                if (RNG.nextBoolean()) {
                    newX += delta;
                } else {
                    newY += delta;
                }
            }
        } else {
            switch (this.uavMobilityOption) {
                case "NO" -> {
                    // ONAT: Case for the non-mobile edge servers
                }
                case "RANDOM" -> {
                    // between 1 and maxMoveDistance
                    int deltaMagnitude = RNG.nextInt((int) uav.getMaxMoveDistance()) + 1;
                    // randomize the sign
                    int deltaSign = RNG.nextBoolean() ? 1 : -1;
                    // combine them to find the change in the position
                    int delta = deltaMagnitude * deltaSign;

                    if (RNG.nextBoolean()) {
                        newX += delta;
                    } else {
                        newY += delta;
                    }
                }
                case "LOCAL" -> {
                    double sumX = 0;
                    double sumY = 0;
                    int userCount = 0;
                    Location currentLoc = uav.getLocation();
                    double now = CloudSim.clock();
                    MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();

                    for (int mobileDeviceId = 0; mobileDeviceId < SimManager.getInstance().getNumOfMobileDevice(); mobileDeviceId++) {
                        // ONAT: Ignore devices that haven't entered the scenario yet (e.g. staged SAR
                        // members still parked at a fixed corner) so they can't trap or skew a UAV.
                        if (!mobilityModel.isActive(mobileDeviceId, now)) continue;

                        Location deviceLoc = mobilityModel.getLocation(mobileDeviceId, now);

                        // ONAT: Check if user is within this UAV's specific Service Radius
                        if (SimUtils.getEuclideanDistance(currentLoc, deviceLoc) <= UAV.SERVICE_RADIUS) {
                            sumX += deviceLoc.getXPos();
                            sumY += deviceLoc.getYPos();
                            userCount++;
                        }
                    }
                    if (userCount > 0) {
                        double targetX = sumX / userCount;
                        double targetY = sumY / userCount;

                        double vectorX = targetX - currentLoc.getXPos();
                        double vectorY = targetY - currentLoc.getYPos();
                        double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

                        double maxSpeed = uav.getMaxMoveDistance();

                        // ONAT: Cap the movement speed
                        if (distanceToTarget > maxSpeed) {
                            double ratio = maxSpeed / distanceToTarget;
                            newX += (int) (vectorX * ratio);
                            newY += (int) (vectorY * ratio);
                        } else {
                            newX = (int) targetX;
                            newY = (int) targetY;
                        }
                    } else {
                        // ONAT: Random if no users
                        int deltaMagnitude = RNG.nextInt((int) uav.getMaxMoveDistance()) + 1;
                        // ONAT: Randomize the sign
                        int deltaSign = RNG.nextBoolean() ? 1 : -1;
                        // ONAT: Combine them to find the change in the position
                        int delta = deltaMagnitude * deltaSign;

                        if (RNG.nextBoolean()) {
                            newX += delta;
                        } else {
                            newY += delta;
                        }
                    }
                }
                case "LOCAL_FORCE" -> {
                    // ONAT: Same local-user attraction as LOCAL, plus a repulsive force from
                    // other UAVs within COORDINATION_RADIUS so two UAVs drawn to the same
                    // user cluster settle apart instead of converging on the same spot -
                    // a decentralized force-balance (virtual force / potential field) approach.
                    double sumX = 0;
                    double sumY = 0;
                    int userCount = 0;
                    double now = CloudSim.clock();
                    MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();

                    for (int mobileDeviceId = 0; mobileDeviceId < SimManager.getInstance().getNumOfMobileDevice(); mobileDeviceId++) {
                        // ONAT: Ignore devices that haven't entered the scenario yet.
                        if (!mobilityModel.isActive(mobileDeviceId, now)) continue;

                        Location deviceLoc = mobilityModel.getLocation(mobileDeviceId, now);

                        if (SimUtils.getEuclideanDistance(currentLocation, deviceLoc) <= UAV.SERVICE_RADIUS) {
                            sumX += deviceLoc.getXPos();
                            sumY += deviceLoc.getYPos();
                            userCount++;
                        }
                    }

                    double attractX = 0;
                    double attractY = 0;
                    if (userCount > 0) {
                        attractX = (sumX / userCount) - currentLocation.getXPos();
                        attractY = (sumY / userCount) - currentLocation.getYPos();
                    }

                    double repelX = 0;
                    double repelY = 0;
                    for (UAV other : allUavs) {
                        if (other == uav) continue;

                        Location otherLoc = other.getLocation();
                        double distance = SimUtils.getEuclideanDistance(currentLocation, otherLoc);
                        if (distance > 0 && distance <= COORDINATION_RADIUS) {
                            double strength = REPULSION_GAIN / (distance * distance);
                            repelX += (currentLocation.getXPos() - otherLoc.getXPos()) / distance * strength;
                            repelY += (currentLocation.getYPos() - otherLoc.getYPos()) / distance * strength;
                        }
                    }

                    double vectorX = attractX + repelX;
                    double vectorY = attractY + repelY;
                    double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

                    if (distanceToTarget > 0) {
                        double maxSpeed = uav.getMaxMoveDistance();
                        double ratio = Math.min(1.0, maxSpeed / distanceToTarget);
                        newX += (int) (vectorX * ratio);
                        newY += (int) (vectorY * ratio);
                    } else if (userCount == 0) {
                        // ONAT: nothing to attract to or repel from - random walk like LOCAL
                        int deltaMagnitude = RNG.nextInt((int) uav.getMaxMoveDistance()) + 1;
                        int deltaSign = RNG.nextBoolean() ? 1 : -1;
                        int delta = deltaMagnitude * deltaSign;

                        if (RNG.nextBoolean()) {
                            newX += delta;
                        } else {
                            newY += delta;
                        }
                    }
                }
                case "ASSIGNED_LOCAL" -> {
                    // ONAT: Centralize among this UAV's permanently assigned users,
                    // regardless of whether they are currently within its own
                    // SERVICE_RADIUS. If the assigned group is spread out (or drifts
                    // apart over time), the averaged target can end up far from any
                    // single member - the UAV "chases the middle" of its group
                    // instead of reacting to who is actually nearby, which can cause
                    // MORE connection losses than LOCAL when the group disperses.
                    List<Integer> myUsers = assignedUsers.getOrDefault(uav, Collections.emptyList());
                    double sumX = 0;
                    double sumY = 0;
                    int userCount = 0;
                    double now = CloudSim.clock();
                    MobilityModel mobilityModel = SimManager.getInstance().getMobilityModel();

                    for (int mobileDeviceId : myUsers) {
                        // ONAT: Ignore devices that haven't entered the scenario yet.
                        if (!mobilityModel.isActive(mobileDeviceId, now)) continue;

                        Location deviceLoc = mobilityModel.getLocation(mobileDeviceId, now);
                        sumX += deviceLoc.getXPos();
                        sumY += deviceLoc.getYPos();
                        userCount++;
                    }

                    if (userCount > 0) {
                        double targetX = sumX / userCount;
                        double targetY = sumY / userCount;

                        double vectorX = targetX - currentLocation.getXPos();
                        double vectorY = targetY - currentLocation.getYPos();
                        double distanceToTarget = Math.sqrt(vectorX * vectorX + vectorY * vectorY);

                        double maxSpeed = uav.getMaxMoveDistance();

                        // ONAT: Cap the movement speed
                        if (distanceToTarget > maxSpeed) {
                            double ratio = maxSpeed / distanceToTarget;
                            newX += (int) (vectorX * ratio);
                            newY += (int) (vectorY * ratio);
                        } else {
                            newX = (int) targetX;
                            newY = (int) targetY;
                        }
                    }
                }
                case "GLOBAL" -> {
                }
                default -> SimLogger.printLine(String.format("ONAT: Unsupported UAV mobility option: %s", this.uavMobilityOption));
            }
        }

        // ONAT: Check if the new position is inside the area limits
        double easternBound = SimSettings.getInstance().getEasternBound();
        double westernBound = SimSettings.getInstance().getWesternBound();
        double northernBound = SimSettings.getInstance().getNorthernBound();
        double southernBound = SimSettings.getInstance().getSouthernBound();

        newX = (int) Math.max(westernBound, Math.min(easternBound, newX));
        newY = (int) Math.max(southernBound, Math.min(northernBound, newY));

        Location newLocation = new Location(
                currentLocation.getServingWlanId(),
                currentLocation.getPlaceTypeIndex(),
                newX,
                newY);

        uav.setPlace(newLocation);

        // log the change in the location
        //SimLogger.printLine(
//                String.format("ONAT: Edge Host %d move from (%d, %d) to (%d, %d)",
//                uav.getId(),
//                currentLocation.getXPos(),
//                currentLocation.getYPos(),
//                newX,
//                newY));

        // schedule the next move event
        scheduleNextMoveEvent(uav);
    }

    private double calculateNextEventTimeInterval(UAV uav) {
        return uav.getMobilityInterval() + RNG.nextDouble();
    }

    private void scheduleNextMoveEvent(UAV edgeHost) {
        schedule(getId(), calculateNextEventTimeInterval(edgeHost), SimSettings.EDGE_SERVER_MOVE, edgeHost);
    }
}
