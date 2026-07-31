package com.hackerai.supervisor.annotation;

import java.lang.annotation.*;

/**
 * Marks a static method that builds the supervisor-level request
 * string injected into agent prompts.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SupervisorRequest {}
