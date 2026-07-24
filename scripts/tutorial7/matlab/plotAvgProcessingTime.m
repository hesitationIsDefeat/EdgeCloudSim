function [] = plotAvgProcessingTime()

    plotGenericLine(1, 6, 'Processing Time (sec)', 'ALL_APPS', '', 'SouthEast');
    plotGenericLine(1, 6, 'Processing Time for Disaster Map Fusion App (sec)', 'DISASTER_MAP_FUSION', '', 'SouthEast');
    plotGenericLine(1, 6, 'Processing Time for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'SouthEast');

    plotGenericLine(2, 6, 'Processing Time on Edge (sec)', 'ALL_APPS', '', 'SouthEast');
    plotGenericLine(2, 6, {'Processing Time on Edge';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'SouthEast');
    plotGenericLine(2, 6, {'Processing Time on Edge';'for Situation Awareness Alerts App (sec)'}, 'SITUATION_AWARENESS_ALERTS', '', 'SouthEast');

    plotGenericLine(3, 6, 'Processing Time on Cloud (sec)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(3, 6, {'Processing Time on Cloud';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(3, 6, {'Processing Time on Cloud';'for Situation Awareness Alerts App (sec)'}, 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');
    
end