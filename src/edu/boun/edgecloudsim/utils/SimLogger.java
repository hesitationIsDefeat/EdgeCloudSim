/*
 * Title:        EdgeCloudSim - Simulation Logger
 * 
 * Description: 
 * SimLogger is responsible for storing simulation events/results
 * in to the files in a specific format.
 * Format is decided in a way to use results in matlab efficiently.
 * If you need more results or another file format, you should modify
 * this class.
 * 
 * IMPORTANT NOTES:
 * EdgeCloudSim is designed to perform file logging operations with
 * a low memory consumption. Deep file logging is performed whenever
 * a task is completed. This may cause too many file IO operation and
 * increase the time consumption!
 * 
 * The basic results are kept in the memory, and saved to the files
 * at the end of the simulation. So, basic file logging does
 * bring too much overhead to the time complexity. 
 * 
 * In the earlier versions (v3 and older), EdgeCloudSim keeps all the 
 * task results in the memory and save them to the files when the
 * simulation ends. Since this approach increases memory consumption
 * too much, we sacrificed the time complexity.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.core.SimSettings.NETWORK_DELAY_TYPES;
import edu.boun.edgecloudsim.utils.SimLogger.NETWORK_ERRORS;
import org.cloudbus.cloudsim.core.CloudSim;

/**
 * Comprehensive simulation logging and result collection system for EdgeCloudSim.
 * 
 * <p>SimLogger is responsible for tracking, collecting, and persisting simulation events
 * and performance metrics throughout the simulation execution. It provides both real-time
 * logging capabilities and comprehensive result aggregation for post-simulation analysis.</p>
 * 
 * <p><b>Key Design Features:</b>
 * <ul>
 *   <li><b>Memory Optimization:</b> Balances memory usage with file I/O overhead</li>
 *   <li><b>MATLAB Compatibility:</b> Generates results in MATLAB-friendly formats</li>
 *   <li><b>Multi-tier Analytics:</b> Tracks metrics across cloud, edge, and mobile tiers</li>
 *   <li><b>Comprehensive Coverage:</b> Monitors performance, failures, delays, and costs</li>
 * </ul></p>
 * 
 * <p><b>Logging Strategy:</b>
 * <ul>
 *   <li><b>Deep Logging:</b> Immediate file writes for detailed task events (high I/O)</li>
 *   <li><b>Basic Logging:</b> Memory accumulation with end-of-simulation writes (low overhead)</li>
 *   <li><b>Selective Logging:</b> Configurable verbosity levels for different metrics</li>
 * </ul></p>
 * 
 * <p><b>Collected Metrics Categories:</b>
 * <ul>
 *   <li>Task completion rates and failure analysis</li>
 *   <li>Network delays across all communication segments</li>
 *   <li>Processing times and service quality metrics</li>
 *   <li>Resource utilization and capacity constraints</li>
 *   <li>Cost analysis and Quality of Experience (QoE)</li>
 *   <li>Infrastructure overhead and orchestration costs</li>
 * </ul></p>
 * 
 * <p>The logger implements the Singleton pattern to ensure consistent data collection
 * across all simulation components while maintaining thread-safe operations.</p>
 * 
 * @see edu.boun.edgecloudsim.core.SimManager
 * @see edu.boun.edgecloudsim.core.SimSettings
 */
public class SimLogger {
	/**
	 * Enumeration of possible task states throughout the simulation lifecycle.
	 * 
	 * <p>Task status tracking enables detailed analysis of where tasks succeed,
	 * fail, or encounter bottlenecks in the edge-cloud processing pipeline.</p>
	 */
	public static enum TASK_STATUS {
		/** Task has been created and queued for processing */
		CREATED,
		/** Task data is being uploaded to processing node */
		UPLOADING,
		/** Task is being executed on processing node */
		PROCESSING,
		/** Task results are being downloaded back to mobile device */
		DOWNLOADING,
		/** Task has completed successfully */
		COMLETED,  // Note: Misspelling preserved from original code
		/** Task rejected due to insufficient VM computational capacity */
		REJECTED_DUE_TO_VM_CAPACITY,
		/** Task rejected due to insufficient network bandwidth */
		REJECTED_DUE_TO_BANDWIDTH,
		/** Task failed to complete due to network bandwidth limitations */
		UNFINISHED_DUE_TO_BANDWIDTH,
		/** Task failed due to device mobility (moved out of range) */
		UNFINISHED_DUE_TO_MOBILITY,
		/** Task rejected due to WLAN coverage limitations */
		REJECTED_DUE_TO_WLAN_COVERAGE,
		/** Task force-cancelled after exceeding SimSettings.getTaskTimeout(taskType) while still
		 * in flight (uploading/processing/downloading) */
		TIMED_OUT
	}
	
	/**
	 * Enumeration of network error types for failure analysis.
	 * 
	 * <p>Network error categorization enables targeted analysis of communication
	 * failures across different network segments and technologies.</p>
	 */
	public static enum NETWORK_ERRORS {
		/** Local Area Network communication error */
		LAN_ERROR,
		/** Metropolitan Area Network communication error */
		MAN_ERROR,
		/** Wide Area Network communication error */
		WAN_ERROR,
		/** GSM/Cellular network communication error */
		GSM_ERROR,
		/** No network error occurred */
		NONE
	}

	/** Simulation start timestamp for performance measurement */
	private long startTime;
	
	/** Simulation end timestamp for performance measurement */
	private long endTime;
	
	/** Global flag controlling file-based logging operations */
	private static boolean fileLogEnabled;
	
	/** Global flag controlling console output logging */
	private static boolean printLogEnabled;
	
	/** Prefix for output file naming to support batch simulations */
	private String filePrefix;
	
	/** Directory path for simulation output files */
	private String outputFolder;
	
	/** Map storing detailed task event information for deep logging */
	private Map<Integer, LogItem> taskMap;
	
	/** List tracking VM computational load over time */
	private LinkedList<VmLoadLogItem> vmLoadList;
	
	/** List tracking access point network delays over time */
	private LinkedList<ApDelayLogItem> apDelayList;

	/** ONAT: List tracking UAV (edge server) positions over time, "time;uav_id;x;y" per entry */
	private LinkedList<String> uavLocationList;

	/** ONAT: List of the world events of the scenario, one WorldEvent.toLogLine() per entry - see addEventLog() */
	private LinkedList<String> eventList;

	/** Singleton instance ensuring consistent logging across simulation components */
	private static SimLogger singleton = new SimLogger();
	
	/** Number of task types (every application, then every stream) defined in simulation configuration */
	private int numOfAppTypes;

	/**
	 * ONAT: size of every per-task-type statistics array: one slot per task type (every
	 * application, then every stream) followed by three aggregates, see getTotalIndex(),
	 * getApplicationsIndex() and getStreamsIndex().
	 */
	private int numOfStatSlots;

	/** ONAT: slot of the aggregate over EVERY request (standalone applications and streams) -
	 * what the console summary reports. */
	private int getTotalIndex() {
		return numOfAppTypes;
	}

	/** ONAT: slot of the aggregate over the standalone applications only - requests a device
	 * made for an application itself. An application run as a node of a stream is never
	 * counted here (nor anywhere else, see addStreamNodeLog()). Written to ALL_APPS_GENERIC.log,
	 * which without streams is the same as the total. */
	private int getApplicationsIndex() {
		return numOfAppTypes + 1;
	}

	/** ONAT: slot of the aggregate over the streams only - written to ALL_STREAMS_GENERIC.log
	 * (only when streams exist). */
	private int getStreamsIndex() {
		return numOfAppTypes + 2;
	}

	/**
	 * ONAT: name of the file part (before "_GENERIC.log") the statistics of a slot are written
	 * under, or null if that slot gets no file: an application/stream no device ever requests,
	 * the all-requests total (console only) and the streams aggregate when there are no streams.
	 */
	private String getGenericFileLabel(int slot) {
		if (slot < numOfAppTypes)
			return SimSettings.getInstance().isTaskTypeUsed(slot) ? SimSettings.getInstance().getTaskName(slot) : null;
		if (slot == getApplicationsIndex())
			return "ALL_APPS";
		if (slot == getStreamsIndex() && SimSettings.getInstance().getNumOfStreams() > 0)
			return "ALL_STREAMS";
		return null;
	}

	/** ONAT: fills the three aggregate slots of a per-task-type array from its task type slots. */
	private void fillAggregates(int[] values) {
		int numOfApplications = SimSettings.getInstance().getNumOfApplications();
		values[getTotalIndex()] = IntStream.of(values).limit(numOfAppTypes).sum();
		values[getApplicationsIndex()] = IntStream.of(values).limit(numOfApplications).sum();
		values[getStreamsIndex()] = IntStream.of(values).skip(numOfApplications).limit(numOfAppTypes - numOfApplications).sum();
	}

	private void fillAggregates(double[] values) {
		int numOfApplications = SimSettings.getInstance().getNumOfApplications();
		values[getTotalIndex()] = DoubleStream.of(values).limit(numOfAppTypes).sum();
		values[getApplicationsIndex()] = DoubleStream.of(values).limit(numOfApplications).sum();
		values[getStreamsIndex()] = DoubleStream.of(values).skip(numOfApplications).limit(numOfAppTypes - numOfApplications).sum();
	}

	/** ONAT: notified when a stream node's task ends (see addStreamNodeLog()) */
	private StreamNodeListener streamNodeListener = null;

	/**
	 * ONAT: receives the outcome of every stream node. A stream node is an application task
	 * run on behalf of a stream request, so it is NOT counted in the statistics itself - the
	 * stream request is (as one task, like a partitioned/DAG task's parent) - and the one
	 * running the stream (DefaultMobileDeviceManager) learns of each node's end here instead.
	 */
	public interface StreamNodeListener {
		/**
		 * @param streamId id the stream request was logged under (see addLog())
		 * @param nodeTaskId id the node's own task was logged under (see addStreamNodeLog())
		 * @param status how the node ended: COMLETED, or the reason it failed
		 */
		void onStreamNodeEnded(int streamId, int nodeTaskId, TASK_STATUS status);
	}

	/** ONAT: registers who is told when a stream node ends - replaces any earlier listener. */
	public void setStreamNodeListener(StreamNodeListener listener) {
		streamNodeListener = listener;
	}
	
