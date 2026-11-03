from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")
    app_name = get_configuration()['application_name']

    # Group 1: Overall Processing Time
    plot_generic_line(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'lower right', metric_name='ProcessingTime')
    plot_generic_line(1, 6, f'Processing Time for {app_name} App (sec)', app_name, '', 'lower right', metric_name='ProcessingTime')

    # # Group 2: Processing Time on Edge
    # plot_generic_line(2, 6, 'Processing Time on Edge (sec)', 'ALL_APPS', '', 'lower right', metric_name='ProcessingTimeOnEdge')
    # plot_generic_line(2, 6, f'Processing Time on Edge\nfor {app_name} App (sec)', app_name, '', 'lower right', metric_name='ProcessingTimeOnEdge')

    # # Group 3: Processing Time on Cloud
    # plot_generic_line(3, 6, 'Processing Time on Cloud (sec)', 'ALL_APPS', '', 'upper left', metric_name='ProcessingTimeOnCloud')
    # plot_generic_line(3, 6, f'Processing Time on Cloud\nfor {app_name} App (sec)', app_name, '', 'upper left', metric_name='ProcessingTimeOnCloud')
