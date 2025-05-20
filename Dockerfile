FROM maven:3.8.6-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=build /app/target/downloader-0.0.1-SNAPSHOT.jar app.jar
RUN apt-get update && apt-get install -y wget python3 && rm -rf /var/lib/apt/lists/*
RUN wget -O /app/yt-dlp https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux && chmod +x /app/yt-dlp
RUN wget -O /app/ffmpeg.tar.xz https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz \
    && tar -xf /app/ffmpeg.tar.xz --strip-components=1 -C /app \
    && rm /app/ffmpeg.tar.xz \
    && mv /app/ffmpeg /app/ffmpeg-bin \
    && chmod +x /app/ffmpeg-bin
ENV YT_DLP_PATH=/app/yt-dlp
ENV FFMPEG_PATH=/app/ffmpeg-bin
ENV DOWNLOAD_OUTPUT_DIR=/app/downloads
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]