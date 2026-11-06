# Build stage
FROM node:22-alpine AS build

WORKDIR /app

ARG NODE_ENV=production
ARG VITE_BUILD_TIME

ENV NODE_ENV=$NODE_ENV
ENV VITE_BUILD_TIME=$VITE_BUILD_TIME

# Copy package files
COPY package*.json ./

# Install dependencies, build tools (vite, devDependencies) included whatever NODE_ENV:
# with NODE_ENV=production npm ci would leave them out and the build fail
RUN npm ci --include=dev

# Copy source code
COPY . .

# Build the application
RUN if [ "$NODE_ENV" = "development" ]; then \
      npm run build -- --mode development; \
    else \
      npm run build; \
    fi

# Production stage
FROM nginx:alpine

# Copy built assets from build stage
COPY --from=build /app/dist /usr/share/nginx/html

# Copy nginx configuration
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

# Copy custom nginx configuration if needed
# COPY nginx.conf /etc/nginx/nginx.conf

# Expose port 80
EXPOSE 80

ENTRYPOINT ["/docker-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
# Start nginx
#CMD ["nginx", "-g", "daemon off;"]
