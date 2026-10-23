# Imagen única: la SPA de React se compila y se sirve desde el jar de Spring Boot (puerto 8080).
FROM node:22 AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /backend
COPY backend/ ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN ./mvnw -q -B dependency:go-offline -DskipTests || true
COPY --from=frontend /frontend/dist/ src/main/resources/static/
RUN ./mvnw -q -B package -DskipTests && cp target/backend-*.jar /app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=aws
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
