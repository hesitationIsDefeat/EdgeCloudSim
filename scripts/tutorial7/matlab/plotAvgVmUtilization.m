function [] = plotAvgVmUtilization()

    plotGenericLine(2, 8, 'Average VM Utilization (%)', 'ALL_APPS', '', 'NorthWest');
    plotGenericLine(2, 8, {'Average VM Utilization';'for Disaster Map Fusion App (%)'}, 'DISASTER_MAP_FUSION', '', 'NorthWest');
    plotGenericLine(2, 8, 'Average VM Utilization for Situation Awareness Alerts App (%)', 'SITUATION_AWARENESS_ALERTS', '', 'NorthWest');

end