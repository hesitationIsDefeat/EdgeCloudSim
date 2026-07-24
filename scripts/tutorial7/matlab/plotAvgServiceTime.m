function [] = plotAvgServiceTime()

    plotGenericLine(1, 5, 'Service Time (sec)', 'ALL_APPS', '', 'SouthEast');
    plotGenericLine(1, 5, {'Service Time for';'Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'SouthEast');
    plotGenericLine(1, 5, 'Service Time for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'SouthEast');

    plotGenericLine(2, 5, 'Service Time on Edge (sec)', 'ALL_APPS', '', 'SouthEast');
    plotGenericLine(2, 5, {'Service Time on Edge';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'SouthEast');
    plotGenericLine(2, 5, 'Service Time on Edge for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'SouthEast');

    plotGenericLine(3, 5, 'Service Time on Cloud (sec)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(3, 5, {'Service Time on Cloud';'for Disaster Map Fusion App (sec)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(3, 5, 'Service Time on Cloud for Situation Awareness Alerts App (sec)', 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');

end