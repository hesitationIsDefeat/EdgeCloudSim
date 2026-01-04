package edu.boun.edgecloudsim.edge_orchestrator.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.utils.Location;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.SimEvent;
import edu.boun.edgecloudsim.utils.SimUtils;

import java.util.List;



public class UAVEdgeOrchestrator extends EdgeOrchestrator
{
    @Override
    public void initialize() {

    }

    @Override
    public int getDeviceToOffload(Task task) {
        return SimSettings.GENERIC_EDGE_DEVICE_ID;
    }

    // ONAT: Chooses the least busy UAV in the range
    private UAV getUAVToOffloadTo(Task task) {
        List<UAV> uavs = SimManager.getInstance().getEdgeServerManager().getDatacenterList().stream().flatMap(datacenter -> datacenter.getHostList().stream()).map(host -> (UAV) host).toList();
        Location senderLocation = task.getSubmittedLocation();

        UAV selectedUAV = null;
        double lowestLoad = Double.MAX_VALUE;

        for (UAV uav: uavs) {
            // ONAT: Check if the user is in the service range of the UAV
            double distance = SimUtils.getEuclideanDistance(senderLocation, uav.getLocation());
            if (distance > UAV.SERVICE_RADIUS) continue;

            // ONAT: TODO: Check for energy


            double uavLoad = uav.getCurrentLoad();
            // ONAT: Check if the requested load fits into the UAV
            double taskLoad = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(uav.getVm().getVmType());
            if (uavLoad + taskLoad > 100.0) continue;

            // ONAT: Check for the least loaded UAV
            if (uavLoad > lowestLoad) continue;

            // ONAT: Assign the new least loaded UAV and respective load
            selectedUAV = uav;
            lowestLoad = uavLoad;
        }

        return selectedUAV;
    }

    @Override
    public Vm getVmToOffload(Task task, int deviceId) {
        UAV selectedUAV = this.getUAVToOffloadTo(task);
        return selectedUAV == null ? null: selectedUAV.getVm();
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
