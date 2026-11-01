/*
 * Title:        EdgeCloudSim - Scenario Factory
 * 
 * Description:  Sample scenario factory providing the default
 *               instances of required abstract classes
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial16;

import edu.boun.edgecloudsim.applications.tutorial6.ConvergingMobilityModel;
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
import edu.boun.edgecloudsim.task_generator.IdleActiveLoadGenerator;
import edu.boun.edgecloudsim.task_generator.LoadGeneratorModel;

/**
 * Scenario factory for tutorial16: a single normal-user population converging on one
 * meeting zone at the map center (same user mobility as tutorial15) served by a single
 * UAV (see edge_devices.xml) that follows the users' K-Means centroid. Every user runs the
 * one application in applications.xml, whose vm_utilization_on_edge is the value this
 * tutorial is meant to be re-run with. There is no SAR population and no task
 * partitioning, so the plain {@link IdleActiveLoadGenerator} and
 * {@link ConvergingMobilityModel} are used directly.
 */
public class SampleScenarioFactory implements ScenarioFactory {
	private final int numOfMobileDevices;
	private final double simulationTime;
	private final String orchestratorPolicy;
	private final String simScenario;
	private final String uavMobilityOption;
	
	/**
	 * Constructor for sample scenario factory.
	 * 
	 * @param _numOfMobileDevices Number of mobile devices (users)
	 * @param _simulationTime Total simulation time in seconds
	 * @param _orchestratorPolicy Orchestrator policy for task offloading decisions
	 * @param _simScenario Simulation scenario type (e.g., SINGLE_TIER, TWO_TIER)
	 * @param _uavMobilityOption Centralized UAV mobility variant (e.g., PRIORITY_KMEANS)
	 */
	SampleScenarioFactory(int _numOfMobileDevices,
						  double _simulationTime,
						  String _orchestratorPolicy,
						  String _simScenario,
						  String _uavMobilityOption){
		orchestratorPolicy = _orchestratorPolicy;
		numOfMobileDevices = _numOfMobileDevices;
		simulationTime = _simulationTime;
		simScenario = _simScenario;
		uavMobilityOption = _uavMobilityOption;
	}
	
	/**
	 * Creates load generator model for task generation patterns.
	 * @return IdleActiveLoadGenerator (every user picks the single application)
	 */
	@Override
	public LoadGeneratorModel getLoadGeneratorModel() {
		return new IdleActiveLoadGenerator(numOfMobileDevices, simulationTime, simScenario);
	}

	/**
	 * Creates edge orchestrator for task offloading decisions.
	 * @return UAVEdgeOrchestrator, whose admission check counts the running task, the
	 * waiting list and any load already reserved in the same batch
	 */
	@Override
	public EdgeOrchestrator getEdgeOrchestrator() {
		return new UAVEdgeOrchestrator();
	}

	/**
	 * Creates mobility model for device movement patterns.
	 * @return ConvergingMobilityModel with a SINGLE meeting area at the center of the
	 * simulation area ((western+eastern)/2, (southern+northern)/2, i.e. (500,500) for the
	 * default 0-1000 bounds) - exactly what tutorial15's CombinedMobilityModel builds for
	 * its normal users.
	 */
	@Override
	public MobilityModel getMobilityModel() {
		SimSettings SS = SimSettings.getInstance();
		double[][] centerMeetingArea = {{
				(SS.getWesternBound() + SS.getEasternBound()) / 2,
				(SS.getSouthernBound() + SS.getNorthernBound()) / 2}};

		return new ConvergingMobilityModel(numOfMobileDevices, simulationTime,
				SS.getMeetingPointAssignmentPolicy(), centerMeetingArea);
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
	 * @return SampleEdgeServerManager, which builds UAV hosts
	 */
	@Override
	public EdgeServerManager getEdgeServerManager() {
		return new SampleEdgeServerManager();
	}

	/**
	 * Creates the UAV mobility model: a single {@link CentralizedUAVMobility}
	 * controller, which with one UAV simply keeps it on the users' centroid.
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
