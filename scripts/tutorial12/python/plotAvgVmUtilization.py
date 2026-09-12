from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average VM Utilization Plots ---")

    # Group 1: VM Utilization on Edge
    plot_generic_line(2, 8, 'Average VM Utilization (%)', 'ALL_APPS', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, 'Average VM Utilization\nfor Text Message App (%)', 'TEXT_MESSAGE', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, 'Average VM Utilization for Photo Message App (%)', 'PHOTO_MESSAGE', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, 'Average VM Utilization\nfor Disaster Map Fusion App (%)', 'DISASTER_MAP_FUSION', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, 'Average VM Utilization for Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', '', 'upper left', metric_name='VmUtilization')
