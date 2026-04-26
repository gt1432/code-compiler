# Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Install GCC and G++ for the compiler functionality
RUN apk add --no-cache gcc g++ musl-dev

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
