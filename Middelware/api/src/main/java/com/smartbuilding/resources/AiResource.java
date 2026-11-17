package com.smartbuilding.resources;

import com.smartbuilding.security.RequireRole;
import com.smartbuilding.services.AiService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.Map;
import java.util.Locale;

@Path("/ai")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AiResource {

    @Inject
    private AiService aiService;

    @Context
    private SecurityContext securityContext;

    // 1. POST /api/ai/explain-incident
    @POST
    @Path("/explain-incident")
    public Response explainIncident(Map<String, String> payload) {
        if (payload == null || !payload.containsKey("incidentId")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "incidentId est obligatoire"))
                    .build();
        }

        String incidentId = payload.get("incidentId");

        // Récupérer le vrai userId (sub du token JWT transmis par SecurityFilter)
        String userId = securityContext.getUserPrincipal() != null ? securityContext.getUserPrincipal().getName() : "user";
        String role = securityContext.isUserInRole("ADMIN") ? "ADMIN" : "OCCUPANT";

        try {
            Map<String, Object> result = aiService.explainIncident(incidentId, userId, role);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", e.getMessage())).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", e.getMessage())).build();
        }
    }

    // 2. POST /api/ai/ask
    @POST
    @Path("/ask")
    public Response askQuestion(Map<String, String> payload) {
        if (payload == null || !payload.containsKey("question")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "question est obligatoire"))
                    .build();
        }

        String question = payload.get("question");
        String roomId = payload.get("roomId");

        // Récupérer le vrai userId (sub du token JWT transmis par SecurityFilter)
        String userId = securityContext.getUserPrincipal() != null ? securityContext.getUserPrincipal().getName() : "user";
        String role = securityContext.isUserInRole("ADMIN") ? "ADMIN" : "OCCUPANT";

        try {
            Map<String, Object> result = aiService.askQuestion(question, roomId, userId, role);
            return Response.ok(result).build();
        } catch (IllegalStateException e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(Map.of("error", e.getMessage())).build();
        }
    }


    // 3. POST /api/ai/feedback
    @POST
    @Path("/feedback")
    public Response submitFeedback(Map<String, String> payload) {

        // Vérifier les champs obligatoires
        if (payload == null
                || payload.get("interactionId") == null
                || payload.get("interactionId").isBlank()
                || payload.get("vote") == null
                || payload.get("vote").isBlank()) {

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error",
                            "interactionId et vote sont obligatoires"))
                    .build();
        }

        // Récupérer l'identité authentifiée depuis le JWT
        String userId = securityContext.getUserPrincipal() != null
                ? securityContext.getUserPrincipal().getName()
                : null;

        if (userId == null || userId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "Utilisateur non authentifié"))
                    .build();
        }

        String interactionId = payload.get("interactionId").trim();
        String vote = payload.get("vote").trim().toUpperCase(Locale.ROOT);
        String comment = payload.get("comment");

        // Validation du vote
        if (!"UP".equals(vote) && !"DOWN".equals(vote)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Le vote doit être UP ou DOWN"))
                    .build();
        }

        try {
            boolean ok = aiService.registerFeedback(
                    interactionId, vote, comment, userId);

            if (ok) {
                return Response.noContent().build();
            }

            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Interaction non trouvee"))
                    .build();

        } catch (SecurityException e) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error",
                            "Vous ne pouvez modifier que vos propres interactions"))
                    .build();

        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    // 4. GET /api/ai/stats (Réservé à l'ADMIN pour le Back Office)
    @GET
    @Path("/stats")
    @RequireRole("ADMIN")
    public Response getAiStats() {
        return Response.ok(aiService.getStats()).build();
    }

    // 5. GET /api/ai/interactions (Réservé à l'ADMIN)
    @GET
    @Path("/interactions")
    @RequireRole("ADMIN")
    public Response getAiInteractions() {
        return Response.ok(aiService.getRecentInteractions()).build();
    }
}
