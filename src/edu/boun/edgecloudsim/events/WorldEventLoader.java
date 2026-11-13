/*
 * Title:        EdgeCloudSim - World Event Loader
 *
 * Description:
 * Reads the world events of a scenario from its events.xml, one <event> per event that will
 * happen - nothing about them is random.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.events;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * ONAT: Format of events.xml (times in simulation seconds, distances in meters):
 * <pre>
 * &lt;events&gt;
 *   &lt;event name="CONCERT"&gt;
 *     &lt;start_time&gt;200&lt;/start_time&gt;
 *     &lt;end_time&gt;650&lt;/end_time&gt;
 *     &lt;x_pos&gt;200&lt;/x_pos&gt;
 *     &lt;y_pos&gt;800&lt;/y_pos&gt;
 *     &lt;radius&gt;100&lt;/radius&gt;
 *     &lt;attraction_radius&gt;450&lt;/attraction_radius&gt;   (optional, default: radius)
 *     &lt;participation&gt;0.5&lt;/participation&gt;            (optional, default: 1)
 *     &lt;request&gt;VIDEO_APP&lt;/request&gt;
 *     &lt;poisson_interarrival&gt;30&lt;/poisson_interarrival&gt;
 *   &lt;/event&gt;
 * &lt;/events&gt;
 * </pre>
 * The events are returned in file order. A mistake (a missing or unknown tag, a number that is
 * not a number, an unknown request, an event outside the map, a repeated name, ...) is reported as an
 * {@link IllegalArgumentException} naming the event, so a typo cannot silently change an experiment.
 * Load the events only after SimSettings has parsed applications.xml and streams.xml, since
 * every event's request is looked up there.
 */
public final class WorldEventLoader {
	private static final List<String> MANDATORY_TAGS = Arrays.asList(
			"start_time", "end_time", "x_pos", "y_pos", "radius", "request", "poisson_interarrival");
	private static final List<String> OPTIONAL_TAGS = Arrays.asList("attraction_radius", "participation");

	private WorldEventLoader() {
	}

	/**
	 * @param eventsFilePath path of the events.xml
	 * @return the events of the file, in file order (unmodifiable)
	 * @throws IllegalArgumentException if the file cannot be read or any event is invalid
	 */
	public static List<WorldEvent> load(String eventsFilePath) {
		Document doc = parse(eventsFilePath);
		Element root = doc.getDocumentElement();
		if (!"events".equals(root.getTagName()))
			throw new IllegalArgumentException(eventsFilePath + ": the root element must be <events>, found <" + root.getTagName() + ">");

		SimSettings settings = SimSettings.getInstance();
		List<WorldEvent> events = new ArrayList<WorldEvent>();
		Set<String> names = new HashSet<String>();

		NodeList children = root.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i).getNodeType() != Node.ELEMENT_NODE)
				continue;
			Element eventElement = (Element) children.item(i);
			if (!"event".equals(eventElement.getTagName()))
				throw new IllegalArgumentException(eventsFilePath + ": unexpected element <" + eventElement.getTagName() + "> inside <events>");

			String name = eventElement.getAttribute("name").trim();
			if (name.isEmpty())
				throw new IllegalArgumentException(eventsFilePath + ": event number " + (events.size() + 1) + " has no name attribute");
			if (name.contains(SimSettings.DELIMITER))
				throw new IllegalArgumentException("Event '" + name + "': the name must not contain '" + SimSettings.DELIMITER + "'");
			if (!names.add(name))
				throw new IllegalArgumentException("Event '" + name + "' is defined more than once");

			checkTags(eventElement, name);

			double radius = number(eventElement, name, "radius");
			WorldEvent event = new WorldEvent(name,
					number(eventElement, name, "start_time"),
					number(eventElement, name, "end_time"),
					number(eventElement, name, "x_pos"),
					number(eventElement, name, "y_pos"),
					radius,
					hasTag(eventElement, "attraction_radius") ? number(eventElement, name, "attraction_radius") : radius,
					hasTag(eventElement, "participation") ? number(eventElement, name, "participation") : 1.0,
					text(eventElement, name, "request"),
					number(eventElement, name, "poisson_interarrival"));

			if (event.getX() < settings.getWesternBound() || event.getX() > settings.getEasternBound()
					|| event.getY() < settings.getSouthernBound() || event.getY() > settings.getNorthernBound())
				throw new IllegalArgumentException("Event '" + name + "': its center (" + event.getX() + ", " + event.getY()
						+ ") is outside the simulated area");

			if (!settings.isTaskTypeUsed(event.getRequestTaskType()))
				SimLogger.printLine("WARNING: event '" + name + "' requests '" + event.getRequestName()
						+ "', whose usage_percentage is 0 - its requests are counted in the overall results but it gets no results file of its own");
			events.add(event);
		}

		if (events.isEmpty())
			throw new IllegalArgumentException(eventsFilePath + ": no <event> is defined");
		return Collections.unmodifiableList(events);
	}

	private static Document parse(String eventsFilePath) {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// A config file has no use for DTDs; refusing them keeps the parser from fetching anything
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			DocumentBuilder builder = factory.newDocumentBuilder();
			Document doc = builder.parse(new File(eventsFilePath));
			doc.getDocumentElement().normalize();
			return doc;
		} catch (IOException | SAXException | ParserConfigurationException e) {
			throw new IllegalArgumentException("cannot read the events file '" + eventsFilePath + "': " + e.getMessage(), e);
		}
	}

	/** Every mandatory tag exactly once, no tag that is not known. */
	private static void checkTags(Element eventElement, String name) {
		Set<String> seen = new HashSet<String>();
		NodeList children = eventElement.getChildNodes();
		for (int i = 0; i < children.getLength(); i++) {
			if (children.item(i).getNodeType() != Node.ELEMENT_NODE)
				continue;
			String tag = ((Element) children.item(i)).getTagName();
			if (!MANDATORY_TAGS.contains(tag) && !OPTIONAL_TAGS.contains(tag))
				throw new IllegalArgumentException("Event '" + name + "': unknown tag <" + tag + ">");
			if (!seen.add(tag))
				throw new IllegalArgumentException("Event '" + name + "': tag <" + tag + "> is given more than once");
		}
		for (String tag : MANDATORY_TAGS) {
			if (!seen.contains(tag))
				throw new IllegalArgumentException("Event '" + name + "': missing mandatory tag <" + tag + ">");
		}
	}

	private static boolean hasTag(Element eventElement, String tag) {
		return eventElement.getElementsByTagName(tag).getLength() > 0;
	}

	private static String text(Element eventElement, String name, String tag) {
		String value = eventElement.getElementsByTagName(tag).item(0).getTextContent().trim();
		if (value.isEmpty())
			throw new IllegalArgumentException("Event '" + name + "': tag <" + tag + "> is empty");
		return value;
	}

	private static double number(Element eventElement, String name, String tag) {
		String value = text(eventElement, name, tag);
		double number;
		try {
			number = Double.parseDouble(value);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Event '" + name + "': <" + tag + "> must be a number, found '" + value + "'");
		}
		if (Double.isNaN(number) || Double.isInfinite(number))
			throw new IllegalArgumentException("Event '" + name + "': <" + tag + "> must be a finite number, found '" + value + "'");
		return number;
	}
}
