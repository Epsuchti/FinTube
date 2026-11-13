FROM eclipse-temurin:21-jre

WORKDIR /app
# yt-dlp only enables Deno by default; /etc/yt-dlp.conf makes it use the installed Node
# for YouTube's n/sig challenges (yt-dlp-ejs provides the solver scripts).
RUN apt-get update && apt-get install -y --no-install-recommends curl ffmpeg nodejs python3 python3-pip && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --break-system-packages --no-cache-dir --upgrade yt-dlp yt-dlp-ejs 'bgutil-ytdlp-pot-provider==2.0.0' \
    && printf -- '--js-runtimes node\n' > /etc/yt-dlp.conf

ARG JAR_FILE=target/fintube-server-*.jar
COPY ${JAR_FILE} app.jar

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1
# Do not keep per-thread NIO temporary direct buffers larger than 256 KiB. Without this cap every
# thread that once read or wrote a large heap array through a FileChannel pins a direct buffer of
# that size, until MaxDirectMemorySize runs out and H2 compaction fails with
# "OutOfMemoryError: Direct buffer memory". JAVA_OPTS comes last so it can still override this.
ENTRYPOINT ["sh", "-c", "java -Djdk.nio.maxCachedBufferSize=262144 $JAVA_OPTS -jar /app/app.jar"]
