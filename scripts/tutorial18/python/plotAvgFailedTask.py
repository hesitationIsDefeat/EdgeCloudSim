from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Failed Task Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups().
    for group in get_plot_groups(config):
        noun = group['noun']

        # Group 1: Overall Failed Tasks
        plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks (%)',
                          app_type=group['overall'], calculate_percentage='percentage_of_all', legend_pos='upper left',
                          metric_name='FailedTask', output_subfolder=group['folder'])
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(row_offset=1, column_offset=2, y_label=f'Failed Tasks for\n{label} {noun} (%)',
                              app_type=name, calculate_percentage='percentage_of_all', legend_pos='upper left',
                              metric_name='FailedTask', output_subfolder=group['folder'])

        # # Group 2: Failed Tasks on Edge
        # plot_generic_line(row_offset=2, column_offset=2, y_label='Failed Tasks on Edge (%)',
        #                   app_type=group['overall'], calculate_percentage='percentage_of_all', legend_pos='upper left',
        #                   metric_name='FailedTaskOnEdge', output_subfolder=group['folder'])
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(row_offset=2, column_offset=2, y_label=f'Failed Tasks on Edge for\n{label} {noun} (%)',
        #                       app_type=name, calculate_percentage='percentage_of_all', legend_pos='upper left',
        #                       metric_name='FailedTaskOnEdge', output_subfolder=group['folder'])

        # # Group 3: Failed Tasks on Cloud
        # plot_generic_line(row_offset=3, column_offset=2, y_label='Failed Tasks on Cloud (%)',
        #                   app_type=group['overall'], calculate_percentage='percentage_of_all', legend_pos='upper left',
        #                   metric_name='FailedTaskOnCloud', output_subfolder=group['folder'])
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(row_offset=3, column_offset=2, y_label=f'Failed Tasks on Cloud for\n{label} {noun} (%)',
        #                       app_type=name, calculate_percentage='percentage_of_all', legend_pos='upper left',
        #                       metric_name='FailedTaskOnCloud', output_subfolder=group['folder'])
