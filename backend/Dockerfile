FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

# Normalize a wrapper copied from a Windows checkout before executing it in Linux.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 potner \
    && useradd --system --uid 10001 --gid 10001 --home-dir /app --no-create-home potner

WORKDIR /app
COPY --from=builder /workspace/build/libs/potner.jar /app/potner.jar

# Create the photo directory before dropping privileges so the named volume inherits
# this ownership. Docker copies the image path's owner and mode when it initializes an
# empty named volume; without this the volume is created as root and the non-root
# application cannot write uploaded photos.
RUN mkdir -p /var/potner/photos && chown 10001:10001 /var/potner/photos

USER 10001:10001

EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=25.0", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/potner.jar"]
