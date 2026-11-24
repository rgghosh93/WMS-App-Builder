# The WMS for wms-builder: the same server as the Dockerfile, plus what the App Builder's agent needs to work on the code
# in the cloud: git to fetch and push it, a full Java JDK and Node to build it and run its checks.
# Build: docker build -f Dockerfile.builder -t wms-builder .

# ---------- The frontend ----------
FROM node:22.12.0-alpine AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- The backend, with the frontend inside ----------
FROM maven:3.9.9-eclipse-temurin-21 AS backend
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /app/frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package && cp target/wms-backend-*.jar /app/wms.jar

# ---------- Node, for the agent to check the screens ----------
FROM node:22.12.0-bookworm-slim AS node

# ---------- What runs ----------
FROM eclipse-temurin:21-jdk
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx \
    && node --version && npm --version && git --version
RUN groupadd --system wms && useradd --system --gid wms --create-home --home-dir /home/wms wms \
    && mkdir -p /var/wms-builder && chown wms:wms /var/wms-builder
WORKDIR /app
COPY --from=backend /app/wms.jar ./wms.jar
EXPOSE 8080
# Every Java process gets a quarter of the memory unless it says otherwise, so the agent's builds and tests can run beside
# the server; the server itself takes more, below.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=25 -XX:+ExitOnOutOfMemoryError" \
    MAVEN_OPTS="-Xmx384m" \
    WMS_BUILDER_WORKDIR=/var/wms-builder
# The change requests' copies live on a disk mounted at /var/wms-builder, which may be mounted owned by root: it is handed
# to wms, and the server runs as wms, never as root.
ENTRYPOINT ["sh", "-c", "chown wms:wms /var/wms-builder 2>/dev/null; exec env HOME=/home/wms setpriv --reuid=wms --regid=wms --init-groups java -XX:MaxRAMPercentage=40 -jar /app/wms.jar"]
