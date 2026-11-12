from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Network Delay Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups().
    for group in get_plot_groups(config):
        noun = group['noun']
        folder = group['folder']

        # Group 1: Average Network Delay
        plot_generic_line(1, 7, 'Average Network Delay (sec)', group['overall'], '', 'upper left',
                          metric_name='NetworkDelay', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(1, 7, f'Average Network Delay\nfor {label} {noun} (sec)', name, '', 'upper left',
                              metric_name='NetworkDelay', output_subfolder=folder)

        # # Group 2: WLAN Delay
        # plot_generic_line(5, 1, 'Average WLAN Delay (sec)', group['overall'], '', 'upper left',
        #                   metric_name='WlanDelay', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(5, 1, f'Average WLAN Delay\nfor {label} {noun} (sec)', name, '', 'upper left',
        #                       metric_name='WlanDelay', output_subfolder=folder)

        # # Group 3: MAN Delay
        # plot_generic_line(5, 2, 'Average MAN Delay (sec)', group['overall'], '', 'upper right',
        #                   metric_name='ManDelay', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(5, 2, f'Average MAN Delay\nfor {label} {noun} (sec)', name, '', 'upper right',
        #                       metric_name='ManDelay', output_subfolder=folder)

        # # Group 4: WAN Delay
        # plot_generic_line(5, 3, 'Average WAN Delay (sec)', group['overall'], '', 'upper left',
        #                   metric_name='WanDelay', output_subfolder=folder)
        # for name, label in zip(group['names'], group['labels']):
        #     plot_generic_line(5, 3, f'Average WAN Delay\nfor {label} {noun} (sec)', name, '', 'upper left',
        #                       metric_name='WanDelay', output_subfolder=folder)
