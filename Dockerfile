FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests -Dmaven.test.skip=true

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/target/downloader-0.0.1-SNAPSHOT.jar app.jar
COPY ca.pem /app/ca.pem
RUN apt-get update && apt-get install -y wget python3 xz-utils && rm -rf /var/lib/apt/lists/*
RUN wget -O /app/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux && chmod +x /app/yt-dlp
RUN wget -O /app/ffmpeg.tar.xz https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz \
    && tar -xf /app/ffmpeg.tar.xz --strip-components=1 -C /app \
    && rm /app/ffmpeg.tar.xz \
    && mv /app/ffmpeg /app/ffmpeg-bin \
    && chmod +x /app/ffmpeg-bin
ENV YT_DLP_PATH=/app/yt-dlp
ENTRYPOINT ["java", "-jar", "app.jar"]