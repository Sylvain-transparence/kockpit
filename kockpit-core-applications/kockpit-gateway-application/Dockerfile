FROM amazoncorretto:21-al2023-headless

COPY target/kockpit-gateway-application-*.jar /app/gateway.jar

ENTRYPOINT ["java", "-XX:InitialRAMPercentage=70.0", "-XX:MinRAMPercentage=70.0", "-XX:MaxRAMPercentage=70.0", "-jar", "/app/gateway.jar"]
