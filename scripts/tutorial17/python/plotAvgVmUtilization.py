from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average VM Utilization Plots ---")
    config = get_configuration()
    app_names = config['application_names']
    app_labels = config['application_labels']

    # Group 1: VM Utilization on Edge
    plot_generic_line(2, 8, 'Average VM Utilization (%)', 'ALL_APPS', '', 'upper left', metric_name='VmUtilization')
    for app_name, app_label in zip(app_names, app_labels):
        plot_generic_line(2, 8, f'Average VM Utilization\nfor {app_label} App (%)', app_name, '', 'upper left', metric_name='VmUtilization')
