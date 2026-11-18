/*
 * Title:        EdgeCloudSim - Events Files
 *
 * Description:
 * Finds the events files of a tutorial (every events*.xml of its config folder) and the name each
 * of them is run under, taken from the events names file (config/event_names.properties).
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.events;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import edu.boun.edgecloudsim.utils.SimLogger;

/**
 * ONAT: A tutorial can come with several events files (events.xml, events_storm.xml, ...), all in
 * its config folder and all in the format {@link WorldEventLoader} reads. The whole tutorial is run
 * once for each of them, and the results of a run are kept in a folder with the name the events
 * file has in the events names file: a line {@code <tutorial>.<file name without .xml>=<name>}, e.g.
 * {@code tutorial19.events_storm=StormSeason}. A file that has no line is run under its file name
 * without the .xml. A name becomes a folder name (and is passed around by shell scripts), so it may
 * only contain letters, digits, '_', '-' and '.', and two files of a tutorial may not share one.
 */
public final class EventsFiles {
	/** One events file and the name it is run under. */
	public static final class Entry {
		private final String name;
		private final String path;

		private Entry(String name, String path) {
			this.name = name;
			this.path = path;
		}

		/** @return the name of the run, which is also the name of its results folder */
		public String getName() {
			return name;
		}

		/** @return path of the events file, to be given to {@link WorldEventLoader#load(String)} */
		public String getPath() {
			return path;
		}
	}

	private static final String FILE_PREFIX = "events";
	private static final String FILE_SUFFIX = ".xml";
	private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_.-]+");

	private EventsFiles() {
	}

	/**
	 * @param eventsFilePath one events file, e.g. the one a script run was given
	 * @return an entry for it, named after its file name without the .xml
	 */
	public static Entry single(String eventsFilePath) {
		String fileName = new File(eventsFilePath).getName();
		return new Entry(fileName.endsWith(FILE_SUFFIX) ? fileName.substring(0, fileName.length() - FILE_SUFFIX.length()) : fileName,
				eventsFilePath);
	}

	/**
	 * @param configFolder  the tutorial's config folder, which holds its events files
	 * @param tutorialKey   the tutorial as written in the events names file, e.g. "tutorial19"
	 * @param namesFilePath the events names file; one that does not exist leaves every file under its own name
	 * @return an entry for every events*.xml of the folder, sorted by file name (unmodifiable)
	 * @throws IllegalArgumentException if there is no events file, a name is invalid or two files share a name
	 */
	public static List<Entry> discover(String configFolder, String tutorialKey, String namesFilePath) {
		File[] files = new File(configFolder).listFiles((dir, fileName) ->
				fileName.startsWith(FILE_PREFIX) && fileName.endsWith(FILE_SUFFIX));
		if (files == null || files.length == 0)
			throw new IllegalArgumentException("no events file (" + FILE_PREFIX + "*" + FILE_SUFFIX + ") found in '" + configFolder + "'");
		Arrays.sort(files);

		Properties names = loadNames(namesFilePath);
		List<Entry> entries = new ArrayList<Entry>(files.length);
		Set<String> usedNames = new HashSet<String>();
		for (File file : files) {
			String baseName = file.getName().substring(0, file.getName().length() - FILE_SUFFIX.length());
			String name = names.getProperty(tutorialKey + "." + baseName, baseName).trim();

			if (!VALID_NAME.matcher(name).matches() || name.equals(".") || name.equals(".."))
				throw new IllegalArgumentException("invalid name '" + name + "' for " + file.getName() + " in '" + namesFilePath
						+ "': it may only contain letters, digits, '_', '-' and '.'");
			if (!usedNames.add(name))
				throw new IllegalArgumentException("the name '" + name + "' is given to more than one events file of " + tutorialKey
						+ " (see '" + namesFilePath + "')");
			if (!names.containsKey(tutorialKey + "." + baseName))
				SimLogger.printLine("No name for " + file.getName() + " in '" + namesFilePath + "' (expected a line "
						+ tutorialKey + "." + baseName + "=<name>); its results go to '" + name + "'");

			entries.add(new Entry(name, file.getPath()));
		}
		return Collections.unmodifiableList(entries);
	}

	private static Properties loadNames(String namesFilePath) {
		Properties names = new Properties();
		File namesFile = new File(namesFilePath);
		if (!namesFile.isFile())
			return names;

		try (InputStream input = new FileInputStream(namesFile)) {
			names.load(input);
		} catch (IOException e) {
			throw new IllegalArgumentException("cannot read the events names file '" + namesFilePath + "': " + e.getMessage(), e);
		}
		return names;
	}
}
