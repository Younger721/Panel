package com.panel.knowledge.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KbConceptTest {

    @Test
    void normalizeKeepsTechnicalSymbols() {
        assertThat(KbConcept.normalize("C++")).isEqualTo("c++");
        assertThat(KbConcept.normalize("C#")).isEqualTo("c#");
        assertThat(KbConcept.normalize("Redis 持久化")).isEqualTo("redis持久化");
        assertThat(KbConcept.normalize(" RDB（快照） ")).isEqualTo("rdb快照");
    }

    @Test
    void aliasesJsonEscapesQuotes() {
        assertThat(KbConcept.aliasesJson(List.of("Redis Database", "a\"b")))
                .isEqualTo("[\"Redis Database\",\"a\\\"b\"]");
        assertThat(KbConcept.aliasesJson(List.of())).isEqualTo("[]");
    }
}
