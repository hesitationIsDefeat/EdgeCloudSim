from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Failed Task Plots ---")
    app_name = get_configuration()['application_name']

    # Group 1: Overall Failed Tasks
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks (%)',
                      app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTask')
    plot_generic_line(row_offset=1, column_offset=2, y_label=f'Failed Tasks for\n{app_name} App (%)',
                      app_type=app_name, calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTask')

    # # Group 2: Failed Tasks on Edge
    # plot_generic_line(row_offset=2, column_offset=2, y_label='Failed Tasks on Edge (%)',
    #                   app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTaskOnEdge')
    # plot_generic_line(row_offset=2, column_offset=2, y_label=f'Failed Tasks on Edge for\n{app_name} App (%)',
    #                   app_type=app_name, calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTaskOnEdge')

    # # Group 3: Failed Tasks on Cloud
    # plot_generic_line(row_offset=3, column_offset=2, y_label='Failed Tasks on Cloud (%)',
    #                   app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTaskOnCloud')
    # plot_generic_line(row_offset=3, column_offset=2, y_label=f'Failed Tasks on Cloud for\n{app_name} App (%)',
    #                   app_type=app_name, calculate_percentage='percentage_of_all', legend_pos='upper left', metric_name='FailedTaskOnCloud')
