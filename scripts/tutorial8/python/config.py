def get_configuration():
    """
    Returns a dictionary containing all simulation and plotting parameters.
    Equivalent to getConfiguration.m.
    """
    config = {
        'folder_path': '../../../sim_results/tutorial8',
        'num_iterations': 10,
        'x_tick_interval': 1,
        'scenario_types': ['NO', 'RANDOM', 'LOCAL_FORCE', 'VORONOI'],
        'legends': ['NO', 'RND', 'LOCAL_FORCE', 'VORONOI'],
        'figure_position': [6, 3, 15, 15],  # [left, bottom, width, height] in centimeters
        'font_sizes': [13, 12, 12],  # [xy_label, legend, xy_axis_ticks]
        'x_axis_label': 'Number of Clients',
        'min_devices': 100,
        'step_devices': 100,
        'max_devices': 800,
        # ONAT: must match number_of_sar_members in default_config.properties. SAR members
        # are appended after the swept normal-user devices, ids [num_devices, num_devices+num_sar_members).
        'num_sar_members': 120,
        'use_scientific_notation_x_axis': False, # For future use
        'save_figure_as_pdf': True,
        'plot_confidence_interval': True,
        'use_color': True,
        # Colors for plots
        'colors': [
            [0.55, 0, 0],       # Color for first line
            [0, 0.15, 0.6],     # Color for second line
            [0, 0.6, 0.2],      # Color for third line
            [0.9, 0.6, 0]       # Color for fourth line
        ],
        # Line styles and markers for colorless plots
        'bw_markers': ['-k*', '-ko', '-kv', '--kp'],
        # Line styles and markers for colorful plots
        'color_markers': ['-*', '-o', '-v', '--p']
    }
    return config