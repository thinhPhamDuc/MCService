FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/OrderService-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
