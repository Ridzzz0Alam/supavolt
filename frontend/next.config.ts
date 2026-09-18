import type { NextConfig } from 'next';

const nextConfig: NextConfig = {
  // Storage serves files from S3/R2, so allow the public object host through next/image.
  images: {
    remotePatterns: [{ protocol: 'https', hostname: '**' }],
  },
  // The API lives on its own origin; nothing is proxied through Next.
  reactStrictMode: true,
};

export default nextConfig;
