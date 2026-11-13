import os
import xml.etree.ElementTree as ET

# ONAT: tutorial18 compares SERVICE PLACEMENT policies like tutorial17, so every plot has one
# line per entry of default_config.properties's service_placement_policies (legends/colors/
# markers below are all derived from it - adding a new policy there is enough; no python
# changes needed). The UAV mobility option is held fixed (the first uav_mobility_options
# entry) and only appears in the SIMRESULT_*_<MOBILITY>_<PLACEMENT>_* file names MainApp.java
# writes.
# Requests are either plain applications or streams (see streams.xml), so the plots come in two
# groups written to two sub-folders of the output folder - see get_plot_groups().
_CONFIG_DIR = os.path.join(os.path.dirname(__file__), '..', 'config')
_PROPERTIES_PATH = os.path.join(_CONFIG_DIR, 'default_config.properties')
_APPLICATIONS_XML_PATH = os.path.join(_CONFIG_DIR, 'applications.xml')
_STREAMS_XML_PATH = os.path.join(_CONFIG_DIR, 'streams.xml')
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


def _parse_service_placement_policies(properties_path):
    """
    Reads the comma separated service_placement_policies list from default_config.properties.
    Falls back to ALL when absent, mirroring SimSettings (Java)'s default.
    """
    with open(properties_path, 'r') as f:
        for line in f:
            line = line.strip()
            if line.startswith('service_placement_policies='):
                policies = [p.strip() for p in line.split('=', 1)[1].split(',') if p.strip()]
                return policies or ['ALL']
    return ['ALL']


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
    Reads the name of every application that devices request ON THEIR OWN (usage_percentage
    above 0), in document order - these are exactly the applications SimLogger writes a
    per-app *_GENERIC.log for. An application that is only a node of a stream (usage_percentage
    0) is never counted as an application of its own (its runs belong to its stream), so it has
    no file and gets no application plot.
    """
    root = ET.parse(applications_xml_path).getroot()
    names = [app.get('name') for app in root.findall('application')
             if float(app.findtext('usage_percentage', default='0').strip()) > 0]
    if not names:
        raise ValueError(f"No application with a usage_percentage above 0 found in {applications_xml_path}")
    return names


def _get_stream_names(streams_xml_path):
    """
    Reads the name of every <stream> from streams.xml, in document order - these must match the
    per-stream *_GENERIC.log file names SimLogger writes. A stream with usage_percentage 0 is
    never requested, so it has no file and is left out. Empty if there is no streams.xml.
    """
    if not os.path.exists(streams_xml_path):
        return []
    root = ET.parse(streams_xml_path).getroot()
    return [stream.get('name') for stream in root.findall('stream')
            if float(stream.findtext('usage_percentage', default='0').strip()) > 0]


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
    mobility_options = _parse_uav_mobility_options(_PROPERTIES_PATH)
    mobility_option = mobility_options[0]
    if len(mobility_options) > 1:
        print(f"Warning: plotting only the first uav_mobility_options entry ({mobility_option}); "
              f"ignoring {', '.join(mobility_options[1:])}")
    placement_policies = _parse_service_placement_policies(_PROPERTIES_PATH)
    # ONAT: one line per service placement policy. The scenario name is the
    # "<MOBILITY>_<PLACEMENT>" segment embedded in every SIMRESULT_* file name, while the
    # legend only shows the placement policy, since that is what the lines compare.
    scenario_types = [f'{mobility_option}_{p}' for p in placement_policies]
    legends = list(placement_policies)
    num_scenarios = len(scenario_types)
    application_names = _get_application_names(_APPLICATIONS_XML_PATH)
    stream_names = _get_stream_names(_STREAMS_XML_PATH)

    config = {
        'folder_path': '../../../sim_results/tutorial18',
        'orchestrator_policy': _get_orchestrator_policy(_PROPERTIES_PATH),
        'application_names': application_names,
        'application_labels': [_make_app_label(name) for name in application_names],
        'stream_names': stream_names,
        'stream_labels': [_make_app_label(name) for name in stream_names],
        'output_folder_path': os.path.join(
            os.environ.get('PLOT_RUN_DIR', '../../../sim_results/tutorial18'),
            _load_tutorial_display_name('tutorial18')),
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
        # ONAT: tutorial18 intentionally never lists NO/RANDOM in uav_mobility_options
        # (see default_config.properties) - this filter is a no-op here but left in
        # place so pasting a NO/RANDOM entry back in for comparison doesn't also need a
        # python change.
        'heatmap_video_scenarios': [] if mobility_option in ('NO', 'RANDOM') else list(scenario_types),
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


def get_plot_groups(config=None):
    """
    ONAT: the two kinds of request this tutorial plots, each written to its own sub-folder of
    config['output_folder_path'] with identical plots:
      - 'applications': the applications devices request ON THEIR OWN, plus the overall
        ALL_APPS aggregate over just those. An application run as a node of a stream is counted
        in neither (SimLogger counts a stream's nodes in the stream, not as application runs),
        and an application that is only ever a stream node gets no application plot at all.
      - 'streams': every stream, plus the overall ALL_STREAMS aggregate over streams only.
    Each group is a dict: 'folder' (sub-folder name), 'noun' (used in y-axis labels),
    'overall' (the aggregate file label), 'names' (per-item file labels) and 'labels'
    (readable names, same order). A group without items (e.g. no streams.xml) is left out.
    """
    config = config or get_configuration()
    groups = [{
        'folder': 'applications',
        'noun': 'App',
        'overall': 'ALL_APPS',
        'names': config['application_names'],
        'labels': config['application_labels'],
    }]
    if config['stream_names']:
        groups.append({
            'folder': 'streams',
            'noun': 'Stream',
            'overall': 'ALL_STREAMS',
            'names': config['stream_names'],
            'labels': config['stream_labels'],
        })
    return groups
