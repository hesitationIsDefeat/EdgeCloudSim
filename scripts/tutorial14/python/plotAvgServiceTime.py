from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Service Time Plots ---")

    plot_generic_line(1, 5, 'Service Time (sec)', 'ALL_APPS', '', 'upper left', metric_name='ServiceTime')
    plot_generic_line(1, 5, 'Service Time for LLM Inference App (sec)', 'LLM_INFERENCE', '', 'upper left', metric_name='ServiceTime')
