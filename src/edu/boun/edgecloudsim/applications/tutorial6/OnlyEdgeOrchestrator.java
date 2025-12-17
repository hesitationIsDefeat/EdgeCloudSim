package edu.boun.edgecloudsim.applications.tutorial6;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.utils.Location;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.SimEvent;
import edu.boun.edgecloudsim.utils.SimUtils;

import java.util.List;



public class OnlyEdgeOrchestrator extends EdgeOrchestrator
{
    @Override
    public void initialize() {

    }

    @Override
    public int getDeviceToOffload(Task task) {
        return SimSettings.GENERIC_EDGE_DEVICE_ID;
    }

    // ONAT: chooses the closest edge server to offload to
    private Host getHostToOffloadTo(Task task) {
        List<EdgeHost> hosts = SimManager.getInstance().getEdgeServerManager().getDatacenterList().stream().flatMap(datacenter -> datacenter.getHostList().stream()).map(host -> (EdgeHost) host).toList();
        Location senderLocation = task.getSubmittedLocation();

        double minDistance = -1.0;
        EdgeHost hostToOffload = null;
        for (EdgeHost host: hosts) {
            double distance = SimUtils.getEuclideanDistance(senderLocation, host.getLocation());
            if (distance < minDistance) {
                minDistance = distance;
                hostToOffload = host;
            }
        }
        return hostToOffload;
    }

    @Override
    public Vm getVmToOffload(Task task, int deviceId) {
        return null;
    }

    @Override
    public void startEntity() {

    }

    @Override
    public void processEvent(SimEvent simEvent) {

    }

    @Override
    public void shutdownEntity() {

    }
}
