package com.redis.poc.service;

import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for handling geospatial data using Redis.
 * This class leverages Redis's geospatial indexing capabilities to store
 * and query locations based on their longitude and latitude. It provides
 * methods to add locations and find locations within a given radius.
 */
@Service
public class GeospatialService {

    // The key under which all locations are stored in Redis.
    private static final String LOCATIONS_KEY = "locations";
    private final GeoOperations<String, String> geoOperations;

    /**
     * Constructs the GeospatialService.
     * @param redisTemplate The RedisTemplate to interact with Redis.
     */
    public GeospatialService(RedisTemplate<String, String> redisTemplate) {
        // opsForGeo() provides a convenient API for Redis GEO commands.
        this.geoOperations = redisTemplate.opsForGeo();
    }

    /**
     * Adds a location with its coordinates to the geospatial index in Redis.
     * This uses the GEOADD command.
     *
     * @param name The name of the location (e.g., "Eiffel Tower"). This will be the member in the geo set.
     * @param longitude The longitude of the location.
     * @param latitude The latitude of the location.
     */
    public void addLocation(String name, double longitude, double latitude) {
        geoOperations.add(LOCATIONS_KEY, new Point(longitude, latitude), name);
    }

    /**
     * Finds locations within a given radius of a central point.
     * This method uses the GEORADIUS command to query the geospatial index.
     *
     * @param longitude The longitude of the center point for the search.
     * @param latitude The latitude of the center point for the search.
     * @param radius The radius to search within.
     * @param metric The unit of distance for the radius (e.g., KILOMETERS, MILES).
     * @return A Set of location names within the specified radius. Returns an empty set if no locations are found.
     */
    public Set<String> findNearby(double longitude, double latitude, double radius, Metrics metric) {
        // Define the center point of the search area.
        Point center = new Point(longitude, latitude);
        // Define the search radius.
        Distance distance = new Distance(radius, metric);
        // Create a Circle representing the search area.
        Circle within = new Circle(center, distance);

        // Execute the GEORADIUS command to find points within the circle.
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoOperations.radius(LOCATIONS_KEY, within);

        // Extract the names of the locations from the results and return them as a Set.
        return results.getContent().stream()
                .map(GeoResult::getContent)
                .map(RedisGeoCommands.GeoLocation::getName)
                .collect(Collectors.toSet());
    }
}
