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
//                  3.1.1.2) For each VM utilization policy
//                             - Initialize CloudSim
//                             - Build scenario factory
//                             - Create SimManager
//                             - Run simulation
//                             - Log results and duration
// 4) Print total elapsed time

// ONAT: tutorial16 studies how a single UAV's task queue behaves as a function of the
// application's vm_utilization_on_edge. Users and UAV mobility are the same as tutorial15
// (users converge on one meeting zone at the map center, the UAV follows the K-Means
// centroid of the users), but there is exactly ONE UAV with ONE single-core VM and ONE
// non-partitionable application, so every task contends for the same queue. The utilization
// values to compare are written as policy names in vm_utilization_policies
// (default_config.properties), e.g. "UTILIZATION_10,UTILIZATION_30,UTILIZATION_50" - each
// run overwrites the application's vm_utilization_on_edge with that percentage (see
// UTILIZATION_POLICY_PATTERN and SimSettings.setEdgeVmUtilization()), same "encode a
// parameter in the policy name" convention as tutorial14's PARTITION_<N>, so all of them
// end up as separate lines in the same plot. applications.xml's own value is only a
// placeholder.

package edu.boun.edgecloudsim.applications.tutorial16;

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
    public static final String APPLICATION_FOLDER = "tutorial16";

    // ONAT: a "UTILIZATION_<percent>" entry in vm_utilization_policies sets every
    // application's vm_utilization_on_edge to <percent> for that run (decimals allowed,
    // e.g. UTILIZATION_2.5) - see SimSettings.setEdgeVmUtilization().
    private static final Pattern UTILIZATION_POLICY_PATTERN = Pattern.compile("UTILIZATION_(\\d+(?:\\.\\d+)?)");

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

        // ONAT: the utilization policies to compare, validated up front so a typo fails
        // immediately instead of after the first few scenarios have already run.
        String[] utilizationPolicies = SS.getVmUtilizationPolicies();
        if (utilizationPolicies.length == 0) {
            SimLogger.printLine("vm_utilization_policies is not set in " + configFile + "! Terminating simulation...");
            System.exit(1);
        }
        for (String policy : utilizationPolicies) {
            if (!UTILIZATION_POLICY_PATTERN.matcher(policy.trim().toUpperCase()).matches()) {
                SimLogger.printLine("Unknown vm_utilization_policies entry '" + policy + "' (expected UTILIZATION_<percent>, e.g. UTILIZATION_30)! Terminating simulation...");
                System.exit(1);
            }
        }

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
                        // Evaluate each VM utilization policy under the active scenario
                        for(int p=0; p<utilizationPolicies.length; p++)
                        {
                            // Extract current test dimensions
                            String simScenario = SS.getSimulationScenarios()[k];
                            String orchestratorPolicy = SS.getOrchestratorPolicies()[i];
                            String utilizationPolicy = utilizationPolicies[p].trim().toUpperCase();

                            // ONAT: every run overwrites the application's declared edge VM CPU share
                            // (what the UAV orchestrator's admission check works with) with the
                            // policy's percentage - pattern validity was checked above.
                            Matcher utilizationMatcher = UTILIZATION_POLICY_PATTERN.matcher(utilizationPolicy);
                            utilizationMatcher.matches();
                            double edgeVmUtilization = Double.parseDouble(utilizationMatcher.group(1));
                            SS.setEdgeVmUtilization(edgeVmUtilization);

                            Date ScenarioStartDate = Calendar.getInstance().getTime();
                            now = df.format(ScenarioStartDate);

                            // Log scenario header summarizing experimental factors
                            SimLogger.printLine("Scenario started at " + now);
                            SimLogger.printLine("Scenario: " + simScenario + " - Policy: " + orchestratorPolicy + " - VM Utilization Policy: " + utilizationPolicy + " - UAV Mobility: " + uavMobilityOption + " - #iteration: " + iterationNumber);
                            SimLogger.printLine("Duration: " + SS.getSimulationTime()/60 + " min (warm up period: "+ SS.getWarmUpPeriod()/60 +" min) - #users: " + j);
                            // Warm-up period: tasks started during it are excluded from the result statistics.
                            SimLogger.getInstance().simStarted(outputFolder,"SIMRESULT_" + simScenario + "_"  + orchestratorPolicy + "_" + utilizationPolicy + "_" + j + "DEVICES");

                            try
                            {
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
                                ScenarioFactory sampleFactory = new SampleScenarioFactory(j, SS.getSimulationTime(), orchestratorPolicy, simScenario, uavMobilityOption);

                                // SimManager wires all components, schedules events, and aggregates stats.
                                SimManager manager = new SimManager(sampleFactory, j, simScenario, orchestratorPolicy);
                                // Kick off discrete-event simulation (blocking until completion)
                                manager.startSimulation();
                            }
                            catch (Exception e)
                            {
                                // Crash-fast strategy prevents partial mixed-result datasets
                                // Any uncaught exception here invalidates experimental run
                                SimLogger.printLine("The simulation has been terminated due to an unexpected error");
                                e.printStackTrace();
                                System.exit(0);
                            }

                            // Log per-scenario duration (excludes previous scenarios)
                            Date ScenarioEndDate = Calendar.getInstance().getTime();
                            now = df.format(ScenarioEndDate);
                            SimLogger.printLine("Scenario finished at " + now +  ". It took " + SimUtils.getTimeDifference(ScenarioStartDate,ScenarioEndDate));
                            SimLogger.printLine("----------------------------------------------------------------------");
                        }//End of utilization policies loop
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
