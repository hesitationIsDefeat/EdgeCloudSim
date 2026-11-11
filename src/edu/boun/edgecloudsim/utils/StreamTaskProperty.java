/*
 * Title:        EdgeCloudSim - StreamTaskProperty
 *
 * Description:
 * A TaskProperty describing one request of a STREAM (see streams.xml): a DAG whose
 * nodes are applications instead of sub-tasks. It carries one pre-sampled TaskProperty
 * per node, grouped into the stream's layers, so the whole workload stays fixed before the
 * simulation starts like any other generated task.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.ToLongFunction;

import edu.boun.edgecloudsim.core.SimSettings;

/**
 * One request of a stream. Its own task type is the stream's task type
 * ({@link SimSettings#getStreamTaskType(int)}), so statistics are kept per stream next to the
 * per-application ones; the length/upload/download it reports are only the sums over its nodes
 * (informational - the real work is done by the node tasks, each a regular application task
 * with the application's own task type).
 */
public class StreamTaskProperty extends TaskProperty {
	private final int streamIndex;
	private final List<List<TaskProperty>> nodeLayers;

	/**
	 * @param startTime simulation time the stream is requested at (seconds)
	 * @param mobileDeviceId device requesting the stream
	 * @param streamIndex 0-based position of the stream in streams.xml
	 * @param nodeLayers one TaskProperty per node, per layer, in dependency order - layer L is
	 *                   only submitted once every node of layer L-1 has completed
	 */
	public StreamTaskProperty(double startTime, int mobileDeviceId, int streamIndex, List<List<TaskProperty>> nodeLayers) {
		super(startTime, mobileDeviceId, SimSettings.getInstance().getStreamTaskType(streamIndex), 1,
				sumOverNodes(nodeLayers, TaskProperty::getLength), sumOverNodes(nodeLayers, TaskProperty::getInputFileSize),
				sumOverNodes(nodeLayers, TaskProperty::getOutputFileSize));
		this.streamIndex = streamIndex;

		List<List<TaskProperty>> copy = new ArrayList<List<TaskProperty>>(nodeLayers.size());
		for (List<TaskProperty> layer : nodeLayers) {
			copy.add(Collections.unmodifiableList(new ArrayList<TaskProperty>(layer)));
		}
		this.nodeLayers = Collections.unmodifiableList(copy);
	}

	/** @return 0-based position of this request's stream in streams.xml */
	public int getStreamIndex() {
		return streamIndex;
	}

	/** @return the stream's nodes, per layer in dependency order (read-only) */
	public List<List<TaskProperty>> getNodeLayers() {
		return nodeLayers;
	}

	private static long sumOverNodes(List<List<TaskProperty>> nodeLayers, ToLongFunction<TaskProperty> field) {
		long total = 0;
		for (List<TaskProperty> layer : nodeLayers) {
			for (TaskProperty node : layer) {
				total += field.applyAsLong(node);
			}
		}
		return total;
	}
}
