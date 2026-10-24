package com.webox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BackendContextIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void exposesHealthAfterConnectingToMysqlAndRedis() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
    }

    @Test
    void flywaySeedsPublishedMenusForTodayAndTomorrowWithoutPlaintextPasswords() {
        Long menuDates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM daily_menu WHERE menu_date IN (?, ?) AND status = 'PUBLISHED'",
                Long.class, LocalDate.now(), LocalDate.now().plusDays(1));
        Long plaintextPasswords = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE password_hash NOT LIKE '$2%'", Long.class);

        assertThat(menuDates).isEqualTo(2L);
        assertThat(plaintextPasswords).isZero();
    }
}
