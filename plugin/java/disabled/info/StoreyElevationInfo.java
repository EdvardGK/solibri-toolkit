package no.skiplum.solibri.info;

import java.util.Optional;

import com.solibri.smc.api.info.Information;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.PropertyType;

/**
 * ITO that assigns a storey label based on component elevation,
 * using ARK-defined storey boundaries (not Solibri's federated storeys).
 *
 * Pre-configured with Kystbyen Slemmestad Kvartal B storey elevations.
 * The storey boundaries are the ARK_Kv B IfcBuildingStorey elevations,
 * B-prefixed only (shared/site levels excluded).
 */
public class StoreyElevationInfo implements Information<String> {

    // ARK_Kv B storey boundaries (B-prefixed only, in mm)
    // Each entry: { min_elevation, max_elevation, "name" }
    private static final double[][] STOREY_BOUNDS = {
        {  3000,  7930 },  // B_Plan 01ab
        {  7930, 10990 },  // B_Plan 02ab
        { 10990, 12130 },  // B_Plan 03b
        { 12130, 14050 },  // B_Plan 03a
        { 14050, 15190 },  // B_Plan 04b
        { 15190, 17360 },  // B_Plan 04a
        { 17360, 18620 },  // B_Plan 05b
        { 18620, 20890 },  // B_Plan 05a
        { 20890, 21870 },  // B_Plan 06b
        { 21870, 24260 },  // B_Plan 06a
        { 24260, 25120 },  // B_Plan 07b
        { 25120, 28340 },  // B_Plan 07a
        { 28340, 28420 },  // B 08b
        { 28420, 38000 },  // B 08a
    };

    private static final String[] STOREY_NAMES = {
        "B_Plan 01ab",
        "B_Plan 02ab",
        "B_Plan 03b",
        "B_Plan 03a",
        "B_Plan 04b",
        "B_Plan 04a",
        "B_Plan 05b",
        "B_Plan 05a",
        "B_Plan 06b",
        "B_Plan 06a",
        "B_Plan 07b",
        "B_Plan 07a",
        "B 08b",
        "B 08a",
    };

    @Override
    public String getUniqueId() {
        return "skiplum-storey-elevation";
    }

    @Override
    public Optional<String> getInformation(Component component) {
        double elevation = component.getGlobalBottomElevation();

        // Check each storey range
        for (int i = 0; i < STOREY_BOUNDS.length; i++) {
            if (elevation >= STOREY_BOUNDS[i][0] && elevation < STOREY_BOUNDS[i][1]) {
                return Optional.of(STOREY_NAMES[i]);
            }
        }

        // Below or above the defined ranges
        if (elevation < STOREY_BOUNDS[0][0]) {
            return Optional.of("Under B (< " + (int) STOREY_BOUNDS[0][0] + " mm)");
        }

        return Optional.of("Over B (>= " + (int) STOREY_BOUNDS[STOREY_BOUNDS.length - 1][1] + " mm)");
    }

    @Override
    public PropertyType getType() {
        return PropertyType.STRING;
    }
}
