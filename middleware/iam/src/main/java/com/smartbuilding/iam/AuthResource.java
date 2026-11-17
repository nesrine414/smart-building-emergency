package com.smartbuilding.iam;

import com.smartbuilding.entities.User;
import com.smartbuilding.repositories.UserRepository;
import com.smartbuilding.security.JwtService;
import com.smartbuilding.security.PasswordUtil;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

@Path("/auth")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOG = Logger.getLogger(AuthResource.class.getName());

    @Inject
    private UserRepository userRepository;

    @Inject
    private JwtService jwtService;

    @PostConstruct
    public void initDemoUsers() {
        // Initialiser les deux comptes de démonstration (Contrat 1)
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User(
                    UUID.randomUUID().toString(),
                    "admin",
                    PasswordUtil.hashPassword("admin123"),
                    "ADMIN",
                    "Gestionnaire"
            );
            userRepository.save(admin);
            LOG.info("Compte démo ADMIN créé (admin / admin123)");
        }

        if (userRepository.findByUsername("occupant").isEmpty()) {
            User occupant = new User(
                    UUID.randomUUID().toString(),
                    "occupant",
                    PasswordUtil.hashPassword("occupant123"),
                    "OCCUPANT",
                    "Occupant"
            );
            userRepository.save(occupant);
            LOG.info("Compte démo OCCUPANT créé (occupant / occupant123)");
        }
    }

    @POST
    @Path("/login")
    public Response login(Map<String, String> credentials) {
        if (credentials == null || !credentials.containsKey("username") || !credentials.containsKey("password")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "username et password sont obligatoires"))
                    .build();
        }

        String username = credentials.get("username");
        String password = credentials.get("password");

        Optional<User> optUser = userRepository.findByUsername(username);
        if (optUser.isEmpty() || !PasswordUtil.verifyPassword(password, optUser.get().getPasswordHash())) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Identifiants invalides"))
                    .build();
        }

        User user = optUser.get();
        String token = jwtService.generateToken(user.getId(), user.getUsername(), user.getName(), user.getRole());
        long expiresAt = System.currentTimeMillis() + (8 * 3600 * 1000L);

        return Response.ok(Map.of(
                "token", token,
                "role", user.getRole(),
                "name", user.getName(),
                "expiresAt", expiresAt
        )).build();
    }
}