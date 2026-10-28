# ---------- 1단계: 빌드 ----------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# 이미지를 만든 커밋 7자리. CI 가 --build-arg 로 넣고, /health 가 이 값을 보여 준다. 로컬 빌드는 local.
ARG APP_VERSION=local
ENV APP_VERSION=${APP_VERSION}

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-Xms128m", "-Xmx384m", "-jar", "app.jar"]
