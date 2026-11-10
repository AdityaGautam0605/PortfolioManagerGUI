FROM node:24-bookworm-slim AS frontend
WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM maven:3.9.11 AS maven
FROM eclipse-temurin:25-jdk AS backend
COPY --from=maven /usr/share/maven /usr/share/maven
ENV PATH="/usr/share/maven/bin:${PATH}"
WORKDIR /build
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN useradd --system --uid 10001 --create-home folio
COPY --from=backend /build/target/classes ./classes
COPY --from=backend /build/target/dependency ./lib
COPY --from=frontend /build/web/dist ./web/dist
COPY deploy/schema.sql ./deploy/schema.sql
COPY deploy/start.sh ./deploy/start.sh
RUN chmod +x deploy/start.sh && chown -R folio:folio /app
USER folio
ENV PORT=10000
EXPOSE 10000
ENTRYPOINT ["sh", "deploy/start.sh"]
