# Stage build chạy trên CPU của máy build ($BUILDPLATFORM), KHÔNG giả lập CPU khác:
# file .jar chạy được trên mọi CPU nên chỉ cần build 1 lần rồi dùng chung cho amd64 và arm64.
FROM --platform=$BUILDPLATFORM eclipse-temurin:17-jdk AS builder
WORKDIR /app
COPY . .
RUN ./gradlew bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/PaymentService-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
