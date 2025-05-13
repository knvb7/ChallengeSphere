package com.ironside.challengesphere.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TokenProvider {

    @Value("${app.auth.tokenSecret}")
    private String tokenSecret;

    @Value("${app.auth.tokenExpirationMsec}")
    private long tokenExpirationMsec;
    
    @Value("${app.auth.issuer:athletiverse.com}")
    private String issuer;
    
    @Value("${app.auth.audience:athletiverse-api}")
    private String audience;
    
    private byte[] secretKey;
    
    @PostConstruct
    public void init() {
        secretKey = getSecretKey();
    }

    private byte[] getSecretKey() {
        byte[] keyBytes = tokenSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Generate a secure random key if the provided one is too short
            log.warn("Provided JWT secret key is less than 32 bytes, generating a secure random key");
            SecureRandom secureRandom = new SecureRandom();
            keyBytes = new byte[32];
            secureRandom.nextBytes(keyBytes);
        }
        return Arrays.copyOf(keyBytes, 32); // Nimbus requires exactly 256 bits for HS256
    }

    public String createToken(Authentication authentication) {
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + tokenExpirationMsec);
        String authorities = userPrincipal.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        try {
            // Add more claims for better security
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(userPrincipal.getName())
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(now)
                    .expirationTime(expiryDate)
                    .notBeforeTime(now)
                    .jwtID(UUID.randomUUID().toString()) // Unique identifier for the token
                    .claim("username", userPrincipal.getUsername())
                    .claim("authorities", authorities)
                    .claim("email", userPrincipal.getEmail())
                    .build();
                    
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();
                
            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            signedJWT.sign(new MACSigner(secretKey));
            return signedJWT.serialize();
        } catch (Exception ex) {
            log.error("Error creating JWT: {}", ex.getMessage());
            throw new SecurityException("JWT creation failed", ex);
        }
    }

    public Long getUserIdFromToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(new MACVerifier(secretKey))) {
                throw new JOSEException("JWT signature verification failed");
            }
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            
            // Validate additional claims
            validateClaims(claims);
            
            return Long.parseLong(claims.getSubject());
        } catch (Exception ex) {
            log.error("Failed to parse JWT token for user ID: {}", ex.getMessage());
            throw new SecurityException("Invalid JWT token", ex);
        }
    }

    public boolean validateToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(new MACVerifier(secretKey))) {
                log.error("Invalid JWT signature");
                return false;
            }
            
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            validateClaims(claims);
            
            return true;
        } catch (Exception ex) {
            log.error("JWT validation error: {}", ex.getMessage());
            return false;
        }
    }
    
    private void validateClaims(JWTClaimsSet claims) throws JOSEException {
        Date now = new Date();
        
        // Check expiration
        Date expiration = claims.getExpirationTime();
        if (expiration == null || expiration.before(now)) {
            throw new JOSEException("Expired JWT token");
        }
        
        // Check not before time
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.after(now)) {
            throw new JOSEException("JWT token not yet valid");
        }
        
        // Validate issuer
        String tokenIssuer = claims.getIssuer();
        if (tokenIssuer == null || !tokenIssuer.equals(issuer)) {
            throw new JOSEException("Invalid JWT issuer");
        }
        
        // Validate audience
        String tokenAudience = claims.getAudience().get(0);
        if (tokenAudience == null || !tokenAudience.equals(audience)) {
            throw new JOSEException("Invalid JWT audience");
        }
    }
    
    // Method to create refresh tokens
    public String createRefreshToken(Authentication authentication) {
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();
        Date now = new Date();
        // Refresh tokens typically last longer than access tokens
        Date expiryDate = new Date(now.getTime() + getRefreshTokenExpirationMsec());
        
        try {
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(userPrincipal.getName())
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(now)
                    .expirationTime(expiryDate)
                    .notBeforeTime(now)
                    .jwtID(UUID.randomUUID().toString())
                    .claim("token_type", "refresh")
                    .build();
                    
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT)
                .build();
                
            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            signedJWT.sign(new MACSigner(secretKey));
            return signedJWT.serialize();
        } catch (Exception ex) {
            log.error("Error creating refresh token: {}", ex.getMessage());
            throw new SecurityException("Refresh token creation failed", ex);
        }
    }
    
    /**
     * Get the refresh token expiration time in milliseconds
     * Refresh tokens typically have a longer lifespan than access tokens
     */
    public long getRefreshTokenExpirationMsec() {
        return tokenExpirationMsec * 5;
    }
    
    /**
     * Validate a refresh token and extract the user ID
     */
    public Long validateRefreshToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            if (!signedJWT.verify(new MACVerifier(secretKey))) {
                throw new JOSEException("Refresh token signature verification failed");
            }
            
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();
            validateClaims(claims);
            
            // Check that this is indeed a refresh token
            String tokenType = (String) claims.getClaim("token_type");
            if (!"refresh".equals(tokenType)) {
                throw new JOSEException("Invalid token type: not a refresh token");
            }
            
            return Long.parseLong(claims.getSubject());
        } catch (Exception ex) {
            log.error("Refresh token validation error: {}", ex.getMessage());
            throw new SecurityException("Invalid refresh token", ex);
        }
    }
}
