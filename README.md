# CS6650 Assignment 1: WebSocket Chat Server and Load Testing Client

This repository contains the implementation for Assignment 1, including a WebSocket chat server and two load testing clients.

## Repository Structure

```
/
|-- server/                    # Spring Boot WebSocket server implementation
|   |-- README.md             # Server deployment and running instructions
|   `-- src/                  # Source code
|-- client/
|   |-- client_part1/         # Basic load testing client
|   |   |-- README.md         # Client Part 1 instructions
|   |   |-- src/              # Source code
|   |   `-- src/main/resources/client.properties  # Configuration file
|   `-- client_part2/         # Client with performance analysis
|       |-- README.md         # Client Part 2 instructions
|       |-- src/              # Source code
|       `-- src/main/resources/client.properties  # Configuration file
|-- results/                  # Test results and analysis
|   |-- README.md             # Results documentation
|   |-- throughput_over_time.csv  # Throughput data (10-second buckets)
|   `-- generate_throughput_chart.py  # Chart generation script
|-- README.md                 # Main repository README (this file)
`-- DesignDocument.md         # Architecture and design document (2 pages max)
```

## Quick Start

### Server

```bash
cd server
mvn clean package
java -jar target/chat-server-0.0.1-SNAPSHOT.jar
```

Server runs on port 8080. See `server/README.md` for detailed deployment instructions.

### Client Part 1 (Basic Load Testing)

```bash
cd client/client_part1
mvn clean compile
mvn exec:java -Dexec.args="http://your-server:8080"
```

See `client/client_part1/README.md` for detailed instructions.

### Client Part 2 (Performance Analysis)

```bash
cd client/client_part2
mvn clean compile
mvn exec:java -Dexec.args="http://your-server:8080"
```

See `client/client_part2/README.md` for detailed instructions.

## Configuration

- **Server**: `server/src/main/resources/application.properties`
- **Client Part 1**: `client/client_part1/src/main/resources/client.properties`
- **Client Part 2**: `client/client_part2/src/main/resources/client.properties`

## Test Results

Test results, screenshots, and performance analysis charts are stored in the `results/` directory.

See `results/README.md` for information about:
- Throughput data export
- Chart generation
- Performance analysis

## Design Document

See `DesignDocument.md` for:
- Architecture diagram
- Major classes and relationships
- Threading model
- WebSocket connection management
- Little's Law calculations

## Git Repository

Include this repository URL in your submission:
- Repository structure matches the structure shown above
- All README files contain clear running instructions
- Design document is complete and within 2 pages
