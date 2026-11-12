/*
 * Title:        EdgeCloudSim - Scenario Factory
 * 
 * Description:  Sample scenario factory providing the default
 *               instances of required abstract classes
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial19;

import java.util.List;

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
import edu.boun.edgecloudsim.edge_server.uav.ServicePlacementPolicy;
import edu.boun.edgecloudsim.events.WorldEvent;
import edu.boun.edgecloudsim.events.WorldEventLoader;
import edu.boun.edgecloudsim.mobility.MobilityModel;
import edu.boun.edgecloudsim.mobility.uav.CentralizedUAVMobility;
import edu.boun.edgecloudsim.mobility.uav.UAVMobilityModel;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.network.uav.UAVNetworkModel;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

/**
 * Scenario factory for tutorial19: tutorial18's scenario (a UAV fleet repositioned by a swept
 * {@link CentralizedUAVMobility} policy, services placed on the UAVs by a {@link
 * ServicePlacementPolicy}, requests that are applications or streams) under WORLD EVENTS: for
 * the duration of each event (see events.xml, {@link WorldEventLoader}) the users inside its radius request
 * what the event is about - and, with event_gather, a part of the users around it walks to it.
 * The swept UAV mobility policies include the service-aware ones (SERVICE_KMEANS,
 * SERVICE_APF, SERVICE_CAPACITY_FILL), which send UAVs to where THEIR services are requested,
 * next to the service-blind PRIORITY_KMEANS, which only follows the crowds.
 */
public class SampleScenarioFactory implements ScenarioFactory {
	private final int numOfMobileDevices;
	private final double simulationTime;
	private final String simScenario;
	private final String uavMobilityOption;
	private final String servicePlacementPolicy;
	private final List<WorldEvent> events;
	// One instance for both the users' movement and the requests made by the events: the load
	// generator asks it where a user is when it generates that user's requests, so it has to be
	// the very model SimManager initializes (before it creates the load generator).
	private final MobilityModel mobilityModel;
	
	/**
	 * Constructor for sample scenario factory.
	 * 
	 * @param _numOfMobileDevices Number of mobile devices (users)
	 * @param _simulationTime Total simulation time in seconds
	 * @param _simScenario Simulation scenario type (e.g., SINGLE_TIER, TWO_TIER)
	 * @param _uavMobilityOption Centralized UAV mobility policy (e.g., PRIORITY_KMEANS, SERVICE_KMEANS)
	 * @param _servicePlacementPolicy Service placement policy deciding which services each UAV hosts (e.g., SINGLE, ALL)
	 * @param _events The world events of the scenario, as loaded from events.xml
	 */
	SampleScenarioFactory(int _numOfMobileDevices,
						  double _simulationTime,
						  String _simScenario,
						  String _uavMobilityOption,
						  String _servicePlacementPolicy,
						  List<WorldEvent> _events){
		numOfMobileDevices = _numOfMobileDevices;
		simulationTime = _simulationTime;
		simScenario = _simScenario;
		uavMobilityOption = _uavMobilityOption;
		servicePlacementPolicy = _servicePlacementPolicy;

		SimSettings settings = SimSettings.getInstance();
		events = _events;
		mobilityModel = new EventMobilityModel(numOfMobileDevices, simulationTime, events,
				settings.isEventGatherEnabled(), settings.getEventGatherSpeed());
	}
	
	/**
	 * Creates load generator model for task generation patterns.
	 * @return EventLoadGenerator: the requests of the world events (only users inside an event's
	 * radius request it) and, unless event_requests_only is set, the users' normal requests
	 */
	@Override
	public LoadGeneratorModel getLoadGeneratorModel() {
		return new EventLoadGenerator(numOfMobileDevices, simulationTime, simScenario, events, mobilityModel);
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
	 * @return EventMobilityModel: a random walk, plus - with event_gather - the walk to a world
	 * event and back
	 */
	@Override
	public MobilityModel getMobilityModel() {
		return mobilityModel;
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
