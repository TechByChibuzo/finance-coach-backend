package com.financecoach.backend.annotation;

import com.financecoach.backend.model.PlanTier;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPlan {
    PlanTier value();
    String feature() default "";  // Optional feature name
    String message() default "Upgrade to access this feature";
}
