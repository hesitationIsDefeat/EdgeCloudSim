from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Service Time Plots ---")
    config = get_configuration()
    app_names = config['application_names']
    app_labels = config['application_labels']

    # Group 1: Overall Service Time
    plot_generic_line(1, 5, 'Service Time (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTime')
    for app_name, app_label in zip(app_names, app_labels):
        plot_generic_line(1, 5, f'Service Time for\n{app_label} App (sec)', app_name, '', 'lower right', metric_name='ServiceTime')

    # # Group 2: Service Time on Edge
    # plot_generic_line(2, 5, 'Service Time on Edge (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTimeOnEdge')
    # for app_name, app_label in zip(app_names, app_labels):
    #     plot_generic_line(2, 5, f'Service Time on Edge\nfor {app_label} App (sec)', app_name, '', 'lower right', metric_name='ServiceTimeOnEdge')

    # # Group 3: Service Time on Cloud
    # plot_generic_line(3, 5, 'Service Time on Cloud (sec)', 'ALL_APPS', '', 'upper left', metric_name='ServiceTimeOnCloud')
    # for app_name, app_label in zip(app_names, app_labels):
    #     plot_generic_line(3, 5, f'Service Time on Cloud\nfor {app_label} App (sec)', app_name, '', 'upper left', metric_name='ServiceTimeOnCloud')
