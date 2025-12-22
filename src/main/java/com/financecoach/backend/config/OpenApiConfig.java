// src/main/java/com/financecoach/backend/config/OpenApiConfig.java
package com.financecoach.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Bean
    public OpenAPI financeCoachOpenAPI() {
        // Security scheme for JWT
        SecurityScheme securityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Enter your JWT token obtained from /api/auth/login");

        // Security requirement
        SecurityRequirement securityRequirement = new SecurityRequirement()
                .addList("bearerAuth");

        return new OpenAPI()
                .info(new Info()
                        .title("Finance Coach API")
                        .description("""
                                AI-powered personal finance management platform.
                                
                                ## Features
                                - 🏦 Bank account integration via Plaid
                                - 💰 Budget tracking and alerts
                                - 🤖 AI financial coaching powered by Claude
                                - 📊 Transaction analytics and insights
                                - 💳 Subscription management
                                
                                ## Authentication
                                Most endpoints require JWT authentication. 
                                1. Register at `/api/auth/register` or login at `/api/auth/login`
                                2. Copy the JWT token from the response
                                3. Click the 🔒 **Authorize** button (top right)
                                4. Enter: `Bearer <your-token>`
                                5. All subsequent requests will include authentication
                                
                                ## Getting Started
                                1. **Register/Login** - Create account or authenticate
                                2. **Connect Bank** - Link your bank account via Plaid
                                3. **Sync Transactions** - Fetch your transaction history
                                4. **Set Budgets** - Create monthly budgets by category
                                5. **Chat with AI** - Get personalized financial advice
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Finance Coach Support")
                                .email("support@financecoach.com")
                                .url(frontendUrl))
                        .license(new License()
                                .name("MIT License")
                                .url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development"),
                        new Server()
                                .url("https://finance-coach-api.onrender.com")
                                .description("Production Server")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", securityScheme))
                .addSecurityItem(securityRequirement);
    }
}