/*
 * Title:        EdgeCloudSim - Scenario Factory
 * 
 * Description:  Sample scenario factory providing the default
 *               instances of required abstract classes
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial18;

import edu.boun.edgecloudsim.applications.tutorial6.SampleMobilityModel;
import edu.boun.edgecloudsim.cloud_server.CloudServerManager;
import edu.boun.edgecloudsim.cloud_server.DefaultCloudServerManager;
import edu.boun.edgecloudsim.core.ScenarioFactory;
import edu.boun.edgecloudsim.edge_client.DefaultMobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.MobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.DefaultMobileServerManager;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.MobileServerManager;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_orchestrator.uav.UAVEdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeServerManager;
import edu.boun.edgecloudsim.edge_server.uav.ServicePlacementPolicy;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.mobility.uav.CentralizedUAVMobility;
import edu.boun.edgecloudsim.mobility.uav.UAVMobilityModel;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.network.uav.UAVNetworkModel;
import edu.boun.edgecloudsim.task_generator.IdleActiveStreamLoadGenerator;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

/**
 * Scenario factory for tutorial18: tutorial17's scenario (random-walk users, a UAV fleet
 * repositioned by a swept {@link CentralizedUAVMobility} policy, services placed on the UAVs
 * by a swept {@link ServicePlacementPolicy}) where a user's request is either a plain
 * application (applications.xml) or a STREAM (streams.xml): a DAG whose nodes are
 * applications. See {@link IdleActiveStreamLoadGenerator} for how the type of request is
 * assigned and {@code DefaultMobileDeviceManager.submitStream()} for how a stream is run -
 * each of its nodes is offloaded to an in-range UAV hosting the node's application
 * ({@code UAV.canServeTaskType()} / {@code UAVEdgeOrchestrator.getUAVToOffloadTo()}), and a
 * node for which there is none fails the stream.
 */
public class SampleScenarioFactory implements ScenarioFactory {
	private final int numOfMobileDevices;
	private final double simulationTime;
	private final String orchestratorPolicy;
	private final String simScenario;
	private final String uavMobilityOption;
	private final String servicePlacementPolicy;
	
	/**
	 * Constructor for sample scenario factory.
	 * 
	 * @param _numOfMobileDevices Number of mobile devices (users)
	 * @param _simulationTime Total simulation time in seconds
	 * @param _orchestratorPolicy Orchestrator policy for task offloading decisions
	 * @param _simScenario Simulation scenario type (e.g., SINGLE_TIER, TWO_TIER)
	 * @param _uavMobilityOption Centralized UAV mobility variant (e.g., PRIORITY_KMEANS)
	 * @param _servicePlacementPolicy Service placement policy deciding which services each UAV hosts (e.g., SINGLE, ALL)
	 */
	SampleScenarioFactory(int _numOfMobileDevices,
						  double _simulationTime,
						  String _orchestratorPolicy,
						  String _simScenario,
						  String _uavMobilityOption,
						  String _servicePlacementPolicy){
		orchestratorPolicy = _orchestratorPolicy;
		numOfMobileDevices = _numOfMobileDevices;
		simulationTime = _simulationTime;
		simScenario = _simScenario;
		uavMobilityOption = _uavMobilityOption;
		servicePlacementPolicy = _servicePlacementPolicy;
	}
	
	/**
	 * Creates load generator model for task generation patterns.
	 * @return IdleActiveStreamLoadGenerator (every user is assigned one request type - an
	 * application or a stream - weighted by usage_percentage, and requests it with that
	 * type's own timing)
	 */
	@Override
	public LoadGeneratorModel getLoadGeneratorModel() {
		return new IdleActiveStreamLoadGenerator(numOfMobileDevices, simulationTime, simScenario);
	}

	/**
	 * Creates edge orchestrator for task offloading decisions.
	 * @return UAVEdgeOrchestrator, whose admission check also rejects a UAV that doesn't
	 * host the task's app (see ServicePlacementPolicy)
	 */
	@Override
	public EdgeOrchestrator getEdgeOrchestrator() {
		return new UAVEdgeOrchestrator();
	}

	/**
	 * Creates mobility model for device movement patterns.
	 * @return SampleMobilityModel (tutorial6): each user takes independent random-angle
	 * steps every 3 s, bounded by the map edges - no convergence onto any meeting zone.
	 */
	@Override
	public MobilityModel getMobilityModel() {
		return new SampleMobilityModel(numOfMobileDevices, simulationTime);
	}

	/**
	 * Creates network model for communication delay simulation.
	 * @return UAVNetworkModel (distance-dependent WLAN bandwidth, out of range = rejected)
	 */
	@Override
	public NetworkModel getNetworkModel() {
		return new UAVNetworkModel(numOfMobileDevices, simScenario);
	}

	/**
	 * Creates edge server manager for managing edge computing resources.
	 * @return SampleEdgeServerManager, which builds UAV hosts and gives each one the
	 * services chosen by the service placement policy
	 */
	@Override
	public EdgeServerManager getEdgeServerManager() {
		return new SampleEdgeServerManager(new ServicePlacementPolicy(servicePlacementPolicy));
	}

	/**
	 * Creates the UAV mobility model: a single {@link CentralizedUAVMobility}
	 * controller running the swept policy.
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
