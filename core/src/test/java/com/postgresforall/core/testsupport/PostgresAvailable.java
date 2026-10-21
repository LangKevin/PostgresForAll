package com.postgresforall.core.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test that needs the configured live PostgreSQL server. Skipped (not
 * failed) when nothing answers on the config's host:port within ~1s.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
@ExtendWith(PostgresAvailableCondition.class)
public @interface PostgresAvailable {
}
