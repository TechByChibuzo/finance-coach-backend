package com.financecoach.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring can only create a bean with several constructors if one is marked @Autowired
 * (or one has no arguments). Unit tests build beans by hand and can't catch this, so check
 * every component in the app without starting it.
 */
class BeanConstructorTest {

    @Test
    void everyBeanHasAConstructorSpringCanUse() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // also matches @Service, @RestController...

        List<String> problems = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.financecoach.backend")) {
            Class<?> type = Class.forName(definition.getBeanClassName());
            Constructor<?>[] constructors = type.getDeclaredConstructors();
            if (constructors.length <= 1) {
                continue;
            }

            long autowired = Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
            boolean hasNoArg = Arrays.stream(constructors).anyMatch(c -> c.getParameterCount() == 0);
            if (autowired != 1 && !hasNoArg) {
                problems.add(type.getSimpleName() + " has " + constructors.length
                        + " constructors and " + autowired + " marked @Autowired");
            }
        }

        assertThat(problems).isEmpty();
    }
}
