package com.collabcase.speech;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/speech")
public class SpeechController {
    private final SpeechToTextService service;

    public SpeechController(SpeechToTextService service) {
        this.service = service;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SpeechTranscriptionResponse transcribe(@RequestParam("file") MultipartFile file) {
        return service.transcribe(file);
    }
}
