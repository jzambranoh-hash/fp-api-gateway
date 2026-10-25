# ============================================================
#  api-gateway · imagen multi-etapa
#  1) build: compila el .jar con Maven
#  2) runtime: solo el JRE + el .jar (imagen liviana)
# ============================================================

# ---------- Etapa 1: compilación ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Primero solo el pom.xml: si no cambian las dependencias, Docker reutiliza esta capa
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Luego el código fuente
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- Etapa 2: ejecución ----------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Usuario sin privilegios (buena práctica de seguridad)
RUN addgroup -S spring && adduser -S spring -G spring

COPY --from=build /app/target/*.jar app.jar
USER spring

EXPOSE 8080

# MaxRAMPercentage: la JVM usa como máximo el 75 % de la memoria asignada al contenedor
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]