	private File successFile = null, failFile = null;
	private FileWriter successFW = null, failFW = null;
	private BufferedWriter successBW = null, failBW = null;

	// extract following values for each app type.
	// last index is average of all app types
	private int[] uncompletedTask = null;
	private int[] uncompletedTaskOnCloud = null;
	private int[] uncompletedTaskOnEdge = null;
	private int[] uncompletedTaskOnMobile = null;

	private int[] completedTask = null;
	private int[] completedTaskOnCloud = null;
	private int[] completedTaskOnEdge = null;
	private int[] completedTaskOnMobile = null;

	private int[] failedTask = null;
	private int[] failedTaskOnCloud = null;
	private int[] failedTaskOnEdge = null;
	private int[] failedTaskOnMobile = null;

	private double[] networkDelay = null;
	private double[] gsmDelay = null;
	private double[] wanDelay = null;
	private double[] manDelay = null;
	private double[] lanDelay = null;
	// ONAT: DAG inter-layer relay delay (see NetworkModel.getInterLayerDelay()) - not
	// device<->UAV traffic, so tracked separately from lan/man/wan/gsmDelay.
	private double[] uavDelay = null;
	
	private double[] gsmUsage = null;
	private double[] wanUsage = null;
	private double[] manUsage = null;
	private double[] lanUsage = null;
	private double[] uavUsage = null;

	private double[] serviceTime = null;
	private double[] serviceTimeOnCloud = null;
	private double[] serviceTimeOnEdge = null;
	private double[] serviceTimeOnMobile = null;

	private double[] processingTime = null;
	private double[] processingTimeOnCloud = null;
	private double[] processingTimeOnEdge = null;
	private double[] processingTimeOnMobile = null;

	private int[] failedTaskDueToVmCapacity = null;
	private int[] failedTaskDueToVmCapacityOnCloud = null;
	private int[] failedTaskDueToVmCapacityOnEdge = null;
	private int[] failedTaskDueToVmCapacityOnMobile = null;
	
	private double[] cost = null;
	private double[] QoE = null;
	private int[] failedTaskDuetoBw = null;
	private int[] failedTaskDuetoLanBw = null;
	private int[] failedTaskDuetoManBw = null;
	private int[] failedTaskDuetoWanBw = null;
	private int[] failedTaskDuetoGsmBw = null;
	private int[] failedTaskDuetoMobility = null;
	private int[] refectedTaskDuetoWlanRange = null;
	/** ONAT: tasks force-cancelled after exceeding SimSettings.getTaskTimeout(taskType) - see TASK_STATUS.TIMED_OUT. */
	private int[] failedTaskDuetoTimeout = null;
	
	/** Array storing orchestration overhead measurements per application type */
	private double[] orchestratorOverhead = null;

	/**
	 * Private constructor implementing Singleton pattern.
	 * 
	 * <p>Initializes the logger with default settings (logging disabled) and prevents
	 * external instantiation to ensure consistent data collection across the simulation.</p>
	 */
	private SimLogger() {
		fileLogEnabled = false;
		printLogEnabled = false;
	}

	/**
	 * Returns the singleton instance of SimLogger.
	 * 
	 * <p>Provides global access to the simulation logger, ensuring all components
	 * use the same logging instance for consistent data collection and result
	 * aggregation throughout the simulation execution.</p>
	 * 
	 * @return the singleton SimLogger instance
	 */
	public static SimLogger getInstance() {
		return singleton;
	}

	/**
	 * Enables file-based logging for persistent result storage.
	 * 
	 * <p>When enabled, the logger writes detailed simulation results to files
	 * for post-simulation analysis. File logging includes comprehensive metrics
	 * suitable for MATLAB analysis and visualization tools.</p>
	 */
	public static void enableFileLog() {
		fileLogEnabled = true;
	}

	/**
	 * Enables console-based logging for real-time monitoring.
	 * 
	 * <p>When enabled, the logger outputs simulation progress and key events
	 * to the console, providing real-time feedback during simulation execution.
	 * Useful for debugging and monitoring simulation progress.</p>
	 */
	public static void enablePrintLog() {
		printLogEnabled = true;
	}

	/**
	 * Checks if file logging is currently enabled.
	 * 
	 * @return true if file logging is enabled, false otherwise
	 */
	public static boolean isFileLogEnabled() {
		return fileLogEnabled;
	}

	/**
	 * Disables file-based logging to reduce I/O overhead.
	 * 
	 * <p>When disabled, simulation results will only be kept in memory
	 * and won't be persistently stored to files.</p>
	 */
	public static void disableFileLog() {
		fileLogEnabled = false;
	}
	
	/**
	 * Disables console-based logging to reduce output verbosity.
	 * 
	 * <p>When disabled, the logger will run silently without console output,
	 * improving performance for batch simulation runs.</p>
	 */
	public static void disablePrintLog() {
		printLogEnabled = false;
	}
	
	/**
	 * Returns the output folder path for simulation results.
	 * 
	 * @return the directory path where simulation output files are stored
	 */
	public String getOutputFolder() {
		return outputFolder;
	}

	/**
	 * Helper method to append a line to a buffered writer with proper formatting.
	 * 
	 * @param bw the BufferedWriter to write to
	 * @param line the text line to append
	 * @throws IOException if writing fails
	 */
	private void appendToFile(BufferedWriter bw, String line) throws IOException {
		bw.write(line);
		bw.newLine();
	}

	/**
	 * Prints a line to console if print logging is enabled.
	 * 
	 * <p>Provides conditional console output based on the global print logging
	 * setting. Use this method throughout EdgeCloudSim for consistent logging behavior.</p>
	 * 
	 * @param msg the message to print to console
	 */
	public static void printLine(String msg) {
		if (printLogEnabled)
			System.out.println(msg);
	}

	/**
	 * Prints text to console without newline if print logging is enabled.
	 * 
	 * <p>Useful for progress indicators and multi-part messages that span
	 * multiple print calls.</p>
	 * 
	 * @param msg the message to print to console
	 */
	public static void print(String msg) {
		if (printLogEnabled)
			System.out.print(msg);
	}

