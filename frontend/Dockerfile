# Build the bundle with the Node the CLI supports, then throw the toolchain away: what ships is a
# few static files and an nginx to serve them.
FROM node:24-alpine AS build
WORKDIR /app

# Dependencies are copied and installed first so that editing source does not invalidate the
# install layer. npm ci installs exactly the lockfile, which is what makes the image reproducible.
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npx ng build --configuration production

FROM nginx:1.29-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
# The nginx image runs everything in this directory before starting, which is where the API URL
# gets written into env.js.
COPY docker-entrypoint.d/20-api-base-url.sh /docker-entrypoint.d/20-api-base-url.sh
RUN chmod +x /docker-entrypoint.d/20-api-base-url.sh

COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80
