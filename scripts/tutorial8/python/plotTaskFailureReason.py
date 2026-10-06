# plotTaskFailureReason.py
from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Task Failure Reason Plots ---")
    config = get_configuration()
    app_names = config['application_names']
    app_labels = config['application_labels']

    # Group 1: VM Capacity
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToVmCapacity')
    for app_name, app_label in zip(app_names, app_labels):
        plot_generic_line(1, 10, f'Failed Task due to VM Capacity\nfor {app_label} App (%)', app_name, 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToVmCapacity')

    # Group 2: Mobility
    plot_generic_line(1, 11, 'Failed Task due to Mobility (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToMobility')
    for app_name, app_label in zip(app_names, app_labels):
        plot_generic_line(1, 11, f'Failed Task due to Mobility\nfor {app_label} App (%)', app_name, 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToMobility')

    # ... and so on for WLAN, MAN, WAN failures