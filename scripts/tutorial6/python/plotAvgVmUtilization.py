from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Task Failure Reason Plots ---")

    # Group 1: VM Capacity
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Text Message App (%)', 'TEXT_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Photo Message App (%)', 'PHOTO_MESSAGE', 'percentage_of_failed', 'upper left')

    # Group 2: Mobility
    plot_generic_line(1, 11, 'Failed Task due to Mobility (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Text Message App (%)', 'TEXT_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Photo Message App (%)', 'PHOTO_MESSAGE', 'percentage_of_failed', 'upper left')

    # ... and so on for WLAN, MAN, WAN failures