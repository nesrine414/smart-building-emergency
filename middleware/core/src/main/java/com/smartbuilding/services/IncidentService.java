
package com.smartbuilding.services;

import com.smartbuilding.entities.Incident;
import com.smartbuilding.events.IncidentChanged;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;

@ApplicationScoped
public class IncidentService {

    private MongoClient mongoClient;
    private MongoCollection<Document> collection;

    @Inject
    private Event<IncidentChanged> incidentChangedEvent;

    @PostConstruct
    public void init() {
        String uri = System.getenv("MONGODB_URI");

        if (uri == null || uri.isBlank()) {
            uri = "mongodb://localhost:27017";
        }

        mongoClient = MongoClients.create(uri);

        MongoDatabase database =
                mongoClient.getDatabase("smartbuilding");

        collection = database.getCollection("incidents");
    }

    @PreDestroy
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    public List<Incident> findAll(String status) {
        List<Incident> incidents = new ArrayList<>();

        var filter = (status != null && !status.isBlank())
                ? eq("status", status)
                : new Document();

        for (Document document :
                collection.find(filter).sort(descending("timestamp"))) {
            incidents.add(fromDocument(document));
        }

        return incidents;
    }

    public Optional<Incident> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }

        Document document = collection.find(eq("_id", id)).first();

        return Optional.ofNullable(
                document == null ? null : fromDocument(document)
        );
    }

    public Incident save(Incident incident) {
        boolean isNew =
                incident.getId() == null || incident.getId().isBlank();

        if (isNew) {
            incident.setId(UUID.randomUUID().toString());
            incident.setTimestamp(System.currentTimeMillis());
            incident.setUpdatedAt(incident.getTimestamp());

            if (incident.getStatus() == null
                    || incident.getStatus().isBlank()) {
                incident.setStatus("OPEN");
            }
        } else {
            incident.setUpdatedAt(System.currentTimeMillis());
        }

        Document document = toDocument(incident);

        collection.replaceOne(
                eq("_id", incident.getId()),
                document,
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );

        String changeType = isNew
                ? "CREATED"
                : ("RESOLVED".equalsIgnoreCase(incident.getStatus())
                    ? "RESOLVED" : "UPDATED");

        incidentChangedEvent.fire(
                new IncidentChanged(incident, changeType)
        );

        return incident;
    }

    public Optional<Incident> updateStatus(String id, String newStatus) {
        Optional<Incident> optional = findById(id);

        if (optional.isEmpty()) {
            return Optional.empty();
        }

        Incident incident = optional.get();
        incident.setStatus(newStatus);
        incident.setUpdatedAt(System.currentTimeMillis());

        Incident updated = saveWithoutEvent(incident);

        String changeType = "RESOLVED".equalsIgnoreCase(newStatus)
                ? "RESOLVED" : "UPDATED";

        incidentChangedEvent.fire(
                new IncidentChanged(updated, changeType)
        );

        return Optional.of(updated);
    }

    private Incident saveWithoutEvent(Incident incident) {
        collection.replaceOne(
                eq("_id", incident.getId()),
                toDocument(incident),
                new com.mongodb.client.model.ReplaceOptions().upsert(true)
        );

        return incident;
    }

    public Optional<Incident> findActiveIncident(
            String roomId, String type) {

        Document document = collection.find(
                new Document("roomId", roomId)
                        .append("type", type)
                        .append("status", "OPEN")
        ).sort(descending("timestamp")).first();

        return Optional.ofNullable(
                document == null ? null : fromDocument(document)
        );
    }

    private Document toDocument(Incident incident) {
        return new Document("_id", incident.getId())
                .append("buildingId", incident.getBuildingId())
                .append("roomId", incident.getRoomId())
                .append("sensorId", incident.getSensorId())
                .append("type", incident.getType())
                .append("severity", incident.getSeverity())
                .append("status", incident.getStatus())
                .append("description", incident.getDescription())
                .append("occupied", incident.isOccupied())
                .append("timestamp", incident.getTimestamp())
                .append("updatedAt", incident.getUpdatedAt())
                .append("affectedRoomIds", incident.getAffectedRoomIds())
                .append("recommendation", incident.getRecommendation());
    }

    private Incident fromDocument(Document document) {
        Incident incident = new Incident();

        incident.setId(document.getString("_id"));
        incident.setBuildingId(document.getString("buildingId"));
        incident.setRoomId(document.getString("roomId"));
        incident.setSensorId(document.getString("sensorId"));
        incident.setType(document.getString("type"));
        incident.setSeverity(document.getString("severity"));
        incident.setStatus(document.getString("status"));
        incident.setDescription(document.getString("description"));
        incident.setOccupied(
                Boolean.TRUE.equals(document.getBoolean("occupied"))
        );

        Number timestamp = document.get("timestamp", Number.class);
        Number updatedAt = document.get("updatedAt", Number.class);

        incident.setTimestamp(timestamp == null ? 0 : timestamp.longValue());
        incident.setUpdatedAt(updatedAt == null ? 0 : updatedAt.longValue());

        List<String> rooms = document.getList(
                "affectedRoomIds", String.class, new ArrayList<>()
        );

        incident.setAffectedRoomIds(rooms);
        incident.setRecommendation(document.getString("recommendation"));

        return incident;
    }
}
