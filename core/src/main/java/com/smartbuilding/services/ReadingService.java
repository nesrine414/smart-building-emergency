
package com.smartbuilding.services;

import com.smartbuilding.entities.SensorReading;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ReadingService {

    private MongoClient mongoClient;
    private MongoCollection<Document> collection;

    @PostConstruct
    public void init() {
        mongoClient = MongoClients.create("mongodb://localhost:27017");
        MongoDatabase database = mongoClient.getDatabase("smartbuilding");
        Document infos = mongoClient.getDatabase("admin")
    .runCommand(new Document("hello", 1));

System.out.println("MONGO JAVA - SERVEUR : " + infos.toJson());
System.out.println("MONGO JAVA - BASE : " + database.getName());
        collection = database.getCollection("readings");

        System.out.println("MongoDB connecté : smartbuilding.readings");
    }

    @PreDestroy
    public void close() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }

    public SensorReading save(SensorReading reading) {
        if (reading.getId() == null || reading.getId().isBlank()) {
            reading.setId(UUID.randomUUID().toString());
        }

        if (reading.getTimestamp() <= 0) {
            reading.setTimestamp(System.currentTimeMillis());
        }

        Document document = new Document("_id", reading.getId())
                .append("sensorId", reading.getSensorId())
                .append("type", reading.getType())
                .append("timestamp", reading.getTimestamp())
                .append("value", reading.getValue())
                .append("unit", reading.getUnit());

System.out.println("TEST MONGODB : tentative d'enregistrement de " + reading.getSensorId());

        var result = collection.replaceOne(
                Filters.eq("_id", reading.getId()),
                document,
                new ReplaceOptions().upsert(true)
        );

        System.out.println("TEST MONGODB : matched = " + result.getMatchedCount());
        System.out.println("TEST MONGODB : modified = " + result.getModifiedCount());
        System.out.println("TEST MONGODB : upsertedId = " + result.getUpsertedId());

        System.out.println("TEST MONGODB : enregistrement terminé pour " + reading.getSensorId());

        return reading;

    }

    public List<SensorReading> findLatest(int limit, String sensorId) {
        int max = limit > 0 ? Math.min(limit, 500) : 50;

        var query = sensorId != null && !sensorId.isBlank()
                ? collection.find(Filters.eq("sensorId", sensorId))
                : collection.find();

        List<SensorReading> results = new ArrayList<>();

        for (Document document : query.sort(Sorts.descending("timestamp")).limit(max)) {
            results.add(toSensorReading(document));
        }

        return results;
    }

    public Optional<SensorReading> findById(String id) {
        Document document = collection.find(Filters.eq("_id", id)).first();

        return Optional.ofNullable(
                document == null ? null : toSensorReading(document)
        );
    }

    private SensorReading toSensorReading(Document document) {
        SensorReading reading = new SensorReading();

        reading.setId(document.getString("_id"));
        reading.setSensorId(document.getString("sensorId"));
        reading.setType(document.getString("type"));
        reading.setTimestamp(document.getLong("timestamp"));
        reading.setValue(((Number) document.get("value")).doubleValue());
        reading.setUnit(document.getString("unit"));

        return reading;
    }
}
