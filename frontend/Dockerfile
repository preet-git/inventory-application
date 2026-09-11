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

# The stock nginx image serves this directory at /, which is all a single-page app with no routes
# needs.
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html

EXPOSE 80
