package edu.boun.edgecloudsim.applications.tutorial6;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.mobility.edge.EdgeMobilityModel;
import edu.boun.edgecloudsim.utils.Location;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.Random;

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

/**
 * ONAT:
 * An Edge Mobility class that moves the edge servers a random amount to a random direction
 * */
public class RandomEdgeMobility extends EdgeMobilityModel {
    private double mobilityInterval;
    private int maxMoveDistance;

    public RandomEdgeMobility() {
        super();
    }

    @Override
    public void initialize(EdgeServerManager edgeServerManager) {
        this.edgeServerManager = edgeServerManager;
        this.mobilityInterval = 1.0;
        this.maxMoveDistance = 1;
    }

    // ONAT: Create first edge server move events
    @Override
    public void startEntity() {
        this.edgeServerManager.getDatacenterList().stream()
                .flatMap(datacenter -> datacenter.getHostList().stream())
                .map(host -> (EdgeHost) host)
                .forEach(this::scheduleNextMoveEvent);
    }
    
    // ONAT: Handle move event and schedule next move event
    @Override
    protected void processMoveEvent(SimEvent event) {
        EdgeHost edgeHost = (EdgeHost) event.getData();
        Location currentLocation = edgeHost.getLocation();
        int newX = currentLocation.getXPos();
        int newY = currentLocation.getYPos();

        // between 1 and maxMoveDistance
        int deltaMagnitude = RNG.nextInt(maxMoveDistance) + 1;
        // randomize the sign
        int deltaSign = RNG.nextBoolean() ? 1 : -1;
        // combine them to find the change in the position
        int delta = deltaMagnitude * deltaSign;

        if (RNG.nextBoolean()) {
            newX += delta;
        } else {
            newY += delta;
        }

        Location newLocation = new Location(
                currentLocation.getServingWlanId(),
                currentLocation.getPlaceTypeIndex(),
                newX,
                newY);

        edgeHost.setPlace(newLocation);

        // log the change in the location
        //SimLogger.printLine(
//                String.format("ONAT: Edge Host %d move from (%d, %d) to (%d, %d)",
//                edgeHost.getId(),
//                currentLocation.getXPos(),
//                currentLocation.getYPos(),
//                newX,
//                newY));

        // schedule the next move event
        scheduleNextMoveEvent(edgeHost);
    }

    private double calculateNextEventTimeInterval() {
        return this.mobilityInterval + RNG.nextDouble();
    }

    private void scheduleNextMoveEvent(EdgeHost edgeHost) {
        schedule(getId(), calculateNextEventTimeInterval(), SimSettings.EDGE_SERVER_MOVE, edgeHost);
    }
}
