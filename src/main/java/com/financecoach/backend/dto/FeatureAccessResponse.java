package com.financecoach.backend.dto;

import lombok.Data;

@Data
public class FeatureAccessResponse {
    private boolean hasAccess;
    private boolean canUse;
    private Integer remainingUsage;
    private String message;

    public static FeatureAccessResponse granted(Integer remaining) {
        FeatureAccessResponse response = new FeatureAccessResponse();
        response.setHasAccess(true);
        response.setCanUse(true);
        response.setRemainingUsage(remaining);
        return response;
    }

    public static FeatureAccessResponse denied(String message) {
        FeatureAccessResponse response = new FeatureAccessResponse();
        response.setHasAccess(false);
        response.setCanUse(false);
        response.setMessage(message);
        return response;
    }

    public static FeatureAccessResponse limitExceeded(String message) {
        FeatureAccessResponse response = new FeatureAccessResponse();
        response.setHasAccess(true);
        response.setCanUse(false);
        response.setRemainingUsage(0);
        response.setMessage(message);
        return response;
    }
}