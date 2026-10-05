import os

# ONAT: tutorial14's x-axis is partition count (1,2,4,8,16 - an explicit, non-arithmetic
# list read from partition_count_options), NOT device count like every other tutorial -
# see x_values below and plotGenericLine.py's rewritten data-reading loop.
_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', 'config')
_PROPERTIES_PATH = os.path.join(_CONFIG_DIR, 'default_config.properties')
_ROOT_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', '..', '..', 'config')
_TUTORIAL_NAMES_PATH = os.path.join(_ROOT_CONFIG_DIR, 'tutorial_names.properties')

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


def _parse_property(properties_path, key):
    """Reads a single `key=value` line from default_config.properties, or None if absent."""
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith(f'{key}='):
                return line.split('=', 1)[1].strip()
    return None


def _parse_csv_property(properties_path, key):
    """Reads a comma separated `key=a,b,c` line from default_config.properties."""
    value = _parse_property(properties_path, key)
    if not value:
        raise ValueError(f"{key} not found in {properties_path}")
    return [v.strip() for v in value.split(',') if v.strip()]


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


def get_fixed_sar_member_count():
    """
    tutorial14 has no normal-user population - every device is a SAR/LLM-inference
    member, and the population size is FIXED directly via min_number_of_mobile_devices
    (== max_number_of_mobile_devices), unlike tutorial8-13's percentage-based
    computeNumOfSarMembers(). Mirrors MainApp.java's SS.getMinNumOfMobileDev() read.
    """
    value = _parse_property(_PROPERTIES_PATH, 'min_number_of_mobile_devices')
    return int(value) if value else 0


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
    scenario_types = _parse_csv_property(_PROPERTIES_PATH, 'task_partition_policies')
    legends = [_make_legend(scenario_type) for scenario_type in scenario_types]
    num_scenarios = len(scenario_types)

    x_values = [int(v) for v in _parse_csv_property(_PROPERTIES_PATH, 'partition_count_options')]

    config = {
        'folder_path': '../../../sim_results/tutorial14',
        'orchestrator_policy': _get_orchestrator_policy(_PROPERTIES_PATH),
        'output_folder_path': os.path.join(
            os.environ.get('PLOT_RUN_DIR', '../../../sim_results/tutorial14'),
            _load_tutorial_display_name('tutorial14')),
        'num_iterations': 10,
        'x_tick_interval': 1,
        'scenario_types': scenario_types,
        'legends': legends,
        'figure_position': [6, 3, 15, 15],  # [left, bottom, width, height] in centimeters
        'font_sizes': [13, 12, 12],  # [xy_label, legend, xy_axis_ticks]
        'x_axis_label': 'Partition Count',
        # ONAT: explicit, non-arithmetic x-axis values (replaces min/step/max device count).
        'x_values': x_values,
        # ONAT: partition count(s) to render heat map videos for (plotUserLocationHeatmapVideo.py).
        # Defaults to the largest configured partition count (most granular partitioning).
        'heatmap_video_partition_counts': [x_values[-1]] if x_values else [],
        'use_scientific_notation_x_axis': False,
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
