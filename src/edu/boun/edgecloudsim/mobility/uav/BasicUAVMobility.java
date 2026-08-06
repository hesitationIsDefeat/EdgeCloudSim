package edu.boun.edgecloudsim.mobility.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
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

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

public class BasicUAVMobility extends UAVMobilityModel{
    private String uavMobilityOption;

    // ONAT: Fixed, non-overlapping group of mobile device IDs assigned to each
    // UAV under the ASSIGNED_LOCAL policy. Populated once at startEntity() and
    // never updated afterwards - membership does not depend on distance.
    private final Map<UAV, List<Integer>> assignedUsers = new HashMap<>();

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
        List<UAV> uavs = this.edgeServerManager.getDatacenterList().stream()
                .flatMap(datacenter -> datacenter.getHostList().stream())
                .map(uav -> (UAV) uav)
                .toList();

        if (this.uavMobilityOption.equals("ASSIGNED_LOCAL")) {
            assignUsersToUAVs(uavs);
        }

        uavs.forEach(this::scheduleNextMoveEvent);
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

                for (int mobileDeviceId = 0; mobileDeviceId < SimManager.getInstance().getNumOfMobileDevice(); mobileDeviceId++) {
                    Location deviceLoc = SimManager.getInstance().getMobilityModel().getLocation(mobileDeviceId, CloudSim.clock());

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

                for (int mobileDeviceId : myUsers) {
                    Location deviceLoc = SimManager.getInstance().getMobilityModel().getLocation(mobileDeviceId, CloudSim.clock());
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
