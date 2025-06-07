package com.ironside.challengesphere.security.oauth2;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;

import java.util.Map;

public class OAuth2UserInfoFactory {
    
    private static final Logger logger = LogManager.getLogger(OAuth2UserInfoFactory.class);
    
    public static OAuth2UserInfo getOAuth2UserInfo(String registrationId, Map<String, Object> attributes) throws ChallengeSphereException {
        if (registrationId.equalsIgnoreCase("google")) {
            return new GoogleOAuth2UserInfo(attributes);
        } else {
            throw new ChallengeSphereException("Sorry! Login with " + registrationId + " is not supported yet.", HttpStatus.BAD_REQUEST, logger);
        }
    }
}
