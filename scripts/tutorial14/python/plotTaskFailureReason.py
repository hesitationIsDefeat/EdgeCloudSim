# plotTaskFailureReason.py
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Task Failure Reason Plots ---")

    # Group 1: VM Capacity
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToVmCapacity')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor LLM Inference App (%)', 'LLM_INFERENCE', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToVmCapacity')

    # Group 2: Mobility
    plot_generic_line(1, 11, 'Failed Task due to Mobility (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToMobility')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor LLM Inference App (%)', 'LLM_INFERENCE', 'percentage_of_failed', 'upper left', metric_name='FailedTaskDueToMobility')

    # ... and so on for WLAN, MAN, WAN failures
