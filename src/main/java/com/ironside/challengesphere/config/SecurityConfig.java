//package com.ironside.challengesphere.config;
//
//// TODO: Re-enable security later
//// import com.ironside.challengesphere.security.JwtAuthenticationFilter;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.authentication.AuthenticationManager;
//import org.springframework.security.config.Customizer;
//import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
//// import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.web.SecurityFilterChain;
//// import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
//
//@Configuration
//// @EnableMethodSecurity  // TODO: Re-enable when security is wired back
//public class SecurityConfig {
//
//    // TODO: Re-enable JWT filter when security is wired back
//    // private final JwtAuthenticationFilter jwtAuthenticationFilter;
//    //
//    // public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
//    //     this.jwtAuthenticationFilter = jwtAuthenticationFilter;
//    // }
//
//    @Bean
//    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
//        http
//                .csrf(csrf -> csrf.disable())
//                .cors(Customizer.withDefaults())
//                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                .authorizeHttpRequests(auth -> auth
//                        .anyRequest().permitAll()
//                );
//                // TODO: Re-enable JWT filter when security is wired back
//                // .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
//
//        return http.build();
//    }
//
//    @Bean
//    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
//        return configuration.getAuthenticationManager();
//    }
//}
