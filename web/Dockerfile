# Stage 1: build and test with the Azul Zulu 25 JDK, then carve out a minimal runtime with jlink
FROM azul/zulu-openjdk:25 AS build
WORKDIR /src

# Resolve dependencies first so this layer is cached until the pom changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q verify

# Unpack the Spring Boot jar, work out which JDK modules it needs, and link a custom runtime of just those
RUN java -Djarmode=tools -jar target/java-calc-*.jar extract --destination /app
RUN MODULES=$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps --class-path '/app/lib/*' /app/java-calc-*.jar) \
 && jlink --add-modules "${MODULES},jdk.crypto.ec" --no-man-pages --no-header-files \
          --compress=zip-6 --output /opt/jre

# Stage 2: tiny runtime image
FROM debian:trixie-slim
RUN useradd --system --no-create-home calc
COPY --from=build /opt/jre /opt/jre
COPY --from=build /app /app
USER calc
EXPOSE 9000
ENTRYPOINT ["/opt/jre/bin/java", "-XX:MaxRAMPercentage=75", "-jar", "/app/java-calc-0.1.0-SNAPSHOT.jar"]
