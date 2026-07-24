function [] = plotTaskFailureReason()

    plotGenericLine(1, 10, 'Failed Task due to VM Capacity (%)', 'ALL_APPS', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(1, 10, {'Failed Task due to VM Capacity';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(1, 10, {'Failed Task due to VM Capacity';'for Situation Awareness Alerts App (%)'}, 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'NorthWest');

    plotGenericLine(1, 11, 'Failed Task due to Mobility (%)', 'ALL_APPS', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(1, 11, {'Failed Task due to Mobility';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(1, 11, {'Failed Task due to Mobility';'for Situation Awareness Alerts App (%)'}, 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'NorthWest');

    plotGenericLine(5, 5, 'Failed Tasks due to WLAN failure (%)', 'ALL_APPS', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 5, {'Failed Tasks due to WLAN failure';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 5, {'Failed Tasks due to WLAN failure';'for Situation Awareness Alerts App (%)'}, 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'NorthWest');

    plotGenericLine(5, 6, 'Failed Tasks due to MAN failure (%)', 'ALL_APPS', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 6, {'Failed Tasks due to MAN failure';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 6, {'Failed Tasks due to MAN failure';'for Situation Awareness Alerts App (%)'}, 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'NorthWest');

    plotGenericLine(5, 7, 'Failed Tasks due to WAN failure (%)', 'ALL_APPS', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 7, {'Failed Tasks due to WAN failure';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_failed', 'NorthWest');
    plotGenericLine(5, 7, {'Failed Tasks due to WAN failure';'for Situation Awareness Alerts App (%)'}, 'SITUATION_AWARENESS_ALERTS', 'percentage_of_failed', 'NorthWest');

end