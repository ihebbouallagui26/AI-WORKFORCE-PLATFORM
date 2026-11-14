/**
 * {@link OidcProvider} implemented with openid-client (maintained, certified OpenID library).
 * All cryptography and protocol validation (discovery, issuer, PKCE, state, nonce, ID token
 * signature and claims) is done by the library; nothing is implemented by hand here.
 */
import * as oidc from 'openid-client';
import { isLoopbackHost, type AuthConfig } from './auth-policy';
import type { OidcProvider, PkceChecks, TokenSet, UserClaims } from './auth-session';

export class OpenIdProvider implements OidcProvider {
  private configuration: Promise<oidc.Configuration> | null = null;

  constructor(private readonly config: AuthConfig) {}

  private discover(): Promise<oidc.Configuration> {
    if (!this.configuration) {
      // Plain HTTP is only ever allowed towards the local development stack.
      const insecure = this.config.issuer.protocol === 'http:' && isLoopbackHost(this.config.issuer.hostname);
      this.configuration = oidc
        .discovery(this.config.issuer, this.config.clientId, undefined, oidc.None(), {
          execute: insecure ? [oidc.allowInsecureRequests] : [],
          timeout: 15,
        })
        .catch((error: unknown) => {
          this.configuration = null; // retry discovery on the next attempt
          throw error;
        });
    }
    return this.configuration;
  }

  async newChecks(): Promise<PkceChecks> {
    return { verifier: oidc.randomPKCECodeVerifier(), state: oidc.randomState(), nonce: oidc.randomNonce() };
  }

  async authorizationUrl(redirectUri: string, checks: PkceChecks): Promise<URL> {
    const configuration = await this.discover();
    return oidc.buildAuthorizationUrl(configuration, {
      redirect_uri: redirectUri,
      scope: this.config.scope,
      response_type: 'code',
      code_challenge: await oidc.calculatePKCECodeChallenge(checks.verifier),
      code_challenge_method: 'S256',
      state: checks.state,
      nonce: checks.nonce,
    });
  }

  async exchangeCode(callbackUrl: URL, checks: PkceChecks): Promise<TokenSet> {
    const configuration = await this.discover();
    const response = await oidc.authorizationCodeGrant(configuration, callbackUrl, {
      pkceCodeVerifier: checks.verifier,
      expectedState: checks.state,
      expectedNonce: checks.nonce,
      idTokenExpected: true,
    });
    return toTokenSet(response);
  }

  async refresh(refreshToken: string): Promise<TokenSet> {
    return toTokenSet(await oidc.refreshTokenGrant(await this.discover(), refreshToken));
  }

  async revoke(refreshToken: string): Promise<void> {
    await oidc.tokenRevocation(await this.discover(), refreshToken, { token_type_hint: 'refresh_token' });
  }

  async endSessionUrl(idTokenHint: string | undefined): Promise<URL | null> {
    const configuration = await this.discover();
    if (!configuration.serverMetadata().end_session_endpoint) {
      return null;
    }
    const parameters: Record<string, string> = { client_id: this.config.clientId };
    if (idTokenHint) {
      parameters['id_token_hint'] = idTokenHint;
    }
    return oidc.buildEndSessionUrl(configuration, parameters);
  }
}

function toTokenSet(response: oidc.TokenEndpointResponse & oidc.TokenEndpointResponseHelpers): TokenSet {
  const expiresIn = response.expiresIn() ?? 60;
  const claims = response.claims();
  return {
    accessToken: response.access_token,
    expiresAt: Date.now() + expiresIn * 1000,
    ...(response.refresh_token ? { refreshToken: response.refresh_token } : {}),
    ...(response.id_token ? { idToken: response.id_token } : {}),
    ...(claims ? { claims: toUserClaims(claims) } : {}),
  };
}

function toUserClaims(claims: oidc.IDToken): UserClaims {
  return {
    sub: claims.sub,
    ...(typeof claims['email'] === 'string' ? { email: claims['email'] } : {}),
    ...(typeof claims['email_verified'] === 'boolean' ? { emailVerified: claims['email_verified'] } : {}),
    ...(typeof claims['name'] === 'string' ? { name: claims['name'] } : {}),
  };
}
