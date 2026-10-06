/*
 * Title:        EdgeCloudSim - Main Application
 *
 * Description:  Main application for this scenario
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

// High-level execution phases:
// 1) Parse / default command line args
// 2) Load simulation settings (config, devices, applications)
// 3) For each iteration
//    3.1) For each mobile device population size
//          3.1.1) For each simulation scenario
//                  3.1.1.1) For each orchestrator policy
//                  3.1.1.2) For each task partition policy
//                             - Initialize CloudSim
//                             - Build scenario factory
//                             - Create SimManager
//                             - Run simulation
//                             - Log results and duration
// 4) Print total elapsed time

// ONAT: tutorial14 has a single normal-user population (no SAR members at all - see
// default_config.properties, which never sets sar_member_percentage, so
// SS.computeNumOfSarMembers() always returns 0) running a single application,
// MustPartitionTask, whose vm_utilization_on_edge (120%) exceeds what a single edge VM
// can ever satisfy unpartitioned. UAV mobility is FIXED to a single KMEANS variant
// (bare PRIORITY_KMEANS, see uav_mobility_options - only one entry, taken once below,
// not swept); instead this tutorial sweeps task_partition_policies
// (PARTITION_1/PARTITION_2/PARTITION_4), each forcing MustPartitionTask to split into
// exactly that many children regardless of applications.xml's own partition_count -
// see the PARTITION_POLICY_PATTERN parsing below and SimSettings.setPartitionCountOverride().

package edu.boun.edgecloudsim.applications.tutorial14;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.core.ScenarioFactory;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

public class MainApp {

    public static final int EXPECTED_NUM_OF_ARGS = 5;
    public static final String APPLICATION_FOLDER = "tutorial14";

    // ONAT: each task_partition_policies entry must look like "PARTITION_<N>" - N is
    // the exact child count MustPartitionTask is forced to split into for that run
    // (see SimSettings.setPartitionCountOverride()), overriding applications.xml's own
    // partition_count regardless of its value.
    private static final Pattern PARTITION_POLICY_PATTERN = Pattern.compile("PARTITION_(\\d+)");

    /**
     * Creates main() to run this example
     */
    public static void main(String[] args) {
        //disable console output of CloudSim library
        Log.disable();

        //enable console output for EdgeCloudSim (centralized logging utility)
        SimLogger.enablePrintLog();

        // Declare iteration boundaries (can become a range if user wants batch runs)
        int iterationStart;
        int iterationEnd;
        // Paths provided externally or defaulted for IDE usage
        String configFile = null;
        String outputFolder = null;
        String edgeDevicesFile = null;
        String applicationsFile = null;

        // Parse command line arguments:
        // Expected: 0:config 1:edge_devices 2:applications 3:output_folder 4:iteration
        // If not provided, fall back to defaults for quick local tests.
        if (args.length == EXPECTED_NUM_OF_ARGS){
            configFile = args[0];
            edgeDevicesFile = args[1];
            applicationsFile = args[2];
            outputFolder = args[3];
            iterationStart = Integer.parseInt(args[4]);
            iterationEnd = iterationStart;
        }
        else{
            // Inform user that defaults are used (common in IDE debugging)
            SimLogger.printLine("Simulation setting file, output folder and iteration number are not provided! Using default ones...");
            configFile = "scripts/" + APPLICATION_FOLDER + "/config/default_config.properties";
            applicationsFile = "scripts/" + APPLICATION_FOLDER + "/config/applications.xml";
            edgeDevicesFile = "scripts/" + APPLICATION_FOLDER + "/config/edge_devices.xml";

            // !! IMPORTANT NOTICE !!
            // For those who are using IDE (eclipse etc) can modify
            // -> iteration value to run a specific iteration
            // -> iteration Start/End value to run multiple iterations at a time
            //    in this case start shall be less than or equal to end value
            iterationStart = 1;
            iterationEnd = 10;
        }

        // Load simulation settings (returns false if any config inconsistency occurs)
        // Abort early to avoid partial / misleading runs.
        SimSettings SS = SimSettings.getInstance();
        if(!SS.initialize(configFile, edgeDevicesFile, applicationsFile)){
            SimLogger.printLine("cannot initialize simulation settings!");
            System.exit(0);
        }

        // Prepare date formatter for human-readable logging timestamps
        DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        Date SimulationStartDate = Calendar.getInstance().getTime();
        String now = df.format(SimulationStartDate);
        SimLogger.printLine("Simulation started at " + now);
        SimLogger.printLine("----------------------------------------------------------------------");

        // ONAT: UAV mobility is fixed for this tutorial (single configured entry,
        // bare PRIORITY_KMEANS) - taken once here rather than swept in the loop below.
        String uavMobilityOption = SS.getUAVMobilityOptions()[0];

        // For each iteration specified by the user or default range
        for(int iterationNumber=iterationStart; iterationNumber<=iterationEnd; iterationNumber++) {
            // Derive output folder automatically when not explicitly provided
            if (args.length != EXPECTED_NUM_OF_ARGS)
                outputFolder = "sim_results/" + APPLICATION_FOLDER + "/ite" + iterationNumber;

            if(SS.getFileLoggingEnabled()){
                // File logging enabled -> ensure clean slate for this iteration
                // (avoids mixing results from different runs)
                SimLogger.enableFileLog();
                File dir = new File(outputFolder);
                if(dir.exists() && dir.isDirectory())
                {
                    SimLogger.printLine("Output folder is available; cleaning '" + outputFolder + "'");
                    for (File f: dir.listFiles())
                    {
                        // Only delete plain files (keep potential sub-structure future-proof)
                        if (f.exists() && f.isFile())
                        {
                            if(!f.delete())
                            {
                                SimLogger.printLine("file cannot be deleted: " + f.getAbsolutePath());
                                System.exit(1);
                            }
                        }
                    }
                }
                else {
                    // If folder missing, create it (mkdirs covers nested structure)
                    SimLogger.printLine("Output folder is not available; deleting '" + outputFolder + "'");
                    dir.mkdirs();
                }
            }

            // Device population sweep (scalability analysis)
            for(int j=SS.getMinNumOfMobileDev(); j<=SS.getMaxNumOfMobileDev(); j+=SS.getMobileDevCounterSize())
            {
                // Iterate through each configured simulation scenario variant
                for(int k=0; k<SS.getSimulationScenarios().length; k++)
                {
                    // Evaluate each orchestrator policy under the active scenario
                    for(int i=0; i<SS.getOrchestratorPolicies().length; i++)
                    {
                        // Evaluate each task partition policy under the active scenario
                        for (int p=0; p<SS.getTaskPartitionPolicies().length; p++) {
                            // Extract current test dimensions
                            String simScenario = SS.getSimulationScenarios()[k];
                            String orchestratorPolicy = SS.getOrchestratorPolicies()[i];
                            String taskPartitionPolicy = SS.getTaskPartitionPolicies()[p].trim().toUpperCase();
                            SS.setTaskPartitionPolicy(taskPartitionPolicy);

                            // ONAT: the policy name itself encodes the partition count
                            // (e.g. "PARTITION_4" -> 4 children) - override it regardless
                            // of applications.xml's own partition_count for MustPartitionTask.
                            Matcher partitionPolicyMatcher = PARTITION_POLICY_PATTERN.matcher(taskPartitionPolicy);
                            if (!partitionPolicyMatcher.matches()) {
                                SimLogger.printLine("Invalid task partition policy '" + taskPartitionPolicy + "' - expected PARTITION_<N>. Terminating simulation...");
                                System.exit(1);
                            }
                            int partitionCount = Integer.parseInt(partitionPolicyMatcher.group(1));
                            SS.setPartitionCountOverride(partitionCount);

                            Date ScenarioStartDate = Calendar.getInstance().getTime();
                            now = df.format(ScenarioStartDate);

                            // ONAT: this tutorial has no SAR population at all (see
                            // default_config.properties) - always 0, kept for structural
                            // parity with sibling tutorials' SampleScenarioFactory signature.
                            int numOfSarMembers = SS.computeNumOfSarMembers(j);
                            int totalNumOfMobileDevices = j + numOfSarMembers;

                            // Log scenario header summarizing experimental factors
                            SimLogger.printLine("Scenario started at " + now);
                            SimLogger.printLine("Scenario: " + simScenario + " - Policy: " + orchestratorPolicy + " - Task Partition Policy: " + taskPartitionPolicy + " - UAV Mobility: " + uavMobilityOption + " - #iteration: " + iterationNumber);
                            SimLogger.printLine("Duration: " + SS.getSimulationTime()/60 + " min (warm up period: "+ SS.getWarmUpPeriod()/60 +" min) - #normal users: " + j + " - #SAR members: " + numOfSarMembers);
                            // Warm-up period: metrics during first interval often excluded from statistical analysis (transient phase).
                            // Consider filtering in post-processing if comparing steady-state KPIs.
                            SimLogger.getInstance().simStarted(outputFolder,"SIMRESULT_" + simScenario + "_"  + orchestratorPolicy + "_" + taskPartitionPolicy + "_" + j + "DEVICES");

                            try
                            {
                                // For multi-seed experimentation, wrap this block and vary RNG seeds between iterations.
                                // Minimal event granularity (0.01) chosen to avoid zero-time collisions
                                // CloudSim core init:
                                // num_user: logical users generating events (broker etc.)
                                // calendar: base time reference
                                // trace_flag: enable event tracing (disabled for performance)
                                // last param: minimal time between events (precision)
                                int num_user = 2;   // number of grid users
                                Calendar calendar = Calendar.getInstance();
                                boolean trace_flag = false;  // mean trace events

                                // Initialize the CloudSim library
                                CloudSim.init(num_user, calendar, trace_flag, 0.01);

                                // ScenarioFactory encapsulates workload, mobility, network, placement etc.
                                ScenarioFactory sampleFactory = new SampleScenarioFactory(j, numOfSarMembers, SS.getSimulationTime(), orchestratorPolicy, simScenario, uavMobilityOption);

                                // SimManager wires all components, schedules events, and aggregates stats.
                                // Pass the combined device count so UAV tracking and task submission
                                // (which iterate ids 0..numOfMobileDevice-1) also cover SAR members.
                                SimManager manager = new SimManager(sampleFactory, totalNumOfMobileDevices, simScenario, orchestratorPolicy);
                                // Kick off discrete-event simulation (blocking until completion)
                                manager.startSimulation();
                            }
                            catch (Exception e)
                            {
                                // Crash-fast strategy prevents partial mixed-result datasets
                                // Any uncaught exception here invalidates experimental run
                                // Fail fast to avoid corrupt aggregated datasets
                                SimLogger.printLine("The simulation has been terminated due to an unexpected error");
                                e.printStackTrace();
                                System.exit(0);
                            }

                            // Log per-scenario duration (excludes previous scenarios)
                            Date ScenarioEndDate = Calendar.getInstance().getTime();
                            now = df.format(ScenarioEndDate);
                            SimLogger.printLine("Scenario finished at " + now +  ". It took " + SimUtils.getTimeDifference(ScenarioStartDate,ScenarioEndDate));
                            SimLogger.printLine("----------------------------------------------------------------------");
                        }
                    }//End of orchestrators loop
                }//End of scenarios loop
            }//End of mobile devices loop
        }//End of iteration loop

        // Final summary for the entire multi-iteration batch
        Date SimulationEndDate = Calendar.getInstance().getTime();
        now = df.format(SimulationEndDate);
        SimLogger.printLine("Simulation finished at " + now +  ". It took " + SimUtils.getTimeDifference(SimulationStartDate,SimulationEndDate));
    }
}
