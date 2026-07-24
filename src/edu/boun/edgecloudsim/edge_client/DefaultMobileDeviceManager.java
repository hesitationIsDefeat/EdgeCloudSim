/*
 * Title:        EdgeCloudSim - Mobile Device Manager
 * 
 * Description: 
 * DefaultMobileDeviceManager is responsible for submitting the tasks to the related
 * device by using the Edge Orchestrator. It also takes proper actions 
 * when the execution of the tasks are finished.
 * By default, DefaultMobileDeviceManager sends tasks to the edge servers or
 * cloud servers. If you want to use different topology, for example
 * MAN edge server, you should modify the flow defined in this class.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.edge_client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.core.SimSettings.NETWORK_DELAY_TYPES;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.TaskProperty;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * Default implementation of MobileDeviceManager for standard edge computing scenarios.
 * Handles task submission, orchestration, and network communication for mobile devices
 * in edge-cloud computing environments with support for edge servers and cloud servers.
 */
public class DefaultMobileDeviceManager extends MobileDeviceManager {
	// Custom event tags to avoid conflicts with CloudSim's internal tags
	private static final int BASE = 100000; // Base value for custom event tags
	private static final int REQUEST_RECEIVED_BY_CLOUD = BASE + 1;
	private static final int REQUEST_RECEIVED_BY_EDGE_DEVICE = BASE + 2;
	private static final int RESPONSE_RECEIVED_BY_MOBILE_DEVICE = BASE + 3;
	private int taskIdCounter=0;  // Counter for generating unique task IDs
	private final Map<Integer, PartitionState> partitionStateMap = new HashMap<Integer, PartitionState>();
	
	/**
	 * Constructor for default mobile device manager.
	 * @throws Exception if manager initialization fails
	 */
	public DefaultMobileDeviceManager() throws Exception{
	}

	/**
	 * Initializes the mobile device manager.
	 * No special initialization required for default implementation.
	 */
	@Override
	public void initialize() {
	}

	private static class PendingTaskSubmission {
		private final Task task;
		private final Vm selectedVm;

		private PendingTaskSubmission(Task task, Vm selectedVm) {
			this.task = task;
			this.selectedVm = selectedVm;
		}
	}

	private static class PartitionState {
		private final int parentTaskId;
		private final int totalChildren;
		private int completedChildren;
		private boolean failed;
		// Accumulates the upload/download delay of every child so the parent's
		// own log entry (which is what feeds the network delay statistics)
		// reflects the network cost actually incurred by its sub-tasks - the
		// parent itself is never bound to a VM/Cloudlet and never uploads or
		// downloads anything on its own.
		private double uploadDelaySum;
		private double downloadDelaySum;

		private PartitionState(int parentTaskId, int totalChildren) {
			this.parentTaskId = parentTaskId;
			this.totalChildren = totalChildren;
			this.completedChildren = 0;
			this.failed = false;
			this.uploadDelaySum = 0;
			this.downloadDelaySum = 0;
		}
	}
	
	/**
	 * Creates and returns the CPU utilization model for tasks.
	 * Uses custom utilization model with application-specific parameters.
	 * @return CpuUtilizationModel_Custom for realistic resource modeling
	 */
	@Override
	public UtilizationModel getCpuUtilizationModel() {
		return new CpuUtilizationModel_Custom();
	}
	
	/**
	 * Submit cloudlets to the created VMs.
	 * Overridden from parent class but not used in EdgeCloudSim's task submission model.
	 * Tasks are submitted via submitTask() method instead.
	 */
	protected void submitCloudlets() {
		// Not used in EdgeCloudSim - tasks submitted via submitTask() method
	}
	
	/**
	 * Processes a cloudlet return event when task execution completes.
	 * Handles network delays for task result delivery back to mobile device.
	 * 
	 * @param ev SimEvent containing the completed task
	 */
	protected void processCloudletReturn(SimEvent ev) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		Task task = (Task) ev.getData();
		PartitionState partitionState = null;
		if (task.isPartitionChild()) {
			partitionState = partitionStateMap.get(task.getParentTaskId());
			if (partitionState == null || partitionState.failed) {
				return;
			}
		}
		