	/**
	 * Initializes the logging system for a new simulation run.
	 * 
	 * <p>This method sets up all necessary data structures and file handles for
	 * collecting simulation metrics. It must be called before any logging operations
	 * begin. The method configures both memory-based and file-based logging depending
	 * on the enabled logging modes.</p>
	 * 
	 * <p><b>Initialization Tasks:</b>
	 * <ul>
	 *   <li>Records simulation start timestamp for performance measurement</li>
	 *   <li>Sets up output file naming and directory structure</li>
	 *   <li>Initializes metric collection arrays for all application types</li>
	 *   <li>Creates file handles for deep logging if enabled</li>
	 *   <li>Prepares data structures for real-time event tracking</li>
	 * </ul></p>
	 * 
	 * @param outFolder the directory path where result files will be stored
	 * @param fileName the prefix for output file naming
	 */
	public void simStarted(String outFolder, String fileName) {
		startTime = System.currentTimeMillis();
		filePrefix = fileName;
		outputFolder = outFolder;
		taskMap = new HashMap<Integer, LogItem>();
		vmLoadList = new LinkedList<VmLoadLogItem>();
		apDelayList = new LinkedList<ApDelayLogItem>();
		uavLocationList = new LinkedList<String>();
		eventList = new LinkedList<String>();
		
		numOfAppTypes = SimSettings.getInstance().getNumOfTaskTypes();
		numOfStatSlots = numOfAppTypes + 3;
		
		if (SimSettings.getInstance().getDeepFileLoggingEnabled()) {
			try {
				successFile = new File(outputFolder, filePrefix + "_SUCCESS.log");
				successFW = new FileWriter(successFile, true);
				successBW = new BufferedWriter(successFW);

				failFile = new File(outputFolder, filePrefix + "_FAIL.log");
				failFW = new FileWriter(failFile, true);
				failBW = new BufferedWriter(failFW);
				
				appendToFile(successBW, "#auto generated file!");
				appendToFile(failBW, "#auto generated file!");
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}

		// extract following values for each app type.
		// ONAT: the last 3 indexes are aggregates - see getTotalIndex()/getApplicationsIndex()/getStreamsIndex()
		uncompletedTask = new int[numOfStatSlots];
		uncompletedTaskOnCloud = new int[numOfStatSlots];
		uncompletedTaskOnEdge = new int[numOfStatSlots];
		uncompletedTaskOnMobile = new int[numOfStatSlots];

		completedTask = new int[numOfStatSlots];
		completedTaskOnCloud = new int[numOfStatSlots];
		completedTaskOnEdge = new int[numOfStatSlots];
		completedTaskOnMobile = new int[numOfStatSlots];

		failedTask = new int[numOfStatSlots];
		failedTaskOnCloud = new int[numOfStatSlots];
		failedTaskOnEdge = new int[numOfStatSlots];
		failedTaskOnMobile = new int[numOfStatSlots];

		networkDelay = new double[numOfStatSlots];
		gsmDelay = new double[numOfStatSlots];
		wanDelay = new double[numOfStatSlots];
		manDelay = new double[numOfStatSlots];
		lanDelay = new double[numOfStatSlots];
		uavDelay = new double[numOfStatSlots];
		
		gsmUsage = new double[numOfStatSlots];
		wanUsage = new double[numOfStatSlots];
		manUsage = new double[numOfStatSlots];
		lanUsage = new double[numOfStatSlots];
		uavUsage = new double[numOfStatSlots];

		serviceTime = new double[numOfStatSlots];
		serviceTimeOnCloud = new double[numOfStatSlots];
		serviceTimeOnEdge = new double[numOfStatSlots];
		serviceTimeOnMobile = new double[numOfStatSlots];

		processingTime = new double[numOfStatSlots];
		processingTimeOnCloud = new double[numOfStatSlots];
		processingTimeOnEdge = new double[numOfStatSlots];
		processingTimeOnMobile = new double[numOfStatSlots];

		failedTaskDueToVmCapacity = new int[numOfStatSlots];
		failedTaskDueToVmCapacityOnCloud = new int[numOfStatSlots];
		failedTaskDueToVmCapacityOnEdge = new int[numOfStatSlots];
		failedTaskDueToVmCapacityOnMobile = new int[numOfStatSlots];
		
		cost = new double[numOfStatSlots];
		QoE = new double[numOfStatSlots];
		failedTaskDuetoBw = new int[numOfStatSlots];
		failedTaskDuetoLanBw = new int[numOfStatSlots];
		failedTaskDuetoManBw = new int[numOfStatSlots];
		failedTaskDuetoWanBw = new int[numOfStatSlots];
		failedTaskDuetoGsmBw = new int[numOfStatSlots];
		failedTaskDuetoMobility = new int[numOfStatSlots];
		refectedTaskDuetoWlanRange = new int[numOfStatSlots];
		failedTaskDuetoTimeout = new int[numOfStatSlots];

		orchestratorOverhead = new double[numOfStatSlots];
	}

	/**
	 * Creates a new log entry for a task with its basic characteristics.
	 * 
	 * <p>Initializes logging for a new task by creating a LogItem with the specified
	 * parameters. This method should be called when a task is first created and
	 * before any processing begins.</p>
	 * 
	 * @param deviceId ID of the mobile device generating the task
	 * @param taskId unique identifier for the task
	 * @param taskType application type index of the task
	 * @param taskLenght computational requirement in million instructions
	 * @param taskInputType input data size in bytes
	 * @param taskOutputSize output data size in bytes
	 */
	public void addLog(int deviceId, int taskId, int taskType,
			int taskLenght, int taskInputType, int taskOutputSize) {
		taskMap.put(taskId, new LogItem(deviceId, taskType, taskLenght, taskInputType, taskOutputSize));
	}

	/**
	 * ONAT: creates the log entry of a stream node - the application task a stream request
	 * runs for one of its DAG nodes. It is logged like any other task (every later call such
	 * as taskStarted()/setUploadDelay() works unchanged on nodeTaskId), but once it ends it is
	 * not added to the statistics: its network delays are folded into the stream request's
	 * own entry (see addLog() for streamId), a failure becomes the stream's failure, and the
	 * StreamNodeListener is told. Success of the stream itself is up to the caller
	 * (taskEnded(streamId)) since it depends on every other node too.
	 *
	 * @param streamId id of the stream request this node belongs to
	 * @param nodeTaskId unique id of the node's own task
	 * @param taskType application type index of the node's application
	 */
	public void addStreamNodeLog(int streamId, int deviceId, int nodeTaskId, int taskType,
			int taskLenght, int taskInputType, int taskOutputSize) {
		LogItem node = new LogItem(deviceId, taskType, taskLenght, taskInputType, taskOutputSize);
		node.setStreamId(streamId);
		taskMap.put(nodeTaskId, node);
	}

	/**
	 * Records the start time of task processing.
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when task processing begins
	 */
	public void taskStarted(int taskId, double time) {
		taskMap.get(taskId).taskStarted(time);
	}

	/**
	 * Records upload network delay for a task.
	 * 
	 * @param taskId unique identifier for the task
	 * @param delay upload delay in seconds
	 * @param delayType type of network segment causing the delay
	 */
	public void setUploadDelay(int taskId, double delay, NETWORK_DELAY_TYPES delayType) {
		taskMap.get(taskId).setUploadDelay(delay, delayType);
	}

	/**
	 * Records download network delay for a task.
	 * 
	 * @param taskId unique identifier for the task
	 * @param delay download delay in seconds
	 * @param delayType type of network segment causing the delay
	 */
	public void setDownloadDelay(int taskId, double delay, NETWORK_DELAY_TYPES delayType) {
		// ONAT: a plain task that already timed out has been recorded (and removed from
		// the map) while its cloudlet kept running - nothing left to record for it.
		LogItem item = taskMap.get(taskId);
		if (item != null)
			item.setDownloadDelay(delay, delayType);
	}

	/**
	 * Records a DAG inter-layer relay delay for a task - accumulates rather than
	 * overwrites, since a DAG job may hand off between multiple consecutive layer
	 * pairs over its lifetime (see NetworkModel.getInterLayerDelay()).
	 * 
	 * @param taskId unique identifier for the (parent) task
	 * @param delay inter-layer relay delay in seconds
	 */
	public void addInterLayerDelay(int taskId, double delay) {
		taskMap.get(taskId).addUavDelay(delay);
	}
	
	/**
	 * Records task assignment to computational resources.
	 * 
	 * @param taskId unique identifier for the task
	 * @param datacenterId ID of the assigned datacenter
	 * @param hostId ID of the assigned host machine
	 * @param vmId ID of the assigned virtual machine
	 * @param vmType type/tier of the processing environment (cloud/edge/mobile)
	 */
	public void taskAssigned(int taskId, int datacenterId, int hostId, int vmId, int vmType) {
		// ONAT: ignored for an already recorded (e.g. timed-out) task, see setDownloadDelay()
		LogItem item = taskMap.get(taskId);
		if (item != null)
			item.taskAssigned(datacenterId, hostId, vmId, vmType);
	}

	/**
	 * Records the start of task execution on the assigned VM.
	 * 
	 * @param taskId unique identifier for the task
	 */
	public void taskExecuted(int taskId) {
		// ONAT: ignored for an already recorded (e.g. timed-out) task, see setDownloadDelay()
		LogItem item = taskMap.get(taskId);
		if (item != null)
			item.taskExecuted();
	}

	/**
	 * Records task completion and triggers result aggregation.
	 * 
	 * <p>Marks the task as successfully completed and calls recordLog to
	 * aggregate the results into summary statistics and optionally write
	 * to result files.</p>
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when task completed
	 */
	public void taskEnded(int taskId, double time) {
		taskMap.get(taskId).taskEnded(time);
		recordLog(taskId);
	}

	/**
	 * Records task rejection due to insufficient VM computational capacity.
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when rejection occurred
	 * @param vmType type of VM that rejected the task (cloud/edge/mobile)
	 */
	public void rejectedDueToVMCapacity(int taskId, double time, int vmType) {
		taskMap.get(taskId).taskRejectedDueToVMCapacity(time, vmType);
		recordLog(taskId);
	}

	/**
	 * Records task rejection due to WLAN coverage limitations.
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when rejection occurred
	 * @param vmType intended VM type for the rejected task
	 */
    public void rejectedDueToWlanCoverage(int taskId, double time, int vmType) {
    	taskMap.get(taskId).taskRejectedDueToWlanCoverage(time, vmType);
		recordLog(taskId);
    }
    
    /**
     * Records task rejection due to insufficient network bandwidth.
     * 
     * @param taskId unique identifier for the task
     * @param time simulation time when rejection occurred
     * @param vmType intended VM type for the rejected task
     * @param delayType network segment that caused the bandwidth limitation
     */
	public void rejectedDueToBandwidth(int taskId, double time, int vmType, NETWORK_DELAY_TYPES delayType) {
		taskMap.get(taskId).taskRejectedDueToBandwidth(time, vmType, delayType);
		recordLog(taskId);
	}

	/**
	 * Records task failure due to network bandwidth constraints.
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when failure occurred
	 * @param delayType network segment that caused the bandwidth failure
	 */
	public void failedDueToBandwidth(int taskId, double time, NETWORK_DELAY_TYPES delayType) {
		taskMap.get(taskId).taskFailedDueToBandwidth(time, delayType);
		recordLog(taskId);
	}

	/**
	 * Records a hard task-timeout cancellation (see SimSettings.getTaskTimeout(taskType)).
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when the timeout fired
	 * @param vmType VM tier the task was uploading to/running on/downloading from when cancelled
	 */
	public void timedOut(int taskId, double time, int vmType) {
		taskMap.get(taskId).taskTimedOut(time, vmType);
		recordLog(taskId);
	}

	/**
	 * Records task failure due to device mobility (moved out of coverage).
	 * 
	 * @param taskId unique identifier for the task
	 * @param time simulation time when mobility failure occurred
	 */
	public void failedDueToMobility(int taskId, double time) {
		taskMap.get(taskId).taskFailedDueToMobility(time);
		recordLog(taskId);
	}

	/**
	 * Records Quality of Experience (QoE) metric for a task.
	 * 
	 * @param taskId unique identifier for the task
	 * @param QoE quality of experience score
	 */
	public void setQoE(int taskId, double QoE){
		taskMap.get(taskId).setQoE(QoE);
	}
	
	/**
	 * Records orchestration overhead for task management.
	 * 
	 * @param taskId unique identifier for the task
	 * @param overhead orchestration overhead in seconds
	 */
	public void setOrchestratorOverhead(int taskId, double overhead){
		taskMap.get(taskId).setOrchestratorOverhead(overhead);
	}

	/**
	 * Records VM utilization across different processing tiers.
	 * 
	 * <p>Logs computational load distribution for performance analysis.
	 * Only records if location logging is enabled in simulation settings.</p>
	 * 
	 * @param time current simulation time
	 * @param loadOnEdge computational load on edge infrastructure (0-1)
	 * @param loadOnCloud computational load on cloud infrastructure (0-1)
	 * @param loadOnMobile computational load on mobile devices (0-1)
	 */
	public void addVmUtilizationLog(double time, double loadOnEdge, double loadOnCloud, double loadOnMobile) {
		if(SimSettings.getInstance().getLocationLogInterval() != 0)
			vmLoadList.add(new VmLoadLogItem(time, loadOnEdge, loadOnCloud, loadOnMobile));
	}

	/**
	 * ONAT: Records a UAV's (edge server's) position at the given time, so that its
	 * movement can be visualized the same way as mobile device positions.
	 * Only records if file logging and location logging are enabled.
	 *
	 * @param time current simulation time
	 * @param uavId identifier of the UAV (edge host id)
	 * @param x current X position of the UAV
	 * @param y current Y position of the UAV
	 */
	public void addUavLocationLog(double time, int uavId, int x, int y) {
		if(fileLogEnabled && SimSettings.getInstance().getLocationLogInterval() != 0)
			uavLocationList.add(time + SimSettings.DELIMITER + uavId + SimSettings.DELIMITER + x + SimSettings.DELIMITER + y);
	}

	/**
	 * ONAT: Records one world event of the scenario (see events/WorldEvent) - written to the
	 * _EVENTS.log file at the end of the simulation so the events can be drawn next to the
	 * user and UAV positions (e.g. in the heat-map video). Call it once per event after
	 * simStarted(); a scenario that never calls it gets no events file. Only records if file
	 * logging is enabled.
	 *
	 * @param eventLogLine the event as one WorldEvent.toLogLine() line
	 */
	public void addEventLog(String eventLogLine) {
		if(fileLogEnabled)
			eventList.add(eventLogLine);
	}

	/**
	 * Records access point network delay measurements over time.
	 * 
	 * <p>Logs upload and download delays for all access points to analyze
	 * network performance variations. Only records if AP delay logging
	 * is enabled in simulation settings.</p>
	 * 
	 * @param time current simulation time
	 * @param apUploadDelays array of upload delays for each access point
	 * @param apDownloadDelays array of download delays for each access point
	 */
	public void addApDelayLog(double time, double[] apUploadDelays, double[] apDownloadDelays) {
		if(SimSettings.getInstance().getApDelayLogInterval() != 0)
			apDelayList.add(new ApDelayLogItem(time, apUploadDelays, apDownloadDelays));
	}
	
	/**
	 * Finalizes logging and generates comprehensive simulation result files.
	 * 
	 * <p>This method performs the critical task of aggregating all collected
	 * simulation data and writing detailed results to files. It calculates
	 * summary statistics, generates performance reports, and creates output
	 * files suitable for MATLAB analysis and visualization.</p>
	 * 
	 * <p><b>Generated Output Files:</b>
	 * <ul>
	 *   <li>Generic results per application type with performance metrics</li>
	 *   <li>VM utilization logs for resource usage analysis</li>
	 *   <li>Location-based performance breakdowns</li>
	 *   <li>Per-device (x,y) position log for mobility visualization</li>
	 *   <li>Access point delay measurements over time</li>
	 *   <li>Deep task logs (if enabled) for detailed event analysis</li>
	 * </ul></p>
	 * 
	 * @throws IOException if file writing operations fail
	 */
	public void simStopped() throws IOException {
		endTime = System.currentTimeMillis();
		File vmLoadFile = null, locationFile = null, userLocationFile = null, uavLocationFile = null, apUploadDelayFile = null, apDownloadDelayFile = null;
		FileWriter vmLoadFW = null, locationFW = null, userLocationFW = null, uavLocationFW = null, apUploadDelayFW = null, apDownloadDelayFW = null;
		BufferedWriter vmLoadBW = null, locationBW = null, userLocationBW = null, uavLocationBW = null, apUploadDelayBW = null, apDownloadDelayBW = null;

		// Save generic results to file for each app type (every application, then every
		// stream) plus the applications and streams aggregates - see getGenericFileLabel()
		File[] genericFiles = new File[numOfStatSlots];
		FileWriter[] genericFWs = new FileWriter[numOfStatSlots];
		BufferedWriter[] genericBWs = new BufferedWriter[numOfStatSlots];

		// open all files and prepare them for write
		if (fileLogEnabled) {
			vmLoadFile = new File(outputFolder, filePrefix + "_VM_LOAD.log");
			vmLoadFW = new FileWriter(vmLoadFile, true);
			vmLoadBW = new BufferedWriter(vmLoadFW);

			locationFile = new File(outputFolder, filePrefix + "_LOCATION.log");
			locationFW = new FileWriter(locationFile, true);
			locationBW = new BufferedWriter(locationFW);

			userLocationFile = new File(outputFolder, filePrefix + "_USER_LOCATIONS.log");
			userLocationFW = new FileWriter(userLocationFile, true);
			userLocationBW = new BufferedWriter(userLocationFW);

			uavLocationFile = new File(outputFolder, filePrefix + "_UAV_LOCATIONS.log");
			uavLocationFW = new FileWriter(uavLocationFile, true);
			uavLocationBW = new BufferedWriter(uavLocationFW);

			apUploadDelayFile = new File(outputFolder, filePrefix + "_AP_UPLOAD_DELAY.log");
			apUploadDelayFW = new FileWriter(apUploadDelayFile, true);
			apUploadDelayBW = new BufferedWriter(apUploadDelayFW);

			apDownloadDelayFile = new File(outputFolder, filePrefix + "_AP_DOWNLOAD_DELAY.log");
			apDownloadDelayFW = new FileWriter(apDownloadDelayFile, true);
			apDownloadDelayBW = new BufferedWriter(apDownloadDelayFW);

			for (int i = 0; i < numOfStatSlots; i++) {
				// ONAT: slots without a file (an unused app/stream, the all-requests total)
				// are discarded
				String fileLabel = getGenericFileLabel(i);
				if (fileLabel == null)
					continue;

				genericFiles[i] = new File(outputFolder, filePrefix + "_" + fileLabel + "_GENERIC.log");
				genericFWs[i] = new FileWriter(genericFiles[i], true);
				genericBWs[i] = new BufferedWriter(genericFWs[i]);
				appendToFile(genericBWs[i], "#auto generated file!");
			}

			appendToFile(vmLoadBW, "#auto generated file!");
			appendToFile(locationBW, "#auto generated file!");
			appendToFile(userLocationBW, "#auto generated file!");
			appendToFile(uavLocationBW, "#auto generated file!");
			appendToFile(apUploadDelayBW, "#auto generated file!");
			appendToFile(apDownloadDelayBW, "#auto generated file!");
		}

		//the tasks in the map is not completed yet!
		for (Map.Entry<Integer, LogItem> entry : taskMap.entrySet()) {
			LogItem value = entry.getValue();

			// ONAT: a stream node still running is part of its (counted) stream request
			if (value.isStreamNode())
				continue;

			uncompletedTask[value.getTaskType()]++;
			if (value.getVmType() == SimSettings.VM_TYPES.CLOUD_VM.ordinal())
				uncompletedTaskOnCloud[value.getTaskType()]++;
			else if (value.getVmType() == SimSettings.VM_TYPES.MOBILE_VM.ordinal())
				uncompletedTaskOnMobile[value.getTaskType()]++;
			else
				uncompletedTaskOnEdge[value.getTaskType()]++;
		}

		// calculate total values (ONAT: and the applications-only / streams-only aggregates)
		fillAggregates(uncompletedTask);
		fillAggregates(uncompletedTaskOnCloud);
		fillAggregates(uncompletedTaskOnEdge);
		fillAggregates(uncompletedTaskOnMobile);

		fillAggregates(completedTask);
		fillAggregates(completedTaskOnCloud);
		fillAggregates(completedTaskOnEdge);
		fillAggregates(completedTaskOnMobile);

		fillAggregates(failedTask);
		fillAggregates(failedTaskOnCloud);
		fillAggregates(failedTaskOnEdge);
		fillAggregates(failedTaskOnMobile);

		fillAggregates(networkDelay);
		fillAggregates(lanDelay);
		fillAggregates(manDelay);
		fillAggregates(wanDelay);
		fillAggregates(gsmDelay);
		fillAggregates(uavDelay);
		
		fillAggregates(lanUsage);
		fillAggregates(manUsage);
		fillAggregates(wanUsage);
		fillAggregates(gsmUsage);
		fillAggregates(uavUsage);

		fillAggregates(serviceTime);
		fillAggregates(serviceTimeOnCloud);
		fillAggregates(serviceTimeOnEdge);
		fillAggregates(serviceTimeOnMobile);

		fillAggregates(processingTime);
		fillAggregates(processingTimeOnCloud);
		fillAggregates(processingTimeOnEdge);
		fillAggregates(processingTimeOnMobile);

		fillAggregates(failedTaskDueToVmCapacity);
		fillAggregates(failedTaskDueToVmCapacityOnCloud);
		fillAggregates(failedTaskDueToVmCapacityOnEdge);
		fillAggregates(failedTaskDueToVmCapacityOnMobile);
		
		fillAggregates(cost);
		fillAggregates(QoE);
		fillAggregates(failedTaskDuetoBw);
		fillAggregates(failedTaskDuetoGsmBw);
		fillAggregates(failedTaskDuetoWanBw);
		fillAggregates(failedTaskDuetoManBw);
		fillAggregates(failedTaskDuetoLanBw);
		fillAggregates(failedTaskDuetoMobility);
		fillAggregates(refectedTaskDuetoWlanRange);
		fillAggregates(failedTaskDuetoTimeout);

		fillAggregates(orchestratorOverhead);
		
		// calculate server load
		double totalVmLoadOnEdge = 0;
		double totalVmLoadOnCloud = 0;
		double totalVmLoadOnMobile = 0;
		// ONAT: like the task statistics, the average server load only covers samples taken
		// after the warm-up period; the VM load file below still gets the whole time series
		// (warm-up included) so the transient stays visible there.
		int vmLoadSampleCount = 0;
		for (VmLoadLogItem entry : vmLoadList) {
			if (fileLogEnabled && SimSettings.getInstance().getVmLoadLogInterval() != 0)
				appendToFile(vmLoadBW, entry.toString());

			if (entry.getTime() < SimSettings.getInstance().getWarmUpPeriod())
				continue;

			totalVmLoadOnEdge += entry.getEdgeLoad();
			totalVmLoadOnCloud += entry.getCloudLoad();
			totalVmLoadOnMobile += entry.getMobileLoad();
			vmLoadSampleCount++;
		}

		if (fileLogEnabled) {
			// write location info to file for each location
			// assuming each location has only one access point
			double locationLogInterval = SimSettings.getInstance().getLocationLogInterval();
			if(locationLogInterval != 0) {
				for (int t = 1; t < (SimSettings.getInstance().getSimulationTime() / locationLogInterval); t++) {
					int[] locationInfo = new int[SimSettings.getInstance().getNumOfEdgeDatacenters()];
					Double time = t * SimSettings.getInstance().getLocationLogInterval();
					
					if (time < SimSettings.CLIENT_ACTIVITY_START_TIME)
						continue;

					for (int i = 0; i < SimManager.getInstance().getNumOfMobileDevice(); i++) {
						Location loc = SimManager.getInstance().getMobilityModel().getLocation(i, time);
						locationInfo[loc.getServingWlanId()]++;

						// record the exact position of each user at the same sampling
						// frequency as the location log, so that the movement of the
						// users can be visualized (e.g. as a heat map over time)
						userLocationBW.write(time.toString());
						userLocationBW.write(SimSettings.DELIMITER + i);
						userLocationBW.write(SimSettings.DELIMITER + loc.getXPos());
						userLocationBW.write(SimSettings.DELIMITER + loc.getYPos());
						userLocationBW.newLine();
					}

					locationBW.write(time.toString());
					for (int i = 0; i < locationInfo.length; i++)
						locationBW.write(SimSettings.DELIMITER + locationInfo[i]);

					locationBW.newLine();
				}
			}

			// ONAT: write the UAV (edge server) positions recorded live during the simulation
			for (String entry : uavLocationList)
				appendToFile(uavLocationBW, entry);

			// ONAT: write the world events (tutorial19) - only for scenarios that have any
			if (!eventList.isEmpty()) {
				File eventFile = new File(outputFolder, filePrefix + "_EVENTS.log");
				try (BufferedWriter eventBW = new BufferedWriter(new FileWriter(eventFile, true))) {
					appendToFile(eventBW, "#auto generated file!");
					for (String entry : eventList)
						appendToFile(eventBW, entry);
				}
			}

			// write delay info to file for each access point
			if(SimSettings.getInstance().getApDelayLogInterval() != 0) {
				for (ApDelayLogItem entry : apDelayList) {
					appendToFile(apUploadDelayBW, entry.getUploadStat());
					appendToFile(apDownloadDelayBW, entry.getDownloadStat());
				}
			}

			for (int i = 0; i < numOfStatSlots; i++) {

				// ONAT: no file for an unused app/stream, nor for the all-requests total
				if (getGenericFileLabel(i) == null)
					continue;

				// check if the divisor is zero in order to avoid division by
				// zero problem
				double _serviceTime = (completedTask[i] == 0) ? 0.0 : (serviceTime[i] / (double) completedTask[i]);
				double _networkDelay = (completedTask[i] == 0) ? 0.0 : (networkDelay[i] / ((double) completedTask[i] - (double)completedTaskOnMobile[i]));
				double _processingTime = (completedTask[i] == 0) ? 0.0 : (processingTime[i] / (double) completedTask[i]);
				double _vmLoadOnEdge = (vmLoadSampleCount == 0) ? 0.0 : (totalVmLoadOnEdge / (double) vmLoadSampleCount);
				double _vmLoadOnClould = (vmLoadSampleCount == 0) ? 0.0 : (totalVmLoadOnCloud / (double) vmLoadSampleCount);
				double _vmLoadOnMobile = (vmLoadSampleCount == 0) ? 0.0 : (totalVmLoadOnMobile / (double) vmLoadSampleCount);
				double _cost = (completedTask[i] == 0) ? 0.0 : (cost[i] / (double) completedTask[i]);
				double _QoE1 = (completedTask[i] == 0) ? 0.0 : (QoE[i] / (double) completedTask[i]);
				double _QoE2 = (completedTask[i] == 0) ? 0.0 : (QoE[i] / (double) (failedTask[i] + completedTask[i]));

				double _lanDelay = (lanUsage[i] == 0) ? 0.0
						: (lanDelay[i] / (double) lanUsage[i]);
				double _manDelay = (manUsage[i] == 0) ? 0.0
						: (manDelay[i] / (double) manUsage[i]);
				double _wanDelay = (wanUsage[i] == 0) ? 0.0
						: (wanDelay[i] / (double) wanUsage[i]);
				double _gsmDelay = (gsmUsage[i] == 0) ? 0.0
						: (gsmDelay[i] / (double) gsmUsage[i]);
				double _uavDelay = (uavUsage[i] == 0) ? 0.0
						: (uavDelay[i] / (double) uavUsage[i]);
				
				// write generic results
				String genericResult1 = Integer.toString(completedTask[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTask[i]) + SimSettings.DELIMITER 
						+ Integer.toString(uncompletedTask[i]) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDuetoBw[i]) + SimSettings.DELIMITER
						+ Double.toString(_serviceTime) + SimSettings.DELIMITER 
						+ Double.toString(_processingTime) + SimSettings.DELIMITER 
						+ Double.toString(_networkDelay) + SimSettings.DELIMITER
						+ Double.toString(0) + SimSettings.DELIMITER 
						+ Double.toString(_cost) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDueToVmCapacity[i]) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDuetoMobility[i]) + SimSettings.DELIMITER 
						+ Double.toString(_QoE1) + SimSettings.DELIMITER 
						+ Double.toString(_QoE2) + SimSettings.DELIMITER
						+ Integer.toString(refectedTaskDuetoWlanRange[i]);

				// check if the divisor is zero in order to avoid division by zero problem
				double _serviceTimeOnEdge = (completedTaskOnEdge[i] == 0) ? 0.0
						: (serviceTimeOnEdge[i] / (double) completedTaskOnEdge[i]);
				double _processingTimeOnEdge = (completedTaskOnEdge[i] == 0) ? 0.0
						: (processingTimeOnEdge[i] / (double) completedTaskOnEdge[i]);
				String genericResult2 = Integer.toString(completedTaskOnEdge[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskOnEdge[i]) + SimSettings.DELIMITER
						+ Integer.toString(uncompletedTaskOnEdge[i]) + SimSettings.DELIMITER
						+ Integer.toString(0) + SimSettings.DELIMITER
						+ Double.toString(_serviceTimeOnEdge) + SimSettings.DELIMITER
						+ Double.toString(_processingTimeOnEdge) + SimSettings.DELIMITER
						+ Double.toString(0.0) + SimSettings.DELIMITER 
						+ Double.toString(_vmLoadOnEdge) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDueToVmCapacityOnEdge[i]);

				// check if the divisor is zero in order to avoid division by zero problem
				double _serviceTimeOnCloud = (completedTaskOnCloud[i] == 0) ? 0.0
						: (serviceTimeOnCloud[i] / (double) completedTaskOnCloud[i]);
				double _processingTimeOnCloud = (completedTaskOnCloud[i] == 0) ? 0.0
						: (processingTimeOnCloud[i] / (double) completedTaskOnCloud[i]);
				String genericResult3 = Integer.toString(completedTaskOnCloud[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskOnCloud[i]) + SimSettings.DELIMITER
						+ Integer.toString(uncompletedTaskOnCloud[i]) + SimSettings.DELIMITER
						+ Integer.toString(0) + SimSettings.DELIMITER
						+ Double.toString(_serviceTimeOnCloud) + SimSettings.DELIMITER
						+ Double.toString(_processingTimeOnCloud) + SimSettings.DELIMITER 
						+ Double.toString(0.0) + SimSettings.DELIMITER
						+ Double.toString(_vmLoadOnClould) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDueToVmCapacityOnCloud[i]);
				
				// check if the divisor is zero in order to avoid division by zero problem
				double _serviceTimeOnMobile = (completedTaskOnMobile[i] == 0) ? 0.0
						: (serviceTimeOnMobile[i] / (double) completedTaskOnMobile[i]);
				double _processingTimeOnMobile = (completedTaskOnMobile[i] == 0) ? 0.0
						: (processingTimeOnMobile[i] / (double) completedTaskOnMobile[i]);
				String genericResult4 = Integer.toString(completedTaskOnMobile[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskOnMobile[i]) + SimSettings.DELIMITER
						+ Integer.toString(uncompletedTaskOnMobile[i]) + SimSettings.DELIMITER
						+ Integer.toString(0) + SimSettings.DELIMITER
						+ Double.toString(_serviceTimeOnMobile) + SimSettings.DELIMITER
						+ Double.toString(_processingTimeOnMobile) + SimSettings.DELIMITER 
						+ Double.toString(0.0) + SimSettings.DELIMITER
						+ Double.toString(_vmLoadOnMobile) + SimSettings.DELIMITER 
						+ Integer.toString(failedTaskDueToVmCapacityOnMobile[i]);
				
				String genericResult5 = Double.toString(_lanDelay) + SimSettings.DELIMITER
						+ Double.toString(_manDelay) + SimSettings.DELIMITER
						+ Double.toString(_wanDelay) + SimSettings.DELIMITER
						+ Double.toString(_gsmDelay) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskDuetoLanBw[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskDuetoManBw[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskDuetoWanBw[i]) + SimSettings.DELIMITER
						+ Integer.toString(failedTaskDuetoGsmBw[i]) + SimSettings.DELIMITER
						+ Double.toString(_uavDelay);
				
				//performance related values
				double _orchestratorOverhead = orchestratorOverhead[i] / (double) (failedTask[i] + completedTask[i]);
				
				String genericResult6 = Long.toString((endTime-startTime)/60)  + SimSettings.DELIMITER
						+ Double.toString(_orchestratorOverhead);
						

				appendToFile(genericBWs[i], genericResult1);
				appendToFile(genericBWs[i], genericResult2);
				appendToFile(genericBWs[i], genericResult3);
				appendToFile(genericBWs[i], genericResult4);
				appendToFile(genericBWs[i], genericResult5);
				
				//append performance related values only to the aggregate files (ALL_APPS, ALL_STREAMS)
				if(i >= numOfAppTypes) {
					appendToFile(genericBWs[i], genericResult6);
				}
				else {
					printLine(SimSettings.getInstance().getTaskName(i));
					printLine("# of tasks (Edge/Cloud): "
							+ (failedTask[i] + completedTask[i]) + "("
							+ (failedTaskOnEdge[i] + completedTaskOnEdge[i]) + "/" 
							+ (failedTaskOnCloud[i]+ completedTaskOnCloud[i]) + ")" );
					
					printLine("# of failed tasks (Edge/Cloud): "
							+ failedTask[i] + "("
							+ failedTaskOnEdge[i] + "/"
							+ failedTaskOnCloud[i] + ")");
					
					printLine("# of completed tasks (Edge/Cloud): "
							+ completedTask[i] + "("
							+ completedTaskOnEdge[i] + "/"
							+ completedTaskOnCloud[i] + ")");
					
					printLine("---------------------------------------");
				}
			}

			// close open files
			if (SimSettings.getInstance().getDeepFileLoggingEnabled()) {
				successBW.close();
				failBW.close();
			}
			vmLoadBW.close();
			locationBW.close();
			userLocationBW.close();
			uavLocationBW.close();
			apUploadDelayBW.close();
			apDownloadDelayBW.close();
			for (int i = 0; i < numOfStatSlots; i++) {
				if (genericBWs[i] != null)
					genericBWs[i].close();
			}
			
		}

		// printout important results
		printLine("# of tasks (Edge/Cloud/Mobile): "
				+ (failedTask[numOfAppTypes] + completedTask[numOfAppTypes]) + "("
				+ (failedTaskOnEdge[numOfAppTypes] + completedTaskOnEdge[numOfAppTypes]) + "/" 
				+ (failedTaskOnCloud[numOfAppTypes]+ completedTaskOnCloud[numOfAppTypes]) + "/" 
				+ (failedTaskOnMobile[numOfAppTypes]+ completedTaskOnMobile[numOfAppTypes]) + ")");
		
		printLine("# of failed tasks (Edge/Cloud/Mobile): "
				+ failedTask[numOfAppTypes] + "("
				+ failedTaskOnEdge[numOfAppTypes] + "/"
				+ failedTaskOnCloud[numOfAppTypes] + "/"
				+ failedTaskOnMobile[numOfAppTypes] + ")");
		
		printLine("# of completed tasks (Edge/Cloud/Mobile): "
				+ completedTask[numOfAppTypes] + "("
				+ completedTaskOnEdge[numOfAppTypes] + "/"
				+ completedTaskOnCloud[numOfAppTypes] + "/"
				+ completedTaskOnMobile[numOfAppTypes] + ")");
		
		printLine("# of uncompleted tasks (Edge/Cloud/Mobile): "
				+ uncompletedTask[numOfAppTypes] + "("
				+ uncompletedTaskOnEdge[numOfAppTypes] + "/"
				+ uncompletedTaskOnCloud[numOfAppTypes] + "/"
				+ uncompletedTaskOnMobile[numOfAppTypes] + ")");

		printLine("# of failed tasks due to vm capacity (Edge/Cloud/Mobile): "
				+ failedTaskDueToVmCapacity[numOfAppTypes] + "("
				+ failedTaskDueToVmCapacityOnEdge[numOfAppTypes] + "/"
				+ failedTaskDueToVmCapacityOnCloud[numOfAppTypes] + "/"
				+ failedTaskDueToVmCapacityOnMobile[numOfAppTypes] + ")");
		
		printLine("# of failed tasks due to Mobility/WLAN Range/Network(WLAN/MAN/WAN/GSM)/Timeout: "
				+ failedTaskDuetoMobility[numOfAppTypes]
				+ "/" + refectedTaskDuetoWlanRange[numOfAppTypes]
				+ "/" + failedTaskDuetoBw[numOfAppTypes] 
				+ "(" + failedTaskDuetoLanBw[numOfAppTypes] 
				+ "/" + failedTaskDuetoManBw[numOfAppTypes] 
				+ "/" + failedTaskDuetoWanBw[numOfAppTypes] 
				+ "/" + failedTaskDuetoGsmBw[numOfAppTypes] + ")"
				+ "/" + failedTaskDuetoTimeout[numOfAppTypes]);
		
		printLine("percentage of failed tasks: "
				+ String.format("%.6f", ((double) failedTask[numOfAppTypes] * (double) 100)
						/ (double) (completedTask[numOfAppTypes] + failedTask[numOfAppTypes]))
				+ "%");

		printLine("average service time: "
				+ String.format("%.6f", serviceTime[numOfAppTypes] / (double) completedTask[numOfAppTypes])
				+ " seconds. (" + "on Edge: "
				+ String.format("%.6f", serviceTimeOnEdge[numOfAppTypes] / (double) completedTaskOnEdge[numOfAppTypes])
				+ ", " + "on Cloud: "
				+ String.format("%.6f", serviceTimeOnCloud[numOfAppTypes] / (double) completedTaskOnCloud[numOfAppTypes])
				+ ", " + "on Mobile: "
				+ String.format("%.6f", serviceTimeOnMobile[numOfAppTypes] / (double) completedTaskOnMobile[numOfAppTypes])
				+ ")");

		printLine("average processing time: "
				+ String.format("%.6f", processingTime[numOfAppTypes] / (double) completedTask[numOfAppTypes])
				+ " seconds. (" + "on Edge: "
				+ String.format("%.6f", processingTimeOnEdge[numOfAppTypes] / (double) completedTaskOnEdge[numOfAppTypes])
				+ ", " + "on Cloud: " 
				+ String.format("%.6f", processingTimeOnCloud[numOfAppTypes] / (double) completedTaskOnCloud[numOfAppTypes])
				+ ", " + "on Mobile: " 
				+ String.format("%.6f", processingTimeOnMobile[numOfAppTypes] / (double) completedTaskOnMobile[numOfAppTypes])
				+ ")");

		printLine("average network delay: "
				+ String.format("%.6f", networkDelay[numOfAppTypes] / ((double) completedTask[numOfAppTypes] - (double) completedTaskOnMobile[numOfAppTypes]))
				+ " seconds. (" + "LAN delay: "
				+ String.format("%.6f", lanDelay[numOfAppTypes] / (double) lanUsage[numOfAppTypes])
				+ ", " + "MAN delay: "
				+ String.format("%.6f", manDelay[numOfAppTypes] / (double) manUsage[numOfAppTypes])
				+ ", " + "WAN delay: "
				+ String.format("%.6f", wanDelay[numOfAppTypes] / (double) wanUsage[numOfAppTypes])
				+ ", " + "GSM delay: "
				+ String.format("%.6f", gsmDelay[numOfAppTypes] / (double) gsmUsage[numOfAppTypes])
				+ ", " + "UAV delay: "
				+ String.format("%.6f", uavDelay[numOfAppTypes] / (double) uavUsage[numOfAppTypes]) + ")");

		printLine("average server utilization Edge/Cloud/Mobile: " 
				+ String.format("%.6f", totalVmLoadOnEdge / (double) vmLoadSampleCount) + "/"
				+ String.format("%.6f", totalVmLoadOnCloud / (double) vmLoadSampleCount) + "/"
				+ String.format("%.6f", totalVmLoadOnMobile / (double) vmLoadSampleCount));

		printLine("average cost: " + cost[numOfAppTypes] / completedTask[numOfAppTypes] + "$");
		printLine("average overhead: " + orchestratorOverhead[numOfAppTypes] / (failedTask[numOfAppTypes] + completedTask[numOfAppTypes]) + " ns");
		printLine("average QoE (for all): " + QoE[numOfAppTypes] / (failedTask[numOfAppTypes] + completedTask[numOfAppTypes]) + "%");
		printLine("average QoE (for executed): " + QoE[numOfAppTypes] / completedTask[numOfAppTypes] + "%");

		// clear related collections (map list etc.)
		taskMap.clear();
		vmLoadList.clear();
		apDelayList.clear();
		eventList.clear();
	}
	
	/**
	 * ONAT: the stream's timeout fired while some of its nodes were still running: from now on
	 * the failure of one of them must NOT become the stream's outcome (see endStreamNode()) -
	 * the caller logs the stream with timedOut() once those nodes have all ended.
	 */
	public void markStreamTimeoutPending(int streamId) {
		LogItem stream = taskMap.get(streamId);
		if (stream != null)
			stream.setTimeoutPending();
	}

	/**
	 * ONAT: a stream node ended (see addStreamNodeLog()): folds its network delays into the
	 * stream request's own entry and, if the node failed, makes that failure the stream's
	 * outcome (the stream cannot go on without the node) - then tells the listener. A stream
	 * request that is already resolved on its own is left alone: the node just ran to its end.
	 * One whose timeout is pending (see markStreamTimeoutPending()) only gets the delays - its
	 * outcome is the timeout, logged by the caller.
	 */
	private void endStreamNode(int nodeTaskId, LogItem node) {
		int streamId = node.getStreamId();
		LogItem stream = taskMap.get(streamId);
		boolean failed = node.getStatus() != TASK_STATUS.COMLETED;
		boolean decidesStream = failed && stream != null && !stream.isTimeoutPending();

		if (stream != null) {
			stream.addNetworkDelaysOf(node);
			if (decidesStream)
				stream.adoptFailureOf(node);
		}

		if (streamNodeListener != null)
			streamNodeListener.onStreamNodeEnded(streamId, nodeTaskId, node.getStatus());

		if (decidesStream)
			recordLog(streamId);
	}

	/**
	 * Processes and aggregates completed task metrics into summary statistics.
	 * 
	 * <p>This critical method removes a task from active tracking and aggregates
	 * its results into the appropriate statistical arrays. It handles both successful
	 * and failed tasks, organizing metrics by task type and processing tier
	 * (cloud/edge/mobile) for comprehensive performance analysis.</p>
	 * 
	 * <p><b>Aggregated Metrics Include:</b>
	 * <ul>
	 *   <li>Task completion and failure counts by tier</li>
	 *   <li>Service times and network delays</li>
	 *   <li>Processing costs and QoE scores</li>
	 *   <li>Orchestration overhead measurements</li>
	 *   <li>Network utilization across different segments</li>
	 * </ul></p>
	 * 
	 * <p>Tasks that occurred during the warm-up period are excluded from
	 * statistics to ensure accurate steady-state performance measurements.</p>
	 * 
	 * @param taskId unique identifier of the task to record
	 */
	private void recordLog(int taskId){
		LogItem value = taskMap.remove(taskId);

		// ONAT: a stream node is not a task of its own in the statistics, see addStreamNodeLog()
		if (value.isStreamNode()) {
			endStreamNode(taskId, value);
			return;
		}
		
		// Skip tasks from warm-up period to ensure steady-state statistics
		if (value.isInWarmUpPeriod())
			return;

		if (value.getStatus() == SimLogger.TASK_STATUS.COMLETED) {
			completedTask[value.getTaskType()]++;

			if (value.getVmType() == SimSettings.VM_TYPES.CLOUD_VM.ordinal())
				completedTaskOnCloud[value.getTaskType()]++;
			else if (value.getVmType() == SimSettings.VM_TYPES.MOBILE_VM.ordinal())
				completedTaskOnMobile[value.getTaskType()]++;
			else
				completedTaskOnEdge[value.getTaskType()]++;
		}
		else {
			failedTask[value.getTaskType()]++;

			if (value.getVmType() == SimSettings.VM_TYPES.CLOUD_VM.ordinal())
				failedTaskOnCloud[value.getTaskType()]++;
			else if (value.getVmType() == SimSettings.VM_TYPES.MOBILE_VM.ordinal())
				failedTaskOnMobile[value.getTaskType()]++;
			else
				failedTaskOnEdge[value.getTaskType()]++;
		}

		if (value.getStatus() == SimLogger.TASK_STATUS.COMLETED) {
			cost[value.getTaskType()] += value.getCost();
			QoE[value.getTaskType()] += value.getQoE();
			serviceTime[value.getTaskType()] += value.getServiceTime();
			networkDelay[value.getTaskType()] += value.getNetworkDelay();
			processingTime[value.getTaskType()] += (value.getServiceTime() - value.getNetworkDelay());
			orchestratorOverhead[value.getTaskType()] += value.getOrchestratorOverhead();
			
			if(value.getNetworkDelay(NETWORK_DELAY_TYPES.WLAN_DELAY) != 0) {
				lanUsage[value.getTaskType()]++;
				lanDelay[value.getTaskType()] += value.getNetworkDelay(NETWORK_DELAY_TYPES.WLAN_DELAY);
			}
			if(value.getNetworkDelay(NETWORK_DELAY_TYPES.MAN_DELAY) != 0) {
				manUsage[value.getTaskType()]++;
				manDelay[value.getTaskType()] += value.getNetworkDelay(NETWORK_DELAY_TYPES.MAN_DELAY);
			}
			if(value.getNetworkDelay(NETWORK_DELAY_TYPES.WAN_DELAY) != 0) {
				wanUsage[value.getTaskType()]++;
				wanDelay[value.getTaskType()] += value.getNetworkDelay(NETWORK_DELAY_TYPES.WAN_DELAY);
			}
			if(value.getNetworkDelay(NETWORK_DELAY_TYPES.GSM_DELAY) != 0) {
				gsmUsage[value.getTaskType()]++;
				gsmDelay[value.getTaskType()] += value.getNetworkDelay(NETWORK_DELAY_TYPES.GSM_DELAY);
			}
			if(value.getNetworkDelay(NETWORK_DELAY_TYPES.UAV_DELAY) != 0) {
				uavUsage[value.getTaskType()]++;
				uavDelay[value.getTaskType()] += value.getNetworkDelay(NETWORK_DELAY_TYPES.UAV_DELAY);
			}
			
			if (value.getVmType() == SimSettings.VM_TYPES.CLOUD_VM.ordinal()) {
				serviceTimeOnCloud[value.getTaskType()] += value.getServiceTime();
				processingTimeOnCloud[value.getTaskType()] += (value.getServiceTime() - value.getNetworkDelay());
			}
			else if (value.getVmType() == SimSettings.VM_TYPES.MOBILE_VM.ordinal()) {
				serviceTimeOnMobile[value.getTaskType()] += value.getServiceTime();
				processingTimeOnMobile[value.getTaskType()] += value.getServiceTime();
			}
			else {
				serviceTimeOnEdge[value.getTaskType()] += value.getServiceTime();
				processingTimeOnEdge[value.getTaskType()] += (value.getServiceTime() - value.getNetworkDelay());
			}
		} else if (value.getStatus() == SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY) {
			failedTaskDueToVmCapacity[value.getTaskType()]++;
			
			if (value.getVmType() == SimSettings.VM_TYPES.CLOUD_VM.ordinal())
				failedTaskDueToVmCapacityOnCloud[value.getTaskType()]++;
			else if (value.getVmType() == SimSettings.VM_TYPES.MOBILE_VM.ordinal())
				failedTaskDueToVmCapacityOnMobile[value.getTaskType()]++;
			else
				failedTaskDueToVmCapacityOnEdge[value.getTaskType()]++;
		} else if (value.getStatus() == SimLogger.TASK_STATUS.REJECTED_DUE_TO_BANDWIDTH
				|| value.getStatus() == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH) {
			failedTaskDuetoBw[value.getTaskType()]++;
			if (value.getNetworkError() == NETWORK_ERRORS.LAN_ERROR)
				failedTaskDuetoLanBw[value.getTaskType()]++;
			else if (value.getNetworkError() == NETWORK_ERRORS.MAN_ERROR)
				failedTaskDuetoManBw[value.getTaskType()]++;
			else if (value.getNetworkError() == NETWORK_ERRORS.WAN_ERROR)
				failedTaskDuetoWanBw[value.getTaskType()]++;
			else if (value.getNetworkError() == NETWORK_ERRORS.GSM_ERROR)
				failedTaskDuetoGsmBw[value.getTaskType()]++;
		} else if (value.getStatus() == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_MOBILITY) {
			failedTaskDuetoMobility[value.getTaskType()]++;
		} else if (value.getStatus() == SimLogger.TASK_STATUS.REJECTED_DUE_TO_WLAN_COVERAGE) {
			refectedTaskDuetoWlanRange[value.getTaskType()]++;;
        } else if (value.getStatus() == SimLogger.TASK_STATUS.TIMED_OUT) {
			failedTaskDuetoTimeout[value.getTaskType()]++;
		}
		
		//if deep file logging is enabled, record every task result
		if (SimSettings.getInstance().getDeepFileLoggingEnabled()){
			try {
				if (value.getStatus() == SimLogger.TASK_STATUS.COMLETED)
					appendToFile(successBW, value.toString(taskId));
				else
					appendToFile(failBW, value.toString(taskId));
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
		}
	}
}

/**
 * Represents a snapshot of VM computational load across processing tiers at a specific time.
 * 
 * <p>This helper class stores utilization measurements for edge, cloud, and mobile
 * processing infrastructure, enabling time-series analysis of resource usage patterns
 * throughout the simulation.</p>
 */
class VmLoadLogItem {
	/** Simulation time when this measurement was taken */
	private double time;
	
	/** Computational load on edge infrastructure (0.0 to 1.0) */
	private double vmLoadOnEdge;
	
	/** Computational load on cloud infrastructure (0.0 to 1.0) */
	private double vmLoadOnCloud;
	
	/** Computational load on mobile devices (0.0 to 1.0) */
	private double vmLoadOnMobile;

	/**
	 * Creates a new VM load measurement record.
	 * 
	 * @param _time simulation timestamp
	 * @param _vmLoadOnEdge edge infrastructure utilization (0-1)
	 * @param _vmLoadOnCloud cloud infrastructure utilization (0-1)  
	 * @param _vmLoadOnMobile mobile device utilization (0-1)
	 */
	VmLoadLogItem(double _time, double _vmLoadOnEdge, double _vmLoadOnCloud, double _vmLoadOnMobile) {
		time = _time;
		vmLoadOnEdge = _vmLoadOnEdge;
		vmLoadOnCloud = _vmLoadOnCloud;
		vmLoadOnMobile = _vmLoadOnMobile;
	}

	/**
	 * Returns the edge infrastructure computational load.
	 * @return edge load percentage (0.0 to 1.0)
	 */
	public double getEdgeLoad() {
		return vmLoadOnEdge;
	}

	/**
	 * Returns the simulation time when this measurement was taken.
	 * @return simulation timestamp in seconds
	 */
	public double getTime() {
		return time;
	}

	/**
	 * Returns the cloud infrastructure computational load.
	 * @return cloud load percentage (0.0 to 1.0)
	 */
	public double getCloudLoad() {
		return vmLoadOnCloud;
	}
	
	/**
	 * Returns the mobile device computational load.
	 * @return mobile load percentage (0.0 to 1.0)
	 */
	public double getMobileLoad() {
		return vmLoadOnMobile;
	}
	
	public String toString() {
		return time + 
				SimSettings.DELIMITER + vmLoadOnEdge +
				SimSettings.DELIMITER + vmLoadOnCloud +
				SimSettings.DELIMITER + vmLoadOnMobile;
	}
}

/**
 * Represents network delay measurements for all access points at a specific time.
 * 
 * <p>This helper class stores upload and download delay measurements across
 * all access points in the simulation, enabling analysis of network performance
 * variations and bottlenecks over time.</p>
 */
class ApDelayLogItem {
	/** Simulation time when measurements were taken */
	private double time;
	
	/** Upload delays for each access point in seconds */
	private double apUploadDelays[];
	
	/** Download delays for each access point in seconds */
	double[] apDownloadDelays;
	
	/**
	 * Creates a new access point delay measurement record.
	 * 
	 * @param _time simulation timestamp
	 * @param _apUploadDelays array of upload delays for each access point
	 * @param _apDownloadDelays array of download delays for each access point
	 */
	ApDelayLogItem(double _time, double[] _apUploadDelays, double[] _apDownloadDelays){
		time = _time;
		apUploadDelays = _apUploadDelays;
		apDownloadDelays = _apDownloadDelays;
	}
	
	/**
	 * Returns formatted upload delay statistics for MATLAB analysis.
	 * 
	 * @return comma-delimited string with timestamp and all AP upload delays
	 */
	public String getUploadStat() {
		String result = Double.toString(time);
		for(int i=0; i<apUploadDelays.length; i++)
			result += SimSettings.DELIMITER + apUploadDelays[i];
		
		return result;
	}

	/**
	 * Returns formatted download delay statistics for MATLAB analysis.
	 * 
	 * @return comma-delimited string with timestamp and all AP download delays
	 */
	public String getDownloadStat() {
		String result = Double.toString(time);
		for(int i=0; i<apDownloadDelays.length; i++)
			result += SimSettings.DELIMITER + apDownloadDelays[i];
		
		return result;
	}
}

/**
 * Comprehensive logging record for individual task execution and performance metrics.
 * 
 * <p>LogItem maintains detailed information about a single task's lifecycle,
 * including timing measurements, resource assignments, network delays, costs,
 * and quality metrics. This class serves as the primary data structure for
 * deep logging and detailed performance analysis.</p>
 * 
 * <p>The class tracks complete task execution from creation through completion
 * or failure, enabling comprehensive post-simulation analysis of performance
 * bottlenecks, resource utilization patterns, and quality of service metrics.</p>
 */
class LogItem {
	/** Current status of the task (created, processing, completed, failed, etc.) */
	private SimLogger.TASK_STATUS status;
	
	/** Type of network error that occurred (if any) */
	private SimLogger.NETWORK_ERRORS networkError;
	
	/** ID of the mobile device that generated this task */
	private int deviceId;
	
	/** ID of the datacenter processing this task */
	private int datacenterId;
	
	/** ID of the host machine assigned to this task */
	private int hostId;
	
	/** ID of the virtual machine executing this task */
	private int vmId;
	
	/** Type of processing tier (cloud/edge/mobile) */
	private int vmType;
	
	/** Application type index of this task */
	private int taskType;

	/** ONAT: id of the stream request this item is a node of, or NO_STREAM for every other task */
	private static final int NO_STREAM = -1;
	private int streamId = NO_STREAM;
	
	/** Computational length requirement in million instructions */
	private int taskLenght;
	
	/** Input data size in bytes */
	private int taskInputType;
	private int taskOutputSize;
	private double taskStartTime;
	private double taskEndTime;
	private double lanUploadDelay;
	private double manUploadDelay;
	private double wanUploadDelay;
	private double gsmUploadDelay;
	private double lanDownloadDelay;
	private double manDownloadDelay;
	private double wanDownloadDelay;
	private double gsmDownloadDelay;
	// ONAT: DAG inter-layer relay delay (see NetworkModel.getInterLayerDelay()) - a
	// single accumulator (not split into upload/download legs like the others above),
	// since a DAG job can hand off between MULTIPLE layer pairs; see addUavDelay().
	private double uavDelay;
	private double bwCost;
	private double cpuCost;
	private double QoE;
	private double orchestratorOverhead;
	private boolean isInWarmUpPeriod;

	LogItem(int _deviceId, int _taskType, int _taskLenght, int _taskInputType, int _taskOutputSize) {
		deviceId = _deviceId;
		taskType = _taskType;
		taskLenght = _taskLenght;
		taskInputType = _taskInputType;
		taskOutputSize = _taskOutputSize;
		networkError = NETWORK_ERRORS.NONE;
		status = SimLogger.TASK_STATUS.CREATED;
		taskEndTime = 0;

		// ONAT: taskStarted() is the only other place this flag is set, and it is never
		// reached by a task rejected before its upload begins (e.g. out of every UAV's
		// service radius) - without this such a task would keep the default (false) and be
		// counted as a failure even when it was created during the warm-up period.
		isInWarmUpPeriod = CloudSim.clock() < SimSettings.getInstance().getWarmUpPeriod();
	}
	
	public void taskStarted(double time) {
		taskStartTime = time;
		status = SimLogger.TASK_STATUS.UPLOADING;
		
		if (time < SimSettings.getInstance().getWarmUpPeriod())
			isInWarmUpPeriod = true;
		else
			isInWarmUpPeriod = false;
	}
	
	public void setUploadDelay(double delay, NETWORK_DELAY_TYPES delayType) {
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			lanUploadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			manUploadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			wanUploadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			gsmUploadDelay = delay;
	}
	
	public void setDownloadDelay(double delay, NETWORK_DELAY_TYPES delayType) {
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			lanDownloadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			manDownloadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			wanDownloadDelay = delay;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			gsmDownloadDelay = delay;
	}

	/** Accumulates (rather than overwrites) since a DAG job can relay data between
	 * multiple consecutive layer pairs over its lifetime - see NetworkModel.getInterLayerDelay(). */
	public void addUavDelay(double delay) {
		uavDelay += delay;
	}

	public void setStreamId(int _streamId) {
		streamId = _streamId;
	}

	public int getStreamId() {
		return streamId;
	}

	/** True for the item of a stream node (see SimLogger.addStreamNodeLog()) */
	public boolean isStreamNode() {
		return streamId != NO_STREAM;
	}

	/** ONAT: this (stream request's) item's timeout fired while nodes were still running, see
	 * SimLogger.markStreamTimeoutPending(). */
	private boolean timeoutPending = false;

	public void setTimeoutPending() {
		timeoutPending = true;
	}

	public boolean isTimeoutPending() {
		return timeoutPending;
	}

	/** ONAT: adds a finished stream node's network delays to this (stream request's) item -
	 * accumulated, since a stream has many nodes. */
	public void addNetworkDelaysOf(LogItem node) {
		lanUploadDelay += node.lanUploadDelay;
		manUploadDelay += node.manUploadDelay;
		wanUploadDelay += node.wanUploadDelay;
		gsmUploadDelay += node.gsmUploadDelay;
		lanDownloadDelay += node.lanDownloadDelay;
		manDownloadDelay += node.manDownloadDelay;
		wanDownloadDelay += node.wanDownloadDelay;
		gsmDownloadDelay += node.gsmDownloadDelay;
		uavDelay += node.uavDelay;
	}

	/** ONAT: makes a failed stream node's failure this (stream request's) own outcome. The VM
	 * tier stays the stream's own, like a partitioned task's parent. */
	public void adoptFailureOf(LogItem node) {
		status = node.status;
		networkError = node.networkError;
		taskEndTime = node.taskEndTime;
	}
	
	public void taskAssigned(int _datacenterId, int _hostId, int _vmId, int _vmType) {
		status = SimLogger.TASK_STATUS.PROCESSING;
		datacenterId = _datacenterId;
		hostId = _hostId;
		vmId = _vmId;
		vmType = _vmType;
	}

	public void taskExecuted() {
		status = SimLogger.TASK_STATUS.DOWNLOADING;
	}

	public void taskEnded(double time) {
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.COMLETED;
	}

	public void taskRejectedDueToVMCapacity(double time, int _vmType) {
		vmType = _vmType;
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY;
	}
	
	public void taskRejectedDueToWlanCoverage(double time, int _vmType) {
		vmType = _vmType;
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.REJECTED_DUE_TO_WLAN_COVERAGE;
	}

	public void taskRejectedDueToBandwidth(double time, int _vmType, NETWORK_DELAY_TYPES delayType) {
		vmType = _vmType;
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.REJECTED_DUE_TO_BANDWIDTH;
		
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			networkError = NETWORK_ERRORS.LAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			networkError = NETWORK_ERRORS.MAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			networkError = NETWORK_ERRORS.WAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			networkError = NETWORK_ERRORS.GSM_ERROR;
	}

	public void taskFailedDueToBandwidth(double time, NETWORK_DELAY_TYPES delayType) {
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH;
		
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			networkError = NETWORK_ERRORS.LAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			networkError = NETWORK_ERRORS.MAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			networkError = NETWORK_ERRORS.WAN_ERROR;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			networkError = NETWORK_ERRORS.GSM_ERROR;
	}

	public void taskFailedDueToMobility(double time) {
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_MOBILITY;
	}

	public void taskTimedOut(double time, int _vmType) {
		vmType = _vmType;
		taskEndTime = time;
		status = SimLogger.TASK_STATUS.TIMED_OUT;
	}

	public void setCost(double _bwCost, double _cpuCos) {
		bwCost = _bwCost;
		cpuCost = _cpuCos;
	}
	
	public void setQoE(double qoe){
		QoE = qoe;
	}
	
	public void setOrchestratorOverhead(double overhead){
		orchestratorOverhead = overhead;
	}

	public boolean isInWarmUpPeriod() {
		return isInWarmUpPeriod;
	}

	public double getCost() {
		return bwCost + cpuCost;
	}

	public double getQoE() {
		return QoE;
	}

	public double getOrchestratorOverhead() {
		return orchestratorOverhead;
	}
	
	public double getNetworkUploadDelay(NETWORK_DELAY_TYPES delayType) {
		double result = 0;
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			result = lanUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			result = manUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			result = wanUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			result = gsmUploadDelay;
		
		return result;
	}

	public double getNetworkDownloadDelay(NETWORK_DELAY_TYPES delayType) {
		double result = 0;
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			result = lanDownloadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			result = manDownloadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			result = wanDownloadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			result = gsmDownloadDelay;
		
		return result;
	}
	
	public double getNetworkDelay(NETWORK_DELAY_TYPES delayType){
		double result = 0;
		if(delayType == NETWORK_DELAY_TYPES.WLAN_DELAY)
			result = lanDownloadDelay + lanUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.MAN_DELAY)
			result = manDownloadDelay + manUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.WAN_DELAY)
			result = wanDownloadDelay + wanUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.GSM_DELAY)
			result = gsmDownloadDelay + gsmUploadDelay;
		else if(delayType == NETWORK_DELAY_TYPES.UAV_DELAY)
			result = uavDelay;
		
