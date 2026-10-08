from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average VM Utilization Plots ---")
    app_name = get_configuration()['application_name']

    # Group 1: VM Utilization on Edge
    plot_generic_line(2, 8, 'Average VM Utilization (%)', 'ALL_APPS', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, f'Average VM Utilization\nfor {app_name} App (%)', app_name, '', 'upper left', metric_name='VmUtilization')
