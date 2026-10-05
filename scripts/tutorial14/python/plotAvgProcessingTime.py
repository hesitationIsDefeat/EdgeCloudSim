from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")

    plot_generic_line(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'upper left', metric_name='ProcessingTime')
    plot_generic_line(1, 6, 'Processing Time for LLM Inference App (sec)', 'LLM_INFERENCE', '', 'upper left', metric_name='ProcessingTime')
