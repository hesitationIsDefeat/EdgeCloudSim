from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups().
    for group in get_plot_groups(config):
        noun = group['noun']
        folder = group['folder']

        # Group 1: Overall Processing Time
        plot_generic_line(1, 6, 'Processing Time (sec)', group['overall'], '', 'lower right',
                          metric_name='ProcessingTime', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 6, f'Processing Time for {label} {noun} (sec)', name, '', 'lower right',
                              metric_name='ProcessingTime', output_subfolder=folder)

        # # Group 2: Processing Time on Edge
        # plot_generic_line(2, 6, 'Processing Time on Edge (sec)', group['overall'], '', 'lower right',
        #                   metric_name='ProcessingTimeOnEdge', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(2, 6, f'Processing Time on Edge\nfor {label} {noun} (sec)', name, '', 'lower right',
        #                       metric_name='ProcessingTimeOnEdge', output_subfolder=folder)

        # # Group 3: Processing Time on Cloud
        # plot_generic_line(3, 6, 'Processing Time on Cloud (sec)', group['overall'], '', 'upper left',
        #                   metric_name='ProcessingTimeOnCloud', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(3, 6, f'Processing Time on Cloud\nfor {label} {noun} (sec)', name, '', 'upper left',
        #                       metric_name='ProcessingTimeOnCloud', output_subfolder=folder)
