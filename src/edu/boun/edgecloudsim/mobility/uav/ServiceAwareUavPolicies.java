/*
 * Title:        EdgeCloudSim - Service-aware UAV mobility policies
 *
 * Description:
 * The target-selection logic of the service-aware centralized UAV policies (SERVICE_KMEANS,
 * SERVICE_APF, SERVICE_CAPACITY_FILL): the service-aware counterparts of the PRIORITY_KMEANS,
 * APF and CAPACITY_FILL policies of CentralizedUAVMobility. They differ in one thing: the
 * users' pull on a UAV depends on what the UAV can serve. Each unit of demand (a request
 * together with the service it needs, see ServiceDemandTracker) only pulls the UAVs that host
 * that service, so UAVs gather where THEIR services are being requested - not merely where
 * users are. A UAV that no demand for its services pulls holds its position.
 * With every UAV hosting every service they reduce to the service-blind policies (apart from
 * counting requests instead of devices).
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.mobility.uav;

import static edu.boun.edgecloudsim.utils.SimUtils.RNG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_server.uav.UAV;
import edu.boun.edgecloudsim.mobility.uav.ServiceDemandTracker.Demand;
import edu.boun.edgecloudsim.utils.Location;

/**
 * ONAT: Each method returns, per UAV (same order as the UAV list), the point the central
 * controller should send it to, or null when the UAV should keep its position. The caller
 * (CentralizedUAVMobility) applies the speed limit and the map bounds when it alerts the UAV.
 * <p>
 * {@code hosts[u][slot]} says whether UAV u hosts the service of that slot (see
 * ServiceDemandTracker.getServiceIndices()); demand entries whose service no UAV hosts
 * pull nobody.
 */
final class ServiceAwareUavPolicies {
	private ServiceAwareUavPolicies() {
	}

	/**
	 * SERVICE_KMEANS: Lloyd's algorithm like PRIORITY_KMEANS (centers seeded at the UAVs' own
	 * positions, K = number of UAVs, weighted centroids, same iteration cap and stop rule), but
	 * every demand entry can only join the nearest center of a UAV that hosts its service - so
	 * the UAVs of one service end up clustering independently over that service's demand.
	 * A UAV nobody joined keeps its own position as its center, i.e. stays.
	 */
	static double[][] serviceKMeans(List<UAV> uavs, boolean[][] hosts, Demand demand) {
		int numOfUavs = uavs.size();
		double[] centerX = new double[numOfUavs];
		double[] centerY = new double[numOfUavs];
		for (int u = 0; u < numOfUavs; u++) {
			Location location = uavs.get(u).getLocation();
			centerX[u] = location.getXPos();
			centerY[u] = location.getYPos();
		}

		double[] sumX = new double[numOfUavs];
		double[] sumY = new double[numOfUavs];
		double[] sumWeight = new double[numOfUavs];
		for (int iteration = 0; iteration < CentralizedUAVMobility.KMEANS_MAX_ITERATIONS; iteration++) {
			Arrays.fill(sumX, 0);
			Arrays.fill(sumY, 0);
			Arrays.fill(sumWeight, 0);

			for (int p = 0; p < demand.size(); p++) {
				int nearest = nearestHostingCenter(centerX, centerY, hosts, demand.serviceSlot[p], demand.x[p], demand.y[p]);
				if (nearest < 0)
					continue; // no UAV hosts this service at all
				sumX[nearest] += demand.x[p] * demand.weight[p];
				sumY[nearest] += demand.y[p] * demand.weight[p];
				sumWeight[nearest] += demand.weight[p];
			}

			double maxShift = 0;
			for (int c = 0; c < numOfUavs; c++) {
				if (sumWeight[c] <= 0)
					continue; // empty cluster - keep its previous center

				double newX = sumX[c] / sumWeight[c];
				double newY = sumY[c] / sumWeight[c];
				maxShift = Math.max(maxShift, Math.hypot(newX - centerX[c], newY - centerY[c]));
				centerX[c] = newX;
				centerY[c] = newY;
			}

			if (maxShift < CentralizedUAVMobility.KMEANS_CONVERGENCE_EPSILON)
				break;
		}

		double[][] targets = new double[numOfUavs][];
		for (int u = 0; u < numOfUavs; u++) {
			targets[u] = new double[] { centerX[u], centerY[u] };
		}
		return targets;
	}

