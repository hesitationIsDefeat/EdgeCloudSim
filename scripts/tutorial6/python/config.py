import os


_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', 'config')
_PROPERTIES_PATH = os.path.join(_CONFIG_DIR, 'default_config.properties')


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


def get_configuration():
    """
    Returns a dictionary containing all simulation and plotting parameters.
    Equivalent to getConfiguration.m.
    """
    config = {
        'folder_path': '../../../sim_results/tutorial6',
        'orchestrator_policy': _get_orchestrator_policy(_PROPERTIES_PATH),
        'num_iterations': 10,
        'x_tick_interval': 1,
        'scenario_types': ['NO', 'RANDOM', 'LOCAL', 'ASSIGNED_LOCAL'],
        'legends': ['NO', 'RND', 'LOCAL', 'ASSIGNED'],
        'figure_position': [6, 3, 15, 15],  # [left, bottom, width, height] in centimeters
        'font_sizes': [13, 12, 12],  # [xy_label, legend, xy_axis_ticks]
        'x_axis_label': 'Number of Clients',
        'min_devices': 100,
        'step_devices': 100,
        'max_devices': 800,
        'use_scientific_notation_x_axis': False, # For future use
        'save_figure_as_pdf': True,
        'plot_confidence_interval': True,
        'use_color': True,
        # Colors for plots
        'colors': [
            [0.55, 0, 0],       # Color for first line
            [0, 0.15, 0.6],     # Color for second line
            [0, 0.23, 0],       # Color for third line
            [0.6, 0, 0.6],      # Color for fourth line
            [0.08, 0.08, 0.08]  # Color for fifth line
        ],
        # Line styles and markers for colorless plots
        'bw_markers': ['-k*', '-ko', '--ks', ':k^', '-.kd'],
        # Line styles and markers for colorful plots
        'color_markers': ['-*', '-o', '--s', ':^', '-.d']
    }
    return config