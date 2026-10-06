from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Network Delay Plots ---")
    config = get_configuration()
    app_names = config['application_names']
    app_labels = config['application_labels']

    # Group 1: Average Network Delay
    plot_generic_line(1, 7, 'Average Network Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='NetworkDelay')
    for app_name, app_label in zip(app_names, app_labels):
        plot_generic_line(1, 7, f'Average Network Delay\nfor {app_label} App (sec)', app_name, '', 'upper left', metric_name='NetworkDelay')

    # # Group 2: WLAN Delay
    # plot_generic_line(5, 1, 'Average WLAN Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='WlanDelay')
    # for app_name, app_label in zip(app_names, app_labels):
    #     plot_generic_line(5, 1, f'Average WLAN Delay\nfor {app_label} App (sec)', app_name, '', 'upper left', metric_name='WlanDelay')

    # # Group 3: MAN Delay
    # plot_generic_line(5, 2, 'Average MAN Delay (sec)', 'ALL_APPS', '', 'upper right', metric_name='ManDelay')
    # for app_name, app_label in zip(app_names, app_labels):
    #     plot_generic_line(5, 2, f'Average MAN Delay\nfor {app_label} App (sec)', app_name, '', 'upper right', metric_name='ManDelay')

    # # Group 4: WAN Delay
    # plot_generic_line(5, 3, 'Average WAN Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='WanDelay')
    # for app_name, app_label in zip(app_names, app_labels):
    #     plot_generic_line(5, 3, f'Average WAN Delay\nfor {app_label} App (sec)', app_name, '', 'upper left', metric_name='WanDelay')
