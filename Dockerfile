# DoQFlow AI Backend 이미지
# 배포 compose의 backend 서비스(ghcr.io/doqflowai/doqflow-backend)가 쓴다.
# 비밀값은 이미지에 넣지 않는다. DB·JWT·내부 키는 실행할 때 환경변수로 받는다 (.env는 .dockerignore로 제외).

# ---------- 빌드: jar 만들기 ----------
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build

# 의존성을 먼저 받아 두면 소스만 바뀐 빌드는 이 층을 다시 쓴다
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 테스트는 CI(워크플로)에서 돌린다. 이미지 빌드는 패키징만 한다
RUN mvn -B -q -DskipTests package \
    && cp target/*.jar /build/app.jar

# ---------- 실행 이미지 ----------
FROM eclipse-temurin:17-jre

# 헬스체크용 curl
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# root가 아닌 사용자로 실행한다.
# /app/uploads는 업로드 볼륨이 붙는 자리다. 이 사용자 소유로 만들어 두면 빈 볼륨을 처음 붙일 때 소유권이 그대로 복사된다.
# 저장 파일은 기본 권한(파일 644, 폴더 755)이라 AI Module 컨테이너(다른 사용자)가 읽기 전용으로 읽을 수 있다.
RUN useradd --system --uid 10001 --no-create-home app \
    && mkdir -p /app/uploads \
    && chown app:app /app/uploads

WORKDIR /app
COPY --from=build /build/app.jar /app/app.jar
USER app

ENV UPLOAD_DIR=/app/uploads \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080

# 모든 API가 /api 아래에 있다 (server.servlet.context-path)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
