package com.vigil.application.casework;

import java.util.UUID;

public record DiscoverObservedQueryResult(UUID caseId, boolean created) { }
