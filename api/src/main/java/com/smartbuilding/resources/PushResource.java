package com.smartbuilding.resources;

import com.smartbuilding.entities.PushSubscription;
import com.smartbuilding.services.PushService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

@Path("/push")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class PushResource {

    @Inject
    private PushService pushService;

    // 1. GET /api/push/public-key
    @GET
    @Path("/public-key")
    public Response getPublicKey() {
        return Response.ok(Map.of("publicKey", pushService.getPublicKey())).build();
    }

    // 2. POST /api/push/subscriptions
    @POST
    @Path("/subscriptions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response subscribe(Map<String, Object> payload) {
        if (payload == null || !payload.containsKey("subscription")) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "subscription est obligatoire"))
                    .build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> subMap = (Map<String, Object>) payload.get("subscription");
        String endpoint = (String) subMap.get("endpoint");

        @SuppressWarnings("unchecked")
        Map<String, String> keys = (Map<String, String>) subMap.get("keys");

        PushSubscription sub = new PushSubscription();
        sub.setEndpoint(endpoint);
        if (keys != null) {
            sub.setP256dh(keys.get("p256dh"));
            sub.setAuth(keys.get("auth"));
        }
        sub.setBuildingId((String) payload.get("buildingId"));
        if (payload.get("floor") instanceof Number num) {
            sub.setFloor(num.intValue());
        }
        sub.setRoomId((String) payload.get("roomId"));

        pushService.saveSubscription(sub);

        return Response.status(Response.Status.CREATED)
                .entity(Map.of("status", "SUBSCRIBED", "endpoint", endpoint))
                .build();
    }

    // 3. DELETE /api/push/subscriptions?endpoint=...
    @DELETE
    @Path("/subscriptions")
    public Response unsubscribe(@QueryParam("endpoint") String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "endpoint est obligatoire"))
                    .build();
        }

        pushService.deleteSubscription(endpoint);
        return Response.noContent().build();
    }
}