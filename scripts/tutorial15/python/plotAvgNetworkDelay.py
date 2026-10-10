from config import get_configuration
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Network Delay Plots ---")
    app_name = get_configuration()['application_name']

    # Group 1: Average Network Delay
    plot_generic_line(1, 7, 'Average Network Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='NetworkDelay')
    plot_generic_line(1, 7, f'Average Network Delay\nfor {app_name} App (sec)', app_name, '', 'upper left', metric_name='NetworkDelay')

    # # Group 2: WLAN Delay
    # plot_generic_line(5, 1, 'Average WLAN Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='WlanDelay')
    # plot_generic_line(5, 1, f'Average WLAN Delay\nfor {app_name} App (sec)', app_name, '', 'upper left', metric_name='WlanDelay')

    # # Group 3: MAN Delay
    # plot_generic_line(5, 2, 'Average MAN Delay (sec)', 'ALL_APPS', '', 'upper right', metric_name='ManDelay')
    # plot_generic_line(5, 2, f'Average MAN Delay\nfor {app_name} App (sec)', app_name, '', 'upper right', metric_name='ManDelay')

    # # Group 4: WAN Delay
    # plot_generic_line(5, 3, 'Average WAN Delay (sec)', 'ALL_APPS', '', 'upper left', metric_name='WanDelay')
    # plot_generic_line(5, 3, f'Average WAN Delay\nfor {app_name} App (sec)', app_name, '', 'upper left', metric_name='WanDelay')
