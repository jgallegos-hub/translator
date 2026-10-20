# Own relay (backend/relay) as a standard container: Fly.io today, any host tomorrow.
FROM denoland/deno:2.9.7
WORKDIR /app
COPY shared ./shared
COPY relay ./relay
# Pre-fetch npm deps at build time so the first request doesn't download them.
RUN deno cache relay/main.ts
USER deno
ENV PORT=8080
EXPOSE 8080
CMD ["run", "--allow-net", "--allow-env", "--allow-read", "relay/main.ts"]
