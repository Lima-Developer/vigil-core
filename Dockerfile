FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY vigil-domain/pom.xml vigil-domain/pom.xml
COPY vigil-application/pom.xml vigil-application/pom.xml
COPY vigil-adapters/pom.xml vigil-adapters/pom.xml
COPY vigil-bootstrap/pom.xml vigil-bootstrap/pom.xml
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -B -q -DskipTests dependency:go-offline
COPY vigil-domain vigil-domain
COPY vigil-application vigil-application
COPY vigil-adapters vigil-adapters
COPY vigil-bootstrap vigil-bootstrap
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home /var/lib/vigil vigil \
    && mkdir -p /var/lib/vigil \
    && chown -R vigil:vigil /var/lib/vigil
WORKDIR /app
COPY --from=build /workspace/vigil-bootstrap/target/vigil-bootstrap-0.1.0-SNAPSHOT-exec.jar /app/vigil.jar
USER 10001
VOLUME ["/var/lib/vigil"]
EXPOSE 8080
ENV VIGIL_DATABASE_PATH=/var/lib/vigil/vigil.db \
    SERVER_ADDRESS=0.0.0.0
ENTRYPOINT ["java", "-jar", "/app/vigil.jar"]
