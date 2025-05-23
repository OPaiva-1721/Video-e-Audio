# Stage 1: Build the application using Maven
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /build
# Copiar pom.xml e baixar dependências primeiro para aproveitar o cache do Docker
COPY pom.xml .
RUN mvn dependency:go-offline
# Copiar o restante do código fonte
COPY src ./src
# Compilar e empacotar a aplicação
RUN mvn package -DskipTests

# Stage 2: Create the final runtime image
FROM openjdk:17-jdk-slim
WORKDIR /app

# Instalar dependências do sistema operacional
RUN apt-get update && apt-get install -y --no-install-recommends curl python3 ffmpeg && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Instalar yt-dlp (versão Linux)
RUN curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp && \
    chmod a+rx /usr/local/bin/yt-dlp

# Criar diretório de downloads (será montado pelo Render Disk )
RUN mkdir -p /app/downloads

# Copiar o JAR buildado do stage anterior
COPY --from=build /build/target/*.jar app.jar

# Expor a porta da aplicação
EXPOSE 8080

# Definir o comando de inicialização
CMD ["java", "-jar", "app.jar"]
