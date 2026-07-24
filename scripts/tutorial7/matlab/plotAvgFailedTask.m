function [] = plotAvgFailedTask()

    plotGenericLine(1, 2, 'Failed Tasks (%)', 'ALL_APPS', 'percentage_of_all', 'NorthWest');
    plotGenericLine(1, 2, {'Failed Tasks for';'Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_all', 'NorthWest');
    plotGenericLine(1, 2, 'Failed Tasks for Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', 'percentage_of_all', 'NorthWest');

    plotGenericLine(2, 2, 'Failed Tasks on Edge (%)', 'ALL_APPS', 'percentage_of_all', 'NorthWest');
    plotGenericLine(2, 2, {'Failed Tasks on Edge';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_all', 'NorthWest');
    plotGenericLine(2, 2, 'Failed Tasks on Edge for Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', 'percentage_of_all', 'NorthWest');

    plotGenericLine(3, 2, 'Failed Tasks on Cloud (%)', 'ALL_APPS', 'percentage_of_all', 'NorthWest');
    plotGenericLine(3, 2, {'Failed Tasks on Cloud for';'Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', 'percentage_of_all', 'NorthWest');
    plotGenericLine(3, 2, 'Failed Tasks on Cloud for Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', 'percentage_of_all', 'NorthWest');
    
end