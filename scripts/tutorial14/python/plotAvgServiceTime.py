from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Service Time Plots ---")
    app_name = get_configuration()['application_name']

    # Group 1: Overall Service Time
    plot_generic_line(1, 5, 'Service Time (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTime')
    plot_generic_line(1, 5, f'Service Time for\n{app_name} App (sec)', app_name, '', 'lower right', metric_name='ServiceTime')

    # # Group 2: Service Time on Edge
    # plot_generic_line(2, 5, 'Service Time on Edge (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTimeOnEdge')
    # plot_generic_line(2, 5, f'Service Time on Edge\nfor {app_name} App (sec)', app_name, '', 'lower right', metric_name='ServiceTimeOnEdge')

    # # Group 3: Service Time on Cloud
    # plot_generic_line(3, 5, 'Service Time on Cloud (sec)', 'ALL_APPS', '', 'upper left', metric_name='ServiceTimeOnCloud')
    # plot_generic_line(3, 5, f'Service Time on Cloud\nfor {app_name} App (sec)', app_name, '', 'upper left', metric_name='ServiceTimeOnCloud')
