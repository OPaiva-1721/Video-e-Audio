./validate-cookies.sh || exit 1

# Stage 1: Build the application using Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src
RUN mvn package -DskipTests

# Stage 2: Runtime
FROM openjdk:17-jdk-slim
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    curl python3 ffmpeg && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp

RUN mkdir -p /app/downloads

COPY --from=build /build/target/*.jar app.jar
COPY ca.pem /app/ca.pem
COPY cookies.txt /app/cookies.txt

# VARIÁVEIS PARA O SPRING
ENV yt-dlp.path=/usr/local/bin/yt-dlp
ENV ffmpeg.path=/usr/bin/ffmpeg
ENV download.output.dir=/app/downloads

# Expor a porta (ajustável via PORT do Render)
ENV PORT=10000
EXPOSE 10000

CMD ["java", "-jar", "app.jar"]
