package com.example.cdq.chat;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class ChatUiController {

    @GetMapping("/")
    String index() {
        return "index";
    }
}
