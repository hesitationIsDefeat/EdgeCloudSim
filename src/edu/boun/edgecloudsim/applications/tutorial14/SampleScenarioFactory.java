/*
 * Title:        EdgeCloudSim - Scenario Factory
 * 
 * Description:  Sample scenario factory providing the default
 *               instances of required abstract classes
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial14;

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
import edu.boun.edgecloudsim.mobility.uav.CentralizedUAVMobility;
import edu.boun.edgecloudsim.mobility.uav.UAVMobilityModel;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.network.uav.UAVNetworkModel;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

/**
 * Scenario factory for tutorial14, reusing tutorial13's SAR/UAV wiring but with
 * {@code numOfNormalUsers} always 0 - this tutorial's only population is a fixed-size
 * SAR team running a single partitionable application (LLM_INFERENCE). UAV mobility is
 * fixed to a single {@code PRIORITY_KMEANS} variant; the swept axis is partition count,
 * not UAV mobility or device count (see {@link MainApp}).
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
	 * @param _numOfNormalUsers Number of normal-user mobile devices (always 0 in this tutorial)
	 * @param _numOfSarMembers Number of SAR team members (fixed population size)
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
	 * @return SARAwareLoadGenerator, restricted to the SAR app subset (LLM_INFERENCE);
	 * the normal-user subset is always empty since numOfNormalUsers is always 0.
	 */
	@Override
	public LoadGeneratorModel getLoadGeneratorModel() {
		return new SARAwareLoadGenerator(numOfNormalUsers, numOfSarMembers, simulationTime, simScenario,
				SimSettings.getInstance().getSarEntryTime());
	}

	/**
	 * Creates edge orchestrator for task offloading decisions.
	 * @return UAVEdgeOrchestrator with configured policy and scenario
	 */
	@Override
	public EdgeOrchestrator getEdgeOrchestrator() {
		return new UAVEdgeOrchestrator();
	}

	/**
	 * Creates mobility model for device movement patterns.
	 * @return CombinedMobilityModel: an empty normal-user population (numOfNormalUsers=0)
	 * plus the fixed-size SAR team, which moves in fixed teams alternating between
	 * random-walk and stationary phases from the very start of the simulation.
	 */
	@Override
	public MobilityModel getMobilityModel() {
		SimSettings SS = SimSettings.getInstance();

		// ONAT: "PRIORITY_KMEANS_<factor>" (e.g. "PRIORITY_KMEANS_6") explicitly overrides
		// the SAR priority weight for that run, regardless of sar_priority_factor - same
		// convention as BasicUAVMobility's VORONOI_<factor>. Bare "PRIORITY_KMEANS" keeps
		// using the configured sar_priority_factor.
		double sarPriorityFactor = SS.getSarPriorityFactor();
		if (CentralizedUAVMobility.isPriorityKMeansPolicy(uavMobilityOption)) {
			Double explicitFactor = CentralizedUAVMobility.parseExplicitPriorityFactor(uavMobilityOption);
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
	 * @return UAVNetworkModel for UAV-specialized WLAN delay modeling
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
     * Creates the UAV mobility model: a single {@link CentralizedUAVMobility}
     * controller with full visibility of every mobile device - just the SAR team in
     * this tutorial, since there is no normal-user population.
     */
    @Override
    public UAVMobilityModel getEdgeMobilityModel() {
        return new CentralizedUAVMobility(uavMobilityOption);
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
