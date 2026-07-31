package com.hackerai.supervisor.annotation;

import java.lang.annotation.*;

/**
 * Marks an interface as a supervisor agent that orchestrates
 * multiple sub-agents to fulfill coding/pentest requests.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SupervisorAgent {

    String description() default "";

    Class<?>[] subAgents() default {};

    int maxIterations() default 5;

    Class<?>[] parallelStages() default {};
}
