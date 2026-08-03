package com.ironside.challengesphere.security;

import com.ironside.challengesphere.model.User;
import com.ironside.challengesphere.util.UserRole;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class UserPrincipal implements UserDetails {

    @Getter
    private final Long id;
    private final String username;
    private final String password;
    private final boolean active;
    private final UserRole role;

    public UserPrincipal(Long id, String username, String password, boolean active, UserRole role) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.active = active;
        this.role = role == null ? UserRole.USER : role;
    }

    public static UserPrincipal from(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getUsername(),
                user.getHashedPassword(),
                Boolean.TRUE.equals(user.getActive()) && user.getBannedAt() == null,
                user.getRole()
        );
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        if (role == UserRole.ADMIN) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
