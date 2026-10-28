/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // Dev server is exposed through Cloudflare quick tunnels; without this Next returns 403 for /_next/* and HMR.
  allowedDevOrigins: ['*.trycloudflare.com'],
  experimental: { proxyClientMaxBodySize: '31mb' },
  // app/api/v1/[...segments] owns forwarding and reads GATEWAY_URL at runtime.
  // A beforeFiles rewrite bypasses that handler and freezes its URL at build time.
  images: {
    unoptimized: true,
  },
}

export default nextConfig
