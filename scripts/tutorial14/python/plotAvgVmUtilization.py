from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average VM Utilization Plots ---")

    plot_generic_line(2, 8, 'Average VM Utilization (%)', 'ALL_APPS', '', 'upper left', metric_name='VmUtilization')
    plot_generic_line(2, 8, 'Average VM Utilization for LLM Inference App (%)', 'LLM_INFERENCE', '', 'upper left', metric_name='VmUtilization')
