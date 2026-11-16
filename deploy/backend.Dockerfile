# 仅用于 deploy/docker-compose.prod.yml
# 先在本地构建：mvn -f backend/pom.xml -DskipTests package
# 构建镜像：docker build -f deploy/backend.Dockerfile --build-arg JAR_FILE=panel-backend-0.0.1-SNAPSHOT.jar -t panel-backend:<ver> .
FROM eclipse-temurin:21-jre-jammy

ARG JAR_FILE=panel-backend-0.0.1-SNAPSHOT.jar

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd --system panel \
 && useradd --system --gid panel --home /opt/panel panel \
 && mkdir -p /opt/panel/data /opt/panel/logs \
 && chown -R panel:panel /opt/panel

WORKDIR /opt/panel
COPY backend/target/${JAR_FILE} /opt/panel/panel-backend.jar
USER panel

EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Shanghai -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/opt/panel/panel-backend.jar"]
