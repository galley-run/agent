# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy Maven files
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
COPY mvnw.cmd .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app

# Copy the fat jar from build stage
COPY --from=build /app/target/agent-*-fat.jar /app/agent.jar

# Environment variable for Galley Agent ID
ENV GALLEY_AGENT_ID=""

# Expose port (adjust if needed based on your application)
EXPOSE 8080

# Run the application
ENTRYPOINT ["sh", "-c", "java -DGALLEY_AGENT_ID=${GALLEY_AGENT_ID} -Dconf.json=${CONF_JSON_PATH:-/app/conf.json} -jar /app/agent.jar"]
