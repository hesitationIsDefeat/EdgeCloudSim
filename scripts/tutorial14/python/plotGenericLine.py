import os
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from scipy.stats import t
import warnings

# Import configuration from the config.py file
from config import get_configuration

# Suppress warnings for cleaner output
warnings.filterwarnings("ignore", category=UserWarning)

def plot_generic_line(row_offset, column_offset, y_label, app_type='ALL_APPS',
                      calculate_percentage=None, legend_pos='best', divisor=1,
                      ignore_zero_values=False, metric_name=None):
    """
    Reads simulation data, processes it, and generates a line plot.
    Equivalent to plotGenericLine.m. Unlike every other tutorial's copy, the x-axis
    here is partition count (an explicit list, e.g. [1,2,4,8,16]) rather than an
    arithmetic device-count range, and filenames end in "{x}PARTITIONS" not "{x}DEVICES".
    """
    config = get_configuration()
    
    # Extract configuration parameters
    folder_path = config['folder_path']
    output_folder_path = config['output_folder_path']
    num_simulations = config['num_iterations']
    scenarios = config['scenario_types']
    x_values = config['x_values']
    orchestrator_policy = config['orchestrator_policy']
    
    num_x_steps = len(x_values)
    
    # Array to store all simulation results
    all_results = np.zeros((num_simulations, len(scenarios), num_x_steps))
    missing_files = {scenario: 0 for scenario in scenarios}
    first_used_file = {scenario: None for scenario in scenarios}
    
    # --- Data Reading Loop ---
    for s in range(1, num_simulations + 1):  # Iterations are 1-based
        for i, scenario in enumerate(scenarios):
            for j, x_value in enumerate(x_values):
                try:
                    file_name = f'SIMRESULT_DEFAULT_SCENARIO_{orchestrator_policy}_{scenario}_{x_value}PARTITIONS_{app_type}_GENERIC.log'
                    file_path = os.path.join(folder_path, f'ite{s}', file_name)

                    if first_used_file[scenario] is None and os.path.isfile(file_path):
                        first_used_file[scenario] = file_path
                    
                    # Read the specific value from the log file
                    data = pd.read_csv(file_path, sep=';', header=None, skiprows=row_offset, nrows=1)
                    value = data.iloc[0, column_offset - 1] # Convert to 0-based index
                    
                    # --- Percentage Calculation ---
                    if calculate_percentage == 'percentage_of_all':
                        total_data = pd.read_csv(file_path, sep=';', header=None, skiprows=1, nrows=1)
                        total_tasks = total_data.iloc[0, 0] + total_data.iloc[0, 1]
                        value = (100 * value) / total_tasks if total_tasks > 0 else 0
                    
                    all_results[s-1, i, j] = value
                except FileNotFoundError:
                    print(f"Warning: File not found -> {file_path}")
                    all_results[s-1, i, j] = np.nan
                    missing_files[scenario] += 1
                except Exception as e:
                    print(f"Error reading {file_path}: {e}")
                    all_results[s-1, i, j] = np.nan

    # Safety summary to make stale/mixed inputs obvious before plotting.
    print(f"[plot source] app={app_type}, row={row_offset}, col={column_offset}")
    for scenario in scenarios:
        sample = first_used_file[scenario] if first_used_file[scenario] is not None else "NO_VALID_FILE_FOUND"
        print(f"  scenario={scenario} sample_file={sample} missing_files={missing_files[scenario]}")

    # --- Data Aggregation ---
    if num_simulations == 1:
        results = np.squeeze(all_results)
    else:
        results = np.nanmean(all_results, axis=0)

    results /= divisor

    # --- Confidence Interval Calculation (95%) ---
    mean_results = np.nanmean(all_results, axis=0) / divisor
    min_ci_vals = np.zeros_like(mean_results)
    max_ci_vals = np.zeros_like(mean_results)

    if num_simulations > 1:
        for i in range(len(scenarios)):
            for j in range(num_x_steps):
                data_slice = all_results[:, i, j][~np.isnan(all_results[:, i, j])] / divisor
                if len(data_slice) > 1:
                    std_err = np.std(data_slice, ddof=1) / np.sqrt(len(data_slice))
                    if np.isfinite(std_err) and np.isfinite(mean_results[i, j]):
                        ci_margin = t.ppf(0.975, len(data_slice) - 1) * std_err
                        min_ci_vals[i, j] = ci_margin
                        max_ci_vals[i, j] = ci_margin

    # --- Plotting ---
    fig, ax = plt.subplots()
    fig_pos_cm = config['figure_position']
    fig.set_size_inches(fig_pos_cm[2] / 2.54, fig_pos_cm[3] / 2.54) # Convert cm to inches
    font_sizes = config['font_sizes']
    plt.rcParams.update({'font.family': 'Times New Roman'})
    
    legends = config['legends']
    
    for i in range(len(scenarios)):
        color = config['colors'][i] if config['use_color'] else 'k'
        marker_style = config['color_markers'][i] if config['use_color'] else config['bw_markers'][i]
        
        if config['plot_confidence_interval']:
            ax.errorbar(x_values, results[i, :], yerr=[min_ci_vals[i, :], max_ci_vals[i, :]],
                        label=legends[i], color=color, fmt=marker_style, capsize=3)
        else:
            ax.plot(x_values, results[i, :], marker_style, label=legends[i], color=color)

    ax.set_xlabel(config['x_axis_label'], fontsize=font_sizes[0])
    ax.set_ylabel(y_label, fontsize=font_sizes[0])
    ax.legend(fontsize=font_sizes[1], loc=legend_pos)
    ax.tick_params(axis='both', which='major', labelsize=font_sizes[2])
    # ONAT: partition counts double each step (1,2,4,8,16) - a log2 x-axis spaces them
    # evenly instead of crushing the low end of a linear axis.
    ax.set_xscale('log', base=2)
    ax.set_xticks(x_values)
    ax.set_xticklabels([str(x) for x in x_values])
    ax.set_ylim(bottom=0)
    ax.grid(True, linestyle='--', alpha=0.6)
    fig.tight_layout()

    # --- Save Figure ---
    if config['save_figure_as_pdf']:
        safe_app_type = app_type.replace(' ', '_')
        # ONAT: fall back to the raw row/column offsets only if the caller didn't
        # provide a descriptive metric name.
        safe_metric_name = (metric_name or f"{row_offset}_{column_offset}").replace(' ', '_')
        filename = f"{safe_metric_name}_{safe_app_type}.pdf"
        output_path = os.path.join(output_folder_path, filename)
        os.makedirs(output_folder_path, exist_ok=True)
        fig.savefig(output_path, bbox_inches='tight')
        print(f"Figure saved to {output_path}")

    plt.show()
