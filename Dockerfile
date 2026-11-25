# memecreator backend. Multi-arch: builds on x86-64 and on Oracle's Arm (aarch64) servers.
# Built by deploy/docker-compose.yml; the build context is the repository root.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre
# fontconfig lets Java find installed fonts. Impact ships only with Windows, so the meme font on Linux is Anton
# (SIL Open Font License, deploy/fonts/OFL-Anton.txt) - second in meme.fonts.
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig curl \
 && rm -rf /var/lib/apt/lists/*
COPY deploy/fonts/Anton-Regular.ttf deploy/fonts/OFL-Anton.txt /usr/share/fonts/truetype/anton/
RUN fc-cache -f
RUN groupadd --system --gid 10001 meme && useradd --system --uid 10001 --gid meme --home /app meme \
 && mkdir -p /data /app/config && chown -R meme:meme /data /app
WORKDIR /app
COPY --from=build /src/target/memecreator-*.jar /app/app.jar
USER meme
EXPOSE 8081
# Server settings (paths under /data, secure cookie, ...) come from /app/config/application.yml, mounted by compose.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
