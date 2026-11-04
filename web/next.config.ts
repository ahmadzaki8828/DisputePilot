import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // A self-contained server (.next/standalone) keeps the Docker image small.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
