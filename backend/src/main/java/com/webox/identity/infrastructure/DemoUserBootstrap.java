package com.webox.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class DemoUserBootstrap implements org.springframework.boot.ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;
    private final String adminPassword;
    private final String employeeEmail;
    private final String employeePassword;

    public DemoUserBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                             @Value("${webox.demo.admin-email:admin@webox.local}") String adminEmail,
                             @Value("${WEBOX_DEMO_ADMIN_PASSWORD:}") String adminPassword,
                             @Value("${webox.demo.employee-email:employee@webox.local}") String employeeEmail,
                             @Value("${WEBOX_DEMO_EMPLOYEE_PASSWORD:}") String employeePassword) {
        this.jdbc = jdbc; this.passwordEncoder = passwordEncoder; this.adminEmail = adminEmail;
        this.adminPassword = adminPassword; this.employeeEmail = employeeEmail; this.employeePassword = employeePassword;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        createIfConfigured(adminEmail, adminPassword, "WeBox Admin", "ADMIN");
        createIfConfigured(employeeEmail, employeePassword, "WeBox Employee", "EMPLOYEE");
    }

    private void createIfConfigured(String email, String password, String displayName, String role) {
        if (password == null || password.isBlank()) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE email=?", Integer.class, email) > 0) return;
        jdbc.update("INSERT INTO app_user(email,password_hash,display_name,role,enabled) VALUES(?,?,?,?,TRUE)",
                email.trim().toLowerCase(java.util.Locale.ROOT), passwordEncoder.encode(password), displayName, role);
    }
}
