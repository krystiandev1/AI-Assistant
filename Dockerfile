# Stage 1: Build Java (all Maven modules)
FROM eclipse-temurin:21-jdk AS java-builder
WORKDIR /build

COPY .mvn .mvn
COPY mvnw mvnw
COPY pom.xml pom.xml
COPY ai-assistant/pom.xml ai-assistant/pom.xml
COPY countries-mcp-server/pom.xml countries-mcp-server/pom.xml

RUN chmod +x mvnw && ./mvnw dependency:go-offline -pl ai-assistant -am -q

COPY ai-assistant/src ai-assistant/src

RUN ./mvnw package -pl ai-assistant -am -DskipTests -q

# Stage 2: Install Node.js dependencies for weather MCP
FROM node:22-slim AS node-builder
WORKDIR /weather
COPY external/mcp-weather/package.json ./
COPY external/mcp-weather/package-lock.json* ./
RUN npm install --production --ignore-scripts

# Stage 3: Runtime (Java 21 JRE + Node.js 22)
FROM eclipse-temurin:21-jre AS runtime

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=java-builder /build/ai-assistant/target/ai-assistant-*.jar app.jar
COPY --from=node-builder /weather/node_modules /app/external/mcp-weather/node_modules
COPY external/mcp-weather/dist /app/external/mcp-weather/dist

COPY ai-assistant-entrypoint.sh /ai-assistant-entrypoint.sh
RUN sed -i 's/\r$//' /ai-assistant-entrypoint.sh && chmod +x /ai-assistant-entrypoint.sh

EXPOSE 8080

ENTRYPOINT ["/ai-assistant-entrypoint.sh"]
