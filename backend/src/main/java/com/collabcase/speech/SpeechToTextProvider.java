package com.collabcase.speech;

public interface SpeechToTextProvider {
    SpeechTranscription transcribe(byte[] audio, String filename, String contentType);
}
