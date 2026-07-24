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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



public class UAVEdgeOrchestrator extends EdgeOrchestrator
{
    @Override
    public void initialize() {

    }

    @Override
    public int getDeviceToOffload(Task task) {
        return SimSettings.GENERIC_EDGE_DEVICE_ID;
    }

    // ONAT: Chooses the least busy UAV in the range, taking into account any
    // load already reserved for other sub-tasks selected earlier in the same
    // batch (see getVmsToOffload) that haven't actually been bound to a VM yet.
    private UAV getUAVToOffloadTo(Task task, Map<UAV, Double> reservedLoad) {
        List<UAV> uavs = SimManager.getInstance().getEdgeServerManager().getDatacenterList().stream().flatMap(datacenter -> datacenter.getHostList().stream()).map(host -> (UAV) host).toList();
        Location senderLocation = task.getSubmittedLocation();

        UAV selectedUAV = null;
        double lowestLoad = Double.MAX_VALUE;

        for (UAV uav: uavs) {
            // ONAT: Check if the user is in the service range of the UAV
            double distance = SimUtils.getEuclideanDistance(senderLocation, uav.getLocation());
            if (distance > UAV.SERVICE_RADIUS) continue;

            // ONAT: TODO: Check for energy


            // ONAT: Include load already reserved for sibling sub-tasks in this batch
            double uavLoad = uav.getCurrentLoad() + reservedLoad.getOrDefault(uav, 0.0);
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
        return getVmsToOffload(Collections.singletonList(task), deviceId).get(0);
    }

    // ONAT: Selects UAVs for a whole batch of sibling sub-tasks at once. Each
    // selection reserves its predicted load against the chosen UAV before the
    // next sub-task is placed, so siblings routed to the same UAV are checked
    // against their combined load instead of each being blind to the others.
    @Override
    public List<Vm> getVmsToOffload(List<Task> tasks, int deviceId) {
        Map<UAV, Double> reservedLoad = new HashMap<UAV, Double>();
        List<Vm> selectedVMs = new ArrayList<Vm>(tasks.size());

        for (Task task : tasks) {
            UAV selectedUAV = getUAVToOffloadTo(task, reservedLoad);
            if (selectedUAV == null) {
                selectedVMs.add(null);
                continue;
            }

            double taskLoad = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(selectedUAV.getVm().getVmType());
            reservedLoad.merge(selectedUAV, taskLoad, Double::sum);
            selectedVMs.add(selectedUAV.getVm());
        }

        return selectedVMs;
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
