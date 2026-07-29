package com.hackerai.supervisor.annotation;

import java.lang.annotation.*;

/**
 * Injection key annotation. Marks a parameter as being pulled
 * from the shared ContextMap by its declared type.
 *
 * Example:
 *   @K(WorkingDirectory.class) String workingDirectory
 *
 * This tells the framework to look up the value stored under
 * the WorkingDirectory key in the context.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface K {
    Class<?> value();
}
