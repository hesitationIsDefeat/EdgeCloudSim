from config import get_configuration, get_plot_groups
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average VM Utilization Plots ---")
    config = get_configuration()

    # ONAT: identical plots for every group (applications requested on their own / streams),
    # each into its own sub-folder - see config.get_plot_groups(). The VM utilization is a
    # property of the whole run, so every file (and thus every plot) holds the same value.
    for group in get_plot_groups(config):
        noun = group['noun']
        folder = group['folder']

        # Group 1: VM Utilization on Edge
        plot_generic_line(2, 8, 'Average VM Utilization (%)', group['overall'], '', 'upper left',
                          metric_name='VmUtilization', output_subfolder=folder)
        for name, label in zip(group['names'], group['labels']):
            plot_generic_line(2, 8, f'Average VM Utilization\nfor {label} {noun} (%)', name, '', 'upper left',
                              metric_name='VmUtilization', output_subfolder=folder)
