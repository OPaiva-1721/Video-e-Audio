FROM openjdk:17-jdk-slim

# Instalar dependências
RUN apt-get update && apt-get install -y curl python3 ffmpeg

# Instalar yt-dlp
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp

WORKDIR /app

# Criar diretório de downloads
RUN mkdir -p /app/downloads

# Copiar JAR e configurações
COPY target/*.jar app.jar

# Expor porta
EXPOSE 8080

# Comando de inicialização
CMD ["java", "-jar", "app.jar"]
