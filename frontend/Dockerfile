FROM node:24-alpine AS dependencies
WORKDIR /app
RUN corepack enable && corepack prepare pnpm@11.19.0 --activate
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM node:24-alpine AS build
WORKDIR /app
ARG GATEWAY_URL=http://api-gateway:8080
ARG NEXT_PUBLIC_DEMO_MAILPIT_URL
ENV GATEWAY_URL=${GATEWAY_URL}
ENV NEXT_PUBLIC_DEMO_MAILPIT_URL=${NEXT_PUBLIC_DEMO_MAILPIT_URL}
RUN corepack enable && corepack prepare pnpm@11.19.0 --activate
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN pnpm build

FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
COPY --from=build /app/public ./public
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
