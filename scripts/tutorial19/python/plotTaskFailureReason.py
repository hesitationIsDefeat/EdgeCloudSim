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

        # Group 2: Bandwidth (a UAV's bandwidth is shared by the users it serves, so crowds can exhaust it).
        # ONAT: failures due to mobility are not plotted - they never happen in tutorial19, where a request
        # is only dropped for finding no in-range UAV with the service and free capacity (VM capacity) or for
        # bandwidth. Nor does any request time out (the admission check keeps the UAV queues short), which is
        # just as well: SimLogger writes no timeout column to GENERIC.log, so if task_timeout is ever made
        # short enough to time requests out, the VM capacity and bandwidth shares no longer add up to 100%.
        plot_generic_line(1, 4, 'Failed Task due to Bandwidth (%)', group['overall'], 'percentage_of_failed', 'upper left',
                          metric_name='FailedTaskDueToBandwidth', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 4, f'Failed Task due to Bandwidth\nfor {label} {noun} (%)', name, 'percentage_of_failed', 'upper left',
                              metric_name='FailedTaskDueToBandwidth', output_subfolder=folder)
