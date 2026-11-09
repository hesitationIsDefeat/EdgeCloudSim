package edu.boun.edgecloudsim.edge_server.uav;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.VmScheduler;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisioner;
import org.cloudbus.cloudsim.provisioners.RamProvisioner;

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
}
