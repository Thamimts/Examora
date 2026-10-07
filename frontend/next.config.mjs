/** @type {import('next').NextConfig} */
if (process.env.NODE_ENV === 'production') {
  const apiUrl = process.env.NEXT_PUBLIC_API_URL?.trim()
  if (!apiUrl) {
    throw new Error('NEXT_PUBLIC_API_URL is required for production builds.')
  }
  let parsedApiUrl
  try {
    parsedApiUrl = new URL(apiUrl)
  } catch {
    throw new Error('NEXT_PUBLIC_API_URL must be a valid HTTPS URL in production.')
  }
  if (parsedApiUrl.protocol !== 'https:') {
    throw new Error('NEXT_PUBLIC_API_URL must use HTTPS in production.')
  }

  const serverOnlySecrets = ['GROQ_API_KEY', 'OPENAI_API_KEY', 'GOOGLE_CLIENT_SECRET', 'GITHUB_CLIENT_SECRET', 'JWT_SECRET', 'DB_PASSWORD']
  const exposedSecret = serverOnlySecrets.find((key) => process.env[`NEXT_PUBLIC_${key}`])
  if (exposedSecret) {
    throw new Error(`NEXT_PUBLIC_${exposedSecret} is forbidden; configure it as a server-only environment variable.`)
  }
}
const nextConfig = {
  typescript: {
    ignoreBuildErrors: false,
  },
  images: {
    unoptimized: true,
  },
  async rewrites() {
    return [{ source: '/login', destination: '/' }, { source: '/register', destination: '/' }, { source: '/forgot-password', destination: '/' }, { source: '/reset-password', destination: '/' }, { source: '/oauth/callback', destination: '/' }, { source: '/settings/security', destination: '/' }, { source: '/student/:path*', destination: '/' }, { source: '/teacher/:path*', destination: '/' }, { source: '/admin/:path*', destination: '/' }]
  },
}

export default nextConfig
