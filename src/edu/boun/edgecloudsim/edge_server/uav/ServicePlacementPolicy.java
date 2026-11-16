/*
 * Title:        EdgeCloudSim - UAV Service Placement Policy
 *
 * Description:  Decides which application services each UAV hosts, replacing hard-coded
 *               edge_devices.xml service_capability lists. The policy name comes from
 *               default_config.properties' service_placement_policies.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.edge_server.uav;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * ONAT: Computes every UAV's service capability (the app service_index values it may
 * run, see {@link UAV#canServeTaskType(int)}) from a named policy:
 * <ul>
 *   <li>{@link #SINGLE}: each UAV hosts exactly one service, and services are spread
 *   over the UAVs as evenly as possible (e.g. 16 UAVs and 4 services -> 4 UAVs each).
 *   UAVs are dealt out round-robin (UAV i hosts service i % numOfServices) rather than in
 *   contiguous blocks, because consecutive UAV ids share a datacenter (i.e. start next to
 *   each other) - contiguous blocks would leave a service available in one map corner only.</li>
 *   <li>{@link #ALL}: every UAV hosts every service.</li>
 * </ul>
 * A "service" is a distinct applications.xml service_index. The services are read from
 * {@link SimSettings} so that service attributes added to applications.xml later on
 * (priority, popularity, ...) can be consumed by new policies without changing
 * how callers use this class. A service costs its service_ram out of the RAM of the VM of every
 * UAV that hosts it: every placement is checked against those VM RAMs, and a policy that wants
 * to pack services by RAM reads {@link SimSettings#getServiceRam(int)} and
 * {@link SimSettings#getVmRamOfEdgeHost(int)}.
 */
public class ServicePlacementPolicy {
    public static final String SINGLE = "SINGLE";
    public static final String ALL = "ALL";
    public static final List<String> SUPPORTED_POLICIES = List.of(SINGLE, ALL);

    private final String policyName;

    /**
     * @param policyName one of {@link #SUPPORTED_POLICIES}
     * @throws IllegalArgumentException if the policy is unknown
     */
    public ServicePlacementPolicy(String policyName) {
        if (!isSupported(policyName)) {
            throw new IllegalArgumentException("Unknown service placement policy '" + policyName
                    + "' (supported: " + SUPPORTED_POLICIES + ")");
        }
        this.policyName = policyName;
    }

    public static boolean isSupported(String policyName) {
        return SUPPORTED_POLICIES.contains(policyName);
    }

    public String getPolicyName() {
        return policyName;
    }

    /**
     * @param numOfUAVs number of UAVs to place services on; UAV ids are expected to be
     *                  0..numOfUAVs-1 (the global host id order, see UAV's constructor)
     * @return for every UAV id, the service_index values that UAV hosts
     * @throws IllegalStateException if the RAM of a UAV's VM cannot hold the services placed on it
     */
    public int[][] place(int numOfUAVs) {
        int[] services = getServices();

        if (SINGLE.equals(policyName) && numOfUAVs < services.length) {
            SimLogger.printLine("WARNING: " + SINGLE + " service placement has fewer UAVs ("
                    + numOfUAVs + ") than services (" + services.length + ") - some services run on no UAV");
        }

        int[][] placement = computePlacement(numOfUAVs, services);
        checkFitsInRam(placement);
        return placement;
    }

    /**
     * Checks that this policy's placement fits the RAM of every UAV's VM, without placing anything,
     * so that a sweep can fail before its first scenario instead of part way through.
     *
     * @param numOfUAVs see {@link #place(int)}
     * @throws IllegalStateException if the RAM of a UAV's VM cannot hold the services placed on it
     */
    public void validate(int numOfUAVs) {
        checkFitsInRam(computePlacement(numOfUAVs, getServices()));
    }

    private int[][] computePlacement(int numOfUAVs, int[] services) {
        int[][] placement = new int[numOfUAVs][];

        switch (policyName) {
            case SINGLE -> {
                for (int uav = 0; uav < numOfUAVs; uav++) {
                    placement[uav] = new int[]{services[uav % services.length]};
                }
            }
            case ALL -> {
                for (int uav = 0; uav < numOfUAVs; uav++) {
                    placement[uav] = services.clone();
                }
            }
            default -> throw new IllegalStateException("Unhandled service placement policy: " + policyName);
        }

        return placement;
    }

    /**
     * ONAT: every service a UAV hosts takes its service_ram (applications.xml) out of the RAM of
     * the UAV's VM (edge_devices.xml's &lt;ram&gt; of the host's &lt;VM&gt; - not of the host), so a
     * placement that needs more than that VM has is not a placement. It is an error rather than
     * being trimmed to what fits: a UAV left with no service would have an empty capability
     * list, which means UNRESTRICTED (see {@link UAV#canServeTaskType(int)}), i.e. the opposite.
     */
    private void checkFitsInRam(int[][] placement) {
        SimSettings settings = SimSettings.getInstance();
        for (int uav = 0; uav < placement.length; uav++) {
            int vmRam = settings.getVmRamOfEdgeHost(uav);
            double neededRam = settings.getServicesRam(placement[uav]);
            if (vmRam >= 0 && neededRam > vmRam) {
                throw new IllegalStateException("Service placement policy '" + policyName + "' puts services "
                        + Arrays.toString(placement[uav]) + " on UAV" + uav + ", which need " + neededRam
                        + " MB (service_ram in applications.xml) but the VM of the UAV has only " + vmRam + " MB of RAM");
            }
        }
    }

    /** Distinct service_index values of every application, in applications.xml order. */
    private static int[] getServices() {
        SimSettings settings = SimSettings.getInstance();
        Set<Integer> services = new LinkedHashSet<>();
        for (int taskType = 0; taskType < settings.getTaskLookUpTable().length; taskType++) {
            services.add(settings.getTaskServiceIndex(taskType));
        }
        return services.stream().mapToInt(Integer::intValue).toArray();
    }

    /** One-line, human readable summary of a placement, e.g. "UAV0=[0] UAV1=[1]". */
    public static String describe(int[][] placement) {
        StringBuilder sb = new StringBuilder();
        for (int uav = 0; uav < placement.length; uav++) {
            if (uav > 0) sb.append(' ');
            sb.append("UAV").append(uav).append('=').append(Arrays.toString(placement[uav]).replace(" ", ""));
        }
        return sb.toString();
    }
}
