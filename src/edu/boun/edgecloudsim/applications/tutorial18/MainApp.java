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
// 2) Load simulation settings (config, devices, applications, streams)
// 3) For each iteration
//    3.1) For each mobile device population size
//          3.1.1) For each simulation scenario
//                  3.1.1.1) For each orchestrator policy
//                             3.1.1.1.1) For each UAV mobility option
//                              3.1.1.1.1.1) For each service placement policy
//                              - Initialize CloudSim
//                              - Build scenario factory
//                              - Create SimManager
//                              - Run simulation
//                              - Log results and duration
// 4) Print total elapsed time

// ONAT: tutorial18 adds STREAMS (streams.xml) to tutorial17's service-placement scenario: a
// stream is a DAG (dag_layers, e.g. 1,2,1) whose nodes are applications - i.e. services -
// instead of sub-tasks. A mobile device is assigned ONE request type, either a plain
// application (applications.xml) or a stream, drawn together from their usage_percentage
// values; a stream has its own active/idle periods, arrival rate, usage_percentage and
// timeout, and the applications it runs ignore theirs while they are nodes of it. A stream's
// nodes run layer by layer (the nodes of a layer in parallel), each one offloaded to an in-range
// UAV hosting its application's service, and the stream fails as a whole the moment a node
// finds no such UAV ("dropped"). The same application can also be requested on its own, and an
// application may itself be a DAG of sub-tasks (dag_layers in applications.xml) - as a
// stream node that DAG runs exactly as it would standalone.
// As in tutorial17, which services each UAV hosts is decided by a swept service placement
// policy (service_placement_policies - SINGLE, ALL), UAVs are repositioned by a CENTRALIZED
// mobility policy (uav_mobility_options) and users move with a plain random walk.

package edu.boun.edgecloudsim.applications.tutorial18;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.core.ScenarioFactory;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.uav.ServicePlacementPolicy;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

public class MainApp {

    public static final int EXPECTED_NUM_OF_ARGS = 6;
    public static final String APPLICATION_FOLDER = "tutorial18";

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
        String streamsFile = null;

        // Parse command line arguments:
        // Expected: 0:config 1:edge_devices 2:applications 3:streams 4:output_folder 5:iteration
        // If not provided, fall back to defaults for quick local tests.
        if (args.length == EXPECTED_NUM_OF_ARGS){
            configFile = args[0];
            edgeDevicesFile = args[1];
            applicationsFile = args[2];
            streamsFile = args[3];
            outputFolder = args[4];
            iterationStart = Integer.parseInt(args[5]);
            iterationEnd = iterationStart;
        }
        else{
            // Inform user that defaults are used (common in IDE debugging)
            SimLogger.printLine("Simulation setting file, output folder and iteration number are not provided! Using default ones...");
            configFile = "scripts/" + APPLICATION_FOLDER + "/config/default_config.properties";
            applicationsFile = "scripts/" + APPLICATION_FOLDER + "/config/applications.xml";
            streamsFile = "scripts/" + APPLICATION_FOLDER + "/config/streams.xml";
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
        if(!SS.initialize(configFile, edgeDevicesFile, applicationsFile, streamsFile)){
            SimLogger.printLine("cannot initialize simulation settings!");
            System.exit(0);
        }

        // Fail before any scenario runs rather than part way through the sweep
        for (String servicePlacementPolicy : SS.getServicePlacementPolicies()) {
            if (!ServicePlacementPolicy.isSupported(servicePlacementPolicy)) {
                SimLogger.printLine("unknown service placement policy '" + servicePlacementPolicy
                        + "' (supported: " + ServicePlacementPolicy.SUPPORTED_POLICIES + ")");
                System.exit(0);
            }
        }

        // Prepare date formatter for human-readable logging timestamps
        DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        Date SimulationStartDate = Calendar.getInstance().getTime();
        String now = df.format(SimulationStartDate);
        SimLogger.printLine("Simulation started at " + now);
        SimLogger.printLine("----------------------------------------------------------------------");

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
                        // Evaluate each centralized UAV mobility policy under the active scenario
                        for (int u=0; u<SS.getUAVMobilityOptions().length; u++) {
                            // Evaluate each service placement policy under the active UAV mobility policy
                            for (int s=0; s<SS.getServicePlacementPolicies().length; s++) {
                                // Extract current test dimensions
                                String simScenario = SS.getSimulationScenarios()[k];
                                String orchestratorPolicy = SS.getOrchestratorPolicies()[i];
                                String uavMobilityOption = SS.getUAVMobilityOptions()[u];
                                String servicePlacementPolicy = SS.getServicePlacementPolicies()[s];
                                Date ScenarioStartDate = Calendar.getInstance().getTime();
                                now = df.format(ScenarioStartDate);

                                // Log scenario header summarizing experimental factors
                                SimLogger.printLine("Scenario started at " + now);
                                SimLogger.printLine("Scenario: " + simScenario + " - Policy: " + orchestratorPolicy + " - UAV Mobility: " + uavMobilityOption + " - Service Placement: " + servicePlacementPolicy + " - #iteration: " + iterationNumber);
                                SimLogger.printLine("Duration: " + SS.getSimulationTime()/60 + " min (warm up period: "+ SS.getWarmUpPeriod()/60 +" min) - #users: " + j);
                                // Warm-up period: tasks started during it are excluded from the result statistics.
                                SimLogger.getInstance().simStarted(outputFolder,"SIMRESULT_" + simScenario + "_"  + orchestratorPolicy + "_" + uavMobilityOption + "_" + servicePlacementPolicy + "_" + j + "DEVICES");

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
                                    ScenarioFactory sampleFactory = new SampleScenarioFactory(j, SS.getSimulationTime(), orchestratorPolicy, simScenario, uavMobilityOption, servicePlacementPolicy);

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
                            }
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