		return result;
	}
	
	public double getNetworkDelay(){
		return  lanUploadDelay +
				manUploadDelay +
				wanUploadDelay +
				gsmUploadDelay +
				lanDownloadDelay +
				manDownloadDelay +
				wanDownloadDelay +
				gsmDownloadDelay +
				uavDelay;
	}
	
	public double getServiceTime() {
		return taskEndTime - taskStartTime;
	}

	public SimLogger.TASK_STATUS getStatus() {
		return status;
	}

	public SimLogger.NETWORK_ERRORS getNetworkError() {
		return networkError;
	}
	
	public int getVmType() {
		return vmType;
	}

	public int getTaskType() {
		return taskType;
	}

	public String toString(int taskId) {
		String result = taskId + SimSettings.DELIMITER + deviceId + SimSettings.DELIMITER + datacenterId + SimSettings.DELIMITER + hostId
				+ SimSettings.DELIMITER + vmId + SimSettings.DELIMITER + vmType + SimSettings.DELIMITER + taskType
				+ SimSettings.DELIMITER + taskLenght + SimSettings.DELIMITER + taskInputType + SimSettings.DELIMITER
				+ taskOutputSize + SimSettings.DELIMITER + taskStartTime + SimSettings.DELIMITER + taskEndTime
				+ SimSettings.DELIMITER;

		if (status == SimLogger.TASK_STATUS.COMLETED){
			result += getNetworkDelay() + SimSettings.DELIMITER;
			result += getNetworkDelay(NETWORK_DELAY_TYPES.WLAN_DELAY) + SimSettings.DELIMITER;
			result += getNetworkDelay(NETWORK_DELAY_TYPES.MAN_DELAY) + SimSettings.DELIMITER;
			result += getNetworkDelay(NETWORK_DELAY_TYPES.WAN_DELAY) + SimSettings.DELIMITER;
			result += getNetworkDelay(NETWORK_DELAY_TYPES.GSM_DELAY);
		}
		else if (status == SimLogger.TASK_STATUS.REJECTED_DUE_TO_VM_CAPACITY)
			result += "1"; // failure reason 1
		else if (status == SimLogger.TASK_STATUS.REJECTED_DUE_TO_BANDWIDTH)
			result += "2"; // failure reason 2
		else if (status == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_BANDWIDTH)
			result += "3"; // failure reason 3
		else if (status == SimLogger.TASK_STATUS.UNFINISHED_DUE_TO_MOBILITY)
			result += "4"; // failure reason 4
        else if (status == SimLogger.TASK_STATUS.REJECTED_DUE_TO_WLAN_COVERAGE)
            result += "5"; // failure reason 5
		else if (status == SimLogger.TASK_STATUS.TIMED_OUT)
			result += "6"; // failure reason 6
		else
			result += "0"; // default failure reason
		return result;
	}
}
