package edu.boun.edgecloudsim.edge_orchestrator.uav;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.utils.Location;
import org.cloudbus.cloudsim.ResCloudlet;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;
import edu.boun.edgecloudsim.utils.SimUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



public class UAVEdgeOrchestrator extends EdgeOrchestrator
{
    // ONAT: A UAV admits a task by RAM alone: the task is queued (or run) on it only if its
    // queue_ram fits in what the UAV's VM has left after the services it hosts (service_ram)
    // and the tasks already on it - see UAV.hasRamFor(). How many tasks that is depends only
    // on those RAM values (floor((VM RAM - hosted service_ram) / queue_ram) for one kind of
    // task); an application's vm_utilization_on_edge no longer limits the queue and is only
    // used to tell which UAV is the least loaded and for the utilization results. A task
    // without a queue_ram would therefore never be rejected, so none is accepted: startup is
    // refused instead.
    @Override
    public void initialize() {
        SimSettings settings = SimSettings.getInstance();
        for (int taskType = 0; taskType < settings.getTaskLookUpTable().length; taskType++) {
            if (settings.getTaskQueueRam(taskType) <= 0) {
                throw new IllegalArgumentException("Application '" + settings.getTaskName(taskType)
                        + "' has no queue_ram above 0 in applications.xml: " + getClass().getSimpleName()
                        + " admits a task by the RAM it holds on a UAV, so every application needs one");
            }
        }
    }

    @Override
    public int getDeviceToOffload(Task task) {
        return SimSettings.GENERIC_EDGE_DEVICE_ID;
    }

    // ONAT: Chooses the least busy UAV in the range, taking into account any
    // load and RAM already reserved for other sub-tasks selected earlier in the same
    // batch (see getVmsToOffload) that haven't actually been bound to a VM yet.
    private UAV getUAVToOffloadTo(Task task, Map<UAV, Double> reservedLoad, Map<UAV, Double> reservedRam) {
        List<UAV> uavs = SimManager.getInstance().getEdgeServerManager().getDatacenterList().stream().flatMap(datacenter -> datacenter.getHostList().stream()).map(host -> (UAV) host).toList();
        Location senderLocation = task.getSubmittedLocation();

        UAV selectedUAV = null;
        double lowestLoad = Double.MAX_VALUE;

        for (UAV uav: uavs) {
            // ONAT: Skip UAVs that aren't allowed to run this task's app at all
            // (edge_devices.xml's <service_capability> list, matched against the app's
            // service_index in applications.xml - see UAV.canServeTaskType()). An
            // unrestricted UAV (no service_capability declared) can run any app, so
            // this never rejects anything for scenarios that don't use the feature.
            int serviceIndex = SimSettings.getInstance().getTaskServiceIndex(task.getTaskType());
            if (!uav.canServeTaskType(serviceIndex)) continue;

            // ONAT: Check if the user is in the service range of the UAV
            double distance = SimUtils.getEuclideanDistance(senderLocation, uav.getLocation());
            if (distance > UAV.SERVICE_RADIUS) continue;

            // ONAT: Skip UAVs whose VM allocation failed at startup (see UAV.getVm())
            // instead of crashing with a NullPointerException below.
            EdgeVM uavVm = uav.getVm();
            if (uavVm == null) continue;

            // ONAT: TODO: Check for energy


            // ONAT: The declared CPU share (vm_utilization_on_edge) of the running task, of everything
            // queued behind it and of the siblings reserved in this batch is only the measure of how
            // loaded a UAV is, for picking the least loaded one - it no longer admits or rejects
            // anything (see the class comment). getCurrentLoad() only reflects the single cloudlet
            // CloudletSchedulerSpaceShared is actively executing (getTotalUtilizationOfCpu sums the
            // exec list only), so the waiting list is added: a UAV with a deep backlog isn't mistaken
            // for idle just because its one running task has a small share.
            double uavLoad = uav.getCurrentLoad() + getWaitingListLoad(uavVm) + reservedLoad.getOrDefault(uav, 0.0);

            // ONAT: Check if the task's queue_ram fits into what is left of the UAV's VM RAM after
            // its hosted services (service_ram), the tasks already on it, and the siblings
            // reserved in this batch. This is the only admission check.
            if (!uav.hasRamFor(task, reservedRam.getOrDefault(uav, 0.0))) continue;

            // ONAT: Check for the least loaded UAV
            if (uavLoad > lowestLoad) continue;

            // ONAT: Assign the new least loaded UAV and respective load
            selectedUAV = uav;
            lowestLoad = uavLoad;
        }

        return selectedUAV;
    }

    // ONAT: Sums the declared CPU share of cloudlets queued behind the single executing
    // one, since CloudletSchedulerSpaceShared.getTotalUtilizationOfCpu() is blind to them
    // (used to tell the least loaded UAV, see getUAVToOffloadTo()).
    private double getWaitingListLoad(EdgeVM vm) {
        double waitingLoad = 0.0;
        for (ResCloudlet rcl : vm.getCloudletScheduler().<ResCloudlet>getCloudletWaitingList()) {
            waitingLoad += rcl.getCloudlet().getUtilizationOfCpu(CloudSim.clock());
        }
        return waitingLoad;
    }

    @Override
    public Vm getVmToOffload(Task task, int deviceId) {
        return getVmsToOffload(Collections.singletonList(task), deviceId).get(0);
    }

    // ONAT: Selects UAVs for a whole batch of sibling sub-tasks at once. Each
    // selection reserves its predicted load and queue RAM against the chosen UAV before
    // the next sub-task is placed, so siblings routed to the same UAV are checked
    // against their combined load/RAM instead of each being blind to the others.
    @Override
    public List<Vm> getVmsToOffload(List<Task> tasks, int deviceId) {
        Map<UAV, Double> reservedLoad = new HashMap<UAV, Double>();
        Map<UAV, Double> reservedRam = new HashMap<UAV, Double>();
        List<Vm> selectedVMs = new ArrayList<Vm>(tasks.size());

        for (Task task : tasks) {
            UAV selectedUAV = getUAVToOffloadTo(task, reservedLoad, reservedRam);
            if (selectedUAV == null) {
                selectedVMs.add(null);
                continue;
            }

            double taskLoad = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(selectedUAV.getVm().getVmType());
            reservedLoad.merge(selectedUAV, taskLoad, Double::sum);
            reservedRam.merge(selectedUAV, task.getQueueRam(), Double::sum);
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
