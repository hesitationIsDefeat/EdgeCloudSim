package edu.boun.edgecloudsim.applications.tutorial6;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;

import edu.boun.edgecloudsim.mobility.MobilityModel;
import org.apache.commons.math3.distribution.ExponentialDistribution;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * Nomadic mobility model implementation for EdgeCloudSim.
 *
 * This mobility model implements a discrete location-based movement pattern where
 * mobile devices move between fixed locations (datacenters/hotspots) rather than
 * following continuous trajectories. Devices spend exponentially distributed time
 * periods at each location before moving to a different location.
 *
 * Key characteristics:
 * - Discrete location transitions rather than continuous movement
 * - Exponential distribution for residence time at each location
 * - Location attractiveness modeling based on place type characteristics
 * - Random location selection with avoidance of immediate return to same location
 * - Timeline-based location tracking using TreeMap for efficient queries
 *
 * This model is particularly suitable for scenarios involving:
 * - Users moving between Wi-Fi hotspots, offices, or public spaces
 * - Nomadic workers changing locations throughout the day
 * - Vehicular scenarios with discrete stops (stations, parking lots, etc.)
 *
 * The model uses exponential distributions parameterized by location attractiveness
 * to determine residence times, creating realistic movement patterns with varied
 * dwell times at different location types.
 */
public class SampleMobilityModel extends MobilityModel {
    private List<TreeMap<Double, Location>> treeMapArray;    // Timeline of location changes for each device
    private final double SPEED = 2.0;

    /**
     * Constructs a NomadicMobility model with specified parameters.
     *
     * @param _numberOfMobileDevices Total number of mobile devices to model
     * @param _simulationTime Duration of the simulation in seconds
     */
    public SampleMobilityModel(int _numberOfMobileDevices, double _simulationTime) {
        super(_numberOfMobileDevices, _simulationTime);
    }

    /**
     * Initializes the nomadic mobility model by generating movement timelines.
     * Creates exponential distributions for each location based on attractiveness,
     * assigns initial positions to devices, and generates complete movement
     * trajectories for the entire simulation period.
     */
    @Override
    public void initialize() {
        treeMapArray = new ArrayList<>();

        // Create exponential distribution generators for residence time at each location
        ExponentialDistribution[] expRngList = new ExponentialDistribution[SimSettings.getInstance().getNumOfEdgeDatacenters()];

        // Create exponential RNG for each datacenter location based on attractiveness
        Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
        NodeList datacenterList = doc.getElementsByTagName("datacenter");
        for (int i = 0; i < datacenterList.getLength(); i++) {
            Node datacenterNode = datacenterList.item(i);
            Element datacenterElement = (Element) datacenterNode;
            Element location = (Element)datacenterElement.getElementsByTagName("location").item(0);
            String attractiveness = location.getElementsByTagName("attractiveness").item(0).getTextContent();
            int placeTypeIndex = Integer.parseInt(attractiveness);

            // Create exponential distribution based on location attractiveness (mean residence time)
            expRngList[i] = new ExponentialDistribution(SimSettings.getInstance().getMobilityLookUpTable()[placeTypeIndex]);
        }

        // Initialize timeline maps and assign initial positions to mobile devices
        for(int i=0; i<numberOfMobileDevices; i++) {
            treeMapArray.add(i, new TreeMap<Double, Location>());

            // ONAT: Randomly assign initial values to mobile devices
            int placeTypeIndex = 0;
            int wlan_id = 0;
            int x_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getWesternBound(), (int) SimSettings.getInstance().getEasternBound());
            int y_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getSouthernBound(), (int) SimSettings.getInstance().getNorthernBound());

            // Set initial location at simulation start time (allows for initialization period)
            treeMapArray.get(i).put(SimSettings.CLIENT_ACTIVITY_START_TIME, new Location(placeTypeIndex, wlan_id, x_pos, y_pos));
        }

        // Generate complete movement trajectory for each device throughout simulation
        for(int i=0; i<numberOfMobileDevices; i++) {
            TreeMap<Double, Location> treeMap = treeMapArray.get(i);

            // Continue generating location changes until simulation end time
            while(treeMap.lastKey() < SimSettings.getInstance().getSimulationTime()) {
                Location lastLoc = treeMap.lastEntry().getValue();
                int currentX = lastLoc.getXPos();
                int currentY = lastLoc.getYPos();

                double travelTime = 3.0;
                double angle = Math.random() * 2 * Math.PI;
                double distance = SPEED * travelTime;


                int deltaX = (int) (distance * Math.cos(angle));
                int deltaY = (int) (distance * Math.sin(angle));

                int newX = currentX + deltaX;
                int newY = currentY + deltaY;

                if (newX < SimSettings.getInstance().getWesternBound()) newX = (int) SimSettings.getInstance().getWesternBound();
                if (newX > SimSettings.getInstance().getEasternBound()) newX = (int) SimSettings.getInstance().getEasternBound();
                if (newY < SimSettings.getInstance().getNorthernBound()) newY = (int) SimSettings.getInstance().getNorthernBound();
                if (newY > SimSettings.getInstance().getSouthernBound()) newY = (int) SimSettings.getInstance().getSouthernBound();

                treeMap.put(treeMap.lastKey() + travelTime,
                        new Location(0, 0, newX, newY));
            }
        }

    }

    /**
     * Returns the current location of a mobile device at the specified time.
     * Uses the pre-generated timeline to find the appropriate location entry
     * that was active at the requested time.
     *
     * @param deviceId Unique identifier of the mobile device
     * @param time Simulation time when location is requested (in seconds)
     * @return Location object containing the device's position and serving edge server
     */
    @Override
    public Location getLocation(int deviceId, double time) {
        TreeMap<Double, Location> treeMap = treeMapArray.get(deviceId);

        // Find the latest location entry before or at the requested time
        Map.Entry<Double, Location> e = treeMap.floorEntry(time);

        if(e == null){
            SimLogger.printLine("impossible is occurred! no location is found for the device '" + deviceId + "' at " + time);
            System.exit(1);
        }

        return e.getValue();
    }

}