		// Log task execution completion
		if (!task.isPartitionChild())
			SimLogger.getInstance().taskExecuted(task.getCloudletId());

		if(task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID){
			// Task completed on cloud - calculate WAN download delay for result delivery
			double WanDelay = networkModel.getDownloadDelay(SimSettings.CLOUD_DATACENTER_ID, task.getMobileDeviceId(), task);
			if(WanDelay > 0)
			{
				Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(),CloudSim.clock()+WanDelay);
				if(task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId())
				{
					networkModel.downloadStarted(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);
					SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), WanDelay, NETWORK_DELAY_TYPES.WAN_DELAY);
					schedule(getId(), WanDelay, RESPONSE_RECEIVED_BY_MOBILE_DEVICE, task);
				}
				else
				{
					SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
				}
			}
			else
			{
				SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), NETWORK_DELAY_TYPES.WAN_DELAY);
			}
		}
		else{
			// Task completed on edge server - calculate WLAN download delay for result delivery
			double WlanDelay = networkModel.getDownloadDelay(task.getAssociatedHostId(), task.getMobileDeviceId(), task);
			if(WlanDelay > 0)
			{
				Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(),CloudSim.clock()+WlanDelay);
				if(task.getSubmittedLocation().getServingWlanId() == currentLocation.getServingWlanId())
				{
					networkModel.downloadStarted(currentLocation, SimSettings.GENERIC_EDGE_DEVICE_ID);
					// Partition children don't have their own log entry (only the
					// parent does), so their download delay is rolled up into the
					// parent's PartitionState and applied once all children finish.
					if (task.isPartitionChild())
						partitionState.downloadDelaySum += WlanDelay;
					else
						SimLogger.getInstance().setDownloadDelay(task.getCloudletId(), WlanDelay, NETWORK_DELAY_TYPES.WLAN_DELAY);
					schedule(getId(), WlanDelay, RESPONSE_RECEIVED_BY_MOBILE_DEVICE, task);
				}
				else
				{
					if (task.isPartitionChild()) {
						failPartition(task.getParentTaskId(), SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_MOBILITY, NETWORK_DELAY_TYPES.WLAN_DELAY);
					}
					else {
						SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
					}
				}
			}
			else
			{
				if (task.isPartitionChild()) {
					failPartition(task.getParentTaskId(), SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH, NETWORK_DELAY_TYPES.WLAN_DELAY);
				}
				else {
					SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), NETWORK_DELAY_TYPES.WLAN_DELAY);
				}
			}
		}
	}
	
	/**
	 * Processes custom events specific to EdgeCloudSim's mobile device operations.
	 * Handles task upload completion and result delivery events.
	 * 
	 * @param ev The simulation event to process
	 */
	protected void processOtherEvent(SimEvent ev) {
		if (ev == null) {
			SimLogger.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null! Terminating simulation...");
			System.exit(1);
			return;
		}
		
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		
		switch (ev.getTag()) {
			case REQUEST_RECEIVED_BY_CLOUD:
			{
				Task task = (Task) ev.getData();

				// Mark upload as completed to cloud datacenter
				networkModel.uploadFinished(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);

				// Submit task to appropriate cloud VM
				submitTaskToVm(task,0,SimSettings.CLOUD_DATACENTER_ID);
				
				break;
			}
			case REQUEST_RECEIVED_BY_EDGE_DEVICE:
			{
				Task task;
				Vm selectedVm = null;
				if (ev.getData() instanceof PendingTaskSubmission) {
					PendingTaskSubmission pendingTask = (PendingTaskSubmission) ev.getData();
					task = pendingTask.task;
					selectedVm = pendingTask.selectedVm;
				}
				else {
					task = (Task) ev.getData();
				}

				// Mark upload as completed to edge server - this must happen even if
				// a sibling sub-task has since failed the whole partition, since the
				// network transfer for THIS task already completed (matching the
				// uploadStarted call made when it was submitted).
				networkModel.uploadFinished(task.getSubmittedLocation(), task.isPartitionChild() && selectedVm != null ? selectedVm.getHost().getId() : SimSettings.GENERIC_EDGE_DEVICE_ID);

				if (task.isPartitionChild()) {
					PartitionState partitionState = partitionStateMap.get(task.getParentTaskId());
					if (partitionState == null || partitionState.failed) {
						break;
					}
				}
				
				// Submit task to appropriate edge VM
				if (task.isPartitionChild())
					submitTaskToVm(task, 0, selectedVm);
				else
					submitTaskToVm(task, 0, SimSettings.GENERIC_EDGE_DEVICE_ID);
				
				break;
			}
			case RESPONSE_RECEIVED_BY_MOBILE_DEVICE:
			{
				Task task = (Task) ev.getData();

				// Mark download as finished based on datacenter type - this applies to
				// partition children too, and must happen even if a sibling sub-task
				// has since failed the whole partition, since the download for THIS
				// task already completed (matching its earlier downloadStarted call).
				if(task.getAssociatedDatacenterId() == SimSettings.CLOUD_DATACENTER_ID)
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.CLOUD_DATACENTER_ID);
				else if(task.getAssociatedDatacenterId() != SimSettings.MOBILE_DATACENTER_ID)
					networkModel.downloadFinished(task.getSubmittedLocation(), SimSettings.GENERIC_EDGE_DEVICE_ID);

				if (task.isPartitionChild()) {
					PartitionState partitionState = partitionStateMap.get(task.getParentTaskId());
					if (partitionState == null || partitionState.failed) {
						break;
					}
					partitionState.completedChildren++;
					if (partitionState.completedChildren >= partitionState.totalChildren) {
						// Roll up every child's upload/download delay into the parent's
						// own log entry before marking it complete, since the parent
						// never uploads/downloads anything itself.
						SimLogger.getInstance().setUploadDelay(partitionState.parentTaskId, partitionState.uploadDelaySum, NETWORK_DELAY_TYPES.WLAN_DELAY);
						SimLogger.getInstance().setDownloadDelay(partitionState.parentTaskId, partitionState.downloadDelaySum, NETWORK_DELAY_TYPES.WLAN_DELAY);
						SimLogger.getInstance().taskEnded(partitionState.parentTaskId, CloudSim.clock());
						partitionStateMap.remove(partitionState.parentTaskId);
					}
					break;
				}
				
				// Log task completion
				SimLogger.getInstance().taskEnded(task.getCloudletId(), CloudSim.clock());
				break;
			}
			default:
				SimLogger.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker. Terminating simulation...");
				System.exit(1);
				break;
		}
	}

	/**
	 * Submits a task from a mobile device for processing.
	 * Handles orchestration decisions and network delay simulation for task offloading.
	 * 
	 * @param edgeTask Task properties including requirements and mobile device context
	 */
	public void submitTask(TaskProperty edgeTask) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		if (edgeTask.isPartitionable()) {
			submitPartitionableTask(edgeTask, networkModel);
			return;
		}
		
		// Create EdgeCloudSim task from task properties
		Task task = createTask(edgeTask);
		
		// Get current location of the mobile device
		Location currentLocation = SimManager.getInstance().getMobilityModel().
				getLocation(task.getMobileDeviceId(),CloudSim.clock());
		
		// Set the location where this task was submitted
		task.setSubmittedLocation(currentLocation);

		// Add task to simulation logging system
		SimLogger.getInstance().addLog(task.getMobileDeviceId(),
				task.getCloudletId(),
				task.getTaskType(),
				(int)task.getCloudletLength(),
				(int)task.getCloudletFileSize(),
				(int)task.getCloudletOutputSize());

		// Use edge orchestrator to decide where to process this task
		int nextHopId = SimManager.getInstance().getEdgeOrchestrator().getDeviceToOffload(task);
		
		// Handle task submission based on orchestrator decision
		if(nextHopId == SimSettings.CLOUD_DATACENTER_ID){
			// Task assigned to cloud - calculate WAN upload delay
			double WanDelay = networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task);
			
			if(WanDelay>0){
				// Start network upload and schedule task arrival after delay
				networkModel.uploadStarted(currentLocation, nextHopId);
				SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
				SimLogger.getInstance().setUploadDelay(task.getCloudletId(), WanDelay, NETWORK_DELAY_TYPES.WAN_DELAY);
				schedule(getId(), WanDelay, REQUEST_RECEIVED_BY_CLOUD, task);
			}
			else
			{
				// WAN bandwidth not available - reject task
				SimLogger.getInstance().rejectedDueToBandwidth(
						task.getCloudletId(),
						CloudSim.clock(),
						SimSettings.VM_TYPES.CLOUD_VM.ordinal(),
						NETWORK_DELAY_TYPES.WAN_DELAY);
			}
		}
		else if(nextHopId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
			// Task assigned to edge server - calculate WLAN upload delay
			double WlanDelay = networkModel.getUploadDelay(task.getMobileDeviceId(), nextHopId, task);
			
			if(WlanDelay > 0){
				// Start network upload and schedule task arrival after delay
				networkModel.uploadStarted(currentLocation, nextHopId);
				schedule(getId(), WlanDelay, REQUEST_RECEIVED_BY_EDGE_DEVICE, task);
				SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
				SimLogger.getInstance().setUploadDelay(task.getCloudletId(), WlanDelay, NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
			else {
				// WLAN bandwidth not available - reject task
				SimLogger.getInstance().rejectedDueToBandwidth(
						task.getCloudletId(),
						CloudSim.clock(),
						SimSettings.VM_TYPES.EDGE_VM.ordinal(),
						NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
		}
		else {
			// Unknown orchestrator decision - terminate simulation
			SimLogger.printLine("Unknown nextHopId! Terminating simulation...");
			System.exit(1);
		}
	}

	private void submitPartitionableTask(TaskProperty edgeTask, NetworkModel networkModel) {
		int parentTaskId = ++taskIdCounter;
		SimLogger.getInstance().addLog(edgeTask.getMobileDeviceId(), parentTaskId, SimManager.getInstance().getLoadGeneratorModel().getTaskTypeOfDevice(edgeTask.getMobileDeviceId()), (int)edgeTask.getLength(), (int)edgeTask.getInputFileSize(), (int)edgeTask.getOutputFileSize());
		SimLogger.getInstance().taskStarted(parentTaskId, CloudSim.clock());
		SimLogger.getInstance().taskAssigned(parentTaskId, SimSettings.GENERIC_EDGE_DEVICE_ID, 0, 0, SimSettings.VM_TYPES.EDGE_VM.ordinal());

		PartitionState partitionState = new PartitionState(parentTaskId, edgeTask.getPartitionCount());
		partitionStateMap.put(parentTaskId, partitionState);

		List<TaskProperty> childProperties = splitTaskProperty(edgeTask);
		List<Task> childTasks = new ArrayList<Task>(childProperties.size());
		for (int i = 0; i < childProperties.size(); i++) {
			Task childTask = createTask(childProperties.get(i));
			childTask.setPartitionChild(true);
			childTask.setParentTaskId(parentTaskId);
			childTask.setChildIndex(i);
			childTask.setChildCount(childProperties.size());

			Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(childTask.getMobileDeviceId(), CloudSim.clock());
			childTask.setSubmittedLocation(currentLocation);

			childTasks.add(childTask);
		}

		// Select the target VM/UAV for every sibling sub-task at the same time so
		// the orchestrator can account for the cumulative load the whole batch
		// will place on shared UAVs before any of them are actually bound -
		// otherwise each selection would be blind to the load its just-chosen
		// siblings are about to add to the same UAV.
		List<Vm> selectedVMs = SimManager.getInstance().getEdgeOrchestrator()
				.getVmsToOffload(childTasks, SimSettings.GENERIC_EDGE_DEVICE_ID);

		for (int i = 0; i < childTasks.size(); i++) {
			if (partitionState.failed) {
				break;
			}

			Task childTask = childTasks.get(i);
			Vm selectedVM = selectedVMs.get(i);
			if (selectedVM == null) {
				failPartition(parentTaskId, SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY, NETWORK_DELAY_TYPES.WLAN_DELAY);
				break;
			}

			double wlanDelay = networkModel.getUploadDelay(childTask.getMobileDeviceId(), selectedVM.getHost().getId(), childTask);
			if (wlanDelay <= 0) {
				failPartition(parentTaskId, SimLogger.TASK_STATUS.REJECTED_DUE_TO_BANDWIDTH, NETWORK_DELAY_TYPES.WLAN_DELAY);
				break;
			}

			// Track this child's upload delay so it can be rolled up into the
			// parent's log entry once every sibling has finished.
			partitionState.uploadDelaySum += wlanDelay;

			networkModel.uploadStarted(childTask.getSubmittedLocation(), selectedVM.getHost().getId());
			schedule(getId(), wlanDelay, REQUEST_RECEIVED_BY_EDGE_DEVICE, new PendingTaskSubmission(childTask, selectedVM));
		}
	}

	private List<TaskProperty> splitTaskProperty(TaskProperty edgeTask) {
		int childCount = Math.max(1, edgeTask.getPartitionCount());
		List<TaskProperty> childProperties = new ArrayList<TaskProperty>(childCount);

		long[] lengthParts = splitValue(edgeTask.getLength(), childCount);
		long[] uploadParts = splitValue(edgeTask.getInputFileSize(), childCount);
		long[] downloadParts = splitValue(edgeTask.getOutputFileSize(), childCount);

		for (int i = 0; i < childCount; i++) {
			childProperties.add(new TaskProperty(CloudSim.clock(), edgeTask.getMobileDeviceId(), SimManager.getInstance().getLoadGeneratorModel().getTaskTypeOfDevice(edgeTask.getMobileDeviceId()), edgeTask.getPesNumber(), lengthParts[i], uploadParts[i], downloadParts[i]));
		}

		return childProperties;
	}

	private long[] splitValue(long total, int parts) {
		long[] values = new long[parts];
		long base = parts == 0 ? total : total / parts;
		long remainder = parts == 0 ? 0 : total % parts;
		for (int i = 0; i < parts; i++) {
			values[i] = base + (i < remainder ? 1 : 0);
			if (total > 0 && values[i] == 0) {
				values[i] = 1;
			}
		}
		return values;
	}

	private void failPartition(int parentTaskId, SimLogger.TASK_STATUS status, NETWORK_DELAY_TYPES delayType) {
		PartitionState partitionState = partitionStateMap.get(parentTaskId);
		if (partitionState == null || partitionState.failed) {
			return;
		}

		partitionState.failed = true;
		if (status == SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY) {
			SimLogger.getInstance().rejectedDueToVMCapacity(parentTaskId, CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
		}
		else if (status == SimLogger.TASK_STATUS.REJECTED_DUE_TO_BANDWIDTH) {
			SimLogger.getInstance().rejectedDueToBandwidth(parentTaskId, CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal(), delayType);
		}
		else if (status == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH) {
			SimLogger.getInstance().failedDueToBandwidth(parentTaskId, CloudSim.clock(), delayType);
		}
		else if (status == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_MOBILITY) {
			SimLogger.getInstance().failedDueToMobility(parentTaskId, CloudSim.clock());
		}
		partitionStateMap.remove(parentTaskId);
	}
	
	/**
	 * Submits a task to a specific VM in the designated datacenter.
	 * Handles VM selection, resource assignment, and task binding.
	 * 
	 * @param task The task to be submitted
	 * @param delay Additional delay before task execution
	 * @param datacenterId The target datacenter ID (cloud or edge)
	 */
	private void submitTaskToVm(Task task, double delay, int datacenterId) {
		Vm selectedVM = SimManager.getInstance().getEdgeOrchestrator().getVmToOffload(task, datacenterId);
		// Non-partitioned tasks have no PartitionState/failPartition path to fall
		// back on, so a missing VM here must be logged directly - otherwise the
		// rejection (and its log entry) is silently dropped and never counted as
		// a VM-capacity failure.
		if (selectedVM == null && !task.isPartitionChild()) {
			int vmType = (datacenterId == SimSettings.CLOUD_DATACENTER_ID)
					? SimSettings.VM_TYPES.CLOUD_VM.ordinal()
					: SimSettings.VM_TYPES.EDGE_VM.ordinal();
			SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), vmType);
			return;
		}
		submitTaskToVm(task, delay, selectedVM);
	}

	private void submitTaskToVm(Task task, double delay, Vm selectedVM) {
		// Use orchestrator to select appropriate VM for this task
		if (selectedVM == null) {
			if (task.isPartitionChild()) {
				failPartition(task.getParentTaskId(), SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY, NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
			return;
		}
		
		// Determine VM type for logging purposes
		int vmType = 0;
		int datacenterId = selectedVM.getHost().getDatacenter().getId();
		if(datacenterId == SimSettings.CLOUD_DATACENTER_ID)
			vmType = SimSettings.VM_TYPES.CLOUD_VM.ordinal();
		else
			vmType = SimSettings.VM_TYPES.EDGE_VM.ordinal();
		
		// Associate task with the selected datacenter
		if(datacenterId == SimSettings.CLOUD_DATACENTER_ID)
			task.setAssociatedDatacenterId(SimSettings.CLOUD_DATACENTER_ID);
		else
			task.setAssociatedDatacenterId(selectedVM.getHost().getDatacenter().getId());

		// Save resource assignment information
		task.setAssociatedHostId(selectedVM.getHost().getId());
		task.setAssociatedVmId(selectedVM.getId());
		
		// Bind task to the selected VM using CloudSim mechanisms
		getCloudletList().add(task);
		bindCloudletToVm(task.getCloudletId(),selectedVM.getId());
		
		//SimLogger.printLine(CloudSim.clock() + ": Cloudlet#" + task.getCloudletId() + " is submitted to VM#" + task.getVmId());
		schedule(getVmsToDatacentersMap().get(task.getVmId()), delay, CloudSimTags.CLOUDLET_SUBMIT, task);

		if (!task.isPartitionChild()) {
			SimLogger.getInstance().taskAssigned(task.getCloudletId(),
					selectedVM.getHost().getDatacenter().getId(),
					selectedVM.getHost().getId(),
					selectedVM.getId(),
					vmType);
		}
	}
	
	/**
	 * Creates an EdgeCloudSim Task from TaskProperty specifications.
	 * Configures resource utilization models and assigns task metadata.
	 * 
	 * @param edgeTask Task properties defining requirements and constraints
	 * @return Configured Task object ready for execution
	 */
	private Task createTask(TaskProperty edgeTask){
		// Use full utilization model for RAM and bandwidth (standard approach)
		UtilizationModel utilizationModel = new UtilizationModelFull();
		// Use custom CPU utilization model for realistic resource modeling
		UtilizationModel utilizationModelCPU = getCpuUtilizationModel();

		// Create EdgeCloudSim task with specified parameters
		Task task = new Task(edgeTask.getMobileDeviceId(), ++taskIdCounter,
				edgeTask.getLength(), edgeTask.getPesNumber(),
				edgeTask.getInputFileSize(), edgeTask.getOutputFileSize(),
				utilizationModelCPU, utilizationModel, utilizationModel);
		
		// Set task ownership and classification
		task.setUserId(this.getId());
		task.setTaskType(edgeTask.getTaskType());
		
		// Associate task with CPU utilization model for dynamic utilization calculation
		if (utilizationModelCPU instanceof CpuUtilizationModel_Custom) {
			((CpuUtilizationModel_Custom)utilizationModelCPU).setTask(task);
		}
		
		return task;
	}
}
