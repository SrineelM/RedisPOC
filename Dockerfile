# Best Practice: Use a multi-stage build to create a small, secure final image.

# --- Build Stage ---
# Use a full JDK image to build the application JAR file.
FROM gradle:8.5.0-jdk17 AS build

# Set the working directory inside the container
WORKDIR /home/gradle/src

# Copy the entire project into the container
COPY . .

# Grant execution rights to the Gradle wrapper and build the application.
# The --no-daemon flag is recommended for CI/CD environments.
RUN chmod +x ./gradlew && ./gradlew build --no-daemon


# --- Runtime Stage ---
# Use a minimal JRE image for the final, small container.
FROM eclipse-temurin:17-jre-jammy

# Set the working directory
WORKDIR /app

# Copy the executable JAR from the build stage
COPY --from=build /home/gradle/src/build/libs/*.jar app.jar

# Expose the port the application runs on
EXPOSE 8080

# Define the entry point for the container.
# This runs the application with the 'prod' profile active by default.
# Environment variables can be passed via `docker run -e ...` to configure it.
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
