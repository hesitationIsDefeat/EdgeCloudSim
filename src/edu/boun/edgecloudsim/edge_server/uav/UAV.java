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
        super(id, ramProvisioner, bwProvisioner, storage, peList, vmScheduler);
        this.mobilityInterval = SimSettings.getInstance().getUavMobilityInterval();
        this.speed = 15;
        this.maxMoveDistance = this.speed * mobilityInterval;
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
