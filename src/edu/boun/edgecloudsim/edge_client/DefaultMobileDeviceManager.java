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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import edu.boun.edgecloudsim.utils.StreamTaskProperty;
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
	// ONAT: fires after a DAG layer's inter-layer relay delay (if its results have to
	// reach another UAV at all, see getInterLayerRelayDelay()) has elapsed to actually
	// submit the next layer's children - carries a PendingLayerSubmission as its event data.
	private static final int SUBMIT_NEXT_DAG_LAYER = BASE + 4;
	// ONAT: fires SimSettings.getTaskTimeout(taskType) seconds after a top-level task's
	// creation (see armTimeout()) to log it as TIMED_OUT if it's still in flight - does
	// NOT stop the task itself (see processTaskTimeout) - carries the top-level task id
	// (a plain task's own cloudlet id, or a partitioned/DAG task's parentTaskId) as its
	// event data.
	private static final int TASK_TIMEOUT = BASE + 5;
	// ONAT: fires right after the last node of a stream layer completed to submit the next
	// layer's nodes - carries the stream id as its event data (see onStreamNodeEnded()).
	private static final int SUBMIT_NEXT_STREAM_LAYER = BASE + 6;
	// ONAT: the "stream id" of a task that is not a node of a stream
	private static final int NO_STREAM = -1;
	private int taskIdCounter=0;  // Counter for generating unique task IDs
	private final Map<Integer, PartitionState> partitionStateMap = new HashMap<Integer, PartitionState>();
	// ONAT: every stream request still being executed, keyed by the id it is logged under
	private final Map<Integer, StreamState> streamStateMap = new HashMap<Integer, StreamState>();
	// ONAT: stream node task id (a plain task's own id, or a partitioned node's parent id) ->
	// id of the stream it belongs to, until the node ends - see recordStreamNodeOutput()
	private final Map<Integer, Integer> streamNodeUnits = new HashMap<Integer, Integer>();
	// ONAT: hard task-timeout bookkeeping (see SimSettings.isTaskTimeoutEnabled(taskType)/
	// getTaskTimeout(taskType)) - empty/unused for every app that doesn't set task_timeout.
	// Keyed by top-level task id (own cloudlet id for a plain task, parentTaskId for a
	// partitioned/DAG task); value is the VM tier (SimSettings.VM_TYPES ordinal) to log
	// the timeout against if it fires - see armTimeout()/updateTimeoutVmTypeHint() below.
	// A timeout does NOT cancel or otherwise touch the task - it keeps
	// uploading/processing/downloading exactly as if the deadline had never been
	// reached (more realistic: a client giving up on a slow request doesn't make the
	// server stop computing it) - firing only logs one TIMED_OUT outcome and suppresses
	// the task's own later, redundant natural outcome (see timedOutTaskIds).
	private final Map<Integer, Integer> pendingTimeouts = new HashMap<Integer, Integer>();
	// ONAT: top-level ids of PLAIN tasks already logged as TIMED_OUT - their own eventual
	// natural completion/failure must skip logging a second outcome (the task itself runs
	// to completion unaffected; only the redundant SimLogger call is suppressed).
	// Partitioned/DAG tasks don't need this: PartitionState.failed (set by failPartition)
	// already serves the same purpose for their children.
	private final Set<Integer> timedOutTaskIds = new HashSet<Integer>();
	
	/**
	 * Constructor for default mobile device manager.
	 * @throws Exception if manager initialization fails
	 */
	public DefaultMobileDeviceManager() throws Exception{
	}

	/**
	 * Initializes the mobile device manager.
	 * ONAT: registers itself to be told how the nodes of a stream end (see onStreamNodeEnded()).
	 */
	@Override
	public void initialize() {
		// ONAT: stream nodes' outcomes are reported by the logger (see SimLogger.addStreamNodeLog())
		SimLogger.getInstance().setStreamNodeListener(this::onStreamNodeEnded);
	}

	/**
	 * ONAT: a stream request being executed. A stream is a DAG whose nodes are applications:
	 * every node of a layer is submitted as an application task of its own (so it is
	 * offloaded, and may even be partitioned, exactly like that application requested alone)
	 * once every node of the previous layer has completed AND that layer's output has been
	 * relayed to the UAVs the next layer runs on (see releaseStreamLayer()); the nodes of one
	 * layer run in parallel. Any failing node fails the whole stream - there is no partial
	 * success - and a timeout lets the running nodes finish first (see timeoutStream()).
	 */
	private static class StreamState {
		private final int streamId;
		// Layers not released yet, in dependency order - consumed one per finished layer
		private final Deque<List<TaskProperty>> remainingLayers;
		// Size of the layer most recently STARTED, and how many of its nodes have ended
		// (completed or failed) - the layer is running while endedNodes < currentLayerSize
		private int currentLayerSize;
		private int endedNodes;
		// Output data (KB) of the current layer's completed nodes, per UAV (host id) that
		// produced it - the stream counterpart of PartitionState.currentLayerOutputKbByHost,
		// consumed by getInterLayerRelayDelay() when the layer is done. Reset per layer.
		private final Map<Integer, Long> layerOutputKbByHost;
		// The stream's timeout fired while nodes were still running: they finish, no further
		// layer starts, and the stream is logged TIMED_OUT once the last of them has ended
		private boolean timedOut;
		// Set once the stream is resolved (failed or timed out), so a layer still being
		// started knows to stop
		private boolean resolved;

		private StreamState(int streamId, Deque<List<TaskProperty>> remainingLayers) {
			this.streamId = streamId;
			this.remainingLayers = remainingLayers;
			this.currentLayerSize = 0;
			this.endedNodes = 0;
			this.layerOutputKbByHost = new HashMap<Integer, Long>();
			this.timedOut = false;
			this.resolved = false;
		}
	}

	/**
	 * ONAT: a partitioned task (flat or DAG) already split, with its first sibling batch built
	 * but nothing registered or submitted yet - so the UAVs of that batch can be chosen (and
	 * the relay delay towards them worked out) before the task starts. See
	 * preparePartitionedTask()/startPartitionedTask().
	 */
	private static class PreparedPartition {
		private final TaskProperty edgeTask;
		private final int parentTaskId;
		private final List<Task> firstBatch;
		private final int totalChildCount;
		// Layers after the first one for a DAG, null for a flat partition
		private final Deque<List<TaskProperty>> remainingLayers;

		private PreparedPartition(TaskProperty edgeTask, int parentTaskId, List<Task> firstBatch, int totalChildCount, Deque<List<TaskProperty>> remainingLayers) {
			this.edgeTask = edgeTask;
			this.parentTaskId = parentTaskId;
			this.firstBatch = firstBatch;
			this.totalChildCount = totalChildCount;
			this.remainingLayers = remainingLayers;
		}
	}

	/**
	 * ONAT: one node of a stream layer that is about to start: either a plain application task
	 * or a prepared partitioned one, plus the VMs the orchestrator chose for its tasks (in the
	 * order of getPlacementTasks()).
	 */
	private static class StreamNodePlan {
		private final Task plainTask;
		private final PreparedPartition partition;
		private List<Vm> selectedVMs;

		private StreamNodePlan(Task plainTask, PreparedPartition partition) {
			this.plainTask = plainTask;
			this.partition = partition;
		}

		/** The tasks that need a UAV before this node can start. */
		private List<Task> getPlacementTasks() {
			return plainTask != null ? Collections.singletonList(plainTask) : partition.firstBatch;
		}
	}

	private static class PendingStreamLayer {
		private final int streamId;
		private final List<StreamNodePlan> plans;

		private PendingStreamLayer(int streamId, List<StreamNodePlan> plans) {
			this.streamId = streamId;
			this.plans = plans;
		}
	}

	private static class PendingTaskSubmission {
		private final Task task;
		private final Vm selectedVm;

		private PendingTaskSubmission(Task task, Vm selectedVm) {
			this.task = task;
			this.selectedVm = selectedVm;
		}
	}

	private static class PendingLayerSubmission {
		private final int parentTaskId;
		private final List<Task> childTasks;
		// ONAT: the UAVs/VMs already chosen for childTasks (same order) when the previous
		// layer finished - the relay delay depends on where they are, so they have to be
		// known before the layer is actually submitted.
		private final List<Vm> selectedVMs;

		private PendingLayerSubmission(int parentTaskId, List<Task> childTasks, List<Vm> selectedVMs) {
			this.parentTaskId = parentTaskId;
			this.childTasks = childTasks;
			this.selectedVMs = selectedVMs;
		}
	}

	private static class PartitionState {
		private final int parentTaskId;
		// Not final: for a DAG task this is reset to the CURRENT layer's size each time
		// a new layer is submitted (see submitChildTaskBatch) rather than the total
		// sub-task count across the whole DAG.
		private int totalChildren;
		private int completedChildren;
		private boolean failed;
		// Accumulates the upload/download delay of every child (every layer, for a DAG
		// task) so the parent's own log entry (which is what feeds the network delay
		// statistics) reflects the network cost actually incurred by its sub-tasks - the
		// parent itself is never bound to a VM/Cloudlet and never uploads or
		// downloads anything on its own.
		private double uploadDelaySum;
		private double downloadDelaySum;
		// DAG-specific bookkeeping (unused/false-null for a flat partitioned task):
		// isDag marks that completedChildren/totalChildren track only the currently
		// running layer, totalDagChildCount is the sub-task count across EVERY layer
		// (used for CPU-utilization-share purposes, see buildChildTasks), and
		// remainingLayers holds every layer not yet submitted - consumed one at a time
		// as each running layer finishes, so a layer never starts before every
		// sub-task in the previous layer has completed.
		private boolean isDag;
		private int totalDagChildCount;
		private Deque<List<TaskProperty>> remainingLayers;
		// ONAT: output data (KB) of the CURRENTLY running DAG layer's finished children,
		// summed per UAV (host id) that produced it - reset per layer in
		// submitChildTaskBatch. Used to work out how much of the layer's combined result has
		// to be relayed to the UAVs the next layer runs on (see getInterLayerRelayDelay()).
		// Unused for a flat (non-DAG) partition.
		private final Map<Integer, Long> currentLayerOutputKbByHost;

		private PartitionState(int parentTaskId, int totalChildren) {
			this.parentTaskId = parentTaskId;
			this.totalChildren = totalChildren;
			this.completedChildren = 0;
			this.failed = false;
			this.uploadDelaySum = 0;
			this.downloadDelaySum = 0;
			this.isDag = false;
			this.totalDagChildCount = totalChildren;
			this.remainingLayers = null;
			this.currentLayerOutputKbByHost = new HashMap<Integer, Long>();
		}
	}

	/**
	 * ONAT: arms the hard task-timeout for a newly submitted top-level task (a plain
	 * task's own id, or a partitioned/DAG task's parentTaskId) - the deadline is per-app
	 * (see applications.xml's task_timeout), so the task's own type must be supplied.
	 * No-op when task_timeout is disabled for that app (<=0), so apps that don't set it
	 * see no behavior change at all.
	 */
	private void armTimeout(int topLevelTaskId, int taskType) {
		SimSettings SS = SimSettings.getInstance();
		if (!SS.isTaskTimeoutEnabled(taskType))
			return;
		pendingTimeouts.put(topLevelTaskId, SimSettings.VM_TYPES.EDGE_VM.ordinal());
		schedule(getId(), SS.getTaskTimeout(taskType), TASK_TIMEOUT, topLevelTaskId);
	}

	/**
	 * Updates the VM tier to log against if topLevelTaskId's timeout fires later (a plain
	 * task may move from uploading-to-cloud/edge to actually being bound to a VM of that
	 * tier) - no-op if the task was never armed (timeout disabled) or already resolved.
	 */
	private void updateTimeoutVmTypeHint(int topLevelTaskId, int vmTypeHint) {
		if (pendingTimeouts.containsKey(topLevelTaskId))
			pendingTimeouts.put(topLevelTaskId, vmTypeHint);
	}

	/**
	 * Marks topLevelTaskId fully resolved (succeeded or failed through its own normal
	 * path, NOT via a timeout) - disarms its pending TASK_TIMEOUT check. Safe to call
	 * unconditionally (no-op if the task was never armed).
	 */
	private void resolveTimeout(int topLevelTaskId) {
		pendingTimeouts.remove(topLevelTaskId);
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
					if (!timedOutTaskIds.remove(task.getCloudletId())) {
						SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
					}
					resolveTimeout(task.getCloudletId());
				}
			}
			else
			{
				if (!timedOutTaskIds.remove(task.getCloudletId())) {
					SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), NETWORK_DELAY_TYPES.WAN_DELAY);
				}
				resolveTimeout(task.getCloudletId());
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
						if (!timedOutTaskIds.remove(task.getCloudletId())) {
							SimLogger.getInstance().failedDueToMobility(task.getCloudletId(), CloudSim.clock());
						}
						resolveTimeout(task.getCloudletId());
					}
				}
			}
			else
			{
				if (task.isPartitionChild()) {
					failPartition(task.getParentTaskId(), SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH, NETWORK_DELAY_TYPES.WLAN_DELAY);
				}
				else {
					if (!timedOutTaskIds.remove(task.getCloudletId())) {
						SimLogger.getInstance().failedDueToBandwidth(task.getCloudletId(), CloudSim.clock(), NETWORK_DELAY_TYPES.WLAN_DELAY);
					}
					resolveTimeout(task.getCloudletId());
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
				// ONAT: a plain stream node is uploaded to its preselected VM exactly like a
				// partition child (see submitPlainTaskToVm()).
				networkModel.uploadFinished(task.getSubmittedLocation(), selectedVm != null ? selectedVm.getHost().getId() : SimSettings.GENERIC_EDGE_DEVICE_ID);

				if (task.isPartitionChild()) {
					PartitionState partitionState = partitionStateMap.get(task.getParentTaskId());
					if (partitionState == null || partitionState.failed) {
						break;
					}
				}
				
				// Submit task to appropriate edge VM
				if (task.isPartitionChild() || selectedVm != null)
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
					// ONAT: only meaningful for a DAG task (see currentLayerOutputKbByHost) -
					// harmless no-op accumulation for a flat partition, which never reads it.
					partitionState.currentLayerOutputKbByHost.merge(task.getAssociatedHostId(), task.getCloudletOutputSize(), Long::sum);
					if (partitionState.completedChildren >= partitionState.totalChildren) {
						if (partitionState.isDag && !partitionState.remainingLayers.isEmpty()) {
							// Every sub-task in the current DAG layer just finished -
							// only now (not before) may the next layer's sub-tasks be
							// submitted, since they depend on this layer's completion.
							List<Task> nextLayerTasks = buildChildTasks(partitionState.parentTaskId,
									partitionState.remainingLayers.poll(), partitionState.totalDagChildCount);

							// ONAT: the next layer's UAVs are chosen now, since whether (and how
							// much of) this finished layer's output has to be relayed to another
							// UAV depends on where the next layer runs.
							List<Vm> nextLayerVMs = SimManager.getInstance().getEdgeOrchestrator()
									.getVmsToOffload(nextLayerTasks, SimSettings.GENERIC_EDGE_DEVICE_ID);

							// ONAT: simulate the hand-off delay of relaying this finished
							// layer's output to the other UAVs the next layer runs on (zero if it
							// already sits on every one of them) - logged into the parent's own
							// entry (both as its own UAV_DELAY category and, via
							// getNetworkDelay()'s total, folded into the overall network
							// delay/service time) and only THEN is the next layer actually
							// submitted.
							double interLayerDelay = getInterLayerRelayDelay(
									partitionState.currentLayerOutputKbByHost, nextLayerVMs, networkModel);
							if (interLayerDelay > 0)
								SimLogger.getInstance().addInterLayerDelay(partitionState.parentTaskId, interLayerDelay);
							schedule(getId(), interLayerDelay, SUBMIT_NEXT_DAG_LAYER,
									new PendingLayerSubmission(partitionState.parentTaskId, nextLayerTasks, nextLayerVMs));
						}
						else {
							// Roll up every child's upload/download delay into the parent's
							// own log entry before marking it complete, since the parent
							// never uploads/downloads anything itself.
							SimLogger.getInstance().setUploadDelay(partitionState.parentTaskId, partitionState.uploadDelaySum, NETWORK_DELAY_TYPES.WLAN_DELAY);
							SimLogger.getInstance().setDownloadDelay(partitionState.parentTaskId, partitionState.downloadDelaySum, NETWORK_DELAY_TYPES.WLAN_DELAY);
							// ONAT: for a stream node, its output (what its last layer produced)
							// is what the next stream layer may have to relay
							recordStreamNodeOutput(partitionState.parentTaskId, partitionState.currentLayerOutputKbByHost);
							SimLogger.getInstance().taskEnded(partitionState.parentTaskId, CloudSim.clock());
							partitionStateMap.remove(partitionState.parentTaskId);
							resolveTimeout(partitionState.parentTaskId);
						}
					}
					break;
				}
				
				// Log task completion
				// ONAT: a stream node's output is what the next stream layer may have to relay
				// (a task that ran on the cloud has none on any UAV)
				if (task.getAssociatedDatacenterId() != SimSettings.CLOUD_DATACENTER_ID)
					recordStreamNodeOutput(task.getCloudletId(), Collections.singletonMap(task.getAssociatedHostId(), task.getCloudletOutputSize()));
				if (!timedOutTaskIds.remove(task.getCloudletId())) {
					SimLogger.getInstance().taskEnded(task.getCloudletId(), CloudSim.clock());
				}
				resolveTimeout(task.getCloudletId());
				break;
			}
			case TASK_TIMEOUT:
			{
				int topLevelTaskId = (Integer) ev.getData();
				processTaskTimeout(topLevelTaskId);
				break;
			}
			case SUBMIT_NEXT_DAG_LAYER:
			{
				PendingLayerSubmission pending = (PendingLayerSubmission) ev.getData();
				PartitionState partitionState = partitionStateMap.get(pending.parentTaskId);
				if (partitionState == null || partitionState.failed) {
					break;
				}
				submitChildTaskBatch(pending.parentTaskId, partitionState, pending.childTasks, pending.selectedVMs, networkModel);
				break;
			}
			case SUBMIT_NEXT_STREAM_LAYER:
			{
				PendingStreamLayer pending = (PendingStreamLayer) ev.getData();
				StreamState streamState = streamStateMap.get(pending.streamId);
				// Gone if the stream failed/timed out during the relay delay - nothing of
				// the layer was registered anywhere yet, so there is nothing to undo
				if (streamState != null) {
					startStreamLayer(streamState, pending.plans);
				}
				break;
			}
			default:
				SimLogger.printLine(getName() + ".processOtherEvent(): " + "Error - event unknown by this DatacenterBroker. Terminating simulation...");
				System.exit(1);
				break;
		}
	}

	/**
	 * Logs a TIMED_OUT outcome for a top-level task that is still in flight
	 * SimSettings.getTaskTimeout(taskType) seconds after its own creation (see
	 * armTimeout()) - does NOT stop/cancel it (the task keeps uploading/processing/
	 * downloading exactly as if the deadline had not been reached, more realistic than
	 * an instant hard-cancel - see the class-level comment on pendingTimeouts). Logs
	 * exactly once for the top-level task (via failPartition for a partitioned/DAG task,
	 * or directly otherwise) and marks it so its own later, natural outcome is
	 * suppressed instead of double-logged. No-op if the task already resolved
	 * (succeeded or failed) through its own normal path before this event fired.
	 */
	private void processTaskTimeout(int topLevelTaskId) {
		Integer vmTypeHint = pendingTimeouts.remove(topLevelTaskId);
		if (vmTypeHint == null) {
			return;
		}

		if (streamStateMap.containsKey(topLevelTaskId)) {
			// Nodes already running keep going untouched, exactly like a timed-out
			// partitioned task's children; no further layer is submitted though, since
			// the stream is no longer in streamStateMap.
			timeoutStream(topLevelTaskId);
		}
		else if (partitionStateMap.containsKey(topLevelTaskId)) {
			// Children keep running untouched - failPartition only marks the parent as
			// resolved/logs once; the existing partitionState.failed guard already makes
			// every child's own eventual natural outcome a no-op for pipeline-advancement
			// purposes while still letting its network bookkeeping proceed normally.
			failPartition(topLevelTaskId, SimLogger.TASK_STATUS.TIMED_OUT, NETWORK_DELAY_TYPES.WLAN_DELAY);
		}
		else {
			timedOutTaskIds.add(topLevelTaskId);
			SimLogger.getInstance().timedOut(topLevelTaskId, CloudSim.clock(), vmTypeHint);
		}
	}

	/**
	 * Submits a task from a mobile device for processing.
	 * Handles orchestration decisions and network delay simulation for task offloading.
	 * ONAT: a {@link StreamTaskProperty} is a stream request instead (see submitStream()).
	 * 
	 * @param edgeTask Task properties including requirements and mobile device context
	 */
	public void submitTask(TaskProperty edgeTask) {
		if (edgeTask instanceof StreamTaskProperty) {
			submitStream((StreamTaskProperty) edgeTask);
			return;
		}
		submitApplicationTask(edgeTask);
	}

	/**
	 * Submits one application task requested on its own by a device: offloaded by the edge
	 * orchestrator (plain, partitioned or DAG-partitioned) and counted in the statistics. The
	 * nodes of a stream take a different route (see releaseStreamLayer()/startStreamLayer()),
	 * since where they run has to be known before they start.
	 */
	private void submitApplicationTask(TaskProperty edgeTask) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		if (edgeTask.isPartitionable()) {
			startPartitionedTask(preparePartitionedTask(edgeTask), NO_STREAM, null, networkModel);
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

		// ONAT: arm the hard task-timeout (see SimSettings.isTaskTimeoutEnabled(taskType)) from
		// this task's own creation instant - no-op when disabled for this app.
		armTimeout(task.getCloudletId(), task.getTaskType());

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
				updateTimeoutVmTypeHint(task.getCloudletId(), SimSettings.VM_TYPES.CLOUD_VM.ordinal());
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
				resolveTimeout(task.getCloudletId());
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
				updateTimeoutVmTypeHint(task.getCloudletId(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
			}
			else {
				// WLAN bandwidth not available - reject task
				SimLogger.getInstance().rejectedDueToBandwidth(
						task.getCloudletId(),
						CloudSim.clock(),
						SimSettings.VM_TYPES.EDGE_VM.ordinal(),
						NETWORK_DELAY_TYPES.WLAN_DELAY);
				resolveTimeout(task.getCloudletId());
			}
		}
		else {
			// Unknown orchestrator decision - terminate simulation
			SimLogger.printLine("Unknown nextHopId! Terminating simulation...");
			System.exit(1);
		}
	}

	/**
	 * Splits a partitionable task into its sibling batches and builds the first batch's child
	 * tasks, WITHOUT registering or submitting anything - so a caller can choose the batch's
	 * UAVs first (see releaseStreamLayer()). A flat partition has only that one batch; a DAG
	 * (dag_layers, see SimSettings.isTaskDag()) keeps its later layers to be submitted as each
	 * running layer completes (see PartitionState's remainingLayers/isDag fields).
	 */
	private PreparedPartition preparePartitionedTask(TaskProperty edgeTask) {
		int parentTaskId = ++taskIdCounter;

		// ONAT: a PARTITION_<N> override (see SimSettings.setPartitionCountOverride())
		// forces even a DAG-enabled task into a flat N-way split - its dag_layers are
		// ignored entirely for that run - so a layered DAG ("FULL") can be compared
		// against a flat baseline with the same (or any other) child count.
		if (SimSettings.getInstance().isTaskDag(edgeTask.getTaskType())
				&& !SimSettings.getInstance().isPartitionCountOverrideActive()) {
			Deque<List<TaskProperty>> dagLayers = splitTaskPropertyIntoDagLayers(edgeTask);
			int totalDagChildCount = 0;
			for (List<TaskProperty> layer : dagLayers) {
				totalDagChildCount += layer.size();
			}

			List<TaskProperty> firstLayer = dagLayers.poll();
			return new PreparedPartition(edgeTask, parentTaskId,
					buildChildTasks(parentTaskId, firstLayer, totalDagChildCount), totalDagChildCount, dagLayers);
		}

		List<TaskProperty> childProperties = splitTaskProperty(edgeTask);
		return new PreparedPartition(edgeTask, parentTaskId,
				buildChildTasks(parentTaskId, childProperties, childProperties.size()), childProperties.size(), null);
	}

	/**
	 * Registers and starts a task prepared by preparePartitionedTask(): logs it (as a stream
	 * node if streamId says so), tracks it in a PartitionState and submits its first batch -
	 * on preselectedVMs (same order as the batch) if given, else on VMs selected right here.
	 * Only a DAG's first layer is submitted here; every later layer follows from the
	 * RESPONSE_RECEIVED_BY_MOBILE_DEVICE case.
	 */
	private void startPartitionedTask(PreparedPartition prepared, int streamId, List<Vm> preselectedVMs, NetworkModel networkModel) {
		TaskProperty edgeTask = prepared.edgeTask;
		int parentTaskId = prepared.parentTaskId;
		addTaskLog(streamId, edgeTask.getMobileDeviceId(), parentTaskId, edgeTask.getTaskType(), (int)edgeTask.getLength(), (int)edgeTask.getInputFileSize(), (int)edgeTask.getOutputFileSize());
		SimLogger.getInstance().taskStarted(parentTaskId, CloudSim.clock());
		SimLogger.getInstance().taskAssigned(parentTaskId, SimSettings.GENERIC_EDGE_DEVICE_ID, 0, 0, SimSettings.VM_TYPES.EDGE_VM.ordinal());
		if (streamId == NO_STREAM)
			armTimeout(parentTaskId, edgeTask.getTaskType());

		PartitionState partitionState = new PartitionState(parentTaskId, prepared.firstBatch.size());
		if (prepared.remainingLayers != null) {
			partitionState.isDag = true;
			partitionState.totalDagChildCount = prepared.totalChildCount;
			partitionState.remainingLayers = prepared.remainingLayers;
		}
		partitionStateMap.put(parentTaskId, partitionState);

		submitChildTaskBatch(parentTaskId, partitionState, prepared.firstBatch, preselectedVMs, networkModel);
	}

	/**
	 * Creates child Task instances (with partition bookkeeping set) from a batch of
	 * sub-task properties - one call per flat partition batch, or per DAG layer.
	 *
	 * @param totalChildCount total sibling count used for CPU-utilization-share
	 *        purposes (see CpuUtilizationModel_Custom) - the full partition batch
	 *        size for a flat task, or the sum across every DAG layer for a DAG task.
	 */
	private List<Task> buildChildTasks(int parentTaskId, List<TaskProperty> childProperties, int totalChildCount) {
		List<Task> childTasks = new ArrayList<Task>(childProperties.size());
		for (int i = 0; i < childProperties.size(); i++) {
			Task childTask = createTask(childProperties.get(i));
			childTask.setPartitionChild(true);
			childTask.setParentTaskId(parentTaskId);
			childTask.setChildIndex(i);
			childTask.setChildCount(totalChildCount);

			Location currentLocation = SimManager.getInstance().getMobilityModel().getLocation(childTask.getMobileDeviceId(), CloudSim.clock());
			childTask.setSubmittedLocation(currentLocation);

			childTasks.add(childTask);
		}
		return childTasks;
	}

	/**
	 * Starts the upload of a whole sibling batch (a flat partition's only batch, or one DAG
	 * layer) - shared by startPartitionedTask (a DAG's first layer, or the flat batch) and the
	 * RESPONSE_RECEIVED_BY_MOBILE_DEVICE layer hand-off (later layers).
	 * The batch's VMs may already be chosen by the caller (same order as childTasks) - null
	 * means "select them here". A DAG layer after the first has its VMs chosen as soon as the
	 * previous layer finishes (see getInterLayerRelayDelay()), and so has a stream node's
	 * first batch (see releaseStreamLayer()).
	 */
	private void submitChildTaskBatch(int parentTaskId, PartitionState partitionState, List<Task> childTasks, List<Vm> preselectedVMs, NetworkModel networkModel) {
		partitionState.completedChildren = 0;
		partitionState.totalChildren = childTasks.size();
		partitionState.currentLayerOutputKbByHost.clear();

		// Select the target VM/UAV for every sibling sub-task at the same time so
		// the orchestrator can account for the cumulative load the whole batch
		// will place on shared UAVs before any of them are actually bound -
		// otherwise each selection would be blind to the load its just-chosen
		// siblings are about to add to the same UAV.
		List<Vm> selectedVMs = (preselectedVMs != null) ? preselectedVMs
				: SimManager.getInstance().getEdgeOrchestrator()
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
			// parent's log entry once every sibling (every layer, for a DAG task)
			// has finished.
			partitionState.uploadDelaySum += wlanDelay;

			networkModel.uploadStarted(childTask.getSubmittedLocation(), selectedVM.getHost().getId());
			schedule(getId(), wlanDelay, REQUEST_RECEIVED_BY_EDGE_DEVICE, new PendingTaskSubmission(childTask, selectedVM));
		}
	}

	/**
	 * ONAT: how long the next DAG layer has to wait for the finished layer's results to
	 * reach it. Each finished sub-task left its output on the UAV it ran on; the next layer
	 * needs the layer's whole combined output on every UAV it runs on. Whatever already sits
	 * on a destination UAV does not move, only the output produced on the OTHER UAVs crosses
	 * the inter-UAV link (see NetworkModel.getInterLayerDelay()) - so a layer that runs
	 * entirely where the previous one did waits for nothing. Destination UAVs receive over
	 * their own links in parallel, so the layer can start once the slowest one has everything.
	 *
	 * @param finishedLayerOutputKbByHost output (KB) of the finished layer, per producing UAV (host id)
	 * @param nextLayerVMs the VMs chosen for the next layer's sub-tasks (null entries = none found)
	 * @return the relay delay in seconds, 0 if no output has to leave its UAV
	 */
	private double getInterLayerRelayDelay(Map<Integer, Long> finishedLayerOutputKbByHost, List<Vm> nextLayerVMs, NetworkModel networkModel) {
		long totalOutputKb = 0;
		for (long outputKb : finishedLayerOutputKbByHost.values())
			totalOutputKb += outputKb;

		Set<Integer> destinationHosts = new HashSet<Integer>();
		for (Vm vm : nextLayerVMs) {
			if (vm != null)
				destinationHosts.add(vm.getHost().getId());
		}

		double relayDelay = 0;
		for (int destinationHost : destinationHosts) {
			long remoteOutputKb = totalOutputKb - finishedLayerOutputKbByHost.getOrDefault(destinationHost, 0L);
			if (remoteOutputKb <= 0)
				continue;
			relayDelay = Math.max(relayDelay,
					networkModel.getInterLayerDelay(remoteOutputKb, SimSettings.getInstance().getUavBandwidth()));
		}
		return relayDelay;
	}

	private List<TaskProperty> splitTaskProperty(TaskProperty edgeTask) {
		int childCount = Math.max(1, edgeTask.getPartitionCount());
		List<TaskProperty> childProperties = new ArrayList<TaskProperty>(childCount);

		long[] lengthParts = splitValue(edgeTask.getLength(), childCount);
		long[] uploadParts = splitValue(edgeTask.getInputFileSize(), childCount);
		long[] downloadParts = splitValue(edgeTask.getOutputFileSize(), childCount);

		for (int i = 0; i < childCount; i++) {
			childProperties.add(new TaskProperty(CloudSim.clock(), edgeTask.getMobileDeviceId(), edgeTask.getTaskType(), edgeTask.getPesNumber(), lengthParts[i], uploadParts[i], downloadParts[i]));
		}

		return childProperties;
	}

	/**
	 * Splits a DAG task's total length/upload/download into per-layer lists in
	 * dependency order, dispatching on SimSettings.getTaskPartitionStrategy() (the
	 * app's own applications.xml partition_strategy, or a per-run override - see
	 * SimSettings.setTaskPartitionStrategyOverride()) to one of three strategies:
	 * - "EQUAL" (default): split evenly across every sub-task in every layer (same
	 *   EQUAL-split convention splitTaskProperty uses for flat partitions), so every
	 *   sub-task gets an identical share regardless of which layer it's in.
	 * - DAG_PARTITION_STRATEGY_LAYER_EQUAL: split evenly across every LAYER first,
	 *   then split each layer's share evenly again across that layer's own
	 *   sub-tasks - sub-tasks in a smaller layer get a bigger individual share than
	 *   sub-tasks in a bigger layer.
	 * - DAG_PARTITION_STRATEGY_WEIGHTED: use explicit per-node weights instead of an
	 *   even split - a per-run weights override (SimSettings.
	 *   isTaskPartitionWeightsOverrideActive()/getTaskPartitionWeightsOverride(), e.g.
	 *   tutorial15's "FULL_(0.1,(0.4,0.4),0.1)" policy encoding its own weights) takes
	 *   precedence if active, otherwise falls back to the app's own applications.xml
	 *   dag_partition_weights (SimSettings.getTaskDagPartitionWeights()). Either way,
	 *   only consulted when this strategy is actually selected, so dag_partition_weights
	 *   has no effect on EQUAL/LAYER_EQUAL runs of the same app (e.g. tutorial15's
	 *   "FULL"/"FULL_LAYERED" vs "FULL_WEIGHTED"/"FULL_(...)").
	 */
	private Deque<List<TaskProperty>> splitTaskPropertyIntoDagLayers(TaskProperty edgeTask) {
		int[] layerSizes = SimSettings.getInstance().getTaskDagLayers(edgeTask.getTaskType());
		String strategy = SimSettings.getInstance().getTaskPartitionStrategy(edgeTask.getTaskType());

		if (SimSettings.DAG_PARTITION_STRATEGY_WEIGHTED.equals(strategy)) {
			double[][] weights;
			if (SimSettings.getInstance().isTaskPartitionWeightsOverrideActive())
				weights = SimSettings.getInstance().getTaskPartitionWeightsOverride(edgeTask.getTaskType());
			else if (SimSettings.getInstance().isTaskDagPartitionWeighted(edgeTask.getTaskType()))
				weights = SimSettings.getInstance().getTaskDagPartitionWeights(edgeTask.getTaskType());
			else
				throw new IllegalStateException("Task type " + edgeTask.getTaskType()
						+ " selected DAG_PARTITION_STRATEGY_WEIGHTED but defines no dag_partition_weights"
						+ " and no per-run weights override is active");
			return splitTaskPropertyIntoDagLayersByWeight(edgeTask, layerSizes, weights);
		}

		if (SimSettings.DAG_PARTITION_STRATEGY_LAYER_EQUAL.equals(strategy))
			return splitTaskPropertyIntoDagLayersByLayerThenSubtask(edgeTask, layerSizes);

		int totalChildCount = 0;
		for (int layerSize : layerSizes) {
			totalChildCount += layerSize;
		}

		long[] lengthParts = splitValue(edgeTask.getLength(), totalChildCount);
		long[] uploadParts = splitValue(edgeTask.getInputFileSize(), totalChildCount);
		long[] downloadParts = splitValue(edgeTask.getOutputFileSize(), totalChildCount);

		Deque<List<TaskProperty>> dagLayers = new ArrayDeque<List<TaskProperty>>(layerSizes.length);
		int childIndex = 0;
		for (int layerSize : layerSizes) {
			List<TaskProperty> layerProperties = new ArrayList<TaskProperty>(layerSize);
			for (int i = 0; i < layerSize; i++) {
				layerProperties.add(new TaskProperty(CloudSim.clock(), edgeTask.getMobileDeviceId(), edgeTask.getTaskType(), edgeTask.getPesNumber(), lengthParts[childIndex], uploadParts[childIndex], downloadParts[childIndex]));
				childIndex++;
			}
			dagLayers.add(layerProperties);
		}

		return dagLayers;
	}

	/**
	 * DAG_PARTITION_STRATEGY_LAYER_EQUAL: divides the task's total length/upload/
	 * download equally among its layers, then divides each layer's own share equally
	 * again among that layer's sub-tasks (two nested equal splits, each exactly
	 * conserving its input total via splitValue's remainder handling).
	 */
	private Deque<List<TaskProperty>> splitTaskPropertyIntoDagLayersByLayerThenSubtask(TaskProperty edgeTask, int[] layerSizes) {
		long[] layerLengthShares = splitValue(edgeTask.getLength(), layerSizes.length);
		long[] layerUploadShares = splitValue(edgeTask.getInputFileSize(), layerSizes.length);
		long[] layerDownloadShares = splitValue(edgeTask.getOutputFileSize(), layerSizes.length);

		Deque<List<TaskProperty>> dagLayers = new ArrayDeque<List<TaskProperty>>(layerSizes.length);
		for (int l = 0; l < layerSizes.length; l++) {
			int layerSize = layerSizes[l];
			long[] lengthParts = splitValue(layerLengthShares[l], layerSize);
			long[] uploadParts = splitValue(layerUploadShares[l], layerSize);
			long[] downloadParts = splitValue(layerDownloadShares[l], layerSize);

			List<TaskProperty> layerProperties = new ArrayList<TaskProperty>(layerSize);
			for (int i = 0; i < layerSize; i++) {
				layerProperties.add(new TaskProperty(CloudSim.clock(), edgeTask.getMobileDeviceId(), edgeTask.getTaskType(), edgeTask.getPesNumber(), lengthParts[i], uploadParts[i], downloadParts[i]));
			}
			dagLayers.add(layerProperties);
		}

		return dagLayers;
	}

	/**
	 * Explicit per-node weights (applications.xml's dag_partition_weights, see
	 * SimSettings.getTaskDagPartitionWeights()): flattens the per-layer weight
	 * fractions (already validated to sum to 1.0 across the whole DAG) into one list,
	 * in dependency order, and splits the task's total length/upload/download in a
	 * single shot via splitValueByWeights - so e.g. weights {{0.1}, {0.5, 0.3}, {0.1}}
	 * give the entry node 10% of the task, the two second-layer nodes 50%/30%, and the
	 * final node 10%.
	 */
	private Deque<List<TaskProperty>> splitTaskPropertyIntoDagLayersByWeight(TaskProperty edgeTask, int[] layerSizes, double[][] weights) {
		int totalChildCount = 0;
		for (int layerSize : layerSizes) {
			totalChildCount += layerSize;
		}

		double[] flatWeights = new double[totalChildCount];
		int weightIndex = 0;
		for (double[] layerWeights : weights) {
			for (double weight : layerWeights) {
				flatWeights[weightIndex++] = weight;
			}
		}

		long[] lengthParts = splitValueByWeights(edgeTask.getLength(), flatWeights);
		long[] uploadParts = splitValueByWeights(edgeTask.getInputFileSize(), flatWeights);
		long[] downloadParts = splitValueByWeights(edgeTask.getOutputFileSize(), flatWeights);

		Deque<List<TaskProperty>> dagLayers = new ArrayDeque<List<TaskProperty>>(layerSizes.length);
		int childIndex = 0;
		for (int layerSize : layerSizes) {
			List<TaskProperty> layerProperties = new ArrayList<TaskProperty>(layerSize);
			for (int i = 0; i < layerSize; i++) {
				layerProperties.add(new TaskProperty(CloudSim.clock(), edgeTask.getMobileDeviceId(), edgeTask.getTaskType(), edgeTask.getPesNumber(), lengthParts[childIndex], uploadParts[childIndex], downloadParts[childIndex]));
				childIndex++;
			}
			dagLayers.add(layerProperties);
		}

		return dagLayers;
	}

	private long[] splitValue(long total, int parts) {
		long[] values = new long[parts];
		long base = parts == 0 ? total : total / parts;
		long remainder = parts == 0 ? 0 : total % parts;
		for (int i = 0; i < parts; i++) {
			values[i] = base + (i < remainder ? 1 : 0);
		}
		return values;
	}

	/**
	 * Splits total into len(weights) parts proportional to weights (which must sum to
	 * 1.0, as validated by SimSettings.parseDagPartitionWeights), exactly conserving
	 * total: each part is floor(total * weight), then any leftover units (lost to
	 * flooring) are handed out one-by-one to the parts with the largest fractional
	 * remainder first (largest-remainder/Hare-Niemeyer apportionment) - same
	 * exact-total guarantee as splitValue's even-split remainder handling.
	 */
	private long[] splitValueByWeights(long total, double[] weights) {
		int parts = weights.length;
		long[] values = new long[parts];
		double[] fractional = new double[parts];
		long allocated = 0;
		for (int i = 0; i < parts; i++) {
			double exact = total * weights[i];
			values[i] = (long) Math.floor(exact);
			fractional[i] = exact - values[i];
			allocated += values[i];
		}

		Integer[] order = new Integer[parts];
		for (int i = 0; i < parts; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Double.compare(fractional[b], fractional[a]));

		long remainder = total - allocated;
		for (int i = 0; i < remainder; i++) {
			values[order[i]]++;
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
		else if (status == SimLogger.TASK_STATUS.TIMED_OUT) {
			SimLogger.getInstance().timedOut(parentTaskId, CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
		}
		partitionStateMap.remove(parentTaskId);
		// ONAT: clears a still-pending TASK_TIMEOUT regardless of which reason this
		// partition failed for - without this, an unrelated (non-timeout) failure would
		// leave a stale pending-timeout entry/scheduled event around for the rest of the run.
		resolveTimeout(parentTaskId);
	}
	
	/**
	 * ONAT: starts a stream request (see StreamState). Like a partitioned task's parent, the
	 * stream is the ONE unit logged in the statistics - its nodes (application tasks) only feed
	 * it their network delays and, if one fails, the failure (see SimLogger.addStreamNodeLog()).
	 * Layers are released one at a time (see releaseStreamLayer()): the first right here, every
	 * later one once the previous one has completed (see onStreamNodeEnded()). The stream's own
	 * timeout - not the timeouts of the applications it runs - is armed, with the same soft
	 * semantics as a plain task's except that the stream is only logged once its running nodes
	 * have ended (see timeoutStream()).
	 */
	private void submitStream(StreamTaskProperty streamTask) {
		SimSettings SS = SimSettings.getInstance();
		int streamId = ++taskIdCounter;
		SimLogger.getInstance().addLog(streamTask.getMobileDeviceId(), streamId, streamTask.getTaskType(), (int)streamTask.getLength(), (int)streamTask.getInputFileSize(), (int)streamTask.getOutputFileSize());
		SimLogger.getInstance().taskStarted(streamId, CloudSim.clock());
		SimLogger.getInstance().taskAssigned(streamId, SimSettings.GENERIC_EDGE_DEVICE_ID, 0, 0, SimSettings.VM_TYPES.EDGE_VM.ordinal());

		if (SS.isStreamTimeoutEnabled(streamTask.getStreamIndex())) {
			pendingTimeouts.put(streamId, SimSettings.VM_TYPES.EDGE_VM.ordinal());
			schedule(getId(), SS.getStreamTimeout(streamTask.getStreamIndex()), TASK_TIMEOUT, streamId);
		}

		StreamState streamState = new StreamState(streamId, new ArrayDeque<List<TaskProperty>>(streamTask.getNodeLayers()));
		streamStateMap.put(streamId, streamState);
		releaseStreamLayer(streamState);
	}

	/**
	 * Releases the stream's next layer: builds every node's task(s), has the orchestrator choose
	 * the UAVs of all of them at once (so the whole layer's cumulative load is accounted for,
	 * like a partition batch), works out how long the finished layer's output needs to reach
	 * those UAVs and only then, after that relay delay, starts the layer (see startStreamLayer()).
	 * The relay is exactly a DAG layer's (see getInterLayerRelayDelay()): only output that has to
	 * move to a different UAV costs anything, destinations receive in parallel so the slowest one
	 * counts, it is 0 when everything already sits on the destination UAVs (and for the first
	 * layer, which has no previous output), and a positive delay is logged on the stream
	 * (SimLogger.addInterLayerDelay) as UAV delay, folded into its network delay.
	 * Nothing is registered anywhere until the layer starts, so a stream that fails or times
	 * out during the delay leaves nothing behind.
	 */
	private void releaseStreamLayer(StreamState streamState) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();

		List<StreamNodePlan> plans = new ArrayList<StreamNodePlan>();
		List<Task> tasksToPlace = new ArrayList<Task>();
		for (TaskProperty node : streamState.remainingLayers.poll()) {
			StreamNodePlan plan = planStreamNode(node);
			plans.add(plan);
			tasksToPlace.addAll(plan.getPlacementTasks());
		}

		List<Vm> selectedVMs = SimManager.getInstance().getEdgeOrchestrator()
				.getVmsToOffload(tasksToPlace, SimSettings.GENERIC_EDGE_DEVICE_ID);
		int nextVm = 0;
		for (StreamNodePlan plan : plans) {
			int placementCount = plan.getPlacementTasks().size();
			plan.selectedVMs = new ArrayList<Vm>(selectedVMs.subList(nextVm, nextVm + placementCount));
			nextVm += placementCount;
		}

		double interLayerDelay = getInterLayerRelayDelay(streamState.layerOutputKbByHost, selectedVMs, networkModel);
		if (interLayerDelay > 0)
			SimLogger.getInstance().addInterLayerDelay(streamState.streamId, interLayerDelay);
		schedule(getId(), interLayerDelay, SUBMIT_NEXT_STREAM_LAYER, new PendingStreamLayer(streamState.streamId, plans));
	}

	/** Builds the task(s) of one stream node - a plain task, or a partitioned task's first batch. */
	private StreamNodePlan planStreamNode(TaskProperty node) {
		if (node.isPartitionable())
			return new StreamNodePlan(null, preparePartitionedTask(node));

		Task task = createTask(node);
		task.setSubmittedLocation(SimManager.getInstance().getMobilityModel().getLocation(task.getMobileDeviceId(), CloudSim.clock()));
		return new StreamNodePlan(task, null);
	}

	/**
	 * Starts every node of a released layer, in parallel, on the UAVs chosen for it. Each node is
	 * an application task like any other - offloaded to a UAV hosting that application's service
	 * or, if the orchestrator found none to take it, rejected, which fails the stream.
	 */
	private void startStreamLayer(StreamState streamState, List<StreamNodePlan> plans) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		streamState.currentLayerSize = plans.size();
		streamState.endedNodes = 0;
		streamState.layerOutputKbByHost.clear();

		for (StreamNodePlan plan : plans) {
			// A node already rejected (see onStreamNodeEnded) failed the stream: nothing else
			// of this layer needs to start
			if (streamState.resolved) {
				break;
			}
			if (plan.plainTask != null)
				submitPlainTaskToVm(plan.plainTask, plan.selectedVMs.get(0), streamState.streamId, networkModel);
			else
				startPartitionedTask(plan.partition, streamState.streamId, plan.selectedVMs, networkModel);
		}
	}

	/**
	 * Starts a plain stream node on the VM the orchestrator already chose for it: uploads to that
	 * UAV, then binds the task to that VM when the upload is done (REQUEST_RECEIVED_BY_EDGE_DEVICE
	 * with a preselected VM) - the way a partition child is submitted. No UAV for it (null) is
	 * rejected like any other "no suitable VM" case.
	 */
	private void submitPlainTaskToVm(Task task, Vm selectedVm, int streamId, NetworkModel networkModel) {
		addTaskLog(streamId, task.getMobileDeviceId(), task.getCloudletId(), task.getTaskType(),
				(int)task.getCloudletLength(), (int)task.getCloudletFileSize(), (int)task.getCloudletOutputSize());

		if (selectedVm == null) {
			SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
			return;
		}

		double wlanDelay = networkModel.getUploadDelay(task.getMobileDeviceId(), selectedVm.getHost().getId(), task);
		if (wlanDelay <= 0) {
			SimLogger.getInstance().rejectedDueToBandwidth(task.getCloudletId(), CloudSim.clock(),
					SimSettings.VM_TYPES.EDGE_VM.ordinal(), NETWORK_DELAY_TYPES.WLAN_DELAY);
			return;
		}

		networkModel.uploadStarted(task.getSubmittedLocation(), selectedVm.getHost().getId());
		SimLogger.getInstance().taskStarted(task.getCloudletId(), CloudSim.clock());
		SimLogger.getInstance().setUploadDelay(task.getCloudletId(), wlanDelay, NETWORK_DELAY_TYPES.WLAN_DELAY);
		schedule(getId(), wlanDelay, REQUEST_RECEIVED_BY_EDGE_DEVICE, new PendingTaskSubmission(task, selectedVm));
	}

	/**
	 * A node of a stream ended (SimLogger.StreamNodeListener). The stream goes on with its next
	 * layer once the whole current layer has completed, completes after the last layer, and is
	 * over as soon as any node fails - the logger has then already recorded that failure as the
	 * stream's own outcome, so only the bookkeeping is released here. Once the stream's timeout
	 * has fired (see timeoutStream()) nothing else starts: the stream is logged TIMED_OUT when
	 * the last node of the running layer has ended, whatever became of it.
	 */
	private void onStreamNodeEnded(int streamId, int nodeTaskId, SimLogger.TASK_STATUS status) {
		streamNodeUnits.remove(nodeTaskId);

		StreamState streamState = streamStateMap.get(streamId);
		// Already over (e.g. failed through another node): a node still running just ends on its own
		if (streamState == null) {
			return;
		}

		streamState.endedNodes++;

		if (streamState.timedOut) {
			if (streamState.endedNodes >= streamState.currentLayerSize) {
				streamState.resolved = true;
				streamStateMap.remove(streamId);
				SimLogger.getInstance().timedOut(streamId, CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
			}
			return;
		}

		if (status != SimLogger.TASK_STATUS.COMLETED) {
			streamState.resolved = true;
			streamStateMap.remove(streamId);
			resolveTimeout(streamId);
			return;
		}

		if (streamState.endedNodes < streamState.currentLayerSize) {
			return;
		}

		if (!streamState.remainingLayers.isEmpty()) {
			releaseStreamLayer(streamState);
			return;
		}

		SimLogger.getInstance().taskEnded(streamId, CloudSim.clock());
		streamState.resolved = true;
		streamStateMap.remove(streamId);
		resolveTimeout(streamId);
	}

	/**
	 * The stream's timeout fired. Nodes that are running are NOT cut short: they finish
	 * executing, no further layer starts, and the stream is logged TIMED_OUT only when the last
	 * of them has ended (see onStreamNodeEnded()) - so whatever those nodes end up doing, the
	 * stream fails then, at the end of their execution. If no node is running right now (the
	 * stream is between two layers, e.g. while a layer's output is being relayed) there is
	 * nothing to wait for and it is logged TIMED_OUT immediately.
	 */
	private void timeoutStream(int streamId) {
		StreamState streamState = streamStateMap.get(streamId);
		if (streamState == null) {
			return;
		}

		if (streamState.endedNodes < streamState.currentLayerSize) {
			streamState.timedOut = true;
			// The logger must not turn a failure of those nodes into the stream's outcome
			SimLogger.getInstance().markStreamTimeoutPending(streamId);
			return;
		}

		streamState.resolved = true;
		streamStateMap.remove(streamId);
		SimLogger.getInstance().timedOut(streamId, CloudSim.clock(), SimSettings.VM_TYPES.EDGE_VM.ordinal());
	}

	/**
	 * Adds a finished stream node's output (KB per UAV, see StreamState.layerOutputKbByHost) to
	 * its stream's current layer - a no-op for a task that is not a stream node or whose stream
	 * is already over.
	 */
	private void recordStreamNodeOutput(int nodeTaskId, Map<Integer, Long> outputKbByHost) {
		Integer streamId = streamNodeUnits.get(nodeTaskId);
		if (streamId == null) {
			return;
		}
		StreamState streamState = streamStateMap.get(streamId);
		if (streamState == null) {
			return;
		}
		for (Map.Entry<Integer, Long> output : outputKbByHost.entrySet()) {
			streamState.layerOutputKbByHost.merge(output.getKey(), output.getValue(), Long::sum);
		}
	}

	/** Logs a task under its own id - or, for a node of the stream streamId, as that stream's node. */
	private void addTaskLog(int streamId, int deviceId, int taskId, int taskType, int taskLength, int inputSize, int outputSize) {
		if (streamId == NO_STREAM) {
			SimLogger.getInstance().addLog(deviceId, taskId, taskType, taskLength, inputSize, outputSize);
		}
		else {
			SimLogger.getInstance().addStreamNodeLog(streamId, deviceId, taskId, taskType, taskLength, inputSize, outputSize);
			streamNodeUnits.put(taskId, streamId);
		}
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
			if (!timedOutTaskIds.remove(task.getCloudletId())) {
				SimLogger.getInstance().rejectedDueToVMCapacity(task.getCloudletId(), CloudSim.clock(), vmType);
			}
			resolveTimeout(task.getCloudletId());
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
		
		// ONAT: update the logging hint now that this task is bound to a real VM tier -
		// if it times out while processing, it keeps running untouched (see
		// processTaskTimeout); this only affects which tier TIMED_OUT would log against.
		updateTimeoutVmTypeHint(task.isPartitionChild() ? task.getParentTaskId() : task.getCloudletId(), vmType);

		// ONAT: Schedule directly to the already-known datacenterId instead of
		// getVmsToDatacentersMap().get(task.getVmId()) - that map is only populated once
		// the broker processes this VM's VM_CREATE_ACK event, which (due to CloudSim's
		// minimum inter-event delay) can still be pending the first time a task is
		// submitted to a freshly created VM, causing a null lookup/NPE here. datacenterId
		// was already derived directly from the host a few lines above, so it's correct
		// regardless of whether the broker's bookkeeping has caught up yet.
		schedule(datacenterId, delay, CloudSimTags.CLOUDLET_SUBMIT, task);

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
