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

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

public class BasicUAVMobility extends UAVMobilityModel{
    private String uavMobilityOption;

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
        this.edgeServerManager.getDatacenterList().stream()
                .flatMap(datacenter -> datacenter.getHostList().stream())
                .map(uav -> (UAV) uav)
                .forEach(this::scheduleNextMoveEvent);
    }

    @Override
    protected void processMoveEvent(SimEvent event) {
        UAV uav = (UAV) event.getData();
        Location currentLocation = uav.getLocation();
        int newX = currentLocation.getXPos();
        int newY = currentLocation.getYPos();
        switch (this.uavMobilityOption) {
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
            case "GLOBAL" -> {
            }
            default -> SimLogger.printLine(String.format("ONAT: Unsupported UAV mobility option: %s", this.uavMobilityOption));
        }

        // ONAT: Check if the new position is inside the area limits

        if (newX > SimSettings.getInstance().getNorthernBound()) newX = (int) SimSettings.getInstance().getNorthernBound();
        else if (newX < SimSettings.getInstance().getSouthernBound()) newX = (int) SimSettings.getInstance().getSouthernBound();

        if (newX > SimSettings.getInstance().getEasternBound()) newX = (int) SimSettings.getInstance().getEasternBound();
        else if (newX < SimSettings.getInstance().getWesternBound()) newX = (int) SimSettings.getInstance().getWesternBound();

        if (newY > SimSettings.getInstance().getNorthernBound()) newY = (int) SimSettings.getInstance().getNorthernBound();
        else if (newY < SimSettings.getInstance().getSouthernBound()) newY = (int) SimSettings.getInstance().getSouthernBound();

        if (newY > SimSettings.getInstance().getEasternBound()) newY = (int) SimSettings.getInstance().getEasternBound();
        else if (newY < SimSettings.getInstance().getWesternBound()) newY = (int) SimSettings.getInstance().getWesternBound();

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
