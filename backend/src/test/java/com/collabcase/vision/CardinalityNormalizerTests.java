package com.collabcase.vision;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardinalityNormalizerTests {
    @Test
    void turnsWordsForManyIntoAsterisk() {
        assertThat(CardinalityNormalizer.normalize("multi")).isEqualTo("*");
        assertThat(CardinalityNormalizer.normalize("multiplicity_star")).isEqualTo("*");
        assertThat(CardinalityNormalizer.normalize("Many")).isEqualTo("*");
        assertThat(CardinalityNormalizer.normalize("1..n")).isEqualTo("1..*");
        assertThat(CardinalityNormalizer.normalize("0..many")).isEqualTo("0..*");
    }

    @Test
    void keepsValidUmlValues() {
        assertThat(CardinalityNormalizer.normalize("1")).isEqualTo("1");
        assertThat(CardinalityNormalizer.normalize("0..1")).isEqualTo("0..1");
        assertThat(CardinalityNormalizer.normalize("*")).isEqualTo("*");
        assertThat(CardinalityNormalizer.normalize(null)).isNull();
    }
}
