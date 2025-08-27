package com.redis.poc.controller;

import com.redis.poc.service.GeospatialService;
import java.util.Set;
import org.springframework.data.geo.Metrics;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/geo")
public class GeospatialController {

    private final GeospatialService geospatialService;

    public GeospatialController(GeospatialService geospatialService) {
        this.geospatialService = geospatialService;
    }

    /**
     * Endpoint to add a new location to the geospatial index.
     * @param name The name of the location.
     * @param longitude The longitude.
     * @param latitude The latitude.
     * @return A confirmation message.
     */
    @PostMapping("/location")
    public String addLocation(
            @RequestParam String name, @RequestParam double longitude, @RequestParam double latitude) {
        geospatialService.addLocation(name, longitude, latitude);
        return "Location '" + name + "' added.";
    }

    /**
     * Endpoint to find locations within a given radius.
     * @param longitude The longitude of the search center.
     * @param latitude The latitude of the search center.
     * @param radius The radius to search within.
     * @return A Set of location names.
     */
    @GetMapping("/nearby")
    public Set<String> findNearby(
            @RequestParam double longitude, @RequestParam double latitude, @RequestParam double radius) {
        // Using KILOMETERS as the default metric for this example
        return geospatialService.findNearby(longitude, latitude, radius, Metrics.KILOMETERS);
    }
}
