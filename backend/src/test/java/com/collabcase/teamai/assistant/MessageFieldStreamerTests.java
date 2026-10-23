package com.collabcase.teamai.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessageFieldStreamerTests {

    @Test
    void emitsMessageAsItArrives() {
        var s = new MessageFieldStreamer();
        assertThat(s.feed("{\"mens")).isEmpty();
        assertThat(s.feed("aje\":\"Ho")).isEqualTo("Ho");
        assertThat(s.feed("la Luis\",")).isEqualTo("la Luis");
        assertThat(s.feed("\"cambios\":{\"mensaje\":\"x\"}}")).isEmpty();
        assertThat(s.text()).isEqualTo("Hola Luis");
    }

    @Test
    void decodesEscapesSplitAcrossChunks() {
        var s = new MessageFieldStreamer();
        s.feed("{\"mensaje\":\"a\\");
        s.feed("\"b\"");
        assertThat(s.text()).isEqualTo("a\"b");

        var u = new MessageFieldStreamer();
        u.feed("{\"mensaje\":\"\\u00");
        u.feed("e1\\n\\t\\\\x\"}");
        assertThat(u.text()).isEqualTo("á\n\t\\x");
    }

    @Test
    void streamerToleratesTruncatedJson() {
        var s = new MessageFieldStreamer();
        s.feed("{\"mensaje\":\"Hola \\u00");
        assertThat(s.text()).isEqualTo("Hola ");
        var t = new MessageFieldStreamer();
        t.feed("{\"mens");
        assertThat(t.text()).isEmpty();
    }

    @Test
    void ignoresMensajeInsideOtherStrings() {
        var s = new MessageFieldStreamer();
        s.feed("{\"otro\":\"mensaje\",\"lista\":[{\"mensaje\":\"no\"}],\"x\":\"a\\\"mensaje\\\":\\\"b\","
                + "\"mensaje\":\"real\"}");
        assertThat(s.text()).isEqualTo("real");
    }
}
