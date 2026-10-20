package edu.boun.edgecloudsim.applications.tutorial15;

import edu.boun.edgecloudsim.applications.tutorial6.ConvergingMobilityModel;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.utils.Location;

/**
 * ONAT:
 * Combined mobility model for tutorial15, hosting two
 * separate populations sharing a single device-id space:
 * - Normal users, ids [0, numOfNormalUsers) - reuse tutorial6's
 *   {@link ConvergingMobilityModel}, overridden to a SINGLE meeting area at the
 *   center of the simulation area (unlike the other tutorials sharing this class,
 *   which keep the default 3 hardcoded areas).
 * - SAR (Search &amp; Rescue) team members, ids [numOfNormalUsers, numOfNormalUsers + numOfSarMembers)
 *   - use {@link SARTeamMobilityModel} (fixed teams, random-walk/stop cycle, delayed entry).
 *
 * Delegating to a single combined model - rather than two independent
 * MobilityModel instances - lets every other component (UAV tracking,
 * task submission, network delay, etc.) that queries
 * {@code SimManager.getInstance().getMobilityModel().getLocation(deviceId, time)}
 * transparently see both populations without any further changes.
 */
public class CombinedMobilityModel extends MobilityModel {
    // ONAT: tutorial15's crowd converges onto a single zone at the map's center
    // ((western+eastern)/2, (southern+northern)/2 == (500,500) for this tutorial's
    // default 0-1000 bounds) instead of ConvergingMobilityModel's default 3 areas.
    private static final double[][] SINGLE_CENTER_MEETING_AREA = {{500, 500}};

    private final int numOfNormalUsers;
    private final ConvergingMobilityModel normalUserMobility;
    private final SARTeamMobilityModel sarTeamMobility;

    public CombinedMobilityModel(int numOfNormalUsers, int numOfSarMembers, double simulationTime,
                                  String meetingPointAssignmentPolicy, int sarTeamSize, double sarEntryTime,
                                  double sarMoveDuration, double sarStopDuration, double sarMoveSpeed,
                                  double sarPriorityFactor) {
        super(numOfNormalUsers + numOfSarMembers, simulationTime);
        this.numOfNormalUsers = numOfNormalUsers;
        this.normalUserMobility = new ConvergingMobilityModel(numOfNormalUsers, simulationTime,
                meetingPointAssignmentPolicy, SINGLE_CENTER_MEETING_AREA);
        this.sarTeamMobility = new SARTeamMobilityModel(numOfSarMembers, simulationTime, sarTeamSize,
                sarEntryTime, sarMoveDuration, sarStopDuration, sarMoveSpeed, sarPriorityFactor);
    }

    @Override
    public void initialize() {
        normalUserMobility.initialize();
        sarTeamMobility.initialize();
    }

    @Override
    public Location getLocation(int deviceId, double time) {
        if (deviceId < numOfNormalUsers)
            return normalUserMobility.getLocation(deviceId, time);

        return sarTeamMobility.getLocation(deviceId - numOfNormalUsers, time);
    }

    @Override
    public boolean isActive(int deviceId, double time) {
        if (deviceId < numOfNormalUsers)
            return normalUserMobility.isActive(deviceId, time);

        return sarTeamMobility.isActive(deviceId - numOfNormalUsers, time);
    }

    @Override
    public double getPriority(int deviceId, double time) {
        if (deviceId < numOfNormalUsers)
            return normalUserMobility.getPriority(deviceId, time);

        return sarTeamMobility.getPriority(deviceId - numOfNormalUsers, time);
    }
}
