package com.example.cdq.chat;

import com.github.pemistahl.lingua.api.Language;
import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

import static com.github.pemistahl.lingua.api.Language.ENGLISH;
import static com.github.pemistahl.lingua.api.Language.GERMAN;
import static com.github.pemistahl.lingua.api.Language.POLISH;
import static com.github.pemistahl.lingua.api.Language.UNKNOWN;

@Component
class InputLanguageDetector {

    // Language-specific characters: always unambiguous.
    private static final Pattern POLISH_CHARS = Pattern.compile("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]");
    private static final Pattern GERMAN_CHARS  = Pattern.compile("[äöüÄÖÜß]");

    // German-only function words: copulas and negation absent from English.
    // Covers "Was ist CDQ Fraud Guard?" where English product words outvote
    // the German copula ("ist") in Lingua's n-gram scoring.
    private static final Pattern GERMAN_WORDS = Pattern.compile(
        "\\b(ist|sind|nicht)\\b", Pattern.CASE_INSENSITIVE);

    // Polish-only function words absent from English — catches diacritic-free short queries
    // like "co to cdq?" that fall below Lingua's minimum confidence threshold.
    // Deliberately excludes "to", "ten", "jest", "ta" — they are valid English words.
    private static final Pattern POLISH_WORDS = Pattern.compile(
        "\\b(co|czy|jak|gdzie|kiedy|kto|ile|jaki|jaka|jakie|nie)\\b",
        Pattern.CASE_INSENSITIVE);

    // Returned when the input is in a language other than the three supported ones.
    static final String UNSUPPORTED = "Unsupported";

    // Wide detector trained on all spoken languages. Returns the actual detected language
    // (e.g. ICELANDIC, FRENCH, CZECH) rather than forcing a nearest-match among 3 options,
    // which allows us to distinguish "unrecognised" from "genuinely unsupported".
    // UNKNOWN is still returned for very short or ambiguous text — handled by Priority 3.
    private final LanguageDetector detector = LanguageDetectorBuilder
        .fromAllSpokenLanguages()
        .build();

    String detect(String text) {
        if (text == null || text.isBlank()) return "English";

        // Priority 1: character-level markers are always unambiguous.
        if (POLISH_CHARS.matcher(text).find()) return "Polish";
        if (GERMAN_CHARS.matcher(text).find())  return "German";

        // Priority 2: wide Lingua model identifies the actual language.
        Language lang = detector.detectLanguageOf(text);
        if (lang == POLISH)  return "Polish";
        if (lang == GERMAN)  return "German";
        if (lang == ENGLISH) return "English";
        if (lang != UNKNOWN) return UNSUPPORTED;  // e.g. ICELANDIC, FRENCH, CZECH

        // Priority 3: Lingua below confidence threshold (UNKNOWN) — function word fallbacks
        // for short diacritic-free queries like "co to cdq?" or "Was ist das?".
        if (GERMAN_WORDS.matcher(text).find()) return "German";
        if (POLISH_WORDS.matcher(text).find()) return "Polish";

        return "English";
    }
}
