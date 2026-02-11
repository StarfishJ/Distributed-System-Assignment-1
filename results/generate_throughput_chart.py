#!/usr/bin/env python3
"""
Generate a simple line chart showing throughput over time from CSV data.
Usage: python generate_throughput_chart.py [throughput_over_time.csv]
"""

import sys
import csv
import matplotlib.pyplot as plt
from pathlib import Path

def read_csv(filename):
    """Read throughput data from CSV file."""
    time_points = []
    throughput_values = []
    
    try:
        with open(filename, 'r') as f:
            reader = csv.DictReader(f)
            for row in reader:
                time_points.append(int(row['Time (seconds)']))
                throughput_values.append(float(row['Throughput (msg/s)']))
    except FileNotFoundError:
        print(f"Error: File '{filename}' not found.")
        print("Make sure you've run the client and it generated the CSV file.")
        sys.exit(1)
    except Exception as e:
        print(f"Error reading CSV file: {e}")
        sys.exit(1)
    
    return time_points, throughput_values

def generate_chart(time_points, throughput_values, output_file='throughput_chart.png'):
    """Generate a line chart showing throughput over time."""
    plt.figure(figsize=(10, 6))
    plt.plot(time_points, throughput_values, marker='o', linestyle='-', linewidth=2, markersize=4)
    plt.xlabel('Time (seconds)', fontsize=12)
    plt.ylabel('Throughput (messages/second)', fontsize=12)
    plt.title('Throughput Over Time (10-second buckets)', fontsize=14, fontweight='bold')
    plt.grid(True, alpha=0.3)
    plt.tight_layout()
    
    # Save the chart
    plt.savefig(output_file, dpi=300, bbox_inches='tight')
    print(f"Chart saved to: {output_file}")
    
    # Also show the chart if running interactively
    try:
        plt.show()
    except:
        pass  # If display is not available, just save the file

def main():
    # Default CSV file path
    csv_file = 'throughput_over_time.csv'
    
    # Check if custom path provided
    if len(sys.argv) > 1:
        csv_file = sys.argv[1]
    
    # Check if file exists
    if not Path(csv_file).exists():
        print(f"Error: CSV file '{csv_file}' not found.")
        print("\nTo generate the CSV file:")
        print("1. Run the Part 2 client: cd client/client_part2 && mvn exec:java -Dexec.args=\"http://your-server:8080\"")
        print("2. The CSV file will be generated in the results/ directory")
        sys.exit(1)
    
    # Read data
    print(f"Reading data from: {csv_file}")
    time_points, throughput_values = read_csv(csv_file)
    
    if not time_points:
        print("Error: No data found in CSV file.")
        sys.exit(1)
    
    print(f"Loaded {len(time_points)} data points")
    print(f"Time range: {min(time_points)}s to {max(time_points)}s")
    print(f"Throughput range: {min(throughput_values):.2f} to {max(throughput_values):.2f} msg/s")
    
    # Generate chart
    output_file = Path(csv_file).parent / 'throughput_chart.png'
    generate_chart(time_points, throughput_values, str(output_file))

if __name__ == '__main__':
    main()
