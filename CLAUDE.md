# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is Aiven's OpenSearch Sink Connector for Apache Kafka - a Kafka Connect plugin that streams data from Kafka topics to OpenSearch indices. The project is written in Java and uses Gradle for build management.

Key technologies:
- Java 11 (source/target compatibility)
- Apache Kafka Connect API
- OpenSearch Java High Level REST Client
- Gradle 8.x with Gradle Wrapper
- JUnit 5 for testing
- Testcontainers for integration tests

## Essential Commands

### Building and Testing
```bash
# Build the project and run all tests
./gradlew build

# Run unit tests only  
./gradlew test

# Run integration tests (requires Docker)
./gradlew integrationTest

# Run all checks (tests + code quality)
./gradlew check

# Build distribution packages
./gradlew installDist        # Creates build/install/ directory
./gradlew assembleDist       # Creates zip/tar distributions
```

### Code Quality and Formatting
```bash
# Check code formatting
./gradlew spotlessCheck

# Apply code formatting
./gradlew spotlessApply

# Generate test coverage report
./gradlew jacocoTestReport   # Report in build/reports/jacoco/test/html/
```

### Development Tasks
```bash
# Generate connector configuration documentation
./gradlew connectorConfigDoc

# Run single test class
./gradlew test --tests "OpensearchSinkTaskTest"

# Run tests with specific system properties
./gradlew integrationTest -Popensearch.testcontainers.image-version=2.1.0
```

## Architecture Overview

### Core Components

**OpensearchSinkConnector** (`src/main/java/.../OpensearchSinkConnector.java`)
- Main connector class extending Kafka Connect's SinkConnector
- Handles connector lifecycle and configuration validation
- Creates task configurations for parallel processing

**OpensearchSinkTask** (`src/main/java/.../OpensearchSinkTask.java`)  
- Core task implementation extending SinkTask
- Processes batches of Kafka records and writes to OpenSearch
- Handles index/data stream creation and mapping management
- Implements retry logic and error handling

**OpensearchClient** (`src/main/java/.../OpensearchClient.java`)
- Abstraction layer over OpenSearch REST High Level Client
- Handles bulk operations, index management, and connection pooling
- Implements configurable retry and backoff strategies

**RecordConverter** (`src/main/java/.../RecordConverter.java`)
- Converts Kafka Connect SinkRecords to OpenSearch documents
- Handles schema evolution and field mapping
- Supports various data transformation options

### Extension Points (SPI)

The connector supports Service Provider Interface (SPI) for extensibility:

**OpensearchClientConfigurator** (`src/main/java/.../spi/OpensearchClientConfigurator.java`)
- Interface for customizing the HTTP client configuration
- Implementations registered via `META-INF/services/` files
- Default implementation: `OpensearchBasicAuthConfigurator`

**ConfigDefContributor** (`src/main/java/.../spi/ConfigDefContributor.java`)
- Interface for contributing additional configuration options
- Allows extending connector configuration without modifying core code

### Configuration System

Configuration is centralized in `OpensearchSinkConnectorConfig` with:
- Hierarchical configuration validation
- Type-safe configuration access methods
- Built-in configuration documentation generation
- Support for sensitive configuration handling

### Testing Strategy

**Unit Tests** (`src/test/java/`)
- Mock-based testing of individual components
- Fast execution, no external dependencies
- JUnit 5 with Mockito

**Integration Tests** (`src/integration-test/java/`)
- Full end-to-end testing with real OpenSearch and Kafka
- Uses Testcontainers for reproducible test environments
- Tests connector deployment and data pipeline functionality

## Development Guidelines

### Code Style
- Uses Spotless plugin for consistent formatting
- Eclipse formatter configuration in `gradle-config/aiven-eclipse-formatter.xml`
- Import order: javax, java, org.apache.kafka, org.opensearch, io.aiven, others
- License headers required on all Java files

### Testing Requirements
- Unit tests for all public methods and business logic
- Integration tests for connector functionality and OpenSearch interactions
- All tests must pass before merge (`./gradlew check`)
- Aim for high test coverage (measured with JaCoCo)

### Configuration Changes
- Update `OpensearchSinkConnectorConfig` for new config options
- Regenerate documentation: `./gradlew connectorConfigDoc`
- Update `docs/opensearch-sink-connector-config-options.rst`

### SPI Extensions
- Implement appropriate SPI interface
- Register implementation in `META-INF/services/` file
- Add tests in both `src/test/` and `src/integration-test/`
- Follow existing patterns (see `OpensearchBasicAuthConfigurator`)

## Key Configuration Files

- `config/quickstart-opensearch.properties` - Example connector configuration
- `checkstyle/checkstyle.xml` - Code style rules (currently unused)
- `gradle-config/aiven-eclipse-formatter.xml` - Eclipse code formatter settings
- `src/main/resources/META-INF/services/` - SPI service registrations

## AWS OpenSearch Serverless Support

The connector now supports AWS OpenSearch Serverless with IMDSv2/IAM authentication:

### AWS Configuration Options

**Basic AWS Authentication:**
```properties
# Use instance profile credentials (recommended for EC2)
aws.credentials.provider=instance_profile
aws.region=us-east-1
aws.service.name=es
```

**Static AWS Credentials:**
```properties
aws.credentials.provider=static
aws.access.key.id=YOUR_ACCESS_KEY
aws.secret.access.key=YOUR_SECRET_KEY
aws.region=us-east-1
aws.service.name=es
```

**Temporary AWS Credentials:**
```properties
aws.credentials.provider=static
aws.access.key.id=YOUR_ACCESS_KEY
aws.secret.access.key=YOUR_SECRET_KEY
aws.session.token=YOUR_SESSION_TOKEN
aws.region=us-east-1
aws.service.name=es
```

### IMDSv2 Support

The connector automatically uses IMDSv2 when running on EC2 instances with:
- Instance profile credentials provider
- No explicit region (auto-detected from EC2 metadata)
- Proper IAM role attached to the EC2 instance

### OpenSearch Serverless Example

```properties
connector.class=io.aiven.kafka.connect.opensearch.OpensearchSinkConnector
connection.url=https://search-domain.us-east-1.opensearch.amazonaws.com
aws.credentials.provider=instance_profile
aws.region=us-east-1
aws.service.name=opensearch
topics=my-topic
key.ignore=true
```

## Common Development Patterns

### Adding New Configuration Options
1. Add constant and getter to `OpensearchSinkConnectorConfig`
2. Update `CONFIG` ConfigDef with validation rules
3. Use the configuration in relevant components
4. Add unit tests for validation logic
5. Regenerate documentation with `connectorConfigDoc` task

### Extending Client Configuration  
1. Implement `OpensearchClientConfigurator` interface
2. Register in `META-INF/services/io.aiven.kafka.connect.opensearch.spi.OpensearchClientConfigurator`
3. Add corresponding configuration options if needed
4. Test with integration tests using different OpenSearch setups

### Error Handling Patterns
- Use ConnectException for configuration and startup errors
- Use DataException for data processing errors  
- Implement proper retry logic with exponential backoff
- Support errant record reporting for non-retryable errors