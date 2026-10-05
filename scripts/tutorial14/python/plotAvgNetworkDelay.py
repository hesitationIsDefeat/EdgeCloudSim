from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Network Delay Plots ---")

    plot_generic_line(1, 7, 'Average Network Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='NetworkDelay')
    plot_generic_line(1, 7, 'Average Network Delay\nfor LLM Inference App (sec)', 'LLM_INFERENCE', '', 'upper left', metric_name='NetworkDelay')
