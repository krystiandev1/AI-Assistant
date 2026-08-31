package com.example.cdq.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InputLanguageDetectorTest {

    private final InputLanguageDetector detector = new InputLanguageDetector();

    @Test
    void detects_polish() {
        assertThat(detector.detect("Czym jest CDQ Fraud Guard?")).isEqualTo("Polish");
        assertThat(detector.detect("Jaka jest stolica Niemiec?")).isEqualTo("Polish");
        assertThat(detector.detect("Co wiesz o Berlinie?")).isEqualTo("Polish");
        assertThat(detector.detect("Jaka jest aktualna temperatura w Monachium?")).isEqualTo("Polish");
        assertThat(detector.detect("Jaka jest aktualnie pogoda w stolicy niemiec?")).isEqualTo("Polish");
        assertThat(detector.detect("Jaka jest pogoda w warszawie teraz?")).isEqualTo("Polish");
        // Short diacritic-free query — caught by Polish function word fallback ("co", "to"):
        assertThat(detector.detect("co to cdq?")).isEqualTo("Polish");
    }

    @Test
    void null_and_blank_return_english() {
        assertThat(detector.detect(null)).isEqualTo("English");
        assertThat(detector.detect("")).isEqualTo("English");
        assertThat(detector.detect("   ")).isEqualTo("English");
    }

    @Test
    void unsupported_language_returns_unsupported() {
        // Icelandic — confirmed production edge case ("Varsjá" misrouted as Polish)
        assertThat(detector.detect("Hvernig er veðrið í Varsjá núna?"))
            .isEqualTo(InputLanguageDetector.UNSUPPORTED);
        // French
        assertThat(detector.detect("Quelle est la température actuelle à Berlin?"))
            .isEqualTo(InputLanguageDetector.UNSUPPORTED);
        // Czech (structurally close to Polish — must not be misclassified as Polish)
        assertThat(detector.detect("Jaká je aktuální teplota v Berlíně?"))
            .isEqualTo(InputLanguageDetector.UNSUPPORTED);
    }

    @Test
    void detects_german() {
        assertThat(detector.detect("Was ist CDQ Fraud Guard?")).isEqualTo("German");
        assertThat(detector.detect("Was ist die Hauptstadt von Deutschland?")).isEqualTo("German");
        assertThat(detector.detect("Was weißt du über Berlin?")).isEqualTo("German");
        assertThat(detector.detect("Wie ist die aktuelle Temperatur in München?")).isEqualTo("German");
    }

    @Test
    void detects_english() {
        assertThat(detector.detect("What is CDQ Fraud Guard?")).isEqualTo("English");
        assertThat(detector.detect("What is the capital of Germany?")).isEqualTo("English");
        assertThat(detector.detect("What do you know about Berlin?")).isEqualTo("English");
        assertThat(detector.detect("What is the current temperature in Munich?")).isEqualTo("English");
    }
}
