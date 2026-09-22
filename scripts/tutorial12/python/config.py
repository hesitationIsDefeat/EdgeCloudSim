import os
import re

# ONAT: single source of truth is default_config.properties's uav_mobility_options -
# scenario_types/legends/colors/markers below are all derived from it, so adding a new
# "PRIORITY_KMEANS_<factor>" there is enough; no python changes needed.
_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', 'config')
_PROPERTIES_PATH = os.path.join(_CONFIG_DIR, 'default_config.properties')
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


def _parse_uav_mobility_options(properties_path):
    """Reads the comma separated uav_mobility_options list from default_config.properties."""
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith('uav_mobility_options='):
                return [opt.strip() for opt in line.split('=', 1)[1].split(',') if opt.strip()]
    raise ValueError(f"uav_mobility_options not found in {properties_path}")


def _parse_property(properties_path, key):
    """Reads a single `key=value` line from default_config.properties, or None if absent."""
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith(f'{key}='):
                return line.split('=', 1)[1].strip()
    return None


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
    """Turns 'PRIORITY_KMEANS'/'PRIORITY_KMEANS_2' into 'PRIORITY_KMEANS (1x)'/'PRIORITY_KMEANS (2x)'."""
    match = re.match(r'^(.*)_(\d+(?:\.\d+)?)$', scenario_type)
    if match:
        base, factor = match.groups()
        return f'{base} ({factor}x)'
    return f'{scenario_type} (1x)'


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
    scenario_types = _parse_uav_mobility_options(_PROPERTIES_PATH)
    legends = [_make_legend(scenario_type) for scenario_type in scenario_types]
    num_scenarios = len(scenario_types)

    config = {
        'folder_path': '../../../sim_results/tutorial12',
        'output_folder_path': os.path.join('../../../sim_results/tutorial12', _load_tutorial_display_name('tutorial12')),
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
