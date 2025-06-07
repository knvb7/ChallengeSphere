package com.ironside.challengesphere.security;

import com.ironside.challengesphere.exception.ChallengeSphereException;
import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.repository.UserRepository;
import com.ironside.challengesphere.security.oauth2.OAuth2UserInfo;
import com.ironside.challengesphere.security.oauth2.OAuth2UserInfoFactory;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final Logger logger = LogManager.getLogger(CustomOAuth2UserService.class);
    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest oAuth2UserRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(oAuth2UserRequest);

        try {
            return processOAuth2User(oAuth2UserRequest, oAuth2User);
        } catch (AuthenticationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Throwing an instance of AuthenticationException will trigger the OAuth2AuthenticationFailureHandler
            throw new InternalAuthenticationServiceException(ex.getMessage(), ex.getCause());
        }
    }

    private OAuth2User processOAuth2User(OAuth2UserRequest oAuth2UserRequest, OAuth2User oAuth2User) throws ChallengeSphereException {
        String registrationId = oAuth2UserRequest.getClientRegistration().getRegistrationId();
        OAuth2UserInfo oAuth2UserInfo = OAuth2UserInfoFactory.getOAuth2UserInfo(registrationId, oAuth2User.getAttributes());
        
        if (!StringUtils.hasText(oAuth2UserInfo.getEmail())) {
            throw new ChallengeSphereException("Email not found from OAuth2 provider", HttpStatus.BAD_REQUEST, logger);
        }

        Optional<User> userOptional = userRepository.findByEmail(oAuth2UserInfo.getEmail());
        User user;
        
        if (userOptional.isPresent()) {
            user = userOptional.get();
            user = updateExistingUser(user, oAuth2UserInfo);
        } else {
            user = registerNewUser(oAuth2UserRequest, oAuth2UserInfo);
        }

        return UserPrincipal.create(user, oAuth2User.getAttributes());
    }

    private User registerNewUser(OAuth2UserRequest oAuth2UserRequest, OAuth2UserInfo oAuth2UserInfo) {
        User user = new User();

        user.setEmailId(oAuth2UserInfo.getEmail());
        user.setFullName(oAuth2UserInfo.getName());
        user.setProfilePicUrl(oAuth2UserInfo.getImageUrl());
        user.setUsername(generateUsername(oAuth2UserInfo));
        user.setPasswordHash(""); // OAuth2 users don't have a password
        user.setActive(true);
        user.setRole(UserRole.FAN); // Default role for new users
        user.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        user.setUpdatedAt(new Timestamp(System.currentTimeMillis()));

        return userRepository.save(user);
    }

    private User updateExistingUser(User existingUser, OAuth2UserInfo oAuth2UserInfo) {
        existingUser.setFullName(oAuth2UserInfo.getName());
        if (oAuth2UserInfo.getImageUrl() != null) {
            existingUser.setProfilePicUrl(oAuth2UserInfo.getImageUrl());
        }
        existingUser.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        
        return userRepository.save(existingUser);
    }
    
    private String generateUsername(OAuth2UserInfo oAuth2UserInfo) {
        // Generate a username based on email or name
        String baseName = oAuth2UserInfo.getName().toLowerCase().replaceAll("\\s+", "");
        
        // Check if a username exists and append numbers if needed
        String username = baseName;
        int counter = 1;
        
        while (userRepository.findByUsername(username).isPresent()) {
            username = baseName + counter;
            counter++;
        }
        
        return username;
    }
}
