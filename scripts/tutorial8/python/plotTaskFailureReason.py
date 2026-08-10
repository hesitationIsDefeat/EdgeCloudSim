# plotTaskFailureReason.py
from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Task Failure Reason Plots ---")

    # Group 1: VM Capacity
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Text Message App (%)', 'TEXT_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Photo Message App (%)', 'PHOTO_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Disaster Map Fusion App (%)', 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 10, 'Failed Task due to VM Capacity\nfor Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'upper left')

    # Group 2: Mobility
    plot_generic_line(1, 11, 'Failed Task due to Mobility (%)', 'ALL_APPS', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Text Message App (%)', 'TEXT_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Photo Message App (%)', 'PHOTO_MESSAGE', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Disaster Map Fusion App (%)', 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'upper left')
    plot_generic_line(1, 11, 'Failed Task due to Mobility\nfor Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'upper left')

    # ... and so on for WLAN, MAN, WAN failures