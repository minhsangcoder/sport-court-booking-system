/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  experimental: { proxyClientMaxBodySize: '31mb' },
  async rewrites() {
    return [{ source: '/api/v1/:path*', destination: `${process.env.GATEWAY_URL ?? 'http://localhost:8080'}/api/v1/:path*` }]
  },
  images: {
    unoptimized: true,
  },
}

export default nextConfig
