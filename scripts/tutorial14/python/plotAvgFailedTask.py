from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Failed Task Plots ---")

    # Group 1: Overall Failed Tasks
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks (%)',
                      app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTask')
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks for\nLLM Inference App (%)',
                      app_type='LLM_INFERENCE', calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTask')
