package edu.boun.edgecloudsim.applications.tutorial7;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 */
public class SampleMobilityModel extends MobilityModel {
    private List<TreeMap<Double, Location>> treeMapArray;
    private final double SPEED = 2.0;

    public SampleMobilityModel(int _numberOfMobileDevices, double _simulationTime) {
        super(_numberOfMobileDevices, _simulationTime);
    }

    @Override
    public void initialize() {
        treeMapArray = new ArrayList<>();

        ExponentialDistribution[] expRngList = new ExponentialDistribution[SimSettings.getInstance().getNumOfEdgeDatacenters()];

        Document doc = SimSettings.getInstance().getEdgeDevicesDocument();
        NodeList datacenterList = doc.getElementsByTagName("datacenter");
        for (int i = 0; i < datacenterList.getLength(); i++) {
            Node datacenterNode = datacenterList.item(i);
            Element datacenterElement = (Element) datacenterNode;
            Element location = (Element)datacenterElement.getElementsByTagName("location").item(0);
            String attractiveness = location.getElementsByTagName("attractiveness").item(0).getTextContent();
            int placeTypeIndex = Integer.parseInt(attractiveness);
            expRngList[i] = new ExponentialDistribution(SimSettings.getInstance().getMobilityLookUpTable()[placeTypeIndex]);
        }

        for(int i=0; i<numberOfMobileDevices; i++) {
            treeMapArray.add(i, new TreeMap<Double, Location>());

            int placeTypeIndex = 0;
            int wlan_id = 0;
            int x_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getWesternBound(), (int) SimSettings.getInstance().getEasternBound());
            int y_pos = SimUtils.getRandomNumber((int) SimSettings.getInstance().getSouthernBound(), (int) SimSettings.getInstance().getNorthernBound());

            treeMapArray.get(i).put(SimSettings.CLIENT_ACTIVITY_START_TIME, new Location(placeTypeIndex, wlan_id, x_pos, y_pos));
        }

        for(int i=0; i<numberOfMobileDevices; i++) {
            TreeMap<Double, Location> treeMap = treeMapArray.get(i);

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
                if (newY < SimSettings.getInstance().getSouthernBound()) newY = (int) SimSettings.getInstance().getSouthernBound();
                if (newY > SimSettings.getInstance().getNorthernBound()) newY = (int) SimSettings.getInstance().getNorthernBound();

                treeMap.put(treeMap.lastKey() + travelTime,
                        new Location(0, 0, newX, newY));
            }
        }

    }

    @Override
    public Location getLocation(int deviceId, double time) {
        TreeMap<Double, Location> treeMap = treeMapArray.get(deviceId);

        Map.Entry<Double, Location> e = treeMap.floorEntry(time);

        if(e == null){
            SimLogger.printLine("impossible is occurred! no location is found for the device '" + deviceId + "' at " + time);
            System.exit(1);
        }

        return e.getValue();
    }

}
