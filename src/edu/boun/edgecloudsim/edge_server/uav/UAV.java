package edu.boun.edgecloudsim.edge_server.uav;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.ResCloudlet;
import org.cloudbus.cloudsim.VmScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisioner;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;

import java.util.Arrays;
import java.util.List;

public class UAV extends EdgeHost {
    private double mobilityInterval; // s
    private int speed; // m/s
    private double maxMoveDistance; // m
    // ONAT: Assumed to be at least 125
    public static int SERVICE_RADIUS = 100; // m

    // ONAT: service-capability restriction - the list of app service_index values (see
    // SimSettings.getTaskServiceIndex()) this UAV is allowed to run. Either read from
    // edge_devices.xml's optional <service_capability> host tag, or supplied by a
    // ServicePlacementPolicy. null/empty (the default, when neither sets it for this host)
    // means UNRESTRICTED: this UAV can run every app, preserving the original "every
    // UAV can run every task" behavior.
    private final int[] serviceCapability;

    // ONAT: RAM (MB) this UAV's VM spends to host its services (applications.xml's service_ram of
    // every service in serviceCapability; an unrestricted UAV is charged for all of them).
    // Fixed for the whole run - services are placed once, before the simulation starts.
    private final double serviceRam;

    // Allowed rounding error (MB) when comparing a task's RAM against the free RAM, since
    // partition children hold fractional shares of their parent's queue_ram
    private static final double RAM_EPSILON = 1e-6;

    /**
     * Constructs an EdgeHost with the specified resource configuration.
     * Initializes the host with processing elements, resource provisioners,
     * and VM scheduling policy. Location information should be set separately
     * using the setPlace() method.
     *
     * @param id             Unique identifier for this host
     * @param ramProvisioner RAM allocation policy for VMs on this host
     * @param bwProvisioner  Bandwidth allocation policy for VMs on this host
     * @param storage        Total storage capacity available on this host
     * @param peList         List of processing elements (CPU cores) available
     * @param vmScheduler    VM scheduling policy for this host
     */
    public UAV(int id, RamProvisioner ramProvisioner, BwProvisioner bwProvisioner, long storage, List<? extends Pe> peList, VmScheduler vmScheduler) {
        // ONAT: looked up by this UAV's own host id, which - across every
        // EdgeServerManager implementation - is assigned in the exact same
        // datacenter-then-host document order used by SimSettings.parseEdgeDevicesXML()
        // to build edgeHostServiceCapability, so index id always matches this host.
        this(id, ramProvisioner, bwProvisioner, storage, peList, vmScheduler,
                SimSettings.getInstance().getHostServiceCapability(id));
    }

    /**
     * Same as the constructor above, but with this UAV's service capability supplied
     * explicitly (e.g. computed by a {@link ServicePlacementPolicy}) instead of read from
     * edge_devices.xml's {@code service_capability} tag.
     *
     * @param serviceCapability the app service_index values this UAV may run; null/empty
     *                          means unrestricted
     */
    public UAV(int id, RamProvisioner ramProvisioner, BwProvisioner bwProvisioner, long storage, List<? extends Pe> peList, VmScheduler vmScheduler, int[] serviceCapability) {
        super(id, ramProvisioner, bwProvisioner, storage, peList, vmScheduler);
        this.mobilityInterval = SimSettings.getInstance().getUavMobilityInterval();
        this.speed = 15;
        this.maxMoveDistance = this.speed * mobilityInterval;
        this.serviceCapability = serviceCapability;

        this.serviceRam = SimSettings.getInstance().getServicesRam(serviceCapability);

        // ONAT: the UAV is built before its VM exists, so the RAM the services must fit in is
        // the one the VM is declared with in edge_devices.xml (the same value the VM is then
        // created with, see getFreeRam()) - never the host's own RAM. Unknown (-1) when the
        // host declares no VM, which getVm() reports on its own.
        int vmRam = SimSettings.getInstance().getVmRamOfEdgeHost(id);
        if (vmRam >= 0 && this.serviceRam > vmRam) {
            throw new IllegalArgumentException(String.format(
                    "UAV (Host ID: %d) runs a VM with %d MB of RAM but the services it hosts (%s) need %.1f MB (service_ram in applications.xml)",
                    id, vmRam, serviceCapability == null || serviceCapability.length == 0
                            ? "all of them - no service_capability given" : Arrays.toString(serviceCapability),
                    this.serviceRam));
        }
    }

    public double getMaxMoveDistance() {
        return this.maxMoveDistance;
    }

