# The WMS as one image: the React app is built, then packed into the Spring Boot server, which serves both.
# Build: docker build -t wms .   Run: docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=postgres,cloud -e WMS_DB_URL=... wms

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

# ---------- What runs ----------
FROM eclipse-temurin:21-jre
RUN groupadd --system wms && useradd --system --gid wms --home-dir /app wms
WORKDIR /app
COPY --from=backend /app/wms.jar ./wms.jar
USER wms
EXPOSE 8080
# Leave room for the JVM itself within the instance's memory.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/wms.jar"]
