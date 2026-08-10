from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Processing Time Plots ---")

    # Group 1: Overall Processing Time
    plot_generic_line(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'lower right')
    plot_generic_line(1, 6, 'Processing Time for Text Message App (sec)', 'TEXT_MESSAGE', '', 'lower right')
    plot_generic_line(1, 6, 'Processing Time for Photo Message App (sec)', 'PHOTO_MESSAGE', '', 'lower right')

    # # Group 2: Processing Time on Edge
    # plot_generic_line(2, 6, 'Processing Time on Edge (sec)', 'ALL_APPS', '', 'lower right')
    # plot_generic_line(2, 6, 'Processing Time on Edge\nfor Text Message App (sec)', 'TEXT_MESSAGE', '', 'lower right')
    # plot_generic_line(2, 6, 'Processing Time on Edge\nfor Photo Message App (sec)', 'PHOTO_MESSAGE', '', 'lower right')

    # # Group 3: Processing Time on Cloud
    # plot_generic_line(3, 6, 'Processing Time on Cloud (sec)', 'ALL_APPS', '', 'upper left')
    # plot_generic_line(3, 6, 'Processing Time on Cloud\nfor Text Message App (sec)', 'TEXT_MESSAGE', '', 'upper left')
    # plot_generic_line(3, 6, 'Processing Time on Cloud\nfor Photo Message App (sec)', 'PHOTO_MESSAGE', '', 'upper left')