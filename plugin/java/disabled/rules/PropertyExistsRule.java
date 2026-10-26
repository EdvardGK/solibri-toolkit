package no.skiplum.solibri.rules;

import java.util.Collection;
import java.util.Collections;

import com.solibri.smc.api.checking.OneByOneRule;
import com.solibri.smc.api.checking.Result;
import com.solibri.smc.api.checking.ResultFactory;
import com.solibri.smc.api.checking.RuleParameters;
import com.solibri.smc.api.checking.StringParameter;
import com.solibri.smc.api.model.Component;
import com.solibri.smc.api.model.PropertyReference;
import com.solibri.smc.api.model.PropertySet;

/**
 * A rule that checks whether components have a required IFC property.
 * Designed to work with IfcOpenShell analysis output - the Python side
 * identifies which properties should exist, this rule enforces it in Solibri.
 */
public final class PropertyExistsRule extends OneByOneRule {

    private final RuleParameters params = RuleParameters.of(this);

    private final StringParameter psetName = params.createString("rpPropertySetName");
    private final StringParameter propName = params.createString("rpPropertyName");

    @Override
    public Collection<Result> check(Component component, ResultFactory resultFactory) {
        String pset = psetName.getValue();
        String prop = propName.getValue();

        if (pset == null || pset.isEmpty() || prop == null || prop.isEmpty()) {
            return Collections.emptyList();
        }

        // Search through property sets for the required property
        boolean found = false;
        for (PropertySet ps : component.getPropertySets(pset)) {
            if (ps.getProperty(prop).isPresent()) {
                found = true;
                break;
            }
        }

        if (!found) {
            String name = component.getName() != null ? component.getName() : "Unnamed";
            Result result = resultFactory.create(
                "Missing property: " + pset + "." + prop,
                "Component '" + name + "' is missing required property '" + prop
                    + "' in property set '" + pset + "'."
            );
            return Collections.singleton(result);
        }

        return Collections.emptyList();
    }
}
