# plotTaskFailureReason.py
from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Task Failure Reason Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups().
    for group in get_plot_groups(config):
        noun = group['noun']
        folder = group['folder']

        # Group 1: VM Capacity
        plot_generic_line(1, 10, 'Failed Task due to VM Capacity (%)', group['overall'], 'percentage_of_failed', 'upper left',
                          metric_name='FailedTaskDueToVmCapacity', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 10, f'Failed Task due to VM Capacity\nfor {label} {noun} (%)', name, 'percentage_of_failed', 'upper left',
                              metric_name='FailedTaskDueToVmCapacity', output_subfolder=folder)

        # Group 2: Mobility
        plot_generic_line(1, 11, 'Failed Task due to Mobility (%)', group['overall'], 'percentage_of_failed', 'upper left',
                          metric_name='FailedTaskDueToMobility', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 11, f'Failed Task due to Mobility\nfor {label} {noun} (%)', name, 'percentage_of_failed', 'upper left',
                              metric_name='FailedTaskDueToMobility', output_subfolder=folder)

        # ... and so on for WLAN, MAN, WAN failures
