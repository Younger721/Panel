package com.panel.knowledge.application;

import java.util.List;

public record KbSearchResult(List<KbSearchHit> hits, KbSearchDebug debug) {
}
