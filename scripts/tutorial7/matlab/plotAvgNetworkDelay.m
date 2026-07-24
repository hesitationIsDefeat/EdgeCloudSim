function [] = plotAvgNetworkDelay()

    plotGenericLine(1, 7, 'Average Network Delay (sec)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(1, 7, {'Average Network Delay';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(1, 7, 'Average Network Delay for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');

    plotGenericLine(5, 1, 'Average WLAN Delay (sec)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(5, 1, {'Average WLAN Delay';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(5, 1, 'Average WLAN Delay for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');

    plotGenericLine(5, 2, 'Average MAN Delay (sec)', 'ALL_APPS', '', 'NorthEast');
    plotGenericLine(5, 2, {'Average MAN Delay';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthEast');
    plotGenericLine(5, 2, 'Average MAN Delay for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'SouthWest');

    plotGenericLine(5, 3, 'Average WAN Delay (sec)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(5, 3, {'Average WAN Delay';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(5, 3, 'Average WAN Delay for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');
    
end