package com.example.aitmk.service.impl;

import com.example.aitmk.support.CrmRelationIds;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CrmManagedAgentIdsTest {
    private final ObjectMapper json = new ObjectMapper();

    static Stream<Arguments> textValues() {
        return Stream.of(
                Arguments.of(null, List.of()), Arguments.of("", List.of()),
                Arguments.of("[]", List.of()), Arguments.of("null", List.of()), Arguments.of("{}", List.of()),
                Arguments.of("id1,id2", List.of("id1","id2")),
                Arguments.of(" id1 ,, id2,id1,", List.of("id1","id2")),
                Arguments.of("[{\"sid\":\"id1\"},{\"sid\":\"id2\"}]", List.of("id1","id2")),
                Arguments.of("{\"rowid\":\"id1\"}", List.of("id1")),
                Arguments.of("{\"rowId\":\"id1\"}", List.of("id1")),
                Arguments.of("{\"id\":\"id1\"}", List.of("id1")),
                Arguments.of("{\"accountId\":\"id1\"}", List.of("id1")),
                Arguments.of("[{\"sid\":\"id1\",\"sourcevalue\":\"a,b,c,d\"},{\"sid\":\"id2\"}]", List.of("id1","id2")),
                Arguments.of("[{\"sid\":\"id1\"},broken]", List.of())
        );
    }

    @ParameterizedTest @MethodSource("textValues")
    void parsesEverySupportedTextFormWithoutProducingJsonFragments(String raw, List<String> expected) {
        assertThat(CrmRelationIds.parseText(raw)).isEqualTo(expected);
    }

    @Test void parsesAllRowsFromJsonNodeArrayInsteadOfOnlyTheFirst() throws Exception {
        var node=json.readTree("[{\"sid\":\"id1\"},{\"sid\":\"id2\"},{\"sid\":\"id1\"}]");
        assertThat(CrmRelationIds.parse(node)).containsExactly("id1","id2");
    }

    @Test void serializesNormalizedIdsForFullReplacement() {
        assertThat(CrmRelationIds.serialize(List.of(" id1 ","", "id2","id1"))).isEqualTo("id1,id2");
        assertThat(CrmRelationIds.serialize(List.of())).isEmpty();
    }
}
