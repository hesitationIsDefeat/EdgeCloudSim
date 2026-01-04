package edu.boun.edgecloudsim.mobility.uav;

import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import org.cloudbus.cloudsim.core.SimEvent;

/**
 * ONAT:
 * An Edge Mobility class used for non-mobile edge servers
 * */
public class DefaultUAVMobility extends UAVMobilityModel {

    public DefaultUAVMobility() {
        super();
    }

    @Override
    public void initialize(EdgeServerManager edgeServerManager) {
        this.edgeServerManager = null;
    }

    @Override
    protected void processMoveEvent(SimEvent event) {

    }
}
