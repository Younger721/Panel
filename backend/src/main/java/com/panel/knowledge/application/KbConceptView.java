package com.panel.knowledge.application;

import java.util.List;

public record KbConceptView(Long id, String name, String type, String description, List<String> aliases) {
}
