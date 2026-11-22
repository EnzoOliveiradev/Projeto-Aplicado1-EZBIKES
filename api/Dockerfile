# ---- Build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Baixa as dependências primeiro para aproveitar o cache de camadas
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Runtime ----
FROM eclipse-temurin:21-jre

RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app

COPY --from=build /build/target/ezbikes-api.jar app.jar
RUN chown -R app:app /app
USER app

# 8080: loja/API pública · 8081: Actuator (health e métricas), só para a rede interna
EXPOSE 8080 8081
ENV JAVA_OPTS="-Xms128m -Xmx384m"

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
