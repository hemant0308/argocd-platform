package com.argocd.platform.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ArgoCD Platform Service.
 *
 * <p>This service acts as the Routing Service for the ArgoCD platform — it is the
 * API bridge between ArgoCD ApplicationSet Plugin Generators and the PostgreSQL
 * state store that holds all user-owned platform state (projects, clusters,
 * applications and their partition assignments).
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Partition discovery APIs consumed by Managed ArgoCD ApplicationSets</li>
 *   <li>Platform-level user-to-project authorization</li>
 *   <li>Management of projects, clusters, applications and their partition assignments</li>
 * </ul>
 *
 * <p>DataSource and DSLContext are auto-configured by Spring Boot via
 * {@code spring.datasource.*} properties — no custom configuration needed.
 * Liquibase migrations run automatically on startup.
 */
@SpringBootApplication
@OpenAPIDefinition(
        info = @Info(
                title = "ArgoCD Platform Service API",
                version = "1.0.0",
                description = """
                        Routing Service for the ArgoCD multi-control-plane platform.
                        Provides partition discovery APIs for ApplicationSet Plugin Generators
                        and manages the lifecycle of projects, clusters, and applications.
                        """,
                contact = @Contact(
                        name = "ArgoCD Platform Team"
                )
        ),
        servers = {
                @Server(url = "/", description = "ArgoCD Platform Service")
        }
)
public class ArgoCDPlatformServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArgoCDPlatformServiceApplication.class, args);
    }
}
