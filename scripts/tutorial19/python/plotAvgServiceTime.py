from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Service Time Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups().
    for group in get_plot_groups(config):
        noun = group['noun']
        folder = group['folder']

        # Group 1: Overall Service Time
        plot_generic_line(1, 5, 'Service Time (sec)', group['overall'], '', 'lower right',
                          metric_name='ServiceTime', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 5, f'Service Time for\n{label} {noun} (sec)', name, '', 'lower right',
                              metric_name='ServiceTime', output_subfolder=folder)

        # # Group 2: Service Time on Edge
        # plot_generic_line(2, 5, 'Service Time on Edge (sec)', group['overall'], '', 'lower right',
        #                   metric_name='ServiceTimeOnEdge', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(2, 5, f'Service Time on Edge\nfor {label} {noun} (sec)', name, '', 'lower right',
        #                       metric_name='ServiceTimeOnEdge', output_subfolder=folder)

        # # Group 3: Service Time on Cloud
        # plot_generic_line(3, 5, 'Service Time on Cloud (sec)', group['overall'], '', 'upper left',
        #                   metric_name='ServiceTimeOnCloud', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(3, 5, f'Service Time on Cloud\nfor {label} {noun} (sec)', name, '', 'upper left',
        #                       metric_name='ServiceTimeOnCloud', output_subfolder=folder)
