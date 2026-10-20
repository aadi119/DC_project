# RailNet Ops: the whole site (Java backend, Spark, MPI) in one image.
#   docker build -t railnet .
#   docker run -p 8080:8080 railnet      → http://localhost:8080

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml log4j2.properties ./
COPY lib lib
RUN mvn -q -B dependency:go-offline
COPY *.java ./
RUN mvn -q -B compile dependency:copy-dependencies -DincludeScope=runtime

# ---------- MPJ Express (Java MPI runtime, MIT licence) ----------
FROM eclipse-temurin:21-jdk AS mpj
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -fsSL -o /tmp/mpj.tar.gz "https://sourceforge.net/projects/mpjexpress/files/releases/mpj-v0_44.tar.gz/download" \
 && mkdir -p /opt && tar -xzf /tmp/mpj.tar.gz -C /opt && mv /opt/mpj-v0_44 /opt/mpj

# ---------- run ----------
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=mpj /opt/mpj /opt/mpj
COPY --from=build /app/target/classes target/classes
COPY --from=build /app/target/dependency target/dependency
COPY lib lib
COPY web web
ENV MPJ_HOME=/opt/mpj \
    BIND_HOST=0.0.0.0 \
    PORT=8080 \
    NO_BROWSER=1
EXPOSE 8080
CMD ["java", "-Dstdout.encoding=UTF-8", "-Djava.awt.headless=true", "-Djava.security.manager=allow", \
     "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED", \
     "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", \
     "--add-opens=java.base/java.net=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED", \
     "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED", \
     "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", \
     "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED", "--add-opens=java.base/sun.security.action=ALL-UNNAMED", \
     "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED", \
     "-cp", "target/classes:target/dependency/*:lib/mpj.jar", "RailwayWebServer"]
