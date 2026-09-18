import { NextResponse, type NextRequest } from 'next/server';
import * as jose from 'jose';

/**
 * Same behaviour as the original proxy.ts, with one change: the API's refresh endpoint now
 * rotates the stored token, so the Set-Cookie headers it returns must be copied onto the
 * response or the next request will present an already-revoked token.
 */

const ACCESS_TOKEN = 'access_token';
const REFRESH_TOKEN = 'refresh_token';
const AUTH_ROUTES = ['/login', '/register'];

async function refreshSession(refreshToken: string, response: NextResponse): Promise<boolean> {
  const apiUrl = process.env.API_URL;
  if (!apiUrl) return false;

  const res = await fetch(`${apiUrl}/auth/refresh`, {
    method: 'POST',
    headers: { Cookie: `${REFRESH_TOKEN}=${refreshToken}` },
  });

  if (!res.ok) return false;

  for (const header of res.headers.getSetCookie()) {
    const [pair] = header.split(';');
    const separator = pair.indexOf('=');
    const name = pair.slice(0, separator).trim();
    const value = pair.slice(separator + 1);

    if (name !== ACCESS_TOKEN && name !== REFRESH_TOKEN) continue;

    response.cookies.set(name, value, {
      httpOnly: true,
      sameSite: 'lax',
      secure: process.env.NODE_ENV === 'production',
      path: '/',
    });
  }

  return true;
}

export async function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const accessToken = request.cookies.get(ACCESS_TOKEN)?.value;
  const refreshToken = request.cookies.get(REFRESH_TOKEN)?.value;

  const isAuthRoute = AUTH_ROUTES.some((r) => pathname.startsWith(r));
  const isProtected = pathname.startsWith('/organizations') || pathname.startsWith('/dashboard');

  let isValid = false;
  if (accessToken && process.env.JWT_ACCESS_SECRET) {
    try {
      const secret = new TextEncoder().encode(process.env.JWT_ACCESS_SECRET);
      await jose.jwtVerify(accessToken, secret, {
        issuer: process.env.JWT_ISSUER ?? 'supavolt',
        audience: process.env.JWT_AUDIENCE ?? 'supavolt-dashboard',
      });
      isValid = true;
    } catch {
      // Expired or invalid: fall through to the refresh path.
    }
  }

  if (isAuthRoute && isValid) {
    return NextResponse.redirect(new URL('/dashboard', request.url));
  }

  if (isAuthRoute && refreshToken) {
    const response = NextResponse.redirect(new URL('/dashboard', request.url));
    if (await refreshSession(refreshToken, response)) return response;
  }

  if (isProtected && !isValid) {
    if (refreshToken) {
      const response = NextResponse.next();
      if (await refreshSession(refreshToken, response)) return response;
    }
    return NextResponse.redirect(new URL('/login', request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico).*)'],
};
