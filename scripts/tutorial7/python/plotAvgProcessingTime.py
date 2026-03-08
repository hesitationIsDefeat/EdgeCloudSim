from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")

    # Group 1: Overall Processing Time
    plot_generic_line(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'lower right')
    plot_generic_line(1, 6, 'Processing Time for LLM Inference (sec)', 'LLM_INFERENCE', '', 'lower right')

    # Group 2: Processing Time on Edge
    plot_generic_line(2, 6, 'Processing Time on Edge (sec)', 'ALL_APPS', '', 'lower right')
    plot_generic_line(2, 6, 'Processing Time on Edge\nfor LLM Inference (sec)', 'LLM_INFERENCE', '', 'lower right')

    # Group 3: Processing Time on Cloud
    plot_generic_line(3, 6, 'Processing Time on Cloud (sec)', 'ALL_APPS', '', 'upper left')
    plot_generic_line(3, 6, 'Processing Time on Cloud\nfor LLM Inference (sec)', 'LLM_INFERENCE', '', 'upper left')