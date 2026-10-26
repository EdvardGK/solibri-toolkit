package no.skiplum.solibri.rules;

import java.util.Collection;
import java.util.Collections;

import com.solibri.geometry.linearalgebra.Vector2d;
import com.solibri.geometry.primitive2d.Polygon2d;
import com.solibri.smc.api.SMC;
import com.solibri.smc.api.checking.BooleanParameter;
import com.solibri.smc.api.checking.ConcurrentRule;
import com.solibri.smc.api.checking.ComponentSelector;
import com.solibri.smc.api.checking.DoubleParameter;
import com.solibri.smc.api.checking.FilterParameter;
import com.solibri.smc.api.checking.PreCheckResult;
import com.solibri.smc.api.checking.Result;
import com.solibri.smc.api.checking.ResultFactory;
import com.solibri.smc.api.checking.RuleParameters;
import com.solibri.smc.api.checking.StringParameter;
import com.solibri.smc.api.footprints.Footprint;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.PropertyType;

/**
 * Rule that flags components outside a specified building zone and/or
 * storey elevation range. Uses clash zone IfcSpace footprint polygons
 * for oriented containment testing.
 *
 * Configuration:
 *   - Zone name: which zone to scope to (e.g., "KvB_Zone")
 *   - Elevation filter: optional min/max Z range (from ARK storey definitions)
 *
 * Components OUTSIDE the specified scope are reported as results.
 */
public final class ZoneScopeRule extends ConcurrentRule {

    private final RuleParameters params = RuleParameters.of(this);

    private final StringParameter rpZoneName =
            params.createString("rpZoneName");

    private final BooleanParameter rpCheckElevation =
            params.createBoolean("rpCheckElevation");

    private final DoubleParameter rpMinElevation =
            params.createDouble("rpMinElevation", PropertyType.LENGTH);

    private final DoubleParameter rpMaxElevation =
            params.createDouble("rpMaxElevation", PropertyType.LENGTH);

    // Resolved zone data, set in preCheck
    private Polygon2d zonePolygon;
    private String zoneName;

    @Override
    public PreCheckResult preCheck(ComponentSelector components) {
        String targetName = rpZoneName.getValue();
        if (targetName == null || targetName.isEmpty()) {
            return PreCheckResult.createIrrelevant("No zone name specified.");
        }

        zoneName = targetName;
        zonePolygon = null;

        // Search all sub-models for a zone component matching the name
        for (com.solibri.smc.api.model.Model subModel : SMC.getModel().getSubModels()) {
            String modelName = subModel.getName();
            if (modelName == null || !modelName.toLowerCase().contains("zone")) {
                continue;
            }

            try {
                Collection<Component> allComponents = subModel.getComponents(
                        component -> true);

                for (Component c : allComponents) {
                    if (targetName.equals(c.getName())) {
                        Footprint footprint = c.getFootprint();
                        if (footprint != null) {
                            zonePolygon = footprint.getOutline();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                // Skip models that can't be iterated
            }

            if (zonePolygon != null) {
                break;
            }
        }

        if (zonePolygon == null) {
            return PreCheckResult.createIrrelevant(
                    "Zone '" + targetName + "' not found in any loaded model.");
        }

        return PreCheckResult.createRelevant();
    }

    @Override
    public Collection<Result> check(Component component, ResultFactory resultFactory) {
        if (zonePolygon == null) {
            return Collections.emptyList();
        }

        // Skip zone components themselves
        String name = component.getName();
        if (name != null && name.endsWith("_Zone")) {
            return Collections.emptyList();
        }

        // 2D containment test: center of bounding box vs zone polygon
        double cx = (component.getBoundingBox().getLowerBound().getX()
                + component.getBoundingBox().getUpperBound().getX()) / 2.0;
        double cy = (component.getBoundingBox().getLowerBound().getY()
                + component.getBoundingBox().getUpperBound().getY()) / 2.0;

        Vector2d center = Vector2d.create(cx, cy);

        if (!zonePolygon.contains(center)) {
            String compName = name != null ? name : "Unnamed";
            Result result = resultFactory.create(
                    "Outside zone: " + zoneName,
                    "Component '" + compName + "' is outside the " + zoneName + " zone.");
            return Collections.singleton(result);
        }

        // Elevation check (optional)
        if (rpCheckElevation.getValue()) {
            double minZ = rpMinElevation.getValue();
            double maxZ = rpMaxElevation.getValue();
            double bottomZ = component.getGlobalBottomElevation();

            if (bottomZ < minZ || bottomZ >= maxZ) {
                String compName = name != null ? name : "Unnamed";
                String storeyRange = String.format("%.0f - %.0f mm", minZ, maxZ);
                Result result = resultFactory.create(
                        "Outside elevation range",
                        "Component '" + compName + "' at elevation "
                                + String.format("%.0f", bottomZ)
                                + " mm is outside the specified range (" + storeyRange + ").");
                return Collections.singleton(result);
            }
        }

        return Collections.emptyList();
    }
}
