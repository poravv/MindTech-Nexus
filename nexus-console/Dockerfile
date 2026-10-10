FROM node:22.23.3-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# Runs as uid 101 on port 8080; nginx.conf is a template rendered with envsubst at startup.
FROM nginxinc/nginx-unprivileged:1.30.5-alpine3.24
ENV NEXUS_API_UPSTREAM=http://nexus-api:8080
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/nexus-console/browser /usr/share/nginx/html
EXPOSE 8080
