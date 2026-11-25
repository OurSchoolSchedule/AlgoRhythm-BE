package com.rssolplan.edu.global.fordevToken;

import com.rssolplan.edu.domain.school.SchoolUserRepository;
import com.rssolplan.edu.domain.user.User;
import com.rssolplan.edu.domain.user.UserRepository;
import com.rssolplan.edu.global.config.JwtTokenProvider;
import com.rssolplan.edu.global.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DevLoginSecurityTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(WebSecurity.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(SchoolUserRepository.class, () -> mock(SchoolUserRepository.class))
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(JwtTokenProvider.class, () -> mock(JwtTokenProvider.class));

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, DevTokenController.class})
    static class WebSecurity {}

    private MockHttpServletRequestBuilder devLogin() {
        return post("/api/auth/dev-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"teacher@example.invalid\"}");
    }

    private RequestPostProcessor remoteAddress(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "|", "|true", "prod|", "prod|true", "staging|true",
            "local|", "local|false", "local|unexpected", "local,prod|true"
    })
    void disabledConfigurationsRejectAnonymousAndAuthenticatedRequests(String profiles, String flag) {
        var configured = runner;
        if (profiles != null) {
            configured = configured.withPropertyValues("spring.profiles.active=" + profiles);
        }
        if (flag != null) {
            configured = configured.withPropertyValues("app.auth.dev-login-enabled=" + flag);
        }
        configured.run(context -> {
            assertThat(context).doesNotHaveBean(DevTokenController.class);
            var api = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
            api.perform(devLogin()).andExpect(status().isForbidden());
            api.perform(devLogin().with(user("signed-in").roles("ADMIN"))).andExpect(status().isForbidden());
            verifyNoInteractions(context.getBean(JwtTokenProvider.class));
        });
    }

    @Test
    void explicitlyEnabledLocalProfilePermitsOnlyTheIssuancePost() {
        runner.withPropertyValues("spring.profiles.active=local", "app.auth.dev-login-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(DevTokenController.class);
                    var teacher = User.builder().id(1L).build();
                    when(context.getBean(UserRepository.class).findByEmail("teacher@example.invalid"))
                            .thenReturn(Optional.of(teacher));
                    when(context.getBean(JwtTokenProvider.class).generateAccess(1L)).thenReturn("local-test-token");

                    var api = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
                    api.perform(devLogin()).andExpect(status().isOk()).andExpect(content().string("local-test-token"));
                    api.perform(devLogin().with(remoteAddress("::1"))).andExpect(status().isOk());
                    api.perform(devLogin().with(remoteAddress("192.0.2.1"))).andExpect(status().isForbidden());
                    verify(context.getBean(JwtTokenProvider.class), times(2)).generateAccess(1L);
                    api.perform(get("/api/auth/dev-token")).andExpect(status().isForbidden());
                    api.perform(get("/api/mypage/active-school")).andExpect(status().isForbidden());
                });
    }
}
