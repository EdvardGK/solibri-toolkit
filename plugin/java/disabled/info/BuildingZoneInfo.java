package no.skiplum.solibri.info;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.solibri.geometry.linearalgebra.Vector2d;
import com.solibri.geometry.primitive2d.Polygon2d;
import com.solibri.smc.api.SMC;
import com.solibri.smc.api.footprints.Footprint;
import com.solibri.smc.api.info.Information;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.PropertyType;

/**
 * ITO that returns the building zone name for each component.
 *
 * Auto-discovers zone IfcSpaces from any loaded model with "zone" in its name
 * (e.g., clash_zones). Uses the zone footprint polygons for oriented 2D
 * containment testing — no manual configuration needed.
 */
public class BuildingZoneInfo implements Information<String> {

    private volatile List<ZoneData> cachedZones;

    @Override
    public String getUniqueId() {
        return "skiplum-building-zone";
    }

    @Override
    public Optional<String> getInformation(Component component) {
        List<ZoneData> zones = getZones();
        if (zones.isEmpty()) {
            return Optional.empty();
        }

        // Use center of bounding box as the component's position
        double cx = (component.getBoundingBox().getLowerBound().getX()
                + component.getBoundingBox().getUpperBound().getX()) / 2.0;
        double cy = (component.getBoundingBox().getLowerBound().getY()
                + component.getBoundingBox().getUpperBound().getY()) / 2.0;

        Vector2d point = Vector2d.create(cx, cy);

        for (ZoneData zone : zones) {
            if (zone.polygon.contains(point)) {
                return Optional.of(zone.name);
            }
        }

        return Optional.of("Uteområde");
    }

    @Override
    public PropertyType getType() {
        return PropertyType.STRING;
    }

    private List<ZoneData> getZones() {
        if (cachedZones != null) {
            return cachedZones;
        }

        synchronized (this) {
            if (cachedZones != null) {
                return cachedZones;
            }

            List<ZoneData> zones = new ArrayList<>();

            // Find zone components by scanning models with "zone" in the name
            for (com.solibri.smc.api.model.Model subModel : SMC.getModel().getSubModels()) {
                String modelName = subModel.getName();
                if (modelName == null || !modelName.toLowerCase().contains("zone")) {
                    continue;
                }

                try {
                    Collection<Component> allComponents = subModel.getComponents(
                            component -> true);

                    for (Component c : allComponents) {
                        String name = c.getName();
                        if (name != null && name.endsWith("_Zone")) {
                            Footprint footprint = c.getFootprint();
                            if (footprint != null) {
                                Polygon2d outline = footprint.getOutline();
                                if (outline != null) {
                                    String zoneName = name.substring(0, name.length() - 5);
                                    zones.add(new ZoneData(zoneName, outline));
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // Model may not support component iteration
                }
            }

            cachedZones = zones;
            return zones;
        }
    }

    private static final class ZoneData {
        final String name;
        final Polygon2d polygon;

        ZoneData(String name, Polygon2d polygon) {
            this.name = name;
            this.polygon = polygon;
        }
    }
}