	/** Index of the center nearest to (x, y) among the UAVs hosting the slot's service, or -1 if none does. */
	private static int nearestHostingCenter(double[] centerX, double[] centerY, boolean[][] hosts, int slot, double x, double y) {
		int nearest = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int c = 0; c < centerX.length; c++) {
			if (!hosts[c][slot])
				continue;
			double dx = centerX[c] - x;
			double dy = centerY[c] - y;
			double distance = dx * dx + dy * dy;
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = c;
			}
		}
		return nearest;
	}

	/**
	 * SERVICE_APF: the artificial potential fields of the APF policy, with two changes.
	 * (1) A demand entry attracts a UAV only if the UAV hosts the entry's service.
	 * (2) The repulsion between two UAVs is scaled by how much of what they host overlaps
	 * (Jaccard similarity of their hosted services): two UAVs hosting the same services still
	 * push each other apart, but UAVs hosting different services do not - they are meant to
	 * stack on a place that needs several services at once (a stream), and the more a UAV
	 * overlaps another, the less useful it is to have both there.
	 * A UAV that no demand pulls holds its position (instead of drifting on repulsion alone).
	 */
	static double[][] serviceApf(List<UAV> uavs, boolean[][] hosts, Demand demand) {
		int numOfUavs = uavs.size();
		double[] uavX = new double[numOfUavs];
		double[] uavY = new double[numOfUavs];
		for (int u = 0; u < numOfUavs; u++) {
			Location location = uavs.get(u).getLocation();
			uavX[u] = location.getXPos();
			uavY[u] = location.getYPos();
		}

		double[][] overlap = hostedOverlaps(hosts);
		double[][] targets = new double[numOfUavs][];

		for (int j = 0; j < numOfUavs; j++) {
			double forceX = 0;
			double forceY = 0;
			double pullingWeight = 0;

			for (int p = 0; p < demand.size(); p++) {
				if (!hosts[j][demand.serviceSlot[p]])
					continue;
				double dx = demand.x[p] - uavX[j];
				double dy = demand.y[p] - uavY[j];
				double scale = CentralizedUAVMobility.APF_ATTRACTION_GAIN * demand.weight[p]
						/ CentralizedUAVMobility.cubedSoftenedDistance(dx, dy);
				forceX += scale * dx;
				forceY += scale * dy;
				pullingWeight += demand.weight[p];
			}

			if (pullingWeight <= 0)
				continue; // nothing this UAV can serve is being requested - hold

			for (int k = 0; k < numOfUavs; k++) {
				if (k == j || overlap[j][k] <= 0)
					continue;

				double dx = uavX[k] - uavX[j];
				double dy = uavY[k] - uavY[j];
				if (dx == 0 && dy == 0) {
					// exactly co-located: the repulsion direction is undefined, so pick a random one
					// (same fix as the service-blind APF policy)
					double angle = RNG.nextDouble() * 2 * Math.PI;
					dx = Math.cos(angle);
					dy = Math.sin(angle);
				}
				double scale = overlap[j][k] * CentralizedUAVMobility.APF_REPULSION_GAIN
						/ CentralizedUAVMobility.cubedSoftenedDistance(dx, dy);
				forceX -= scale * dx;
				forceY -= scale * dy;
			}

			if (Math.hypot(forceX, forceY) < CentralizedUAVMobility.APF_DEADLOCK_FORCE_EPSILON) {
				// attraction and repulsion (nearly) cancel out - random nudge to leave the local minimum
				int maxMove = Math.max(1, (int) CentralizedUAVMobility.maxMoveDistance(uavs.get(j)));
				int delta = (RNG.nextInt(maxMove) + 1) * (RNG.nextBoolean() ? 1 : -1);
				targets[j] = new double[] {
						uavX[j] + (RNG.nextBoolean() ? delta : 0),
						uavY[j] + (RNG.nextBoolean() ? 0 : delta) };
			} else {
				// only the DIRECTION matters once the force exceeds the UAV's speed limit
				targets[j] = new double[] { uavX[j] + forceX, uavY[j] + forceY };
			}
		}
		return targets;
	}

	/**
	 * SERVICE_CAPACITY_FILL: the demand-fill policy of CAPACITY_FILL, per service. The map is
	 * split into grid cells and every cell has a demand PER SERVICE. The (cell, service) pairs
	 * are served highest demand first: the nearest still-unassigned UAV that hosts the service is
	 * sent to the cell, and is credited with the same per-UAV capacity (total demand / number of
	 * UAVs) as in CAPACITY_FILL, which it spends first on that service and then - if it hosts
	 * them - on the other services requested in that cell - so with every UAV hosting every
	 * service this assigns the same number of UAVs as CAPACITY_FILL. More UAVs are sent while
	 * the pair's demand is not covered and a UAV hosting the service is left. UAVs still
	 * unassigned afterwards hold their position. UAVs sharing a cell disperse slightly, only
	 * from the ones they overlap with (hosting the same services), like SERVICE_APF's repulsion.
	 */
	static double[][] serviceCapacityFill(List<UAV> uavs, boolean[][] hosts, Demand demand) {
		int numOfUavs = uavs.size();
		double[][] targets = new double[numOfUavs][];
		if (numOfUavs == 0)
			return targets;

		SimSettings settings = SimSettings.getInstance();
		double cellSize = CentralizedUAVMobility.CAPACITY_FILL_GRID_CELL_SIZE;
		double westernBound = settings.getWesternBound();
		double southernBound = settings.getSouthernBound();
		int numCols = (int) Math.ceil((settings.getEasternBound() - westernBound) / cellSize);
		int numRows = (int) Math.ceil((settings.getNorthernBound() - southernBound) / cellSize);
		int numOfSlots = hosts[0].length;
		if (numCols <= 0 || numRows <= 0)
			return targets;

		double[][] remaining = new double[numCols * numRows][numOfSlots];
		double totalDemand = 0;
		for (int p = 0; p < demand.size(); p++) {
			int col = CentralizedUAVMobility.clampIndex((int) ((demand.x[p] - westernBound) / cellSize), numCols);
			int row = CentralizedUAVMobility.clampIndex((int) ((demand.y[p] - southernBound) / cellSize), numRows);
			remaining[row * numCols + col][demand.serviceSlot[p]] += demand.weight[p];
			totalDemand += demand.weight[p];
		}
		if (totalDemand <= 0)
			return targets;

		double capacityPerUav = totalDemand / numOfUavs * CentralizedUAVMobility.CAPACITY_FILL_LOAD_FACTOR;

		// (cell, slot) pairs with demand, highest demand first (ties by position, irrelevant to correctness)
		List<int[]> pairs = new ArrayList<int[]>();
		for (int cell = 0; cell < remaining.length; cell++) {
			for (int slot = 0; slot < numOfSlots; slot++) {
				if (remaining[cell][slot] > 0)
					pairs.add(new int[] { cell, slot });
			}
		}
		double[][] initialDemand = new double[remaining.length][];
		for (int cell = 0; cell < remaining.length; cell++) {
			initialDemand[cell] = remaining[cell].clone();
		}
		pairs.sort((a, b) -> Double.compare(initialDemand[b[0]][b[1]], initialDemand[a[0]][a[1]]));

		double[] uavX = new double[numOfUavs];
		double[] uavY = new double[numOfUavs];
		for (int u = 0; u < numOfUavs; u++) {
			Location location = uavs.get(u).getLocation();
			uavX[u] = location.getXPos();
			uavY[u] = location.getYPos();
		}

		int[] assignedCell = new int[numOfUavs]; // -1 = not assigned to any cell
		Arrays.fill(assignedCell, -1);

		for (int[] pair : pairs) {
			int cell = pair[0];
			int slot = pair[1];
			double cellCenterX = westernBound + (cell % numCols + 0.5) * cellSize;
			double cellCenterY = southernBound + (cell / numCols + 0.5) * cellSize;

			while (remaining[cell][slot] > 0) {
				int nearest = -1;
				double bestDistanceSquared = Double.MAX_VALUE;
				for (int u = 0; u < numOfUavs; u++) {
					if (assignedCell[u] != -1 || !hosts[u][slot])
						continue;
					double dx = uavX[u] - cellCenterX;
					double dy = uavY[u] - cellCenterY;
					double distanceSquared = dx * dx + dy * dy;
					if (distanceSquared < bestDistanceSquared) {
						bestDistanceSquared = distanceSquared;
						nearest = u;
					}
				}
				if (nearest < 0)
					break; // every UAV hosting this service is already busy elsewhere

				assignedCell[nearest] = cell;
				spendCapacity(remaining[cell], slot, hosts[nearest], capacityPerUav);
			}
		}

		double[][] overlap = hostedOverlaps(hosts);
		for (int j = 0; j < numOfUavs; j++) {
			int cell = assignedCell[j];
			if (cell == -1)
				continue; // no unmet demand this UAV can serve - hold

			double targetX = westernBound + (cell % numCols + 0.5) * cellSize;
			double targetY = southernBound + (cell / numCols + 0.5) * cellSize;

			double dispersionX = 0;
			double dispersionY = 0;
			for (int k = 0; k < numOfUavs; k++) {
				if (k == j || assignedCell[k] != cell || overlap[j][k] <= 0)
					continue;

				double dx = uavX[j] - uavX[k];
				double dy = uavY[j] - uavY[k];
				if (dx == 0 && dy == 0) {
					double angle = RNG.nextDouble() * 2 * Math.PI;
					dx = Math.cos(angle);
					dy = Math.sin(angle);
				}
				double scale = overlap[j][k] * CentralizedUAVMobility.CAPACITY_FILL_DISPERSION_GAIN
						/ CentralizedUAVMobility.cubedSoftenedDistance(dx, dy);
				dispersionX += scale * dx;
				dispersionY += scale * dy;
			}

			targets[j] = new double[] { targetX + dispersionX, targetY + dispersionY };
		}
		return targets;
	}

	/**
	 * Credits one UAV's capacity against the demand of a cell: first the demand of the service it
	 * was sent for, then (largest first) the demand of the other services in the cell the UAV
	 * hosts, until its capacity is used up or nothing it can serve is left there.
	 */
	private static void spendCapacity(double[] cellRemaining, int ownSlot, boolean[] uavHosts, double capacity) {
		double budget = capacity;

		double spend = Math.min(budget, cellRemaining[ownSlot]);
		cellRemaining[ownSlot] -= spend;
		budget -= spend;

		while (budget > 0) {
			int best = -1;
			for (int slot = 0; slot < cellRemaining.length; slot++) {
				if (slot == ownSlot || !uavHosts[slot] || cellRemaining[slot] <= 0)
					continue;
				if (best < 0 || cellRemaining[slot] > cellRemaining[best])
					best = slot;
			}
			if (best < 0)
				break;

			spend = Math.min(budget, cellRemaining[best]);
			cellRemaining[best] -= spend;
			budget -= spend;
		}
	}

	/** Pairwise Jaccard similarity of what the UAVs host: 1 = same services, 0 = nothing in common. */
	static double[][] hostedOverlaps(boolean[][] hosts) {
		int numOfUavs = hosts.length;
		double[][] overlap = new double[numOfUavs][numOfUavs];
		for (int a = 0; a < numOfUavs; a++) {
			for (int b = 0; b < numOfUavs; b++) {
				int shared = 0;
				int either = 0;
				for (int slot = 0; slot < hosts[a].length; slot++) {
					if (hosts[a][slot] && hosts[b][slot])
						shared++;
					if (hosts[a][slot] || hosts[b][slot])
						either++;
				}
				overlap[a][b] = either == 0 ? 0 : (double) shared / either;
			}
		}
		return overlap;
	}
}
