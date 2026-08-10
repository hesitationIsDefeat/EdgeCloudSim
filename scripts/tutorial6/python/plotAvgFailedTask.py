from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Average Failed Task Plots ---")
    
    # Group 1: Overall Failed Tasks
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks (%)',
                      app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left')
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks for\nText Message App (%)',
                      app_type='TEXT_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')
    plot_generic_line(row_offset=1, column_offset=2, y_label='Failed Tasks for Photo Message App (%)',
                      app_type='PHOTO_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')

    # # Group 2: Failed Tasks on Edge
    # plot_generic_line(row_offset=2, column_offset=2, y_label='Failed Tasks on Edge (%)',
    #                   app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left')
    # plot_generic_line(row_offset=2, column_offset=2, y_label='Failed Tasks on Edge for\nText Message App (%)',
    #                   app_type='TEXT_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')
    # plot_generic_line(row_offset=2, column_offset=2, y_label='Failed Tasks on Edge for Photo Message App (%)',
    #                   app_type='PHOTO_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')

    # # Group 3: Failed Tasks on Cloud
    # plot_generic_line(row_offset=3, column_offset=2, y_label='Failed Tasks on Cloud (%)',
    #                   app_type='ALL_APPS', calculate_percentage='percentage_of_all', legend_pos='upper left')
    # plot_generic_line(row_offset=3, column_offset=2, y_label='Failed Tasks on Cloud for\nText Message App (%)',
    #                   app_type='TEXT_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')
    # plot_generic_line(row_offset=3, column_offset=2, y_label='Failed Tasks on Cloud for Photo Message App (%)',
    #                   app_type='PHOTO_MESSAGE', calculate_percentage='percentage_of_all', legend_pos='upper left')