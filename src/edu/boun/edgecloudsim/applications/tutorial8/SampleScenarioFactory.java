/*
 * Title:        EdgeCloudSim - Scenario Factory
 * 
 * Description:  Sample scenario factory providing the default
 *               instances of required abstract classes
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial8;

import edu.boun.edgecloudsim.cloud_server.CloudServerManager;
import edu.boun.edgecloudsim.cloud_server.DefaultCloudServerManager;
import edu.boun.edgecloudsim.core.ScenarioFactory;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.DefaultMobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.MobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.DefaultMobileServerManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.MobileServerManager;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_orchestrator.uav.UAVEdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.mobility.uav.BasicUAVMobility;
import edu.boun.edgecloudsim.mobility.uav.UAVMobilityModel;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.network.uav.UAVNetworkModel;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

/**
 * Scenario factory for tutorial8, combining the normal-user population from
 * tutorial6 with a separate SAR (Search &amp; Rescue) team population that
 * enters the scenario later and has its own applications and movement policy.
 * See {@link CombinedMobilityModel} and {@link SARAwareLoadGenerator}.
 */
public class SampleScenarioFactory implements ScenarioFactory {
	private final int numOfNormalUsers;
	private final int numOfSarMembers;
	private final double simulationTime;
	private final String orchestratorPolicy;
	private final String simScenario;
    private final String uavMobilityOption;
	
	/**
	 * Constructor for sample scenario factory.
	 * 
	 * @param _numOfNormalUsers Number of normal-user mobile devices (swept population)
	 * @param _numOfSarMembers Number of SAR team members (fixed, entering later)
	 * @param _simulationTime Total simulation time in seconds
	 * @param _orchestratorPolicy Orchestrator policy for task offloading decisions
	 * @param _simScenario Simulation scenario type (e.g., SINGLE_TIER, TWO_TIER)
	 */
	SampleScenarioFactory(int _numOfNormalUsers,
                          int _numOfSarMembers,
                          double _simulationTime,
                          String _orchestratorPolicy,
                          String _simScenario,
                          String uavMobilityOption){
		orchestratorPolicy = _orchestratorPolicy;
		numOfNormalUsers = _numOfNormalUsers;
		numOfSarMembers = _numOfSarMembers;
		simulationTime = _simulationTime;
		simScenario = _simScenario;
        this.uavMobilityOption = uavMobilityOption;
	}
	
	/**
	 * Creates load generator model for task generation patterns.
	 * @return SARAwareLoadGenerator, keeping normal users and SAR members on
	 * their own reserved application subsets and entry times
	 */
	@Override
	public LoadGeneratorModel getLoadGeneratorModel() {
		return new SARAwareLoadGenerator(numOfNormalUsers, numOfSarMembers, simulationTime, simScenario,
				SimSettings.getInstance().getSarEntryTime());
	}

	/**
	 * Creates edge orchestrator for task offloading decisions.
	 * @return BasicEdgeOrchestrator with configured policy and scenario
	 */
	@Override
	public EdgeOrchestrator getEdgeOrchestrator() {
		return new UAVEdgeOrchestrator();
	}

	/**
	 * Creates mobility model for device movement patterns.
	 * @return CombinedMobilityModel: normal users converge onto 3 hardcoded meeting
	 * areas (same as tutorial6), while SAR members move in fixed teams that enter
	 * later and alternate between random-walk and stationary phases.
	 */
	@Override
	public MobilityModel getMobilityModel() {
		SimSettings SS = SimSettings.getInstance();

		// ONAT: "VORONOI_<factor>" (e.g. "VORONOI_2") explicitly overrides the SAR
		// priority weight for that run, regardless of sar_priority_factor - this is
		// what lets a single MainApp sweep compare several priority factors under
		// distinct, self-describing result file names. Bare "VORONOI" (and every
		// other UAV mobility policy) keeps using the configured sar_priority_factor.
		double sarPriorityFactor = SS.getSarPriorityFactor();
		if (BasicUAVMobility.isVoronoiPolicy(uavMobilityOption)) {
			Double explicitFactor = BasicUAVMobility.parseExplicitPriorityFactor(uavMobilityOption);
			if (explicitFactor != null)
				sarPriorityFactor = explicitFactor;
		}

		return new CombinedMobilityModel(numOfNormalUsers, numOfSarMembers, simulationTime,
				SS.getMeetingPointAssignmentPolicy(), SS.getSarTeamSize(), SS.getSarEntryTime(),
				SS.getSarMoveDuration(), SS.getSarStopDuration(), SS.getSarMoveSpeed(),
				sarPriorityFactor);
	}

	/**
	 * Creates network model for communication delay simulation.
	 * @return MM1Queue model for queueing theory-based network delays
	 */
	@Override
	public NetworkModel getNetworkModel() {
		return new UAVNetworkModel(numOfNormalUsers + numOfSarMembers, simScenario);
	}

	/**
	 * Creates edge server manager for managing edge computing resources.
	 * @return DefaultEdgeServerManager for standard edge server operations
	 */
	@Override
	public EdgeServerManager getEdgeServerManager() {
		return new SampleEdgeServerManager();
	}

    /**
     * Creates the UAV mobility model. Unchanged from tutorial6: since both
     * populations share a single device-id space via {@link CombinedMobilityModel},
     * the LOCAL_FORCE/VORONOI UAV tracking policies swept in tutorial8 (which iterate
     * over every mobile device id) automatically take SAR members into account as well,
     * once they have entered the scenario.
     */
    @Override
    public UAVMobilityModel getEdgeMobilityModel() {
        return new BasicUAVMobility(uavMobilityOption);
    }

    /**
	 * Creates cloud server manager for managing cloud computing resources.
	 * @return DefaultCloudServerManager for standard cloud server operations
	 */
	@Override
	public CloudServerManager getCloudServerManager() {
		return new DefaultCloudServerManager();
	}
	
	/**
	 * Creates mobile device manager for handling mobile device operations.
	 * @return DefaultMobileDeviceManager for standard mobile device management
	 * @throws Exception if mobile device manager creation fails
	 */
	@Override
	public MobileDeviceManager getMobileDeviceManager() throws Exception {
		return new DefaultMobileDeviceManager();
	}

	/**
	 * Creates mobile server manager for mobile device processing units.
	 * @return DefaultMobileServerManager for standard mobile device operations
	 */
	@Override
	public MobileServerManager getMobileServerManager() {
		return new DefaultMobileServerManager();
	}
}
