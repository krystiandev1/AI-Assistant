package com.example.cdq.config;

import com.example.cdq.chat.PromptInjectionException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

public class PromptGuardAdvisor implements BaseAdvisor {

    private static final List<Pattern> INJECTION_PATTERNS = List.of(
        Pattern.compile("ignore (?:\\w+ ){1,3}instructions", CASE_INSENSITIVE),
        Pattern.compile("forget (?:\\w+ ){1,3}instructions", CASE_INSENSITIVE),
        Pattern.compile("disregard (?:\\w+ ){1,3}instructions", CASE_INSENSITIVE),
        Pattern.compile("(override|overwrite) (the |your )?(system prompt|instructions|rules)", CASE_INSENSITIVE),
        Pattern.compile("new system prompt", CASE_INSENSITIVE),
        Pattern.compile("pretend (you are|to be|that you)", CASE_INSENSITIVE),
        Pattern.compile("jailbreak", CASE_INSENSITIVE),
        Pattern.compile("do anything now", CASE_INSENSITIVE),
        // Model-specific control tokens used in injection payloads
        Pattern.compile("\\[INST]", CASE_INSENSITIVE),
        Pattern.compile("<<SYS>>", CASE_INSENSITIVE),
        Pattern.compile("<\\|system\\|>", CASE_INSENSITIVE),
        Pattern.compile("<\\|im_start\\|>", CASE_INSENSITIVE),
        Pattern.compile("###\\s*system", CASE_INSENSITIVE),
        Pattern.compile("###\\s*instruction", CASE_INSENSITIVE)
    );

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String userText = request.prompt().getInstructions().stream()
            .filter(m -> m.getMessageType() == MessageType.USER)
            .map(m -> m.getText())
            .findFirst()
            .orElse("");

        if (containsInjection(userText)) {
            throw new PromptInjectionException();
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    public static boolean containsInjection(String text) {
        return INJECTION_PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }
}
