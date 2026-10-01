# Engineering principles

## Never hide missing data with hard-coded values

- Do not embed domain data, reference data, environment settings, service endpoints, dataset versions, coordinates, rates or operational policy directly in application or sample code.
- Put shared domain and reference values in the project's authoritative data source, with provenance and regression coverage.
- Put deployment-specific values in configuration and caller-specific values in request inputs.
- Road distances in application and sample code must come from the built-in road-distance estimator or a configured road-network provider such as OSRM. Never use `putRoadRouteOverride`, `getRoadRouteOverrides`, `putRailRouteOverride`, `getRailRouteOverrides`, `SuppliedRoute`, or another fixed literal to force a desired route result.
- Samples must exercise the same public API and data-resolution path used by consumers. They must not contain private fallback values that make only the sample work.
- Named algorithm constants are acceptable only when they are intrinsic to the algorithm, centrally defined and documented. They must not disguise missing configuration or reference data.
