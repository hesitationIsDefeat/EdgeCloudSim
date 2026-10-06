import os
import xml.etree.ElementTree as ET
import re

# ONAT: single source of truth is default_config.properties's task_partition_policies -
# scenario_types/legends/colors/markers below are all derived from it, so adding a new
# policy there is enough; no python changes needed. Unlike tutorial12 (which swept
# uav_mobility_options), tutorial13 fixes UAV mobility to a single PRIORITY_KMEANS
# variant and instead sweeps task_partition_policies (NO vs FULL, see MainApp.java).
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


def _get_application_names(applications_xml_path):
    """
    Reads every <application name="..."> from applications.xml, in document order - this
    must match the app-type segment SimLogger embeds in every per-app *_GENERIC.log file
    name, so plotting scripts never hardcode a stale/incomplete application list.
    """
    root = ET.parse(applications_xml_path).getroot()
    names = [app.get('name') for app in root.findall('application')]
    if not names:
        raise ValueError(f"No <application> found in {applications_xml_path}")
    return names


def _make_app_label(app_name):
    """Turns 'DISASTER_MAP_FUSION' into 'Disaster Map Fusion' for plot labels."""
    return app_name.replace('_', ' ').title()


def compute_num_sar_members(num_normal_users):
    """
    Mirrors SimSettings.computeNumOfSarMembers() (Java): SAR member count is a
    percentage (sar_member_percentage) of the normal-user count, rounded to the
    nearest whole number of sar_team_size teams - not a fixed number.
    """
    percentage = float(_parse_property(_PROPERTIES_PATH, 'sar_member_percentage') or 0)
    team_size = int(_parse_property(_PROPERTIES_PATH, 'sar_team_size') or 5)
    if team_size <= 0:
        return 0
    num_teams = round(round(num_normal_users * percentage) / team_size)
    return num_teams * team_size


def _make_legend(scenario_type):
    """Turns 'NO'/'FULL' into 'No Partitioning'/'Full Partitioning'."""
    return f'{scenario_type.title()} Partitioning'


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
    application_names = _get_application_names(_APPLICATIONS_XML_PATH)

    config = {
        'folder_path': '../../../sim_results/tutorial13',
        'orchestrator_policy': _get_orchestrator_policy(_PROPERTIES_PATH),
        'application_names': application_names,
        'application_labels': [_make_app_label(name) for name in application_names],
        'output_folder_path': os.path.join(
            os.environ.get('PLOT_RUN_DIR', '../../../sim_results/tutorial13'),
            _load_tutorial_display_name('tutorial13')),
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
