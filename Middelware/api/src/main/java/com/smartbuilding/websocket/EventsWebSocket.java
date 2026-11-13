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