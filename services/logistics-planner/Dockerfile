# Stage 1: Build — uses Maven wrapper (downloads Maven 3.9.9 on first build, cached after)
FROM eclipse-temurin:24-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B -q

COPY src ./src
RUN ./mvnw package -DskipTests -B -q

# Stage 2: Runtime — JDK image (no separate JRE published for Java 24)
FROM eclipse-temurin:24-jdk AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
USER app

COPY --from=build /build/target/logistics-planner-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
