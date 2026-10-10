import os
import re
import xml.etree.ElementTree as ET

# ONAT: single source of truth is default_config.properties's task_partition_policies
# (NO / FULL / PARTITION_<N>) - scenario_types/legends/colors/markers below are all
# derived from it. UAV mobility is fixed to a single KMEANS (PRIORITY_KMEANS) variant -
# see MainApp.java.
_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', 'config')
_PROPERTIES_PATH = os.path.join(_CONFIG_DIR, 'default_config.properties')
_APPLICATIONS_XML_PATH = os.path.join(_CONFIG_DIR, 'applications.xml')
_ROOT_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', '..', '..', 'config')
_TUTORIAL_NAMES_PATH = os.path.join(_ROOT_CONFIG_DIR, 'tutorial_names.properties')

# Cycled by index so any number of scenarios gets a distinct color/marker automatically.
_COLOR_PALETTE = [
    [0.55, 0, 0],
    [0, 0.15, 0.6],
    [0, 0.6, 0.2],
    [0.6, 0.4, 0],
    [0.4, 0, 0.6],
    [0, 0.5, 0.5],
]
_BW_MARKER_PALETTE = ['-k*', '-ko', '-kv', '-ks', '-k^', '-kd']
_COLOR_MARKER_PALETTE = ['-*', '-o', '-v', '-s', '-^', '-d']


def _parse_task_partition_policies(properties_path):
    """Reads the comma separated task_partition_policies list from default_config.properties."""
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith('task_partition_policies='):
                return [opt.strip().upper() for opt in line.split('=', 1)[1].split(',') if opt.strip()]
    raise ValueError(f"task_partition_policies not found in {properties_path}")


def _parse_property(properties_path, key):
    """Reads a single `key=value` line from default_config.properties, or None if absent."""
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith(f'{key}='):
                return line.split('=', 1)[1].strip()
    return None


def _get_application_name(applications_xml_path):
    """
    Reads the (single) <application name="..."> from applications.xml - this must match
    the app-type segment SimLogger embeds in every per-app *_GENERIC.log file name, so
    plotting scripts never hardcode a stale application name.
    """
    root = ET.parse(applications_xml_path).getroot()
    applications = root.findall('application')
    if not applications:
        raise ValueError(f"No <application> found in {applications_xml_path}")
    return applications[0].get('name')


def _get_orchestrator_policy(properties_path):
    """
    Reads orchestrator_policies from default_config.properties and returns the first
    entry - this must match the orchestratorPolicy segment MainApp.java embeds in every
    SIMRESULT_* log file name, so plotting scripts never hardcode a stale policy name.
    """
    value = _parse_property(properties_path, 'orchestrator_policies')
    if not value:
        raise ValueError(f"orchestrator_policies not found in {properties_path}")
    return value.split(',')[0].strip()


_LEGEND_NAMES = {'NO': 'Not Partitioned', 'FULL': 'DAG Partitioned'}
_PARTITION_POLICY_PATTERN = re.compile(r'PARTITION_(\d+)')


def _make_legend(scenario_type):
    """Turns 'NO'/'FULL' into 'Not Partitioned'/'DAG Partitioned', and
    'PARTITION_4'/'PARTITION_7' into '4 Partitions (Flat)'/'7 Partitions (Flat)' -
    the "(Flat)" suffix distinguishes these from FULL's dependent-layer split, since
    both can have the same child count (e.g. PARTITION_7) yet differ in whether
    children are submitted all at once or in dependent waves."""
    if scenario_type in _LEGEND_NAMES:
        return _LEGEND_NAMES[scenario_type]
    match = _PARTITION_POLICY_PATTERN.match(scenario_type)
    if not match:
        return scenario_type
    count = int(match.group(1))
    return f'{count} Partition (Flat)' if count == 1 else f'{count} Partitions (Flat)'


def _load_tutorial_display_name(tutorial_key):
    """Reads config/tutorial_names.properties; falls back to the key itself if missing."""
    try:
        with open(_TUTORIAL_NAMES_PATH, 'r') as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                key, _, value = line.partition('=')
                if key.strip() == tutorial_key:
                    return value.strip()
    except FileNotFoundError:
        pass
    return tutorial_key


def get_configuration():
    """
    Returns a dictionary containing all simulation and plotting parameters.
    Equivalent to getConfiguration.m.
    """
    scenario_types = _parse_task_partition_policies(_PROPERTIES_PATH)
    legends = [_make_legend(scenario_type) for scenario_type in scenario_types]
    num_scenarios = len(scenario_types)

    config = {
        'folder_path': '../../../sim_results/tutorial15',
        'orchestrator_policy': _get_orchestrator_policy(_PROPERTIES_PATH),
        'application_name': _get_application_name(_APPLICATIONS_XML_PATH),
        'output_folder_path': os.path.join(
            os.environ.get('PLOT_RUN_DIR', '../../../sim_results/tutorial15'),
            _load_tutorial_display_name('tutorial15')),
        'num_iterations': 10,
        'x_tick_interval': 1,
        'scenario_types': scenario_types,
        'legends': legends,
        'figure_position': [6, 3, 15, 15],  # [left, bottom, width, height] in centimeters
        'font_sizes': [13, 12, 12],  # [xy_label, legend, xy_axis_ticks]
        'x_axis_label': 'Number of Clients',
        'min_devices': 100,
        'step_devices': 100,
        'max_devices': 800,
        # ONAT: device counts to render heat map videos for (plotUserLocationHeatmapVideo.py),
        # used instead of sweeping every step_devices increment since videos are expensive to generate.
        'heatmap_video_devices': [800],
        'use_scientific_notation_x_axis': False, # For future use
        'save_figure_as_pdf': True,
        'plot_confidence_interval': True,
        'use_color': True,
        # Colors for plots
        'colors': [_COLOR_PALETTE[i % len(_COLOR_PALETTE)] for i in range(num_scenarios)],
        # Line styles and markers for colorless plots
        'bw_markers': [_BW_MARKER_PALETTE[i % len(_BW_MARKER_PALETTE)] for i in range(num_scenarios)],
        # Line styles and markers for colorful plots
        'color_markers': [_COLOR_MARKER_PALETTE[i % len(_COLOR_MARKER_PALETTE)] for i in range(num_scenarios)]
    }
    return config
