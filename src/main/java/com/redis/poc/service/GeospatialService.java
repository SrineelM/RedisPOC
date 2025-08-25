package com.redis.poc.service;

import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GeospatialService {

    private static final String LOCATIONS_KEY = "locations";
    private final GeoOperations<String, String> geoOperations;

    public GeospatialService(RedisTemplate<String, String> redisTemplate) {
        this.geoOperations = redisTemplate.opsForGeo();
    }

    /**
     * Adds a location with its coordinates to the geospatial index.
     * @param name The name of the location (e.g., "Eiffel Tower").
     * @param longitude The longitude of the location.
     * @param latitude The latitude of the location.
     */
    public void addLocation(String name, double longitude, double latitude) {
        geoOperations.add(LOCATIONS_KEY, new Point(longitude, latitude), name);
    }

    /**
     * Finds locations within a given radius of a central point.
     * @param longitude The longitude of the center point.
     * @param latitude The latitude of the center point.
     * @param radius The radius to search within.
     * @param metric The unit of distance for the radius (e.g., KILOMETERS).
     * @return A Set of location names within the specified radius.
     */
    public Set<String> findNearby(double longitude, double latitude, double radius, Metrics metric) {
        Point center = new Point(longitude, latitude);
        Distance distance = new Distance(radius, metric);
        Circle within = new Circle(center, distance);

        // Use the geoOperations.radius() method to find points within the circle.
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoOperations.radius(LOCATIONS_KEY, within);

        // Extract the names of the locations from the results.
        return results.getContent().stream()
                .map(GeoResult::getContent)
                .map(RedisGeoCommands.GeoLocation::getName)
                .collect(Collectors.toSet());
    }
}
