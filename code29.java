package com.hackerai.supervisor.annotation;

import java.lang.annotation.*;

/**
 * Describes an agent method's purpose and invocation behavior.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentMethod {

    String description() default "";

    boolean requiresBuild() default false;

    boolean generatesArtifacts() default true;
}
