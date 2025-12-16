package com.financecoach.backend.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {
    String value();  // Feature name: "ai_coach", "export_reports", etc.
    String message() default "This feature is not available on your plan";
}
