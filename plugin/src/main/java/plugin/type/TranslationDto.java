package plugin.type;

import lombok.Data;

@Data
public class TranslationDto {
    private String translatedText;
    private DetectedLanguage detectedLanguage;

    @Data
    public static class DetectedLanguage {
        private double confidence;
        private String language;
    }
}
