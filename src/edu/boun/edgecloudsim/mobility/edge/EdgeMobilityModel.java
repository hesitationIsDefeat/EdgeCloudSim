package edu.boun.edgecloudsim.mobility.edge;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

public abstract class EdgeMobilityModel extends SimEntity {
    protected EdgeServerManager edgeServerManager;

    public EdgeMobilityModel() {
        super("EdgeMobility");
    }

    /**
     * ONAT:
     * Initializes the mobility model.
     * @param edgeServerManager The simulation's edge server manager to be updated on location changes.
     */
    public abstract void initialize(EdgeServerManager edgeServerManager);

    /**
     * ONAT:
     * Handles the related events.
     * @param event The simulation event.
     */
    protected abstract void processMoveEvent(SimEvent event);

    @Override
    public void processEvent(SimEvent event) {
        switch (event.getTag()) {
            case SimSettings.EDGE_SERVER_MOVE:
                processMoveEvent(event);
                break;
            // Extend if needed
        }
    }

    @Override
    public void startEntity() {
        // Any initialization logic that needs to run at simulation start
    }

    @Override
    public void shutdownEntity() {
        // Any cleanup logic
    }
}
