from plotGenericLine import plot_generic_line

if __name__ == '__main__':
    print("--- Generating: Time Complexity Plots ---")

    # ONAT: run-wide values (simulation time, orchestration overhead), not per application or
    # stream, so these go to the output folder itself rather than a group's sub-folder. They
    # are read from the ALL_APPS aggregate, which holds the same run duration as ALL_STREAMS.

    plot_generic_line(6, 1, 'Simulation Time (minute)', 'ALL_APPS', '', 'upper left', divisor=60, metric_name='SimulationTime')
    plot_generic_line(6, 2, 'Orchestration Algorithm Overhead (μs)', 'ALL_APPS', '', 'upper right', divisor=1000, metric_name='OrchestrationOverhead')