    public int getSpeed() {
        return this.speed;
    }

    public double getMobilityInterval() {
        return this.mobilityInterval;
    }

    public boolean isUserInRange(Location userLocation) {
        return SimUtils.getEuclideanDistance(this.getLocation(), userLocation) <= SERVICE_RADIUS;
    }

    /**
     * @param taskServiceIndex the service_index (see SimSettings.getTaskServiceIndex())
     *                         of the app a candidate task belongs to
     * @return true if this UAV is allowed to run that app - always true when
     * unrestricted (no service_capability declared for this host in edge_devices.xml)
     */
    public boolean canServeTaskType(int taskServiceIndex) {
        if (serviceCapability == null || serviceCapability.length == 0) return true;
        for (int supported : serviceCapability) {
            if (supported == taskServiceIndex) return true;
        }
        return false;
    }

    public EdgeVM getVm() {
        // DEBUG: Check if the list is empty
        if (this.getVmList().isEmpty()) {
            String errorMsg = String.format(
                    "CRITICAL ERROR: UAV (Host ID: %d) has 0 VMs! \n" +
                            "   -> Reason: VM allocation likely failed during startup. \n" +
                            "   -> Host Specs: [RAM: %d, MIPS: %d] \n" +
                            "   -> Fix: Reduce VM specs in edge_devices.xml to be smaller than Host specs.",
                    this.getId(), this.getRam(), this.getTotalMips()
            );

            // Use SimLogger if available, or System.err
            SimLogger.printLine(errorMsg);
            // edu.boun.edgecloudsim.utils.SimLogger.printLine(errorMsg);

            return null; // Return null prevents the immediate crash here (but handle it in caller!)
        }

        return (EdgeVM) this.getVmList().getFirst();
    }

    public double getCurrentLoad() {
        // getVm() returns null if VM allocation failed for this UAV - treat as idle
        // rather than crashing; callers that place tasks should still skip this UAV
        // entirely (see UAVEdgeOrchestrator.getUAVToOffloadTo).
        EdgeVM vm = this.getVm();
        return vm == null ? 0.0 : vm.getTotalUtilizationOfCpu(CloudSim.clock());
    }

    /** @return RAM (MB) spent on hosting this UAV's services - fixed for the whole run */
    public double getServiceRam() {
        return serviceRam;
    }

    /**
     * ONAT: RAM (MB) held by the tasks currently on this UAV - the running one and every one
     * queued behind it (see Task.getQueueRam()). Read from the VM scheduler's own lists instead
     * of being booked/released by hand, so a task that finishes (or runs past its timeout)
     * frees its RAM without any bookkeeping.
     */
    public double getQueuedRam() {
        EdgeVM vm = this.getVm();
        return vm == null ? 0.0 : getQueuedRam(vm);
    }

    private static double getQueuedRam(EdgeVM vm) {
        double queuedRam = 0.0;
        for (ResCloudlet rcl : vm.getCloudletScheduler().<ResCloudlet>getCloudletExecList()) {
            queuedRam += getQueueRam(rcl);
        }
        for (ResCloudlet rcl : vm.getCloudletScheduler().<ResCloudlet>getCloudletWaitingList()) {
            queuedRam += getQueueRam(rcl);
        }
        return queuedRam;
    }

    private static double getQueueRam(ResCloudlet rcl) {
        return rcl.getCloudlet() instanceof Task ? ((Task) rcl.getCloudlet()).getQueueRam() : 0.0;
    }

    /**
     * ONAT: RAM (MB) of this UAV's VM that is taken by neither the hosted services nor the tasks
     * currently on it. The budget is the VM's own RAM (edge_devices.xml's &lt;ram&gt; of the &lt;VM&gt;),
     * not the host's: everything a UAV runs lives in that VM. A UAV without a VM has none.
     */
    public double getFreeRam() {
        EdgeVM vm = this.getVm();
        return vm == null ? 0.0 : vm.getRam() - serviceRam - getQueuedRam(vm);
    }

    /**
     * @param task          the task to hold RAM on this UAV
     * @param reservedRam   RAM (MB) already promised to other tasks that are not on this UAV
     *                      yet (e.g. earlier siblings of the same placement batch)
     * @return true if the task's queue RAM fits in what is free on this UAV
     */
    public boolean hasRamFor(Task task, double reservedRam) {
        double taskRam = task.getQueueRam();
        return taskRam <= 0 || taskRam <= getFreeRam() - reservedRam + RAM_EPSILON;
    }
}
