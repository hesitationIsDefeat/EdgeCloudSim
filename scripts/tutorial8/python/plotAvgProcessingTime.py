from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")

    # Group 1: Overall Processing Time
    plot_generic_line(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'lower right', metric_name='ProcessingTime')
    plot_generic_line(1, 6, 'Processing Time for Text Message App (sec)', 'TEXT_MESSAGE', '', 'lower right', metric_name='ProcessingTime')
    plot_generic_line(1, 6, 'Processing Time for Photo Message App (sec)', 'PHOTO_MESSAGE', '', 'lower right', metric_name='ProcessingTime')
    plot_generic_line(1, 6, 'Processing Time for Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'lower right', metric_name='ProcessingTime')
    plot_generic_line(1, 6, 'Processing Time for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'lower right', metric_name='ProcessingTime')

    # # Group 2: Processing Time on Edge
    # plot_generic_line(2, 6, 'Processing Time on Edge (sec)', 'ALL_APPS', '', 'lower right', metric_name='ProcessingTimeOnEdge')
    # plot_generic_line(2, 6, 'Processing Time on Edge\nfor Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'lower right', metric_name='ProcessingTimeOnEdge')
    # plot_generic_line(2, 6, 'Processing Time on Edge\nfor Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'lower right', metric_name='ProcessingTimeOnEdge')

    # # Group 3: Processing Time on Cloud
    # plot_generic_line(3, 6, 'Processing Time on Cloud (sec)', 'ALL_APPS', '', 'upper left', metric_name='ProcessingTimeOnCloud')
    # plot_generic_line(3, 6, 'Processing Time on Cloud\nfor Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'upper left', metric_name='ProcessingTimeOnCloud')
    # plot_generic_line(3, 6, 'Processing Time on Cloud\nfor Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'upper left', metric_name='ProcessingTimeOnCloud')