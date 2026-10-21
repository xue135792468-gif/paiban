package com.paiban.identity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.paiban.identity.domain.AppUser;
import com.paiban.identity.domain.AppUserRepository;
import com.paiban.identity.domain.PermissionRepository;
import com.paiban.identity.domain.Role;
import com.paiban.identity.domain.RoleRepository;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * First-start self-initialisation (deploy-friendly ticket 03).
 *
 * <p>Boots the application against a private, empty H2 database so Flyway really has to
 * create the schema and the seeder really has to create the catalogue. The second half
 * re-runs the same seeding entry point the application runs on every restart and asserts
 * that nothing is duplicated or reset.
 *
 * <p>The database URL is overridden (and the bootstrap password pinned to empty) so the
 * assertions do not depend on rows or environment variables other tests may have left
 * behind, and so the test stays independent of suite ordering.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:paiban_identity_init;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "paiban.security.bootstrap-admin-password="
        })
class IdentityDataInitializerTest {

    @Autowired
    @Qualifier("seedIdentityData")
    private ApplicationRunner seedRunner;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private PermissionRepository permissionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void firstStartCreatesExactlyOneAdminAccountWithTheAdminRole() {
        assertThat(userRepository.count())
                .as("a clean database contains only the bootstrap administrator")
                .isEqualTo(1);

        AppUser admin = userRepository.findByUsername("admin").orElseThrow();
        assertThat(roleCodes(admin)).containsExactly("admin");
        assertThat(admin.getDisplayName()).isNotBlank();
        assertThat(passwordEncoder.matches("admin123", admin.getPasswordHash()))
                .as("local/test profiles keep the documented admin/admin123 bootstrap credential")
                .isTrue();

        assertThat(roleRepository.findAll())
                .extracting(Role::getCode)
                .containsExactlyInAnyOrder("admin", "teacher", "head_teacher");
        assertThat(roleRepository.findByCode("admin").orElseThrow().getPermissions())
                .as("the admin role receives the whole permission catalogue")
                .hasSize((int) permissionRepository.count());
    }

    @Test
    void reseedingOnRestartCreatesNothingAndResetsNothing() throws Exception {
        AppUser before = userRepository.findByUsername("admin").orElseThrow();
        long users = userRepository.count();
        long roles = roleRepository.count();
        long permissions = permissionRepository.count();
        String passwordHash = before.getPasswordHash();
        Set<String> roleCodes = roleCodes(before);
        int adminPermissionCount = roleRepository.findByCode("admin").orElseThrow().getPermissions().size();

        // Exactly what a container restart does: run the seeding entry point again.
        seedRunner.run(new DefaultApplicationArguments());

        assertThat(userRepository.count()).isEqualTo(users);
        assertThat(userRepository.findAllByRolesCode("admin")).hasSize(1);
        assertThat(roleRepository.count()).isEqualTo(roles);
        assertThat(permissionRepository.count()).isEqualTo(permissions);

        AppUser after = userRepository.findByUsername("admin").orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getPasswordHash())
                .as("a restart must not reset the administrator password")
                .isEqualTo(passwordHash);
        assertThat(roleCodes(after)).isEqualTo(roleCodes);
        assertThat(roleRepository.findByCode("admin").orElseThrow().getPermissions())
                .hasSize(adminPermissionCount);
    }

    @Test
    void placeholderConfiguredPasswordNeverBecomesTheAdminPassword() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("postgres");

        String password = new IdentityDataInitializer().bootstrapPassword(
                production, "replace-with-a-strong-initial-admin-password");

        assertThat(password)
                .as("a placeholder copied from .env.example must not become a real credential")
                .isNotEqualTo("replace-with-a-strong-initial-admin-password")
                .matches("[0-9a-f]{16}");
    }

    @Test
    void placeholderAdminPasswordFallsBackToAdmin123InDevelopmentProfiles() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThat(new IdentityDataInitializer().bootstrapPassword(local, "replace-with-a-strong-password"))
                .isEqualTo("admin123");
    }

    @Test
    void realConfiguredPasswordIsKept() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("postgres");

        assertThat(new IdentityDataInitializer().bootstrapPassword(production, "Chosen-Pass-2026"))
                .isEqualTo("Chosen-Pass-2026");
    }

    @Test
    void placeholderDetectionCoversEveryDocumentedPrefix() {
        assertThat(IdentityDataInitializer.isPlaceholder("replace-with-x")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("replace_with_x")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("CHANGE-ME-x")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("changeme-x")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("your-password")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("your_password")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("TODO-set-this")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("")).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder(null)).isTrue();
        assertThat(IdentityDataInitializer.isPlaceholder("Chosen-Pass-2026")).isFalse();
    }

    private Set<String> roleCodes(AppUser user) {
        return user.getRoles().stream().map(Role::getCode).collect(Collectors.toSet());
    }
}
