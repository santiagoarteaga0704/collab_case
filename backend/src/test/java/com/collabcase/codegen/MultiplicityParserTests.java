package com.collabcase.codegen;
import static org.assertj.core.api.Assertions.assertThat;import org.junit.jupiter.api.Test;import com.collabcase.uml.MultiplicityParser;
class MultiplicityParserTests{@Test void onlyUnlimitedUpperBoundIsMany(){var parser=new MultiplicityParser();assertThat(parser.isMany("*")).isTrue();assertThat(parser.isMany("0..*")).isTrue();assertThat(parser.isMany("1..*")).isTrue();assertThat(parser.isMany("1")).isFalse();assertThat(parser.isMany("0..1")).isFalse();assertThat(parser.isMany("1..1")).isFalse();}}
