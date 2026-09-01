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

    static final String UNSUPPORTED = "Unsupported";

    private static final Pattern POLISH_CHARS = Pattern.compile("[ąćęłńóśźżĄĆĘŁŃÓŚŹŻ]");
    private static final Pattern GERMAN_CHARS  = Pattern.compile("[äöüÄÖÜß]");

    // \b treats accented chars as \W — explicit lookarounds cover Latin Extended (U+00C0–U+024F)
    private static final String WB = "(?<![a-zA-ZÀ-ɏ])";
    private static final String WE = "(?![a-zA-ZÀ-ɏ])";

    private static final Pattern GERMAN_WORDS = Pattern.compile(
        WB + "(ist|sind|nicht)" + WE, Pattern.CASE_INSENSITIVE);
    private static final Pattern POLISH_WORDS = Pattern.compile(
        WB + "(co|czy|jak|gdzie|kiedy|kto|ile|jaki|jaka|jakie|nie)" + WE,
        Pattern.CASE_INSENSITIVE);

    private final LanguageDetector lingua = LanguageDetectorBuilder
        .fromAllSpokenLanguages()
        .build();

    String detect(String text) {
        if (text == null || text.isBlank()) return "English";

        String byChars = detectByCharacters(text);
        if (byChars != null) return byChars;

        String byWords = detectByFunctionWords(text);
        if (byWords != null) return byWords;

        return detectByLingua(text);
    }

    private String detectByCharacters(String text) {
        if (POLISH_CHARS.matcher(text).find()) return "Polish";
        if (GERMAN_CHARS.matcher(text).find())  return "German";
        return null;
    }

    private String detectByFunctionWords(String text) {
        if (GERMAN_WORDS.matcher(text).find()) return "German";
        if (POLISH_WORDS.matcher(text).find()) return "Polish";
        return null;
    }

    private String detectByLingua(String text) {
        Language lang = lingua.detectLanguageOf(text);
        if (lang == POLISH)  return "Polish";
        if (lang == GERMAN)  return "German";
        if (lang != UNKNOWN && lang != ENGLISH) return UNSUPPORTED;
        return "English";
    }
}
