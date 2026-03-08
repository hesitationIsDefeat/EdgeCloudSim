/*
 * Title:        EdgeCloudSim - Mobile Device Manager
 * 
 * Description: 
 * Mobile Device Manager is one of the most important component
 * in EdgeCloudSim. It is responsible for creating the tasks,
 * submitting them to the related VM with respect to the
 * Edge Orchestrator decision, and takes proper actions when
 * the execution of the tasks are finished. It also feeds the
 * SimLogger with the relevant results.

 * SampleMobileDeviceManager sends tasks to the edge servers or
 * cloud servers. The mobile devices use WAN if the tasks are
 * offloaded to the edge servers. On the other hand, they use WLAN
 * if the target server is an edge server. Finally, the mobile
 * devices use MAN if they must be served by a remote edge server
 * due to the congestion at their own location. In this case,
 * they access the edge server via two hops where the packets
 * must go through WLAN and MAN.
 * 
 * If you want to use different topology, you should modify
 * the flow implemented in this class.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.tutorial7;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.core.SimSettings.NETWORK_DELAY_TYPES;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.MobileDeviceManager;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_client.partition.PartititionableTask;
import edu.boun.edgecloudsim.edge_client.partition.SubTask;
import edu.boun.edgecloudsim.edge_orchestrator.uav.UAVEdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.TaskProperty;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

import java.util.Arrays;
import java.util.List;

// Execution pipeline overview:
// 1) submitTask(): create Task -> decide offloading target -> choose VM -> initiate upload
// 2) REQUEST_* events: emulate network hops (WLAN / MAN) until task reaches serving edge
// 3) Task executes on VM (CloudSim handles execution)
// 4) processCloudletReturn(): generate response path (direct or via relay)
// 5) RESPONSE_* events: emulate download and finalize logging
// Fail cases tracked: insufficient bandwidth, mobility-induced disconnection, VM capacity rejection.
// Time measurements: orchestrator overhead (ns), upload/download delays, execution start/end.

public class SampleMobileDeviceManager extends MobileDeviceManager {
	// Base value chosen to avoid collision with CloudSim's internal tag space.
	private static final int BASE = 100000; //start from base in order not to conflict cloudsim tag!
	
	// Event tags for custom network traversal state machine:
	private static final int REQUEST_RECEIVED_BY_EDGE_DEVICE = BASE + 1; // local edge got upload
	private static final int REQUEST_RECEIVED_BY_REMOTE_EDGE_DEVICE = BASE + 2; // neighbor/remote edge got relay upload
	private static final int REQUEST_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_NEIGHBOR = BASE + 3; // local edge will forward to neighbor (MAN hop)
	private static final int RESPONSE_RECEIVED_BY_MOBILE_DEVICE = BASE + 4; // final response arrived to device
	private static final int RESPONSE_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_MOBILE_DEVICE = BASE + 5; // remote edge sends response to original edge for WLAN relay
	
	private int taskIdCounter=0; // monotonically increasing local task id (per device manager instance)
	private final String taskPartitionOption;
	
	public SampleMobileDeviceManager(String taskPartitionOption) throws Exception{
		this.taskPartitionOption = taskPartitionOption;
	}

	@Override
	public void initialize() {
		// No pre-allocation needed; networking & mobility fetched on demand.
		// Could cache model references here for micro optimizations.
	}
	
	@Override
	public UtilizationModel getCpuUtilizationModel() {
		// Custom model provides per-task predicted CPU percentage for placement decisions.
		return new CpuUtilizationModel_Custom();
	}
	
	@Override
	public void startEntity() {
		super.startEntity();
	}
	
	/**
	 * Submit cloudlets to the created VMs.
	 * 
	 * @pre $none
	 * @post $none
	 */
	protected void submitCloudlets() {
		//do nothing!
	}
	
	/**
	 * Process a cloudlet return event.
	 * 
	 * @param ev a SimEvent object
	 * @pre ev != $null
	 * @post $none
	 */
	protected void processCloudletReturn(SimEvent ev) {
		// Called when task execution completes at the edge VM.
		// Decide whether response needs relay (if served by foreign WLAN zone).
		// Apply mobility-aware validation before scheduling download.

		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		Task task = (Task) ev.getData();

		// --- YOUR CUSTOM SUBTASK LOGIC ---
		if (task instanceof SubTask) {
			PartititionableTask parentTask = ((SubTask) task).getParentTask();
			// Note: getCloudletStatus() == Cloudlet.SUCCESS is safer than string comparison
			boolean isSuccess = task.getCloudletStatusString().equals(Cloudlet.getStatusString(Cloudlet.SUCCESS));

			parentTask.reportSubTaskResult(isSuccess);

			if (parentTask.isFullyReconstructed()) {
				// Logic for when all pieces are back (optional)
			}
		} else {
			SimLogger.getInstance().taskExecuted(task.getCloudletId());
		}

		int nextEvent = RESPONSE_RECEIVED_BY_MOBILE_DEVICE;
		int nextDeviceForNetworkModel = SimSettings.GENERIC_EDGE_DEVICE_ID;
		NETWORK_DELAY_TYPES delayType = NETWORK_DELAY_TYPES.WLAN_DELAY;

		// Calculate standard download delay
		double delay = networkModel.getDownloadDelay(task.getAssociatedHostId(), task.getMobileDeviceId(), task);

		// --- FIX FOR IndexOutOfBoundsException START ---
		EdgeHost host = null;
		int datacenterId = task.getAssociatedDatacenterId();

		// Only check for EdgeHost location if the task ran on an Edge Server (not Cloud)
		if (datacenterId != SimSettings.CLOUD_DATACENTER_ID) {
			// Iterate to find the correct Datacenter by ID
			for (org.cloudbus.cloudsim.Datacenter dc : SimManager.getInstance().getEdgeServerManager().getDatacenterList()) {
				if (dc.getId() == datacenterId) {
					// EdgeCloudSim assumes 1 Host per Edge Datacenter
					if (!dc.getHostList().isEmpty()) {
						host = (EdgeHost) dc.getHostList().get(0);
					}
					break;
				}
			}
		}
		// --- FIX END ---

		// If neighbour edge device is selected (and we successfully found the host)
		if(host != null && host.getLocation().getServingWlanId() != task.getSubmittedLocation().getServingWlanId())
		{
			// if neighbor edge served the task, reroute through MAN back to original edge before WLAN delivery
			delay = networkModel.getDownloadDelay(SimSettings.GENERIC_EDGE_DEVICE_ID, SimSettings.GENERIC_EDGE_DEVICE_ID, task);
			nextEvent = RESPONSE_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_MOBILE_DEVICE;
			nextDeviceForNetworkModel = SimSettings.GENERIC_EDGE_DEVICE_ID + 1;
			delayType = NETWORK_DELAY_TYPES.MAN_DELAY;
		}

		if(delay > 0)
		{
			Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(), CloudSim.clock() + delay);
			if(task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId())
			{
				networkModel.downloadStarted(currentLocation, nextDeviceForNetworkModel);
				SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), delay, delayType);

				schedule(getId(), delay, nextEvent, task);
			}
			else
			{
				// Mobility check: ensure device still in original WLAN after simulated download delay
				SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
			}
		}
		else
		{
			// Failure branches:
			//  - delay <= 0 : bandwidth saturated
			//  - WLAN changed: mobility-induced failure
			SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), delayType);
		}
	}
	
	protected void processOtherEvent(SimEvent ev) {
		// Central dispatcher for custom network traversal events.
		// Each case advances the finite state machine for request/response.
		if (ev == null) {
			SimLogger.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null! Terminating simulation...");
			System.exit(0);
			return;
		}
		
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		
		switch (ev.getTag()) {
			case REQUEST_RECEIVED_BY_EDGE_DEVICE:
			{
				// Local edge receives task -> finish WLAN upload -> submit to VM
				Task task = (Task) ev.getData();
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);
				submitTaskToVm(task, SimSettings.VM_TYPES.EDGE_VM);
				break;
			}
			case REQUEST_RECEIVED_BY_REMOTE_EDGE_DEVICE:
			{
				// Remote edge (neighbor) receives relayed task via MAN -> submit to VM
				Task task = (Task) ev.getData();
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID+1);
				submitTaskToVm(task, SimSettings.VM_TYPES.EDGE_VM);
				
				break;
			}
			case REQUEST_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_NEIGHBOR:
			{
				// Local edge decides to forward to neighbor (capacity / policy reason)
				// Start MAN upload; on success schedule remote edge receive event.
				Task task = (Task) ev.getData();
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);
				
				double manDelay =  networkModel.getUploadDelay(SimSettings.GENERIC_EDGE_DEVICE_ID, SimSettings.GENERIC_EDGE_DEVICE_ID, task);
				if(manDelay>0){
					networkModel.uploadStarted(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID+1);
					SimLogger.getInstance().setUploadDelay(task.getCloudletId(), manDelay, NETWORK_DELAY_TYPES.MAN_DELAY);
					schedule(getId(), manDelay, REQUEST_RECEIVED_BY_REMOTE_EDGE_DEVICE, task);
				}
				else
				{
					// If MAN bandwidth unavailable -> reject due to MAN bandwidth
					SimLogger.getInstance().rejectedDueToBandwidth(
							task.getCloudletId(),
							CloudSim.clock(),
							SimSettings.VM_TYPES.EDGE_VM.ordinal(),
							NETWORK_DELAY_TYPES.MAN_DELAY);
				}
				
				break;
			}
			case RESPONSE_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_MOBILE_DEVICE:
			{
				// Remote edge finished execution; sending result back to original edge via MAN then WLAN last hop.
				// Mobility check performed after MAN leg before WLAN leg scheduling.
				Task task = (Task) ev.getData();
				networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID+1);
				
				//SimLogger.printLine(CloudSim.clock() + ": " + getName() + ": task #" + task.getCloudletId() + " received from edge");
				double delay = networkModel.getDownloadDelay(task.getAssociatedHostId(), task.getMobileDeviceId(), task);
				
				if(delay > 0)
				{
					Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(),CloudSim.clock()+delay);
					if(task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId())
					{
						networkModel.downloadStarted(currentLocation, SimSettings.GENERIC_EDGE_DEVICE_ID);
						SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), delay, NETWORK_DELAY_TYPES.WLAN_DELAY);
						schedule(getId(), delay, RESPONSE_RECEIVED_BY_MOBILE_DEVICE, task);
					}
					else
					{
						SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
					}
				}
				else
				{
					SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), NETWORK_DELAY_TYPES.WLAN_DELAY);
				}
				
				break;
			}
			case RESPONSE_RECEIVED_BY_MOBILE_DEVICE:
			{
				// Final delivery; mark task completion (success path).
				Task task = (Task) ev.getData();
				
				if(task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);
				else
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);
				
				SimLogger.getInstance().taskEnded(task.getCloudletId(), CloudSim.clock());
				break;
			}
			default:
				// Defensive: unexpected tag indicates logic/config error.
				SimLogger.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker. Terminating simulation...");
				System.exit(0);
				break;
		}
	}

	private void offloadSingleTask(Task task, Vm selectedVM, NetworkModel networkModel) {
		// Calculate upload delay relative to the VM's host
		int nextHopId = (selectedVM != null) ? selectedVM.getHost().getDatacenter().getId() : -1;

		double delay = (nextHopId != -1) ? networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task) : 0;

		SimSettings.NETWORK_DELAY_TYPES delayType = SimSettings.NETWORK_DELAY_TYPES.WLAN_DELAY;
		int vmType = SimSettings.VM_TYPES.EDGE_VM.ordinal();
		int nextEvent = REQUEST_RECEIVED_BY_EDGE_DEVICE;
		int nextDeviceForNetworkModel = SimSettings.GENERIC_EDGE_DEVICE_ID;

		if (delay > 0 && selectedVM != null) {
			task.setAssociatedDatacenterId(nextHopId);
			task.setAssociatedHostId(selectedVM.getHost().getId());
			task.setAssociatedVmId(selectedVM.getId());

			// --- CRITICAL FIX START ---
			// Explicitly set CloudSim Core fields so the Datacenter can find the VM owner
			task.setVmId(selectedVM.getId());
			task.setUserId(getId());
			// --- CRITICAL FIX END ---

			getCloudletList().add(task);
			bindCloudletToVm(task.getCloudletId(), selectedVM.getId());

			if (selectedVM instanceof EdgeVM) {
				EdgeHost host = (EdgeHost)(selectedVM.getHost());
				if (host.getLocation().getServingWlanId() != task.getSubmittedLocation().getServingWlanId()) {
					nextEvent = REQUEST_RECEIVED_BY_EDGE_DEVICE_TO_RELAY_NEIGHBOR;
				}
			}

			networkModel.uploadStarted(task.getSubmittedLocation(), nextDeviceForNetworkModel);
			SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
			SimLogger.getInstance().setUploadDelay(task.getCloudletId(), delay, delayType);

			schedule(getId(), delay, nextEvent, task);
		} else {
			// Failure logging
			if (selectedVM == null) {
				SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), vmType);
			} else {
				SimLogger.getInstance().rejectedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), vmType, delayType);
			}
		}
	}

	public void submitTask(TaskProperty edgeTask) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();

		// Create the task (Partitionable or Normal)
		Task task = createTask(edgeTask);

		Location currentLocation = SimManager.getInstance().getMobilityModel().
				getLocation(task.getMobileDeviceId(), CloudSim.clock());

		task.setSubmittedLocation(currentLocation);

		// Add log for the main task
		SimLogger.getInstance().addLog(task.getMobileDeviceId(),
				task.getCloudletId(),
				task.getTaskType(),
				(int)task.getCloudletLength(),
				(int)task.getCloudletFileSize(),
				(int)task.getCloudletOutputSize());

		// --- NEW LOGIC: Check for Partitionable Task ---
		if (task instanceof PartititionableTask && taskPartitionOption.equals("FULL")) {
			PartititionableTask pTask = (PartititionableTask) task;

			// Generate subtasks
			List<SubTask> subtasks = pTask.generateSubtasks();

			long startTime = System.nanoTime();
			// Retrieve list of VMs for subtasks
			List<EdgeVM	> vmList = ((UAVEdgeOrchestrator) SimManager.getInstance().getEdgeOrchestrator()).getUAVListToOffloadTo(subtasks).stream().map(UAV::getVm).toList();

			long estimatedTime = System.nanoTime() - startTime;
			// SimLogger.getInstance().setOrchestratorOverhead(task.getCloudletId(), estimatedTime);

			if (vmList != null && !vmList.isEmpty() && vmList.size() == subtasks.size()) {

				// Offload subtasks one by one
				for (int i = 0; i < subtasks.size(); i++) {
					Task subtask = subtasks.get(i);
					Vm selectedVM = vmList.get(i);

					// Important: Set the location of the subtask to match the parent's current location
					subtask.setSubmittedLocation(currentLocation);
					subtask.setUserId(getId());

					SimLogger.getInstance().addLog(subtask.getMobileDeviceId(),
							subtask.getCloudletId(),
							subtask.getTaskType(),
							(int)subtask.getCloudletLength(),
							(int)subtask.getCloudletFileSize(),
							(int)subtask.getCloudletOutputSize());

					offloadSingleTask(subtask, selectedVM, networkModel);
				}
			} else {
				// Fallback or Failure: If no VMs returned or mismatch
				SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
			}

		} else {
			// --- STANDARD LOGIC for Normal Tasks ---
			long startTime = System.nanoTime();
			int nextHopId = SimManager.getInstance().getEdgeOrchestrator().getDeviceToOffload(task);
			long estimatedTime = System.nanoTime() - startTime;

			SimLogger.getInstance().setOrchestratorOverhead(task.getCloudletId(), estimatedTime);

			Vm selectedVM = null;
			// Only check VM if connection is valid
			if (networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task) > 0) {
				selectedVM = SimManager.getInstance().getEdgeOrchestrator().getVmToOffload(task, nextHopId);
			}

			offloadSingleTask(task, selectedVM, networkModel);
		}
	}

	private void submitTaskToVm(Task task, SimSettings.VM_TYPES vmType) {
		// FIX: Use the explicitly stored Datacenter ID instead of the Broker Map lookup.
		// The map lookup (getVmsToDatacentersMap().get(task.getVmId())) returns null
		// because the subtask VMs might not be fully registered in this Broker's local map.
		int targetDatacenterId = task.getAssociatedDatacenterId();

		// Safety check
		if (targetDatacenterId <= 0) {
			// Fallback to map if associated ID wasn't set (should not happen with correct logic)
			Integer mappedId = getVmsToDatacentersMap().get(task.getVmId());
			if (mappedId != null) {
				targetDatacenterId = mappedId;
			}
		}

		if (targetDatacenterId > 0) {
			schedule(targetDatacenterId, 0, CloudSimTags.CLOUDLET_SUBMIT, task);

			SimLogger.getInstance().taskAssigned(task.getCloudletId(),
					task.getAssociatedDatacenterId(),
					task.getAssociatedHostId(),
					task.getAssociatedVmId(),
					vmType.ordinal());
		} else {
			SimLogger.printLine(getName() + ": Error - Target Datacenter ID not found for Task " + task.getCloudletId());
		}
	}
	
	private Task createTask(TaskProperty edgeTask){
		// Builds a Task (Cloudlet) with:
		//  - Custom CPU utilization model (predictive dynamic utilization)
		//  - Full (constant) utilization for RAM and BW models
		// Binds task back into utilization model for feedback-based prediction.
		UtilizationModel utilizationModel = new UtilizationModelFull(); /*UtilizationModelStochastic*/
		UtilizationModel utilizationModelCPU = getCpuUtilizationModel();

		PartititionableTask task = new PartititionableTask(edgeTask.getMobileDeviceId(), ++taskIdCounter,
				edgeTask.getLength(), edgeTask.getPesNumber(),
				edgeTask.getInputFileSize(), edgeTask.getOutputFileSize(),
				utilizationModelCPU, utilizationModel, utilizationModel, Arrays.asList(50, 50));
		
		//set the owner of this task
		task.setUserId(this.getId());
		task.setTaskType(edgeTask.getTaskType());
		
		if (utilizationModelCPU instanceof CpuUtilizationModel_Custom) {
			((CpuUtilizationModel_Custom)utilizationModelCPU).setTask(task);
		}
		
		// link reverse reference for dynamic prediction
		return task;
	}
}
