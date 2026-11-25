# The web front end (memecreator-fe repository) built and served by Caddy.
# Build context: this deploy/ folder; the front-end source comes in as the extra context "fe" (see docker-compose.yml).
FROM node:22-alpine AS build
WORKDIR /fe
COPY --from=fe package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=fe . .
RUN rm -rf node_modules/.vite && npm run build

FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /fe/dist /srv
