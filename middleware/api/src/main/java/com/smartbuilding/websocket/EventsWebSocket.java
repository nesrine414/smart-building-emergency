package com.smartbuilding.websocket;

import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import java.util.logging.Level;
import java.util.logging.Logger;

@ServerEndpoint("/ws/events")
public class EventsWebSocket {

    private static final Logger LOG = Logger.getLogger(EventsWebSocket.class.getName());

    private EventBroadcaster getBroadcaster() {
        return CDI.current().select(EventBroadcaster.class).get();
    }

        @OnOpen
    public void onOpen(Session session) {
        // Extraire ?token= de la query string
        String query = session.getRequestURI().getQuery();
        String token = null;
        if (query != null && query.contains("token=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    token = param.substring("token=".length());
                    break;
                }
            }
        }

        // Valider le jeton avec JwtService
        com.smartbuilding.security.JwtService jwtService = 
            jakarta.enterprise.inject.spi.CDI.current().select(com.smartbuilding.security.JwtService.class).get();

        if (token == null || jwtService.validateToken(token).isEmpty()) {
            LOG.warning("Connexion WebSocket refusée : jeton JWT absent ou invalide.");
            try {
                session.close(new jakarta.websocket.CloseReason(
                    jakarta.websocket.CloseReason.CloseCodes.CANNOT_ACCEPT, 
                    "Jeton JWT invalide ou manquant"
                ));
            } catch (Exception ignored) {}
            return;
        }

        getBroadcaster().addSession(session);
    }

    @OnClose
    public void onClose(Session session) {
        getBroadcaster().removeSession(session);
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        LOG.log(Level.WARNING, "Erreur sur session WebSocket " + (session != null ? session.getId() : "inconnue"), throwable);
        if (session != null) {
            getBroadcaster().removeSession(session);
        }
    }
}