from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Service Time Plots ---")

    # Group 1: Overall Service Time
    plot_generic_line(1, 5, 'Service Time (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTime')
    plot_generic_line(1, 5, 'Service Time for\nText Message App (sec)', 'TEXT_MESSAGE', '', 'lower right', metric_name='ServiceTime')
    plot_generic_line(1, 5, 'Service Time for Photo Message App (sec)', 'PHOTO_MESSAGE', '', 'lower right', metric_name='ServiceTime')
    plot_generic_line(1, 5, 'Service Time for\nDisaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'lower right', metric_name='ServiceTime')
    plot_generic_line(1, 5, 'Service Time for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'lower right', metric_name='ServiceTime')

    # # Group 2: Service Time on Edge
    # plot_generic_line(2, 5, 'Service Time on Edge (sec)', 'ALL_APPS', '', 'lower right', metric_name='ServiceTimeOnEdge')
    # plot_generic_line(2, 5, 'Service Time on Edge\nfor Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'lower right', metric_name='ServiceTimeOnEdge')
    # plot_generic_line(2, 5, 'Service Time on Edge for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'lower right', metric_name='ServiceTimeOnEdge')

    # # Group 3: Service Time on Cloud
    # plot_generic_line(3, 5, 'Service Time on Cloud (sec)', 'ALL_APPS', '', 'upper left', metric_name='ServiceTimeOnCloud')
    # plot_generic_line(3, 5, 'Service Time on Cloud\nfor Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'upper left', metric_name='ServiceTimeOnCloud')
    # plot_generic_line(3, 5, 'Service Time on Cloud for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'upper left', metric_name='ServiceTimeOnCloud')