package no.skiplum.solibri.info;

import java.util.Optional;

import com.solibri.smc.api.info.Information;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.PropertyType;

/**
 * Example ITO: calculates the middle elevation of a component.
 * Demonstrates how custom Information Takeoffs integrate with Solibri's ITO system.
 */
public class MiddleElevationInfo implements Information<Double> {

    @Override
    public String getUniqueId() {
        return "skiplum-middle-elevation";
    }

    @Override
    public Optional<Double> getInformation(Component component) {
        double top = component.getGlobalTopElevation();
        double bottom = component.getGlobalBottomElevation();
        return Optional.of((top + bottom) / 2.0);
    }

    @Override
    public PropertyType getType() {
        return PropertyType.LENGTH;
    }
}
