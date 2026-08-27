package com.example.cdq.chat;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String question) {}
