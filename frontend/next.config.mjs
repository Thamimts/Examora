/** @type {import('next').NextConfig} */
const nextConfig = {
  typescript: {
    ignoreBuildErrors: true,
  },
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [{ source: '/login', destination: '/' }, { source: '/register', destination: '/' }, { source: '/forgot-password', destination: '/' }, { source: '/reset-password', destination: '/' }, { source: '/student/:path*', destination: '/' }, { source: '/teacher/:path*', destination: '/' }, { source: '/admin/:path*', destination: '/' }]
  },
}

export default nextConfig
