package com.csg.airtel.aaa4j.domain.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Entity representing a message template from MESSAGE_TEMPLATE table.
 * Used for various notification types including quota usage, expiry, credential updates, and throttling.
 */
@Getter
@Setter
public class MessageTemplate {

    private Long superTemplateId;
    private Long templateId;
    private String templateName;
    private String messageType;
    private Integer quotaPercentage;
    private String messageContent;


    /**
     * Convert to ThresholdGlobalTemplates for quota notification usage.
     * Only applicable for MESSAGE_TYPE = 'USAGE'.
     */
    public ThresholdGlobalTemplates toThresholdGlobalTemplates() {
        ThresholdGlobalTemplates template = new ThresholdGlobalTemplates();

        if (quotaPercentage != null) {
            template.setThreshold(quotaPercentage.longValue());
        }

        template.setMassage(messageContent);

        // Extract parameters from message content (e.g., {username}, {bucketId}, {availableQuota})
        template.setParams(extractTemplateParams(messageContent));

        return template;
    }

    /**
     * Extract parameter names from message template.
     * Finds all occurrences of {paramName} pattern.
     */
    private String[] extractTemplateParams(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }

        return java.util.regex.Pattern.compile("\\{([^}]+)\\}")
                .matcher(content)
                .results()
                .map(match -> match.group(1))
                .distinct()
                .toArray(String[]::new);
    }
}
